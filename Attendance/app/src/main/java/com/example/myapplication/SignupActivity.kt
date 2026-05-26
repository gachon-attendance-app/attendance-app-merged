package com.example.myapplication

import android.app.Activity
import android.os.Bundle

class SignupActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 회원가입 선택 화면
        setContentView(R.layout.signup_1)
    }
}