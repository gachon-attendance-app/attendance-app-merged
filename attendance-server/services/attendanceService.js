const {db, admin, rtdb} = require('../firebase/firebaseAdmin');
const {generateSessionCode} = require('../utils/codeGenerator');
const {generateUwbParams} = require('../utils/uwbParamsGenerator');

exports.startAttendanceSession = async ({courseId, professorId, professorUwbAddress}) => {
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
    const sessionData = {
        lectureSessionId,
        sessionCode,
        courseId,
        professorId,
        status,
        startTime: now,
        endTime: null,
        uwbParams,
    };

    await db.collection('attendance_sessions').doc(lectureSessionId).set(sessionData);

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
    const recordRef = db.collection('attendance_records').doc(attendanceId);
    const logRef = db.collection('ranging_logs').doc();

    const finalStatus = await db.runTransaction(async (tx) => {
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

        return newStatus;
    });

    return finalStatus;
}
