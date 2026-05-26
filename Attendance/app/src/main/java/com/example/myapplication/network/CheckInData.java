package com.example.myapplication.network;

public class CheckInData {
    private String attendanceId;
    private String lectureSessionId;
    private String studentId;
    private StartAttendanceData.UwbParams uwbParams;

    public String getAttendanceId() { return attendanceId; }
    public String getLectureSessionId() { return lectureSessionId; }
    public String getStudentId() { return studentId; }
    public StartAttendanceData.UwbParams getUwbParams() { return uwbParams; }
}
