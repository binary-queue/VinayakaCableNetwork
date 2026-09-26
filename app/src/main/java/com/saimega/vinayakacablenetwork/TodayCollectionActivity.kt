package com.saimega.vinayakacablenetwork

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

class TodayCollectionActivity : BaseActivity() {

    private lateinit var tvTotalCollection: TextView
    private lateinit var tvPaymentCount: TextView
    private lateinit var tvCashAmount: TextView
    private lateinit var tvOnlineAmount: TextView
    private lateinit var tvHandoverNotice: TextView
    private lateinit var btnHandoverAction: MaterialButton
    private lateinit var rvTransactions: RecyclerView
    private lateinit var rvPendingCustomers: RecyclerView
    private lateinit var tvNoCollectibleCustomers: TextView
    private lateinit var adapter: PaymentHistoryAdapter
    private lateinit var customerAdapter: CustomerAdapter

    private val db = FirebaseFirestore.getInstance()
    private val handoverRepository = CashHandoverRepository()
    private val currencyFormatter = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
    private var role = Roles.EMPLOYEE
    private var username = ""
    private var pendingHandoverTotals = HandoverTotals(0.0, 0.0, 0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_today_collection)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        tvTotalCollection = findViewById(R.id.tvTotalCollection)
        tvPaymentCount = findViewById(R.id.tvPaymentCount)
        tvCashAmount = findViewById(R.id.tvCashAmount)
        tvOnlineAmount = findViewById(R.id.tvOnlineAmount)
        tvHandoverNotice = findViewById(R.id.tvHandoverNotice)
        btnHandoverAction = findViewById(R.id.btnHandoverAction)
        rvTransactions = findViewById(R.id.rvTransactions)
        rvPendingCustomers = findViewById(R.id.rvPendingCustomers)
        tvNoCollectibleCustomers = findViewById(R.id.tvNoCollectibleCustomers)

        val prefs = getSharedPreferences("vinayaka_prefs", MODE_PRIVATE)
        role = prefs.getString("user_role", Roles.EMPLOYEE) ?: Roles.EMPLOYEE
        username = prefs.getString("username", "") ?: ""
        btnHandoverAction.text = getString(
            if (role == Roles.ADMIN) R.string.review_cash_handovers else R.string.submit_collection_to_admin
        )
        if (role != Roles.ADMIN) btnHandoverAction.isEnabled = false
        btnHandoverAction.setOnClickListener {
            if (role == Roles.ADMIN) {
                startActivity(android.content.Intent(this, CashHandoverActivity::class.java))
            } else {
                confirmHandoverSubmission()
            }
        }

        rvTransactions.layoutManager = LinearLayoutManager(this)
        rvPendingCustomers.layoutManager = LinearLayoutManager(this)
        customerAdapter = CustomerAdapter { customer ->
            startActivity(android.content.Intent(this, CustomerDetailsActivity::class.java).apply {
                putExtra("seriesNumber", customer.id)
                putExtra("customerModel", customer)
            })
        }
        rvPendingCustomers.adapter = customerAdapter
        adapter = PaymentHistoryAdapter(emptyList(), canEdit = false) { _, _ ->
            // Open payment details if needed
        }
        rvTransactions.adapter = adapter

