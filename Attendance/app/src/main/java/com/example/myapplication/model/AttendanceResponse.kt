package com.example.myapplication.model

/**
 * main_p_1.xml
 * - btnProfessorAttendanceCheck: 교수 출석 시작 버튼
 * - tvPinDigit1~4: PIN 4자리 표시
 */
data class StartAttendanceResponse(
    val success: Boolean,
    val sessionId: Int?,
    val classId: Int?,
    val pinCode: String?,
    val bluetoothEnabled: Boolean?,
    val startedAt: String?,
    val pinExpiresAt: String?,
    val message: String?
)

/**
 * main1.xml
 * - btnAttendance: 학생 출석 버튼
 * - 블루투스 감지 결과 전송
 */
data class BluetoothCheckRequest(
    val sessionId: Int,
    val studentId: Int,
    val classId: Int,
    val detectedDeviceId: String,
    val rssi: Int,
    val checkedAt: String
)

/**
 * pin.xml
 * - PIN 입력값 전송
 */
data class PinCheckRequest(
    val sessionId: Int,
    val studentId: Int,
    val pinCode: String
)

/**
 * UWB 중간 출석 체크
 * - detected: UWB 감지 성공 여부
 * - distance: 감지 거리
 */
data class UwbCheckRequest(
    val sessionId: Int,
    val studentId: Int,
    val classId: Int,
    val detected: Boolean,
    val distance: Double?,
    val checkedAt: String
)

/**
 * main1.xml, pin.xml
 * - tvAttendanceStatus: 출석 결과 표시
 */
data class AttendanceCheckResponse(
    val success: Boolean,
    val status: String?,
    val message: String?
)

/**
 * UWB 중간 출석 체크 결과
 * - currentStatus: 현재 출석 상태
 * - missedUwbCount: UWB 미인증 횟수
 */
data class UwbCheckResponse(
    val success: Boolean,
    val currentStatus: String?,
    val missedUwbCount: Int?,
    val message: String?
)

/**
 * main_p_1.xml
 * - tvClassName: 수업명
 * - tvClassTime: 수업 시간
 * - tvAttendanceRate: 출석률
 * - tvLateRate: 지각률
 * - tvAbsentRate: 결석률
 * - tvUwbCheckCount: UWB 체크 횟수
 * - layoutStudentAttendanceRows: 학생별 출석 표
 */
data class ProfessorAttendanceStatusResponse(
    val success: Boolean,
    val classId: Int?,
    val courseName: String?,
    val classTime: String?,
    val attendanceRate: Int?,
    val lateRate: Int?,
    val absentRate: Int?,
    val uwbCheckCount: Int?,
    val students: List<StudentAttendanceItem>,
    val message: String?
)

/**
 * main_p_1.xml
 * - 학생별 출석 표 한 줄 데이터
 */
data class StudentAttendanceItem(
    val studentId: String,
    val name: String,
    val status: String
)

/**
 * all_attendance.xml, all_attendance_rate.xml
 * - 과목별 출결 통계 목록
 */
data class AttendanceSummaryResponse(
    val success: Boolean,
    val studentId: Int?,
    val courses: List<CourseAttendanceSummary>,
    val message: String?
)

/**
 * all_attendance.xml, all_attendance_rate.xml
 * - courseName: 과목명
 * - presentRate: 출석률
 * - lateRate: 지각률
 * - absentRate: 결석률
 */
data class CourseAttendanceSummary(
    val classId: Int?,
    val courseName: String?,
    val presentCount: Int?,
    val lateCount: Int?,
    val absentCount: Int?,
    val presentRate: Int?,
    val lateRate: Int?,
    val absentRate: Int?
)