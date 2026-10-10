package com.example.lightledger.ocr

import com.example.lightledger.data.PaymentMethod
import com.example.lightledger.data.TransactionType
import com.example.lightledger.parsing.AmountExtractor
import java.util.Calendar

data class ParsedScreenshot(
    val amountCents: Long?,
    val merchant: String?,
    val type: TransactionType?,
    val paymentMethod: PaymentMethod,
    val platform: String?,
    val timestamp: Long?,
)

interface ScreenshotParser {
    fun canHandle(text: String): Boolean
    fun parse(text: String): ParsedScreenshot?
}

class WeChatScreenshotParser : ScreenshotParser {
    override fun canHandle(text: String) = text.contains("微信支付") || text.contains("微信账单")
    override fun parse(text: String) = ScreenshotTextParser.parse(text, PaymentMethod.WECHAT, "WeChat")
}

class AlipayScreenshotParser : ScreenshotParser {
    override fun canHandle(text: String) = text.contains("支付宝") || text.contains("支付宝账单")
    override fun parse(text: String) = ScreenshotTextParser.parse(text, PaymentMethod.ALIPAY, "Alipay")
}

class GenericScreenshotParser : ScreenshotParser {
    override fun canHandle(text: String) = true
    override fun parse(text: String) = ScreenshotTextParser.parse(text, null, null)
}

object ScreenshotParserRegistry {
    private val parsers = listOf(WeChatScreenshotParser(), AlipayScreenshotParser(), GenericScreenshotParser())
    fun parse(text: String): ParsedScreenshot? = parsers.firstOrNull { it.canHandle(text) }?.parse(text)
}

internal object ScreenshotTextParser {
    private val merchantPattern = Regex("(?:商户|商家|收款方|交易对方|付款给)\\s*[:：]\\s*([^，,。;；\\n¥￥0-9]{2,30})")
    private val fullDate = Regex("(20\\d{2})[年/.-](\\d{1,2})[月/.-](\\d{1,2})日?\\s+(\\d{1,2}):(\\d{2})")

    fun parse(text: String, forcedMethod: PaymentMethod?, platform: String?): ParsedScreenshot {
        val type = when {
            listOf("退款", "退回", "已退").any(text::contains) -> TransactionType.REFUND
            listOf("收款", "到账", "收入").any(text::contains) -> TransactionType.INCOME
            text.contains("转账") -> TransactionType.TRANSFER
            listOf("支出", "付款", "支付", "消费").any(text::contains) -> TransactionType.EXPENSE
            else -> null
        }
        val method = forcedMethod ?: when {
            text.contains("微信") -> PaymentMethod.WECHAT
            text.contains("支付宝") -> PaymentMethod.ALIPAY
            text.contains("银行卡") -> PaymentMethod.BANK_CARD
            text.contains("现金") -> PaymentMethod.CASH
            else -> PaymentMethod.OTHER
        }
        val merchant = merchantPattern.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        val timestamp = fullDate.find(text)?.let { match ->
            try {
                Calendar.getInstance().apply {
                    isLenient = false
                    clear()
                    set(match.groupValues[1].toInt(), match.groupValues[2].toInt() - 1, match.groupValues[3].toInt(),
                        match.groupValues[4].toInt(), match.groupValues[5].toInt())
                }.timeInMillis
            } catch (_: Exception) { null }
        }
        return ParsedScreenshot(AmountExtractor.extract(text), merchant, type, method, platform, timestamp)
    }
}
