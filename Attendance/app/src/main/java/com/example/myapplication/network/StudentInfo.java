package com.example.myapplication.network;

/**
 * GET /api/attendance/{lectureSessionId}/students 응답 원소.
 *
 * 교수 폰의 5분 주기 ranging 루프에서 검증 대상 명단으로 사용.
 * UWB 주소는 응답에 포함되지만 stale이라 안 씀 — 실제 ranging 주소는 매 사이클 socket으로 동적 교환.
 */
public class StudentInfo {
    private String studentId;
    private String attendanceId;

    public String getStudentId() { return studentId; }
    public String getAttendanceId() { return attendanceId; }
}
