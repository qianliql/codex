package com.example.lightledger.ui

import com.example.lightledger.data.PaymentMethod
import com.example.lightledger.data.TransactionType
import java.math.BigDecimal
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

object UiFormat {
    fun money(cents: Long): String = NumberFormat.getCurrencyInstance(Locale.CHINA).format(BigDecimal.valueOf(cents, 2))
    fun date(timestamp: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.CHINA).format(Date(timestamp))
    fun type(type: TransactionType): String = when (type) {
        TransactionType.EXPENSE -> "支出"
        TransactionType.INCOME -> "收入"
        TransactionType.REFUND -> "退款"
        TransactionType.TRANSFER -> "转账"
    }
    fun method(method: PaymentMethod): String = when (method) {
        PaymentMethod.WECHAT -> "微信"
        PaymentMethod.ALIPAY -> "支付宝"
        PaymentMethod.BANK_CARD -> "银行卡"
        PaymentMethod.CASH -> "现金"
        PaymentMethod.OTHER -> "其他"
    }
}
