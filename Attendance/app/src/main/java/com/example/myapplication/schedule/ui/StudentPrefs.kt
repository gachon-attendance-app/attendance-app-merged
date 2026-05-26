package com.example.myapplication.schedule.ui

import android.content.Context

/**
 * 학생 ID 조회 — LoginActivity가 저장하는 "LOGIN_INFO" SharedPreferences를 읽음.
 *
 * 통합 전엔 자체 "student_prefs"에 학번을 저장했지만,
 * 이제 LoginActivity 로그인 흐름이 진실원천. 학번은 userRole="student"일 때만 유효.
 *
 * Java에서 호출 시: `StudentPrefs.INSTANCE.getStudentId(context)`
 */
object StudentPrefs {

    private const val PREFS_LOGIN_INFO = "LOGIN_INFO"  // LoginActivity.saveLoginInfo와 동일 이름
    private const val KEY_STUDENT_NUMBER = "studentNumber"
    private const val KEY_USER_ROLE = "userRole"
    private const val ROLE_STUDENT = "student"

    /**
     * 로그인된 학생의 학번 반환. 로그인 안 됐거나 role이 student가 아니면 null.
     */
    fun getStudentId(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_LOGIN_INFO, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_USER_ROLE, null) != ROLE_STUDENT) return null
        return prefs.getString(KEY_STUDENT_NUMBER, null)
    }

    /**
     * @deprecated 학번은 이제 LoginActivity가 저장. 이 메서드는 no-op.
     * MainActivity2 제거 후 같이 삭제 예정.
     */
    @Deprecated("Login flow now owns the student ID")
    fun setStudentId(context: Context, id: String) {
        // no-op: LoginActivity가 진실원천
    }
}
