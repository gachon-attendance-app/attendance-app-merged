const {Server} = require('socket.io');
const registry = require('./connectionRegistry');
const router = require('./socketRouter');

/**
 * socket.io 서버 초기화.
 *
 * Auth payload (handshake.auth):
 *   { userId, role: "STUDENT" | "PROFESSOR", lectureSessionId }
 *
 * Phase 2 단계에선 단순 신뢰 (필드 존재/형식만 검증).
 * 토큰 발급은 로그인 도입 시 같이 추가.
 */
function initSocketServer(httpServer) {
    const io = new Server(httpServer, {
        cors: {origin: '*'},        // 개발용. 운영 시 도메인 제한
    });

    // 인증 미들웨어
    io.use((socket, next) => {
        const {userId, role, lectureSessionId} = socket.handshake.auth || {};

        if (!userId || !role || !lectureSessionId) {
            return next(new Error('AUTH_INVALID: missing fields'));
        }
        if (role !== 'STUDENT' && role !== 'PROFESSOR') {
            return next(new Error('AUTH_INVALID: bad role'));
        }

        socket.data = {userId, role, lectureSessionId};
        next();
    });

    io.on('connection', (socket) => {
        registry.add(socket);
        console.log(`[socket] connect userId=${socket.data.userId} role=${socket.data.role} lecture=${socket.data.lectureSessionId}`);

        router.attach(socket);

        socket.on('disconnect', (reason) => {
            registry.remove(socket);
            console.log(`[socket] disconnect userId=${socket.data.userId} reason=${reason}`);
        });
    });

    return io;
}

module.exports = {initSocketServer};
