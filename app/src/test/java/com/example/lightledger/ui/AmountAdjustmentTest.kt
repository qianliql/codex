package com.example.lightledger.ui

import org.junit.Assert.*
import org.junit.Test

class AmountAdjustmentTest {
    @Test fun addsSubtractsMultipliesAndDividesInCents() {
        assertEquals(1135L, AmountAdjustment.calculate(1235, AmountOperation.SUBTRACT, "1"))
        assertEquals(1435L, AmountAdjustment.calculate(1235, AmountOperation.ADD, "2"))
        assertEquals(988L, AmountAdjustment.calculate(1235, AmountOperation.MULTIPLY, "0.8"))
        assertEquals(618L, AmountAdjustment.calculate(1235, AmountOperation.DIVIDE, "2"))
    }

    @Test fun rejectsZeroNegativeResultAndOverflow() {
        assertTrue(runCatching { AmountAdjustment.calculate(100, AmountOperation.DIVIDE, "0") }.isFailure)
        assertTrue(runCatching { AmountAdjustment.calculate(100, AmountOperation.SUBTRACT, "1") }.isFailure)
        assertTrue(runCatching { AmountAdjustment.calculate(Long.MAX_VALUE, AmountOperation.MULTIPLY, "2") }.isFailure)
    }
}
