package com.example.myapplication.network;

/**
 * POST /api/attendance/check-in 요청 바디.
 * Gson은 필드로 직렬화 — getter 불필요.
 */
public class CheckInRequest {
    private final String sessionCode;
    private final String studentId;
    private final String studentUwbAddress;

    public CheckInRequest(String sessionCode, String studentId, String studentUwbAddress) {
        this.sessionCode = sessionCode;
        this.studentId = studentId;
        this.studentUwbAddress = studentUwbAddress;
    }
}
