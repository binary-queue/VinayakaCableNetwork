package com.saimega.vinayakacablenetwork

data class PaymentModel(
    val paymentId: String = "",
    val customerId: String = "",
    val name: String = "",
    val teluguName: String = "",
    val phone: String = "",
    val vcNumber: String = "",
    val packageName: String = "",
    val baseAmount: Double = 0.0,
    val billAmount: Double = 0.0,
    val previousOutstanding: Double = 0.0,
    val alreadyPaid: Double = 0.0,
    val extraCharges: Double = 0.0,
    val total: Double = 0.0,
    val paid: Double = 0.0,
    val remaining: Double = 0.0,
    val paymentMode: String = "",
    val paymentNumber: String = "",
    val remarks: String = "",
    val smsRequested: Boolean = false,
    val receiptNumber: String = "",
    val collectorUsername: String = "",
    val handoverId: String = "",
    val handoverSubmittedAt: Long = 0L,
    val date: String = "",
    val month: Int = 0,
    val year: Int = 0,
    val timestamp: Long = 0L
) {
    fun displayName(languageCode: String): String =
        if (languageCode == "te" && teluguName.isNotBlank()) teluguName else name
}
