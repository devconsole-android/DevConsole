/**
 * @author Shakib
 * @since 09/11/26
 */
package io.devconsole

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable

/** Small count badge drawn in the top-right corner of the floating button. */
internal class FailedRequestBadgeDrawable(
    count: Int,
    private val density: Float,
) : Drawable() {
    private val badgePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BADGE_COLOR
        }
    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = TEXT_SIZE_DP * density
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
    private val label = if (count >= MAX_DISPLAYED_COUNT) OVERFLOW_LABEL else count.toString()

    override fun draw(canvas: Canvas) {
        val diameter = DIAMETER_DP * density
        val inset = INSET_DP * density
        val centerX = bounds.right - inset - diameter / 2
        val centerY = bounds.top + inset + diameter / 2
        canvas.drawCircle(centerX, centerY, diameter / 2, badgePaint)
        val metrics = textPaint.fontMetrics
        val baseline = centerY - (metrics.ascent + metrics.descent) / 2
        canvas.drawText(label, centerX, baseline, textPaint)
    }

    override fun setAlpha(alpha: Int) {
        badgePaint.alpha = alpha
        textPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        badgePaint.colorFilter = colorFilter
        textPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        val BADGE_COLOR = 0xFFC0261D.toInt()
        const val DIAMETER_DP = 20f
        const val INSET_DP = 2f
        const val TEXT_SIZE_DP = 10f
        const val MAX_DISPLAYED_COUNT = 100
        const val OVERFLOW_LABEL = "99+"
    }
}
