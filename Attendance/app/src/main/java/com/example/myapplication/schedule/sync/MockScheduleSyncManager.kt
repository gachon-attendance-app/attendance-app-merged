package com.example.myapplication.schedule.sync

import com.example.myapplication.schedule.data.ScheduleDao
import com.example.myapplication.schedule.data.ScheduleEntity

/**
 * 하드코딩된 시간표를 반환하는 mock 구현.
 *
 * 용도:
 *   - DB 담당자가 Firebase RTDB 스키마를 만들기 전까지의 임시 구현
 *   - Stage 3 UI 개발 / Stage 4 알람 등록 검증을 위한 데이터 소스
 *
 * 학번을 무시하고 항상 같은 시간표를 반환. 실제 구현(RtdbScheduleSyncManager)
 * 도입 후엔 이 클래스는 androidTest나 디버그 빌드에서만 사용.
 */
class MockScheduleSyncManager(
    private val dao: ScheduleDao
) : ScheduleSyncManager {

    override suspend fun sync(studentId: String): Int {
        // mock: studentId 무시
        dao.replaceAll(MOCK_DATA)
        return MOCK_DATA.size
    }

    companion object {
        /**
         * 테스트용 시간표 — 월~금 분산 + 같은 과목 주 2회 케이스 포함.
         * 시간은 사람이 알아보기 좋은 정각 + 50분 강의 형태.
         */
        internal val MOCK_DATA: List<ScheduleEntity> = listOf(
            ScheduleEntity(
                subjectId = "TEST_NOW",
                subjectName = "곧 시작할 테스트 수업",
                dayOfWeek = "FRIDAY",      // ← 오늘 요일
                startTime = "02:12",        // ← 현재 + 8분 (= 3분 뒤 발화)
                endTime = "02:28",
                location = "테스트 강의실"
            ),
            ScheduleEntity(
                subjectId = "CS101", subjectName = "운영체제",
                dayOfWeek = "MONDAY", startTime = "14:00", endTime = "14:50",
                location = "공학관 301"
            ),
            ScheduleEntity(
                subjectId = "CS101", subjectName = "운영체제",
                dayOfWeek = "WEDNESDAY", startTime = "14:00", endTime = "14:50",
                location = "공학관 301"
            ),
            ScheduleEntity(
                subjectId = "CS201", subjectName = "자료구조",
                dayOfWeek = "TUESDAY", startTime = "10:00", endTime = "10:50",
                location = "공학관 205"
            ),
            ScheduleEntity(
                subjectId = "MATH101", subjectName = "미적분학",
                dayOfWeek = "THURSDAY", startTime = "09:00", endTime = "09:50",
                location = "자연대 102"
            ),
            ScheduleEntity(
                subjectId = "ENG101", subjectName = "영어회화",
                dayOfWeek = "FRIDAY", startTime = "13:00", endTime = "13:50",
                location = "인문대 401"
            ),
        )
    }
}
