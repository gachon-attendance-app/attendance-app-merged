package com.example.myapplication.model

/**
 * mypage.xml
 * - tvUserName: 이름
 * - tvUserRole: 학생/교수 구분
 * - tvDepartment: 학과
 * - tvStudentNumber: 학번/교번
 */
data class MyInfoResponse(
    val success: Boolean,
    val userId: Int?,
    val loginId: String?,
    val name: String?,
    val role: String?,
    val department: String?,
    val studentNumber: String?,
    val professorNumber: String?,
    val message: String?
)