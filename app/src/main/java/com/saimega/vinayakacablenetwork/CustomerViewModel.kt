package com.saimega.vinayakacablenetwork

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

/**
 * CustomerViewModel
 *
 * Owns all state for CustomerListActivity.
 *
 * Data strategy — real-time Firestore snapshot listener:
 *  - [_customers] is updated automatically whenever any customer document changes in Firestore.
 *  - This means returning from CustomerDetailsActivity after a payment automatically
 *    reflects the new "paid" status and ₹0 pendingAmount with NO manual refresh needed.
 *  - The listener is started in [init] and stopped in [onCleared] to prevent memory leaks.
 *
 * DC Rule: the UI must show [CustomerModel.pendingAmount] directly from Firestore —
 * never a locally-recalculated sum. The adapter and model already enforce this.
 */
class CustomerViewModel : ViewModel() {

    private val db = FirebaseFirestore.getInstance()
    private val repository = CustomerRepository()

    // ── Public state ──────────────────────────────────────────────────────────

    /** Full filtered list pushed to the adapter in real-time. */
    private val _customers = MutableLiveData<List<CustomerModel>>(emptyList())
    val customers: LiveData<List<CustomerModel>> = _customers

    /** True while the initial load is in-flight. */
    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    /** Non-null on Firestore error. */
    private val _error = MutableLiveData<String?>(null)
    val error: LiveData<String?> = _error

    // ── Private state ─────────────────────────────────────────────────────────
    private var snapshotListener: ListenerRegistration? = null

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Call once from Activity.onCreate. Attaches a real-time Firestore snapshot
     * listener over the full customers collection — status/search filtering is
     * the adapter's job now (see CustomerAdapter.setStatusFilter/filter), so a
     * search box always has every customer to search, regardless of whatever
     * status filter the screen was opened with.
     */
    fun init() {
        attachSnapshotListener()
    }

    /**
     * Force a re-attach (e.g. after a connectivity recovery).
     * For normal use, the snapshot listener handles updates automatically.
     */
    fun refresh() {
        detachSnapshotListener()
        attachSnapshotListener()
    }

    // Compatibility shim — kept so scroll listener calls still compile
    fun loadNextPageIfNeeded() { /* real-time listener returns all matching docs */ }

    // ── Snapshot listener ─────────────────────────────────────────────────────

    private fun attachSnapshotListener() {
        if (snapshotListener != null) return   // already attached

        _isLoading.value = true

        snapshotListener = db.collection("customers").addSnapshotListener { snapshot, error ->
            _isLoading.value = false

            if (error != null) {
                _error.value = error.message
                return@addSnapshotListener
            }

            if (snapshot == null) return@addSnapshotListener

            val allDocs = snapshot.documents.mapNotNull { doc ->
                try { mapDocToCustomer(doc) } catch (e: Exception) { null }
            }

            _customers.value = allDocs.sortedBy { it.name }
        }
    }

    private fun detachSnapshotListener() {
        snapshotListener?.remove()
        snapshotListener = null
    }

    // ── Firestore → CustomerModel mapping ─────────────────────────────────────

    private fun mapDocToCustomer(doc: com.google.firebase.firestore.DocumentSnapshot): CustomerModel {
        val rawStatus = doc.getString("status")?.trim()
        val statusVal = if (rawStatus.isNullOrEmpty()) "DATA_ERROR" else rawStatus
        val paymentStatusRaw = doc.getString("paymentStatus")?.trim()
        val connStatus = doc.getString("Connection Status") ?: "active"

        return CustomerModel(
            id            = doc.id,
            name          = doc.getString("name") ?: "",
            teluguName    = doc.getString("telugu name") ?: "",
            phone         = doc.getString("phone") ?: "",
            baseAmount    = (doc.get("baseAmount") as? Number)?.toDouble() ?: 0.0,
            monthlyCharge = (doc.get("monthlyCharge") as? Number)?.toDouble() ?: 0.0,
            extraCharges  = (doc.get("extraCharges") as? Number)?.toDouble() ?: 0.0,
            previousDue   = (doc.get("previousDue") as? Number)?.toDouble() ?: 0.0,
            pendingAmount = (doc.get("pendingAmount") as? Number)?.toDouble() ?: 0.0,
            status        = statusVal,
            paymentStatus = paymentStatusRaw ?: statusVal,
            connectionStatus = connStatus,
            deactivatedMonth = doc.getString("deactivatedMonth"),
            reconnectedMonth = doc.getString("reconnectedMonth"),
            lastPaidMonth = doc.getString("lastPaidMonth") ?: "",
            joinMonth = doc.getString("joinMonth") ?: "",
            vcNumber = doc.getString("vcNumber") ?: doc.getString("VC No") ?: "",
            boxNumber = doc.getString("boxNumber")
                ?: (doc.get(com.google.firebase.firestore.FieldPath.of("STB/box No")) as? String)
                ?: "",
            crfNumber = doc.getString("crfNumber") ?: doc.getString("CRF No") ?: "",
            address = doc.getString("address") ?: "",
            packageId = doc.getString("packageId") ?: doc.getString("package") ?: ""
        )
    }

    // ── Distinct fetch helpers (used by ReportActivity / DashboardActivity) ───

    suspend fun getPaidCustomers(): List<CustomerModel> = repository.getPaidCustomers()

    suspend fun getUnpaidCustomers(): List<CustomerModel> = repository.getUnpaidCustomers()

    suspend fun getUnpaidCustomersForPdf(): List<CustomerModel> = repository.getUnpaidCustomersForPdf()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        detachSnapshotListener()  // prevent memory leak when Activity is destroyed
    }
}
