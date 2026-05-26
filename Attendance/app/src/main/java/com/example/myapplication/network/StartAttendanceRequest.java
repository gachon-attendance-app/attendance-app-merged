package com.example.myapplication.network;

/**
 * POST /api/attendance/start 요청 바디.
 * Gson은 필드로 직렬화 — getter 불필요.
 *
 * classStartAt:
 *   - 수업 시작 epoch millis. 서버가 RTDB Attendance_Session 미러링 시
 *     bluetoothEndAt / pinEndAt 계산에 사용 (15분 페이즈 전환 등).
 *   - 0 (또는 미설정)이면 서버가 now로 fallback. ad-hoc 세션 호환.
 */
public class StartAttendanceRequest {
    private final String courseId;
    private final String professorId;
    private final String professorUwbAddress;
    private final long classStartAt;

    public StartAttendanceRequest(String courseId, String professorId, String professorUwbAddress) {
        this(courseId, professorId, professorUwbAddress, 0L);
    }

    public StartAttendanceRequest(String courseId, String professorId,
                                  String professorUwbAddress, long classStartAt) {
        this.courseId = courseId;
        this.professorId = professorId;
        this.professorUwbAddress = professorUwbAddress;
        this.classStartAt = classStartAt;
    }
}
