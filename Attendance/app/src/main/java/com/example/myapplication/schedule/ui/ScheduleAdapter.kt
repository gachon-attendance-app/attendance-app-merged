package com.example.myapplication.schedule.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.schedule.data.ScheduleEntity

/**
 * 시간표 RecyclerView 어댑터.
 *
 * 행 포맷: "월 14:00~14:50  운영체제  공학관 301"
 * DiffUtil로 부분 갱신 — Stage 2 sync 후 자동으로 변경된 항목만 다시 그림.
 */
class ScheduleAdapter : ListAdapter<ScheduleEntity, ScheduleAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_schedule, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val dayChip: TextView = itemView.findViewById(R.id.scheduleDayChip)
        private val timeText: TextView = itemView.findViewById(R.id.scheduleTimeText)
        private val subjectText: TextView = itemView.findViewById(R.id.scheduleSubjectText)
        private val locationText: TextView = itemView.findViewById(R.id.scheduleLocationText)

        fun bind(schedule: ScheduleEntity) {
            dayChip.text = dayOfWeekShort(schedule.dayOfWeek)
            timeText.text = "${schedule.startTime}~${schedule.endTime}"
            subjectText.text = schedule.subjectName
            locationText.text = schedule.location
        }

        /** "MONDAY" → "월" 매핑. 알 수 없으면 첫 글자 대문자만. */
        private fun dayOfWeekShort(dayOfWeek: String): String = when (dayOfWeek.uppercase()) {
            "MONDAY"    -> "월"
            "TUESDAY"   -> "화"
            "WEDNESDAY" -> "수"
            "THURSDAY"  -> "목"
            "FRIDAY"    -> "금"
            "SATURDAY"  -> "토"
            "SUNDAY"    -> "일"
            else -> dayOfWeek.take(1).uppercase()
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ScheduleEntity>() {
            override fun areItemsTheSame(old: ScheduleEntity, new: ScheduleEntity): Boolean =
                old.id == new.id

            override fun areContentsTheSame(old: ScheduleEntity, new: ScheduleEntity): Boolean =
                old == new
        }
    }
}
