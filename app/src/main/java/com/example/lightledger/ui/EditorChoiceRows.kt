package com.example.lightledger.ui

import android.widget.HorizontalScrollView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlin.math.ceil
import kotlin.math.max

/** Fixed row counts with widths derived from the actual available space. */
internal object EditorChoiceRows {
    fun bindCategories(group: ChipGroup, scroll: HorizontalScrollView) {
        scroll.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) fitCategories(group, scroll)
        }
        fitCategories(group, scroll)
    }

    fun fitCategories(group: ChipGroup, scroll: HorizontalScrollView) {
        val density = group.resources.displayMetrics.density
        val chips = chips(group)
        chips.forEach { compact(it, true) }
        val available = scroll.width.takeIf { it > 0 }
            ?: (group.resources.displayMetrics.widthPixels - (72 * density).toInt())
        val gap = group.chipSpacingHorizontal
        val minimum = chips.firstOrNull()?.let { ceil(it.paint.measureText("分类") + 31 * density).toInt() }
            ?: (64 * density).toInt()
        val width = max(minimum, (available - 3 * gap) / 4)
        val count = chips.count { it.visibility != android.view.View.GONE }
        val columns = max(1, (count + 1) / 2)
        val total = columns * width + (columns - 1) * gap
        if (group.layoutParams.width != total) group.layoutParams = group.layoutParams.apply { this.width = total }
        chips.forEach { if (it.layoutParams.width != width) it.layoutParams = it.layoutParams.apply { this.width = width } }
    }

    fun bindPayments(group: ChipGroup) {
        group.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) fitPayments(group)
        }
        fitPayments(group)
    }

    fun fitPayments(group: ChipGroup) {
        val chips = chips(group)
        chips.forEach { compact(it, false) }
        if (group.width <= 0 || chips.isEmpty()) return
        val width = (group.width - group.paddingLeft - group.paddingRight - (chips.size - 1) * group.chipSpacingHorizontal) / chips.size
        chips.forEach {
            if (it.layoutParams.width != width) it.layoutParams = it.layoutParams.apply { this.width = width }
            val padding = max((it.paddingLeft + it.paddingRight).toFloat(), it.chipStartPadding + it.chipEndPadding)
            val budget = (width - padding - 2 * group.resources.displayMetrics.density).coerceAtLeast(1f)
            val textWidth = it.paint.measureText(it.text.toString())
            if (textWidth > budget) it.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, it.textSize * budget / textWidth)
        }
    }

    private fun chips(group: ChipGroup) = (0 until group.childCount).map { group.getChildAt(it) }.filterIsInstance<Chip>()
    private fun compact(chip: Chip, icon: Boolean) {
        val d = chip.resources.displayMetrics.density
        if (chip.textSize != 13 * chip.resources.displayMetrics.scaledDensity) chip.textSize = 13f
        chip.chipStartPadding = 4 * d; chip.chipEndPadding = 4 * d
        chip.textStartPadding = 0f; chip.textEndPadding = 0f
        chip.iconStartPadding = 0f; chip.iconEndPadding = 3 * d
        chip.chipIconSize = 18 * d
        chip.isChipIconVisible = icon
        chip.ellipsize = android.text.TextUtils.TruncateAt.END
        chip.contentDescription = chip.text
    }
}
