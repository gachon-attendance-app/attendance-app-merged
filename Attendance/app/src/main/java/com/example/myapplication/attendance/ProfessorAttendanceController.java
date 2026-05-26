package com.example.myapplication.attendance;

import android.content.Context;
import android.util.Log;

import com.example.myapplication.ble.BleAdvertiseManager;
import com.example.myapplication.network.ApiResponse;
import com.example.myapplication.network.AttendanceApi;
import com.example.myapplication.network.RetrofitClient;
import com.example.myapplication.network.StartAttendanceData;
import com.example.myapplication.network.StartAttendanceRequest;
import com.example.myapplication.network.StudentInfo;
import com.example.myapplication.network.UwbParamsAdapter;
import com.example.myapplication.network.socket.AttendanceSocketClient;
import com.example.myapplication.uwb.ProfessorUwbRangingManager;

import java.io.IOException;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 교수 출석 흐름 — Option A + fail-recovery.
 *
 * 흐름:
 *   1. /start 호출 (UWB 주소는 placeholder) → BLE 광고 + Socket connect + setParams
 *   2. BLE 만료 후 5분 주기 ranging 루프
 *   3. 매 사이클: closeScope+openScope → 새 P_n → /students → 학생 1명씩 처리
 *      - 첫 학생: prepareSession(peers=[X_i])
 *      - 둘째+: addControleeAsync(X_i) (성공 시 prev removeControleeAsync)
 *      - 학생 1명 측정 fail (READY timeout, addControlee fail, 또는 awaitMeasurement timeout)
 *        → 사이클을 sub-cycle로 분할: closeScope+openScope→P_{n+1}, 다음 학생 다시 first로
 *   4. 휴식 시간 (XX:50~XX:00) pause/resume
 *   5. stop(): 멱등
 *
 * 학생 측 UWB 주소(X_i)는 ephemeral — 매 PREPARE-READY 동적 교환.
 *
 * 스레딩: scheduler 단일 스레드 직렬화. 사이클 안의 학생 순회는 동기 (CountDownLatch).
 */
public class  ProfessorAttendanceController {

    private static final String TAG = "ProfessorController";

    private static final long RANGING_PERIOD_MINUTES = 1;
    private static final long READY_TIMEOUT_SECONDS = 2;
    /**
     * Manager awaitMeasurement timeout.
     * 정상 측정은 보통 수십ms~1초 (PoC에서 16ms 확인). fail 학생을 빨리 회수하기 위해 4s로 짧게 잡음.
     * RangingResultFailure는 hw inherent ~10초 후 emit이지만 우리는 그 event 안 기다리고 timeout으로 fail 처리.
     */
    private static final long RANGING_RESULT_TIMEOUT_MS = 4_000L;
    /** addControlee / openScope 동기 wait timeout. */
    private static final long SCOPE_OP_TIMEOUT_SECONDS = 5;
    /**
     * 짧은 timeout(4s) 후 closeScope 시 hw가 아직 ranging 시도 중일 수 있음.
     * 즉시 새 openScope하면 새 prepareSession이 RangingResultFailure로 거부될 수 있어 약간의 안전 마진.
     * 실측에서 부족하면 늘리면 됨.
     */
    private static final long SCOPE_CLEANUP_MS = 300L;

    /** /start의 professorUwbAddress placeholder. 매 사이클 openScope로 실제 주소 받아 PREPARE에 동봉. */
    private static final String PROFESSOR_ADDR_PLACEHOLDER = "0x0001";

    /** 시계 시간 기반 자동 휴식: 매 시간 XX:50~XX:00. */
    private static final int BREAK_START_MINUTE = 50;
    private static final int BREAK_END_MINUTE = 0;

    private final BleAdvertiseManager advertiseManager;
    private final AttendanceApi api;
    private final ProfessorUwbRangingManager uwbManager;
    private final AttendanceSocketClient socketClient;
    private final ScheduledExecutorService scheduler;

