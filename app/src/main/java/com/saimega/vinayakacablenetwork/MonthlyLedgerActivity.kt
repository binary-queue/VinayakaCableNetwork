package com.saimega.vinayakacablenetwork

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale

class MonthlyLedgerActivity : BaseActivity() {

    private val repository = CustomerRepository()
    private val currency = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
    private lateinit var container: LinearLayout
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_monthly_ledger)
        val customerId = intent.getStringExtra("CUSTOMER_ID").orEmpty()
        if (customerId.isBlank()) {
            finish()
            return
        }
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.monthly_ledger_title, intent.getStringExtra("CUSTOMER_NAME").orEmpty())
        toolbar.setNavigationOnClickListener { finish() }
        container = findViewById(R.id.ledgerContainer)
        tvEmpty = findViewById(R.id.tvLedgerEmpty)
        loadLedger(customerId)
    }

    private fun loadLedger(customerId: String) {
        lifecycleScope.launch {
            try {
                val entries = repository.fetchMonthlyLedger(customerId)
                tvEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
                entries.forEach(::addLedgerEntry)
            } catch (e: Exception) {
                Toast.makeText(this@MonthlyLedgerActivity, getString(R.string.error_prefix, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun addLedgerEntry(entry: MonthlyLedgerEntry) {
        val card = MaterialCardView(this).apply {
            radius = dp(8).toFloat()
            cardElevation = dp(1).toFloat()
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val monthLabel = runCatching {
            val date = SimpleDateFormat("yyyy-MM", Locale.US).parse(entry.monthKey)
            if (date == null) entry.monthKey else SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(date)
        }.getOrDefault(entry.monthKey)
        val title = TextView(this).apply {
            text = if (entry.isRevised) getString(R.string.ledger_revised_month, monthLabel) else monthLabel
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        content.addView(title)

        val columns = listOf(
            getString(R.string.ledger_bill) to entry.bill,
            getString(R.string.ledger_extra) to entry.extraCharges,
            getString(R.string.ledger_previous) to entry.previousOutstanding,
            getString(R.string.ledger_paid) to entry.paid,
            getString(R.string.ledger_balance) to entry.balance
        )
        val horizontal = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        columns.forEach { (label, amount) ->
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(dp(88), -2)
            }
            column.addView(TextView(this).apply {
                text = label
                textSize = 11f
                setTextColor(getColor(com.saimega.vinayakacablenetwork.R.color.dashboard_text_secondary))
            })
            column.addView(TextView(this).apply {
                text = currency.format(amount)
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.START
                setPadding(0, dp(4), 0, 0)
            })
            row.addView(column)
        }
        horizontal.addView(row)
        content.addView(horizontal)
        if (entry.isRevised && entry.revisionReason.isNotBlank()) {
            content.addView(TextView(this).apply {
                text = getString(R.string.ledger_revision_reason, entry.revisionReason)
                textSize = 12f
                setPadding(0, dp(8), 0, 0)
            })
        }
        card.addView(content)
        container.addView(card)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}