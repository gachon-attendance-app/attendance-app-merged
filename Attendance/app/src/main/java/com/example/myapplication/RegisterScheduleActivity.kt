package com.example.myapplication

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

class RegisterScheduleActivity : Activity() {

    private lateinit var etCourseCode: EditText
    private lateinit var btnAddClass: Button
    private lateinit var btnConfirmSchedule: Button
    private lateinit var classBlockLayer: FrameLayout

    private val selectedCourses = mutableListOf<Course>()
    private var userId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.register_schedule)

        userId = getSharedPreferences("LOGIN_INFO", MODE_PRIVATE)
            .getString("userId", "") ?: ""

        etCourseCode = findViewById(R.id.etCourseCode)
        btnAddClass = findViewById(R.id.btnAddClass)
        btnConfirmSchedule = findViewById(R.id.btnConfirmSchedule)
        classBlockLayer = findViewById(R.id.classBlockLayer)

        btnAddClass.setOnClickListener {
            val inputCode = etCourseCode.text.toString().trim()

            if (inputCode.isEmpty()) {
                Toast.makeText(this, "과목 코드를 입력해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lookupSubject(inputCode)
        }

        btnConfirmSchedule.setOnClickListener {
            if (selectedCourses.isEmpty()) {
                Toast.makeText(this, "최소 1개 이상의 수업을 추가해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            saveEnrollment()
        }
    }

    private fun lookupSubject(subjectCode: String) {
        FirebaseClient.get("Subjects/$subjectCode") { json ->
            val subject = FirebaseParsers.subject(json, subjectCode)

            if (subject == null) {
                Toast.makeText(this, "등록되지 않은 과목 코드입니다.", Toast.LENGTH_SHORT).show()
                return@get
            }

            val course = FirebaseParsers.subjectToCourse(subject)
            addCourseIfPossible(course)
        }
    }

    private fun addCourseIfPossible(course: Course) {
        if (selectedCourses.any { it.code == course.code }) {
            Toast.makeText(this, "이미 추가된 과목입니다.", Toast.LENGTH_SHORT).show()
            return
        }

        if (hasTimeConflict(course)) {
            Toast.makeText(this, "이미 등록된 수업과 시간이 겹칩니다.", Toast.LENGTH_SHORT).show()
            return
        }

        selectedCourses.add(course)
        etCourseCode.text.clear()

        addCourseToTimeTable(course)

        Toast.makeText(this, course.name + " 수업이 추가되었습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun saveEnrollment() {
        if (userId.isBlank()) {
            Toast.makeText(this, "로그인 정보가 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        saveNextEnrollment(0)
    }

    private fun saveNextEnrollment(index: Int) {
        if (index >= selectedCourses.size) {
            Toast.makeText(this, "시간표가 저장되었습니다.", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        val course = selectedCourses[index]

        FirebaseClient.put("Enrollment/$userId/${course.code}", org.json.JSONObject().put("value", true)) {
            FirebaseClient.put("Enrollment/$userId/${course.code}", org.json.JSONObject.NULL as? org.json.JSONObject ?: org.json.JSONObject()) {
                FirebaseClient.putRawBoolean("Enrollment/$userId/${course.code}", true) {
                    saveNextEnrollment(index + 1)
                }
            }
        }
    }

    private fun addCourseToTimeTable(course: Course) {
        val colors = listOf("#8FA2C7", "#B9AAA5", "#79B2B8", "#A7B58D", "#C39DA4")
        val color = colors[(selectedCourses.size - 1) % colors.size]

        for (time in course.schedules) {
            val block = TextView(this).apply {
                text = course.name + "\n" + course.classroom
                setTextColor(Color.WHITE)
                textSize = 10f
                gravity = Gravity.CENTER
                setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
                setBackgroundColor(Color.parseColor(color))
            }

            val params = FrameLayout.LayoutParams(
                getColumnWidth(),
                getBlockHeight(time.startHour, time.endHour)
            )

            params.leftMargin = getLeftMarginByDay(time.day)
            params.topMargin = getTopMarginByHour(time.startHour)

            classBlockLayer.addView(block, params)
        }
    }

    private fun hasTimeConflict(newCourse: Course): Boolean {
        for (selectedCourse in selectedCourses) {
            for (selectedTime in selectedCourse.schedules) {
                for (newTime in newCourse.schedules) {
                    val sameDay = selectedTime.day == newTime.day
                    val overlap = selectedTime.startHour < newTime.endHour &&
                            newTime.startHour < selectedTime.endHour

                    if (sameDay && overlap) {
                        return true
                    }
                }
            }
        }

        return false
    }

    private fun getColumnWidth(): Int {
        val width = classBlockLayer.width

        return if (width > 0) {
            width / 5
        } else {
            val screenWidth = resources.displayMetrics.widthPixels
            val horizontalPadding = dpToPx(24 + 24 + 18 + 8 + 28)
            (screenWidth - horizontalPadding) / 5
        }
    }

    private fun getLeftMarginByDay(day: String): Int {
        val columnWidth = getColumnWidth()

        return when (day) {
            "월" -> columnWidth * 0
            "화" -> columnWidth * 1
            "수" -> columnWidth * 2
            "목" -> columnWidth * 3
            "금" -> columnWidth * 4
            else -> 0
        }
    }

    private fun getTopMarginByHour(hour: Int): Int {
        val oneHourHeight = dpToPx(52)

        return when (hour) {
            9 -> oneHourHeight * 0
            10 -> oneHourHeight * 1
            11 -> oneHourHeight * 2
            12 -> oneHourHeight * 3
            13 -> oneHourHeight * 4
            14 -> oneHourHeight * 5
            15 -> oneHourHeight * 6
            16 -> oneHourHeight * 7
            else -> 0
        }
    }

    private fun getBlockHeight(startHour: Int, endHour: Int): Int {
        return ((endHour - startHour).coerceAtLeast(1)) * dpToPx(52)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}