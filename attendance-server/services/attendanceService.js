const {db, admin, rtdb} = require('../firebase/firebaseAdmin');
const {generateSessionCode} = require('../utils/codeGenerator');
const {generateUwbParams} = require('../utils/uwbParamsGenerator');

// ── 그쪽 RTDB 스키마 dual-write 유틸 ───────────────────────────
// 그쪽 화면(refreshStudentAttendanceButtonState / updateProfessorSessionUi 등)이
// RTDB Attendance_Session/{courseId}/{date} 와 Attendance_Records/{courseId}/{date}/{userId}
// 를 직접 읽어 UI를 그림. dual-write로 서버가 두 곳 모두 채워줌.

const FIVE_MIN_MS    = 5 * 60 * 1000;
const TEN_MIN_MS     = 10 * 60 * 1000;
const FIFTEEN_MIN_MS = 15 * 60 * 1000;

/** KST(UTC+9) 기준 yyyy-MM-dd. 그쪽 클라(SimpleDateFormat KOREA)와 동일 결과. */
function dateStringKst(epochMs) {
    const kst = new Date(epochMs + 9 * 60 * 60 * 1000);
    return kst.toISOString().slice(0, 10);
}

/** Attendance_Session/{courseId}/{sessionDate} 미러링. best-effort. */
async function syncSessionToRtdb({courseId, sessionDate, sessionCode, now, classStartAt, bluetoothEndAt, pinEndAt}) {
    await rtdb.ref(`Attendance_Session/${courseId}/${sessionDate}`).set({
        authMethod: "BLUETOOTH_PIN_UWB",
        pinCode: sessionCode,
        status: "BLUETOOTH_ACTIVE",   // 시간 전환 X — 클라가 bluetoothEndAt/pinEndAt 시간 비교로 페이즈 결정
        startedAt: now,
        bluetoothEndAt,
        pinEndAt,
        classStartAt,
        uwbCheckCount: 0,
    });
}

/** Attendance_Records/{courseId}/{sessionDate}/{studentId} 미러링. best-effort. */
async function syncRecordToRtdb({courseId, sessionDate, studentId, now, classStartAt, bluetoothEndAt}) {
    // 그쪽 원본 로직 재현:
    //   - BLE 페이즈 (now <= bluetoothEndAt) → authMethod="BLUETOOTH", finalStatus="출석"
    //   - PIN 페이즈 (now > bluetoothEndAt) → authMethod="PIN", classStartAt+10min 기준 출석/결석
    const inBluetoothPhase = bluetoothEndAt > 0 && now <= bluetoothEndAt;
    const authMethod = inBluetoothPhase ? "BLUETOOTH" : "PIN";

    let finalStatus = "출석";
    if (!inBluetoothPhase && classStartAt > 0 && now > classStartAt + TEN_MIN_MS) {
        finalStatus = "결석";
    }

    await rtdb.ref(`Attendance_Records/${courseId}/${sessionDate}/${studentId}`).set({
        finalStatus,
        authMethod,
        missedCount: 0,
        checkedAt: now,
    });
}

/**
 * UWB ranging 결과 → RTDB Attendance_Records 미러링.
 * processRangingResult 트랜잭션 직후 호출. best-effort.
 *
 * 우리 Firestore 스키마 ↔ 그쪽 RTDB 스키마 매핑:
 *   - uwbFailCount → missedCount
 *   - status="ABSENT" → finalStatus="결석", status="CHECKED_IN" → 기존 finalStatus 보존
 *
 * 그쪽 원본 가짜 loop는 missedCount를 매 사이클 +1 (성공 여부 무관)했으나,
 * 우리는 진짜 ranging 결과 기반 (연속 실패 카운터). 의미가 다르므로 update만 함 (기존 finalStatus 덮어쓰지 않게 finalStatus는 ABSENT일 때만 갱신).
 */
