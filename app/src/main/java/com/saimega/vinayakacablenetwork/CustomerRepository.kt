package com.saimega.vinayakacablenetwork

import android.util.Log
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

sealed class BillingRunResult {
    data class Success(val customersBilled: Int) : BillingRunResult()
    object AlreadyRun : BillingRunResult()
    data class Failure(val exception: Exception) : BillingRunResult()
}

class CustomerRepository {

    companion object {
        const val PAGE_SIZE = 20L
    }

    private val db = FirebaseFirestore.getInstance()

    data class PageResult(
        val customers: List<CustomerModel>,
        val lastDocument: DocumentSnapshot?
    )

    // =========================
    // CUSTOMER PAGINATION
    // =========================

    private fun mapDocToCustomer(doc: DocumentSnapshot): CustomerModel {
        val rawStatus = doc.getString("status")?.trim()
        val statusVal = if (rawStatus.isNullOrEmpty()) "DATA_ERROR" else rawStatus
        val paymentStatusRaw = doc.getString("paymentStatus")?.trim()
        val connStatus = doc.getString("Connection Status") ?: "active"

        return CustomerModel(
            id = doc.id,
            name = doc.getString("name") ?: "",
            teluguName = doc.getString("telugu name") ?: "",
            phone = doc.getString("phone") ?: "",
            baseAmount = (doc.get("baseAmount") as? Number)?.toDouble() ?: 0.0,
            monthlyCharge = (doc.get("monthlyCharge") as? Number)?.toDouble() ?: 0.0,
            extraCharges = (doc.get("extraCharges") as? Number)?.toDouble() ?: 0.0,
            previousDue = (doc.get("previousDue") as? Number)?.toDouble() ?: 0.0,
            pendingAmount = (doc.get("pendingAmount") as? Number)?.toDouble() ?: 0.0,
            status = statusVal,
            paymentStatus = paymentStatusRaw ?: statusVal ?: "Unpaid",
            connectionStatus = connStatus,
            deactivatedMonth = doc.getString("deactivatedMonth"),
            reconnectedMonth = doc.getString("reconnectedMonth"),
            lastPaidMonth = doc.getString("lastPaidMonth") ?: "",
            lastBilledMonth = doc.getString("lastBilledMonth") ?: "",
            joinMonth = doc.getString("joinMonth") ?: "",
            vcNumber = doc.getString("vcNumber") ?: doc.getString("VC No") ?: "",
            boxNumber = doc.getString("boxNumber") ?: doc.getString("STB/box No") ?: "",
            crfNumber = doc.getString("crfNumber") ?: doc.getString("CRF No") ?: "",
            address = doc.getString("address") ?: "",
            packageId = doc.getString("packageId") ?: doc.getString("package") ?: ""
        )
    }

    suspend fun fetchFirstPage(status: String): PageResult {
        val snapshot = db.collection("customers").get(Source.SERVER).await()
        val customers = snapshot.documents.map { mapDocToCustomer(it) }
        val filtered = when (status.lowercase()) {
            "paid" -> customers.filter { it.paymentStatus.equals("paid", true) }
            "unpaid" -> customers.filter { it.paymentStatus.equals("unpaid", true) }
            "partial" -> customers.filter { it.status.equals("partial", true) }
            else -> customers
        }
        return PageResult(filtered, null)
    }

    suspend fun fetchNextPage(status: String, lastDocument: DocumentSnapshot): PageResult = PageResult(emptyList(), null)

