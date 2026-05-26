package com.example.myapplication.model

/**
 * LoginActivity.kt
 * - accessToken: 자동 로그인/인증용 토큰
 * - userId: 사용자 고유 ID
 * - loginId: 학번/교번/로그인 ID
 * - name: 사용자 이름
 * - role: STUDENT / PROFESSOR
 */
data class LoginResponse(
    val success: Boolean,
    val accessToken: String?,
    val userId: Int?,
    val loginId: String?,
    val name: String?,
    val role: String?,
    val message: String?
)