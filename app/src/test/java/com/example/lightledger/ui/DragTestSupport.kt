package com.example.lightledger.ui

import android.content.ClipData
import android.view.DragEvent
import com.example.lightledger.data.Category
import org.robolectric.Robolectric

/** Emulates the OS drag dispatcher; does not claim physical touch acceptance on a phone. */
internal object DragTestSupport {
    var source: Category? = null
    fun controller() = Robolectric.buildActivity(CategoryActivity::class.java).apply {
        get().categoryDragStarter = { _, category -> source = category; true }
    }.setup()
    fun event(action: Int, category: Category, x: Float = 0f, y: Float = 0f): DragEvent {
        val clip = ClipData.newPlainText("分类", category.id.toString())
        val method = DragEvent::class.java.declaredMethods.first { it.name == "obtain" && it.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType && it.parameterTypes.contains(ClipData::class.java) }
        method.isAccessible = true
        var floatIndex = 0
        val args = method.parameterTypes.mapIndexed { index, type -> when {
            index == 0 -> action
            type == Float::class.javaPrimitiveType -> when (floatIndex++) { 0 -> x; 1 -> y; else -> 0f }
            type == Int::class.javaPrimitiveType -> 0
            type == Boolean::class.javaPrimitiveType -> true
            type == Any::class.java -> category
            type == ClipData::class.java -> clip
            type == android.content.ClipDescription::class.java -> clip.description
            else -> null
        } }.toTypedArray()
        return method.invoke(null, *args) as DragEvent
    }
}
