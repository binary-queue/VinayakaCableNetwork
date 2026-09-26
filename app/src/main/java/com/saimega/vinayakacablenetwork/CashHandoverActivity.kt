package com.saimega.vinayakacablenetwork

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CashHandoverActivity : BaseActivity() {

    private val repository = CashHandoverRepository()
    private val currency = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
    private lateinit var container: LinearLayout
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val role = getSharedPreferences("vinayaka_prefs", MODE_PRIVATE)
            .getString("user_role", Roles.EMPLOYEE) ?: Roles.EMPLOYEE
        if (role != Roles.ADMIN) {
            Toast.makeText(this, R.string.no_permission_message, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setContentView(R.layout.activity_cash_handover)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        container = findViewById(R.id.handoverContainer)
        tvEmpty = findViewById(R.id.tvHandoverEmpty)
        loadHandovers()
    }

    private fun loadHandovers() {
        lifecycleScope.launch {
            try {
                val handovers = repository.pendingHandovers()
                container.removeAllViews()
                if (handovers.isEmpty()) {
                    tvEmpty.visibility = View.VISIBLE
                    container.addView(tvEmpty)
                } else {
                    tvEmpty.visibility = View.GONE
                    handovers.forEach(::addHandoverRow)
                }
            } catch (e: Exception) {
                Toast.makeText(this@CashHandoverActivity, getString(R.string.error_prefix, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun addHandoverRow(handover: CashHandover) {
        val card = MaterialCardView(this).apply {
            radius = dp(8).toFloat()
            cardElevation = dp(1).toFloat()
            setContentPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val submittedAt = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault())
            .format(Date(handover.submittedAt))
        content.addView(label(getString(R.string.handover_collector_format, handover.collectorUsername), 16f, true))
        content.addView(label(submittedAt, 13f, false))
        content.addView(label(getString(
            R.string.handover_totals_format,
            currency.format(handover.cashAmount),
            currency.format(handover.digitalAmount),
            currency.format(handover.totalAmount),
            handover.paymentCount
        ), 14f, true))
        val review = MaterialButton(this).apply {
            text = getString(R.string.review_handover)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
            setOnClickListener { reviewHandover(handover) }
        }
        content.addView(review)
        card.addView(content)
        container.addView(card)
    }

    private fun reviewHandover(handover: CashHandover) {
        lifecycleScope.launch {
            try {
                val payments = repository.paymentsFor(handover)
                val details = payments.joinToString("\n") { payment ->
                    "${payment.name} · ${payment.receiptNumber} · ${currency.format(payment.paid)} · ${payment.paymentMode}"
                }
                AlertDialog.Builder(this@CashHandoverActivity)
                    .setTitle(R.string.review_handover)
                    .setMessage(getString(
                        R.string.handover_review_message,
                        handover.collectorUsername,
                        handover.paymentCount,
                        currency.format(handover.cashAmount),
                        currency.format(handover.digitalAmount),
                        currency.format(handover.totalAmount),
                        details
                    ))
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.verify_cash_received) { _, _ ->
                        lifecycleScope.launch {
                            val username = getSharedPreferences("vinayaka_prefs", MODE_PRIVATE)
                                .getString("username", "") ?: ""
                            if (repository.verify(handover.id, username)) {
                                Toast.makeText(this@CashHandoverActivity, R.string.handover_verified, Toast.LENGTH_SHORT).show()
                                loadHandovers()
                            } else {
                                Toast.makeText(this@CashHandoverActivity, getString(R.string.error_prefix, "verification failed"), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    .show()
            } catch (e: Exception) {
                Toast.makeText(this@CashHandoverActivity, getString(R.string.error_prefix, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun label(text: String, size: Float, bold: Boolean) = TextView(this).apply {
        this.text = text
        textSize = size
        gravity = Gravity.START
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(3), 0, dp(3))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}