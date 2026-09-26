package com.saimega.vinayakacablenetwork

import org.junit.Assert.assertEquals
import org.junit.Test

class BillingCycleTest {

    @Test
    fun `receipt number uses year month and zero padded sequence`() {
        assertEquals("RN/202609/0007", BillingCycle.formatReceiptNumber("2026-09", 7))
    }

    @Test
    fun `monthly ledger adds previous due bill and extras then subtracts paid`() {
        val result = BillingCycle.computeMonthlyLedgerAmounts(
            previousOutstanding = 50.0,
            monthlyBill = 200.0,
            extraCharges = 25.0,
            paid = 150.0
        )

        assertEquals(275.0, result.totalDue, 0.001)
        assertEquals(125.0, result.remaining, 0.001)
    }

    @Test
    fun `bill revision updates only the revised bill delta`() {
        val result = BillingCycle.computeBillRevision(
            currentPending = 175.0,
            previousOutstanding = 50.0,
            oldBill = 200.0,
            newBill = 150.0,
            extraCharges = 25.0,
            paid = 100.0
        )

        assertEquals(125.0, result.pendingAmount, 0.001)
        assertEquals(225.0, result.totalDue, 0.001)
        assertEquals(125.0, result.remaining, 0.001)
    }

    @Test
    fun `monthly payment status uses that month's ledger totals`() {
        assertEquals("unpaid", BillingCycle.monthlyPaymentStatus(200.0, 0.0))
        assertEquals("partial", BillingCycle.monthlyPaymentStatus(200.0, 50.0))
        assertEquals("paid", BillingCycle.monthlyPaymentStatus(200.0, 200.0))
    }

    @Test
    fun `dashboard excludes inactive customers from payment status counts`() {
        val counts = BillingCycle.computeDashboardMonthlyCounts(
            customers = listOf(
                CustomerBillingState("active-paid", "active", 0.0, 100.0),
                CustomerBillingState("active-partial", "active", 50.0, 100.0),
                CustomerBillingState("cut", "deactivated", 25.0, 100.0, deactivatedMonth = "2026-09")
            ),
            monthStatuses = mapOf("active-paid" to "paid", "active-partial" to "partial", "cut" to "unpaid"),
            monthKey = "2026-09"
        )

        assertEquals(2, counts.active)
        assertEquals(1, counts.inactive)
        assertEquals(1, counts.paid)
        assertEquals(1, counts.partial)
        assertEquals(0, counts.unpaid)
    }

    @Test
    fun `billing sequence starts in April and includes requested month`() {
        assertEquals(
            listOf("2026-04", "2026-05", "2026-06", "2026-07", "2026-08", "2026-09"),
            BillingCycle.monthSequence("2026-04", "2026-09")
        )
    }

    @Test
    fun `inactive month ledger carries outstanding without a new bill`() {
        val calculation = BillingCycle.computeMonthlyLedger(
            customer = CustomerBillingState(
                id = "cut",
                connectionStatus = "deactivated",
                pendingAmount = 75.0,
                monthlyCharge = 200.0,
                deactivatedMonth = "2026-09"
            ),
            monthKey = "2026-09",
            previousOutstanding = 75.0,
            extraCharges = 0.0,
            paid = 0.0
        )

        assertEquals(0.0, calculation.bill, 0.001)
        assertEquals(75.0, calculation.remaining, 0.001)
    }

    @Test
    fun `exact payment of pending balance marks customer paid`() {
        val result = BillingCycle.computePaymentBalance(
            pendingAmount = 200.0,
            extraCharges = 0.0,
            amountPaid = 200.0,
            monthKey = "2026-09",
            lastPaidMonth = "2026-08"
        )

        assertEquals(0.0, result.pendingAmount, 0.001)
        assertEquals("2026-09", result.lastPaidMonth)
        assertEquals("paid", result.status)
    }

    @Test
    fun `partial payment preserves remainder and prior paid month`() {
        val result = BillingCycle.computePaymentBalance(
            pendingAmount = 200.0,
            extraCharges = 20.0,
            amountPaid = 150.0,
            monthKey = "2026-09",
            lastPaidMonth = "2026-08"
        )

        assertEquals(70.0, result.pendingAmount, 0.001)
        assertEquals("2026-08", result.lastPaidMonth)
        assertEquals("partial", result.status)
    }

    @Test
    fun `extra charge is included once in amount due`() {
        val result = BillingCycle.computePaymentBalance(
            pendingAmount = 200.0,
            extraCharges = 25.0,
            amountPaid = 225.0,
            monthKey = "2026-09",
            lastPaidMonth = ""
        )

        assertEquals(0.0, result.pendingAmount, 0.001)
        assertEquals("paid", result.status)
    }

