package com.example.myapplication

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.min

class DonutChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 24f
        strokeCap = Paint.Cap.BUTT
    }

    private val rect = RectF()

    /*
     * 비율 값
     * 출석 + 지각 + 결석 합이 100이 되도록 사용
     */
    private var attendanceRate = 100f
    private var lateRate = 0f
    private var absentRate = 0f

    private var animatedProgress = 0f

    /*
     * 색상
     * 출석: 파랑
     * 지각: 보라
     * 결석: 빨강
     * 빈 배경: 연회색
     */
    private val attendanceColor = try {
        ContextCompat.getColor(context, R.color.primarybase)
    } catch (e: Exception) {
        android.graphics.Color.parseColor("#2196D3")
    }

    private val lateColor = android.graphics.Color.parseColor("#9C27B0")
    private val absentColor = android.graphics.Color.parseColor("#D00000")
    private val emptyColor = android.graphics.Color.parseColor("#E3E3E5")

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val viewSize = min(width, height).toFloat()
        val padding = paint.strokeWidth / 2f + 4f

        val left = (width - viewSize) / 2f + padding
        val top = (height - viewSize) / 2f + padding
        val right = left + viewSize - padding * 2
        val bottom = top + viewSize - padding * 2

        rect.set(left, top, right, bottom)

        // 기본 회색 도넛 배경
        paint.color = emptyColor
        canvas.drawArc(rect, 0f, 360f, false, paint)

        // 총합이 0이면 회색 배경만 표시
        val totalRate = attendanceRate + lateRate + absentRate
        if (totalRate <= 0f) return

        /*
         * Android drawArc()
         * 양수 sweepAngle: 시계 방향
         * 음수 sweepAngle: 반시계 방향
         *
         * 그래서 - 값을 사용해서 반시계 방향 애니메이션 구현
         */
        var startAngle = -90f

        val attendanceSweep = -360f * (attendanceRate / totalRate) * animatedProgress
        val lateSweep = -360f * (lateRate / totalRate) * animatedProgress
        val absentSweep = -360f * (absentRate / totalRate) * animatedProgress

        if (attendanceRate > 0f) {
            paint.color = attendanceColor
            canvas.drawArc(rect, startAngle, attendanceSweep, false, paint)
            startAngle += attendanceSweep
        }

        if (lateRate > 0f) {
            paint.color = lateColor
            canvas.drawArc(rect, startAngle, lateSweep, false, paint)
            startAngle += lateSweep
        }

        if (absentRate > 0f) {
            paint.color = absentColor
            canvas.drawArc(rect, startAngle, absentSweep, false, paint)
        }
    }

    /*
     * 기존 방식 유지
     * 예: setData(80f, 10f, 10f)
     * 출석 80%, 지각 10%, 결석 10%
     */
    fun setData(
        attendance: Float,
        late: Float,
        absent: Float
    ) {
        attendanceRate = attendance.coerceAtLeast(0f)
        lateRate = late.coerceAtLeast(0f)
        absentRate = absent.coerceAtLeast(0f)

        startAnimation()
    }

    /*
     * 백엔드에서 받은 횟수 기반 방식
     * 예: 출석 8회, 지각 1회, 결석 1회
     * setAttendanceData(8, 1, 1)
     */
    fun setAttendanceData(
        attendanceCount: Int,
        lateCount: Int,
        absentCount: Int
    ) {
        val attendance = attendanceCount.coerceAtLeast(0)
        val late = lateCount.coerceAtLeast(0)
        val absent = absentCount.coerceAtLeast(0)

        val total = attendance + late + absent

        if (total <= 0) {
            attendanceRate = 0f
            lateRate = 0f
            absentRate = 0f
        } else {
            attendanceRate = attendance * 100f / total
            lateRate = late * 100f / total
            absentRate = absent * 100f / total
        }

        startAnimation()
    }

    private fun startAnimation() {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900L
            interpolator = DecelerateInterpolator()

            addUpdateListener {
                animatedProgress = it.animatedValue as Float
                invalidate()
            }

            start()
        }
    }
}