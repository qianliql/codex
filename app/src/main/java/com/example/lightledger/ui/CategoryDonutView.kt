package com.example.lightledger.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.example.lightledger.R
import com.example.lightledger.statistics.CategoryBreakdown
import com.example.lightledger.statistics.ExpenseSlice
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

class CategoryDonutView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var onSliceClick: ((ExpenseSlice) -> Unit)? = null
    private var data = CategoryBreakdown(0, emptyList())
    private var income = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var downX = 0f; private var downY = 0f
    private val d get() = resources.displayMetrics.density
    private val colors = intArrayOf(context.ledgerColor(R.color.ledger_teal), 0xFF6D87AB.toInt(), 0xFFC8986D.toInt(), 0xFF9183B3.toInt(), 0xFF70A5A2.toInt(), 0xFFBC7D8E.toInt(), 0xFF88A17C.toInt(), 0xFFAF936B.toInt(), 0xFF70838B.toInt())
    fun colorFor(index: Int): Int = if (data.slices.getOrNull(index)?.merged == true) 0xFFADB6B3.toInt() else colors[index % colors.size]
    fun setData(value: CategoryBreakdown, income: Boolean = false) {
        data = value
        this.income = income
        contentDescription = "分类${if (income) "收入" else "支出"}圆环图，${UiFormat.money(value.totalCents)}。" + value.slices.joinToString("；") { "${it.label} ${UiFormat.money(it.amountCents)}" }
        invalidate()
    }
    private val radius get() = min(width, height) * .39f
    private val stroke get() = min(28 * d, radius * .3f)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f; val cy = height / 2f
        paint.style = Paint.Style.STROKE; paint.strokeWidth = stroke; paint.color = context.ledgerColor(R.color.ledger_border)
        canvas.drawCircle(cx, cy, radius, paint)
        if (data.totalCents > 0) {
            var start = -90f
            val bounds = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            data.slices.forEachIndexed { index, slice ->
                val sweep = (slice.amountCents.toDouble() / data.totalCents * 360).toFloat()
                paint.color = colorFor(index)
                canvas.drawArc(bounds, start, sweep, false, paint); start += sweep
            }
        }
        paint.style = Paint.Style.FILL; paint.color = context.ledgerColor(R.color.ledger_muted)
        paint.typeface = android.graphics.Typeface.DEFAULT; paint.textSize = sp(12f)
        val label = if (data.totalCents == 0L) "本期暂无${if (income) "收入" else "支出"}" else "分类总${if (income) "收入" else "支出"}"
        canvas.drawText(label, cx - paint.measureText(label) / 2, cy - 12 * d, paint)
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD; paint.color = context.ledgerColor(R.color.ledger_ink); paint.textSize = sp(22f)
        val amount = UiFormat.money(data.totalCents)
        val budget = (radius - stroke / 2) * 1.65f
        if (paint.measureText(amount) > budget) paint.textSize *= budget / paint.measureText(amount)
        canvas.drawText(amount, cx - paint.measureText(amount) / 2, cy + 18 * d, paint)
    }
    private fun sp(value: Float) = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
    internal fun sliceAt(x: Float, y: Float): ExpenseSlice? {
        if (data.totalCents == 0L) return null
        val dx = x - width / 2f; val dy = y - height / 2f
        val distance = hypot(dx, dy)
        if (distance < radius - stroke / 2 - 8 * d || distance > radius + stroke / 2 + 8 * d) return null
        val angle = (Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 450) % 360
        var end = 0.0
        data.slices.forEach { slice ->
            end += slice.amountCents.toDouble() / data.totalCents * 360
            if (angle < end) return slice
        }
        return data.slices.lastOrNull()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; sliceAt(event.x, event.y) != null }
        MotionEvent.ACTION_UP -> {
            if (hypot(event.x - downX, event.y - downY) <= ViewConfiguration.get(context).scaledTouchSlop) sliceAt(event.x, event.y)?.let { performClick(); onSliceClick?.invoke(it) }
            true
        }
        MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_MOVE -> true
        else -> super.onTouchEvent(event)
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
