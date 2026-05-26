package com.example.myapplication.schedule.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * 시간표 DAO.
 *
 * 주요 호출자:
 *   - Stage 2: ScheduleSyncManager가 RTDB fetch 후 replaceAll로 갱신
 *   - Stage 3: UI(MainActivity2 등)가 observeAll Flow로 자동 갱신 수신
 *   - Stage 4(후속): ScheduleAlarmManager가 getByDayOfWeek로 알람 등록 대상 조회
 *
 * 트랜잭션 메서드 replaceAll:
 *   sync 시 deleteAll → insertAll을 묶음. 중간에 앱이 죽으면 둘 다 롤백 → 빈 DB 방지.
 *
 * Flow observeAll:
 *   Room이 DB 변경을 감지해 자동 emit. UI는 collect만 하면 sync 직후 자동 갱신.
 */
@Dao
interface ScheduleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(schedules: List<ScheduleEntity>)

    @Query("SELECT * FROM schedules ORDER BY dayOfWeek, startTime")
    suspend fun getAll(): List<ScheduleEntity>

    @Query("SELECT * FROM schedules ORDER BY dayOfWeek, startTime")
    fun observeAll(): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules WHERE dayOfWeek = :day ORDER BY startTime")
    suspend fun getByDayOfWeek(day: String): List<ScheduleEntity>

    @Query("DELETE FROM schedules")
    suspend fun deleteAll()

    /**
     * 원자적 전체 교체. sync 시 사용.
     * 중간 실패 시 DB가 기존 상태로 롤백 — 빈 DB로 끝나지 않음.
     */
    @Transaction
    suspend fun replaceAll(schedules: List<ScheduleEntity>) {
        deleteAll()
        insertAll(schedules)
    }
}
