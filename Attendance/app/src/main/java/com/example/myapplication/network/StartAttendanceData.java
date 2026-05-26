package com.example.myapplication.network;

public class StartAttendanceData {
    private String lectureSessionId;
    private String sessionCode;
    private UwbParams uwbParams;

    public String getLectureSessionId() { return lectureSessionId; }
    public String getSessionCode() { return sessionCode; }
    public UwbParams getUwbParams() { return uwbParams; }

    public static class UwbParams {
        private ComplexChannel complexChannel;
        private int sessionId;
        private int[] sessionKey;

        public ComplexChannel getComplexChannel() { return complexChannel; }
        public int getSessionId() { return sessionId; }
        public int[] getSessionKey() { return sessionKey; }
    }

    public static class ComplexChannel {
        private int channel;
        private int preambleIndex;

        public int getChannel() { return channel; }
        public int getPreambleIndex() { return preambleIndex; }
    }
}
