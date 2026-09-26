package com.saimega.vinayakacablenetwork

import org.junit.Assert.assertEquals
import org.junit.Test

class CashHandoverTest {

    @Test
    fun `handover totals split cash and digital without losing payment count`() {
        val totals = CashHandoverMath.totals(listOf(
            PaymentModel(paid = 100.0, paymentMode = "Cash"),
            PaymentModel(paid = 50.0, paymentMode = "UPI"),
            PaymentModel(paid = 25.0, paymentMode = "Cash")
        ))

        assertEquals(125.0, totals.cashAmount, 0.001)
        assertEquals(50.0, totals.digitalAmount, 0.001)
        assertEquals(175.0, totals.totalAmount, 0.001)
        assertEquals(3, totals.paymentCount)
    }
}