async function syncUwbResultToRtdb({courseId, sessionDate, studentId, newFailCount, newStatus}) {
    const updates = {
        missedCount: newFailCount,
    };
    if (newStatus === 'ABSENT') {
        updates.finalStatus = '결석';
    }
    await rtdb.ref(`Attendance_Records/${courseId}/${sessionDate}/${studentId}`).update(updates);
}

/**
 * 사이클 1회 시작 시 RTDB Attendance_Session.uwbCheckCount 갱신. best-effort.
 *
 * 옵션 B (server 타임스탬프 추적):
 *   - processRangingResult 수신 시 session.lastUwbCycleAt이 60초 이상 전이면 새 사이클로 간주
 *   - uwbCheckCount += 1
 */
async function syncCycleCountToRtdb({courseId, sessionDate, uwbCheckCount}) {
    await rtdb.ref(`Attendance_Session/${courseId}/${sessionDate}`).update({
        uwbCheckCount,
    });
}

/** 사이클 감지 임계값. RANGING_PERIOD_MINUTES(1분 테스트/5분 prod) 보다 작아야 함. */
const CYCLE_GAP_MS = 60 * 1000;

/**
 * UWB 측정 결과 → 그쪽 RTDB UWB_Logs 미러링.
 *
 * WeekActivity.kt (주간 출결 화면)이 다음 형식으로 읽음:
 *   UWB_Logs/{courseId}/{sessionDate}/{studentId}/{timeKey}:
 *     { detected: boolean, timestamp: string }
 *
 * timeKey는 RTDB 키 제약(`.` `#` `$` `[` `]` `/` 금지) 안전하게 HH_mm_ss 사용.
 * timestamp는 사람 친화적 HH:mm:ss (WeekActivity가 우선 표시).
 * 키에 초까지 포함 → 같은 분 안 여러 측정도 충돌 없음.
 */
async function syncUwbLogToRtdb({courseId, sessionDate, studentId, nowMs, success}) {
    const kst = new Date(nowMs + 9 * 60 * 60 * 1000);
    const hh = String(kst.getUTCHours()).padStart(2, '0');
    const mm = String(kst.getUTCMinutes()).padStart(2, '0');
    const ss = String(kst.getUTCSeconds()).padStart(2, '0');
    const timeKey   = `${hh}_${mm}_${ss}`;
    const timestamp = `${hh}:${mm}:${ss}`;
    await rtdb.ref(`UWB_Logs/${courseId}/${sessionDate}/${studentId}/${timeKey}`).set({
        detected: success,
        timestamp,
    });
}

exports.startAttendanceSession = async ({courseId, professorId, professorUwbAddress, classStartAt}) => {
    const lectureSessionId = db.collection('attendance_sessions').doc().id;
    const status = "ACTIVE";
    let sessionCode;
    // 세션 코드 중복 방지
    for(let i = 0; i < 5; i++) {
        const code = generateSessionCode();
        const existing = await db.collection('attendance_sessions')
            .where('sessionCode', '==', code)
            .where('status', '==', 'ACTIVE')
            .limit(1)
            .get();

        if(existing.empty) {
            sessionCode = code;
            break;
        }
    }

    if(!sessionCode) {
        throw new Error("Failed to generate unique sessionCode");
    }

    // 수업 1회당 1번 생성 → 모든 학생이 동일한 값 공유
    const uwbParams = generateUwbParams(professorUwbAddress);

    const now = new Date();
    const nowMs = now.getTime();
    // classStartAt 없으면 now로 fallback (ad-hoc 세션).
    const safeClassStartAt = (classStartAt && classStartAt > 0) ? classStartAt : nowMs;
    const bluetoothEndAt = nowMs + FIVE_MIN_MS;
    const pinEndAt = safeClassStartAt + FIFTEEN_MIN_MS;
    // sessionDate: 세션 시작 시점 기준 yyyy-MM-dd. 자정 걸쳐도 모든 dual-write가 동일 경로 사용.
    const sessionDate = dateStringKst(nowMs);

    const sessionData = {
        lectureSessionId,
        sessionCode,
        courseId,
        professorId,
        status,
        startTime: now,
        endTime: null,
        uwbParams,
        // 신규: dual-write 화면 시간 계산용 (epoch millis)
        classStartAt: safeClassStartAt,
        bluetoothEndAt,
        pinEndAt,
        // 신규: RTDB 경로 + UWB 사이클 추적
        sessionDate,
        uwbCheckCount: 0,
        lastUwbCycleAt: 0,
    };

    await db.collection('attendance_sessions').doc(lectureSessionId).set(sessionData);

    // best-effort RTDB 미러링 (실패해도 Firestore는 commit 유지)
    try {
        await syncSessionToRtdb({
            courseId, sessionDate, sessionCode,
            now: nowMs,
            classStartAt: safeClassStartAt,
            bluetoothEndAt, pinEndAt,
        });
    } catch (e) {
        console.warn('[dual-write] Attendance_Session RTDB sync failed:', e.message);
    }

    return sessionData;
}

