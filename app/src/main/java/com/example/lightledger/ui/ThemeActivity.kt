package com.example.lightledger.ui

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import com.example.lightledger.databinding.ActivityThemeBinding
import com.example.lightledger.databinding.RowThemeBinding

class ThemeActivity : LedgerActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityThemeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBarInsets.apply(binding.root)
        binding.goBack.setOnClickListener { finish() }
        populate(binding, this) { palette ->
            if (LedgerThemes.selected(this) != palette) {
                LedgerThemes.select(this, palette)
                recreate()
            }
        }
    }
    companion object {
        internal fun populate(binding: ActivityThemeBinding, context: android.content.Context, onSelect: (LedgerPalette) -> Unit) {
            binding.themeChoices.removeAllViews()
            val selected = LedgerThemes.selected(context)
            LedgerPalette.entries.forEach { palette ->
                val row = RowThemeBinding.inflate(android.view.LayoutInflater.from(context), binding.themeChoices, false)
                row.themeName.text = palette.label
                row.themeCaption.text = palette.caption
                row.selectedLabel.visibility = if (palette == selected) View.VISIBLE else View.GONE
                row.root.strokeWidth = ((if (palette == selected) 2 else 1) * context.resources.displayMetrics.density).toInt()
                row.root.strokeColor = context.ledgerColor(if (palette == selected) com.example.lightledger.R.color.ledger_teal else com.example.lightledger.R.color.ledger_border)
                listOf(row.accentSwatch to palette.accent, row.softSwatch to palette.soft, row.backgroundSwatch to palette.background).forEach { (view, color) ->
                    view.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL; setColor(color)
                        setStroke((context.resources.displayMetrics.density).toInt(), palette.accent and 0xFFFFFF or 0x33000000)
                    }
                }
                row.root.contentDescription = "${palette.label}，${if (palette == selected) "当前配色" else "点击切换"}"
                row.root.setOnClickListener { onSelect(palette) }
                binding.themeChoices.addView(row.root)
            }
        }
    }
}
