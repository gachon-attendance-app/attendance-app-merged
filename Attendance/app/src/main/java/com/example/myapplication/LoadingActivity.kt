package com.example.myapplication

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper

class LoadingActivity : Activity() {

    private val loadingTime = 1500L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.loading)

        Handler(Looper.getMainLooper()).postDelayed({
            val loginPref = getSharedPreferences("login_pref", MODE_PRIVATE)
            val isAutoLogin = loginPref.getBoolean("auto_login", false)
            val savedUserId = loginPref.getString("saved_user_id", null)

            if (isAutoLogin && !savedUserId.isNullOrBlank()) {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            } else {
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
        }, loadingTime)
    }
}