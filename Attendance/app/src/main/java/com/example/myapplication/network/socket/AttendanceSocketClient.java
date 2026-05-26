package com.example.myapplication.network.socket;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.myapplication.network.NetworkConfig;

import io.socket.client.IO;
import io.socket.client.Socket;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

/**
 * socket.io-client 래퍼.
 *
 * 책임:
 *   1. 서버에 WebSocket 연결 (auth payload: userId/role/lectureSessionId)
 *   2. 자동 재연결 (exponential 1s → 5s, 무제한 시도)
 *   3. role에 따른 이벤트 송수신 추상화
 *      - STUDENT  : PREPARE/DONE 수신, READY 송신
 *      - PROFESSOR: READY 수신, PREPARE/RESULT 송신
 *
 * 콜백은 main thread에서 호출됨 (Handler dispatch). BLE Manager와 정책 일치.
 *
 * 사용 예:
 *   client = new AttendanceSocketClient(Role.STUDENT);
 *   client.setListener(...);
 *   client.connect("STU001", "lectureXxx");
 *   ...
 *   client.disconnect();
 */
public class AttendanceSocketClient {

    private static final String TAG = "AttendanceSocket";

    public enum Role { STUDENT, PROFESSOR }

    private final Role role;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Socket socket;
    private OnSocketListener listener;

    public AttendanceSocketClient(Role role) {
        this.role = role;
    }

    public void setListener(OnSocketListener listener) {
        this.listener = listener;
    }

    /** 서버에 연결. 이미 연결돼 있으면 무시. */
    public void connect(String userId, String lectureSessionId) {
        if (socket != null && socket.connected()) {
            Log.w(TAG, "이미 연결됨 — 재연결 무시");
            return;
        }
        try {
            IO.Options options = new IO.Options();
            options.auth = buildAuth(userId, lectureSessionId);
            options.reconnection = true;
            options.reconnectionDelay = 1000L;
            options.reconnectionDelayMax = 5000L;
            // reconnectionAttempts 기본값 = Integer.MAX_VALUE (무제한 시도)

            socket = IO.socket(NetworkConfig.SOCKET_BASE_URL, options);
            attachLifecycleHandlers();
            attachRoleHandlers();
            socket.connect();
            Log.d(TAG, "connect 호출 — role=" + role + " user=" + userId + " lecture=" + lectureSessionId);
        } catch (URISyntaxException e) {
            Log.e(TAG, "URI 파싱 실패: " + NetworkConfig.SOCKET_BASE_URL, e);
        }
    }

    /** 명시적 종료. 자동 재연결도 중단됨. */
    public void disconnect() {
        if (socket != null) {
            socket.disconnect();
            socket.off();        // 등록한 리스너 모두 제거
            socket = null;
            Log.d(TAG, "disconnect 완료");
        }
    }

    // ── 송신 메서드 ──────────────────────────────

    /** 교수: 학생 1명 ranging 준비 요청. controllerAddressHex는 사이클별로 바뀜. */
    public void emitPrepare(String attendanceId, String studentId, String controllerAddressHex) {
        try {
            JSONObject payload = new JSONObject()
                    .put(SocketEvent.KEY_ATTENDANCE_ID, attendanceId)
                    .put(SocketEvent.KEY_STUDENT_ID, studentId)
                    .put(SocketEvent.KEY_CONTROLLER_ADDRESS, controllerAddressHex);
            emit(SocketEvent.PREPARE, payload);
        } catch (JSONException e) {
            Log.e(TAG, "PREPARE payload 생성 실패", e);
        }
    }

    /** 학생: ranging session open 완료 알림. studentAddressHex는 사이클별로 바뀜. */
    public void emitReady(String attendanceId, String studentId, String studentAddressHex) {
        try {
            JSONObject payload = new JSONObject()
                    .put(SocketEvent.KEY_ATTENDANCE_ID, attendanceId)
                    .put(SocketEvent.KEY_STUDENT_ID, studentId)
                    .put(SocketEvent.KEY_STUDENT_ADDRESS, studentAddressHex);
            emit(SocketEvent.READY, payload);
        } catch (JSONException e) {
            Log.e(TAG, "READY(PoC) payload 생성 실패", e);
        }
    }

    /** 교수: ranging 결과 보고. distance==null이면 OUT_OF_RANGE/CONNECTION_FAILED 분기. */
    public void emitResult(String attendanceId, String studentId,
                           Double distance, boolean connectionFailed) {
        try {
            JSONObject payload = new JSONObject()
                    .put(SocketEvent.KEY_ATTENDANCE_ID, attendanceId)
                    .put(SocketEvent.KEY_STUDENT_ID, studentId)
                    .put(SocketEvent.KEY_DISTANCE,
                            distance == null ? JSONObject.NULL : distance)
                    .put(SocketEvent.KEY_CONNECTION_FAILED, connectionFailed);
            emit(SocketEvent.RESULT, payload);
        } catch (JSONException e) {
            Log.e(TAG, "RESULT payload 생성 실패", e);
        }
    }

