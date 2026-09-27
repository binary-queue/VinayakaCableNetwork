package com.saimega.vinayakacablenetwork

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

class CustomerDetailsActivity : BaseActivity() {

    private lateinit var scrollView: NestedScrollView
    private lateinit var tvName: TextView
    private lateinit var tvSeries: TextView
    private lateinit var tvAvatarInitial: TextView
    private lateinit var chipStatus: Chip

    private lateinit var rowBaseAmount: View
    private lateinit var rowPendingBill: View
    private lateinit var rowExtraCharges: View
    private lateinit var rowTotal: View
    private lateinit var rowRemaining: View
    private lateinit var rowPaidFully: View
    private lateinit var rowChange: View

    private lateinit var tvBaseAmount: TextView
    private lateinit var tvPendingAmount: TextView
    private lateinit var tvExtraChargesDisplay: TextView
    private lateinit var tvTotalAmount: TextView
    private lateinit var tvRemainingAmount: TextView
    private lateinit var tvPaidFullyAmount: TextView
    private lateinit var tvChangeAmount: TextView

    private lateinit var etManualAmount: EditText
    private lateinit var etAmountPaid: EditText
    private lateinit var spPaymentMode: AutoCompleteTextView
    private lateinit var etPaymentNumber: EditText
    private lateinit var etRemarks: EditText
    private lateinit var tilPaymentNumber: View
    private lateinit var switchSmsReceipt: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var tvUpiLabel: TextView

    private lateinit var btnSubmit: Button
    private lateinit var btnPayFull: com.google.android.material.button.MaterialButton
    private lateinit var btnPayBillOnly: com.google.android.material.button.MaterialButton
    private lateinit var btnPayHalf: com.google.android.material.button.MaterialButton
    private lateinit var btnViewReceipt: Button
    private lateinit var btnDownloadInvoice: Button
    private lateinit var btnShareWhatsapp: Button
    private lateinit var btnViewHistory: Button
    private lateinit var btnViewLedger: Button
    private lateinit var btnCallCustomer: com.google.android.material.button.MaterialButton
    private lateinit var btnCustomerWhatsapp: com.google.android.material.button.MaterialButton

    private lateinit var rowEditDelete: View
    private lateinit var btnEditCustomer: Button
    private lateinit var btnDeleteCustomer: Button
    private lateinit var btnToggleConnection: com.google.android.material.button.MaterialButton
    private lateinit var btnReviseBill: com.google.android.material.button.MaterialButton

