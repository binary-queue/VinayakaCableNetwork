package com.saimega.vinayakacablenetwork

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class CashHandoverRepository {

    private val db = FirebaseFirestore.getInstance()

    suspend fun submitToday(collectorUsername: String): HandoverSubmissionResult {
        if (collectorUsername.isBlank()) {
            return HandoverSubmissionResult.Failure(IllegalArgumentException("Collector username is required"))
        }

        return try {
            val now = System.currentTimeMillis()
            val dayStart = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(now))
            val snapshot = db.collection("payments")
                .whereEqualTo("collectorUsername", collectorUsername)
                .whereGreaterThanOrEqualTo("timestamp", dayStart)
                .whereLessThanOrEqualTo("timestamp", now)
                .get(Source.SERVER)
                .await()
            val candidateDocs = snapshot.documents.filter { it.getString("handoverId").isNullOrBlank() }
            if (candidateDocs.isEmpty()) return HandoverSubmissionResult.NothingToSubmit
            if (candidateDocs.size > 499) {
                return HandoverSubmissionResult.Failure(
                    IllegalStateException("More than 499 payments need submission. Contact an administrator.")
                )
            }

            val handoverRef = db.collection("handovers").document()
            val result = db.runTransaction { transaction ->
                val freshDocs = candidateDocs.map { transaction.get(it.reference) }
                val unsubmitted = freshDocs.filter { it.exists() && it.getString("handoverId").isNullOrBlank() }
                if (unsubmitted.isEmpty()) return@runTransaction null

                val payments = unsubmitted.mapNotNull { doc ->
                    doc.toObject(PaymentModel::class.java)?.copy(paymentId = doc.id)
                }
                val totals = CashHandoverMath.totals(payments)
                val paymentIds = unsubmitted.map { it.id }
                val handover = CashHandover(
                    id = handoverRef.id,
                    collectorUsername = collectorUsername,
                    date = date,
                    paymentIds = paymentIds,
                    cashAmount = totals.cashAmount,
                    digitalAmount = totals.digitalAmount,
                    totalAmount = totals.totalAmount,
                    paymentCount = totals.paymentCount,
                    status = "pending",
                    submittedAt = now
                )

                transaction.set(handoverRef, handover)
                unsubmitted.forEach { paymentDoc ->
                    transaction.update(paymentDoc.reference, mapOf(
                        "handoverId" to handoverRef.id,
                        "handoverSubmittedAt" to now
                    ))
                }
                handover
            }.await()

            if (result == null) HandoverSubmissionResult.NothingToSubmit
            else HandoverSubmissionResult.Submitted(result)
        } catch (e: Exception) {
            HandoverSubmissionResult.Failure(e)
        }
    }

    suspend fun pendingHandovers(): List<CashHandover> {
        val snapshot = db.collection("handovers")
            .whereEqualTo("status", "pending")
            .get(Source.SERVER)
            .await()
        return snapshot.documents.mapNotNull { doc ->
            doc.toObject(CashHandover::class.java)?.copy(id = doc.id)
        }.sortedByDescending { it.submittedAt }
    }

    suspend fun paymentsFor(handover: CashHandover): List<PaymentModel> {
        return handover.paymentIds.mapNotNull { paymentId ->
            val doc = db.collection("payments").document(paymentId).get(Source.SERVER).await()
            doc.toObject(PaymentModel::class.java)?.copy(paymentId = doc.id)
        }
    }

    suspend fun verify(handoverId: String, verifierUsername: String): Boolean {
        if (verifierUsername.isBlank()) return false
        return try {
            val ref = db.collection("handovers").document(handoverId)
            db.runTransaction { transaction ->
                val snapshot = transaction.get(ref)
                if (!snapshot.exists() || snapshot.getString("status") != "pending") {
                    return@runTransaction false
                }
                transaction.update(ref, mapOf(
                    "status" to "verified",
                    "verifiedBy" to verifierUsername,
                    "verifiedAt" to System.currentTimeMillis()
                ))
                true
            }.await()
        } catch (e: Exception) {
            false
        }
    }
}