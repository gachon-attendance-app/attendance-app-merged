package com.example.myapplication

import org.json.JSONArray
import org.json.JSONObject

/**
 * Users/{userId}
 * - userId: 학번
 * - portalID: 포털 ID
 * - userType: STUDENT / PROFESSOR
 */
data class AppUser(
    val userId: String,
    val portalId: String,
    val password: String,
    val name: String,
    val email: String?,
    val userType: String
)

/**
 * Subjects/{subjectCode}
 * - 강의 정보
 */
data class Subject(
    val subjectCode: String,
    val subjectName: String,
    val professorName: String,
    val schedules: List<SubjectSchedule>
)

/**
 * Subjects/{subjectCode}/schedule/dayN
 * - 요일별 시간표 정보
 */
data class SubjectSchedule(
    val dayOfWeek: String,
    val location: String,
    val periods: List<SubjectPeriod>
)

/**
 * Subjects/{subjectCode}/schedule/dayN/periods
 * - 교시별 시작/종료 시간
 */
data class SubjectPeriod(
    val startTime: String,
    val endTime: String
)

/**
 * 시간표 블록 표시용
 */
data class CourseTime(
    val day: String,
    val startHour: Int,
    val endHour: Int
)

/**
 * register_schedule.xml, schedule_1.xml
 * - 앱 화면 표시용 수업 데이터
 */
data class Course(
    val code: String,
    val name: String,
    val professor: String,
    val classroom: String,
    val schedules: List<CourseTime>
)

object FirebaseParsers {

    fun user(json: JSONObject?, key: String): AppUser? {
        if (json == null) return null

        return AppUser(
            userId = json.optString("userId", key),
            portalId = json.optString("portalID", ""),
            password = json.optString("password", ""),
            name = json.optString("name", ""),
            email = json.optStringOrNull("email"),
            userType = json.optString("userType", "STUDENT")
        )
    }

    fun findUserByPortalId(usersJson: JSONObject?, portalId: String): AppUser? {
        if (usersJson == null) return null

        val keys = usersJson.keys()

        while (keys.hasNext()) {
            val key = keys.next()
            val userJson = usersJson.optJSONObject(key) ?: continue
            val user = user(userJson, key) ?: continue

            if (user.portalId == portalId) {
                return user
            }
        }

        return null
    }

    fun subject(json: JSONObject?, fallbackCode: String): Subject? {
        if (json == null) return null

        val scheduleObject = json.optJSONObject("schedule")
        val schedules = mutableListOf<SubjectSchedule>()

        if (scheduleObject != null) {
            val dayKeys = scheduleObject.keys()

            while (dayKeys.hasNext()) {
                val dayKey = dayKeys.next()
                val dayObject = scheduleObject.optJSONObject(dayKey) ?: continue
                val periodsArray = dayObject.optJSONArray("periods")
                val periods = mutableListOf<SubjectPeriod>()

                if (periodsArray != null) {
                    for (i in 0 until periodsArray.length()) {
                        val periodObject = periodsArray.optJSONObject(i) ?: continue
                        periods.add(
                            SubjectPeriod(
                                startTime = periodObject.optString("startTime", ""),
                                endTime = periodObject.optString("endTime", "")
                            )
                        )
                    }
                }

                schedules.add(
                    SubjectSchedule(
                        dayOfWeek = dayObject.optString("dayOfWeek", ""),
                        location = dayObject.optString("location", ""),
                        periods = periods
                    )
                )
            }
        }

        return Subject(
            subjectCode = json.optString("subjectCode", fallbackCode),
            subjectName = json.optString("subjectName", ""),
            professorName = json.optString("professorName", ""),
            schedules = schedules
        )
    }

    fun subjectToCourse(subject: Subject): Course {
        val courseTimes = mutableListOf<CourseTime>()
        var classroom = ""

        subject.schedules.forEach { schedule ->
            if (classroom.isBlank()) {
                classroom = schedule.location
            }

            val startHour = schedule.periods.firstOrNull()?.startTime?.substringBefore(":")?.toIntOrNull()
            val endHour = schedule.periods.lastOrNull()?.endTime?.substringBefore(":")?.toIntOrNull()

            if (startHour != null && endHour != null) {
                courseTimes.add(
                    CourseTime(
                        day = convertDayToKorean(schedule.dayOfWeek),
                        startHour = startHour,
                        endHour = endHour + 1
                    )
                )
            }
        }

        return Course(
            code = subject.subjectCode,
            name = subject.subjectName,
            professor = subject.professorName,
            classroom = classroom,
            schedules = courseTimes
        )
    }

    fun attendanceStatusToKorean(status: String): String {
        return when (status) {
            "PRESENT", "출석" -> "출석"
            "LATE", "지각" -> "지각"
            "ABSENT", "결석" -> "결석"
            "NOT_STARTED", "미출석" -> "미출석"
            else -> status
        }
    }

    fun JSONObject.optStringOrNull(name: String): String? {
        return if (has(name) && !isNull(name)) optString(name) else null
    }

    fun convertDayToKorean(day: String): String {
        return when (day.lowercase()) {
            "monday", "mon", "월" -> "월"
            "tuesday", "tue", "화" -> "화"
            "wednesday", "wed", "수" -> "수"
            "thursday", "thu", "목" -> "목"
            "friday", "fri", "금" -> "금"
            else -> "월"
        }
    }

    fun JSONObject.toPrettyStringSafe(): String {
        return try {
            toString(2)
        } catch (e: Exception) {
            toString()
        }
    }
}