    fun submitPayment(
        customer: CustomerModel,
        amountPaid: Double,
        paymentMode: String,
        paymentNumber: String,
        extraCharges: Double,
        collectorUsername: String,
        remarks: String,
        smsRequested: Boolean,
        onSuccess: (String) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        if (amountPaid <= 0.0 || extraCharges < 0.0) {
            onFailure(IllegalArgumentException("Payment must be positive and extra charges cannot be negative"))
            return
        }

        val paymentRef = db.collection("payments").document()
        val now = System.currentTimeMillis()
        val dateString = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(now))
        val monthKey = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date(now))
        val customerRef = db.collection("customers").document(customer.id)
        val billingRef = db.collection("billing").document(customer.id).collection("months").document(monthKey)
        val monthCounterRef = db.collection("receiptCounters").document(monthKey.replace("-", ""))

        db.runTransaction { transaction ->
            val customerSnapshot = transaction.get(customerRef)
            val billingSnapshot = transaction.get(billingRef)
            val counterSnapshot = transaction.get(monthCounterRef)
            if (!customerSnapshot.exists()) throw IllegalStateException("Customer ${customer.id} not found")

            val baseAmount = (customerSnapshot.get("baseAmount") as? Number)?.toDouble() ?: 0.0
            val currentPending = (customerSnapshot.get("pendingAmount") as? Number)?.toDouble() ?: baseAmount
            val previousPaid = (billingSnapshot.get("paid") as? Number)?.toDouble() ?: 0.0
            val monthlyBill = (billingSnapshot.get("bill") as? Number)?.toDouble()
                ?: (customerSnapshot.get("monthlyCharge") as? Number)?.toDouble() ?: baseAmount
            val previousOutstanding = (billingSnapshot.get("previousOutstanding") as? Number)?.toDouble()
                ?: (currentPending - monthlyBill).coerceAtLeast(0.0)
            val previousExtraCharges = (billingSnapshot.get("extraCharges") as? Number)?.toDouble() ?: 0.0
            val paymentUpdate = BillingCycle.computePaymentBalance(
                currentPending, extraCharges, amountPaid, monthKey,
                customerSnapshot.getString("lastPaidMonth") ?: ""
            )
            val newPaid = previousPaid + amountPaid
            val ledgerAmounts = BillingCycle.computeMonthlyLedgerAmounts(
                previousOutstanding, monthlyBill, previousExtraCharges + extraCharges, newPaid
            )
            val sequence = ((counterSnapshot.get("lastSequence") as? Number)?.toInt() ?: 0) + 1
            val receiptNumber = BillingCycle.formatReceiptNumber(monthKey, sequence)

            transaction.update(customerRef, mapOf(
                "pendingAmount" to paymentUpdate.pendingAmount,
                "lastPaidMonth" to paymentUpdate.lastPaidMonth,
                "status" to paymentUpdate.status,
                "paymentStatus" to paymentUpdate.status.replaceFirstChar { it.uppercase() }
            ))
            transaction.set(paymentRef, PaymentModel(
                paymentId = paymentRef.id,
                customerId = customer.id,
                name = customerSnapshot.getString("name") ?: customer.name,
                teluguName = customerSnapshot.getString("telugu name") ?: customer.teluguName,
                phone = customerSnapshot.getString("phone") ?: "",
                vcNumber = customerSnapshot.getString("vcNumber") ?: customerSnapshot.getString("VC No") ?: customer.id,
                packageName = customerSnapshot.getString("package") ?: customerSnapshot.getString("packageId") ?: "",
                baseAmount = baseAmount,
                billAmount = monthlyBill,
                previousOutstanding = previousOutstanding,
                alreadyPaid = previousPaid,
                extraCharges = extraCharges,
                total = ledgerAmounts.totalDue,
                paid = amountPaid,
                remaining = ledgerAmounts.remaining,
                paymentMode = paymentMode,
                paymentNumber = paymentNumber,
                remarks = remarks.trim(),
                smsRequested = smsRequested,
                receiptNumber = receiptNumber,
                collectorUsername = collectorUsername,
                date = dateString,
                timestamp = now
            ))
            transaction.set(billingRef, mapOf(
                "customerId" to customer.id,
                "name" to customer.name,
                "monthKey" to monthKey,
                "bill" to monthlyBill,
                "previousOutstanding" to previousOutstanding,
                "extraCharges" to (previousExtraCharges + extraCharges),
                "total" to ledgerAmounts.totalDue,
                "paid" to newPaid,
                "remaining" to ledgerAmounts.remaining,
                "isBillRevised" to (billingSnapshot.getBoolean("isBillRevised") ?: false),
                "revisionReason" to (billingSnapshot.getString("revisionReason") ?: ""),
                "timestamp" to now
            ), SetOptions.merge())
            transaction.set(monthCounterRef, mapOf("lastSequence" to sequence, "month" to monthKey), SetOptions.merge())
            paymentRef.id
        }.addOnSuccessListener { paymentId ->
            onSuccess(paymentId)
        }.addOnFailureListener(onFailure)
    }

    /**
     * Corrects an already-recorded payment's amount/mode/reference number.
     *
     * Adjusts the customer's pendingAmount by exactly the delta between the
     * old and new amount, and recomputes status using the SAME rule
     * submitPayment uses (paid/partial iff the payment's own month still
     * equals the customer's lastPaidMonth, unpaid otherwise) — lastPaidMonth
     * itself is deliberately left untouched. This means an edit that revokes
     * an "advance credit" (a payment that was large enough to mark a future
     * month paid) will correctly fix pendingAmount/status for the payment's
     * own month, but cannot claw back that future month's credit — the
     * payment record doesn't retain enough history to safely reverse that.
     */
    suspend fun editPayment(paymentId: String, newAmount: Double, newMode: String, newNumber: String): Boolean {
        return try {
            val paymentRef = db.collection("payments").document(paymentId)
            db.runTransaction { transaction ->
                val paymentSnapshot = transaction.get(paymentRef)
                if (!paymentSnapshot.exists()) {
                    throw IllegalStateException("Payment $paymentId not found")
                }

                val customerId = paymentSnapshot.getString("customerId") ?: ""
                val oldPaid = (paymentSnapshot.get("paid") as? Number)?.toDouble() ?: 0.0
                val total = (paymentSnapshot.get("total") as? Number)?.toDouble() ?: 0.0
                // The payment's own month ("yyyy-MM"), from its "yyyy-MM-dd" date —
                // this is the month being corrected, not necessarily the current month.
                val paymentMonthKey = (paymentSnapshot.getString("date") ?: "").take(7)

                val customerRef = db.collection("customers").document(customerId)
                val billingRef = db.collection("billing")
                    .document(customerId)
                    .collection("months")
                    .document(paymentMonthKey)
                val customerSnapshot = transaction.get(customerRef)
                val billingSnapshot = transaction.get(billingRef)
                val currentPending = (customerSnapshot.get("pendingAmount") as? Number)?.toDouble() ?: 0.0
                val lastPaidMonth = customerSnapshot.getString("lastPaidMonth") ?: ""

                val delta = newAmount - oldPaid
                val newPending = (currentPending - delta).coerceAtLeast(0.0)

                val status = if (lastPaidMonth == paymentMonthKey) {
                    if (newPending == 0.0) "paid" else "partial"
                } else {
                    "unpaid"
                }

                transaction.update(customerRef, mapOf(
                    "pendingAmount" to newPending,
                    "status" to status,
                    "paymentStatus" to status.replaceFirstChar { it.uppercase() }
                ))

                transaction.update(paymentRef, mapOf(
                    "paid" to newAmount,
                    "remaining" to (total - newAmount),
                    "paymentMode" to newMode,
                    "paymentNumber" to newNumber
                ))
                if (billingSnapshot.exists()) {
                    val ledgerTotal = (billingSnapshot.get("total") as? Number)?.toDouble() ?: 0.0
                    val ledgerPaid = (billingSnapshot.get("paid") as? Number)?.toDouble() ?: 0.0
                    val revisedPaid = (ledgerPaid + delta).coerceAtLeast(0.0)
                    transaction.update(billingRef, mapOf(
                        "paid" to revisedPaid,
                        "remaining" to (ledgerTotal - revisedPaid)
                    ))
                }
                null
            }.await()
            true
        } catch (e: Exception) {
            false
        }
    }

    // =========================
    // MONTHLY BILL GENERATION
    // =========================

    /**
     * Advances every active customer's billing cycle: pendingAmount becomes
     * whatever was left unpaid plus this month's monthlyCharge (the
     * carry-forward rule). Deactivated customers are skipped entirely.
     *
     * Idempotent per [monthKey]: if meta/billing.lastGeneratedMonth already
     * equals [monthKey], this is a no-op that returns [BillingRunResult.AlreadyRun].
     */
    suspend fun generateMonthlyBills(monthKey: String): BillingRunResult {
        return try {
            val metaRef = db.collection("meta").document("billing")
            val metaSnap = metaRef.get(Source.SERVER).await()
            val monthFormat = SimpleDateFormat("yyyy-MM", Locale.US)
            val targetDate = monthFormat.parse(monthKey) ?: throw IllegalArgumentException("Month must use yyyy-MM")
            val currentMonth = monthFormat.format(Date())
            if (monthKey < "2026-04" || monthKey > currentMonth) {
                throw IllegalArgumentException("Billing month must be between April 2026 and the current month")
            }
            val backfillVersion = (metaSnap.get("ledgerBackfillVersion") as? Number)?.toInt() ?: 0
            if (metaSnap.getString("lastGeneratedMonth") == monthKey && backfillVersion >= 1) {
                return BillingRunResult.AlreadyRun
            }
            val lastGeneratedMonth = metaSnap.getString("lastGeneratedMonth")
            if (lastGeneratedMonth != null && monthKey < lastGeneratedMonth) {
                throw IllegalArgumentException("Cannot generate an earlier month after a later billing run")
            }

            BillingLedgerBackfill(db).backfillBefore(monthKey)

            // Fetch all and filter client-side (not whereEqualTo): many customer
            // documents (e.g. anything created via NewCustomerActivity) have no
            // "Connection Status" field at all, and Firestore's whereEqualTo can
            // never match a missing field. mapDocToCustomer's default-to-"active"
            // convention is what determines status for those documents, so the
            // query must fetch everything and apply that same default here.
            val snapshot = db.collection("customers")
                .get(Source.SERVER)
                .await()

            val states = snapshot.documents.map { doc ->
                CustomerBillingState(
                    id = doc.id,
                    connectionStatus = doc.getString("Connection Status") ?: "active",
                    pendingAmount = (doc.get("pendingAmount") as? Number)?.toDouble() ?: 0.0,
                    monthlyCharge = (doc.get("monthlyCharge") as? Number)?.toDouble()
                        ?: (doc.get("baseAmount") as? Number)?.toDouble() ?: 0.0,
                    lastBilledMonth = doc.getString("lastBilledMonth") ?: "",
                    joinedMonth = doc.getString("joinMonth") ?: "",
                    deactivatedMonth = doc.getString("deactivatedMonth"),
                    reconnectedMonth = doc.getString("reconnectedMonth")
                )
            }

            val updates = BillingCycle.computeMonthlyBillUpdates(states, monthKey)
            val inactiveWithoutLedger = states.filter { state ->
                state.lastBilledMonth != monthKey && !BillingCycle.isActiveForMonth(state, monthKey)
            }

            updates.chunked(250).forEach { chunk ->
                val batch = db.batch()
                for (update in chunk) {
                    val customerRef = db.collection("customers").document(update.id)
                    val ledgerRef = db.collection("billing")
                        .document(update.id)
                        .collection("months")
                        .document(monthKey)
                    batch.update(customerRef, mapOf(
                        "pendingAmount" to update.newPendingAmount,
                        "lastBilledMonth" to monthKey,
                        "status" to "unpaid",
                        "paymentStatus" to "Unpaid"
                    ))
                    batch.set(ledgerRef, mapOf(
                        "customerId" to update.id,
                        "monthKey" to monthKey,
                        "bill" to update.monthlyBill,
                        "extraCharges" to 0.0,
                        "previousOutstanding" to update.previousOutstanding,
                        "paid" to 0.0,
                        "total" to update.newPendingAmount,
                        "remaining" to update.newPendingAmount,
                        "isBillRevised" to false,
                        "timestamp" to System.currentTimeMillis()
                    ), SetOptions.merge())
                }
                batch.commit().await()
            }

            inactiveWithoutLedger.chunked(500).forEach { chunk ->
                val batch = db.batch()
                chunk.forEach { state ->
                    val ledgerRef = db.collection("billing")
                        .document(state.id)
                        .collection("months")
                        .document(monthKey)
                    val previousOutstanding = state.pendingAmount.coerceAtLeast(0.0)
                    batch.set(ledgerRef, mapOf(
                        "customerId" to state.id,
                        "monthKey" to monthKey,
                        "bill" to 0.0,
                        "extraCharges" to 0.0,
                        "previousOutstanding" to previousOutstanding,
                        "paid" to 0.0,
                        "total" to previousOutstanding,
                        "remaining" to previousOutstanding,
                        "isBillRevised" to false,
                        "timestamp" to System.currentTimeMillis()
                    ), SetOptions.merge())
                }
                batch.commit().await()
            }

            metaRef.set(
                mapOf(
                    "lastGeneratedMonth" to monthKey,
                    "lastGeneratedAt" to System.currentTimeMillis(),
                    "ledgerBackfillVersion" to 1
                ),
                SetOptions.merge()
            ).await()

            BillingRunResult.Success(updates.size)
        } catch (e: Exception) {
            BillingRunResult.Failure(e)
        }
    }

    suspend fun setConnectionStatus(customerId: String, active: Boolean, monthKey: String): Boolean {
        return try {
            val changes = if (active) {
                mapOf("Connection Status" to "active", "reconnectedMonth" to monthKey)
            } else {
                mapOf(
                    "Connection Status" to "deactivated",
                    "deactivatedMonth" to monthKey,
                    "reconnectedMonth" to FieldValue.delete()
                )
            }
            db.collection("customers").document(customerId).update(changes).await()
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun reviseMonthlyBill(customerId: String, monthKey: String, newBill: Double, reason: String): Boolean {
        if (newBill < 0.0 || reason.isBlank()) return false
        val currentMonth = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
        if (monthKey != currentMonth) return false

        return try {
            val customerRef = db.collection("customers").document(customerId)
            val ledgerRef = db.collection("billing")
                .document(customerId)
                .collection("months")
                .document(monthKey)
            db.runTransaction { transaction ->
                val customerSnapshot = transaction.get(customerRef)
                val ledgerSnapshot = transaction.get(ledgerRef)
                if (!customerSnapshot.exists()) throw IllegalStateException("Customer not found")
                val currentPending = (customerSnapshot.get("pendingAmount") as? Number)?.toDouble() ?: 0.0
                val oldBill = (ledgerSnapshot.get("bill") as? Number)?.toDouble()
                    ?: (customerSnapshot.get("monthlyCharge") as? Number)?.toDouble()
                    ?: (customerSnapshot.get("baseAmount") as? Number)?.toDouble() ?: 0.0
                val previousOutstanding = (ledgerSnapshot.get("previousOutstanding") as? Number)?.toDouble()
                    ?: (currentPending - oldBill).coerceAtLeast(0.0)
                val extraCharges = (ledgerSnapshot.get("extraCharges") as? Number)?.toDouble() ?: 0.0
                val paid = (ledgerSnapshot.get("paid") as? Number)?.toDouble() ?: 0.0
                val update = BillingCycle.computeBillRevision(
                    currentPending = currentPending,
                    previousOutstanding = previousOutstanding,
                    oldBill = oldBill,
                    newBill = newBill,
                    extraCharges = extraCharges,
                    paid = paid
                )
                val status = when {
                    update.pendingAmount == 0.0 -> "paid"
                    paid > 0.0 -> "partial"
                    else -> "unpaid"
                }
                val lastPaidMonth = if (update.pendingAmount == 0.0) monthKey
                else customerSnapshot.getString("lastPaidMonth") ?: ""
                transaction.update(customerRef, mapOf(
                    "pendingAmount" to update.pendingAmount,
                    "lastPaidMonth" to lastPaidMonth,
                    "status" to status,
                    "paymentStatus" to status.replaceFirstChar { it.uppercase() }
                ))
                transaction.set(ledgerRef, mapOf(
                    "customerId" to customerId,
                    "monthKey" to monthKey,
                    "bill" to newBill,
                    "previousOutstanding" to previousOutstanding,
                    "extraCharges" to extraCharges,
                    "paid" to paid,
                    "total" to update.totalDue,
                    "remaining" to update.remaining,
                    "isBillRevised" to true,
                    "revisionReason" to reason.trim(),
                    "billRevisedAt" to System.currentTimeMillis()
                ), SetOptions.merge())
                null
            }.await()
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun fetchMonthlyLedger(customerId: String): List<MonthlyLedgerEntry> {
        val snapshot = db.collection("billing").document(customerId)
            .collection("months").get(Source.SERVER).await()
        return snapshot.documents.map { doc ->
            MonthlyLedgerEntry(
                monthKey = doc.getString("monthKey") ?: doc.id,
                bill = (doc.get("bill") as? Number)?.toDouble() ?: (doc.get("total") as? Number)?.toDouble() ?: 0.0,
                extraCharges = (doc.get("extraCharges") as? Number)?.toDouble() ?: 0.0,
                previousOutstanding = (doc.get("previousOutstanding") as? Number)?.toDouble() ?: 0.0,
                paid = (doc.get("paid") as? Number)?.toDouble() ?: 0.0,
                balance = (doc.get("remaining") as? Number)?.toDouble() ?: 0.0,
                isRevised = doc.getBoolean("isBillRevised") ?: false,
                revisionReason = doc.getString("revisionReason") ?: ""
            )
        }.sortedByDescending { it.monthKey }
    }

    suspend fun fetchTotalOutstanding(): Double {
        val snapshot = db.collection("customers").get(Source.SERVER).await()
        return snapshot.documents.sumOf {
            ((it.get("pendingAmount") as? Number)?.toDouble() ?: 0.0).coerceAtLeast(0.0)
        }
    }

    suspend fun fetchCustomerMonthStatuses(monthKey: String): Map<String, CustomerMonthStatus> {
        val snapshot = db.collectionGroup("months").get(Source.SERVER).await()
        return snapshot.documents.filter { (it.getString("monthKey") ?: it.id) == monthKey }.mapNotNull { doc ->
            val customerId = doc.reference.parent.parent?.id ?: doc.getString("customerId") ?: return@mapNotNull null
            val bill = (doc.get("bill") as? Number)?.toDouble() ?: (doc.get("total") as? Number)?.toDouble() ?: 0.0
            val extra = (doc.get("extraCharges") as? Number)?.toDouble() ?: 0.0
            val previous = (doc.get("previousOutstanding") as? Number)?.toDouble() ?: 0.0
            val paid = (doc.get("paid") as? Number)?.toDouble() ?: 0.0
            val total = (doc.get("total") as? Number)?.toDouble() ?: bill + extra + previous
            customerId to CustomerMonthStatus(
                status = BillingCycle.monthlyPaymentStatus(total, paid),
                bill = bill,
                extraCharges = extra,
                previousOutstanding = previous,
                paid = paid,
                remaining = (doc.get("remaining") as? Number)?.toDouble() ?: (total - paid)
            )
        }.toMap()
    }

    suspend fun fetchCollectibleCustomers(monthKey: String): List<CustomerModel> {
        val customers = db.collection("customers").get(Source.SERVER).await().documents
        val monthStatuses = fetchCustomerMonthStatuses(monthKey)
        return customers.mapNotNull { doc ->
            val customer = mapDocToCustomer(doc)
            val state = CustomerBillingState(
                id = customer.id,
                connectionStatus = customer.connectionStatus,
                pendingAmount = customer.pendingAmount,
                monthlyCharge = customer.monthlyCharge,
                lastBilledMonth = customer.lastBilledMonth,
                joinedMonth = customer.joinMonth,
                deactivatedMonth = customer.deactivatedMonth,
                reconnectedMonth = customer.reconnectedMonth
            )
            if (!BillingCycle.isActiveForMonth(state, monthKey)) return@mapNotNull null
            val monthStatus = monthStatuses[customer.id]
            val status = monthStatus?.status ?: customer.status.lowercase()
            val remaining = monthStatus?.remaining ?: customer.pendingAmount
            if (status !in listOf("unpaid", "partial") || remaining <= 0.0) return@mapNotNull null
            customer.copy(
                status = status,
                paymentStatus = status.replaceFirstChar { it.uppercase() },
                pendingAmount = remaining
            )
        }.sortedByDescending { it.pendingAmount }
    }

    /**
     * Rebuilds a billing document for the given customer and month from the
     * source‑of‑truth payments collection. Used for recovery when a billing doc
     * is missing or corrupted.
     */
    suspend fun rebuildBillingFromPayments(customerId: String, monthKey: String) {
        // Parse monthKey (yyyy-MM) to start/end timestamps
        val monthDate = SimpleDateFormat("yyyy-MM", Locale.getDefault()).parse(monthKey) ?: return
        val cal = Calendar.getInstance().apply { time = monthDate }
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val startTs = cal.timeInMillis
        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59); cal.set(Calendar.MILLISECOND, 999)
        val endTs = cal.timeInMillis

        // Fetch all payments for this customer in the month
        val snapshot = db.collection("payments")
            .whereEqualTo("customerId", customerId)
            .whereGreaterThanOrEqualTo("timestamp", startTs)
            .whereLessThanOrEqualTo("timestamp", endTs)
            .get(Source.SERVER)
            .await()

        // Sum paid amounts
        val totalPaid = snapshot.documents.sumOf { doc -> (doc.get("paid") as? Number)?.toDouble() ?: 0.0 }

        // Retrieve baseAmount and extraCharges from the customer document (source of truth)
        val customerSnap = db.collection("customers").document(customerId).get(Source.SERVER).await()
        val baseAmount = (customerSnap.get("baseAmount") as? Number)?.toDouble() ?: 0.0
        val extraCharges = (customerSnap.get("extraCharges") as? Number)?.toDouble() ?: 0.0
        val name = customerSnap.getString("name") ?: ""
        val total = baseAmount + extraCharges
        val remaining = total - totalPaid

        val billingData = mapOf(
            "customerId" to customerId,
            "name"       to name,
            "total"      to total,
            "paid"       to totalPaid,
            "remaining"  to remaining,
            "timestamp"  to System.currentTimeMillis()
        )

        val billingRef = db.collection("billing")
            .document(customerId)
            .collection("months")
            .document(monthKey)

        billingRef.set(billingData, SetOptions.merge()).await()
    }


    // =========================
    // FETCH UNPAID
    // =========================

    suspend fun fetchAllUnpaid(): List<CustomerModel> {
        val snapshot = db.collection("customers")
            .get(Source.SERVER)
            .await()

        val customers = snapshot.documents.map { mapDocToCustomer(it) }
        
        return customers.filter { it.paymentStatus.equals("Unpaid", ignoreCase = true) }
    }

    // =========================
    // DISTINCT FILTERING FUNCTIONS
    // =========================

    suspend fun getPaidCustomers(): List<CustomerModel> {
        val snapshot = db.collection("customers").get(Source.SERVER).await()
        return snapshot.documents
            .map { mapDocToCustomer(it) }
            .filter { it.paymentStatus.equals("Paid", ignoreCase = true) }
    }

    suspend fun getUnpaidCustomers(): List<CustomerModel> {
        val snapshot = db.collection("customers").get(Source.SERVER).await()
        return snapshot.documents
            .map { mapDocToCustomer(it) }
            .filter { it.paymentStatus.equals("Unpaid", ignoreCase = true) }
    }

    suspend fun getCustomersByStatus(status: String): List<CustomerModel> {
        val snapshot = db.collection("customers").get(Source.SERVER).await()
        return snapshot.documents
            .map { mapDocToCustomer(it) }
            .filter { it.paymentStatus.equals(status, ignoreCase = true) }
    }

    suspend fun getUnpaidCustomersForPdf(): List<CustomerModel> {
        // Fetch unpaid customers strictly based on the paymentStatus field,
        // and sort them alphabetically by name for the PDF report.
        return getUnpaidCustomers().sortedBy { it.name }
    }

    // ── Day-wise report: timestamp range ──────────────────────────────────────
    // startDate / endDate in "yyyy-MM-dd". Converts to midnight–23:59:59.999 ms.
    // Filters paid > 0 on the client; sorted latest first.
    suspend fun fetchPaymentsByDateRange(startDate: String, endDate: String): List<PaymentModel> {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        val cal = Calendar.getInstance()

        // Start of startDate → 00:00:00.000
        cal.time = sdf.parse(startDate) ?: Date()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0);      cal.set(Calendar.MILLISECOND, 0)
        val startTs = cal.timeInMillis

        // End of endDate → 23:59:59.999
        cal.time = sdf.parse(endDate) ?: Date()
        cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59);      cal.set(Calendar.MILLISECOND, 999)
        val endTs = cal.timeInMillis

        return fetchPaymentsByTimestampRange(startTs, endTs)
    }

    // ── Month-wise report: full calendar month ─────────────────────────────────
    // month: 1–12,  year: e.g. 2025
    suspend fun fetchPaymentsByMonth(month: Int, year: Int): List<PaymentModel> {
        val cal = Calendar.getInstance()

        // First millisecond of the month
        cal.set(year, month - 1, 1, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startTs = cal.timeInMillis

        // Last millisecond of the month
        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59);      cal.set(Calendar.MILLISECOND, 999)
        val endTs = cal.timeInMillis

        return fetchPaymentsByTimestampRange(startTs, endTs)
    }

    // ── Core: raw timestamp range query ───────────────────────────────────────
    // All other report methods delegate here.
    // Only records with paid > 0 are returned; sorted latest timestamp first.
    suspend fun fetchPaymentsByTimestampRange(startTs: Long, endTs: Long): List<PaymentModel> {
        val snapshot = db.collection("payments")
            .whereGreaterThanOrEqualTo("timestamp", startTs)
            .whereLessThanOrEqualTo("timestamp", endTs)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .get(Source.SERVER)
            .await()

        return snapshot.toObjects(PaymentModel::class.java)
            .filter { it.paid > 0.0 }   // guard against ₹0 records
    }

    suspend fun importCustomerBatch(customers: List<CustomerModel>) {
        val batch = db.batch()
        for (c in customers) {
            val ref = db.collection("customers").document(c.id)
            val data = hashMapOf(
                "id" to c.id,
                "name" to c.name,
                "telugu name" to c.teluguName,
                "phone" to c.phone,
                "baseAmount" to c.baseAmount,
                "pendingAmount" to c.pendingAmount,
                "status" to c.status,
                "Connection Status" to c.connectionStatus,
                "lastPaidMonth" to c.lastPaidMonth,
                "timestamp" to System.currentTimeMillis()
            )
            batch.set(ref, data)
        }
        batch.commit().await()
    }

    /**
     * Updates only the editable identity/billing-input fields of an existing
     * customer. Deliberately uses `update`, not `set` — a full-replace `set`
     * would wipe billing state (pendingAmount, status, lastPaidMonth, etc.)
     * that this edit form never touches.
     */
    suspend fun updateCustomer(
        id: String,
        name: String,
        phone: String,
        baseAmount: Double,
        teluguName: String,
        vcNumber: String,
        boxNumber: String,
        crfNumber: String,
        address: String,
        packageName: String
    ): Boolean {
        return try {
            db.collection("customers").document(id)
                .update(mapOf(
                    "name" to name,
                    "phone" to phone,
                    "baseAmount" to baseAmount,
                    "monthlyCharge" to baseAmount,
                    "telugu name" to teluguName,
                    "vcNumber" to vcNumber,
                    "VC No" to vcNumber,
                    "boxNumber" to boxNumber,
                    "STB/box No" to boxNumber,
                    "crfNumber" to crfNumber,
                    "CRF No" to crfNumber,
                    "address" to address,
                    "package" to packageName,
                    "packageId" to packageName
                ))
                .await()
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun deleteCustomer(id: String): Boolean {
        return try {
            db.collection("customers").document(id).delete().await()
            true
        } catch (e: Exception) {
            false
        }
    }
}