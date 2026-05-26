package com.example.myapplication.uwb

import android.content.Context
import android.util.Log
import androidx.core.uwb.RangingParameters
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbAddress
import androidx.core.uwb.UwbComplexChannel
import androidx.core.uwb.UwbControllerSessionScope
import androidx.core.uwb.UwbDevice
import androidx.core.uwb.UwbManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 교수 측 UWB Ranging Manager (Controller 역할) — Option A + fail-recovery.
 *
 * 생명주기:
 *   - 사이클 시작: [openScope] → 새 localAddress 받음
 *   - [setParams] — 수업 동안 1번 (sessionId/sessionKey/channel/preambleIdx 불변)
 *   - 첫 학생: [startMulticastSession] (prepareSession + flow collect 시작)
 *   - 둘째+: [addControleeAsync] (성공 시 prev [removeControleeAsync])
 *   - 학생별 측정 대기: [awaitMeasurement] (peer 주소로 매칭)
 *   - 사이클 끝 또는 fail-recovery: [closeScope]
 *   - 수업 종료: [stop]
 *
 * alpha API 제약:
 *   - prepareSession은 scope당 1번 → 매 사이클(또는 sub-cycle) 새 scope 필수
 *   - controlee 0개 도달 시 session 자동 종료 → add-before-remove swap 필요
 *   - 응답 없는 peer로 인한 RangingResultFailure 후 다른 활성 peer 없으면 session "add 거부" 상태 진입
 *     → fail-recovery는 closeScope+openScope으로
 */
class ProfessorUwbRangingManager(appContext: Context) {

    data class UwbParams(
        val sessionId: Int,
        val sessionKey: ByteArray,
        val channel: Int,
        val preambleIndex: Int,
    )

    /** awaitMeasurement 결과 콜백. */
    interface OnRangingResultListener {
        fun onResult(distance: Double?, success: Boolean, peerMatched: Boolean)
    }

    /** openScope 결과 콜백. */
    interface StartCallback {
        fun onScopeOpened(localAddressHex: String)
        fun onScopeFailed(reason: String)
    }

    /** addControlee / removeControlee 결과 콜백. */
    interface SimpleCallback {
        fun onSuccess()
        fun onFailure(reason: String)
    }

    // ─── 내부 상태 ────────────────────────────────────────────
    private val appCtx = appContext.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val uwbManager = UwbManager.createInstance(appCtx)
    private var controllerScope: UwbControllerSessionScope? = null
    private var params: UwbParams? = null

    private var multicastCollectJob: Job? = null
    private val pendingMeasurements = ConcurrentHashMap<String, OnRangingResultListener>()

    // ─── 공개 API ─────────────────────────────────────────────

