package com.example.myapplication.schedule.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 학생이 듣는 한 차시 강의를 표현하는 Room 엔티티.
 *
 * 데이터 소스: Firebase Realtime Database (`student_schedules/{studentId}/...`)
 *   → Stage 2의 ScheduleSyncManager가 fetch해서 Room에 저장
 *
 * 필드 포맷:
 *   - dayOfWeek: java.time.DayOfWeek.name 형식 ("MONDAY", "TUESDAY", ...)
 *     사전식 정렬이 요일 자연 순서와 일치하진 않으므로 정렬은 코드에서 후처리하거나
 *     enum으로 변환해 정렬. UI에서 한국어 라벨("월", "화") 변환은 별도 헬퍼.
 *   - startTime/endTime: "HH:mm" 24시간 형식 ("14:00", "09:30")
 *     문자열 비교가 시각 비교와 일치 (사전식 == 시간순).
 *   - subjectId: 학사시스템 학수번호 (예: "CS101"). 같은 과목이 주 2회면 row 2개.
 */
@Entity(tableName = "schedules")
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subjectId: String,
    val subjectName: String,
    val dayOfWeek: String,
    val startTime: String,
    val endTime: String,
    val location: String
)
