package com.example.myapplication.network;

import com.example.myapplication.uwb.ProfessorUwbRangingManager;
import com.example.myapplication.uwb.StudentUwbRangingManager;

/**
 * 서버 응답 DTO({@link StartAttendanceData.UwbParams})를
 * UWB Manager가 사용하는 모양으로 변환하는 어댑터.
 *
 * 변환 포인트:
 *   - sessionKey: int[]  →  byte[]   (UWB API는 ByteArray만 받음)
 *   - complexChannel:    중첩 객체  →  channel/preambleIndex 평탄화
 *
 * 서버 응답의 controllerAddress는 stale이라 무시. 실제 ranging 주소는
 * 매 사이클 openScope로 동적 획득해 socket PREPARE/READY로 교환.
 */
public final class UwbParamsAdapter {

    private UwbParamsAdapter() {}

    public static ProfessorUwbRangingManager.UwbParams toProfessor(
            StartAttendanceData.UwbParams dto) {
        return new ProfessorUwbRangingManager.UwbParams(
                dto.getSessionId(),
                toBytes(dto.getSessionKey()),
                dto.getComplexChannel().getChannel(),
                dto.getComplexChannel().getPreambleIndex()
        );
    }

    public static StudentUwbRangingManager.UwbParams toStudent(
            StartAttendanceData.UwbParams dto) {
        return new StudentUwbRangingManager.UwbParams(
                dto.getSessionId(),
                toBytes(dto.getSessionKey()),
                dto.getComplexChannel().getChannel(),
                dto.getComplexChannel().getPreambleIndex()
        );
    }

    private static byte[] toBytes(int[] src) {
        byte[] out = new byte[src.length];
        for (int i = 0; i < src.length; i++) out[i] = (byte) src[i];
        return out;
    }
}
