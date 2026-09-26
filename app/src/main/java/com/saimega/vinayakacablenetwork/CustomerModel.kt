package com.saimega.vinayakacablenetwork

import java.io.Serializable

data class CustomerModel(
    val id: String = "",
    val name: String = "",
    val teluguName: String = "",   // Firestore field: "telugu name"
    val phone: String = "",
    val baseAmount: Double = 0.0,
    val monthlyCharge: Double = 0.0,
    val extraCharges: Double = 0.0,
    val previousDue: Double = 0.0,
    val pendingAmount: Double = 0.0,
    val status: String = "unpaid", // "paid", "partial", "unpaid"
    val paymentStatus: String = "Unpaid",
    val connectionStatus: String = "active", // "active" or "deactivated"
    val deactivatedMonth: String? = null,
    val reconnectedMonth: String? = null,
    val lastPaidMonth: String = "",
    val lastBilledMonth: String = "",
    val joinMonth: String = "",
    val vcNumber: String = "",
    val boxNumber: String = "",
    val crfNumber: String = "",
    val area: String = "",
    val address: String = "",
    val altPhone: String = "",
    val packageId: String = ""
) : Serializable {
    fun displayName(languageCode: String): String =
        if (languageCode == "te" && teluguName.isNotBlank()) teluguName else name

    /**
     * A customer is "active" if Connection Status is active.
     */
    val isActive: Boolean
        get() = connectionStatus.equals("active", ignoreCase = true)

    val isPartiallyPaid: Boolean
        get() = status.equals("partial", ignoreCase = true)
    /**
     * DC Rule: pendingAmount IS the full outstanding bill, set by the billing sync.
     * We never re-add baseAmount on top — that would double-count.
     * Extra charges entered manually in the UI are added here only as a UI preview.
     */
    val totalBill: Double
        get() = pendingAmount  // single source of truth from Firestore

    /**
     * A customer is "paid" if Firestore says so.
     * Never derive this from arithmetic — a DC customer has pendingAmount > 0
     * but is neither paid nor owed a recalculated sum.
     */
    val isPaidLocally: Boolean
        get() = status.equals("paid", ignoreCase = true)
}