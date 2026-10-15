package com.example.lightledger.notification

import com.example.lightledger.data.PaymentMethod
import com.example.lightledger.data.TransactionType
import com.example.lightledger.parsing.AmountExtractor

data class ParsedPaymentNotification(
    val amountCents: Long,
    val type: TransactionType,
    val merchant: String?,
    val paymentMethod: PaymentMethod,
    val platform: String,
)

interface NotificationParser {
    val packageName: String
    fun parse(title: String, text: String): ParsedPaymentNotification?
}

class WeChatNotificationParser : NotificationParser {
    override val packageName = "com.tencent.mm"

    override fun parse(title: String, text: String): ParsedPaymentNotification? {
        val trustedTitle = title.contains("微信支付") || title.contains("微信收款") ||
            title.contains("微信退款") || title.contains("微信转账") ||
            (title == "微信" && PaymentTextParser.hasOfficialWeChatPrefix(text)) ||
            (title.contains("服务通知") &&
                (PaymentTextParser.hasOfficialWeChatPrefix(text) || PaymentTextParser.hasStrongOutcomeWithAmount(text)))
        if (!trustedTitle) return null
        return PaymentTextParser.parse(title, text, PaymentMethod.WECHAT, "WeChat")
    }
}

class AlipayNotificationParser : NotificationParser {
    override val packageName = "com.eg.android.AlipayGphone"

    override fun parse(title: String, text: String): ParsedPaymentNotification? {
        val combined = "$title\n$text"
        val transactionWord = listOf("支付成功", "付款成功", "付款完成", "支付完成", "成功付款", "成功支付", "扣款成功", "已支付", "交易成功", "支出", "收款", "到账", "退款", "转账")
            .any(combined::contains)
        val trustedTitle = title.contains("支付宝") || title.contains("交易提醒") || title.contains("支付提醒") || title.contains("收款提醒")
        if (!transactionWord || !(trustedTitle || PaymentTextParser.hasStrongOutcomeWithAmount(combined))) return null
        return PaymentTextParser.parse(title, text, PaymentMethod.ALIPAY, "Alipay")
    }
}

object NotificationParserRegistry {
    private val parsers: List<NotificationParser> = listOf(WeChatNotificationParser(), AlipayNotificationParser())
    fun parse(packageName: String, title: String, text: String): ParsedPaymentNotification? =
        parsers.firstOrNull { it.packageName == packageName }?.parse(title, text)
}

/** Only actual payment records with a reliable currency amount may be saved. */
internal object PaymentTextParser {
    private val reportPattern = Regex("(?:记账|账单|消费|收支|收款|微信支付)(?:日|周|月|年)报|(?:月|年)度账单")
    private val invalidOutcome = Regex("(?:支付|付款|扣款|交易|转账|收款|退款)(?:失败|未成功|待确认|待支付|处理中|已取消)|(?:待|等待|请|去|尚未|未完成)支付|等待付款|请付款|支付已取消|交易关闭")
    private val promotionPattern = Regex("领取|优惠券|消费券|支付有礼|付款有礼|支付立减|满减活动|红包活动")
    private val officialWeChatPrefix = Regex("^\\s*(?:\\[\\d+条\\]\\s*)?微信(?:支付|收款|退款|转账)(?:\\s*[:：]|\\s|$)")
    private val labeledAmount = Regex("(?:实付金额|付款金额|支付金额|交易金额|退款金额|收款金额|支出金额|转账金额|实付)\\s*[:：]\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)(?![0-9.,])")
    private val merchantPattern = Regex("(?:商户|收款方|交易对象)\\s*[:：]\\s*([^，,。;；\\n¥￥0-9]{2,30})")
    private val toMerchantPattern = Regex("向([^，,。;；\\n¥￥0-9]{2,30})付款")
    private val strongOutcomes = listOf("支付成功", "付款成功", "付款完成", "支付完成", "成功付款", "成功支付", "扣款成功", "已支付", "交易成功", "收款成功", "收款到账", "退款成功", "退款到账", "转账成功")
    // Alipay's transaction reminder may describe a completed expense before offering points.
    // Require both the single-expense wording and a currency-marked amount.
    private val singleExpense = Regex("(?:你有)?一笔\\s*(?:[¥￥]\\s*[0-9][0-9,]*(?:\\.[0-9]{1,2})?|[0-9][0-9,]*(?:\\.[0-9]{1,2})?\\s*元)\\s*的支出")

    fun hasOfficialWeChatPrefix(text: String) = officialWeChatPrefix.containsMatchIn(text)

    private fun normalizedAmounts(text: String): String = labeledAmount.replace(text) { match ->
        match.value.replace(match.groupValues[1], "¥${match.groupValues[1]}")
    }

    private fun amount(text: String) = AmountExtractor.extract(normalizedAmounts(text))

    fun hasStrongOutcomeWithAmount(text: String): Boolean =
        (strongOutcomes.any(text::contains) || singleExpense.containsMatchIn(text)) &&
            amount(text) != null && !invalidOutcome.containsMatchIn(text)

    fun rejectionReason(title: String, text: String): String {
        val combined = "$title\n$text"
        return when {
            reportPattern.containsMatchIn(combined) -> "日报、月报等汇总消息，已跳过"
            invalidOutcome.containsMatchIn(combined) -> "待支付、处理中或失败消息，已跳过"
            promotionPattern.containsMatchIn(combined) && !hasStrongOutcomeWithAmount(text) -> "促销内容未确认单笔交易，已跳过"
            amount(combined) == null -> "没有可靠的单笔金额（缺少金额或金额冲突），已跳过"
            else -> "未确认支付来源或交易结果，已跳过"
        }
    }

    fun parse(title: String, text: String, method: PaymentMethod, platform: String): ParsedPaymentNotification? {
        val combined = "$title\n$text"
        // Reports may contain totals; they are not individual transactions.
        if (reportPattern.containsMatchIn(combined)) return null
        if (invalidOutcome.containsMatchIn(combined)) return null
        if (promotionPattern.containsMatchIn(combined) && !hasStrongOutcomeWithAmount(text)) return null
        val amountCents = amount(combined) ?: return null
        val type = when {
            listOf("退款", "退回", "已退").any(combined::contains) -> TransactionType.REFUND
            combined.contains("转账") -> TransactionType.TRANSFER
            listOf("支付成功", "付款成功", "付款完成", "支付完成", "成功付款", "成功支付", "已支付", "扣款成功", "实付", "支出").any(text::contains) -> TransactionType.EXPENSE
            listOf("到账", "收入").any(combined::contains) || Regex("收款(?!方|人|账户|商户)").containsMatchIn(combined) -> TransactionType.INCOME
            listOf("支付", "支出", "付款", "扣款", "消费", "交易成功").any(combined::contains) -> TransactionType.EXPENSE
            else -> return null
        }
        val merchant = (merchantPattern.find(combined)?.groupValues?.get(1)
            ?: toMerchantPattern.find(combined)?.groupValues?.get(1))
            ?.trim()?.takeIf { it.isNotEmpty() }
        return ParsedPaymentNotification(amountCents, type, merchant, method, platform)
    }
}
