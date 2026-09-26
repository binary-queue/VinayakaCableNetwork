package com.saimega.vinayakacablenetwork

import android.content.Intent
import android.content.ClipData
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

/**
 * ReportActivity
 *
 * Loads the FULL list of payments for a date range (no pagination).
 * Up to 50–100 records per day are fetched once, summarised at the
 * top of the screen, and bound to a RecyclerView.  RecyclerView's
 * view-recycling handles smooth scrolling automatically.
 *
 * Query strategy: timestamp-range filter on the "payments" collection,
 * paid > 0 guard, ordered latest-first.
 *
 * Export: PDF (6-column A4 table) and CSV share via FileProvider.
 */
class ReportActivity : BaseActivity() {

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var tvReportTitle : TextView
    private lateinit var tvTotal       : TextView
    private lateinit var tvCount       : TextView
    private lateinit var tvOutstanding : TextView
    private lateinit var tvCollectorReportTitle: TextView
    private lateinit var collectorReportContainer: android.widget.LinearLayout
    private lateinit var modeBreakdownContainer: android.widget.LinearLayout
    private lateinit var tvEmpty       : TextView
    private lateinit var progressBar   : ProgressBar
    private lateinit var recyclerReport: RecyclerView
    private lateinit var btnMonthly    : Button
    private lateinit var btnToday      : Button
    private lateinit var btnYesterday  : Button
    private lateinit var btnStartDate  : Button
    private lateinit var btnEndDate    : Button
    private lateinit var btnExcel      : Button
    private lateinit var btnPdf        : Button

    // ── State ─────────────────────────────────────────────────────────────────
    private val repository  = CustomerRepository()
    private val sdf         = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private var startDateStr = sdf.format(Date())
    private var endDateStr   = sdf.format(Date())
    private var mode         = "day"               // "day" | "month"
    private var reportMonth  = Calendar.getInstance().get(Calendar.MONTH) + 1
    private var reportYear   = Calendar.getInstance().get(Calendar.YEAR)
    private var isAdmin = true
    private var outstandingBalance = 0.0
    private var businessSettings = BusinessSettings()

    // Full loaded list — used by both RecyclerView and exports
    private val paymentList = mutableListOf<PaymentModel>()

    // Indian Rupee formatter: formats 5000 → "5,000" (en_IN comma grouping)
    private val inrFormat = NumberFormat.getNumberInstance(Locale("en", "IN"))

    // Eagerly created adapter — attached before any fetch to silence
    // the "No adapter attached; skipping layout" RecyclerView warning.
    private lateinit var reportAdapter: ReportAdapter

