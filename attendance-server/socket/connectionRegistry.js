/**
 * userId → socket 매핑.
 * 추가로 lectureSessionId → 교수 socket 보조 인덱스 유지.
 *
 * 메모리 기반 (인스턴스 1개 가정). 다중 인스턴스 확장 시 Redis 등으로 교체.
 */

const userIdToSocket = new Map();           // userId -> socket
const lectureToProfSocket = new Map();      // lectureSessionId -> professor socket

function add(socket) {
    const {userId, role, lectureSessionId} = socket.data;

    // 같은 userId가 재연결한 경우 → 기존 socket 정리
    const prev = userIdToSocket.get(userId);
    if (prev && prev.id !== socket.id) {
        prev.disconnect(true);
    }
    userIdToSocket.set(userId, socket);

    if (role === 'PROFESSOR') {
        lectureToProfSocket.set(lectureSessionId, socket);
    }
}

function remove(socket) {
    const {userId, role, lectureSessionId} = socket.data;

    // 동일 객체일 때만 제거 (재연결 race 방지)
    if (userIdToSocket.get(userId) === socket) {
        userIdToSocket.delete(userId);
    }
    if (role === 'PROFESSOR' && lectureToProfSocket.get(lectureSessionId) === socket) {
        lectureToProfSocket.delete(lectureSessionId);
    }
}

function getSocketByUserId(userId) {
    return userIdToSocket.get(userId);
}

function getProfessorSocket(lectureSessionId) {
    return lectureToProfSocket.get(lectureSessionId);
}

module.exports = {add, remove, getSocketByUserId, getProfessorSocket};
