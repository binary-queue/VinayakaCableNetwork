package com.saimega.vinayakacablenetwork

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class BillingLedgerBackfill(private val db: FirebaseFirestore = FirebaseFirestore.getInstance()) {

    private data class PaymentTotals(var paid: Double = 0.0, var extra: Double = 0.0, var bill: Double = 0.0)

    suspend fun backfillBefore(targetMonth: String) {
        val monthFormat = SimpleDateFormat("yyyy-MM", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val targetDate = monthFormat.parse(targetMonth) ?: throw IllegalArgumentException("Month must use yyyy-MM")
        val previousMonth = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            time = targetDate
            add(Calendar.MONTH, -1)
        }
        val previousMonthKey = monthFormat.format(previousMonth.time)
        if (previousMonthKey < "2026-04") return

        val customerSnapshot = db.collection("customers").get(Source.SERVER).await()
        val ledgerSnapshot = db.collectionGroup("months").get(Source.SERVER).await()
        val existingLedgers = ledgerSnapshot.documents.mapNotNull { ledger ->
            val customerId = ledger.reference.parent.parent?.id ?: return@mapNotNull null
            (customerId to (ledger.getString("monthKey") ?: ledger.id)) to ledger
        }.toMap()

        val start = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.APRIL, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val end = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            time = previousMonth.time
            set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
        val paymentSnapshot = db.collection("payments")
            .whereGreaterThanOrEqualTo("timestamp", start.timeInMillis)
            .whereLessThanOrEqualTo("timestamp", end.timeInMillis)
            .get(Source.SERVER)
            .await()

        val paymentsByMonth = mutableMapOf<Pair<String, String>, PaymentTotals>()
        paymentSnapshot.documents.forEach { payment ->
            val customerId = payment.getString("customerId") ?: return@forEach
            val month = payment.getString("date")?.take(7)
                ?: monthFormat.format(Date(payment.getLong("timestamp") ?: 0L))
            val totals = paymentsByMonth.getOrPut(customerId to month) { PaymentTotals() }
            totals.paid += (payment.get("paid") as? Number)?.toDouble() ?: 0.0
            totals.extra += (payment.get("extraCharges") as? Number)?.toDouble() ?: 0.0
            val paymentBill = (payment.get("billAmount") as? Number)?.toDouble()
                ?: (payment.get("baseAmount") as? Number)?.toDouble() ?: 0.0
            if (paymentBill > 0.0) totals.bill = paymentBill
        }

        val ledgerWrites = mutableListOf<Pair<com.google.firebase.firestore.DocumentReference, Map<String, Any>>>()
        val pendingBalanceUpdates = mutableListOf<Pair<com.google.firebase.firestore.DocumentReference, Double>>()

        customerSnapshot.documents.forEach { customerDoc ->
            val joinedMonth = customerDoc.getString("joinMonth").orEmpty().ifBlank { "2026-04" }
            val firstMonth = maxOf("2026-04", joinedMonth)
            if (firstMonth > previousMonthKey) return@forEach

            val state = CustomerBillingState(
                id = customerDoc.id,
                connectionStatus = customerDoc.getString("Connection Status") ?: "active",
                pendingAmount = (customerDoc.get("pendingAmount") as? Number)?.toDouble() ?: 0.0,
                monthlyCharge = (customerDoc.get("monthlyCharge") as? Number)?.toDouble()
                    ?: (customerDoc.get("baseAmount") as? Number)?.toDouble() ?: 0.0,
                lastBilledMonth = customerDoc.getString("lastBilledMonth") ?: "",
                joinedMonth = joinedMonth,
                deactivatedMonth = customerDoc.getString("deactivatedMonth"),
                reconnectedMonth = customerDoc.getString("reconnectedMonth")
            )
            var carry = (customerDoc.get("previousDue") as? Number)?.toDouble()?.coerceAtLeast(0.0) ?: 0.0
            for (month in BillingCycle.monthSequence(firstMonth, previousMonthKey)) {
                val existing = existingLedgers[customerDoc.id to month]
                if (existing != null) {
                    val total = (existing.get("total") as? Number)?.toDouble() ?: 0.0
                    val paid = (existing.get("paid") as? Number)?.toDouble() ?: 0.0
                    carry = ((existing.get("remaining") as? Number)?.toDouble() ?: (total - paid)).coerceAtLeast(0.0)
                    continue
                }

                val payment = paymentsByMonth[customerDoc.id to month] ?: PaymentTotals()
                val bill = if (BillingCycle.isActiveForMonth(state, month)) {
                    payment.bill.takeIf { it > 0.0 } ?: state.monthlyCharge
                } else 0.0
                val amounts = BillingCycle.computeMonthlyLedgerAmounts(carry, bill, payment.extra, payment.paid)
                carry = amounts.remaining.coerceAtLeast(0.0)
                val ledgerRef = db.collection("billing").document(customerDoc.id).collection("months").document(month)
                ledgerWrites += ledgerRef to mapOf(
                    "customerId" to customerDoc.id,
                    "name" to (customerDoc.getString("name") ?: ""),
                    "monthKey" to month,
                    "bill" to bill,
                    "extraCharges" to payment.extra,
                    "previousOutstanding" to (amounts.totalDue - bill - payment.extra),
                    "paid" to payment.paid,
                    "total" to amounts.totalDue,
                    "remaining" to carry,
                    "isBillRevised" to false,
                    "timestamp" to System.currentTimeMillis()
                )
            }
            if (customerDoc.getString("lastBilledMonth").orEmpty() < targetMonth) {
                pendingBalanceUpdates += customerDoc.reference to carry
            }
        }

        ledgerWrites.chunked(500).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { (ref, data) -> batch.set(ref, data, SetOptions.merge()) }
            batch.commit().await()
        }
        pendingBalanceUpdates.chunked(500).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { (ref, balance) -> batch.update(ref, "pendingAmount", balance) }
            batch.commit().await()
        }
    }
}