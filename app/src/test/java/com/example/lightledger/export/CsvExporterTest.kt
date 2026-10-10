package com.example.lightledger.export

import com.example.lightledger.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter

class CsvExporterTest {
    @Test fun exportsExactCentsTagsAndEscapesUserText() {
        val snapshot = LedgerSnapshot(
            transactions = listOf(LedgerTransaction(id = 1, amountCents = 12345,
                type = TransactionType.EXPENSE, merchant = "=HYPERLINK(\"x\")",
                categoryId = 1, timestamp = 1_800_000_000_000L,
                note = "第一行,\n第二行")),
            categories = listOf(Category(1, "餐饮")),
            tags = listOf(Tag(2, "旅行")),
            transactionTags = listOf(TransactionTagCrossRef(1, 2)),
            merchantRules = emptyList(), settings = emptyList(),
        )
        val writer = StringWriter()
        CsvExporter.write(snapshot, writer)
        val csv = writer.toString()
        assertTrue(csv.startsWith("\uFEFF时间,类型,金额"))
        assertTrue(csv.contains("\"123.45\""))
        assertTrue(csv.contains("\"旅行\""))
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"x\"\")\""))
        assertTrue(csv.contains("\"第一行,\n第二行\""))
    }
}
