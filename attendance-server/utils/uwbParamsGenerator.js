const crypto = require('crypto');

/*
  UWB ranging 파라미터 1회 생성.
  한 수업의 모든 학생이 이 동일한 값을 공유함 (재협상 없음).

  형식은 Android androidx.core.uwb 클라이언트 DTO에 맞춤:
    - sessionId: 32-bit int
    - sessionKey: 8 byte → int[] (각 0~255)
        ← CONFIG_UNICAST_DS_TWR의 static STS 모드는 정확히 8 byte key 요구.
          16 byte로 보내면 RangingParameters 생성 시 IllegalArgumentException.
    - complexChannel: { channel, preambleIndex }
    - controllerAddress: 교수 폰 UWB 주소 (학생이 Responder 설정 시 사용)

  configType은 응답에 포함하지 않음 — 클라이언트 측에서 "UNICAST_DS_TWR" 하드코딩.
 */
function generateUwbParams(professorUwbAddress) {
    // 음수 회피 위해 최상위 비트 마스킹 (Java int signed 호환)
    const sessionId = crypto.randomBytes(4).readUInt32BE() & 0x7fffffff;

    const keyBuf = crypto.randomBytes(8);
    const sessionKey = Array.from(keyBuf); // [0~255, ...]

    return {
        sessionId,
        sessionKey,
        complexChannel: {
            channel: 9,        // Samsung S23 Ultra 지원 채널: [6, 8, 9, 10, 12, 13, 14]
            preambleIndex: 10,
        },
        controllerAddress: professorUwbAddress,
    };
}

module.exports = { generateUwbParams };
