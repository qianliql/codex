package com.example.lightledger.ui

import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import androidx.appcompat.app.AppCompatActivity
import com.example.lightledger.R

enum class LedgerPalette(val key: String, val label: String, val caption: String, val style: Int,
    val accent: Int, val soft: Int, val background: Int) {
    FOREST("forest", "森林绿", "自然清爽，安静记录", R.style.Theme_LightLedger, 0xFF237462.toInt(), 0xFFE5F1EA.toInt(), 0xFFF5F6F3.toInt()),
    BLUE("blue", "海盐蓝", "清透蓝调，轻盈有序", R.style.Theme_LightLedger_Blue, 0xFF356E9B.toInt(), 0xFFE6EFF7.toInt(), 0xFFF3F6FA.toInt()),
    APRICOT("apricot", "暖杏橙", "温暖柔和，贴近日常", R.style.Theme_LightLedger_Apricot, 0xFFA65B36.toInt(), 0xFFF8EBDD.toInt(), 0xFFFAF6F1.toInt()),
    PURPLE("purple", "雾紫", "柔雾紫调，从容记账", R.style.Theme_LightLedger_Purple, 0xFF795497.toInt(), 0xFFF0E8F7.toInt(), 0xFFF7F4FA.toInt());
}

object LedgerThemes {
    private fun prefs(context: Context) = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    fun selected(context: Context) = LedgerPalette.entries.firstOrNull { it.key == prefs(context).getString("palette", "forest") } ?: LedgerPalette.FOREST
    fun select(context: Context, palette: LedgerPalette) { prefs(context).edit().putString("palette", palette.key).apply() }
}

/** Applies before any view is inflated, including pages opened after a restart. */
open class LedgerActivity : AppCompatActivity() {
    private lateinit var appliedPalette: LedgerPalette
    override fun onCreate(savedInstanceState: Bundle?) {
        appliedPalette = LedgerThemes.selected(this)
        setTheme(appliedPalette.style)
        super.onCreate(savedInstanceState)
    }
    override fun onResume() {
        super.onResume()
        if (LedgerThemes.selected(this) != appliedPalette) recreate()
    }
}

fun Context.ledgerColor(color: Int): Int {
    val attr = when (color) {
        R.color.ledger_teal -> androidx.appcompat.R.attr.colorPrimary
        R.color.ledger_teal_dark -> com.google.android.material.R.attr.colorPrimaryVariant
        R.color.ledger_teal_soft -> R.attr.ledgerSoft
        R.color.ledger_background -> R.attr.ledgerBackground
        R.color.ledger_ink -> com.google.android.material.R.attr.colorOnSurface
        R.color.ledger_muted -> R.attr.ledgerMuted
        R.color.ledger_border -> R.attr.ledgerBorder
        else -> return getColor(color)
    }
    val value = TypedValue()
    return if (theme.resolveAttribute(attr, value, true)) {
        if (value.resourceId != 0) getColor(value.resourceId) else value.data
    } else getColor(color)
}