    // Summary totals — computed once after fetch, reused in exports.
    // modeTotals maps each distinct paymentMode string (e.g. "Cash", "PhonePe")
    // to its collected total, so the breakdown covers every mode actually used
    // rather than a hardcoded cash-vs-everything-else split.
    private var grandTotal = 0.0
    private var modeTotals = linkedMapOf<String, Double>()

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report)
        bindViews()
        restrictNonAdminToCurrentMonth()
        setupButtons()
        loadReport()
    }

    /**
     * Non-admin roles (collectors/technicians) may only ever see the current
     * month's report — the month/date-range pickers that would let them
     * navigate elsewhere are hidden entirely rather than shown disabled.
     */
    private fun restrictNonAdminToCurrentMonth() {
        val role = getSharedPreferences("vinayaka_prefs", MODE_PRIVATE)
            .getString("user_role", Roles.ADMIN) ?: Roles.ADMIN
        isAdmin = role == Roles.ADMIN
        if (role == Roles.ADMIN) return

        findViewById<View>(R.id.rowMonthlyFilter).visibility = View.GONE
        findViewById<View>(R.id.rowDateRangeFilter).visibility = View.GONE

        mode = "month"
        reportMonth = Calendar.getInstance().get(Calendar.MONTH) + 1
        reportYear = Calendar.getInstance().get(Calendar.YEAR)
    }

    // ── View binding ──────────────────────────────────────────────────────────
    private fun bindViews() {
        tvReportTitle  = findViewById(R.id.tvReportTitle)
        tvTotal        = findViewById(R.id.tvTotal)
        tvCount        = findViewById(R.id.tvCount)
        tvOutstanding  = findViewById(R.id.tvOutstanding)
        tvCollectorReportTitle = findViewById(R.id.tvCollectorReportTitle)
        collectorReportContainer = findViewById(R.id.collectorReportContainer)
        modeBreakdownContainer = findViewById(R.id.modeBreakdownContainer)
        tvEmpty        = findViewById(R.id.tvEmpty)
        progressBar    = findViewById(R.id.progressBarReport)
        recyclerReport = findViewById(R.id.recyclerReport)
        btnMonthly     = findViewById(R.id.btnMonthly)
        btnToday       = findViewById(R.id.btnToday)
        btnYesterday   = findViewById(R.id.btnYesterday)
        btnStartDate   = findViewById(R.id.btnStartDate)
        btnEndDate     = findViewById(R.id.btnEndDate)
        btnExcel       = findViewById(R.id.btnExcel)
        btnPdf         = findViewById(R.id.btnPdf)

        recyclerReport.layoutManager = LinearLayoutManager(this)

        // FIX: Attach an empty adapter immediately so the RecyclerView has
        // something to measure against during the first layout pass.  This
        // eliminates the "No adapter attached; skipping layout" Logcat warning.
        reportAdapter = ReportAdapter(emptyList())
        recyclerReport.adapter = reportAdapter

        updateButtonLabels()
    }

    // ── Buttons ───────────────────────────────────────────────────────────────
    private fun setupButtons() {
        btnMonthly.setOnClickListener   { showMonthYearPicker() }
        btnToday.setOnClickListener     { selectQuickDate(0) }
        btnYesterday.setOnClickListener { selectQuickDate(-1) }
        btnStartDate.setOnClickListener { mode = "day"; showDatePicker(isStart = true) }
        btnEndDate.setOnClickListener   { mode = "day"; showDatePicker(isStart = false) }

        btnExcel.setOnClickListener {
            if (paymentList.isEmpty()) toast(getString(R.string.no_data_export)) else exportCSV()
        }
        btnPdf.setOnClickListener {
            if (paymentList.isEmpty()) toast(getString(R.string.no_data_export)) else exportPDF()
        }
    }

    private fun selectQuickDate(dayOffset: Int) {
        val selected = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, dayOffset) }
        val date = sdf.format(selected.time)
        startDateStr = date
        endDateStr = date
        mode = "day"
        updateButtonLabels()
        loadReport()
    }

    // ── Date pickers ──────────────────────────────────────────────────────────
    private fun showDatePicker(isStart: Boolean) {
        val cal = Calendar.getInstance()
        runCatching { cal.time = sdf.parse(if (isStart) startDateStr else endDateStr) ?: Date() }

        android.app.DatePickerDialog(this, { _, y, m, d ->
            val picked = "%04d-%02d-%02d".format(y, m + 1, d)
            if (isStart) {
                startDateStr = picked
            } else {
                if (picked < startDateStr) { toast(getString(R.string.end_date_before_start_error)); return@DatePickerDialog }
                endDateStr = picked
            }
            updateButtonLabels()
            loadReport()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun showMonthYearPicker() {
        val months = java.text.DateFormatSymbols(Locale.getDefault()).months.filter { it.isNotEmpty() }
        val items = mutableListOf<String>()
        for (y in 2020..2030) for (mn in months) items.add("$mn $y")

        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.select_month_year_title))
            .setItems(items.toTypedArray()) { _, which ->
                reportYear  = 2020 + which / 12
                reportMonth = which % 12 + 1
                mode = "month"
                updateButtonLabels()
                loadReport()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateButtonLabels() {
        if (mode == "month") {
            val cal = Calendar.getInstance().also { it.set(Calendar.MONTH, reportMonth - 1); it.set(Calendar.YEAR, reportYear) }
            btnMonthly.text   = "Monthly: ${SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time)}"
            btnStartDate.text = getString(R.string.start_date)
            btnEndDate.text   = "End Date"
        } else {
            btnMonthly.text   = "Monthly Report"
            btnStartDate.text = getString(R.string.from_date, startDateStr)
            btnEndDate.text   = "To:   $endDateStr"
        }
    }

    // ── Data loading — FULL list, no pagination ───────────────────────────────
    private fun loadReport() {
        setLoading(true)

        MainScope().launch {
            try {
                val payments = when (mode) {
                    "month" -> repository.fetchPaymentsByMonth(reportMonth, reportYear)
                    else    -> repository.fetchPaymentsByDateRange(startDateStr, endDateStr)
                }
                val prefs = getSharedPreferences("vinayaka_prefs", MODE_PRIVATE)
                val role = prefs.getString("user_role", Roles.EMPLOYEE) ?: Roles.EMPLOYEE
                val username = prefs.getString("username", "") ?: ""
                val visiblePayments = if (role == Roles.ADMIN) {
                    payments
                } else {
                    payments.filter { it.collectorUsername.equals(username, ignoreCase = true) }
                }
                outstandingBalance = if (role == Roles.ADMIN) repository.fetchTotalOutstanding() else 0.0
                businessSettings = runCatching { BusinessSettingsRepository().load() }
                    .getOrDefault(BusinessSettings())

                // Compute summary totals
                grandTotal = 0.0
                modeTotals = linkedMapOf()
                paymentList.clear()

                for (p in visiblePayments) {
                    grandTotal += p.paid
                    val modeKey = p.paymentMode.ifBlank { "Other" }
                    modeTotals[modeKey] = (modeTotals[modeKey] ?: 0.0) + p.paid
                    paymentList.add(p)
                }

                renderUI()

            } catch (e: Exception) {
                toast(getString(R.string.query_error_prefix, e.message))
            } finally {
                setLoading(false)
            }
        }
    }

    private fun renderUI() {
        val label = if (mode == "month") {
            val cal = Calendar.getInstance().also { it.set(Calendar.MONTH, reportMonth - 1); it.set(Calendar.YEAR, reportYear) }
            "Monthly Report — ${SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time)}"
        } else {
            "Report: $startDateStr  →  $endDateStr"
        }

        tvReportTitle.text = label

        // Delegate all summary-card text updates to the dedicated helper so
        // string formatting (Indian Rupee locale, getString placeholders) is
        // kept in one place and easy to test / adjust.
        updateSummaryUI(
            totalAmount  = grandTotal,
            paymentCount = paymentList.size,
            modeTotals   = modeTotals
        )
        tvOutstanding.visibility = if (isAdmin) View.VISIBLE else View.GONE
        if (isAdmin) {
            tvOutstanding.text = getString(
                R.string.outstanding_total_format,
                inrFormat.format(outstandingBalance.toLong())
            )
        }
        renderCollectorBreakdown()

        tvEmpty.visibility        = if (paymentList.isEmpty()) View.VISIBLE else View.GONE
        recyclerReport.visibility = if (paymentList.isEmpty()) View.GONE   else View.VISIBLE

        // Reuse the already-attached adapter — swap the data in place
        // instead of creating a new adapter on every reload.
        reportAdapter.updateList(paymentList.toList())
    }

    private fun renderCollectorBreakdown() {
        collectorReportContainer.removeAllViews()
        tvCollectorReportTitle.visibility = if (isAdmin) View.VISIBLE else View.GONE
        if (!isAdmin) return

        val collectors = paymentList.groupBy { it.collectorUsername.ifBlank { "Unknown" } }
        for ((username, payments) in collectors.toSortedMap()) {
            val cash = payments.filter { it.paymentMode.equals("Cash", true) }.sumOf { it.paid }
            val digital = payments.filterNot { it.paymentMode.equals("Cash", true) }.sumOf { it.paid }
            collectorReportContainer.addView(TextView(this).apply {
                text = getString(
                    R.string.collector_summary_format,
                    username,
                    payments.size,
                    inrFormat.format(cash.toLong()),
                    inrFormat.format(digital.toLong()),
                    inrFormat.format((cash + digital).toLong())
                )
                textSize = 13f
                setPadding(0, dpToPx(6), 0, dpToPx(6))
            })
        }
    }

    /**
     * Updates the summary card: total, count, and one row per payment mode
     * actually present in this report's data (Cash, PhonePe, Google Pay,
     * Paytm, UPI, or any other value stored on a payment), sorted highest
     * amount first. A mode with zero payments in this period is simply
     * absent — no empty rows.
     *
     * Monetary values are formatted with the Indian numbering system
     * (en_IN locale) so ₹100000 renders as ₹1,00,000.
     */
    private fun updateSummaryUI(
        totalAmount : Double,
        paymentCount: Int,
        modeTotals  : Map<String, Double>
    ) {
        // inrFormat uses Locale("en", "IN") — defined at class level.
        val fmtTotal = inrFormat.format(totalAmount.toLong())

        tvTotal.text = getString(R.string.total_collected, fmtTotal)
        tvCount.text = getString(R.string.payments_count,  paymentCount)

        modeBreakdownContainer.removeAllViews()
        for ((modeName, amount) in modeTotals.entries.sortedByDescending { it.value }) {
            val row = TextView(this).apply {
                text = getString(R.string.mode_amount_format, modeName, inrFormat.format(amount.toLong()))
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@ReportActivity, R.color.cm_blue))
                setPadding(0, 0, 0, dpToPx(4))
            }
            modeBreakdownContainer.addView(row)
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun csvCell(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private fun setLoading(on: Boolean) {
        progressBar.visibility    = if (on) View.VISIBLE else View.GONE
        recyclerReport.visibility = if (on) View.GONE    else View.VISIBLE
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  CSV EXPORT
    // ═════════════════════════════════════════════════════════════════════════
    private fun exportCSV() {
        try {
            val dir  = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val tag  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(dir, "Report_${mode}_$tag.csv")

            file.printWriter().use { w ->
                // ── Header block ──────────────────────────────────────────────
                if (businessSettings.name.isNotBlank()) w.println(csvCell(businessSettings.name))
                listOf(businessSettings.phone, businessSettings.address)
                    .filter { it.isNotBlank() }
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(" · ")
                    ?.let(w::println)
                w.println("${tvReportTitle.text}")
                w.println("Total Collected: ₹${grandTotal.toLong()}")
                w.println(modeTotals.entries.joinToString(",") { (name, amount) -> "$name: ₹${amount.toLong()}" })
                w.println("Payments: ${paymentList.size}")
                w.println()

                val headers = listOf(
                    "Name", "Serial Number (VC No)", "Pending Amount", "LCO Share", "MSO Share",
                    "Package", "Receipt Number", "Date", "Month", "Mode", "PhonePe Number",
                    "Amount Paid", "Bill Amount", "Extra Charges", "Remarks"
                )
                w.println(headers.joinToString(",") { csvCell(it) })

                for (payment in paymentList) {
                    val lcoShare = String.format(Locale.US, "%.2f", payment.paid * 0.47)
                    val msoShare = String.format(Locale.US, "%.2f", payment.paid - payment.paid * 0.47)
                    val billAmount = if (payment.billAmount > 0.0) payment.billAmount else payment.baseAmount
                    val values = listOf(
                        payment.name,
                        payment.vcNumber.ifBlank { payment.customerId },
                        payment.remaining.toString(),
                        lcoShare,
                        msoShare,
                        payment.packageName,
                        payment.receiptNumber,
                        payment.date,
                        payment.date.take(7),
                        payment.paymentMode,
                        if (payment.paymentMode.equals("PhonePe", true)) payment.paymentNumber else "",
                        payment.paid.toString(),
                        billAmount.toString(),
                        payment.extraCharges.toString(),
                        payment.remarks
                    )
                    w.println(values.joinToString(",") { csvCell(it) })
                }
            }

            shareFile(file, "text/csv")
            toast(getString(R.string.csv_saved_in_downloads))

        } catch (e: Exception) {
            toast(getString(R.string.csv_error_prefix, e.message))
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  PDF EXPORT  —  A4 · 6-column corporate table
    // ═════════════════════════════════════════════════════════════════════════

    // ── Page constants ────────────────────────────────────────────────────────
    private val PW    = 595f; private val PH = 842f; private val MG = 36f
    private val TW    get() = PW - MG * 2          // usable table width = 523f

    // ── Column widths (% of TW, sum = 100 %) ─────────────────────────────────
    private val C_NAME   get() = TW * 0.26f        // 26 %
    private val C_SERIES get() = TW * 0.13f        // 13 %
    private val C_PAID   get() = TW * 0.13f        // 13 %
    private val C_STATUS get() = TW * 0.10f        // 10 %
    private val C_MODE   get() = TW * 0.16f        // 16 %
    private val C_NUM    get() = TW * 0.22f        // 22 %

    // ── Column X positions ────────────────────────────────────────────────────
    private val X0 get() = MG
    private val X1 get() = X0 + C_NAME
    private val X2 get() = X1 + C_SERIES
    private val X3 get() = X2 + C_PAID
    private val X4 get() = X3 + C_STATUS
    private val X5 get() = X4 + C_MODE
    private val XE get() = X0 + TW                // right edge

    // ── Row metrics ───────────────────────────────────────────────────────────
    private val ROW_H   = 76f
    private val HEAD_H  = 26f
    private val TITLE_H = 66f                     // space for 3-line header block

    // ── Colours ───────────────────────────────────────────────────────────────
    private val GREEN   = Color.parseColor("#2E7D32")
    private val STRIPE  = Color.parseColor("#F1F8E9")
    private val DIVIDER = Color.parseColor("#C8E6C9")
    private val DARK    = Color.parseColor("#212121")
    private val TOTBG   = Color.parseColor("#E8F5E9")

    private fun exportPDF() {
        val pdf  = PdfDocument()
        var pNum = 1

        // Mutable canvas reference updated on page breaks
        fun newPage(): Pair<PdfDocument.Page, android.graphics.Canvas> {
            val p = pdf.startPage(PdfDocument.PageInfo.Builder(PW.toInt(), PH.toInt(), pNum++).create())
            return p to p.canvas
        }

        var (page, cv) = newPage()
        val yState = floatArrayOf(0f)

        // ── Paints ────────────────────────────────────────────────────────────
        val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
        val pText = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = DARK; it.textSize = 9f }
        val pLine = Paint(Paint.ANTI_ALIAS_FLAG).also {
            it.style = Paint.Style.STROKE; it.strokeWidth = 0.7f; it.color = DIVIDER
        }

        // ── Title block (Page 1 only) ─────────────────────────────────────────
        fun drawTitleBlock() {
            pText.isFakeBoldText = true; pText.textSize = 14f; pText.color = DARK
            val line1 = businessSettings.name
            if (line1.isNotBlank()) {
                cv.drawText(line1, (PW - pText.measureText(line1)) / 2f, MG + 16f, pText)
            }

            pText.isFakeBoldText = false; pText.textSize = 9f; pText.color = Color.parseColor("#616161")
            val line2 = "Report Date: ${tvReportTitle.text}"
            cv.drawText(line2, (PW - pText.measureText(line2)) / 2f, MG + 30f, pText)

            pText.isFakeBoldText = true; pText.textSize = 11f; pText.color = GREEN
            val modeSummary = modeTotals.entries.joinToString("   |   ") { (name, amount) -> "$name: ₹${amount.toLong()}" }
            val line3 = "Total Collected: ₹${grandTotal.toLong()}   |   $modeSummary   |   Payments: ${paymentList.size}"
            cv.drawText(line3, (PW - pText.measureText(line3)) / 2f, MG + 48f, pText)
        }

        // ── Table header row ──────────────────────────────────────────────────
        fun drawTableHeader(yTop: Float) {
            pFill.color = GREEN
            cv.drawRect(X0, yTop, XE, yTop + HEAD_H, pFill)
            pText.color = Color.WHITE; pText.textSize = 8.5f; pText.isFakeBoldText = true
            val bl = yTop + HEAD_H / 2f - (pText.ascent() + pText.descent()) / 2f
            fun hc(label: String, x: Float, w: Float) {
                cv.drawText(label, x + (w - pText.measureText(label)) / 2f, bl, pText)
            }
            hc("Name",           X0, C_NAME)
            hc("Series No.",     X1, C_SERIES)
            hc("Paid ₹",         X2, C_PAID)
            hc("Balance ₹",      X3, C_STATUS)
            hc("Mode",           X4, C_MODE)
            hc("Receipt No.",    X5, C_NUM)
        }

        // ── Data row ──────────────────────────────────────────────────────────
        fun drawRow(p: PaymentModel, rowIdx: Int, yTop: Float) {
            if (rowIdx % 2 == 1) { pFill.color = STRIPE; cv.drawRect(X0, yTop, XE, yTop + ROW_H, pFill) }
            pLine.color = DIVIDER
            cv.drawLine(X0, yTop + ROW_H, XE, yTop + ROW_H, pLine)
            pText.color = DARK; pText.isFakeBoldText = false; pText.textSize = 8.5f
            val bl = yTop + 16f - (pText.ascent() + pText.descent()) / 2f
            val pad = 3f

            // Name — shrink font to fit
            var ns = 8.5f
            while (pText.measureText(p.name) > C_NAME - 2 * pad && ns > 5.5f) {
                ns -= 0.5f; pText.textSize = ns
            }
            cv.drawText(p.name, X0 + pad, bl, pText)
            pText.textSize = 8.5f

            fun cc(text: String, x: Float, w: Float) {
                val tw = pText.measureText(text)
                cv.drawText(text, (x + (w - tw) / 2f).coerceAtLeast(x + pad), bl, pText)
            }
            cc(p.customerId,                   X1, C_SERIES)
            cc("₹${p.paid.toLong()}",          X2, C_PAID)
            cc("₹${p.remaining.toLong()}",    X3, C_STATUS)
            cc(p.paymentMode,                  X4, C_MODE)
            cc(p.receiptNumber.ifEmpty { "—" }, X5, C_NUM)

            val billAmount = if (p.billAmount > 0.0) p.billAmount else p.baseAmount
            val lcoShare = p.paid * 0.47
            val detail = listOf(
                "Pending ₹${p.remaining}",
                "LCO ₹${String.format(Locale.US, "%.2f", lcoShare)}",
                "MSO ₹${String.format(Locale.US, "%.2f", p.paid - lcoShare)}",
                "Package ${p.packageName.ifBlank { "—" }}",
                "Date ${p.date}",
                "Month ${p.date.take(7)}",
                "PhonePe ${if (p.paymentMode.equals("PhonePe", true)) p.paymentNumber else "—"}",
                "Bill ₹$billAmount",
                "Extra ₹${p.extraCharges}",
                "Remarks ${p.remarks.ifBlank { "—" }}"
            ).joinToString("  |  ")
            pText.color = DARK
            pText.textSize = 6.5f
            val maxWidth = TW - pad * 2
            var line = ""
            var lineNumber = 0
            for (word in detail.split(Regex("\\s+"))) {
                val next = if (line.isEmpty()) word else "$line $word"
                if (pText.measureText(next) > maxWidth && line.isNotEmpty()) {
                    cv.drawText(line, X0 + pad, yTop + 34f + lineNumber * 10f, pText)
                    lineNumber++
                    line = word
                } else {
                    line = next
                }
            }
            if (line.isNotEmpty() && lineNumber < 4) {
                cv.drawText(line, X0 + pad, yTop + 34f + lineNumber * 10f, pText)
            }
        }

        // ── Grand total footer ────────────────────────────────────────────────
        fun drawTotalsRow(yTop: Float) {
            pFill.color = TOTBG; cv.drawRect(X0, yTop, XE, yTop + 30f, pFill)
            pLine.color = GREEN; cv.drawRect(X0, yTop, XE, yTop + 30f, pLine)
            pText.isFakeBoldText = true; pText.textSize = 9f; pText.color = GREEN
            val bl = yTop + 15f - (pText.ascent() + pText.descent()) / 2f
            cv.drawText("Total Payments: ${paymentList.size}", X0 + 5f, bl, pText)
            val ts = "Grand Total: ₹${grandTotal.toLong()}"
            cv.drawText(ts, XE - pText.measureText(ts) - 5f, bl, pText)
        }

        // ── Render ────────────────────────────────────────────────────────────
        drawTitleBlock()
        drawTableHeader(MG + TITLE_H)
        yState[0] = MG + TITLE_H + HEAD_H

        val FOOTER_RESERVE = MG + 45f

        for ((idx, p) in paymentList.withIndex()) {
            if (yState[0] + ROW_H + FOOTER_RESERVE > PH - MG) {
                // close table on current page
                pLine.color = GREEN; cv.drawLine(X0, yState[0], XE, yState[0], pLine)
                pdf.finishPage(page)
                val n = newPage(); page = n.first; cv = n.second
                drawTableHeader(MG); yState[0] = MG + HEAD_H
            }
            drawRow(p, idx, yState[0])
            yState[0] += ROW_H
        }

        // Close bottom border
        pLine.color = GREEN; cv.drawLine(X0, yState[0], XE, yState[0], pLine)

        // Grand total block (new page if needed)
        if (yState[0] + 45f > PH - MG) {
            pdf.finishPage(page)
            val n = newPage(); page = n.first; cv = n.second; yState[0] = MG
        }
        drawTotalsRow(yState[0] + 6f)
        pdf.finishPage(page)

        // ── Save & share ──────────────────────────────────────────────────────
        try {
            val dir  = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val tag  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(dir, "Report_${mode}_$tag.pdf")
            pdf.writeTo(FileOutputStream(file)); pdf.close()
            shareFile(file, "application/pdf")
            toast(getString(R.string.pdf_saved_in_downloads))
        } catch (e: Exception) {
            pdf.close(); toast(getString(R.string.pdf_error_prefix, e.message))
        }
    }

    // ── Share helper ──────────────────────────────────────────────────────────
    private fun shareFile(file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(sendIntent, "Share via"))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}