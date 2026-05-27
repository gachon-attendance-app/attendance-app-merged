package com.example.myapplication

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.example.myapplication.launcher.AttendanceServiceLauncher
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class MainActivity : Activity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var contentFrame: FrameLayout

    private var currentPageResId: Int = R.layout.main1
    private var userId: String = ""
    private var userName: String = ""
    private var userRole: String = "student"
    private var currentSubjectCode: String = ""

    private var currentClassName: String = "모바일 프로그래밍"
    private var currentClassTime: String = "10:00 ~ 10:50"
    private var currentClassStartTime: String = "10:00"

    private val handler = Handler(Looper.getMainLooper())
    private var pinPopupShowing = false
    private var uwbRunnable: Runnable? = null
    private var attendanceRefreshRunnable: Runnable? = null

    /** 출석 Service trigger + 권한 흐름 + Service→Activity broadcast 수신 헬퍼. */
    private lateinit var launcher: AttendanceServiceLauncher

    /** 페이즈 UI 전환(15분 후 After15+UWB 카드) 클라이언트 timer. */
    private var phaseTransitionRunnable: Runnable? = null

    companion object {
        private const val DEFAULT_SUBJECT_CODE = "14454001"
        private const val BLUE_ACTIVE = "#015EB6"
        private const val FIVE_MINUTES = 5 * 60 * 1000L
        private const val TEN_MINUTES = 10 * 60 * 1000L
        private const val FIFTEEN_MINUTES = 15 * 60 * 1000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        readLoginInfo()

        setContentView(R.layout.activity_drawer_host)

        drawerLayout = findViewById(R.id.drawerLayout)
        contentFrame = findViewById(R.id.contentFrame)

        // 출석 Service 통합 헬퍼 — 권한/Service trigger/broadcast 수신 캡슐화
        launcher = AttendanceServiceLauncher(this)
        launcher.setListener(sessionListener)

        if (userRole == "professor") {
            loadPage(R.layout.main_p_1)
        } else {
            loadPage(R.layout.main1)
        }

        setupDrawerMenuClick()
    }

    override fun onResume() {
        super.onResume()
        launcher.registerReceiver()
    }

    override fun onPause() {
        super.onPause()
        launcher.unregisterReceiver()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        launcher.handlePermissionResult(requestCode, grantResults)
    }

    override fun onDestroy() {
        super.onDestroy()
        uwbRunnable?.let { handler.removeCallbacks(it) }
        attendanceRefreshRunnable?.let { handler.removeCallbacks(it) }
        phaseTransitionRunnable?.let { handler.removeCallbacks(it) }
    }

    /**
     * AttendanceServiceLauncher → MainActivity broadcast 수신 콜백.
     *
     * dual-write 구조에서 server가 RTDB에 데이터 mirror하므로 그쪽 화면(FirebaseClient.get 기반)이
     * 다음 refresh 시 자동 반영됨. 여기선 Toast / 즉시 UI 갱신만 담당.
     */
    private val sessionListener = object : AttendanceServiceLauncher.SessionEventsListener {
        // 교수: /start 성공 — 받은 4자리 PIN을 즉시 UI에 표시
        override fun onSessionStarted(sessionCode: String?, lectureSessionId: String?) {
            val pageView = contentFrame.getChildAt(0) ?: return
            showPin(pageView, sessionCode ?: "")
            Toast.makeText(
                this@MainActivity,
                "출석체크가 시작되었습니다 (PIN: $sessionCode)",
                Toast.LENGTH_SHORT
            ).show()
        }

        // 교수: /start 실패 또는 학생: check-in 실패
        override fun onSessionFailed(reason: String?) {
            Toast.makeText(this@MainActivity, "출석 시작 실패: $reason", Toast.LENGTH_LONG).show()
        }

        // 교수: 5분 BLE 광고 종료 (Service는 계속 살아있음 — PIN 수동 입력 계속 가능)
        override fun onSessionExpired() {
            Toast.makeText(
                this@MainActivity,
                "BLE 광고 종료 (PIN 수동 입력 계속 가능)",
                Toast.LENGTH_SHORT
            ).show()
        }

        // 학생: BLE 또는 PIN으로 출석 등록 성공
        override fun onAttendanceConfirmed(sessionCode: String?) {
            val pageView = contentFrame.getChildAt(0) ?: return
            setText(pageView, "tvAttendanceStatus", "출석")
            Toast.makeText(this@MainActivity, "출석 처리되었습니다", Toast.LENGTH_SHORT).show()
            // RTDB 미러링도 다음 refresh에 반영됨 — 즉시 버튼 상태 갱신
            refreshStudentAttendanceButtonState(pageView)
        }

        // 학생: check-in 실패 (잘못된 PIN / 미수강 등)
        override fun onAttendanceFailed(reason: String?) {
            Toast.makeText(this@MainActivity, "출석 실패: $reason", Toast.LENGTH_LONG).show()
        }

        // 학생: UWB ranging 3회 연속 실패 → ABSENT
        override fun onAttendanceAbsent(attendanceId: String?) {
            val pageView = contentFrame.getChildAt(0) ?: return
            setText(pageView, "tvAttendanceStatus", "결석")
            AlertDialog.Builder(this@MainActivity)
                .setTitle("결석 처리")
                .setMessage("UWB 재실 검증에 3회 연속 실패하여 결석 처리되었습니다.")
                .setPositiveButton("확인", null)
                .show()
        }
    }

    private fun readLoginInfo() {
        val pref = getSharedPreferences("LOGIN_INFO", MODE_PRIVATE)
        userId = pref.getString("userId", "") ?: ""
        userName = pref.getString("userName", "") ?: ""
        userRole = pref.getString("userRole", "student") ?: "student"
    }

    private fun loadPage(layoutResId: Int) {
        currentPageResId = layoutResId
        attendanceRefreshRunnable?.let { handler.removeCallbacks(it) }
        attendanceRefreshRunnable = null
        contentFrame.removeAllViews()

        val pageView = LayoutInflater.from(this).inflate(layoutResId, contentFrame, false)
        contentFrame.addView(pageView)

        connectTopMenuButton(pageView)
        connectBottomMenu(pageView)
        loadJsonDataForPage(layoutResId, pageView)
    }

    private fun loadJsonDataForPage(layoutResId: Int, pageView: View) {
        when (layoutResId) {
            R.layout.main1 -> {
                loadCurrentClass(pageView)
                setupStudentAttendanceButton(pageView)
            }

            R.layout.main_p_1 -> {
                loadProfessorPage(pageView)
                pageView.findViewById<View?>(R.id.btnProfessorAttendanceCheck)?.setOnClickListener {
                    startAttendanceSession(pageView)
                }
                pageView.findViewById<View?>(R.id.btnRollCallAttendance)?.setOnClickListener {
                    Toast.makeText(this, "호명출석 기능은 출석체크 시작 전만 사용할 수 있습니다", Toast.LENGTH_SHORT).show()
                }
            }

            R.layout.schedule_1 -> {
                loadSchedule(pageView)
            }

            R.layout.mypage -> {
                loadMyPage(pageView)
                loadSchedule(pageView)
            }

            R.layout.week_1,
            R.layout.week_2 -> {
                loadAttendanceCalendar(pageView)
            }

            R.layout.all_attendance -> {
                loadAttendanceSummary(pageView)
            }
        }
    }

    private fun connectTopMenuButton(pageView: View) {
        pageView.findViewById<View?>(R.id.btnMenu)?.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.END)
        }
    }

    private fun connectBottomMenu(pageView: View) {
        val btnHome = pageView.findViewById<View?>(R.id.btnBottomHome)
        val btnRefresh = pageView.findViewById<View?>(R.id.btnBottomRefresh)
        val btnNotice = pageView.findViewById<View?>(R.id.btnBottomNotice)
        val btnSchedule = pageView.findViewById<View?>(R.id.btnBottomSchedule)
        val btnLogout = pageView.findViewById<View?>(R.id.btnBottomLogout)

        btnHome?.setOnClickListener {
            if (userRole == "professor") {
                loadPage(R.layout.main_p_1)
            } else {
                loadPage(R.layout.main1)
            }
        }

        btnRefresh?.setOnClickListener {
            loadPage(currentPageResId)
            Toast.makeText(this, "새로고침되었습니다", Toast.LENGTH_SHORT).show()
        }

        btnNotice?.setOnClickListener {
            if (userRole == "professor") {
                loadPage(R.layout.notice_2)
            } else {
                loadPage(R.layout.notice_1)
            }
        }

        btnSchedule?.setOnClickListener {
            loadPage(R.layout.schedule_1)
        }

        btnLogout?.setOnClickListener {
            logout()
        }
    }

    private fun setupDrawerMenuClick() {
        findViewById<View?>(R.id.menuMyPage)?.setOnClickListener { moveTo(R.layout.mypage) }
        findViewById<View?>(R.id.menuSchedule)?.setOnClickListener { moveTo(R.layout.schedule_1) }
        findViewById<View?>(R.id.menuWeekAttendance)?.setOnClickListener { moveTo(R.layout.week_1) }
        findViewById<View?>(R.id.menuAllAttendance)?.setOnClickListener { moveTo(R.layout.all_attendance) }
        findViewById<View?>(R.id.menuConfirmPeriod)?.setOnClickListener { moveTo(R.layout.confirm_1) }
        findViewById<View?>(R.id.menuConfirmOfficial)?.setOnClickListener { moveTo(R.layout.confirm_2) }

        findViewById<View?>(R.id.menuNotice)?.setOnClickListener {
            if (userRole == "professor") moveTo(R.layout.notice_2) else moveTo(R.layout.notice_1)
        }

        findViewById<View?>(R.id.menuCancel)?.setOnClickListener {
            if (userRole == "professor") moveTo(R.layout.cancel_2) else moveTo(R.layout.cancel_1)
        }
    }

    private fun moveTo(layoutResId: Int) {
        drawerLayout.closeDrawer(GravityCompat.END)
        loadPage(layoutResId)
    }

    private fun loadCurrentClass(pageView: View) {
        FirebaseClient.get("Enrollment/$userId") { enrollmentJson ->
            val subjectCode = enrollmentJson?.keys()?.asSequence()?.firstOrNull()

            if (subjectCode.isNullOrBlank()) {
                setText(pageView, "tvDate", todayText())
                setText(pageView, "tvPeriod", "현재 수업 없음")
                setText(pageView, "tvAttendanceStatus", "출석 전")
                return@get
            }

            currentSubjectCode = subjectCode

            FirebaseClient.get("Subjects/$subjectCode") { subjectJson ->
                val subject = FirebaseParsers.subject(subjectJson, subjectCode)

                if (subject == null) {
                    setText(pageView, "tvDate", todayText())
                    setText(pageView, "tvPeriod", "수업 정보 없음")
                    setText(pageView, "tvAttendanceStatus", "출석 전")
                    return@get
                }

                val firstSchedule = subject.schedules.firstOrNull()
                val firstPeriod = firstSchedule?.periods?.firstOrNull()
                val lastPeriod = firstSchedule?.periods?.lastOrNull()

                currentClassName = subject.subjectName
                currentClassStartTime = firstPeriod?.startTime ?: "10:00"
                currentClassTime = "${firstPeriod?.startTime ?: "10:00"} ~ ${lastPeriod?.endTime ?: "10:50"}"

                setText(pageView, "tvDate", todayText())
                setText(pageView, "tvPeriod", "1교시")
                setText(pageView, "tvAttendanceStatus", "미출석")
            }
        }
    }

    private fun setupStudentAttendanceButton(pageView: View) {
        val btnAttendance = pageView.findViewById<Button?>(R.id.btnAttendance) ?: return
        setAttendanceButtonInactive(btnAttendance)

        attendanceRefreshRunnable?.let { handler.removeCallbacks(it) }
        attendanceRefreshRunnable = object : Runnable {
            override fun run() {
                if (currentPageResId == R.layout.main1) {
                    refreshStudentAttendanceButtonState(pageView)
                    handler.postDelayed(this, 3000L)
                }
            }
        }
        handler.postDelayed(attendanceRefreshRunnable!!, 500L)
    }

    private fun refreshStudentAttendanceButtonState(pageView: View) {
        val btnAttendance = pageView.findViewById<Button?>(R.id.btnAttendance) ?: return
        val today = apiDateText()

        if (currentSubjectCode.isBlank()) {
            currentSubjectCode = DEFAULT_SUBJECT_CODE
        }

        FirebaseClient.get("Attendance_Records/$currentSubjectCode/$today/$userId") { recordJson ->
            val currentStatus = recordJson?.optString("finalStatus", "") ?: ""
            if (currentStatus == "출석" || currentStatus == "異쒖꽍" ||
                currentStatus == "결석" || currentStatus == "寃곗꽍") {
                setAttendanceButtonInactive(btnAttendance)
                setText(pageView, "tvAttendanceStatus", currentStatus)
                btnAttendance.setOnClickListener {
                    Toast.makeText(this, "이미 출석 처리되었습니다", Toast.LENGTH_SHORT).show()
                }
                return@get
            }

            refreshStudentAttendanceSessionState(pageView, btnAttendance, today)
        }
    }

    private fun refreshStudentAttendanceSessionState(pageView: View, btnAttendance: Button, today: String) {
        FirebaseClient.get("Attendance_Session/$currentSubjectCode/$today") { sessionJson ->
            if (sessionJson == null) {
                setAttendanceButtonInactive(btnAttendance)
                btnAttendance.setOnClickListener {
                    Toast.makeText(this, "출석체크 시간이 아닙니다", Toast.LENGTH_SHORT).show()
                }
                return@get
            }

            val now = System.currentTimeMillis()
            val status = sessionJson.optString("status", "READY")
            val bluetoothEndAt = sessionJson.optLong("bluetoothEndAt", 0L)
            val pinEndAt = sessionJson.optLong("pinEndAt", 0L)

            if (status == "BLUETOOTH_ACTIVE" && now <= bluetoothEndAt) {
                setAttendanceButtonActive(btnAttendance)
                btnAttendance.setOnClickListener {
                    startBluetoothAttendanceScan()
                }
                return@get
            }

            setAttendanceButtonInactive(btnAttendance)

            if (now > bluetoothEndAt && now <= pinEndAt) {
                btnAttendance.setOnClickListener {
                    checkStudentPinEligibilityAndShow(pageView, sessionJson)
                }
            } else {
                btnAttendance.setOnClickListener {
                    Toast.makeText(this, "출석체크 시간이 아닙니다", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setAttendanceButtonActive(button: Button) {
        button.setBackgroundResource(R.drawable.bg_attendance_button_blue)
        button.isEnabled = true
        button.alpha = 1.0f
    }

    private fun setAttendanceButtonInactive(button: Button) {
        button.setBackgroundResource(R.drawable.bg_attendance_button_gray)
        button.isEnabled = true
        button.alpha = 1.0f
    }

    /**
     * BLE phase student flow:
     *   교수 Service가 광고 중인 sessionCode를 학생 Service가 BLE scan으로 찾고,
     *   찾은 code로 server /check-in을 호출한다.
     */
    private fun startBluetoothAttendanceScan() {
        if (userId.isBlank()) {
            Toast.makeText(this, "로그인 정보가 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "BLE 출석 신호를 찾는 중...", Toast.LENGTH_SHORT).show()
        launcher.startStudent(userId)
    }

    private fun checkStudentPinEligibilityAndShow(pageView: View, sessionJson: JSONObject) {
        if (pinPopupShowing) return

        val today = apiDateText()

        FirebaseClient.get("Attendance_Records/$currentSubjectCode/$today/$userId") { recordJson ->
            val currentStatus = recordJson?.optString("finalStatus", "결석") ?: "결석"

            if (currentStatus == "출석") {
                Toast.makeText(this, "이미 출석 처리되었습니다", Toast.LENGTH_SHORT).show()
                return@get
            }

            showPinDialog(pageView, sessionJson)
        }
    }

    private fun showPinDialog(pageView: View, sessionJson: JSONObject) {
        pinPopupShowing = true

        val dialogView = LayoutInflater.from(this).inflate(R.layout.pin, null)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        val etPin1 = dialogView.findViewById<EditText>(R.id.etPin1)
        val etPin2 = dialogView.findViewById<EditText>(R.id.etPin2)
        val etPin3 = dialogView.findViewById<EditText>(R.id.etPin3)
        val etPin4 = dialogView.findViewById<EditText>(R.id.etPin4)

        val tvPinClassName = dialogView.findViewById<TextView>(R.id.tvPinClassName)
        val tvPinClassTime = dialogView.findViewById<TextView>(R.id.tvPinClassTime)
        val tvPinRemainTime = dialogView.findViewById<TextView>(R.id.tvPinRemainTime)
        val tvPinStatusGuide = dialogView.findViewById<TextView>(R.id.tvPinStatusGuide)
        val tvPinResultMessage = dialogView.findViewById<TextView>(R.id.tvPinResultMessage)

        val btnPinCancel = dialogView.findViewById<Button>(R.id.btnPinCancel)
        val btnPinConfirm = dialogView.findViewById<Button>(R.id.btnPinConfirm)

        val now = System.currentTimeMillis()
        val classStartAt = sessionJson.optLong("classStartAt", todayMillisFromTime(currentClassStartTime))
        val pinEndAt = sessionJson.optLong("pinEndAt", classStartAt + FIFTEEN_MINUTES)

        val remainMs = (pinEndAt - now).coerceAtLeast(0L)
        val remainMinute = remainMs / 1000 / 60
        val remainSecond = remainMs / 1000 % 60

        tvPinClassName.text = currentClassName
        tvPinClassTime.text = currentClassTime
        tvPinRemainTime.text = "PIN 입력 가능 시간 %02d:%02d".format(remainMinute, remainSecond)

        if (now < classStartAt + TEN_MINUTES) {
            tvPinStatusGuide.text = "현재 PIN 인증 시 출석 처리됩니다."
        } else {
            tvPinStatusGuide.text = "현재 PIN 인증 시 결석 처리됩니다."
        }

        btnPinCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnPinConfirm.setOnClickListener {
            val inputPin = etPin1.text.toString() +
                    etPin2.text.toString() +
                    etPin3.text.toString() +
                    etPin4.text.toString()

            if (System.currentTimeMillis() > pinEndAt) {
                tvPinResultMessage.visibility = View.VISIBLE
                tvPinResultMessage.text = "PIN 입력 시간이 종료되었습니다."
                return@setOnClickListener
            }

            if (inputPin.length != 4) {
                tvPinResultMessage.visibility = View.VISIBLE
                tvPinResultMessage.text = "PIN 4자리를 모두 입력해주세요."
                return@setOnClickListener
            }

            // 통합 후: 클라 PIN 검증 / Firebase PUT 제거.
            // server가 sessionCode 검증 (잘못된 PIN이면 onAttendanceFailed) + dual-write.
            // 출석/결석 판정도 server가 시간 기반으로 결정 (BLE 페이즈/PIN 페이즈 + classStartAt+10min).
            launcher.submitPin(userId, inputPin)
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            pinPopupShowing = false
        }

        dialog.show()
    }

    private fun savePinAttendance(pageView: View, finalStatus: String, onComplete: () -> Unit) {
        val today = apiDateText()

        val body = JSONObject()
            .put("finalStatus", finalStatus)
            .put("authMethod", "PIN")
            .put("missedCount", 0)
            .put("checkedAt", System.currentTimeMillis())

        FirebaseClient.put("Attendance_Records/$currentSubjectCode/$today/$userId", body) {
            setText(pageView, "tvAttendanceStatus", finalStatus)
            onComplete()
            refreshStudentAttendanceButtonState(pageView)
        }
    }

    /**
     * 통합 후 동작:
     *   - 클라가 Firebase Attendance_Session에 직접 PUT 안 함.
     *   - launcher.startProfessor → server /start → server가 RTDB dual-write.
     *   - 받은 PIN은 sessionListener.onSessionStarted 콜백으로 전달 → showPin 표시.
     *   - BLE 광고 / UWB ranging은 우리 Service가 자동 진행 (5분 주기 등).
     *   - 페이즈 UI 전환(After15+UWB 카드)은 classStartAt + 15min 시점에 클라 timer로 표시.
     */
    private fun startAttendanceSession(pageView: View) {
        if (currentSubjectCode.isBlank()) {
            currentSubjectCode = DEFAULT_SUBJECT_CODE
        }
        val now = System.currentTimeMillis()
        val classStartAt = todayMillisFromTime(currentClassStartTime)
        val pinEndAt = classStartAt + FIFTEEN_MINUTES

        // 출석 Service 시작 (권한/UWB feature 체크는 launcher 내부에서)
        launcher.startProfessor(currentSubjectCode, userId, classStartAt)

        // 페이즈 UI 전환 timer — classStartAt + 15min 기준 (수업 시작 + 15분).
        // 교수가 시작 누른 시점이 아니라 수업 시작 시각이 기준.
        val delayToAfter15 = (pinEndAt - now).coerceAtLeast(0L)
        phaseTransitionRunnable?.let { handler.removeCallbacks(it) }
        phaseTransitionRunnable = Runnable { transitionToAfter15Phase(pageView) }
        handler.postDelayed(phaseTransitionRunnable!!, delayToAfter15)
    }

    /** 수업 시작 + 15분 시점에 클라 측에서만 카드 전환 (서버/RTDB WRITE 없음). */
    private fun transitionToAfter15Phase(pageView: View) {
        findChildByIdName<View>(pageView, "cardProfessorControlBefore15")?.visibility = View.GONE
        findChildByIdName<View>(pageView, "cardProfessorControlAfter15")?.visibility = View.VISIBLE
        findChildByIdName<View>(pageView, "cardUwbMiddleCheck")?.visibility = View.VISIBLE
        val btnRollCall = findChildByIdName<View>(pageView, "btnRollCallAttendance")
        val btnProfessorAttendanceCheck = findChildByIdName<View>(pageView, "btnProfessorAttendanceCheck")
        btnRollCall?.isEnabled = false
        btnRollCall?.alpha = 0.4f
        btnProfessorAttendanceCheck?.isEnabled = false
        btnProfessorAttendanceCheck?.alpha = 0.4f
    }

    /**
     * dual-write 통합 후: 사용 안 함 (RTDB status 전환은 시간 기반으로 자연 계산).
     * 본문 주석 처리만 — 함수 시그니처/호출 자리는 향후 참조용으로 보존.
     */
    private fun expireBluetoothAndOpenPin(pageView: View) {
        /*
        val today = apiDateText()

        FirebaseClient.get("Attendance_Session/$currentSubjectCode/$today") { sessionJson ->
            if (sessionJson == null) return@get

            val body = sessionJson
                .put("status", "PIN_ACTIVE")

            FirebaseClient.put("Attendance_Session/$currentSubjectCode/$today", body) {
                Toast.makeText(this, "블루투스 출석이 종료되고 PIN 입력이 시작되었습니다", Toast.LENGTH_SHORT).show()
                updateProfessorSessionUi(pageView, body)
            }
        }
        */
    }

    /**
     * dual-write 통합 후: 사용 안 함. 페이즈 UI 전환은 transitionToAfter15Phase가 담당.
     */
    private fun finishPinAndShowUwb(pageView: View) {
        /*
        val today = apiDateText()

        FirebaseClient.get("Attendance_Session/$currentSubjectCode/$today") { sessionJson ->
            if (sessionJson == null) return@get

            val body = sessionJson
                .put("status", "UWB_ACTIVE")

            FirebaseClient.put("Attendance_Session/$currentSubjectCode/$today", body) {
                updateProfessorSessionUi(pageView, body)
                loadProfessorPage(pageView)
                startUwbLoop(pageView)
            }
        }
        */
    }

    private fun loadProfessorPage(pageView: View) {
        FirebaseClient.get("Subjects") { subjectsJson ->
            val firstSubjectCode = subjectsJson?.keys()?.asSequence()?.firstOrNull() ?: DEFAULT_SUBJECT_CODE
            currentSubjectCode = firstSubjectCode

            FirebaseClient.get("Subjects/$firstSubjectCode") { subjectJson ->
                val subject = FirebaseParsers.subject(subjectJson, firstSubjectCode)

                currentClassName = subject?.subjectName ?: "모바일 프로그래밍"

                val firstSchedule = subject?.schedules?.firstOrNull()
                val firstPeriod = firstSchedule?.periods?.firstOrNull()
                val lastPeriod = firstSchedule?.periods?.lastOrNull()

                currentClassStartTime = firstPeriod?.startTime ?: "10:00"
                currentClassTime = "${firstPeriod?.startTime ?: "10:00"} ~ ${lastPeriod?.endTime ?: "10:50"}"

                setText(pageView, "tvDate", todayText())
                setText(pageView, "tvPeriod", "1교시")
                setText(pageView, "tvClassName", currentClassName)
                setText(pageView, "tvClassTime", subject?.schedules?.joinToString(" / ") {
                    "${FirebaseParsers.convertDayToKorean(it.dayOfWeek)} ${it.periods.firstOrNull()?.startTime ?: ""}-${it.periods.lastOrNull()?.endTime ?: ""}"
                } ?: currentClassTime)

                setText(pageView, "tvAfter15ClassName", currentClassName)
            }

            val today = apiDateText()

            FirebaseClient.get("Attendance_Session/$firstSubjectCode/$today") { sessionJson ->
                updateProfessorSessionUi(pageView, sessionJson)
            }

            FirebaseClient.get("Attendance_Records/$firstSubjectCode") { recordsJson ->
                loadProfessorRows(pageView, recordsJson)
            }
        }
    }

    private fun updateProfessorSessionUi(pageView: View, sessionJson: JSONObject?) {
        val cardBefore15 = findChildByIdName<View>(pageView, "cardProfessorControlBefore15")
        val cardAfter15 = findChildByIdName<View>(pageView, "cardProfessorControlAfter15")
        val cardUwb = findChildByIdName<View>(pageView, "cardUwbMiddleCheck")
        val btnRollCall = findChildByIdName<View>(pageView, "btnRollCallAttendance")
        val btnProfessorAttendanceCheck = findChildByIdName<Button>(pageView, "btnProfessorAttendanceCheck")

        if (sessionJson == null) {
            cardBefore15?.visibility = View.VISIBLE
            cardAfter15?.visibility = View.GONE
            cardUwb?.visibility = View.GONE

            showPin(pageView, "")
            btnRollCall?.isEnabled = true
            btnRollCall?.alpha = 1.0f
            btnRollCall?.setBackgroundResource(R.drawable.bg_attendance_button_blue)
            btnProfessorAttendanceCheck?.isEnabled = true
            btnProfessorAttendanceCheck?.alpha = 1.0f
            btnProfessorAttendanceCheck?.setBackgroundResource(R.drawable.bg_attendance_button_blue)
            return
        }

        val now = System.currentTimeMillis()
        val pinEndAt = sessionJson.optLong("pinEndAt", todayMillisFromTime(currentClassStartTime) + FIFTEEN_MINUTES)
        val status = sessionJson.optString("status", "READY")
        val pinCode = sessionJson.optString("pinCode", "")

        if (now >= pinEndAt || status == "UWB_ACTIVE") {
            cardBefore15?.visibility = View.GONE
            cardAfter15?.visibility = View.VISIBLE
            cardUwb?.visibility = View.VISIBLE
            btnRollCall?.isEnabled = false
            btnRollCall?.alpha = 0.4f
            btnRollCall?.setBackgroundResource(R.drawable.bg_attendance_button_gray)
            btnProfessorAttendanceCheck?.isEnabled = false
            btnProfessorAttendanceCheck?.alpha = 0.4f
            btnProfessorAttendanceCheck?.setBackgroundResource(R.drawable.bg_attendance_button_gray)
            startUwbLoop(pageView)
        } else {
            cardBefore15?.visibility = View.VISIBLE
            cardAfter15?.visibility = View.GONE
            cardUwb?.visibility = View.GONE

            showPin(pageView, pinCode)

            btnRollCall?.isEnabled = false
            btnRollCall?.alpha = 0.4f
            btnRollCall?.setBackgroundResource(R.drawable.bg_attendance_button_gray)

            btnProfessorAttendanceCheck?.isEnabled = false
            btnProfessorAttendanceCheck?.alpha = 0.6f
            btnProfessorAttendanceCheck?.setBackgroundResource(R.drawable.bg_attendance_button_gray)
        }
    }

    /**
     * dual-write 통합 후: 사용 안 함. 진짜 UWB ranging은 ProfessorAttendanceService가 처리.
     * 본문 주석 처리 — UI 카운터 표시는 추후 server broadcast로 대체 예정.
     */
    private fun startUwbLoop(pageView: View) {
        /*
        uwbRunnable?.let { handler.removeCallbacks(it) }

        uwbRunnable = object : Runnable {
            override fun run() {
                runUwbCheck(pageView)
                handler.postDelayed(this, FIVE_MINUTES)
            }
        }

        handler.post(uwbRunnable!!)
        */
    }

    private fun runUwbCheck(pageView: View) {
        /*
        val today = apiDateText()

        FirebaseClient.get("Attendance_Session/$currentSubjectCode/$today") { sessionJson ->
            val currentCount = sessionJson?.optInt("uwbCheckCount", 0) ?: 0
            val nextCount = currentCount + 1

            val updatedSession = (sessionJson ?: JSONObject())
                .put("status", "UWB_ACTIVE")
                .put("uwbCheckCount", nextCount)

            FirebaseClient.put("Attendance_Session/$currentSubjectCode/$today", updatedSession) {
                setText(pageView, "tvUwbCheckCount", "${nextCount}회")

                FirebaseClient.get("Attendance_Records/$currentSubjectCode/$today") { recordsJson ->
                    if (recordsJson == null) {
                        loadProfessorPage(pageView)
                        return@get
                    }

                    val keys = recordsJson.keys()

                    while (keys.hasNext()) {
                        val studentId = keys.next()
                        val record = recordsJson.optJSONObject(studentId) ?: continue

                        if (record.optString("finalStatus") == "출석") {
                            val missedCount = record.optInt("missedCount", 0) + 1
                            record.put("missedCount", missedCount)

                            if (missedCount >= 3) {
                                record.put("finalStatus", "결석")
                            }

                            FirebaseClient.put("Attendance_Records/$currentSubjectCode/$today/$studentId", record) {}
                        }
                    }

                    loadProfessorPage(pageView)
                }
            }
        }
        */
    }

    private fun loadProfessorRows(pageView: View, recordsJson: JSONObject?) {
        val rows = findChildByIdName<LinearLayout>(pageView, "layoutStudentAttendanceRows")
        rows?.removeAllViews()

        FirebaseClient.get("Users") { usersJson ->
            val keys = usersJson?.keys()
            var total = 0
            var present = 0
            var late = 0
            var absent = 0

            if (keys != null) {
                while (keys.hasNext()) {
                    val key = keys.next()
                    val user = FirebaseParsers.user(usersJson.optJSONObject(key), key) ?: continue
                    if (user.userType != "STUDENT") continue

                    val status = findLatestAttendanceStatus(recordsJson, user.userId)

                    total++

                    when (status) {
                        "출석" -> present++
                        "지각" -> late++
                        "결석" -> absent++
                        "미출석" -> absent++
                    }

                    addStudentRow(pageView, user.userId, user.name, status)
                }
            }

            if (total == 0) total = 1

            setText(pageView, "tvStudentCount", "총 ${total}명")
            setText(pageView, "tvAttendanceRate", "${present * 100 / total}%")
            setText(pageView, "tvLateRate", "${late * 100 / total}%")
            setText(pageView, "tvAbsentRate", "${absent * 100 / total}%")
        }
    }

    private fun findLatestAttendanceStatus(recordsJson: JSONObject?, targetUserId: String): String {
        if (recordsJson == null) return "미출석"

        val dateKeys = recordsJson.keys()
        var result = "미출석"

        while (dateKeys.hasNext()) {
            val dateKey = dateKeys.next()
            val dateObject = recordsJson.optJSONObject(dateKey) ?: continue
            val userObject = dateObject.optJSONObject(targetUserId) ?: continue
            result = userObject.optString("finalStatus", "미출석")
        }

        return result
    }

    private fun addStudentRow(pageView: View, studentId: String, name: String, status: String) {
        val parent = findChildByIdName<LinearLayout>(pageView, "layoutStudentAttendanceRows") ?: return

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(0), dpToPx(8), dpToPx(0), dpToPx(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(38)
            )
        }

        row.addView(makeRowText(studentId, 1.45f))
        row.addView(makeRowText(name, 1.0f))

        row.addView(
            makeStatusIcon(
                isVisible = status == "출석",
                drawableResId = R.drawable.attendanceweek,
                weight = 0.75f
            )
        )

        row.addView(
            makeStatusIcon(
                isVisible = status == "결석" || status == "미출석",
                drawableResId = R.drawable.absentweek,
                weight = 0.75f
            )
        )

        row.addView(
            makeStatusIcon(
                isVisible = status == "지각",
                drawableResId = R.drawable.lateweek,
                weight = 0.75f
            )
        )

        parent.addView(row)
    }

    private fun makeRowText(value: String, weight: Float): TextView {
        return TextView(this).apply {
            text = value
            textSize = 12f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(Color.parseColor("#222222"))
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                weight
            )
        }
    }

    private fun makeStatusIcon(isVisible: Boolean, drawableResId: Int, weight: Float): FrameLayout {
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                weight
            )

            val icon = ImageView(this@MainActivity).apply {
                setImageResource(drawableResId)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                visibility = if (isVisible) View.VISIBLE else View.INVISIBLE
                layoutParams = FrameLayout.LayoutParams(
                    dpToPx(18),
                    dpToPx(18),
                    Gravity.CENTER
                )
            }

            addView(icon)
        }
    }

    private fun showPin(pageView: View, pinCode: String) {
        val pin = pinCode.padEnd(4, ' ')
        setText(pageView, "tvPinDigit1", pin[0].toString())
        setText(pageView, "tvPinDigit2", pin[1].toString())
        setText(pageView, "tvPinDigit3", pin[2].toString())
        setText(pageView, "tvPinDigit4", pin[3].toString())
    }

    private fun loadSchedule(pageView: View) {
        FirebaseClient.get("Enrollment/$userId") { enrollmentJson ->
            val subjectCodes = mutableListOf<String>()
            val keys = enrollmentJson?.keys()

            if (keys != null) {
                while (keys.hasNext()) {
                    subjectCodes.add(keys.next())
                }
            }

            val parent = findChildByIdName<FrameLayout>(pageView, "classBlockLayer")
            parent?.removeAllViews()

            if (subjectCodes.isEmpty()) {
                setText(pageView, "tvCurrentClassName", "등록된 시간표 없음")
                return@get
            }

            subjectCodes.forEachIndexed { index, subjectCode ->
                FirebaseClient.get("Subjects/$subjectCode") { subjectJson ->
                    val subject = FirebaseParsers.subject(subjectJson, subjectCode) ?: return@get
                    val course = FirebaseParsers.subjectToCourse(subject)

                    addCourseBlock(parent, course, index)

                    if (index == 0) {
                        setText(pageView, "tvCurrentClassName", subject.subjectName)
                        setText(pageView, "tvDetailProfessor", subject.professorName)
                        setText(pageView, "tvDetailRoom", course.classroom)
                        setText(pageView, "tvDetailCourseCode", subject.subjectCode)
                        setText(pageView, "tvDetailTime", subject.schedules.joinToString(" / ") {
                            "${FirebaseParsers.convertDayToKorean(it.dayOfWeek)} ${it.periods.firstOrNull()?.startTime ?: ""}-${it.periods.lastOrNull()?.endTime ?: ""}"
                        })
                    }
                }
            }
        }
    }

    private fun loadMyPage(pageView: View) {
        FirebaseClient.get("Users/$userId") { userJson ->
            val user = FirebaseParsers.user(userJson, userId)

            if (userRole == "professor") {
                setText(pageView, "tvProfessorName", user?.name ?: userName)
                setText(pageView, "tvProfessorMajor", "소프트웨어학과")
            } else {
                setText(pageView, "tvStudentName", user?.name ?: userName)
                setText(pageView, "tvStudentMajor", "소프트웨어학과")
                setText(pageView, "tvStudentInfo", user?.userId ?: userId)
            }
        }
    }

    private fun loadAttendanceCalendar(pageView: View) {
        FirebaseClient.get("Attendance_Records") { recordsRoot ->
            val result = StringBuilder()

            val subjectKeys = recordsRoot?.keys()
            if (subjectKeys != null) {
                while (subjectKeys.hasNext()) {
                    val subjectCode = subjectKeys.next()
                    val subjectObject = recordsRoot.optJSONObject(subjectCode) ?: continue
                    val dateKeys = subjectObject.keys()

                    while (dateKeys.hasNext()) {
                        val date = dateKeys.next()
                        val userRecord = subjectObject.optJSONObject(date)?.optJSONObject(userId) ?: continue
                        result.append(date)
                            .append(" / ")
                            .append(subjectCode)
                            .append(" / ")
                            .append(userRecord.optString("finalStatus", ""))
                            .append("\n")
                    }
                }
            }

            setText(pageView, "tvAttendanceCalendar", result.toString())
            addSimpleText(pageView, "layoutAttendanceCalendar", result.toString())
        }
    }

    private fun loadAttendanceSummary(pageView: View) {
        FirebaseClient.get("Attendance_Records") { recordsRoot ->
            var present = 0
            var late = 0
            var absent = 0

            val subjectKeys = recordsRoot?.keys()
            if (subjectKeys != null) {
                while (subjectKeys.hasNext()) {
                    val subjectCode = subjectKeys.next()
                    val subjectObject = recordsRoot.optJSONObject(subjectCode) ?: continue
                    val dateKeys = subjectObject.keys()

                    while (dateKeys.hasNext()) {
                        val date = dateKeys.next()
                        val userRecord = subjectObject.optJSONObject(date)?.optJSONObject(userId) ?: continue
                        when (userRecord.optString("finalStatus", "")) {
                            "출석" -> present++
                            "지각" -> late++
                            "결석" -> absent++
                        }
                    }
                }
            }

            val total = (present + late + absent).coerceAtLeast(1)
            val text = "출석 ${present * 100 / total}% / 지각 ${late * 100 / total}% / 결석 ${absent * 100 / total}%"

            setText(pageView, "tvAttendanceSummary", text)
            addSimpleText(pageView, "layoutAttendanceSummary", text)
        }
    }

    private fun addCourseBlock(parent: FrameLayout?, course: Course, index: Int) {
        if (parent == null) return

        val colors = listOf("#8FA2C7", "#B9AAA5", "#79B2B8", "#A7B58D", "#C39DA4")
        val color = colors[index % colors.size]

        course.schedules.forEach { time ->
            val block = TextView(this).apply {
                text = course.name + "\n" + course.classroom
                setTextColor(Color.WHITE)
                textSize = 10f
                gravity = Gravity.CENTER
                setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
                setBackgroundColor(Color.parseColor(color))
            }

            val params = FrameLayout.LayoutParams(
                getColumnWidth(parent),
                getBlockHeight(time.startHour, time.endHour)
            )

            params.leftMargin = getLeftMarginByDay(parent, time.day)
            params.topMargin = getTopMarginByHour(time.startHour)

            parent.addView(block, params)
        }
    }

    private fun addSimpleText(pageView: View, parentIdName: String, value: String) {
        val parent = findChildByIdName<LinearLayout>(pageView, parentIdName) ?: return
        parent.removeAllViews()
        parent.addView(
            TextView(this).apply {
                text = value
                textSize = 14f
                setTextColor(Color.parseColor("#222222"))
                setPadding(16, 12, 16, 12)
            }
        )
    }

    private fun todayMillisFromTime(time: String): Long {
        val parts = time.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 10
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0

        val calendar = Calendar.getInstance(Locale.KOREA)
        calendar.time = Date()
        calendar.set(Calendar.HOUR_OF_DAY, hour)
        calendar.set(Calendar.MINUTE, minute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        return calendar.timeInMillis
    }

    private fun setText(pageView: View, idName: String, value: String) {
        findChildByIdName<TextView>(pageView, idName)?.text = value
    }

    private inline fun <reified T> findChildByIdName(pageView: View, idName: String): T? {
        val id = resources.getIdentifier(idName, "id", packageName)
        return if (id != 0) pageView.findViewById(id) else null
    }

    private fun getColumnWidth(parent: FrameLayout): Int {
        val width = parent.width
        return if (width > 0) width / 5 else (resources.displayMetrics.widthPixels - dpToPx(120)) / 5
    }

    private fun getLeftMarginByDay(parent: FrameLayout, day: String): Int {
        val columnWidth = getColumnWidth(parent)
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

    private fun todayText(): String {
        return SimpleDateFormat("yyyy.MM.dd", Locale.KOREA).format(Date())
    }

    private fun apiDateText(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())
    }

    private fun updateLog(message: String) {
        android.util.Log.d("UWB_DB_TEST", message)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * dual-write 통합 후: 사용 안 함. 본문 주석 처리.
     */
    private fun testUwbMonitorAndDatabase() {
        /*
        updateLog("[테스트 4] UWB 모니터 ↔ 실제 DB 연동 테스트 시작...")

        val testSubjectCode = "TEST_SUBJECT"
        val testStudentId = "TEST_STUDENT_01"
        val today = apiDateText()
        val randomFailCount = (1..3).random()

        // 1. 현재 DB 값 읽기
        FirebaseClient.get("Attendance_Records/$testSubjectCode/$today/$testStudentId") { recordJson ->
            val status = recordJson?.optString("finalStatus")
            updateLog("[실시간 DB 감지] 현재 파이어베이스에 기록된 학생 상태: ${status ?: "아직 판별 안됨 (빈칸)"}")
        }

        // 2. 1분 간격으로 UWB 연결 실패 상황 테스트
        for (i in 1..randomFailCount) {
            handler.postDelayed({
                updateLog("📡 앱에서 ${i}번째 연결 실패(false) 신호 발생!")

                FirebaseClient.get("Attendance_Records/$testSubjectCode/$today/$testStudentId") { recordJson ->
                    val currentRecord = recordJson ?: JSONObject()

                    val missedCount = currentRecord.optInt("missedCount", 0) + 1

                    val finalStatus = if (missedCount >= 3) {
                        "결석"
                    } else {
                        currentRecord.optString("finalStatus", "출석")
                    }

                    val updatedRecord = currentRecord
                        .put("finalStatus", finalStatus)
                        .put("authMethod", "UWB")
                        .put("missedCount", missedCount)
                        .put("checkedAt", System.currentTimeMillis())

                    FirebaseClient.put(
                        "Attendance_Records/$testSubjectCode/$today/$testStudentId",
                        updatedRecord
                    ) {
                        updateLog("[실시간 DB 감지] 현재 파이어베이스에 기록된 학생 상태: $finalStatus / UWB 실패 ${missedCount}회")
                    }
                }
            }, 60000L * i)
        }

        // 3. 테스트 종료 로그
        handler.postDelayed({
            updateLog("[테스트 4] DB 연동 테스트 자동 종료.")
        }, 60000L * randomFailCount + 5000L)
        */
    }

    private fun logout() {
        getSharedPreferences("LOGIN_INFO", MODE_PRIVATE).edit().clear().apply()
        getSharedPreferences("login_pref", MODE_PRIVATE).edit().clear().apply()

        Toast.makeText(this, "로그아웃되었습니다", Toast.LENGTH_SHORT).show()

        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }


}
