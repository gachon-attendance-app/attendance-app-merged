package com.example.myapplication.schedule.sync

/**
 * 시간표 동기화 인터페이스.
 *
 * 학번에 해당하는 시간표를 외부 소스(현재 mock, 추후 Firebase Realtime Database)에서
 * fetch해서 로컬 Room DB에 저장. 구현체 교체로 데이터 소스 변경 가능.
 *
 * 호출자:
 *   - ScheduleSyncWorker: 12시간 주기 자동 동기화
 *   - Stage 3 UI: "지금 동기화" 버튼
 *
 * 구현체:
 *   - MockScheduleSyncManager: 하드코딩 데이터 (Stage 2 — DB 담당자 스키마 합의 전)
 *   - RtdbScheduleSyncManager: Firebase RTDB fetch (후속 PR — 스키마 합의 후)
 */
interface ScheduleSyncManager {

    /**
     * 학번에 해당하는 시간표를 외부에서 받아와 로컬 DB에 원자적으로 교체.
     *
     * @param studentId 동기화 대상 학번
     * @return 동기화된 시간표 row 개수
     * @throws Exception 네트워크 / 파싱 등 일시적 오류 — 호출자가 재시도 결정
     */
    suspend fun sync(studentId: String): Int
}
