package com.example.myapplication.attendance;

import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.util.Log;

import com.example.myapplication.ble.BleScanManager;
import com.example.myapplication.network.ApiResponse;
import com.example.myapplication.network.AttendanceApi;
import com.example.myapplication.network.CheckInData;
import com.example.myapplication.network.CheckInRequest;
import com.example.myapplication.network.RetrofitClient;
import com.example.myapplication.network.UwbParamsAdapter;
import com.example.myapplication.network.socket.AttendanceSocketClient;
import com.example.myapplication.schedule.notification.AttendanceWarningNotifier;
import com.example.myapplication.uwb.StudentUwbRangingManager;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 학생 출석 전체 흐름.
 *
 * 책임:
 *   1. BLE 스캔 시작/중단
 *   2. sessionCode 감지 시 서버에 check-in 요청 (placeholder UWB 주소)
 *   3. 성공 응답 시: setParams + Socket connect + 스캔 중단
 *   4. Socket 이벤트:
 *      - PREPARE(ctrlHex) → openScope → setControllerHex → armSessionMulticast → emitReady(myAddr)
 *      - DONE              → disarmSession + closeScope (다음 PREPARE 대기)
 *      - DONE(status=ABSENT) → 추가로 통보 + Service 종료
 *   5. stop(): 멱등
 *
 * Option A 패턴: 측정마다 새 controleeScope (alpha API의 prepareSession single-use 제약).
 * 학생 측 UWB 주소는 ephemeral — check-in에 보내는 placeholder는 사용 안 되고, 매 PREPARE-READY로
 * 새 주소를 동적 교환.
 */
public class AttendanceController {

    private static final String TAG = "AttendanceController";

    /** check-in에 보내는 placeholder. 실제 ranging엔 매 PREPARE에서 동적 주소 사용. */
    private static final String STUDENT_ADDR_PLACEHOLDER = "0xAAAA";

    private final BleScanManager scanManager;
    private final AttendanceApi api;
    private final StudentUwbRangingManager uwbManager;
    private final AttendanceSocketClient socketClient;
    private final String studentId;
    private final Context context;

    private OnAttendanceListener listener;

    private volatile boolean confirmed = false;
    private volatile boolean stopped = false;

    public AttendanceController(Context context, String studentId) {
        this.scanManager = new BleScanManager(context);
        this.api = RetrofitClient.getInstance().create(AttendanceApi.class);
        this.uwbManager = new StudentUwbRangingManager(context);
        this.socketClient = new AttendanceSocketClient(AttendanceSocketClient.Role.STUDENT);
        this.studentId = studentId;
        this.context = context.getApplicationContext();

        this.scanManager.setListener(new BleScanManager.OnScanListener() {
            @Override
            public void onSessionCodeFound(String sessionCode, ScanResult result) {
                Log.d(TAG, "sessionCode 감지: " + sessionCode + " → 서버 확인 요청");
                verifyAndCheckIn(sessionCode);
            }

            @Override
            public void onScanFailed(String reason) {
                Log.e(TAG, "스캔 실패: " + reason);
                notifyFailed("스캔 실패: " + reason);
            }

            @Override
            public void onScanTimeout() {
                // 스캔 실패해도 출석 자체를 실패시키지 않음 — 학생이 PIN 수동 입력으로
                // 출석할 수 있어야 하므로 Service를 유지. (BleScanManager가 스캔은 이미 중단함)
                Log.w(TAG, "스캔 타임아웃 (교수 신호 못 찾음) — PIN 수동 입력 대기");
            }
        });

        this.socketClient.setListener(new AttendanceSocketClient.OnSocketListener() {
            @Override
            public void onConnected() { Log.d(TAG, "socket connected"); }

            @Override
            public void onDisconnected() { Log.d(TAG, "socket disconnected"); }

            @Override
            public void onConnectError(String reason) {
                Log.w(TAG, "socket connect error: " + reason);
            }

            @Override
            public void onPrepare(String attendanceId, String sId, String controllerHex) {
                Log.d(TAG, "PREPARE 수신 (attId=" + attendanceId + " ctrl=" + controllerHex + ")");
                handlePrepare(attendanceId, sId, controllerHex);
            }

            @Override
            public void onDone(String attendanceId, String status) {
                Log.d(TAG, "DONE 수신 (attId=" + attendanceId + " status=" + status + ")");
                uwbManager.disarmSession();
                uwbManager.closeScope();
                if ("ABSENT".equals(status)) {
                    Log.w(TAG, "ABSENT 전이됨 — 사용자 통보 + Service 종료 요청");
                    notifyAbsent(attendanceId);
                }
            }
        });
    }

    public void setListener(OnAttendanceListener listener) {
        this.listener = listener;
    }

    /** 출석 시작 — BLE 스캔만 시작. UWB scope는 매 PREPARE 받을 때 동적으로 open. */
    public void start() {
        Log.d(TAG, "출석 시작 — BLE 스캔");
        scanManager.startScan();
    }

    /**
     * 수동 PIN 입력 진입점. BLE를 못 잡거나 5분 이후 들어온 학생이 교수 화면의
     * PIN을 직접 입력했을 때 호출. BLE 스캔 경로와 동일한 check-in 로직을 재사용.
     * 이미 출석 확정/중단 상태면 무시 (confirmed 플래그 + 서버 멱등으로 중복 안전).
     */
    public void checkInWithCode(String code) {
        if (stopped || confirmed) {
            Log.d(TAG, "checkInWithCode 무시 (stopped=" + stopped + " confirmed=" + confirmed + ")");
            return;
        }
        if (code == null || code.isEmpty()) {
            Log.w(TAG, "checkInWithCode: 빈 코드 무시");
            return;
        }
        Log.d(TAG, "수동 PIN 입력 출석 시도: " + code);
        verifyAndCheckIn(code);
    }

