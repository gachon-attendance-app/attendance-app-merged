package com.example.myapplication.schedule.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 시간표 도메인 Room 데이터베이스.
 *
 * 설계 결정:
 *   - 클래스명 ScheduleDatabase (AppDatabase 아님): 추후 다른 도메인 DB가 생겨도
 *     이름 충돌 없게 도메인별로 분리.
 *   - exportSchema=false: v1이라 마이그레이션 불필요. 마이그레이션 도입 시 true로
 *     전환하고 schemaLocation을 build.gradle에 지정.
 *   - fallbackToDestructiveMigration 미사용: 스키마 변경 시 학생 시간표가
 *     통째로 날아가지 않도록. v2 이후 진짜 Migration 객체를 작성해서 추가.
 *   - Singleton (double-checked locking): Application 수준 단일 인스턴스.
 *
 * 호출 예:
 *   val dao = ScheduleDatabase.getInstance(context).scheduleDao()
 */
@Database(
    entities = [ScheduleEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ScheduleDatabase : RoomDatabase() {

    abstract fun scheduleDao(): ScheduleDao

    companion object {
        private const val DB_NAME = "attendance_schedule.db"

        @Volatile
        private var INSTANCE: ScheduleDatabase? = null

        fun getInstance(context: Context): ScheduleDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ScheduleDatabase::class.java,
                    DB_NAME
                ).build().also { INSTANCE = it }
            }
        }
    }
}
