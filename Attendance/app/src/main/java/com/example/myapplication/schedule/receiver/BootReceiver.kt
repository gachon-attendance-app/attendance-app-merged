package com.example.myapplication.schedule.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.myapplication.schedule.alarm.ScheduleAlarmManager
import com.example.myapplication.schedule.data.ScheduleDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 폰 재부팅 후 시간표 알람을 자동 재등록.
 *
 * AlarmManager는 재부팅 시 모든 알람을 소거. 우리는 Room에 시간표를 유지하므로
 * 부팅 직후 DB 읽어서 ScheduleAlarmManager.rescheduleAll로 복구.
 *
 * 시간표가 비어있으면(학번 미등록 등) 0개 등록 — 안전 no-op.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.d(TAG, "BOOT_COMPLETED 수신 — 시간표 알람 재예약 시작")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val schedules = ScheduleDatabase.getInstance(context).scheduleDao().getAll()
                ScheduleAlarmManager(context).rescheduleAll(schedules)
                Log.d(TAG, "재예약 완료: ${schedules.size}개")
            } catch (e: Exception) {
                Log.e(TAG, "재예약 실패", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