    /**
     * BT 상태 변화 통보. Service의 BroadcastReceiver가 STATE_ON/OFF 받았을 때 호출.
     *   - enabled=true : scanner 재획득 (BLE 단계 지났어도 무해). 실패 시 fail로 escalate.
     *   - enabled=false: 로그만. 진행 중 ranging은 PREPARE/READY/측정 timeout으로 자연 fail.
     */
    public void onBluetoothStateChanged(boolean enabled) {
        if (stopped) return;
        if (enabled) {
            Log.d(TAG, "BT_STATE_ON — scanner 재획득 시도");
            boolean ok = scanManager.reinit();
            if (!ok) {
                Log.e(TAG, "scanManager.reinit 실패 — 출석 종료");
                notifyFailed("Bluetooth 어댑터 재획득 실패");
            }
        } else {
            Log.w(TAG, "BT_STATE_OFF — 진행 중 동작은 자연 fail 처리, 다음 동작은 STATE_ON 후");
        }
    }

    /** 수동 중단. 멱등. */
    public void stop() {
        if (stopped) return;
        stopped = true;
        Log.d(TAG, "출석 중단");
        scanManager.stopScan();
        uwbManager.stop();
        socketClient.disconnect();
    }

    // ── socket handler ─────────────────────────────────────

    private void handlePrepare(String attId, String sId, String ctrlHex) {
        if (stopped) return;
        uwbManager.openScope(new StudentUwbRangingManager.StartCallback() {
            @Override
            public void onScopeOpened(String myAddr) {
                if (stopped) return;
                uwbManager.setControllerHex(ctrlHex);
                uwbManager.armSessionMulticast(new StudentUwbRangingManager.ArmCallback() {
                    @Override
                    public void onArmed() {
                        if (stopped) return;
                        socketClient.emitReady(attId, sId, myAddr);
                    }

                    @Override
                    public void onArmFailed(String reason) {
                        Log.w(TAG, "armSessionMulticast 실패: " + reason
                                + " — READY 안 보냄 (교수 측 timeout으로 처리됨)");
                        uwbManager.closeScope();
                    }
                });
            }

            @Override
            public void onScopeFailed(String reason) {
                Log.e(TAG, "openScope 실패: " + reason);
                AttendanceWarningNotifier.notifyUwbFailed(context);
                notifyFailed("UWB 연결 실패 (기기의 UWB 설정 확인 요망)");
            }
        });
    }

    // ── BLE check-in ───────────────────────────────────────

    private void verifyAndCheckIn(String sessionCode) {
        CheckInRequest request =
                new CheckInRequest(sessionCode, studentId, STUDENT_ADDR_PLACEHOLDER);

        api.checkIn(request).enqueue(new Callback<ApiResponse<CheckInData>>() {
            @Override
            public void onResponse(Call<ApiResponse<CheckInData>> call,
                                   Response<ApiResponse<CheckInData>> response) {

                if (!response.isSuccessful()) {
                    // 비성공은 모두 스캔 유지 (다음 광고 또는 PIN 입력 대기).
                    //   404: 잘못된 sessionCode (이미 마감/타 강의 신호)
                    //   403: 수강 안 한 수업 — 다른 강의실 BLE 신호가 잡힌 경우.
                    //        Service 안 죽이고 스캔 계속하면 진짜 듣는 수업 광고 잡을 기회 살아있음.
                    //        (PIN 수동 입력 시 사용자 알림은 후속 작업에서 별도 broadcast로 처리)
                    // notifyFailed는 stopSelf까지 부르니까 여기선 호출 X.
                    if (response.code() == 403) {
                        Log.w(TAG, "수강 안 한 수업 (403) → 무시하고 스캔 유지: " + sessionCode);
                    } else {
                        Log.d(TAG, "check-in 비성공 (" + response.code() + ") → 스캔 유지");
                    }
                    return;
                }

                ApiResponse<CheckInData> body = response.body();
                if (body == null || !body.isSuccess() || body.getData() == null) {
                    Log.d(TAG, "check-in 실패 응답 → 스캔 유지: "
                            + (body != null ? body.getMessage() : "body null"));
                    return;
                }

                if (confirmed) {
                    Log.d(TAG, "이미 confirmed — 중복 응답 무시");
                    return;
                }
                confirmed = true;

                CheckInData data = body.getData();
                Log.d(TAG, "출석 확정: " + sessionCode);

                uwbManager.setParams(UwbParamsAdapter.toStudent(data.getUwbParams()));
                // controllerHex는 매 PREPARE에서 갱신. 응답의 controllerAddress는 stale이라 무시.
                socketClient.connect(studentId, data.getLectureSessionId());
                scanManager.stopScan();
                notifyConfirmed(sessionCode, data);
            }

            @Override
            public void onFailure(Call<ApiResponse<CheckInData>> call, Throwable t) {
                Log.e(TAG, "check-in 네트워크 실패 → 스캔 유지", t);
            }
        });
    }

    private void notifyConfirmed(String sessionCode, CheckInData data) {
        if (listener != null) listener.onAttendanceConfirmed(sessionCode, data);
    }

    private void notifyFailed(String reason) {
        if (listener != null) listener.onAttendanceFailed(reason);
    }

    private void notifyAbsent(String attendanceId) {
        if (listener != null) listener.onAbsent(attendanceId);
    }

    public interface OnAttendanceListener {
        void onAttendanceConfirmed(String sessionCode, CheckInData data);
        void onAttendanceFailed(String reason);
        void onAbsent(String attendanceId);
    }
}
