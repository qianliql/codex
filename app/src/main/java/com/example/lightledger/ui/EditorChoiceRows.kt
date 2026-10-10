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
        val count = chips.count { it.visibility != android.view.View.GONE }
        val columns = max(1, (count + 1) / 2)
        val viewportColumns = columns.coerceAtMost(4)
        val width = max(minimum, (available - (viewportColumns - 1) * gap) / viewportColumns)
        val total = columns * width + (columns - 1) * gap
        if (group.layoutParams.width != total) group.layoutParams = group.layoutParams.apply { this.width = total }
        chips.forEach {
            if (it.layoutParams.width != width) it.layoutParams = it.layoutParams.apply { this.width = width }
            centerContent(it, width, true)
        }
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
        val d = group.resources.displayMetrics.density
        val maxText = chips.maxOf { it.paint.measureText(it.text.toString()) }.coerceAtLeast(1f)
        val scale = ((width - 8 * d) / maxText).coerceIn(.1f, 1f)
        val size = chips.first().textSize * scale
        chips.forEach {
            if (it.layoutParams.width != width) it.layoutParams = it.layoutParams.apply { this.width = width }
            it.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size)
            centerContent(it, width, false)
        }
    }

    private fun centerContent(chip: Chip, width: Int, icon: Boolean) {
        val d = chip.resources.displayMetrics.density
        val iconWidth = if (icon) chip.chipIconSize + chip.iconEndPadding else 0f
        val textWidth = chip.paint.measureText(chip.text.toString()).coerceAtMost((width - iconWidth - 8 * d).coerceAtLeast(0f))
        val side = ((width - iconWidth - textWidth) / 2).coerceAtLeast(4 * d)
        chip.chipStartPadding = side; chip.chipEndPadding = side
    }

    private fun chips(group: ChipGroup) = (0 until group.childCount).map { group.getChildAt(it) }.filterIsInstance<Chip>()
    private fun compact(chip: Chip, icon: Boolean) {
        val d = chip.resources.displayMetrics.density
        chip.textSize = 13f
        chip.setEnsureMinTouchTargetSize(false)
        chip.chipMinHeight = 48 * d; chip.minHeight = (48 * d).toInt()
        chip.minWidth = (48 * d).toInt()
        chip.includeFontPadding = false
        chip.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        chip.chipCornerRadius = 12 * d
        chip.chipStartPadding = 4 * d; chip.chipEndPadding = 4 * d
        chip.textStartPadding = 0f; chip.textEndPadding = 0f
        chip.iconStartPadding = 0f; chip.iconEndPadding = 4 * d
        chip.chipIconSize = 18 * d
        chip.isChipIconVisible = icon
        chip.ellipsize = android.text.TextUtils.TruncateAt.END
        chip.contentDescription = chip.text
    }
}
