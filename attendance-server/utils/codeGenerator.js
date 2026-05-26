const crypto = require('crypto');

/*
  암호학적으로 안전한 4자리 숫자 PIN 생성 ("0000" ~ "9999").
  BLE broadcast, 학생 수동 입력, 서버 조회에 공통으로 쓰이는 sessionCode.
  ACTIVE 세션 간 중복 방지는 attendanceService의 retry loop가 담당.
 */
function generateSessionCode() {
  // randomInt은 균등분포 보장 (randomBytes % modulo는 미세 bias 발생)
  return String(crypto.randomInt(0, 10000)).padStart(4, '0');
}

module.exports = { generateSessionCode };
