package com.example.myapplication.network;

import java.util.List;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;

public interface AttendanceApi {

    @POST("api/attendance/start")
    Call<ApiResponse<StartAttendanceData>> startAttendance(
            @Body StartAttendanceRequest request
    );

    @POST("api/attendance/check-in")
    Call<ApiResponse<CheckInData>> checkIn(
            @Body CheckInRequest request
    );

    @GET("api/attendance/{lectureSessionId}/students")
    Call<ApiResponse<List<StudentInfo>>> getCheckedInStudents(
            @Path("lectureSessionId") String lectureSessionId
    );
}
