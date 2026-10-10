package com.example.lightledger.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.example.lightledger.R
import com.example.lightledger.data.TimeTotal
import java.util.Calendar

class SpendingBarChartView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.ledgerColor(R.color.ledger_teal) }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.ledgerColor(R.color.ledger_muted)
        textSize = 11f * resources.displayMetrics.scaledDensity
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.ledgerColor(R.color.ledger_border) }
    private var values: List<Long> = emptyList()
    private var yearly = false

    fun setData(period: StatisticsPeriod, totals: List<TimeTotal>) {
        yearly = period.month == null
        val count = if (yearly) 12 else Calendar.getInstance().apply {
            clear(); set(period.year, (period.month ?: 1) - 1, 1)
        }.getActualMaximum(Calendar.DAY_OF_MONTH)
        val byPeriod = totals.associate { (it.period.toIntOrNull() ?: 0) to (it.expense - it.refund) }
        values = (1..count).map { (byPeriod[it] ?: 0L).coerceAtLeast(0) }
        contentDescription = if (yearly) "年度每月支出柱状图" else "月度每日支出柱状图"
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize((190 * resources.displayMetrics.density).toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        if (values.isEmpty() || values.all { it == 0L }) {
            val label = "本期暂无支出"
            canvas.drawText(label, (width - axisPaint.measureText(label)) / 2, height / 2f, axisPaint)
            return
        }
        val max = values.maxOrNull()?.coerceAtLeast(1) ?: 1
        val start = (axisPaint.measureText(compactAmount(max)) + 12 * density).coerceAtMost(width * .28f)
        val end = width - 10 * density
        val bottom = height - (axisPaint.fontMetrics.descent - axisPaint.fontMetrics.ascent) - 14 * density
        val top = 16 * density
        for (step in 0..2) {
            val y = bottom - (bottom - top) * step / 2
            canvas.drawLine(start, y, end, y, gridPaint)
            canvas.drawText(compactAmount(max * step / 2), 0f, y + 3 * density, axisPaint)
        }
        val slot = (end - start) / values.size
        val stride = if (yearly) {
            if (axisPaint.measureText("12") + 4 * density > slot) 2 else 1
        } else 5
        values.forEachIndexed { index, value ->
            val barHeight = (bottom - top) * (value.toDouble() / max).toFloat()
            val center = start + slot * (index + .5f)
            if (value > 0) canvas.drawRoundRect(center - slot * .28f, bottom - barHeight, center + slot * .28f, bottom, 3 * density, 3 * density, barPaint)
            if (index % stride == 0) {
                val label = "${index + 1}"
                canvas.drawText(label, center - axisPaint.measureText(label) / 2, height - 5 * density, axisPaint)
            }
        }
    }

    private fun compactAmount(cents: Long): String = when {
        cents >= 1_000_000 -> String.format(java.util.Locale.CHINA, "%.1f万", cents / 1_000_000.0)
        else -> String.format(java.util.Locale.CHINA, "%.0f", cents / 100.0)
    }
}
