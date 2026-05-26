const registry = require('./connectionRegistry');
const {processRangingResult} = require('../services/attendanceService');

/**
 * 한 socket에 PREPARE/READY/RESULT 핸들러 부착.
 * (DONE은 송신 전용이라 핸들러 없음 — RESULT 처리 직후 서버가 학생에 emit)
 *
 * 권한:
 *   PROFESSOR만 → PREPARE, RESULT
 *   STUDENT만   → READY
 *   잘못된 role의 emit은 조용히 무시
 */
function attach(socket) {
    const {role, lectureSessionId} = socket.data;

    // 교수 → 서버 → 학생
    // PoC 확장: payload.controllerAddress가 있으면 그대로 forward (Option A 사이클별 새 주소 전달용)
    socket.on('PREPARE', (payload) => {
        if (role !== 'PROFESSOR') return;
        const {attendanceId, studentId, controllerAddress} = payload || {};
        if (!attendanceId || !studentId) return;

        const studentSocket = registry.getSocketByUserId(studentId);
        if (!studentSocket || studentSocket.data.lectureSessionId !== lectureSessionId) {
            console.warn(`[socket] PREPARE forward fail: student=${studentId} not connected to lecture=${lectureSessionId}`);
            return;     // 교수가 자체 timeout으로 처리
        }
        const out = {attendanceId, studentId};
        if (controllerAddress) out.controllerAddress = controllerAddress;
        studentSocket.emit('PREPARE', out);
    });

    // 학생 → 서버 → 교수
    // PoC 확장: payload.studentAddress가 있으면 그대로 forward (학생 측 새 scope 주소 전달용)
    socket.on('READY', (payload) => {
        if (role !== 'STUDENT') return;
        const {attendanceId, studentId, studentAddress} = payload || {};
        if (!attendanceId || !studentId) return;

        const profSocket = registry.getProfessorSocket(lectureSessionId);
        if (!profSocket) {
            console.warn(`[socket] READY forward fail: no professor for lecture=${lectureSessionId}`);
            return;
        }
        const out = {attendanceId, studentId};
        if (studentAddress) out.studentAddress = studentAddress;
        profSocket.emit('READY', out);
    });

    // 교수 → 서버 (DB 트랜잭션 → 학생에 DONE)
    socket.on('RESULT', async (payload) => {
        if (role !== 'PROFESSOR') return;
        const {attendanceId, studentId, distance, connectionFailed} = payload || {};
        if (!attendanceId || !studentId || typeof connectionFailed !== 'boolean') return;

        try {
            const finalStatus = await processRangingResult({
                attendanceId,
                studentId,
                distance,
                connectionFailed,
                lectureSessionId,
            });

            const studentSocket = registry.getSocketByUserId(studentId);
            if (studentSocket) {
                studentSocket.emit('DONE', {attendanceId, status: finalStatus});
            }
        } catch (e) {
            console.error(`[socket] RESULT processing failed for attendanceId=${attendanceId}:`, e.message);
        }
    });
}

module.exports = {attach};
