package com.example.lightledger.data

import androidx.room.*

enum class TransactionType { EXPENSE, INCOME, REFUND, TRANSFER }
enum class PaymentMethod { WECHAT, ALIPAY, BANK_CARD, CASH, OTHER }
enum class TransactionSource { NOTIFICATION, SCREENSHOT, MANUAL }
enum class ClassificationStatus { CLASSIFIED, UNCLASSIFIED, NEED_CONFIRM }

class LedgerConverters {
    @TypeConverter fun type(value: String): TransactionType = enumValueOf(value)
    @TypeConverter fun type(value: TransactionType): String = value.name
    @TypeConverter fun method(value: String): PaymentMethod = enumValueOf(value)
    @TypeConverter fun method(value: PaymentMethod): String = value.name
    @TypeConverter fun source(value: String): TransactionSource = enumValueOf(value)
    @TypeConverter fun source(value: TransactionSource): String = value.name
    @TypeConverter fun status(value: String): ClassificationStatus = enumValueOf(value)
    @TypeConverter fun status(value: ClassificationStatus): String = value.name
}

@Entity(tableName = "categories", indices = [Index(value = ["name"], unique = true)])
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isSystem: Boolean = false,
)

@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class Tag(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)

@Entity(tableName = "transaction_tags", primaryKeys = ["transactionId", "tagId"],
    foreignKeys = [
        ForeignKey(entity = LedgerTransaction::class, parentColumns = ["id"], childColumns = ["transactionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Tag::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE),
    ], indices = [Index("tagId")])
data class TransactionTagCrossRef(val transactionId: Long, val tagId: Long)

@Entity(tableName = "merchant_rules", indices = [Index(value = ["keyword"], unique = true), Index("categoryId")],
    foreignKeys = [ForeignKey(entity = Category::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.CASCADE)])
data class MerchantRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val keyword: String,
    val categoryId: Long,
    val exactMatch: Boolean = true,
)

@Entity(tableName = "settings")
data class AppSetting(@PrimaryKey val key: String, val value: String)

@Entity(
    tableName = "transactions",
    foreignKeys = [ForeignKey(
        entity = Category::class, parentColumns = ["id"], childColumns = ["categoryId"],
        onDelete = ForeignKey.SET_NULL
    )],
    indices = [Index("categoryId"), Index("timestamp"), Index(value = ["notificationKey"], unique = true)]
)
data class LedgerTransaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountCents: Long,
    val type: TransactionType,
    val merchant: String? = null,
    val categoryId: Long? = null,
    val paymentMethod: PaymentMethod = PaymentMethod.OTHER,
    val platform: String? = null,
    val timestamp: Long,
    val source: TransactionSource = TransactionSource.MANUAL,
    val note: String? = null,
    val classificationStatus: ClassificationStatus = ClassificationStatus.UNCLASSIFIED,
    val refundOfTransactionId: Long? = null,
    val notificationKey: String? = null,
    val ocrText: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

data class TransactionRow(
    @Embedded val transaction: LedgerTransaction,
    @ColumnInfo(name = "categoryName") val categoryName: String?,
)

data class MonthTotals(val expense: Long, val refund: Long)

data class CategoryTotal(val label: String, val amountCents: Long)
data class PaymentTotal(val paymentMethod: PaymentMethod, val amountCents: Long)
data class TimeTotal(val period: String, val expense: Long, val refund: Long)
data class TagTotal(val id: Long, val name: String, val expense: Long)

data class LedgerSnapshot(
    val transactions: List<LedgerTransaction>,
    val categories: List<Category>,
    val tags: List<Tag>,
    val transactionTags: List<TransactionTagCrossRef>,
    val merchantRules: List<MerchantRule>,
    val settings: List<AppSetting>,
)
