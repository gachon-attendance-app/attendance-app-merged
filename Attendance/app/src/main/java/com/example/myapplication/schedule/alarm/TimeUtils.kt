package com.example.myapplication.schedule.alarm

import java.util.Calendar

/**
 * 시간표 정보 → 다음 발화 timestamp 변환 유틸.
 *
 * java.time을 쓰지 않는 이유: minSdk 24에선 desugaring 없이는 사용 불가.
 * Calendar API는 모든 SDK에서 동작.
 */
object TimeUtils {

    /**
     * "MONDAY" + "14:00" + offsetMinutes(-5) → 가장 가까운 미래의 월요일 13:55의
     * 시스템 시각(ms). 그 시각이 이미 지났으면 +7일 후.
     *
     * @param dayOfWeek "MONDAY".."SUNDAY" (java.time.DayOfWeek.name 형식)
     * @param startTime "HH:mm" 24시간 형식
     * @param offsetMinutes 시각 보정 (음수 = 이전, 양수 = 이후). 5분 전 알림은 -5
     */
    fun nextOccurrenceMillis(
        dayOfWeek: String,
        startTime: String,
        offsetMinutes: Int = 0
    ): Long {
        val parts = startTime.split(":")
        require(parts.size == 2) { "startTime must be HH:mm, got: $startTime" }
        val hour = parts[0].toInt()
        val minute = parts[1].toInt()
        val targetDay = dayOfWeekToCalendar(dayOfWeek)

        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // 같은 주 안에서 target day로 이동
        val currentDay = cal.get(Calendar.DAY_OF_WEEK)
        val daysToAdd = (targetDay - currentDay + 7) % 7
        cal.add(Calendar.DAY_OF_YEAR, daysToAdd)
        cal.add(Calendar.MINUTE, offsetMinutes)

        // 그 시각이 이미 지났으면 +7일
        if (cal.timeInMillis <= now) {
            cal.add(Calendar.DAY_OF_YEAR, 7)
        }

        return cal.timeInMillis
    }

    /** "MONDAY" → Calendar.MONDAY (=2) 매핑. */
    private fun dayOfWeekToCalendar(dayOfWeek: String): Int = when (dayOfWeek.uppercase()) {
        "SUNDAY"    -> Calendar.SUNDAY
        "MONDAY"    -> Calendar.MONDAY
        "TUESDAY"   -> Calendar.TUESDAY
        "WEDNESDAY" -> Calendar.WEDNESDAY
        "THURSDAY"  -> Calendar.THURSDAY
        "FRIDAY"    -> Calendar.FRIDAY
        "SATURDAY"  -> Calendar.SATURDAY
        else -> throw IllegalArgumentException("Unknown dayOfWeek: $dayOfWeek")
    }
}
