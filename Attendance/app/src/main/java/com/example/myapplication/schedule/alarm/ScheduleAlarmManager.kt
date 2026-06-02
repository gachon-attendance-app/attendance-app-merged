package com.example.myapplication.schedule.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.myapplication.schedule.data.ScheduleEntity
import com.example.myapplication.schedule.receiver.ClassReminderReceiver

/**
 * 시간표를 AlarmManager 알람으로 등록/취소하는 매니저.
 *
 * 동작:
 *   - rescheduleAll: 이전 알람 전부 취소 → 현재 시간표로 새로 예약
 *   - schedule: 한 schedule을 다음 발생 시각의 5분 전으로 예약
 *   - cancelAll: SharedPreferences에 저장된 request code 기반으로 모두 취소
 *
 * Request code 전략:
 *   - alarmRequestCode = (subjectId + dayOfWeek + startTime).hashCode()
 *   - 같은 schedule이면 항상 같은 코드 → setExactAndAllowWhileIdle이 자동 덮어쓰기
 *   - replaceAll로 schedule이 사라질 때만 명시적 cancel 필요
 *
 * 권한:
 *   - Android 12+: canScheduleExactAlarms() 체크. false면 inexact set()으로 fallback.
 *     사용자에게 권한 안내 UI는 후속 PR.
 */
class ScheduleAlarmManager(private val context: Context) {

    /**
     * 시간표 전체 알람 재예약. sync 직후 / 부팅 후 호출.
     * 이전 알람을 모두 취소하고 현재 schedules로 새로 등록.
     */
    fun rescheduleAll(schedules: List<ScheduleEntity>) {
        cancelAll()
        schedules.forEach { schedule(it) }
        val codes = mutableListOf<Int>()
        schedules.forEach {
            codes.add(it.alarmRequestCode(isAutoStart = false))
            codes.add(it.alarmRequestCode(isAutoStart = true))
        }
        persistRequestCodes(codes)
        Log.d(TAG, "rescheduleAll: ${schedules.size * 2}개 알람 등록")
    }

    /** 한 schedule을 시작 시각의 5분 전(알림용)과 정각(출석 시작용)으로 각각 예약. */
    fun schedule(schedule: ScheduleEntity) {
        val reminderMillis = TimeUtils.nextOccurrenceMillis(
            schedule.dayOfWeek,
            schedule.startTime,
            offsetMinutes = -REMINDER_OFFSET_MINUTES
        )
        val startMillis = TimeUtils.nextOccurrenceMillis(
            schedule.dayOfWeek,
            schedule.startTime,
            offsetMinutes = 0
        )
        
        val reminderPi = buildPendingIntent(schedule, isAutoStart = false)
        val startPi = buildPendingIntent(schedule, isAutoStart = true)
        
        val am = context.getSystemService(AlarmManager::class.java) ?: return

        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || am.canScheduleExactAlarms()

        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminderMillis, reminderPi)
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, startMillis, startPi)
        } else {
            Log.w(TAG, "exact alarm 권한 없음 — inexact set()으로 fallback")
            am.set(AlarmManager.RTC_WAKEUP, reminderMillis, reminderPi)
            am.set(AlarmManager.RTC_WAKEUP, startMillis, startPi)
        }
        Log.d(TAG, "schedule: ${schedule.subjectName} reminder=$reminderMillis, start=$startMillis")
    }

    /** 이전에 등록된 모든 알람 취소. SharedPreferences에 저장된 request code 사용. */
    fun cancelAll() {
        val previousCodes = loadRequestCodes()
        if (previousCodes.isEmpty()) return

        val am = context.getSystemService(AlarmManager::class.java) ?: return
        previousCodes.forEach { code ->
            val intent = Intent(context, ClassReminderReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context, code, intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pi != null) {
                am.cancel(pi)
                pi.cancel()
            }
        }
        clearRequestCodes()
        Log.d(TAG, "cancelAll: ${previousCodes.size}개 알람 취소")
    }

    // ── PendingIntent 빌드 ────────────────────────────────────

    private fun buildPendingIntent(schedule: ScheduleEntity, isAutoStart: Boolean): PendingIntent {
        val intent = Intent(context, ClassReminderReceiver::class.java).apply {
            putExtra(ClassReminderReceiver.EXTRA_SUBJECT_ID, schedule.subjectId)
            putExtra(ClassReminderReceiver.EXTRA_SUBJECT_NAME, schedule.subjectName)
            putExtra(ClassReminderReceiver.EXTRA_DAY_OF_WEEK, schedule.dayOfWeek)
            putExtra(ClassReminderReceiver.EXTRA_START_TIME, schedule.startTime)
            putExtra(ClassReminderReceiver.EXTRA_END_TIME, schedule.endTime)
            putExtra(ClassReminderReceiver.EXTRA_LOCATION, schedule.location)
            putExtra(ClassReminderReceiver.EXTRA_IS_AUTO_START, isAutoStart)
        }
        return PendingIntent.getBroadcast(
            context,
            schedule.alarmRequestCode(isAutoStart),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ── Request code persistence ──────────────────────────────

    private fun persistRequestCodes(codes: List<Int>) {
        val joined = codes.joinToString(",")
        prefs().edit().putString(KEY_REQUEST_CODES, joined).apply()
    }

    private fun loadRequestCodes(): List<Int> {
        val joined = prefs().getString(KEY_REQUEST_CODES, null) ?: return emptyList()
        if (joined.isEmpty()) return emptyList()
        return joined.split(",").mapNotNull { it.toIntOrNull() }
    }

    private fun clearRequestCodes() {
        prefs().edit().remove(KEY_REQUEST_CODES).apply()
    }

    private fun prefs() =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "ScheduleAlarmManager"
        const val REMINDER_OFFSET_MINUTES = 5
        private const val PREFS_NAME = "schedule_alarm_prefs"
        private const val KEY_REQUEST_CODES = "alarm_request_codes"
    }
}

/** ScheduleEntity → 안정적 alarm request code 매핑. */
internal fun ScheduleEntity.alarmRequestCode(isAutoStart: Boolean): Int {
    val suffix = if (isAutoStart) "START" else "REMINDER"
    return "$subjectId|$dayOfWeek|$startTime|$suffix".hashCode()
}
