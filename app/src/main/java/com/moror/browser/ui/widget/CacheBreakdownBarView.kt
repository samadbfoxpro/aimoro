package com.moror.browser.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.moror.browser.engine.CategoryStat

class CacheBreakdownBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()
    private val barRect = RectF()
    private var stats: List<CategoryStat> = emptyList()

    private val cornerRadius = 8f * resources.displayMetrics.density
    private val emptyColor = Color.parseColor("#2A2A2A")

    fun setData(newStats: List<CategoryStat>) {
        this.stats = newStats.filter { it.sizeBytes > 0 }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        barRect.set(0f, 0f, w, h)
        clipPath.reset()
        clipPath.addRoundRect(barRect, cornerRadius, cornerRadius, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(clipPath)

        // Draw background
        paint.color = emptyColor
        paint.style = Paint.Style.FILL
        canvas.drawRect(barRect, paint)

        val totalBytes = stats.sumOf { it.sizeBytes }
        if (totalBytes > 0L) {
            var currentX = 0f
            for (stat in stats) {
                val fraction = stat.sizeBytes.toFloat() / totalBytes.toFloat()
                val segmentWidth = w * fraction
                if (segmentWidth > 0f) {
                    try {
                        paint.color = Color.parseColor(stat.category.colorHex)
                    } catch (e: Exception) {
                        paint.color = Color.GRAY
                    }
                    canvas.drawRect(currentX, 0f, currentX + segmentWidth, h, paint)
                    currentX += segmentWidth
                }
            }
        }

        canvas.restore()
    }
}