    private OnSessionListener listener;
    private ScheduledFuture<?> rangingFuture;

    private String currentSessionCode;
    private String currentLectureSessionId;
    /** 사이클(또는 sub-cycle) 시작에 openScope로 받은 controller 주소. PREPARE에 동봉. */
    private volatile String currentControllerAddress;
    /** Add-before-Remove swap용. 사이클(또는 sub-cycle) 첫 학생은 null. */
    private String prevAddedPeer;

    private volatile boolean paused = false;
    private volatile boolean stopped = false;

    /** attendanceId → READY 응답 latch + studentAddress 저장. */
    private final Map<String, ReadyHandler> pendingReady = new ConcurrentHashMap<>();

    public ProfessorAttendanceController(Context context) {
        this.advertiseManager = new BleAdvertiseManager(context);
        this.api = RetrofitClient.getInstance().create(AttendanceApi.class);
        this.uwbManager = new ProfessorUwbRangingManager(context);
        this.socketClient = new AttendanceSocketClient(AttendanceSocketClient.Role.PROFESSOR);
        this.scheduler = Executors.newSingleThreadScheduledExecutor();

        this.advertiseManager.setListener(new BleAdvertiseManager.OnAdvertiseListener() {
            @Override public void onAdvertiseStarted() { Log.d(TAG, "BLE 광고 시작"); }
            @Override public void onAdvertiseFailed(String reason) {
                Log.e(TAG, "BLE 광고 실패: " + reason);
                notifyFailed("BLE 광고 실패: " + reason);
            }
            @Override public void onAdvertiseExpired() {
                Log.d(TAG, "BLE 만료 → ranging 루프 진입");
                notifyExpired();
                startRangingLoopIfActive();
            }
        });

        this.socketClient.setListener(new AttendanceSocketClient.OnSocketListener() {
            @Override public void onConnected() { Log.d(TAG, "socket connected"); }
            @Override public void onDisconnected() { Log.d(TAG, "socket disconnected"); }
            @Override public void onConnectError(String reason) {
                Log.w(TAG, "socket connect error: " + reason);
            }

            @Override
            public void onReady(String attendanceId, String studentId, String studentAddressHex) {
                ReadyHandler h = pendingReady.get(attendanceId);
                if (h != null) {
                    h.studentAddress = studentAddressHex;
                    h.latch.countDown();
                }
            }
        });
    }

    public void setListener(OnSessionListener listener) {
        this.listener = listener;
    }

    /**
     * 세션 시작 — /start 호출 (placeholder UWB 주소).
     * 응답 받으면 setParams + Socket connect + BLE 광고 시작.
     * 실제 controller UWB 주소는 매 사이클(또는 sub-cycle) 시작에 openScope로 받음.
     */
    public void startSession(String courseId, String professorId) {
        startSession(courseId, professorId, 0L);
    }

    /**
     * classStartAt 포함 버전.
     * @param classStartAt 수업 시작 epoch millis. 서버가 dual-write RTDB 시간 계산용으로 사용.
     *                     0이면 서버가 now로 fallback.
     */
    public void startSession(String courseId, String professorId, long classStartAt) {
        Log.d(TAG, "startSession: " + courseId + " / " + professorId + " / classStartAt=" + classStartAt);
        StartAttendanceRequest request =
                new StartAttendanceRequest(courseId, professorId, PROFESSOR_ADDR_PLACEHOLDER, classStartAt);

        api.startAttendance(request).enqueue(new Callback<ApiResponse<StartAttendanceData>>() {
            @Override
            public void onResponse(Call<ApiResponse<StartAttendanceData>> call,
                                   Response<ApiResponse<StartAttendanceData>> response) {
                if (!response.isSuccessful()) {
                    notifyFailed("서버 응답 실패 (" + response.code() + ")");
                    return;
                }
                ApiResponse<StartAttendanceData> body = response.body();
                if (body == null || !body.isSuccess() || body.getData() == null) {
                    notifyFailed("서버 에러: "
                            + (body != null ? body.getMessage() : "body null"));
                    return;
                }

                StartAttendanceData data = body.getData();
                currentSessionCode = data.getSessionCode();
                currentLectureSessionId = data.getLectureSessionId();

                Log.d(TAG, "세션 생성: " + currentSessionCode + " → setParams/Socket/BLE 시작");

                uwbManager.setParams(UwbParamsAdapter.toProfessor(data.getUwbParams()));
                socketClient.connect(professorId, currentLectureSessionId);
                advertiseManager.startAdvertising(currentSessionCode);
                scheduleBreakLoop();

                notifyStarted(data);
            }

            @Override
            public void onFailure(Call<ApiResponse<StartAttendanceData>> call, Throwable t) {
                Log.e(TAG, "서버 연결 실패", t);
                notifyFailed("네트워크 실패: " + t.getMessage());
            }
        });
    }