/**
 * 학생 출석 등록.
 *
 * 흐름:
 *   1. sessionCode로 ACTIVE 세션 조회
 *      - 없으면 404 (다른 수업 코드거나 이미 마감)
 *   2. 학생이 그 수업 수강 중인지 RTDB Enrollment 검증
 *      - 미수강이면 403 (다른 강의실 신호 잘못 잡힘 / 부정 출석 차단)
 *   3. 같은 (sessionCode, studentId)로 이미 레코드 있으면 → 그대로 반환 (멱등)
 *   4. 없으면 새 레코드 생성
 *
 * 에러는 err.statusCode로 의미 구분:
 *   - 404: 세션 없음 (클라이언트는 스캔 계속)
 *   - 400: 세션 마감 (CLOSED)
 *   - 403: 수강 안 함 (다른 수업)
 */
exports.checkInAttendance = async ({sessionCode, studentId, studentUwbAddress}) => {
    // 1. ACTIVE 세션 조회 (sessionCode + status 둘 다로 필터)
    //    → CLOSED된 과거 세션이 같은 코드를 갖고 있어도 걸러짐
    const sessionSnap = await db.collection('attendance_sessions')
        .where('sessionCode', '==', sessionCode)
        .where('status', '==', 'ACTIVE')
        .limit(1)
        .get();

    if(sessionSnap.empty) {
        // ACTIVE 세션이 없음 (잘못된 코드거나 이미 마감). 학생 입장에선 동일하게 처리.
        const err = new Error("해당 sessionCode의 활성 세션이 없습니다");
        err.statusCode = 404;
        throw err;
    }

    const session = sessionSnap.docs[0].data();
    const lectureSessionId = session.lectureSessionId;

    // 2. 수강 검증 — RTDB Enrollment/{studentId}/{courseId} = true 인지 확인
    //    courseId는 교수가 /start로 보낸 값 (현재 통합에선 classId 문자열, 예: "10")
    //    Enrollment 키도 동일하게 classId로 통일됨 (FirebaseSeedData.enrollment 참고)
    const enrollmentSnap = await rtdb
        .ref(`Enrollment/${studentId}/${session.courseId}`)
        .once('value');

    if (!enrollmentSnap.exists() || enrollmentSnap.val() !== true) {
        const err = new Error(`수강 중인 수업이 아닙니다 (courseId=${session.courseId})`);
        err.statusCode = 403;
        throw err;
    }

    // 3. 중복 출석 체크 (멱등)
    const existingSnap = await db.collection('attendance_records')
        .where('sessionCode', '==', sessionCode)
        .where('studentId', '==', studentId)
        .limit(1)
        .get();

    if(!existingSnap.empty) {
        const existing = existingSnap.docs[0].data();
        return {
            attendanceId: existing.attendanceId,
            lectureSessionId: existing.lectureSessionId,
            sessionCode: existing.sessionCode,
            studentId: existing.studentId,
            checkInTime: existing.checkInTime.toDate
                ? existing.checkInTime.toDate().toISOString()
                : existing.checkInTime,
            uwbParams: session.uwbParams,
        };
    }

    // 3. 새 레코드 생성
    const attendanceId = db.collection('attendance_records').doc().id;
    const now = new Date();
    const record = {
        attendanceId,
        lectureSessionId,
        sessionCode,
        studentId,
        studentUwbAddress,
        checkInTime: now,
        status: "CHECKED_IN",
        uwbFailCount: 0,
        lastRangingAt: null,
    };

    await db.collection('attendance_records').doc(attendanceId).set(record);

    // best-effort RTDB 미러링 (그쪽 화면이 RTDB Attendance_Records 읽음)
    try {
        await syncRecordToRtdb({
            courseId: session.courseId,
            // sessionDate 미설정인 구버전 세션은 now 기반으로 fallback
            sessionDate: session.sessionDate || dateStringKst(now.getTime()),
            studentId,
            now: now.getTime(),
            classStartAt: session.classStartAt || 0,
            bluetoothEndAt: session.bluetoothEndAt || 0,
        });
    } catch (e) {
        console.warn('[dual-write] Attendance_Records RTDB sync failed:', e.message);
    }

    return {
        attendanceId,
        lectureSessionId,
        sessionCode,
        studentId,
        checkInTime: now.toISOString(),
        uwbParams: session.uwbParams,
    };
}

