package com.example.myapplication.service;

/**
 * Service ↔ Activity broadcast 통신에 쓰는 액션/extra 키 모음.
 *
 * Service는 Listener 콜백을 받자마자 Intent broadcast로 변환해서 쏘고,
 * Activity는 등록된 BroadcastReceiver 안에서 같은 액션 문자열로 분기 처리한다.
 *
 * 양쪽이 동일 문자열을 참조해야 하므로 한 클래스에 모아둠.
 * 보안상 broadcast는 항상 setPackage(getPackageName())로 우리 앱에만 한정.
 */
public final class AttendanceEvents {
    private AttendanceEvents() {}

    // ── 교수 Service → Activity ────────────────────────────
    public static final String ACTION_SESSION_STARTED = "com.example.myapplication.SESSION_STARTED";
    public static final String ACTION_SESSION_FAILED  = "com.example.myapplication.SESSION_FAILED";
    public static final String ACTION_SESSION_EXPIRED = "com.example.myapplication.SESSION_EXPIRED";

    // ── 학생 Service → Activity ────────────────────────────
    public static final String ACTION_ATTENDANCE_CONFIRMED = "com.example.myapplication.ATTENDANCE_CONFIRMED";
    public static final String ACTION_ATTENDANCE_FAILED    = "com.example.myapplication.ATTENDANCE_FAILED";
    public static final String ACTION_ATTENDANCE_ABSENT    = "com.example.myapplication.ATTENDANCE_ABSENT";

    // ── Extra 키 ─────────────────────────────────────────────
    public static final String EXTRA_SESSION_CODE       = "sessionCode";
    public static final String EXTRA_LECTURE_SESSION_ID = "lectureSessionId";
    public static final String EXTRA_ATTENDANCE_ID      = "attendanceId";
    public static final String EXTRA_STUDENT_ID         = "studentId";
    public static final String EXTRA_REASON             = "reason";
}