    /** 휴식 진입. */
    public void pauseRanging() {
        if (paused || stopped) return;
        paused = true;
        cancelRangingLoop();
        Log.d(TAG, "ranging pause");
    }

    /** 휴식 종료. */
    public void resumeRanging() {
        if (!paused || stopped) return;
        paused = false;
        startRangingLoopIfActive();
        Log.d(TAG, "ranging resume");
    }

    /**
     * BT 상태 변화 통보. Service의 BroadcastReceiver가 STATE_ON/OFF 받았을 때 호출.
     *   - enabled=true : advertiser 재획득. 실패 시 세션 자체 fail로 escalate.
     *   - enabled=false: 로그만. 진행 중 사이클은 ranging timeout/connectionFailed로 자연 fail 처리.
     */
    public void onBluetoothStateChanged(boolean enabled) {
        if (stopped) return;
        if (enabled) {
            Log.d(TAG, "BT_STATE_ON — advertiser 재획득 시도");
            boolean ok = advertiseManager.reinit();
            if (!ok) {
                Log.e(TAG, "advertiseManager.reinit 실패 — 세션 종료");
                notifyFailed("Bluetooth 어댑터 재획득 실패");
            }
        } else {
            Log.w(TAG, "BT_STATE_OFF — 진행 중 ranging은 자연 fail 처리, 다음 사이클은 STATE_ON 후");
        }
    }

    /** 수동 종료. 멱등. */
    public void stopSession() {
        if (stopped) return;
        stopped = true;
        Log.d(TAG, "stopSession");

        cancelRangingLoop();
        scheduler.shutdownNow();
        uwbManager.stop();
        socketClient.disconnect();
        advertiseManager.stopAdvertising();
        pendingReady.clear();
    }

    // ── ranging 루프 ───────────────────────────────────────

    private void startRangingLoopIfActive() {
        if (stopped || paused) return;
        if (rangingFuture != null && !rangingFuture.isDone()) return;
        rangingFuture = scheduler.scheduleWithFixedDelay(
                this::runRangingCycle, 0, RANGING_PERIOD_MINUTES, TimeUnit.MINUTES);
    }

    private void cancelRangingLoop() {
        if (rangingFuture != null) {
            rangingFuture.cancel(false);
            rangingFuture = null;
        }
    }

    private void scheduleBreakLoop() {
        long oneHourMs = TimeUnit.HOURS.toMillis(1);
        long msUntilPause  = millisUntilNextMinuteOfHour(BREAK_START_MINUTE);
        long msUntilResume = millisUntilNextMinuteOfHour(BREAK_END_MINUTE);

        scheduler.scheduleWithFixedDelay(this::pauseRanging,
                msUntilPause, oneHourMs, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(this::resumeRanging,
                msUntilResume, oneHourMs, TimeUnit.MILLISECONDS);

        int currentMinute = Calendar.getInstance().get(Calendar.MINUTE);
        if (currentMinute >= BREAK_START_MINUTE) {
            Log.d(TAG, "수업이 휴식 중에 시작됨 → 즉시 pause");
            pauseRanging();
        }
    }

    private static long millisUntilNextMinuteOfHour(int targetMinute) {
        Calendar now = Calendar.getInstance();
        Calendar target = (Calendar) now.clone();
        target.set(Calendar.MINUTE, targetMinute);
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);
        if (target.getTimeInMillis() <= now.getTimeInMillis()) {
            target.add(Calendar.HOUR_OF_DAY, 1);
        }
        return target.getTimeInMillis() - now.getTimeInMillis();
    }

