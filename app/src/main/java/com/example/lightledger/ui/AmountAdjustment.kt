package com.example.lightledger.ui

import java.math.BigDecimal
import java.math.RoundingMode

internal enum class AmountOperation(val symbol: String) { ADD("+"), SUBTRACT("−"), MULTIPLY("×"), DIVIDE("÷") }

internal object AmountAdjustment {
    fun calculate(cents: Long, operation: AmountOperation, input: String): Long {
        require(cents > 0) { "请先填写原金额" }
        val operand = input.trim().toBigDecimalOrNull() ?: throw IllegalArgumentException("请输入计算数值")
        require(operand > BigDecimal.ZERO) { "计算数值须大于 0" }
        val base = BigDecimal.valueOf(cents, 2)
        val result = when (operation) {
            AmountOperation.ADD -> base + operand
            AmountOperation.SUBTRACT -> base - operand
            AmountOperation.MULTIPLY -> base * operand
            AmountOperation.DIVIDE -> base.divide(operand, 2, RoundingMode.HALF_UP)
        }.setScale(2, RoundingMode.HALF_UP)
        require(result > BigDecimal.ZERO) { "调整后金额须大于 0" }
        return try { result.movePointRight(2).longValueExact() }
        catch (_: ArithmeticException) { throw IllegalArgumentException("计算结果超出可保存范围") }
    }
}
