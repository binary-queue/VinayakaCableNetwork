package com.saimega.vinayakacablenetwork

/**
 * Snapshot of the fields needed to compute one customer's next billing cycle.
 * Deliberately a plain data class (not CustomerModel) so this stays testable
 * without touching Firestore.
 */
data class CustomerBillingState(
    val id: String,
    val connectionStatus: String,
    val pendingAmount: Double,
    val monthlyCharge: Double,
    val lastBilledMonth: String = "",
    val joinedMonth: String = "",
    val deactivatedMonth: String? = null,
    val reconnectedMonth: String? = null
)

/** Result of billing one customer: their new pendingAmount for the new cycle. */
data class BilledCustomerUpdate(
    val id: String,
    val newPendingAmount: Double,
    val monthlyBill: Double,
    val previousOutstanding: Double
)

data class MonthlyLedgerAmounts(
    val totalDue: Double,
    val remaining: Double
)

data class MonthlyLedgerCalculation(
    val bill: Double,
    val previousOutstanding: Double,
    val extraCharges: Double,
    val paid: Double,
    val totalDue: Double,
    val remaining: Double
)

data class BillRevisionUpdate(
    val pendingAmount: Double,
    val totalDue: Double,
    val remaining: Double
)

data class DashboardMonthlyCounts(
    val active: Int,
    val inactive: Int,
    val paid: Int,
    val partial: Int,
    val unpaid: Int
)

data class PaymentBalanceUpdate(
    val pendingAmount: Double,
    val lastPaidMonth: String,
    val status: String
)

object BillingCycle {

    fun monthSequence(firstMonth: String, lastMonth: String): List<String> {
        val format = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val first = format.parse(firstMonth) ?: return emptyList()
        val last = format.parse(lastMonth) ?: return emptyList()
        val cursor = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { time = first }
        val end = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { time = last }
        val result = mutableListOf<String>()
        while (!cursor.after(end)) {
            result += format.format(cursor.time)
            cursor.add(java.util.Calendar.MONTH, 1)
        }
        return result
    }

    fun computeMonthlyLedger(
        customer: CustomerBillingState,
        monthKey: String,
        previousOutstanding: Double,
        extraCharges: Double,
        paid: Double
    ): MonthlyLedgerCalculation {
        val bill = if (isActiveForMonth(customer, monthKey)) customer.monthlyCharge.coerceAtLeast(0.0) else 0.0
        val totals = computeMonthlyLedgerAmounts(previousOutstanding, bill, extraCharges, paid)
        return MonthlyLedgerCalculation(
            bill = bill,
            previousOutstanding = previousOutstanding.coerceAtLeast(0.0),
            extraCharges = extraCharges.coerceAtLeast(0.0),
            paid = paid.coerceAtLeast(0.0),
            totalDue = totals.totalDue,
            remaining = totals.remaining.coerceAtLeast(0.0)
        )
    }

    fun computeMonthlyLedgerAmounts(
        previousOutstanding: Double,
        monthlyBill: Double,
        extraCharges: Double,
        paid: Double
    ): MonthlyLedgerAmounts {
        val totalDue = previousOutstanding.coerceAtLeast(0.0) +
            monthlyBill.coerceAtLeast(0.0) + extraCharges.coerceAtLeast(0.0)
        return MonthlyLedgerAmounts(totalDue, totalDue - paid.coerceAtLeast(0.0))
    }

    fun computeBillRevision(
        currentPending: Double,
        previousOutstanding: Double,
        oldBill: Double,
        newBill: Double,
        extraCharges: Double,
        paid: Double
    ): BillRevisionUpdate {
        val ledger = computeMonthlyLedgerAmounts(previousOutstanding, newBill, extraCharges, paid)
        return BillRevisionUpdate(
            pendingAmount = (currentPending + newBill - oldBill).coerceAtLeast(0.0),
            totalDue = ledger.totalDue,
            remaining = ledger.remaining
        )
    }

    fun monthlyPaymentStatus(totalDue: Double, paid: Double): String = when {
        paid <= 0.0 -> "unpaid"
        paid >= totalDue -> "paid"
        else -> "partial"
    }

    fun computeDashboardMonthlyCounts(
        customers: List<CustomerBillingState>,
        monthStatuses: Map<String, String>,
        monthKey: String
    ): DashboardMonthlyCounts {
        var active = 0
        var inactive = 0
        var paid = 0
        var partial = 0
        var unpaid = 0
        customers.forEach { customer ->
            if (!isActiveForMonth(customer, monthKey)) {
                inactive++
            } else {
                active++
                when (monthStatuses[customer.id] ?: "unpaid") {
                    "paid" -> paid++
                    "partial" -> partial++
                    else -> unpaid++
                }
            }
        }
        return DashboardMonthlyCounts(active, inactive, paid, partial, unpaid)
    }

    fun formatReceiptNumber(monthKey: String, sequence: Int): String {
        val compactMonth = monthKey.replace("-", "")
        return "RN/$compactMonth/${sequence.toString().padStart(4, '0')}"
    }

    fun computePaymentBalance(
        pendingAmount: Double,
        extraCharges: Double,
        amountPaid: Double,
        monthKey: String,
        lastPaidMonth: String
    ): PaymentBalanceUpdate {
        val amountDue = pendingAmount.coerceAtLeast(0.0) + extraCharges.coerceAtLeast(0.0)
        val newPending = (amountDue - amountPaid.coerceAtLeast(0.0)).coerceAtLeast(0.0)
        val paidMonth = if (newPending == 0.0) monthKey else lastPaidMonth
        val status = when {
            newPending == 0.0 -> "paid"
            amountPaid > 0.0 -> "partial"
            else -> "unpaid"
        }
        return PaymentBalanceUpdate(newPending, paidMonth, status)
    }

    /**
     * Carry-forward rule: whatever is left unpaid (0 if fully paid, the
     * remainder otherwise) plus this month's monthlyCharge. Deactivated
     * customers are excluded — they accrue nothing while inactive.
     */
    fun computeMonthlyBillUpdates(
        customers: List<CustomerBillingState>,
        monthKey: String? = null
    ): List<BilledCustomerUpdate> {
        return customers
            .filter { state ->
                if (monthKey == null) state.connectionStatus.equals("active", ignoreCase = true)
                else isActiveForMonth(state, monthKey)
            }
            .filter { monthKey == null || it.lastBilledMonth != monthKey }
            .map {
                val monthlyBill = it.monthlyCharge.coerceAtLeast(0.0)
                val previousOutstanding = it.pendingAmount.coerceAtLeast(0.0)
                BilledCustomerUpdate(
                    id = it.id,
                    newPendingAmount = previousOutstanding + monthlyBill,
                    monthlyBill = monthlyBill,
                    previousOutstanding = previousOutstanding
                )
            }
    }

    fun isActiveForMonth(state: CustomerBillingState, monthKey: String): Boolean {
        if (state.joinedMonth.isNotBlank() && monthKey < state.joinedMonth) return false
        val deactivatedMonth = state.deactivatedMonth
        if (deactivatedMonth == null) {
            return state.connectionStatus.equals("active", ignoreCase = true)
        }
        val wasDeactivated = monthKey >= deactivatedMonth
        val wasReconnected = state.reconnectedMonth?.let { monthKey >= it } ?: false
        return !wasDeactivated || wasReconnected
    }
}