    /**
     * 한 사이클 = openScope + /students + 학생 순회.
     * 학생 1명 측정이 fail이면 sub-cycle로 분할 (closeScope+openScope→다음 학생 다시 first로).
     */
    private void runRangingCycle() {
        if (paused || stopped) return;
        Log.d(TAG, "── ranging 사이클 시작 ──");

        if (!resetScope()) {
            Log.w(TAG, "사이클 시작 openScope 실패 — 이번 사이클 skip");
            return;
        }

        List<StudentInfo> students;
        try {
            Response<ApiResponse<List<StudentInfo>>> resp =
                    api.getCheckedInStudents(currentLectureSessionId).execute();
            if (!resp.isSuccessful() || resp.body() == null
                    || !resp.body().isSuccess() || resp.body().getData() == null) {
                Log.w(TAG, "/students 응답 부적합: code=" + resp.code());
                uwbManager.closeScope();
                return;
            }
            students = resp.body().getData();
        } catch (IOException e) {
            Log.e(TAG, "/students 호출 실패", e);
            uwbManager.closeScope();
            return;
        }

        Log.d(TAG, "ranging 대상 " + students.size() + "명");

        boolean firstOfSubCycle = true;
        for (StudentInfo s : students) {
            if (paused || stopped) break;
            boolean success;
            try {
                success = processStudent(s, firstOfSubCycle);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            if (success) {
                firstOfSubCycle = false;
            } else {
                Log.w(TAG, "fail 감지 → sub-cycle 재시작 (closeScope+openScope)");
                if (!resetScope()) {
                    Log.e(TAG, "sub-cycle openScope 실패 — 이번 사이클 종료");
                    break;
                }
                firstOfSubCycle = true;
            }
        }

        uwbManager.closeScope();
        Log.d(TAG, "── ranging 사이클 종료 ──");
    }

    /**
     * 한 학생 처리:
     *   1. PREPARE(ctrl=currentControllerAddress) → READY 대기 (studentAddress 수신)
     *   2. firstOfSubCycle ? startMulticastSession(stuAddr) : addControleeAsync(stuAddr)
     *   3. awaitMeasurement → 결과 RESULT 전송
     *   4. 성공이면 prev removeControleeAsync (fire-and-forget) + prevAddedPeer 갱신
     * @return 측정 성공이면 true, fail이면 false (caller가 sub-cycle 재시작)
     */
    private boolean processStudent(StudentInfo s, boolean firstOfSubCycle) throws InterruptedException {
        final String attId = s.getAttendanceId();
        final String stuId = s.getStudentId();
        Log.d(TAG, "학생 처리: " + stuId + " (first=" + firstOfSubCycle + ")");

        // 1. READY 대기
        ReadyHandler h = new ReadyHandler();
        pendingReady.put(attId, h);
        socketClient.emitPrepare(attId, stuId, currentControllerAddress);

        boolean readyGot = h.latch.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        pendingReady.remove(attId);

        if (!readyGot || h.studentAddress == null) {
            Log.w(TAG, stuId + ": READY timeout 또는 stuAddr 없음 → fail");
            socketClient.emitResult(attId, stuId, null, true);
            return false;
        }
        final String stuAddr = h.studentAddress;

        // 2. 측정 listener를 먼저 등록 (race 방지)
        AtomicReference<Double> distRef = new AtomicReference<>();
        AtomicBoolean successRef = new AtomicBoolean(false);
        CountDownLatch measureLatch = new CountDownLatch(1);
        uwbManager.awaitMeasurement(stuAddr, RANGING_RESULT_TIMEOUT_MS,
                (distance, success, peerMatched) -> {
                    distRef.set(distance);
                    successRef.set(success && peerMatched);
                    measureLatch.countDown();
                });

        // 3. start or add
        if (firstOfSubCycle) {
            uwbManager.startMulticastSession(stuAddr);
        } else {
            CountDownLatch addLatch = new CountDownLatch(1);
            AtomicBoolean addOk = new AtomicBoolean(false);
            uwbManager.addControleeAsync(stuAddr, new ProfessorUwbRangingManager.SimpleCallback() {
                @Override public void onSuccess() { addOk.set(true); addLatch.countDown(); }
                @Override public void onFailure(String reason) {
                    Log.w(TAG, "addControlee 실패: " + reason);
                    addLatch.countDown();
                }
            });
            addLatch.await(SCOPE_OP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!addOk.get()) {
                // measureLatch 등록한 거 정리 안 해도 timeout으로 자연 해결
                socketClient.emitResult(attId, stuId, null, true);
                return false;
            }
        }

        // 4. 측정 결과 대기 (awaitMeasurement 내부 timeout + 여유)
        measureLatch.await(RANGING_RESULT_TIMEOUT_MS + 2_000, TimeUnit.MILLISECONDS);

        if (!successRef.get()) {
            Log.w(TAG, stuId + ": 측정 실패");
            socketClient.emitResult(attId, stuId, null, true);
            return false;
        }

        // 성공 — RESULT 전송 + prev removeControlee
        socketClient.emitResult(attId, stuId, distRef.get(), false);
        if (prevAddedPeer != null && !prevAddedPeer.equalsIgnoreCase(stuAddr)) {
            final String toRemove = prevAddedPeer;
            uwbManager.removeControleeAsync(toRemove,
                    new ProfessorUwbRangingManager.SimpleCallback() {
                        @Override public void onSuccess() { /* ok */ }
                        @Override public void onFailure(String reason) {
                            Log.w(TAG, "prev removeControlee 실패 (무시): " + reason);
                        }
                    });
        }
        prevAddedPeer = stuAddr;
        return true;
    }

    /**
     * closeScope + openScope을 동기로 처리. currentControllerAddress / prevAddedPeer reset.
     * @return openScope 성공 여부
     */
    private boolean resetScope() {
        uwbManager.closeScope();
        prevAddedPeer = null;

        // hw cleanup 안전 마진 (실측에서 부족하면 늘리기)
        try {
            Thread.sleep(SCOPE_CLEANUP_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> addrRef = new AtomicReference<>();
        AtomicBoolean ok = new AtomicBoolean(false);
        uwbManager.openScope(new ProfessorUwbRangingManager.StartCallback() {
            @Override public void onScopeOpened(String addr) {
                addrRef.set(addr);
                ok.set(true);
                latch.countDown();
            }
            @Override public void onScopeFailed(String reason) {
                Log.e(TAG, "openScope fail: " + reason);
                latch.countDown();
            }
        });
        try {
            latch.await(SCOPE_OP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (ok.get()) {
            currentControllerAddress = addrRef.get();
            Log.d(TAG, "openScope OK ctrl=" + currentControllerAddress);
            return true;
        }
        return false;
    }

    // ── listener notify ───────────────────────────────────

    private void notifyStarted(StartAttendanceData data) {
        if (listener != null) listener.onSessionStarted(data);
    }
    private void notifyFailed(String reason) {
        if (listener != null) listener.onSessionFailed(reason);
    }
    private void notifyExpired() {
        if (listener != null) listener.onSessionExpired();
    }

    public interface OnSessionListener {
        void onSessionStarted(StartAttendanceData data);
        void onSessionFailed(String reason);
        void onSessionExpired();
    }

    /** READY 응답 핸들. socket onReady에서 채워짐. */
    private static class ReadyHandler {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile String studentAddress;
    }
}
