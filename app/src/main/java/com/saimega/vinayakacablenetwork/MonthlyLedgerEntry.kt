package com.saimega.vinayakacablenetwork

data class MonthlyLedgerEntry(
    val monthKey: String,
    val bill: Double,
    val extraCharges: Double,
    val previousOutstanding: Double,
    val paid: Double,
    val balance: Double,
    val isRevised: Boolean,
    val revisionReason: String
)