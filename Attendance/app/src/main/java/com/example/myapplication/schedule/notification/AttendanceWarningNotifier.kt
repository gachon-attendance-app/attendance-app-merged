package com.example.myapplication.schedule.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.myapplication.MainActivity
import com.example.myapplication.R

/**
 * 출석 위험 상황(ABSENT 등)을 heads-up 푸시 알림으로 통보.
 *
 * 사용처:
 *   - StudentAttendanceService.onAbsent: UWB ranging 3회 연속 실패로 결석 전이 시
 *   - (후속) BT off / 네트워크 끊김 등 다른 경고 상황도 추가 가능
 *
 * 채널 'attendance_warn' (IMPORTANCE_HIGH):
 *   - Foreground Service 채널(LOW)과 분리 — 충돌 없이 강한 알림 가능
 *   - 사운드 + 진동 + 잠금화면 표시
 *
 * 알림 ID 1003 — 기존 ID(1001/1002 = Foreground)와 충돌 안 함.
 * 탭하면 MainActivity 열림 (NEW_TASK | CLEAR_TOP).
 *
 * Java 호출 예: AttendanceWarningNotifier.notifyAbsent(this, attendanceId)
 */
object AttendanceWarningNotifier {

    private const val CHANNEL_ID = "attendance_warn"
    private const val CHANNEL_NAME = "출석 경고"
    private const val CHANNEL_DESC = "결석 처리 등 출석 위험 상황 알림"

    private const val NOTIFICATION_ID_ABSENT = 1003
    private const val REQUEST_CODE_TAP = 9002

    /**
     * ABSENT 전이 시 호출. 푸시 알림 + heads-up.
     * @param context applicationContext 권장 (Service에서 호출 시 this도 OK)
     * @param attendanceId 로깅/추적용. null 가능.
     */
    @JvmStatic
    fun notifyAbsent(context: Context, attendanceId: String?) {
        ensureChannel(context)

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPi = PendingIntent.getActivity(
            context,
            REQUEST_CODE_TAP,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("출석 결석 처리")
            .setContentText("이번 수업 출석이 결석으로 전환되었습니다")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(tapPi)
            .build()

        val nm = context.getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID_ABSENT, notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = CHANNEL_DESC
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 250, 500)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audioAttrs = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build()
            setSound(soundUri, audioAttrs)
        }
        // createNotificationChannel은 idempotent (같은 ID 재호출 시 no-op).
        nm.createNotificationChannel(channel)
    }
}