        refreshPendingHandoverNotice()
        fetchTodayCollection()
        fetchCollectibleCustomers()
    }

    override fun onResume() {
        super.onResume()
        fetchCollectibleCustomers()
        refreshPendingHandoverNotice()
    }

    private fun fetchCollectibleCustomers() {
        if (!::customerAdapter.isInitialized) return
        val monthKey = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
        lifecycleScope.launch {
            try {
                val customers = CustomerRepository().fetchCollectibleCustomers(monthKey)
                customerAdapter.submitFullList(customers)
                tvNoCollectibleCustomers.visibility = if (customers.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                rvPendingCustomers.visibility = if (customers.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            } catch (e: Exception) {
                Toast.makeText(this@TodayCollectionActivity, getString(R.string.error_prefix, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun fetchTodayCollection() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val todayStartMs = cal.timeInMillis

        db.collection("payments")
            .whereGreaterThanOrEqualTo("timestamp", todayStartMs)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, e ->
                if (e != null) return@addSnapshotListener

                val allPayments = snapshot?.toObjects(PaymentModel::class.java) ?: emptyList()
                val payments = if (role == Roles.ADMIN) allPayments else
                    allPayments.filter { it.collectorUsername.equals(username, ignoreCase = true) }
                pendingHandoverTotals = CashHandoverMath.totals(
                    payments.filter { it.handoverId.isBlank() }
                )
                if (role != Roles.ADMIN) {
                    btnHandoverAction.isEnabled = pendingHandoverTotals.paymentCount > 0
                }
                adapter.updateData(payments)

                var total = 0.0
                var cash = 0.0
                var online = 0.0

                for (p in payments) {
                    total += p.paid
                    if (p.paymentMode.equals("Cash", true)) {
                        cash += p.paid
                    } else {
                        online += p.paid
                    }
                }

                tvTotalCollection.text = currencyFormatter.format(total)
                tvPaymentCount.text = getString(R.string.payments_count_format, payments.size)
                tvCashAmount.text = currencyFormatter.format(cash)
                tvOnlineAmount.text = currencyFormatter.format(online)
            }
    }

    private fun confirmHandoverSubmission() {
        AlertDialog.Builder(this)
            .setTitle(R.string.submit_collection_to_admin)
            .setMessage(getString(
                R.string.handover_confirmation_message,
                currencyFormatter.format(pendingHandoverTotals.cashAmount),
                currencyFormatter.format(pendingHandoverTotals.digitalAmount),
                currencyFormatter.format(pendingHandoverTotals.totalAmount),
                pendingHandoverTotals.paymentCount
            ))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.submit_collection_to_admin) { _, _ ->
                btnHandoverAction.isEnabled = false
                lifecycleScope.launch {
                    when (val result = handoverRepository.submitToday(username)) {
                        is HandoverSubmissionResult.Submitted -> {
                            pendingHandoverTotals = HandoverTotals(0.0, 0.0, 0)
                            btnHandoverAction.isEnabled = false
                            val time = SimpleDateFormat("h:mm a", Locale.getDefault())
                                .format(Date(result.handover.submittedAt))
                            Toast.makeText(
                                this@TodayCollectionActivity,
                                getString(R.string.handover_submitted_format, result.handover.paymentCount, time),
                                Toast.LENGTH_LONG
                            ).show()
                            tvHandoverNotice.visibility = android.view.View.VISIBLE
                            tvHandoverNotice.setText(R.string.handover_pending_verification)
                        }
                        HandoverSubmissionResult.NothingToSubmit -> {
                            pendingHandoverTotals = HandoverTotals(0.0, 0.0, 0)
                            btnHandoverAction.isEnabled = false
                            Toast.makeText(this@TodayCollectionActivity, R.string.nothing_to_submit, Toast.LENGTH_LONG).show()
                        }
                        is HandoverSubmissionResult.Failure -> {
                            btnHandoverAction.isEnabled = pendingHandoverTotals.paymentCount > 0
                            Toast.makeText(this@TodayCollectionActivity, getString(R.string.error_prefix, result.exception.message), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .show()
    }

    private fun refreshPendingHandoverNotice() {
        if (role == Roles.ADMIN || username.isBlank()) return
        lifecycleScope.launch {
            val hasPending = runCatching {
                handoverRepository.pendingHandovers().any {
                    it.collectorUsername.equals(username, ignoreCase = true)
                }
            }.getOrDefault(false)
            tvHandoverNotice.visibility = if (hasPending) android.view.View.VISIBLE else android.view.View.GONE
            if (hasPending) tvHandoverNotice.setText(R.string.handover_pending_verification)
        }
    }
}
