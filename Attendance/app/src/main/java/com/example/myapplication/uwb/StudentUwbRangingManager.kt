package com.example.myapplication.uwb

import android.content.Context
import android.util.Log
import androidx.core.uwb.RangingParameters
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbAddress
import androidx.core.uwb.UwbComplexChannel
import androidx.core.uwb.UwbControleeSessionScope
import androidx.core.uwb.UwbDevice
import androidx.core.uwb.UwbManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/**
 * 학생 측 UWB Ranging Manager (Controllee 역할) — Option A.
 *
 * 생명주기:
 *   - check-in 응답: [setParams] 1번 (sessionId/sessionKey/channel/preambleIdx)
 *   - PREPARE 받을 때마다:
 *       [openScope] → 새 localAddress
 *       [setControllerHex] (PREPARE 페이로드의 controllerAddress)
 *       [armSessionMulticast] → onArmed 시 READY emit
 *   - DONE 받을 때마다: [disarmSession] + [closeScope]
 *   - 수업 종료: [stop]
 */
class StudentUwbRangingManager(appContext: Context) {

    data class UwbParams(
        val sessionId: Int,
        val sessionKey: ByteArray,
        val channel: Int,
        val preambleIndex: Int,
    )

    interface ArmCallback {
        fun onArmed()
        fun onArmFailed(reason: String)
    }

    interface StartCallback {
        fun onScopeOpened(localAddressHex: String)
        fun onScopeFailed(reason: String)
    }

    // ─── 내부 상태 ────────────────────────────────────────────
    private val appCtx = appContext.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val uwbManager = UwbManager.createInstance(appCtx)
    private var controleeScope: UwbControleeSessionScope? = null
    private var params: UwbParams? = null
    private var controllerHex: String? = null
    private var activeRangingJob: Job? = null

    // ─── 공개 API ─────────────────────────────────────────────

    /** PREPARE 받을 때마다 호출. 기존 scope/job 정리 후 새 controleeScope open. */
    fun openScope(cb: StartCallback) {
        scope.launch {
            activeRangingJob?.cancel()
            activeRangingJob = null
            controleeScope = null

            try {
                controleeScope = uwbManager.controleeSessionScope()
                val addr = bytesToHex(controleeScope!!.localAddress.address)
                Log.d(TAG, "openScope: localAddr=$addr")
                cb.onScopeOpened(addr)
            } catch (t: Throwable) {
                Log.e(TAG, "openScope 실패", t)
                cb.onScopeFailed(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /** DONE 받을 때마다 호출. scope 폐기. */
    fun closeScope() {
        activeRangingJob?.cancel()
        activeRangingJob = null
        controleeScope = null
        Log.d(TAG, "closeScope")
    }

    /** check-in 응답 후 1번. sessionId/sessionKey/channel/preambleIdx 주입. */
    fun setParams(params: UwbParams) {
        this.params = params
        Log.d(TAG, "params set (sessionId=${params.sessionId})")
    }

    /** PREPARE 페이로드의 controllerAddress 주입. 사이클마다 바뀜. */
    fun setControllerHex(hex: String) {
        this.controllerHex = hex
        Log.d(TAG, "controllerHex set=$hex")
    }

    /**
     * MULTICAST_DS_TWR로 RangingSession open + collect.
     * onStart에 cb.onArmed 호출 — 호출자는 그 시점에 READY emit.
     */
    fun armSessionMulticast(cb: ArmCallback) {
        activeRangingJob?.cancel()
        activeRangingJob = scope.launch {
            try {
                val cs = controleeScope
                    ?: throw IllegalStateException("controleeScope not open")
                val p = params
                    ?: throw IllegalStateException("params not set")
                val ctrlHex = controllerHex
                    ?: throw IllegalStateException("controllerHex not set")

                val rangingParams = buildMulticastParameters(p, ctrlHex)
                val flow = cs.prepareSession(rangingParams)

                flow.onStart {
                    cb.onArmed()
                    Log.v(TAG, "armed (collect started)")
                }.onEach { evt ->
                    if (evt is RangingResult.RangingResultFailure) {
                        Log.w(TAG, "RangingResultFailure")
                    }
                }.collect { /* 학생은 측정값 사용 안 함 */ }

                Log.v(TAG, "multicast flow 자연 종료")
            } catch (t: CancellationException) {
                Log.v(TAG, "armSessionMulticast 취소 (정상 disarm)")
                throw t
            } catch (t: Throwable) {
                Log.e(TAG, "armSessionMulticast 실패", t)
                cb.onArmFailed(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /** DONE 받았을 때 호출. RangingSession close. ([closeScope]가 이걸 포함하지만 별도 호출 가능.) */
    fun disarmSession() {
        activeRangingJob?.cancel()
        activeRangingJob = null
        Log.v(TAG, "disarmed")
    }

    /** 수업 종료. */
    fun stop() {
        scope.cancel()
        controleeScope = null
        params = null
        controllerHex = null
        activeRangingJob = null
        Log.d(TAG, "stopped")
    }

    // ─── private ──────────────────────────────────────────────

    private fun buildMulticastParameters(p: UwbParams, ctrlHex: String): RangingParameters {
        val controller = UwbDevice(UwbAddress(hexToBytes(ctrlHex)))
        return RangingParameters(
            uwbConfigType = RangingParameters.CONFIG_MULTICAST_DS_TWR,
            sessionId = p.sessionId,
            subSessionId = 0,
            sessionKeyInfo = p.sessionKey,
            subSessionKeyInfo = null,
            complexChannel = UwbComplexChannel(p.channel, p.preambleIndex),
            peerDevices = listOf(controller),
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
        private const val TAG = "StuUwbMgr"
    }
}
