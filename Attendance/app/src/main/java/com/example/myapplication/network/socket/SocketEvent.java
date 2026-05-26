package com.example.myapplication.network.socket;

/**
 * WebSocket 이벤트명 + 페이로드 키 모음.
 * 서버 socket/socketRouter.js와 같은 문자열 사용해야 함.
 */
public final class SocketEvent {
    private SocketEvent() {}

    // ── 이벤트명 ─────────────────────────
    public static final String PREPARE = "PREPARE";
    public static final String READY   = "READY";
    public static final String RESULT  = "RESULT";
    public static final String DONE    = "DONE";

    // ── 페이로드 키 ──────────────────────
    public static final String KEY_ATTENDANCE_ID     = "attendanceId";
    public static final String KEY_STUDENT_ID        = "studentId";
    public static final String KEY_DISTANCE          = "distance";
    public static final String KEY_CONNECTION_FAILED = "connectionFailed";
    public static final String KEY_STATUS            = "status";

    // 사이클당 새 scope 주소 전달용 (매 PREPARE/READY 동적 교환)
    public static final String KEY_CONTROLLER_ADDRESS = "controllerAddress";
    public static final String KEY_STUDENT_ADDRESS    = "studentAddress";

    // ── handshake auth 키 ────────────────
    public static final String AUTH_USER_ID            = "userId";
    public static final String AUTH_ROLE               = "role";
    public static final String AUTH_LECTURE_SESSION_ID = "lectureSessionId";
}
