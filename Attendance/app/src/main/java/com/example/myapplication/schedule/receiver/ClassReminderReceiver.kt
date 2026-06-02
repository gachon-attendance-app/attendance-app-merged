package com.example.myapplication.schedule.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.myapplication.MainActivity
import com.example.myapplication.R
import com.example.myapplication.schedule.alarm.ScheduleAlarmManager
import com.example.myapplication.schedule.data.ScheduleDatabase
import com.example.myapplication.schedule.data.ScheduleEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 수업 시작 5분 전 알람 수신 → heads-up 알림 발사.
 *
 * 동작:
 *   1) extras에서 시간표 정보 추출
 *   2) class_reminder 채널 보장 (IMPORTANCE_HIGH)
 *   3) 알림 발사 — 과목명 + 시간 + 장소. 탭하면 MainActivity 열림.
 *   4) goAsync로 다음 주 같은 시각 재예약 (DB에 schedule 여전히 있을 때만)
 *
 * 합본의 자동 출석 시작은 **하지 않음** — 학생이 알림 보고 직접 출석 시작.
 */
class ClassReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val subjectId = intent.getStringExtra(EXTRA_SUBJECT_ID) ?: return
        val subjectName = intent.getStringExtra(EXTRA_SUBJECT_NAME) ?: return
        val dayOfWeek = intent.getStringExtra(EXTRA_DAY_OF_WEEK) ?: return
        val startTime = intent.getStringExtra(EXTRA_START_TIME) ?: return
        val endTime = intent.getStringExtra(EXTRA_END_TIME) ?: ""
        val location = intent.getStringExtra(EXTRA_LOCATION) ?: ""

        Log.d(TAG, "알람 발화: $subjectName ($dayOfWeek $startTime)")

        val isAutoStart = intent.getBooleanExtra(EXTRA_IS_AUTO_START, false)

        if (isAutoStart) {
            // ── 자동 출석 시작 (정각) ─────────────────────────
            val loginPref = context.getSharedPreferences("LOGIN_INFO", Context.MODE_PRIVATE)
            val userId = loginPref.getString("userId", "") ?: ""
            val userRole = loginPref.getString("userRole", "") ?: ""

            if (userId.isNotBlank()) {
                if (userRole == "student") {
                    val serviceIntent = Intent(context, com.example.myapplication.service.StudentAttendanceService::class.java).apply {
                        action = com.example.myapplication.service.StudentAttendanceService.ACTION_START
                        putExtra(com.example.myapplication.service.StudentAttendanceService.EXTRA_STUDENT_ID, userId)
                    }
                    androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
                    Log.d(TAG, "자동 출석 시작: 학생($userId)")
                } else if (userRole == "professor") {
                    val serviceIntent = Intent(context, com.example.myapplication.service.ProfessorAttendanceService::class.java).apply {
                        action = com.example.myapplication.service.ProfessorAttendanceService.ACTION_START
                        putExtra(com.example.myapplication.service.ProfessorAttendanceService.EXTRA_COURSE_ID, subjectId)
                        putExtra(com.example.myapplication.service.ProfessorAttendanceService.EXTRA_PROFESSOR_ID, userId)
                        putExtra(com.example.myapplication.service.ProfessorAttendanceService.EXTRA_CLASS_START_AT, 0L)
                    }
                    androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
                    Log.d(TAG, "자동 출석 시작: 교수($userId, $subjectId)")
                }
            }
        } else {
            // 1) 채널 보장 + 2) 알림 발사 (5분 전)
            ensureChannel(context)
            postNotification(context, subjectId, subjectName, startTime, location)
        }

        // 3) 다음 주 재예약 — DB에 schedule 여전히 있을 때만
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = ScheduleDatabase.getInstance(context).scheduleDao()
                val stillExists = dao.getByDayOfWeek(dayOfWeek).any {
                    it.subjectId == subjectId && it.startTime == startTime
                }
                if (stillExists) {
                    val schedule = ScheduleEntity(
                        subjectId = subjectId,
                        subjectName = subjectName,
                        dayOfWeek = dayOfWeek,
                        startTime = startTime,
                        endTime = endTime,
                        location = location
                    )
                    ScheduleAlarmManager(context).schedule(schedule)
                    Log.d(TAG, "다음 주 재예약 완료: $subjectName")
                } else {
                    Log.d(TAG, "DB에 schedule 없음 — 재예약 안 함: $subjectName")
                }
            } catch (e: Exception) {
                Log.e(TAG, "재예약 실패", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    // ── 알림 발사 ─────────────────────────────────────────────

    private fun postNotification(
        context: Context,
        subjectId: String,
        subjectName: String,
        startTime: String,
        location: String
    ) {
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPi = PendingIntent.getActivity(
            context,
            REQUEST_CODE_TAP,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val body = if (location.isNotBlank()) {
            "곧 $startTime 시작 — $location"
        } else {
            "곧 $startTime 시작"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(subjectName)
            .setContentText(body)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(tapPi)
            .build()

        val notifId = "$subjectId|$startTime".hashCode()
        val nm = context.getSystemService(NotificationManager::class.java)
        nm?.notify(notifId, notification)
    }

    // ── 채널 보장 ─────────────────────────────────────────────

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        // 이미 있으면 createNotificationChannel은 no-op이라 매번 호출해도 안전.
        val channel = NotificationChannel(
            CHANNEL_ID,
            "수업 시작 알림",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "수업 시작 5분 전 알림"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400)
            lockscreenVisibility = androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audioAttrs = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build()
            setSound(soundUri, audioAttrs)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "ClassReminderReceiver"
        const val CHANNEL_ID = "class_reminder"
        private const val REQUEST_CODE_TAP = 9001

        const val EXTRA_SUBJECT_ID = "subjectId"
        const val EXTRA_SUBJECT_NAME = "subjectName"
        const val EXTRA_DAY_OF_WEEK = "dayOfWeek"
        const val EXTRA_START_TIME = "startTime"
        const val EXTRA_END_TIME = "endTime"
        const val EXTRA_LOCATION = "location"
        const val EXTRA_IS_AUTO_START = "isAutoStart"
    }
}
