package com.example.myapplication.schedule.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.myapplication.schedule.alarm.ScheduleAlarmManager
import com.example.myapplication.schedule.data.ScheduleDatabase
import com.example.myapplication.schedule.sync.RtdbScheduleSyncManager
import com.example.myapplication.schedule.sync.ScheduleSyncManager
import java.util.concurrent.TimeUnit

/**
 * 시간표를 외부 소스에서 받아 로컬 DB로 동기화하는 WorkManager Worker.
 *
 * 트리거:
 *   - enqueuePeriodic(): 12시간 주기. Stage 3 앱 시작 시 등록
 *   - enqueueOnce(): 즉시 1회. Stage 3 "지금 동기화" 버튼 또는 시간표 첫 등록 시
 *
 * 제약:
 *   - 네트워크 연결 필요 (CONNECTED)
 *   - 실패 시 Result.retry() → WorkManager 자동 지수 백오프
 *
 * 현재 구현은 MockScheduleSyncManager 사용. DB 담당자와 RTDB 스키마 합의 후
 * RtdbScheduleSyncManager로 교체 예정 (한 줄 변경).
 */
class ScheduleSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val studentId = inputData.getString(KEY_STUDENT_ID)
        if (studentId.isNullOrBlank()) {
            Log.e(TAG, "studentId 없음 — 동기화 불가")
            return Result.failure()
        }

        return try {
            val dao = ScheduleDatabase.getInstance(applicationContext).scheduleDao()
            val syncer: ScheduleSyncManager = RtdbScheduleSyncManager(dao)
            val count = syncer.sync(studentId)

            // sync 직후 알람 재예약 (Stage 4: 수업 5분 전 알림)
            val schedules = dao.getAll()
            ScheduleAlarmManager(applicationContext).rescheduleAll(schedules)

            Log.d(TAG, "동기화 성공: $count row, 알람 ${schedules.size}개 재예약, studentId=$studentId")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "동기화 실패 (재시도 예정)", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "ScheduleSyncWorker"
        const val KEY_STUDENT_ID = "studentId"

        private const val UNIQUE_PERIODIC = "schedule_sync_periodic"
        private const val UNIQUE_ONCE = "schedule_sync_once"
        private const val SYNC_INTERVAL_HOURS = 12L

        /**
         * 12시간 주기 동기화 등록. 앱 시작 또는 학번 변경 시 호출.
         * UPDATE 정책: 같은 unique name이 이미 있으면 새 input data로 갱신.
         */
        fun enqueuePeriodic(context: Context, studentId: String) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<ScheduleSyncWorker>(
                SYNC_INTERVAL_HOURS, TimeUnit.HOURS
            )
                .setInputData(workDataOf(KEY_STUDENT_ID to studentId))
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        /**
         * 즉시 1회 동기화. "지금 동기화" 버튼 / 학번 첫 등록 시 호출.
         * REPLACE 정책: 같은 unique name으로 들어온 진행 중 작업이 있으면 교체.
         */
        fun enqueueOnce(context: Context, studentId: String) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<ScheduleSyncWorker>()
                .setInputData(workDataOf(KEY_STUDENT_ID to studentId))
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_ONCE,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