    /** 사이클(또는 sub-cycle) 시작에 호출. 기존 scope/job 정리 후 새 controllerScope open. */
    fun openScope(cb: StartCallback) {
        scope.launch {
            multicastCollectJob?.cancel()
            multicastCollectJob = null
            pendingMeasurements.clear()
            controllerScope = null

            try {
                controllerScope = uwbManager.controllerSessionScope()
                val addr = bytesToHex(controllerScope!!.localAddress.address)
                Log.d(TAG, "openScope: localAddr=$addr")
                cb.onScopeOpened(addr)
            } catch (t: Throwable) {
                Log.e(TAG, "openScope 실패", t)
                cb.onScopeFailed(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /** 사이클 끝/fail-recovery 시 호출. 진행 중 collect 취소 + scope 폐기. */
    fun closeScope() {
        multicastCollectJob?.cancel()
        multicastCollectJob = null
        pendingMeasurements.clear()
        controllerScope = null
        Log.d(TAG, "closeScope")
    }

    /** 수업 동안 1번. sessionId/sessionKey/channel/preambleIdx 주입. */
    fun setParams(params: UwbParams) {
        this.params = params
        Log.d(TAG, "params set (sessionId=${params.sessionId})")
    }

    /**
     * 특정 peer의 다음 측정을 [listener]로 받음. [timeoutMs] 후 미수신 시 fail 통보.
     * race-free하게 [startMulticastSession]/[addControleeAsync] 전에 호출 권장.
     */
    fun awaitMeasurement(peerHex: String, timeoutMs: Long, listener: OnRangingResultListener) {
        val key = peerHex.uppercase()
        pendingMeasurements[key] = listener
        scope.launch {
            delay(timeoutMs)
            val pending = pendingMeasurements.remove(key)
            if (pending != null) {
                Log.w(TAG, "awaitMeasurement 타임아웃: $peerHex")
                pending.onResult(null, false, false)
            }
        }
    }

    /**
     * 사이클(또는 sub-cycle)의 첫 학생만 호출.
     * MULTICAST_DS_TWR로 prepareSession + flow collect를 백그라운드에서 시작.
     * 측정 결과는 [pendingMeasurements]의 listener로 dispatch.
     */
    fun startMulticastSession(initialPeerHex: String) {
        val cs = controllerScope
        if (cs == null) {
            Log.w(TAG, "startMulticastSession: scope 미준비")
            pendingMeasurements.remove(initialPeerHex.uppercase())?.onResult(null, false, false)
            return
        }
        val p = params
        if (p == null) {
            Log.w(TAG, "startMulticastSession: params 미준비")
            pendingMeasurements.remove(initialPeerHex.uppercase())?.onResult(null, false, false)
            return
        }
        multicastCollectJob?.cancel()
        multicastCollectJob = scope.launch {
            try {
                val rangingParams = buildMulticastParameters(p, listOf(initialPeerHex))
                cs.prepareSession(rangingParams)
                    .onStart { Log.v(TAG, "multicast flow collect 시작") }
                    .onEach { evt ->
                        if (evt is RangingResult.RangingResultFailure) {
                            Log.w(TAG, "RangingResultFailure")
                        }
                    }
                    .collect { result ->
                        if (result is RangingResult.RangingResultPosition) {
                            val peerHex = bytesToHex(result.device.address.address).uppercase()
                            val dist = result.position.distance?.value?.toDouble()
                            val cb = pendingMeasurements.remove(peerHex)
                            if (cb != null) {
                                Log.d(TAG, "측정 매칭 peer=$peerHex dist=$dist")
                                cb.onResult(dist, dist != null, true)
                            }
                        }
                    }
                Log.d(TAG, "multicast flow 자연 종료")
            } catch (t: CancellationException) {
                Log.v(TAG, "multicast collect 취소 (정상)")
                throw t
            } catch (t: Throwable) {
                Log.e(TAG, "multicast collect 예외", t)
            }
        }
    }

    /** 둘째 학생부터 호출. 활성 multicast session에 controlee 동적 추가. */
    fun addControleeAsync(peerHex: String, cb: SimpleCallback) {
        scope.launch {
            try {
                val cs = controllerScope ?: throw IllegalStateException("scope null")
                cs.addControlee(UwbAddress(hexToBytes(peerHex)))
                Log.d(TAG, "addControlee 성공: $peerHex")
                cb.onSuccess()
            } catch (t: Throwable) {
                Log.w(TAG, "addControlee 실패 $peerHex: ${t.message}")
                cb.onFailure(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /** 측정 끝난 prev peer 제거 (fire-and-forget). */
    fun removeControleeAsync(peerHex: String, cb: SimpleCallback) {
        scope.launch {
            try {
                val cs = controllerScope ?: throw IllegalStateException("scope null")
                cs.removeControlee(UwbAddress(hexToBytes(peerHex)))
                Log.d(TAG, "removeControlee 성공: $peerHex")
                cb.onSuccess()
            } catch (t: Throwable) {
                Log.w(TAG, "removeControlee 실패 $peerHex: ${t.message}")
                cb.onFailure(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /** 수업 종료. */
    fun stop() {
        multicastCollectJob?.cancel()
        multicastCollectJob = null
        pendingMeasurements.clear()
        scope.cancel()
        controllerScope = null
        params = null
        Log.d(TAG, "stopped")
    }

    // ─── private ──────────────────────────────────────────────

    private fun buildMulticastParameters(p: UwbParams, peerHexes: List<String>): RangingParameters {
        val peers = peerHexes.map { UwbDevice(UwbAddress(hexToBytes(it))) }
        return RangingParameters(
            uwbConfigType = RangingParameters.CONFIG_MULTICAST_DS_TWR,
            sessionId = p.sessionId,
            subSessionId = 0,
            sessionKeyInfo = p.sessionKey,
            subSessionKeyInfo = null,
            complexChannel = UwbComplexChannel(p.channel, p.preambleIndex),
            peerDevices = peers,
            updateRateType = RangingParameters.RANGING_UPDATE_RATE_FREQUENT,
        )
    }

    private fun bytesToHex(bytes: ByteArray): String =
        "0x" + bytes.joinToString("") { "%02X".format(it) }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.removePrefix("0x").removePrefix("0X")
        return ByteArray(clean.length / 2) {
            clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    companion object {
        private const val TAG = "ProfUwbMgr"
    }
}