/**
 * 특정 수업 세션의 CHECKED_IN 학생 명단 + UWB 주소 조회.
 *
 * 용도: 교수 폰이 5분 주기 ranging 루프 진입 시 검증 대상 명단 갱신.
 *
 * 흐름:
 *   1. 세션 문서 ID 직접 조회 (lectureSessionId == 문서 ID)
 *      - 없으면 404 (잘못된 ID)
 *      - status != ACTIVE면 400 (만료된 세션을 polling 못 하게)
 *   2. attendance_records where lectureSessionId AND status == CHECKED_IN
 *      - ABSENT 학생은 ranging 대상 아님 → 제외
 *   3. ranging 루프에 필요한 3개 필드만 골라 응답
 */
exports.getCheckedInStudents = async (lectureSessionId) => {
    const sessionDoc = await db.collection('attendance_sessions').doc(lectureSessionId).get();
    if (!sessionDoc.exists) {
        const err = new Error("세션을 찾을 수 없습니다");
        err.statusCode = 404;
        throw err;
    }
    if (sessionDoc.data().status !== 'ACTIVE') {
        const err = new Error("이미 종료된 세션입니다");
        err.statusCode = 400;
        throw err;
    }

    const snap = await db.collection('attendance_records')
        .where('lectureSessionId', '==', lectureSessionId)
        .where('status', '==', 'CHECKED_IN')
        .get();

    return snap.docs.map(doc => {
        const r = doc.data();
        return {
            studentId: r.studentId,
            attendanceId: r.attendanceId,
            studentUwbAddress: r.studentUwbAddress,
        };
    });
}

/**
 * UWB ranging 결과 1회분 처리.
 *
 * 판정 로직:
 *   connectionFailed=true       → CONNECTION_FAILED, 실패
 *   distance == null || > 20m   → OUT_OF_RANGE, 실패
 *   그 외                       → 성공
 *
 * 트랜잭션 (1개로 묶음):
 *   1. attendance_records 갱신
 *      성공: uwbFailCount=0, lastRangingAt=now
 *      실패: uwbFailCount+=1, 3 도달 시 status=ABSENT (이미 ABSENT면 유지)
 *   2. ranging_logs에 측정 1문서 추가 (불변 이벤트 로그)
 *
 * @returns 최종 status ("CHECKED_IN" | "ABSENT")
 */
