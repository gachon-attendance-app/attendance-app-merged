package com.example.myapplication

import android.graphics.Color
import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WeekActivity : ComponentActivity() {

    private val expandedMap = mutableMapOf<Int, Boolean>()

    private val testCourseId = "10"
    private val studentId = "202234920"
    private val selectedDate = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())
    private val selectedDayOfWeek = SimpleDateFormat("EEEE", Locale.US).format(Date())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.week_1)

        findViewById<TextView>(R.id.tvSelectedDate).text = selectedDate.replace("-", ".")

        initClickEvents()
        loadTestAttendanceFromRtdb()
    }

    private fun initClickEvents() {
        setExpandableClick(1)
        setExpandableClick(2)
        setExpandableClick(3)
        setExpandableClick(4)
        setExpandableClick(5)
    }

    private fun setExpandableClick(index: Int) {
        expandedMap[index] = false

        val item = findViewById<LinearLayout>(getItemId(index))
        val collapseButton = findViewById<TextView>(getCollapseButtonId(index))

        item.setOnClickListener {
            toggleDetail(index)
        }

        collapseButton.setOnClickListener {
            toggleDetail(index)
        }
    }

    private fun toggleDetail(index: Int) {
        val listContainer = findViewById<LinearLayout>(R.id.listContainer)
        val detailArea = findViewById<LinearLayout>(getDetailAreaId(index))

        val isExpanded = expandedMap[index] ?: false

        val transition = AutoTransition()
        transition.duration = 180
        TransitionManager.beginDelayedTransition(listContainer, transition)

        if (isExpanded) {
            detailArea.visibility = View.GONE
            expandedMap[index] = false
        } else {
            detailArea.visibility = View.VISIBLE
            expandedMap[index] = true
        }
    }

    private fun loadTestAttendanceFromRtdb() {
        hideAllItems()

        FirebaseClient.get("Subjects/$testCourseId") { subjectJson ->
            FirebaseClient.get("Attendance_Records/$testCourseId/$selectedDate/$studentId") { recordJson ->
                FirebaseClient.get("UWB_Logs/$testCourseId/$selectedDate/$studentId") { uwbJson ->
                    val item = makeAttendanceItem(testCourseId, subjectJson, recordJson, uwbJson)
                    bindBasicAttendanceItem(1, item)
                    renderDetailRows(1, item.uwbRows)
                }
            }
        }
    }

    private fun makeAttendanceItem(
        subjectCode: String,
        subjectObject: JSONObject?,
        attendanceObject: JSONObject?,
        uwbObject: JSONObject?
    ): AttendanceItem {
        val originalSubjectName = subjectObject?.optString("subjectName", "").orEmpty()
        val subjectName = cleanSubjectName(originalSubjectName).ifBlank {
            "테스트 수업 $subjectCode"
        }

        val classTimes = if (subjectObject != null) {
            getClassTimesForSelectedDay(subjectObject)
        } else {
            emptyList()
        }

        val finalStatus = attendanceObject?.optString("finalStatus", "").orEmpty()

        return AttendanceItem(
            subjectCode = subjectCode,
            subjectName = subjectName,
            classTimes = classTimes,
            finalStatus = finalStatus,
            uwbRows = getUwbRows(uwbObject)
        )
    }

    private fun getClassTimesForSelectedDay(subjectObject: JSONObject): List<String> {
        val result = mutableListOf<String>()

        val scheduleObject = subjectObject.optJSONObject("schedule") ?: return result
        val dayKeys = scheduleObject.keys()

        while (dayKeys.hasNext()) {
            val dayObject = scheduleObject.optJSONObject(dayKeys.next()) ?: continue
            if (dayObject.optString("dayOfWeek", "") != selectedDayOfWeek) continue

            val periodsArray = dayObject.optJSONArray("periods") ?: continue
            for (i in 0 until periodsArray.length()) {
                val periodObject = periodsArray.optJSONObject(i) ?: continue
                val startTime = periodObject.optString("startTime", "")
                val endTime = periodObject.optString("endTime", "")

                if (startTime.isNotBlank() && endTime.isNotBlank()) {
                    result.add("$startTime ~ $endTime")
                }
            }
        }

        return result
    }

    private fun getUwbRows(uwbObject: JSONObject?): List<UwbCheckRow> {
        if (uwbObject == null) return emptyList()

        val rows = mutableListOf<UwbCheckRow>()
        val timeKeys = uwbObject.keys()

        while (timeKeys.hasNext()) {
            val timeKey = timeKeys.next()
            val logObject = uwbObject.optJSONObject(timeKey) ?: continue

            val timestamp = logObject.optString("timestamp", "")
            val displayTime = timestamp.ifBlank { timeKey.replace("_", ":") }

            val detected = when {
                logObject.has("detected") -> logObject.optBoolean("detected")
                logObject.has("isDetected") -> logObject.optBoolean("isDetected")
                else -> null
            }

            rows.add(
                UwbCheckRow(
                    time = displayTime,
                    status = when (detected) {
                        true -> "출석"
                        false -> "미인증"
                        null -> ""
                    }
                )
            )
        }

        return rows.sortedBy { it.time }
    }

    private fun bindBasicAttendanceItem(index: Int, item: AttendanceItem) {
        val itemLayout = findViewById<LinearLayout>(getItemId(index))
        itemLayout.visibility = View.VISIBLE

        val textViews = collectTextViews(itemLayout)
        val titleTextView = textViews.getOrNull(0)
        val timeTextView = textViews.getOrNull(1)
        val statusTextView = textViews.getOrNull(2)
        val statusIconView = findLastImageView(itemLayout)

        titleTextView?.text = item.subjectName
        timeTextView?.text = if (item.classTimes.isNotEmpty()) {
            item.classTimes.first()
        } else {
            selectedDate
        }

        when (normalizeStatus(item.finalStatus)) {
            "출석" -> {
                statusTextView?.text = "출석"
                statusTextView?.setTextColor(Color.parseColor("#004B83"))
                statusIconView?.setImageResource(R.drawable.attendanceweek)
            }

            "지각" -> {
                statusTextView?.text = "지각"
                statusTextView?.setTextColor(Color.parseColor("#9C00B8"))
                statusIconView?.setImageResource(R.drawable.lateweek)
            }

            "결석" -> {
                statusTextView?.text = "결석"
                statusTextView?.setTextColor(Color.parseColor("#D60000"))
                statusIconView?.setImageResource(R.drawable.absentweek)
            }

            else -> {
                statusTextView?.text = "미출석"
                statusTextView?.setTextColor(Color.parseColor("#777777"))
                statusIconView?.setImageResource(R.drawable.absentweek)
            }
        }
    }

    private fun renderDetailRows(index: Int, rows: List<UwbCheckRow>) {
        val container = findViewById<LinearLayout>(getDetailRowsContainerId(index))
        container.removeAllViews()

        val displayRows = if (rows.isEmpty()) {
            listOf(UwbCheckRow("UWB 기록 없음", ""))
        } else {
            rows
        }

        for (row in displayRows) {
            val rowLayout = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dpToPx(18)
                )
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val timeText = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
                text = row.time
                textSize = 12f
                setTextColor(Color.parseColor("#555555"))
            }

            val statusText = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                text = row.status
                textSize = 12f
                setTextColor(Color.parseColor("#555555"))
                gravity = Gravity.END
            }

            rowLayout.addView(timeText)
            rowLayout.addView(statusText)
            container.addView(rowLayout)
        }
    }

    private fun hideAllItems() {
        for (index in 1..5) {
            findViewById<LinearLayout>(getItemId(index)).visibility = View.GONE
        }
    }

    private fun cleanSubjectName(name: String): String {
        return name
            .replace(" (영어강의)", "")
            .replace(" (실시간화상강의)", "")
            .trim()
    }

    private fun normalizeStatus(status: String): String {
        return when (status) {
            "출석", "출석 완료", "異쒖꽍" -> "출석"
            "지각", "吏媛?" -> "지각"
            "결석", "寃곗꽍", "ABSENT" -> "결석"
            else -> ""
        }
    }

    private fun collectTextViews(view: View): List<TextView> {
        val result = mutableListOf<TextView>()
        collectTextViewsInto(view, result)
        return result
    }

    private fun collectTextViewsInto(view: View, result: MutableList<TextView>) {
        if (view is TextView) {
            result.add(view)
            return
        }

        if (view is LinearLayout) {
            for (i in 0 until view.childCount) {
                collectTextViewsInto(view.getChildAt(i), result)
            }
        }
    }

    private fun findLastImageView(view: View): ImageView? {
        var found: ImageView? = null

        if (view is ImageView) {
            found = view
        }

        if (view is LinearLayout) {
            for (i in 0 until view.childCount) {
                val result = findLastImageView(view.getChildAt(i))
                if (result != null) {
                    found = result
                }
            }
        }

        return found
    }

    private fun getItemId(index: Int): Int {
        return when (index) {
            1 -> R.id.itemAttendance1
            2 -> R.id.itemAttendance2
            3 -> R.id.itemAttendance3
            4 -> R.id.itemAttendance4
            else -> R.id.itemAttendance5
        }
    }

    private fun getDetailAreaId(index: Int): Int {
        return when (index) {
            1 -> R.id.detailArea1
            2 -> R.id.detailArea2
            3 -> R.id.detailArea3
            4 -> R.id.detailArea4
            else -> R.id.detailArea5
        }
    }

    private fun getDetailRowsContainerId(index: Int): Int {
        return when (index) {
            1 -> R.id.detailRowsContainer1
            2 -> R.id.detailRowsContainer2
            3 -> R.id.detailRowsContainer3
            4 -> R.id.detailRowsContainer4
            else -> R.id.detailRowsContainer5
        }
    }

    private fun getCollapseButtonId(index: Int): Int {
        return when (index) {
            1 -> R.id.btnCollapse1
            2 -> R.id.btnCollapse2
            3 -> R.id.btnCollapse3
            4 -> R.id.btnCollapse4
            else -> R.id.btnCollapse5
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    data class AttendanceItem(
        val subjectCode: String = "",
        val subjectName: String = "",
        val classTimes: List<String> = emptyList(),
        val finalStatus: String = "",
        val uwbRows: List<UwbCheckRow> = emptyList()
    )

    data class UwbCheckRow(
        val time: String = "",
        val status: String = ""
    )
}
