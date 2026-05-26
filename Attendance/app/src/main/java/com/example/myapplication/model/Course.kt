package com.example.myapplication.model

data class Course(
    val classId: Int,
    val code: String,
    val name: String,
    val professor: String,
    val classroom: String,
    val schedules: List<CourseTime>
)

data class CourseTime(
    val day: String,
    val startHour: Int,
    val endHour: Int
)