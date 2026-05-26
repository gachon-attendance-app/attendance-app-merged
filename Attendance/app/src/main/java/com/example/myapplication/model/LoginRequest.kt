package com.example.myapplication.model

/**
 * login.xml
 * - etId: 사용자 아이디
 * - etPw: 비밀번호
 */
data class LoginRequest(
    val loginId: String,
    val password: String
)