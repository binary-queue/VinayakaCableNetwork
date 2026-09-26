package com.saimega.vinayakacablenetwork

data class CustomerMonthStatus(
    val status: String,
    val bill: Double,
    val extraCharges: Double,
    val previousOutstanding: Double,
    val paid: Double,
    val remaining: Double
)