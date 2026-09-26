package com.saimega.vinayakacablenetwork

data class CashHandover(
    val id: String = "",
    val collectorUsername: String = "",
    val date: String = "",
    val paymentIds: List<String> = emptyList(),
    val cashAmount: Double = 0.0,
    val digitalAmount: Double = 0.0,
    val totalAmount: Double = 0.0,
    val paymentCount: Int = 0,
    val status: String = "pending",
    val submittedAt: Long = 0L,
    val verifiedBy: String = "",
    val verifiedAt: Long = 0L
)

data class HandoverTotals(
    val cashAmount: Double,
    val digitalAmount: Double,
    val paymentCount: Int
) {
    val totalAmount: Double
        get() = cashAmount + digitalAmount
}

object CashHandoverMath {
    fun totals(payments: List<PaymentModel>): HandoverTotals {
        val cash = payments.filter { it.paymentMode.equals("Cash", ignoreCase = true) }
            .sumOf { it.paid }
        val digital = payments.filterNot { it.paymentMode.equals("Cash", ignoreCase = true) }
            .sumOf { it.paid }
        return HandoverTotals(cash, digital, payments.size)
    }
}

sealed class HandoverSubmissionResult {
    data class Submitted(val handover: CashHandover) : HandoverSubmissionResult()
    object NothingToSubmit : HandoverSubmissionResult()
    data class Failure(val exception: Exception) : HandoverSubmissionResult()
}