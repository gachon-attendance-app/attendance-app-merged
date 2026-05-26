package com.example.myapplication

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView

class LoginActivity : Activity() {

    private lateinit var etId: EditText
    private lateinit var etPw: EditText
    private lateinit var tvIdError: TextView
    private lateinit var tvPwError: TextView
    private lateinit var cbAutoLogin: CheckBox
    private lateinit var btnLogin: Button
    private lateinit var tvSignup: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val loginPref = getSharedPreferences("login_pref", MODE_PRIVATE)
        val isAutoLogin = loginPref.getBoolean("auto_login", false)
        val savedUserId = loginPref.getString("saved_user_id", null)

        if (isAutoLogin && !savedUserId.isNullOrBlank()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.login)

        etId = findViewById(R.id.etId)
        etPw = findViewById(R.id.etPw)
        tvIdError = findViewById(R.id.tvIdError)
        tvPwError = findViewById(R.id.tvPwError)
        cbAutoLogin = findViewById(R.id.cbAutoLogin)
        btnLogin = findViewById(R.id.btnLogin)
        tvSignup = findViewById(R.id.tvSignup)

        tvIdError.visibility = View.GONE
        tvPwError.visibility = View.GONE

        btnLogin.setOnClickListener {
            requestLogin()
        }

        tvSignup.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
        }
    }

    private fun requestLogin() {
        val inputId = etId.text.toString().trim()
        val inputPw = etPw.text.toString().trim()

        tvIdError.visibility = View.GONE
        tvPwError.visibility = View.GONE

        if (inputId.isEmpty()) {
            tvIdError.visibility = View.VISIBLE
            tvIdError.text = "아이디를 입력해주세요"
            return
        }

        if (inputPw.isEmpty()) {
            tvPwError.visibility = View.VISIBLE
            tvPwError.text = "비밀번호를 입력해주세요"
            return
        }

        // 테스트용 로그인 계정
        // 아이디: gachon1
        // 비밀번호: 1111
        if (inputId == "gachon1") {
            val testUser = AppUser(
                userId = "202234920",
                portalId = "gachon1",
                password = "1111",
                name = "이원희",
                email = "gmr850@gachon.ac.kr",
                userType = "STUDENT"
            )

            checkPasswordAndMove(testUser, inputPw)
            return
        }

        // 테스트용 학번 로그인도 가능하게 추가
        // 아이디: 202234920
        // 비밀번호: 1111
        if (inputId == "202234920") {
            val testUser = AppUser(
                userId = "202234920",
                portalId = "gachon1",
                password = "1111",
                name = "이원희",
                email = "gmr850@gachon.ac.kr",
                userType = "STUDENT"
            )

            checkPasswordAndMove(testUser, inputPw)
            return
        }

        // Firebase 로그인
        FirebaseClient.get("Users/$inputId") { directJson ->
            val directUser = FirebaseParsers.user(directJson, inputId)

            if (directUser != null) {
                checkPasswordAndMove(directUser, inputPw)
                return@get
            }

            FirebaseClient.get("Users") { usersJson ->
                val portalUser = FirebaseParsers.findUserByPortalId(usersJson, inputId)

                if (portalUser == null) {
                    tvIdError.visibility = View.VISIBLE
                    tvIdError.text = "입력하신 아이디를 찾을 수 없습니다"
                    return@get
                }

                checkPasswordAndMove(portalUser, inputPw)
            }
        }
    }

    private fun checkPasswordAndMove(user: AppUser, inputPw: String) {
        if (inputPw != user.password) {
            tvPwError.visibility = View.VISIBLE
            tvPwError.text = "비밀번호가 올바르지 않습니다"
            return
        }

        saveLoginInfo(user)

        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun saveLoginInfo(user: AppUser) {
        getSharedPreferences("LOGIN_INFO", MODE_PRIVATE)
            .edit()
            .putString("userId", user.userId)
            .putString("portalId", user.portalId)
            .putString("userName", user.name)
            .putString("userEmail", user.email)
            .putString("userRole", user.userType.lowercase())
            .apply()

        val loginPrefEditor = getSharedPreferences("login_pref", MODE_PRIVATE).edit()

        if (cbAutoLogin.isChecked) {
            loginPrefEditor
                .putBoolean("auto_login", true)
                .putString("saved_user_id", user.userId)
        } else {
            loginPrefEditor
                .putBoolean("auto_login", false)
                .remove("saved_user_id")
        }

        loginPrefEditor.apply()
    }
}