    private void emit(String event, JSONObject payload) {
        if (socket == null || !socket.connected()) {
            Log.w(TAG, "emit 불가 (소켓 미연결): " + event);
            return;
        }
        socket.emit(event, payload);
    }

    // ── 핸들러 ───────────────────────────────────

    private void attachLifecycleHandlers() {
        socket.on(Socket.EVENT_CONNECT, args -> {
            Log.d(TAG, "EVENT_CONNECT");
            final OnSocketListener l = listener;
            if (l != null) mainHandler.post(l::onConnected);
        });
        socket.on(Socket.EVENT_DISCONNECT, args -> {
            Log.d(TAG, "EVENT_DISCONNECT: " + (args.length > 0 ? args[0] : ""));
            final OnSocketListener l = listener;
            if (l != null) mainHandler.post(l::onDisconnected);
        });
        socket.on(Socket.EVENT_CONNECT_ERROR, args -> {
            String reason = args.length > 0 ? String.valueOf(args[0]) : "unknown";
            Log.e(TAG, "EVENT_CONNECT_ERROR: " + reason);
            final OnSocketListener l = listener;
            if (l != null) mainHandler.post(() -> l.onConnectError(reason));
        });
    }

    private void attachRoleHandlers() {
        if (role == Role.STUDENT) {
            socket.on(SocketEvent.PREPARE, args -> {
                JSONObject p = parsePayload(args);
                if (p == null) return;
                final String attendanceId = p.optString(SocketEvent.KEY_ATTENDANCE_ID, null);
                final String studentId    = p.optString(SocketEvent.KEY_STUDENT_ID, null);
                final String ctrlHex      = p.optString(SocketEvent.KEY_CONTROLLER_ADDRESS, null);
                Log.d(TAG, "PREPARE 수신: attId=" + attendanceId + " ctrl=" + ctrlHex);
                if (ctrlHex == null || ctrlHex.isEmpty()) {
                    Log.w(TAG, "PREPARE 페이로드에 controllerAddress 없음 — 무시 (att=" + attendanceId + ")");
                    return;
                }
                final OnSocketListener l = listener;
                if (l != null) mainHandler.post(() -> l.onPrepare(attendanceId, studentId, ctrlHex));
            });
            socket.on(SocketEvent.DONE, args -> {
                JSONObject p = parsePayload(args);
                if (p == null) return;
                final String attendanceId = p.optString(SocketEvent.KEY_ATTENDANCE_ID, null);
                final String status       = p.optString(SocketEvent.KEY_STATUS, null);
                Log.d(TAG, "DONE 수신: attId=" + attendanceId + " status=" + status);
                final OnSocketListener l = listener;
                if (l != null) mainHandler.post(() -> l.onDone(attendanceId, status));
            });
        } else { // PROFESSOR
            socket.on(SocketEvent.READY, args -> {
                JSONObject p = parsePayload(args);
                if (p == null) return;
                final String attendanceId = p.optString(SocketEvent.KEY_ATTENDANCE_ID, null);
                final String studentId    = p.optString(SocketEvent.KEY_STUDENT_ID, null);
                final String stuHex       = p.optString(SocketEvent.KEY_STUDENT_ADDRESS, null);
                Log.d(TAG, "READY 수신: attId=" + attendanceId + " stu=" + studentId + " stuAddr=" + stuHex);
                if (stuHex == null || stuHex.isEmpty()) {
                    Log.w(TAG, "READY 페이로드에 studentAddress 없음 — 무시 (att=" + attendanceId + ")");
                    return;
                }
                final OnSocketListener l = listener;
                if (l != null) mainHandler.post(() -> l.onReady(attendanceId, studentId, stuHex));
            });
        }
    }

    private static JSONObject parsePayload(Object[] args) {
        if (args == null || args.length == 0) return null;
        if (args[0] instanceof JSONObject) return (JSONObject) args[0];
        return null;
    }

    private Map<String, String> buildAuth(String userId, String lectureSessionId) {
        Map<String, String> auth = new HashMap<>();
        auth.put(SocketEvent.AUTH_USER_ID, userId);
        auth.put(SocketEvent.AUTH_ROLE, role.name());
        auth.put(SocketEvent.AUTH_LECTURE_SESSION_ID, lectureSessionId);
        return auth;
    }

    /**
     * 콜백 인터페이스. default 빈 구현이라 role별로 필요한 것만 override.
     */
    public interface OnSocketListener {
        // 생명주기 (둘 다 공통)
        default void onConnected() {}
        default void onDisconnected() {}
        default void onConnectError(String reason) {}

        // 학생만
        default void onDone(String attendanceId, String status) {}
        default void onPrepare(String attendanceId, String studentId, String controllerAddressHex) {}

        // 교수만
        default void onReady(String attendanceId, String studentId, String studentAddressHex) {}
    }
}
