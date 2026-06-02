package com.example.myapplication.schedule.sync

import android.util.Log
import com.example.myapplication.schedule.data.ScheduleDao
import com.example.myapplication.schedule.data.ScheduleEntity
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Firebase Realtime Database에서 학번별 수강 시간표를 fetch.
 *
 * 데이터 흐름:
 *   1. Enrollment/{studentId} → 수강 과목 ID 목록
 *   2. Subjects/{subjectId} (병렬) → 각 과목의 schedule 노드
 *   3. schedule.day1/day2/... 각각을 ScheduleEntity로 변환
 *   4. dao.replaceAll로 Room에 원자적 저장
 *
 * RTDB 데이터 스키마 (사용자 mobile-cb29c 프로젝트 기준 — 원본에서 JSON Import):
 *
 *   Enrollment/{studentId}/{subjectId} = true
 *
 *   Subjects/{subjectId} = {
 *     subjectName: String,
 *     subjectCode: String,
 *     professorName: String,
 *     schedule: {
 *       day1: {
 *         dayOfWeek: "Friday"   // 영문 첫글자 대문자
 *         location: String,
 *         periods: [null, {startTime: "HH:mm", endTime: "HH:mm"}, ...]
 *       },
 *       day2: { ... }   // 주 N회면 여러 day 키
 *     }
 *   }
 *
 * periods 구조:
 *   - 0번 인덱스는 null (대학교 1교시부터 시작이라 그렇게 저장됨)
 *   - null 아닌 첫 항목의 startTime + null 아닌 마지막 항목의 endTime을 사용
 *     (1교시만 = 그 항목, 2교시 연강 = 1교시 start + 2교시 end)
 */
class RtdbScheduleSyncManager(
    private val dao: ScheduleDao
) : ScheduleSyncManager {

    private val database by lazy {
        FirebaseDatabase.getInstance(RTDB_URL)
    }

    override suspend fun sync(studentId: String): Int = withContext(Dispatchers.IO) {
        // 1) Enrollment에서 수강 과목 ID 목록 가져오기
        val enrollmentSnap = database
            .getReference("Enrollment")
            .child(studentId)
            .get()
            .await()

        if (!enrollmentSnap.exists()) {
            Log.w(TAG, "수강 신청 없음: studentId=$studentId — 시간표 비움")
            dao.replaceAll(emptyList())
            return@withContext 0
        }

        val subjectIds = enrollmentSnap.children.mapNotNull { it.key }
        if (subjectIds.isEmpty()) {
            dao.replaceAll(emptyList())
            return@withContext 0
        }
        Log.d(TAG, "수강 과목 ${subjectIds.size}개: $subjectIds")

        // 2) 각 과목 병렬 fetch + 변환
        val schedules = coroutineScope {
            subjectIds.map { subjectId ->
                async {
                    try {
                        val snap = database.getReference("Subjects").child(subjectId).get().await()
                        parseSubject(snap)
                    } catch (e: Exception) {
                        Log.e(TAG, "과목 fetch 실패: $subjectId", e)
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }

        // 3) Room에 원자적 교체
        dao.replaceAll(schedules)
        Log.d(TAG, "sync 성공: ${schedules.size}개 ScheduleEntity 저장")
        return@withContext schedules.size
    }

    /**
     * 한 Subject 노드 → 0개 이상의 ScheduleEntity로 변환.
     * 주 N회 강의면 day1, day2, ... 각각 하나씩.
     */
    private fun parseSubject(snap: DataSnapshot): List<ScheduleEntity> {
        if (!snap.exists()) return emptyList()
        /*
        val subjectCode = snap.child("subjectCode").getValue(String::class.java)
            ?: snap.key
            ?: return emptyList()
         */

        val subjectCode = snap.key
            ?: snap.child("subjectCode").getValue(String::class.java)
            ?: return emptyList()

        val subjectName = snap.child("subjectName").getValue(String::class.java) ?: subjectCode

        val scheduleNode = snap.child("schedule")
        if (!scheduleNode.exists()) return emptyList()

        return scheduleNode.children.mapNotNull { dayNode ->
            parseDay(dayNode, subjectCode, subjectName)
        }
    }

    /** day1/day2/... 한 노드 → ScheduleEntity 1개 (또는 null). */
    private fun parseDay(
        dayNode: DataSnapshot,
        subjectId: String,
        subjectName: String
    ): ScheduleEntity? {
        val dayOfWeek = dayNode.child("dayOfWeek").getValue(String::class.java)
            ?: return null
        val location = dayNode.child("location").getValue(String::class.java) ?: ""

        // periods는 [null, {st, et}, ...] 또는 SDK가 null 항목을 자동 제거한 형태
        val periodsNode = dayNode.child("periods")
        val timeRanges = periodsNode.children.mapNotNull { p ->
            val st = p.child("startTime").getValue(String::class.java)
            val et = p.child("endTime").getValue(String::class.java)
            if (st != null && et != null) st to et else null
        }
        if (timeRanges.isEmpty()) return null

        val startTime = timeRanges.first().first
        val endTime = timeRanges.last().second

        return ScheduleEntity(
            subjectId = subjectId,
            subjectName = subjectName,
            dayOfWeek = dayOfWeek.uppercase(),   // "Friday" → "FRIDAY"
            startTime = startTime,
            endTime = endTime,
            location = location
        )
    }

    companion object {
        private const val TAG = "RtdbScheduleSyncMgr"

        /**
         * RTDB URL — FirebaseConfig.BASE_URL 단일 진실원천 참조.
         * 프로젝트 전환 시 FirebaseConfig.kt 한 줄만 바꾸면 됨.
         *
         * FirebaseDatabase.getInstance()는 trailing slash 유무 둘 다 허용.
         */
        private val RTDB_URL: String = com.example.myapplication.FirebaseConfig.BASE_URL
    }
}