    private var currentCustomer: CustomerModel? = null
    private lateinit var viewedMonthKey: String
    private val repository = CustomerRepository()
    private val auditLogRepository = AuditLogRepository()
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_customer_details)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        bindViews()
        setupPaymentMode()
        setupListeners()
        setupEditDelete()
        applyImeInsetPadding()

        val series = intent.getStringExtra("seriesNumber") ?: ""
        viewedMonthKey = intent.getStringExtra("MONTH_KEY")
            ?: java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.getDefault()).format(java.util.Date())
        val passedCustomer = intent.getSerializableExtra("customerModel") as? CustomerModel

        if (passedCustomer != null) {
            currentCustomer = passedCustomer
            bindCustomerData()
        } else if (series.isNotEmpty()) {
            fetchCustomer(series)
        }
    }

    private fun bindViews() {
        scrollView = findViewById(R.id.scrollView)
        tvName = findViewById(R.id.tvName)
        tvSeries = findViewById(R.id.tvSeries)
        tvAvatarInitial = findViewById(R.id.tvAvatarInitial)
        chipStatus = findViewById(R.id.chipStatus)

        rowBaseAmount = findViewById(R.id.rowBaseAmount)
        rowPendingBill = findViewById(R.id.rowPendingBill)
        rowExtraCharges = findViewById(R.id.rowExtraCharges)
        rowTotal = findViewById(R.id.rowTotal)
        rowRemaining = findViewById(R.id.rowRemaining)
        rowPaidFully = findViewById(R.id.rowPaidFully)
        rowChange = findViewById(R.id.rowChange)

        tvBaseAmount = rowBaseAmount.findViewById(R.id.rowValue)
        tvPendingAmount = rowPendingBill.findViewById(R.id.rowValue)
        tvExtraChargesDisplay = rowExtraCharges.findViewById(R.id.rowValue)
        tvTotalAmount = rowTotal.findViewById(R.id.rowValue)
        tvRemainingAmount = rowRemaining.findViewById(R.id.rowValue)
        tvPaidFullyAmount = rowPaidFully.findViewById(R.id.rowValue)
        tvChangeAmount = rowChange.findViewById(R.id.rowValue)

        // Set Labels
        rowBaseAmount.findViewById<TextView>(R.id.rowLabel).text = getString(R.string.base_plan_label)
        rowPendingBill.findViewById<TextView>(R.id.rowLabel).text = getString(R.string.arrears_label)
        rowExtraCharges.findViewById<TextView>(R.id.rowLabel).text = getString(R.string.extra_charges_label)
        rowTotal.findViewById<TextView>(R.id.rowLabel).text = getString(R.string.total_payable_label)
        rowRemaining.findViewById<TextView>(R.id.rowLabel).text = getString(R.string.remaining_label)
        rowPaidFully.findViewById<TextView>(R.id.rowLabel).text = getString(R.string.status_label)
        rowChange.findViewById<TextView>(R.id.rowLabel).text = getString(R.string.return_change_label)

        etManualAmount = findViewById(R.id.etManualAmount)
        etAmountPaid = findViewById(R.id.etAmountPaid)
        spPaymentMode = findViewById(R.id.spPaymentMode)
        etPaymentNumber = findViewById(R.id.etPaymentNumber)
        etRemarks = findViewById(R.id.etRemarks)
        tilPaymentNumber = findViewById(R.id.tilPaymentNumber)
        switchSmsReceipt = findViewById(R.id.switchSmsReceipt)
        tvUpiLabel = findViewById(R.id.tvUpiLabel)

        btnSubmit = findViewById(R.id.btnSubmitPayment)
        btnPayFull = findViewById(R.id.btnPayFull)
        btnPayBillOnly = findViewById(R.id.btnPayBillOnly)
        btnPayHalf = findViewById(R.id.btnPayHalf)
        btnViewReceipt = findViewById(R.id.btnViewReceipt)
        btnDownloadInvoice = findViewById(R.id.btnDownloadInvoice)
        btnShareWhatsapp = findViewById(R.id.btnShareWhatsapp)
        btnViewHistory = findViewById(R.id.btnViewHistory)
        btnViewLedger = findViewById(R.id.btnViewLedger)
        btnCallCustomer = findViewById(R.id.btnCallCustomer)
        btnCustomerWhatsapp = findViewById(R.id.btnCustomerWhatsapp)

        rowEditDelete = findViewById(R.id.rowEditDelete)
        btnEditCustomer = findViewById(R.id.btnEditCustomer)
        btnDeleteCustomer = findViewById(R.id.btnDeleteCustomer)
        btnToggleConnection = findViewById(R.id.btnToggleConnection)
        btnReviseBill = findViewById(R.id.btnReviseBill)
    }

    private fun setupEditDelete() {
        val role = getSharedPreferences("vinayaka_prefs", MODE_PRIVATE).getString("user_role", Roles.EMPLOYEE) ?: Roles.EMPLOYEE
        rowEditDelete.visibility = if (role == Roles.ADMIN) View.VISIBLE else View.GONE
        btnToggleConnection.visibility = if (role == Roles.ADMIN) View.VISIBLE else View.GONE
        btnReviseBill.visibility = if (role == Roles.ADMIN) View.VISIBLE else View.GONE

        btnToggleConnection.setOnClickListener {
            val customer = currentCustomer ?: return@setOnClickListener
            val reconnect = !isActiveForViewedMonth(customer)
            val parsedMonth = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).parse(viewedMonthKey)
            val monthLabel = parsedMonth?.let {
                java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.getDefault()).format(it)
            } ?: viewedMonthKey
            AlertDialog.Builder(this)
                .setTitle(getString(if (reconnect) R.string.reconnect_connection else R.string.deactivate_connection))
                .setMessage(getString(
                    if (reconnect) R.string.reconnect_connection_confirmation else R.string.deactivate_connection_confirmation,
                    customer.name,
                    monthLabel
                ))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(if (reconnect) R.string.reconnect_connection else R.string.deactivate_connection) { _, _ ->
                    lifecycleScope.launch {
                        val updated = repository.setConnectionStatus(customer.id, reconnect, viewedMonthKey)
                        if (updated) {
                            Toast.makeText(this@CustomerDetailsActivity, R.string.connection_status_updated, Toast.LENGTH_SHORT).show()
                            fetchCustomer(customer.id)
                        } else {
                            Toast.makeText(this@CustomerDetailsActivity, getString(R.string.error_prefix, "connection update failed"), Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .show()
        }

            btnReviseBill.setOnClickListener {
                currentCustomer?.let(::showBillRevisionDialog)
            }

        btnEditCustomer.setOnClickListener {
            val customer = currentCustomer ?: return@setOnClickListener
            val intent = Intent(this, NewCustomerActivity::class.java).apply {
                putExtra("customerModel", customer)
            }
            startActivity(intent)
        }

        btnDeleteCustomer.setOnClickListener {
            val customer = currentCustomer ?: return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle(R.string.delete_customer_title)
                .setMessage(getString(R.string.delete_customer_message, customer.name))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete) { _, _ ->
                    lifecycleScope.launch {
                        val success = repository.deleteCustomer(customer.id)
                        if (success) {
                            val prefs = getSharedPreferences("vinayaka_prefs", MODE_PRIVATE)
                            auditLogRepository.logAction(
                                actorUsername = prefs.getString("username", "") ?: "",
                                actorRole = prefs.getString("user_role", Roles.EMPLOYEE) ?: Roles.EMPLOYEE,
                                action = AuditAction.DELETE_CUSTOMER,
                                targetType = AuditTargetType.CUSTOMER,
                                targetId = customer.id,
                                targetName = customer.name
                            )
                            Toast.makeText(this@CustomerDetailsActivity, getString(R.string.customer_deleted_successfully), Toast.LENGTH_SHORT).show()
                            finish()
                        } else {
                            Toast.makeText(this@CustomerDetailsActivity, getString(R.string.error_prefix, "unknown"), Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .show()
        }
    }

    private fun showBillRevisionDialog(customer: CustomerModel) {
        val now = java.util.Date()
        val monthKey = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.getDefault()).format(now)
        val monthLabel = java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.getDefault()).format(now)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 0)
        }
        val amountInput = EditText(this).apply {
            hint = getString(R.string.revised_bill_amount)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(customer.monthlyCharge.toString())
        }
        val reasonInput = EditText(this).apply {
            hint = getString(R.string.bill_revision_reason)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        content.addView(amountInput)
        content.addView(reasonInput)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.revise_bill_title, monthLabel))
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val revisedAmount = amountInput.text.toString().toDoubleOrNull()
                val reason = reasonInput.text.toString().trim()
                if (revisedAmount == null || revisedAmount < 0.0 || reason.isBlank()) {
                    Toast.makeText(this, R.string.bill_revision_reason_required, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    if (repository.reviseMonthlyBill(customer.id, monthKey, revisedAmount, reason)) {
                        Toast.makeText(this@CustomerDetailsActivity, R.string.bill_revised, Toast.LENGTH_SHORT).show()
                        fetchCustomer(customer.id)
                    } else {
                        Toast.makeText(this@CustomerDetailsActivity, getString(R.string.error_prefix, "bill revision failed"), Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    private fun setupListeners() {
        // Extra Charges also re-syncs Amount Paid to the new total — so the
        // field starts pre-filled with what's actually owed instead of
        // making staff retype a number already shown on screen, while
        // staying freely editable afterward for partial/extra payments.
        etManualAmount.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                syncAmountPaidToTotal()
                calculateFinalBill()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        etAmountPaid.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { calculateFinalBill() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        btnPayFull.setOnClickListener {
            currentCustomer?.let { customer ->
                setPaymentAmount(customer.pendingAmount + (etManualAmount.text.toString().toDoubleOrNull() ?: 0.0))
            }
        }
        btnPayHalf.setOnClickListener {
            currentCustomer?.let { customer ->
                setPaymentAmount((customer.pendingAmount + (etManualAmount.text.toString().toDoubleOrNull() ?: 0.0)) / 2.0)
            }
        }
        btnPayBillOnly.setOnClickListener {
            val customer = currentCustomer ?: return@setOnClickListener
            lifecycleScope.launch {
                val monthKey = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.getDefault()).format(java.util.Date())
                val currentLedger = repository.fetchMonthlyLedger(customer.id).firstOrNull { it.monthKey == monthKey }
                val billRemaining = if (currentLedger != null) {
                    (currentLedger.bill - currentLedger.paid).coerceAtLeast(0.0)
                } else {
                    customer.monthlyCharge.takeIf { it > 0.0 } ?: customer.baseAmount
                }
                setPaymentAmount(billRemaining + (etManualAmount.text.toString().toDoubleOrNull() ?: 0.0))
            }
        }

        btnSubmit.setOnClickListener {
            handlePayment()
        }

        btnViewReceipt.setOnClickListener {
            val intent = Intent(this, ReceiptActivity::class.java)
            intent.putExtra("CUSTOMER_ID", currentCustomer?.id)
            startActivity(intent)
        }

        btnDownloadInvoice.setOnClickListener {
            val intent = Intent(this, ReceiptActivity::class.java)
            intent.putExtra("CUSTOMER_ID", currentCustomer?.id)
            intent.putExtra("ACTION", "DOWNLOAD")
            startActivity(intent)
        }

        btnShareWhatsapp.setOnClickListener {
            val intent = Intent(this, ReceiptActivity::class.java)
            intent.putExtra("CUSTOMER_ID", currentCustomer?.id)
            intent.putExtra("ACTION", "WHATSAPP")
            startActivity(intent)
        }

        btnViewHistory.setOnClickListener {
            val intent = Intent(this, CustomerPaymentHistoryActivity::class.java)
            intent.putExtra("CUSTOMER_ID", currentCustomer?.id)
            startActivity(intent)
        }
        btnViewLedger.setOnClickListener {
            val customer = currentCustomer ?: return@setOnClickListener
            startActivity(Intent(this, MonthlyLedgerActivity::class.java).apply {
                putExtra("CUSTOMER_ID", customer.id)
                putExtra("CUSTOMER_NAME", customer.name)
            })
        }
        btnCallCustomer.setOnClickListener {
            val phone = currentCustomer?.phone.orEmpty()
            if (phone.isBlank()) {
                Toast.makeText(this, R.string.customer_phone_missing, Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
            }
        }
        btnCustomerWhatsapp.setOnClickListener {
            val customer = currentCustomer ?: return@setOnClickListener
            val digits = customer.phone.filter(Char::isDigit)
            if (digits.isBlank()) {
                Toast.makeText(this, R.string.customer_phone_missing, Toast.LENGTH_SHORT).show()
            } else {
                val whatsappPhone = if (digits.length == 10) "91$digits" else digits
                val message = getString(
                    R.string.customer_whatsapp_greeting,
                    customer.displayName(LocaleHelper.getLanguage(this))
                )
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$whatsappPhone?text=${Uri.encode(message)}")))
            }
        }
    }

    private fun fetchCustomer(series: String) {
        db.collection("customers").document(series).get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    currentCustomer = doc.toObject(CustomerModel::class.java)?.copy(
                        id = doc.id,
                        teluguName = doc.getString("telugu name") ?: doc.getString("teluguName") ?: "",
                        connectionStatus = doc.getString("Connection Status") ?: "active",
                        deactivatedMonth = doc.getString("deactivatedMonth"),
                        reconnectedMonth = doc.getString("reconnectedMonth"),
                        lastBilledMonth = doc.getString("lastBilledMonth") ?: "",
                        vcNumber = doc.getString("vcNumber") ?: doc.getString("VC No") ?: "",
                        boxNumber = doc.getString("boxNumber")
                            ?: (doc.get(com.google.firebase.firestore.FieldPath.of("STB/box No")) as? String)
                            ?: "",
                        crfNumber = doc.getString("crfNumber") ?: doc.getString("CRF No") ?: "",
                        address = doc.getString("address") ?: "",
                        packageId = doc.getString("packageId") ?: doc.getString("package") ?: ""
                    )
                    bindCustomerData()
                }
            }
    }

    private fun bindCustomerData() {
        val c = currentCustomer ?: return
        btnToggleConnection.text = getString(
            if (isActiveForViewedMonth(c)) R.string.deactivate_connection else R.string.reconnect_connection
        )
        val displayName = c.displayName(LocaleHelper.getLanguage(this))
        tvName.text = displayName
        tvSeries.text = getString(R.string.customer_id_format, c.id)
        tvAvatarInitial.text = displayName.firstOrNull()?.toString()?.uppercase() ?: "?"
        
        chipStatus.text = c.connectionStatus.uppercase()
        if (c.connectionStatus.equals("active", true)) {
            chipStatus.setChipBackgroundColorResource(android.R.color.holo_green_dark)
        } else {
            chipStatus.setChipBackgroundColorResource(android.R.color.holo_red_dark)
        }

        tvBaseAmount.text = "₹${"%.2f".format(c.baseAmount)}"
        tvPendingAmount.text = "₹${"%.2f".format(c.pendingAmount)}"
        
        if (c.status.equals("paid", true)) {
            findViewById<View>(R.id.paymentFormArea).visibility = View.GONE
            btnViewReceipt.visibility = View.VISIBLE
            btnDownloadInvoice.visibility = View.VISIBLE
            btnShareWhatsapp.visibility = View.VISIBLE
        } else {
            findViewById<View>(R.id.paymentFormArea).visibility = View.VISIBLE
            btnViewReceipt.visibility = View.GONE
            btnDownloadInvoice.visibility = View.GONE
            btnShareWhatsapp.visibility = View.GONE
        }

        // Pre-fill Amount Paid with what's actually owed (base + arrears),
        // so the field isn't blank when a genuinely calculated value is
        // already known — staff can still edit it for a partial payment.
        if (!c.status.equals("paid", true)) {
            syncAmountPaidToTotal()
        }

        calculateFinalBill()
    }

    private fun isActiveForViewedMonth(customer: CustomerModel): Boolean = BillingCycle.isActiveForMonth(
        CustomerBillingState(
            id = customer.id,
            connectionStatus = customer.connectionStatus,
            pendingAmount = customer.pendingAmount,
            monthlyCharge = customer.monthlyCharge,
            lastBilledMonth = customer.lastBilledMonth,
            deactivatedMonth = customer.deactivatedMonth,
            reconnectedMonth = customer.reconnectedMonth
        ),
        viewedMonthKey
    )

    /** Sets Amount Paid to Base + Arrears + Extra Charges (the current total due). */
    private fun syncAmountPaidToTotal() {
        val c = currentCustomer ?: return
        val extra = etManualAmount.text.toString().toDoubleOrNull() ?: 0.0
        // DC Rule: pendingAmount is already the full outstanding bill — don't re-add baseAmount.
        val total = c.pendingAmount + extra
        val text = if (total == total.toLong().toDouble()) total.toLong().toString() else total.toString()
        etAmountPaid.setText(text)
        etAmountPaid.setSelection(text.length)
    }

    private fun calculateFinalBill() {
        val c = currentCustomer ?: return
        val extra = etManualAmount.text.toString().toDoubleOrNull() ?: 0.0
        val paid = etAmountPaid.text.toString().toDoubleOrNull() ?: 0.0

        // DC Rule: pendingAmount is already the full outstanding bill — don't re-add baseAmount.
        val total = c.pendingAmount + extra
        val balance = total - paid

        tvExtraChargesDisplay.text = "₹${"%.2f".format(extra)}"
        tvTotalAmount.text = "₹${"%.2f".format(total)}"

        rowBaseAmount.visibility = View.VISIBLE
        rowPendingBill.visibility = if (c.pendingAmount > 0) View.VISIBLE else View.GONE
        rowExtraCharges.visibility = if (extra > 0) View.VISIBLE else View.GONE
        
        when {
            paid > 0 && balance == 0.0 -> {
                rowRemaining.visibility = View.GONE
                rowChange.visibility = View.GONE
                rowPaidFully.visibility = View.VISIBLE
                tvPaidFullyAmount.text = getString(R.string.paid_caps)
            }
            balance < 0 -> {
                rowRemaining.visibility = View.GONE
                rowPaidFully.visibility = View.GONE
                rowChange.visibility = View.VISIBLE
                tvChangeAmount.text = "₹${"%.2f".format(-balance)}"
            }
            else -> {
                rowPaidFully.visibility = View.GONE
                rowChange.visibility = View.GONE
                rowRemaining.visibility = View.VISIBLE
                tvRemainingAmount.text = "₹${"%.2f".format(balance)}"
            }
        }
    }

    private fun handlePayment() {
        val c = currentCustomer ?: return
        val paid = etAmountPaid.text.toString().toDoubleOrNull() ?: 0.0
        if (paid <= 0) {
            Toast.makeText(this, getString(R.string.enter_valid_amount), Toast.LENGTH_SHORT).show()
            return
        }

        btnSubmit.isEnabled = false
        repository.submitPayment(
            customer = c,
            amountPaid = paid,
            paymentMode = spPaymentMode.text.toString(),
            paymentNumber = etPaymentNumber.text.toString(),
            extraCharges = etManualAmount.text.toString().toDoubleOrNull() ?: 0.0,
            collectorUsername = getSharedPreferences("vinayaka_prefs", MODE_PRIVATE)
                .getString("username", "") ?: "",
            remarks = etRemarks.text?.toString().orEmpty(),
            smsRequested = switchSmsReceipt.isChecked,
            onSuccess = { paymentId ->
                Toast.makeText(this, getString(R.string.payment_successful), Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, ReceiptActivity::class.java).apply {
                    putExtra("CUSTOMER_ID", c.id)
                    putExtra("PAYMENT_ID", paymentId)
                })
                finish()
            },
            onFailure = { e ->
                Toast.makeText(this, getString(R.string.error_prefix, e.message), Toast.LENGTH_SHORT).show()
                btnSubmit.isEnabled = true
            }
        )
    }

    private fun setupPaymentMode() {
        val modes = listOf("Cash", "PhonePe", "UPI", "Card", "Cheque", "Other")
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, modes)
        spPaymentMode.setAdapter(adapter)
        spPaymentMode.setText("Cash", false)

        spPaymentMode.setOnItemClickListener { _, _, position, _ ->
            val selected = modes[position]
            val requiresPayerNumber = selected == "PhonePe" || selected == "UPI"
            tilPaymentNumber.visibility = if (requiresPayerNumber) View.VISIBLE else View.GONE
            if (!requiresPayerNumber) {
                etPaymentNumber.text.clear()
            }
        }
    }

    private fun setPaymentAmount(amount: Double) {
        val formatted = if (amount == amount.toLong().toDouble()) amount.toLong().toString()
        else String.format(java.util.Locale.US, "%.2f", amount)
        etAmountPaid.setText(formatted)
        etAmountPaid.setSelection(formatted.length)
    }

    /**
     * On API 35+ the platform enforces edge-to-edge and ignores
     * windowSoftInputMode="adjustResize", so the keyboard would otherwise
     * draw over the bottom of the scroll content instead of shrinking it.
     * Reserve the IME's height as scroll-view padding so there's always
     * room to scroll a focused field above the keyboard, and nudge the
     * currently focused field into view once the keyboard finishes opening.
     */
    private fun applyImeInsetPadding() {
        val rootView = findViewById<View>(android.R.id.content)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val imeBottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            val navBottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom
            scrollView.setPadding(
                scrollView.paddingLeft, scrollView.paddingTop, scrollView.paddingRight,
                maxOf(imeBottom, navBottom)
            )
            if (imeBottom > 0) {
                currentFocus?.let { focused -> scrollView.post { scrollToView(focused) } }
            }
            insets
        }

        val scrollToFocusListener = View.OnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                scrollView.post { scrollToView(view) }
            }
        }
        etAmountPaid.onFocusChangeListener = scrollToFocusListener
        etManualAmount.onFocusChangeListener = scrollToFocusListener
        etPaymentNumber.onFocusChangeListener = scrollToFocusListener
    }

    /** Scrolls [scrollView] so [view] sits just below its toolbar, walking up the view tree to find its true offset. */
    private fun scrollToView(view: View) {
        var offset = 0
        var current = view
        while (current !== scrollView && current.parent is View) {
            offset += current.top
            current = current.parent as View
        }
        scrollView.smoothScrollTo(0, (offset - 24).coerceAtLeast(0))
    }

}