    @Test
    fun `partial customer carries forward remainder plus new monthly charge`() {
        val customers = listOf(
            CustomerBillingState(
                id = "c1",
                connectionStatus = "active",
                pendingAmount = 50.0,
                monthlyCharge = 200.0
            )
        )

        val updates = BillingCycle.computeMonthlyBillUpdates(customers)

        assertEquals(1, updates.size)
        assertEquals("c1", updates[0].id)
        assertEquals(250.0, updates[0].newPendingAmount, 0.001)
        assertEquals(200.0, updates[0].monthlyBill, 0.001)
        assertEquals(50.0, updates[0].previousOutstanding, 0.001)
    }

    @Test
    fun `fully paid customer still gets billed for the new month`() {
        val customers = listOf(
            CustomerBillingState(
                id = "c2",
                connectionStatus = "active",
                pendingAmount = 0.0,
                monthlyCharge = 200.0
            )
        )

        val updates = BillingCycle.computeMonthlyBillUpdates(customers)

        assertEquals(1, updates.size)
        assertEquals(200.0, updates[0].newPendingAmount, 0.001)
    }

    @Test
    fun `customer already billed for month is not billed twice`() {
        val updates = BillingCycle.computeMonthlyBillUpdates(
            listOf(CustomerBillingState("c1", "active", 50.0, 200.0, "2026-09")),
            "2026-09"
        )

        assertEquals(0, updates.size)
    }

    @Test
    fun `line deactivated in october remains billable for september only`() {
        val customer = CustomerBillingState(
            id = "cut-in-october",
            connectionStatus = "deactivated",
            pendingAmount = 50.0,
            monthlyCharge = 200.0,
            deactivatedMonth = "2026-10"
        )

        assertEquals(250.0, BillingCycle.computeMonthlyBillUpdates(listOf(customer), "2026-09").single().newPendingAmount, 0.001)
        assertEquals(0, BillingCycle.computeMonthlyBillUpdates(listOf(customer), "2026-10").size)
    }

    @Test
    fun `reconnected line resumes billing from reconnect month`() {
        val customer = CustomerBillingState(
            id = "reconnected",
            connectionStatus = "active",
            pendingAmount = 50.0,
            monthlyCharge = 200.0,
            deactivatedMonth = "2026-09",
            reconnectedMonth = "2026-11"
        )

        assertEquals(0, BillingCycle.computeMonthlyBillUpdates(listOf(customer), "2026-10").size)
        assertEquals(250.0, BillingCycle.computeMonthlyBillUpdates(listOf(customer), "2026-11").single().newPendingAmount, 0.001)
    }

    @Test
    fun `customer is not active before join month`() {
        val customer = CustomerBillingState(
            id = "joined-later",
            connectionStatus = "active",
            pendingAmount = 0.0,
            monthlyCharge = 200.0,
            joinedMonth = "2026-08"
        )

        assertEquals(0, BillingCycle.computeMonthlyBillUpdates(listOf(customer), "2026-07").size)
        assertEquals(1, BillingCycle.computeMonthlyBillUpdates(listOf(customer), "2026-08").size)
    }

    @Test
    fun `inactive customer is excluded entirely`() {
        val customers = listOf(
            CustomerBillingState(
                id = "c3",
                connectionStatus = "inactive",
                pendingAmount = 100.0,
                monthlyCharge = 200.0
            )
        )

        val updates = BillingCycle.computeMonthlyBillUpdates(customers)

        assertEquals(0, updates.size)
    }

    @Test
    fun `mixed batch only bills active customers`() {
        val customers = listOf(
            CustomerBillingState("active-1", "active", 50.0, 200.0),
            CustomerBillingState("inactive-1", "inactive", 999.0, 200.0),
            CustomerBillingState("active-2", "active", 0.0, 150.0)
        )

        val updates = BillingCycle.computeMonthlyBillUpdates(customers)

        assertEquals(2, updates.size)
        assertEquals(250.0, updates.first { it.id == "active-1" }.newPendingAmount, 0.001)
        assertEquals(150.0, updates.first { it.id == "active-2" }.newPendingAmount, 0.001)
    }

    @Test
    fun `connection status match is case insensitive`() {
        val customers = listOf(
            CustomerBillingState("c4", "ACTIVE", 0.0, 200.0)
        )

        val updates = BillingCycle.computeMonthlyBillUpdates(customers)

        assertEquals(1, updates.size)
    }

}
