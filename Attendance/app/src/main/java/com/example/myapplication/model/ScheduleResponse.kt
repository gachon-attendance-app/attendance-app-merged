package com.example.myapplication.model

/**
 * register_schedule.xml
 * - etCourseCode: 강의 코드 입력값
 * - btnAddClass: 강의 추가 버튼
 */
data class CourseLookupResponse(
    val success: Boolean,
    val course: CourseInfo?,
    val message: String?
)

/**
 * register_schedule.xml
 * - btnConfirmSchedule: 시간표 확정 버튼
 * - classIds: 최종 등록할 수업 classId 목록
 */
data class SaveScheduleRequest(
    val classIds: List<Int>
)

/**
 * register_schedule.xml
 * - 시간표 저장 결과
 */
data class SaveScheduleResponse(
    val success: Boolean,
    val studentId: Int?,
    val classes: List<CourseInfo>,
    val message: String?
)

/**
 * schedule_1.xml, mypage.xml
 * - 저장된 시간표 조회 결과
 */
data class ScheduleResponse(
    val success: Boolean,
    val studentId: Int?,
    val classes: List<CourseInfo>,
    val message: String?
)

/**
 * register_schedule.xml, schedule_1.xml, mypage.xml, main1.xml
 * - classId: 수업 고유 ID
 * - courseCode: 강의 코드
 * - courseName: 강의명
 * - professorName: 교수명
 * - dayOfWeek: 요일
 * - startTime: 시작 시간
 * - endTime: 종료 시간
 * - room: 강의실
 * - semester: 학기
 */
data class CourseInfo(
    val classId: Int,
    val courseCode: String,
    val courseName: String,
    val professorName: String,
    val dayOfWeek: String,
    val startTime: String,
    val endTime: String,
    val room: String,
    val semester: String?
)

/**
 * main1.xml
 * - tvCurrentClassName: 현재 수업명
 * - tvPeriod: 현재 수업 시간
 * - tvAttendanceStatus: 출석 상태
 * - btnAttendance: 출석 버튼
 */
data class CurrentClassResponse(
    val success: Boolean,
    val hasClass: Boolean,
    val classId: Int?,
    val courseCode: String?,
    val courseName: String?,
    val professorName: String?,
    val room: String?,
    val startTime: String?,
    val endTime: String?,
    val attendanceStatus: String?,
    val attendanceMessage: String?,
    val sessionId: Int?,
    val message: String?
)

/**
 * week_1.xml, week_2.xml
 * - 날짜별 출결 상태 목록
 */
data class AttendanceCalendarResponse(
    val success: Boolean,
    val month: String?,
    val days: List<AttendanceDay>,
    val message: String?
)

/**
 * week_1.xml, week_2.xml
 * - date: 날짜
 * - classId: 수업 ID
 * - courseName: 과목명
 * - status: PRESENT / LATE / ABSENT / NOT_STARTED
 */
data class AttendanceDay(
    val date: String,
    val classId: Int?,
    val courseName: String?,
    val status: String
)