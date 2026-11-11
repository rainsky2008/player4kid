package com.netdisk.player4kid.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.netdisk.player4kid.R

/**
 * 首页的“时间能量条”：今天的视频时间分成一格一格（每格 5 分钟，最少 4 格、最多 12 格），
 * 孩子不用读数字，看还剩几格就知道还能看多久。快用完时颜色从绿变黄、再变红。
 */
class TimeBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var segments = 8
    /** 剩余比例 0..1 */
    private var fraction = 1f

    private val density = resources.displayMetrics.density
    private val gap = 4 * density
    private val rect = RectF()

    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.time_empty)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grass = ContextCompat.getColor(context, R.color.grass)
    private val sun = ContextCompat.getColor(context, R.color.sun)
    private val berry = ContextCompat.getColor(context, R.color.berry)

    /** @param remainingMs 今天还能看多久  @param totalMs 今天一共能看多久（含家长加的时间） */
    fun set(remainingMs: Long, totalMs: Long) {
        val total = totalMs.coerceAtLeast(1)
        val n = (total / (5 * 60_000L)).toInt().coerceIn(4, 12)
        val f = (remainingMs.toFloat() / total).coerceIn(0f, 1f)
        if (n == segments && f == fraction) return
        segments = n
        fraction = f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val n = segments
        val h = height.toFloat()
        val segW = (width - gap * (n - 1)) / n
        val radius = h / 2
        fillPaint.color = when {
            fraction > 0.3f -> grass
            fraction > 0.1f -> sun
            else -> berry
        }
        val filled = fraction * n // 例如 6.8 格
        for (i in 0 until n) {
            val left = i * (segW + gap)
            rect.set(left, 0f, left + segW, h)
            canvas.drawRoundRect(rect, radius, radius, emptyPaint)
            val part = (filled - i).coerceIn(0f, 1f)
            if (part > 0f) {
                canvas.save()
                canvas.clipRect(left, 0f, left + segW * part, h)
                canvas.drawRoundRect(rect, radius, radius, fillPaint)
                canvas.restore()
            }
        }
    }
}
