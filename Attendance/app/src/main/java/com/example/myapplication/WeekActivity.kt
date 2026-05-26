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

class WeekActivity : ComponentActivity() {

    private val expandedMap = mutableMapOf<Int, Boolean>()

    private val studentId = "202234920"
    private val selectedDate = "2026-04-28"
    private val selectedDayOfWeek = "Tuesday"

    private lateinit var rootJson: JSONObject

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.week_1)

        rootJson = loadJsonFromAssets()

        findViewById<TextView>(R.id.tvSelectedDate).text = selectedDate.replace("-", ".")

        initClickEvents()
        loadWeeklyAttendanceFromJson()
    }

    private fun loadJsonFromAssets(): JSONObject {
        val jsonText = assets.open("attendanceapp-cbf00-default-rtdb-export (2).json")
            .bufferedReader()
            .use { it.readText() }

        return JSONObject(jsonText)
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

    private fun loadWeeklyAttendanceFromJson() {
        hideAllItems()

        val enrollmentObject = rootJson
            .optJSONObject("Enrollment")
            ?.optJSONObject(studentId)

        if (enrollmentObject == null) {
            return
        }

        val subjectCodes = mutableListOf<String>()
        val keys = enrollmentObject.keys()

        while (keys.hasNext()) {
            subjectCodes.add(keys.next())
        }

        subjectCodes.take(5).forEachIndexed { position, subjectCode ->
            val index = position + 1
            val item = makeAttendanceItem(subjectCode)

            bindBasicAttendanceItem(index, item)
            renderDetailRows(index, item.uwbRows)
        }
    }

    private fun makeAttendanceItem(subjectCode: String): AttendanceItem {
        val subjectObject = rootJson
            .optJSONObject("Subjects")
            ?.optJSONObject(subjectCode)

        val originalSubjectName = subjectObject
            ?.optString("subjectName", "")
            ?: ""

        val subjectName = cleanSubjectName(originalSubjectName)

        val classTimes = if (subjectObject != null) {
            getClassTimesForSelectedDay(subjectObject)
        } else {
            emptyList()
        }

        val attendanceObject = rootJson
            .optJSONObject("Attendance_Records")
            ?.optJSONObject(subjectCode)
            ?.optJSONObject(selectedDate)
            ?.optJSONObject(studentId)

        val finalStatus = attendanceObject
            ?.optString("finalStatus", "")
            ?: ""

        val uwbRows = getUwbRows(subjectCode)

        return AttendanceItem(
            subjectCode = subjectCode,
            subjectName = subjectName,
            classTimes = classTimes,
            finalStatus = finalStatus,
            uwbRows = uwbRows
        )
    }

    private fun getClassTimesForSelectedDay(subjectObject: JSONObject): List<String> {
        val result = mutableListOf<String>()

        val scheduleObject = subjectObject.optJSONObject("schedule") ?: return result
        val dayKeys = scheduleObject.keys()

        while (dayKeys.hasNext()) {
            val dayKey = dayKeys.next()
            val dayObject = scheduleObject.optJSONObject(dayKey) ?: continue

            val dayOfWeek = dayObject.optString("dayOfWeek", "")

            if (dayOfWeek != selectedDayOfWeek) {
                continue
            }

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

    private fun getUwbRows(subjectCode: String): List<UwbCheckRow> {
        val rows = mutableListOf<UwbCheckRow>()

        val uwbObject = rootJson
            .optJSONObject("UWB_Logs")
            ?.optJSONObject(subjectCode)
            ?.optJSONObject(selectedDate)
            ?.optJSONObject(studentId)

        if (uwbObject == null) {
            return emptyList()
        }

        val timeKeys = uwbObject.keys()

        while (timeKeys.hasNext()) {
            val timeKey = timeKeys.next()
            val logObject = uwbObject.optJSONObject(timeKey) ?: continue

            val timestamp = logObject.optString("timestamp", "")
            val displayTime = if (timestamp.isNotBlank()) {
                timestamp
            } else {
                timeKey.replace("_", ":")
            }

            val detected: Boolean? = when {
                logObject.has("detected") -> logObject.optBoolean("detected")
                logObject.has("isDetected") -> logObject.optBoolean("isDetected")
                else -> null
            }

            val status = when (detected) {
                true -> "출석"
                false -> "미출석"
                null -> ""
            }

            rows.add(
                UwbCheckRow(
                    time = displayTime,
                    status = status
                )
            )
        }

        return rows
    }

    private fun bindBasicAttendanceItem(index: Int, item: AttendanceItem) {
        val itemLayout = findViewById<LinearLayout>(getItemId(index))
        itemLayout.visibility = View.VISIBLE

        val titleTextView = findTitleTextView(itemLayout)
        val timeTextView = findTimeTextView(itemLayout)
        val statusTextView = findStatusTextView(itemLayout)
        val statusIconView = findStatusIconView(itemLayout)

        titleTextView?.text = item.subjectName

        timeTextView?.text = if (item.classTimes.isNotEmpty()) {
            "◷ ${item.classTimes.first()}"
        } else {
            "◷ "
        }

        when (item.finalStatus) {
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

            "결석", "ABSENT" -> {
                statusTextView?.text = "결석"
                statusTextView?.setTextColor(Color.parseColor("#D60000"))
                statusIconView?.setImageResource(R.drawable.absentweek)
            }

            else -> {
                statusTextView?.text = ""
            }
        }
    }

    private fun renderDetailRows(index: Int, rows: List<UwbCheckRow>) {
        val container = findViewById<LinearLayout>(getDetailRowsContainerId(index))
        container.removeAllViews()

        for (row in rows) {
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

    private fun findTitleTextView(parent: LinearLayout): TextView? {
        return findTextViewByTextColor(parent, "#004B83")
    }

    private fun findTimeTextView(parent: LinearLayout): TextView? {
        return findTextViewContains(parent, "◷")
    }

    private fun findStatusTextView(parent: LinearLayout): TextView? {
        return findTextViewByStatus(parent)
    }

    private fun findStatusIconView(parent: LinearLayout): ImageView? {
        return findLastImageView(parent)
    }

    private fun findTextViewByTextColor(view: View, colorHex: String): TextView? {
        if (view is TextView) {
            if (view.currentTextColor == Color.parseColor(colorHex)) {
                return view
            }
        }

        if (view is LinearLayout) {
            for (i in 0 until view.childCount) {
                val result = findTextViewByTextColor(view.getChildAt(i), colorHex)
                if (result != null) return result
            }
        }

        return null
    }

    private fun findTextViewContains(view: View, keyword: String): TextView? {
        if (view is TextView) {
            if (view.text.toString().contains(keyword)) {
                return view
            }
        }

        if (view is LinearLayout) {
            for (i in 0 until view.childCount) {
                val result = findTextViewContains(view.getChildAt(i), keyword)
                if (result != null) return result
            }
        }

        return null
    }

    private fun findTextViewByStatus(view: View): TextView? {
        if (view is TextView) {
            val text = view.text.toString()
            if (text == "출석" || text == "지각" || text == "결석") {
                return view
            }
        }

        if (view is LinearLayout) {
            for (i in 0 until view.childCount) {
                val result = findTextViewByStatus(view.getChildAt(i))
                if (result != null) return result
            }
        }

        return null
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