exports.processRangingResult = async ({attendanceId, studentId, distance, connectionFailed, lectureSessionId}) => {
    const ABSENT_THRESHOLD = 3;
    const MAX_DISTANCE_METERS = 20;

    // 판정
    let success, failureReason;
    if (connectionFailed) {
        success = false;
        failureReason = 'CONNECTION_FAILED';
    } else if (distance == null || distance > MAX_DISTANCE_METERS) {
        success = false;
        failureReason = 'OUT_OF_RANGE';
    } else {
        success = true;
        failureReason = null;
    }

    const now = new Date();
    const nowMs = now.getTime();
    const recordRef = db.collection('attendance_records').doc(attendanceId);
    const logRef = db.collection('ranging_logs').doc();
    const sessionRef = db.collection('attendance_sessions').doc(lectureSessionId);

    // dual-write에 필요한 session 정보 미리 읽기 (트랜잭션 밖, RTDB path/사이클 감지용)
    const sessionSnap = await sessionRef.get();
    if (!sessionSnap.exists) {
        const err = new Error(`attendance_session ${lectureSessionId} not found`);
        err.statusCode = 404;
        throw err;
    }
    const session = sessionSnap.data();

    // 트랜잭션: record 갱신 + ranging_logs 추가. newFailCount/newStatus 둘 다 리턴.
    const txResult = await db.runTransaction(async (tx) => {
        const recordSnap = await tx.get(recordRef);
        if (!recordSnap.exists) {
            const err = new Error(`attendance_record ${attendanceId} not found`);
            err.statusCode = 404;
            throw err;
        }
        const record = recordSnap.data();

        let newFailCount = record.uwbFailCount || 0;
        let newStatus = record.status;

        if (success) {
            newFailCount = 0;
            // 이미 ABSENT면 그대로 유지 (성공해도 복구 X)
        } else {
            newFailCount += 1;
            if (newFailCount >= ABSENT_THRESHOLD && newStatus === 'CHECKED_IN') {
                newStatus = 'ABSENT';
            }
        }

        tx.update(recordRef, {
            uwbFailCount: newFailCount,
            status: newStatus,
            lastRangingAt: now,
        });

        tx.set(logRef, {
            rangingLogId: logRef.id,
            attendanceId,
            lectureSessionId,
            studentId,
            timestamp: now,
            distance: success ? distance : null,
            failureReason,
            success,
        });

        return {newStatus, newFailCount};
    });

    const finalStatus = txResult.newStatus;
    const newFailCount = txResult.newFailCount;

    // ── 신규: RTDB 미러링 (best-effort) ──────────────────────────
    // sessionDate fallback: 구버전 세션은 없을 수 있음
    const sessionDate = session.sessionDate || dateStringKst(nowMs);
    const courseId = session.courseId;

    // (1) Attendance_Records.missedCount + finalStatus="결석" (ABSENT일 때만)
    try {
        await syncUwbResultToRtdb({
            courseId, sessionDate, studentId, newFailCount, newStatus: finalStatus,
        });
    } catch (e) {
        console.warn('[dual-write] UWB result RTDB sync failed:', e.message);
    }

    // (1b) UWB_Logs 누적 — 주간 출결 화면(WeekActivity)이 detected/timestamp 읽음
    try {
        await syncUwbLogToRtdb({courseId, sessionDate, studentId, nowMs, success});
    } catch (e) {
        console.warn('[dual-write] UWB_Logs RTDB sync failed:', e.message);
    }

    // (2) 사이클 감지: lastUwbCycleAt 60초 이상 전이면 새 사이클로 간주 → uwbCheckCount++
    const lastCycleAt = session.lastUwbCycleAt || 0;
    const isNewCycle = !lastCycleAt || (nowMs - lastCycleAt > CYCLE_GAP_MS);
    if (isNewCycle) {
        const newCycleCount = (session.uwbCheckCount || 0) + 1;
        try {
            await sessionRef.update({
                lastUwbCycleAt: nowMs,
                uwbCheckCount: newCycleCount,
            });
            await syncCycleCountToRtdb({courseId, sessionDate, uwbCheckCount: newCycleCount});
        } catch (e) {
            console.warn('[dual-write] uwbCheckCount sync failed:', e.message);
        }
    }

    return finalStatus;
}
