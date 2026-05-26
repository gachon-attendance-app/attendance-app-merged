package com.example.myapplication.schedule.ui

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.schedule.data.ScheduleDatabase
import kotlinx.coroutines.launch

/**
 * Java MainActivity2에서 한 줄로 시간표 RecyclerView를 살리는 헬퍼.
 *
 * 사용법 (Java):
 *   new ScheduleViewBinder(this, scheduleRecycler).attach();
 *
 * 동작:
 *   - LinearLayoutManager 설정
 *   - ScheduleAdapter 부착
 *   - dao.observeAll() Flow를 lifecycle-aware하게 collect (STARTED~STOPPED 구간만)
 *   - DB 변경(Stage 2 sync 등)이 일어나면 자동으로 어댑터 갱신
 *
 * Java에서 직접 Flow를 다루기 까다로워서 Kotlin에 위임.
 */
class ScheduleViewBinder(
    private val activity: AppCompatActivity,
    private val recyclerView: RecyclerView
) {

    fun attach() {
        val adapter = ScheduleAdapter()
        recyclerView.layoutManager = LinearLayoutManager(activity)
        recyclerView.adapter = adapter

        val dao = ScheduleDatabase.getInstance(activity).scheduleDao()
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                dao.observeAll().collect { schedules ->
                    adapter.submitList(schedules)
                }
            }
        }
    }
}
