package com.example.myapplication

import com.example.myapplication.model.AttendanceCalendarResponse
import com.example.myapplication.model.AttendanceCheckResponse
import com.example.myapplication.model.AttendanceSummaryResponse
import com.example.myapplication.model.BluetoothCheckRequest
import com.example.myapplication.model.CourseLookupResponse
import com.example.myapplication.model.CurrentClassResponse
import com.example.myapplication.model.LoginRequest
import com.example.myapplication.model.LoginResponse
import com.example.myapplication.model.MyInfoResponse
import com.example.myapplication.model.PinCheckRequest
import com.example.myapplication.model.ProfessorAttendanceStatusResponse
import com.example.myapplication.model.SaveScheduleRequest
import com.example.myapplication.model.SaveScheduleResponse
import com.example.myapplication.model.ScheduleResponse
import com.example.myapplication.model.StartAttendanceResponse
import com.example.myapplication.model.UwbCheckRequest
import com.example.myapplication.model.UwbCheckResponse
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * ApiClient.kt
 * - Retrofit API 목록
 */
interface ApiService {

    /**
     * login.xml
     * - btnLogin: 로그인 버튼
     */
    @POST("auth/login")
    fun login(
        @Body request: LoginRequest
    ): Call<LoginResponse>

    /**
     * register_schedule.xml
     * - etCourseCode: 강의 코드
     * - btnAddClass: 강의 추가 버튼
     */
    @GET("courses/{courseCode}")
    fun lookupCourse(
        @Path("courseCode") courseCode: String
    ): Call<CourseLookupResponse>

    /**
     * register_schedule.xml
     * - btnConfirmSchedule: 시간표 확정 버튼
     */
    @POST("students/{studentId}/schedule")
    fun saveStudentSchedule(
        @Path("studentId") studentId: Int,
        @Body request: SaveScheduleRequest
    ): Call<SaveScheduleResponse>

    /**
     * schedule_1.xml, mypage.xml
     * - 저장된 시간표 조회
     */
    @GET("students/{studentId}/schedule")
    fun getStudentSchedule(
        @Path("studentId") studentId: Int
    ): Call<ScheduleResponse>

    /**
     * main1.xml
     * - 현재 수업 조회
     */
    @GET("students/{studentId}/current-class")
    fun getCurrentClass(
        @Path("studentId") studentId: Int
    ): Call<CurrentClassResponse>

    /**
     * mypage.xml
     * - 사용자 정보 조회
     */
    @GET("users/{userId}/me")
    fun getMyInfo(
        @Path("userId") userId: Int
    ): Call<MyInfoResponse>

    /**
     * main_p_1.xml
     * - btnProfessorAttendanceCheck: 교수 출석 시작 버튼
     */
    @POST("professors/classes/{classId}/attendance/start")
    fun startAttendance(
        @Path("classId") classId: Int
    ): Call<StartAttendanceResponse>

    /**
     * main1.xml
     * - btnAttendance: 학생 출석 버튼
     */
    @POST("com/example/myapplication/attendance/bluetooth-check")
    fun bluetoothCheck(
        @Body request: BluetoothCheckRequest
    ): Call<AttendanceCheckResponse>

    /**
     * pin.xml
     * - PIN 출석 확인
     */
    @POST("com/example/myapplication/attendance/pin-check")
    fun pinCheck(
        @Body request: PinCheckRequest
    ): Call<AttendanceCheckResponse>

    /**
     * UWB 중간 출석 체크
     * - UWB 감지 결과 전송
     */
    @POST("com/example/myapplication/attendance/uwb-check")
    fun uwbCheck(
        @Body request: UwbCheckRequest
    ): Call<UwbCheckResponse>

    /**
     * main_p_1.xml
     * - layoutStudentAttendanceRows: 학생별 출석 표
     * - tvAttendanceRate: 출석률
     * - tvLateRate: 지각률
     * - tvAbsentRate: 결석률
     */
    @GET("professors/classes/{classId}/attendance/status")
    fun getProfessorAttendanceStatus(
        @Path("classId") classId: Int
    ): Call<ProfessorAttendanceStatusResponse>

    /**
     * all_attendance.xml, all_attendance_rate.xml
     * - 과목별 출결 통계
     */
    @GET("students/{studentId}/attendance/summary")
    fun getAttendanceSummary(
        @Path("studentId") studentId: Int
    ): Call<AttendanceSummaryResponse>

    /**
     * week_1.xml, week_2.xml
     * - 날짜별 출결 상태
     */
    @GET("students/{studentId}/attendance/calendar")
    fun getAttendanceCalendar(
        @Path("studentId") studentId: Int,
        @Query("month") month: String
    ): Call<AttendanceCalendarResponse>
}