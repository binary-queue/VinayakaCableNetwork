package com.saimega.vinayakacablenetwork

import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class CustomerListActivity : BaseActivity() {

    // ── Views — names match the new Premium XML IDs ───────────────────────────
    private lateinit var rvCustomers: RecyclerView
    private lateinit var etSearch: TextInputEditText
    private lateinit var btnSearch: MaterialButton
    private lateinit var btnDownload: MaterialButton
    private lateinit var btnDownloadCsv: MaterialButton
    private lateinit var btnDownloadPdfBar: MaterialButton
    private lateinit var progressBar: CircularProgressIndicator
    private lateinit var layoutEmptyState: LinearLayout
    private lateinit var tvSelectedMonth: android.widget.TextView
    private lateinit var btnPreviousMonth: android.widget.ImageButton
    private lateinit var btnNextMonth: android.widget.ImageButton

    // ── ViewModel ─────────────────────────────────────────────────────────────
    private val viewModel: CustomerViewModel by viewModels()

    // ── Adapter ───────────────────────────────────────────────────────────────
    private val adapter by lazy {
        CustomerAdapter { customer ->
            val intent = Intent(this, CustomerDetailsActivity::class.java).apply {
                putExtra("seriesNumber", customer.id)
                putExtra("customerModel", customer)
                putExtra("MONTH_KEY", monthKey())
            }
            startActivity(intent)
        }
    }

    // ── Firestore (only used by generatePDF / generateCSV) ────────────────────
    private val db = FirebaseFirestore.getInstance()
    private lateinit var type: String
    private val selectedMonth = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    private val minimumBillingMonth = Calendar.getInstance().apply {
        set(2026, Calendar.APRIL, 1, 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }
    private val userRole by lazy {
        getSharedPreferences("vinayaka_prefs", MODE_PRIVATE).getString("user_role", Roles.EMPLOYEE)
            ?: Roles.EMPLOYEE
    }

    // ─────────────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_customer_list)

        // ── Bind views to new Premium XML IDs ─────────────────────────────────
        rvCustomers      = findViewById(R.id.rvCustomers)
        etSearch         = findViewById(R.id.etSearch)
        btnSearch        = findViewById(R.id.btnSearch)
        btnDownload      = findViewById(R.id.btnDownload)
        btnDownloadCsv   = findViewById(R.id.btnDownloadCsv)
        btnDownloadPdfBar = findViewById(R.id.btnDownloadPdfBar)
        progressBar      = findViewById(R.id.progressBar)
        layoutEmptyState = findViewById(R.id.layoutEmptyState)
        tvSelectedMonth = findViewById(R.id.tvSelectedMonth)
        btnPreviousMonth = findViewById(R.id.btnPreviousMonth)
        btnNextMonth = findViewById(R.id.btnNextMonth)

        // Toolbar back navigation
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        // ── Read filter from Intent (supports both FILTER_TYPE and legacy 'type') ─────
        val filterExtra = intent.getStringExtra("FILTER_TYPE")
            ?: intent.getStringExtra("type")
            ?: "ALL"
        type = filterExtra.lowercase()
        if (type !in listOf("paid", "unpaid", "partial", "active", "inactive", "all")) type = "all"
        if (userRole == Roles.ADMIN) {
            intent.getStringExtra("MONTH_KEY")?.let { requestedMonth ->
                runCatching {
                    val parsed = SimpleDateFormat("yyyy-MM", Locale.US).parse(requestedMonth)
                    if (parsed != null) selectedMonth.time = parsed
                }
            }
        }
        if (selectedMonth.before(minimumBillingMonth)) {
            selectedMonth.timeInMillis = minimumBillingMonth.timeInMillis
        }

        btnDownload.text = getString(R.string.download_type_pdf, type.replaceFirstChar { it.uppercase() })
        btnDownloadCsv.visibility = View.GONE

        val chipGroupStatus = findViewById<com.google.android.material.chip.ChipGroup>(R.id.chipGroupStatus)
        val chipAll    = findViewById<com.google.android.material.chip.Chip>(R.id.chipAll)
        val chipUnpaid = findViewById<com.google.android.material.chip.Chip>(R.id.chipUnpaid)
        val chipPaid   = findViewById<com.google.android.material.chip.Chip>(R.id.chipPaid)
        val chipPartial = findViewById<com.google.android.material.chip.Chip>(R.id.chipPartial)
        val chipActive = findViewById<com.google.android.material.chip.Chip>(R.id.chipActive)
        val chipInactive = findViewById<com.google.android.material.chip.Chip>(R.id.chipInactive)

        // ── Contextual chip visibility ───────────────────────────────────────
        // Arriving with a specific status (from a dashboard stat card) shows a
        // browsable list already scoped to that status — a single non-interactive
        // chip repeating that back added nothing, so the whole picker is hidden.
        // Only "all" gives the user a real choice between statuses, so only
        // there do the chips appear.
        chipGroupStatus.visibility = View.VISIBLE
        chipAll.visibility = View.VISIBLE
        chipUnpaid.visibility = View.VISIBLE
        chipPaid.visibility = View.VISIBLE
        chipPartial.visibility = View.VISIBLE
        chipActive.visibility = View.VISIBLE
        chipInactive.visibility = View.VISIBLE
        btnDownloadPdfBar.visibility = if (type == "all") View.GONE else View.VISIBLE
        when (type) {
            "paid" -> chipPaid.isChecked = true
            "unpaid" -> chipUnpaid.isChecked = true
            "partial" -> chipPartial.isChecked = true
            "active" -> chipActive.isChecked = true
            "inactive" -> chipInactive.isChecked = true
            else -> chipAll.isChecked = true
        }
        adapter.setStatusFilter(type)

        updateMonthLabel()
        btnPreviousMonth.setOnClickListener { changeMonth(-1) }
        btnNextMonth.setOnClickListener { changeMonth(1) }
        btnPreviousMonth.visibility = if (userRole == Roles.ADMIN) View.VISIBLE else View.INVISIBLE
        btnNextMonth.visibility = if (userRole == Roles.ADMIN) View.VISIBLE else View.INVISIBLE
        loadMonthStatuses()

        chipGroupStatus.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            val newType = when (checkedIds.first()) {
                R.id.chipPaid   -> "paid"
                R.id.chipUnpaid -> "unpaid"
                R.id.chipPartial -> "partial"
                R.id.chipActive -> "active"
                R.id.chipInactive -> "inactive"
                else            -> "all"
            }
            if (type != newType) {
                type = newType
                btnDownload.text = getString(R.string.download_type_pdf, type.replaceFirstChar { it.uppercase() })
                btnDownloadPdfBar.visibility = if (type == "all") View.GONE else View.VISIBLE
                updateResultVisibility(adapter.setStatusFilter(type))
            }
        }

        setupRecyclerView()
        setupSearch()
        observeViewModel()

        btnDownload.setOnClickListener {
            if (adapter.itemCount == 0) {
                Toast.makeText(this, getString(R.string.no_data_export), Toast.LENGTH_SHORT).show()
            } else {
                generatePDF()
            }
        }

        btnDownloadPdfBar.setOnClickListener {
            if (adapter.itemCount == 0) {
                Toast.makeText(this, getString(R.string.no_data_export), Toast.LENGTH_SHORT).show()
            } else {
                generatePDF()
            }
        }

        // Kick off the first data load + attach real-time listener
        viewModel.init()
    }

    private fun monthKey(): String = SimpleDateFormat("yyyy-MM", Locale.US).format(selectedMonth.time)

    private fun updateMonthLabel() {
        tvSelectedMonth.text = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(selectedMonth.time)
        btnNextMonth.isEnabled = monthKey() < SimpleDateFormat("yyyy-MM", Locale.US).format(Calendar.getInstance().time)
    }

    private fun changeMonth(offset: Int) {
        if (userRole != Roles.ADMIN) return
        val candidate = (selectedMonth.clone() as Calendar).apply { add(Calendar.MONTH, offset) }
        if (candidate.before(minimumBillingMonth)) return
        if (candidate.after(Calendar.getInstance())) return
        selectedMonth.timeInMillis = candidate.timeInMillis
        updateMonthLabel()
        loadMonthStatuses()
    }

    private fun loadMonthStatuses() {
        lifecycleScope.launch {
            try {
                val statuses = CustomerRepository().fetchCustomerMonthStatuses(monthKey())
                val count = adapter.setMonthFilter(monthKey(), statuses)
                updateResultVisibility(count)
            } catch (e: Exception) {
                Toast.makeText(this@CustomerListActivity, getString(R.string.error_prefix, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The snapshot listener keeps data current automatically.
        // refresh() is only called here as a safety net (e.g. after connectivity loss).
        // It is a no-op if the listener is already healthy.
    }

    // ── RecyclerView + pagination scroll listener ─────────────────────────────
    private fun setupRecyclerView() {
        val layoutManager = LinearLayoutManager(this)
        rvCustomers.layoutManager = layoutManager
        rvCustomers.adapter = adapter

        // Trigger next page when within 5 items of the bottom
        rvCustomers.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val totalItems  = layoutManager.itemCount
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                if (lastVisible >= totalItems - 5) {
                    viewModel.loadNextPageIfNeeded()
                }
            }
        })
    }

    // ── Search — TextInputEditText replaces the old SearchView ────────────────
    private fun setupSearch() {
        // Live filter as user types — zero Firestore calls
        etSearch.doAfterTextChanged { text ->
            updateResultVisibility(adapter.filter(text?.toString().orEmpty()))
        }

        // Optional: IME "Search" key does the same thing
        etSearch.setOnEditorActionListener { _, _, _ ->
            updateResultVisibility(adapter.filter(etSearch.text?.toString().orEmpty()))
            false
        }

        // Explicit search button tap
        btnSearch.setOnClickListener {
            updateResultVisibility(adapter.filter(etSearch.text?.toString().orEmpty()))
        }
    }

    /**
     * Shows the RecyclerView when there are results to display (whether from
     * the type filter alone or combined with a typed search query) and shows
     * the empty state only when the result count is actually zero.
     */
    private fun updateResultVisibility(resultCount: Int) {
        if (resultCount == 0) {
            rvCustomers.visibility      = View.GONE
            layoutEmptyState.visibility = View.VISIBLE
        } else {
            rvCustomers.visibility      = View.VISIBLE
            layoutEmptyState.visibility = View.GONE
        }
    }

    // ── LiveData observers ────────────────────────────────────────────────────
    private fun observeViewModel() {

        viewModel.customers.observe(this) { list ->
            val resultCount = adapter.submitFullList(list)
            updateResultVisibility(resultCount)
        }

        viewModel.isLoading.observe(this) { loading ->
            // progressBar is centered — show during any load
            progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        }

        viewModel.error.observe(this) { msg ->
            if (!msg.isNullOrBlank()) {
                Toast.makeText(this, getString(R.string.error_prefix, msg), Toast.LENGTH_LONG).show()
            }
        }
    }

    // Convenience for PDF/CSV — exports exactly what's currently displayed
    // (status filter + any active search query), not the unfiltered full list.
    private val list get() = adapter.currentList

    // ── PDF Export ────────────────────────────────────────────────────────────
    private fun generatePDF() {
        // ── UI feedback: show "Generating" immediately ──────────────────────
        Toast.makeText(this, getString(R.string.generating_pdf), Toast.LENGTH_SHORT).show()

        val dataList = list

        try {
            val pdf = PdfDocument()

            val pageWidth  = 595
            val pageHeight = 842
            val margin      = 40f
            val usableWidth = pageWidth - (margin * 2)

            val nameWidth   = usableWidth * 0.40f
            val seriesWidth = usableWidth * 0.25f
            val amountWidth = usableWidth * 0.20f
            val statusWidth = usableWidth * 0.15f

            val tableLeft  = margin
            val tableRight = margin + usableWidth

            val colNameX   = tableLeft + 5f
            val colSeriesX = tableLeft + nameWidth + (seriesWidth / 2f)
            val colAmountX = tableLeft + nameWidth + seriesWidth + (amountWidth / 2f)
            val colStatusX = tableLeft + nameWidth + seriesWidth + amountWidth + (statusWidth / 2f)

            val divSeries = tableLeft + nameWidth
            val divAmount = divSeries + seriesWidth
            val divStatus = divAmount + amountWidth

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 10f
                color    = Color.parseColor("#212121")
            }
            val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#2E7D32")
            }
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style       = Paint.Style.STROKE
                strokeWidth = 0.8f
                color       = Color.parseColor("#C8E6C9")
            }
            val centredPaint = Paint(paint).apply {
                textAlign = Paint.Align.CENTER
            }
            val stripePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#F1F8E9")
            }

            val currentMonthStr = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date())
            val displayHeader = if (type == "paid")
                "Current Month: $currentMonthStr"
            else
                "As of: ${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())}"

            var pageNumber = 1
            var y = 0f

            var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
            var page     = pdf.startPage(pageInfo)
            var canvas   = page.canvas

            fun drawHeader() {
                y = margin
                paint.textAlign      = Paint.Align.CENTER
                paint.textSize       = 14f
                paint.isFakeBoldText = true
                paint.color          = Color.parseColor("#212121")
                canvas.drawText(
                    "${type.uppercase()} Report — $displayHeader",
                    pageWidth / 2f, y + 14f, paint
                )
                y += 30f

                val headerH = 28f
                canvas.drawRect(tableLeft, y, tableRight, y + headerH, headerPaint)

                borderPaint.color = Color.WHITE
                canvas.drawLine(divSeries, y, divSeries, y + headerH, borderPaint)
                canvas.drawLine(divAmount, y, divAmount, y + headerH, borderPaint)
                canvas.drawLine(divStatus, y, divStatus, y + headerH, borderPaint)
                borderPaint.color = Color.parseColor("#C8E6C9")

                val headerBaseline = y + headerH / 2f - (paint.ascent() + paint.descent()) / 2f
                paint.textSize       = 10f
                paint.isFakeBoldText = true
                paint.color          = Color.WHITE
                paint.textAlign      = Paint.Align.LEFT
                canvas.drawText("Name", colNameX, headerBaseline, paint)

                centredPaint.textSize       = 10f
                centredPaint.isFakeBoldText = true
                centredPaint.color          = Color.WHITE
                canvas.drawText("Series", colSeriesX, headerBaseline, centredPaint)
                canvas.drawText(
                    if (type == "paid") "Paid Amount" else "Amount Due",
                    colAmountX, headerBaseline, centredPaint
                )
                canvas.drawText("Status", colStatusX, headerBaseline, centredPaint)

                y += headerH
                paint.isFakeBoldText = false
                paint.color          = Color.parseColor("#212121")
                paint.textSize       = 10f
                paint.textAlign      = Paint.Align.LEFT
            }

            drawHeader()

            var grandTotal = 0.0
            val rowHeight = 36f

            for ((index, c) in dataList.withIndex()) {
                if (y + rowHeight > pageHeight - margin - 40f) {
                    centredPaint.textSize       = 9f
                    centredPaint.isFakeBoldText = false
                    centredPaint.color          = Color.GRAY
                    canvas.drawText("Page $pageNumber", pageWidth / 2f, pageHeight - margin / 2f, centredPaint)

                    pdf.finishPage(page)
                    pageNumber++
                    pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                    page     = pdf.startPage(pageInfo)
                    canvas   = page.canvas
                    drawHeader()
                }

                if (index % 2 == 1) {
                    canvas.drawRect(tableLeft, y, tableRight, y + rowHeight, stripePaint)
                }

                canvas.drawRect(tableLeft, y, tableRight, y + rowHeight, borderPaint)
                canvas.drawLine(divSeries, y, divSeries, y + rowHeight, borderPaint)
                canvas.drawLine(divAmount, y, divAmount, y + rowHeight, borderPaint)
                canvas.drawLine(divStatus, y, divStatus, y + rowHeight, borderPaint)

                paint.textSize = 10f
                val baseline = y + rowHeight / 2f - (paint.ascent() + paint.descent()) / 2f

                paint.textAlign = Paint.Align.LEFT
                val maxNameW = nameWidth - 10f
                val nameWords = c.name.split(" ")
                var nameLine  = ""
                val nameLines = mutableListOf<String>()
                for (word in nameWords) {
                    val test = if (nameLine.isEmpty()) word else "$nameLine $word"
                    if (paint.measureText(test) <= maxNameW) {
                        nameLine = test
                    } else {
                        if (nameLine.isNotEmpty()) nameLines.add(nameLine)
                        nameLine = word
                    }
                }
                if (nameLine.isNotEmpty()) nameLines.add(nameLine)

                if (nameLines.size == 1) {
                    canvas.drawText(nameLines[0], colNameX, baseline, paint)
                } else {
                    val leading = paint.textSize + 2f
                    val top = y + rowHeight / 2f - leading / 2f - paint.ascent()
                    nameLines.take(2).forEachIndexed { i, l ->
                        canvas.drawText(l, colNameX, top + i * leading, paint)
                    }
                }

                centredPaint.textSize       = 10f
                centredPaint.isFakeBoldText = false
                centredPaint.color          = Color.parseColor("#212121")
                canvas.drawText(c.id, colSeriesX, baseline, centredPaint)

                // DC Rule: for paid list show plan charge; for unpaid show exact pendingAmount from Firestore
                // (pendingAmount is already the full outstanding bill — don't re-add baseAmount).
                val isPaidStatus = c.status.equals("paid", ignoreCase = true)
                val displayAmount = if (isPaidStatus) {
                    c.baseAmount
                } else {
                    c.pendingAmount
                }
                canvas.drawText("₹${displayAmount.toInt()}", colAmountX, baseline, centredPaint)
                grandTotal += displayAmount

                val isPaid = isPaidStatus
                centredPaint.color = if (isPaid)
                    Color.parseColor("#2E7D32") else Color.parseColor("#C62828")
                canvas.drawText(if (isPaid) "Paid" else "Unpaid", colStatusX, baseline, centredPaint)

                y += rowHeight
            }

            y += 10f
            val totalsH = 32f
            canvas.drawRect(tableLeft, y, tableRight, y + totalsH,
                Paint().apply { color = Color.parseColor("#E8F5E9") })
            borderPaint.color = Color.parseColor("#2E7D32")
            canvas.drawRect(tableLeft, y, tableRight, y + totalsH, borderPaint)

            val totBaseline = y + totalsH / 2f - (paint.ascent() + paint.descent()) / 2f
            paint.isFakeBoldText = true
            paint.color          = Color.parseColor("#2E7D32")
            paint.textAlign      = Paint.Align.LEFT
            canvas.drawText("Total Items: ${dataList.size}", colNameX, totBaseline, paint)

            centredPaint.isFakeBoldText = true
            centredPaint.color          = Color.parseColor("#2E7D32")
            canvas.drawText("Total: ₹${grandTotal.toInt()}", colAmountX, totBaseline, centredPaint)

            centredPaint.textSize       = 9f
            centredPaint.isFakeBoldText = false
            centredPaint.color          = Color.GRAY
            canvas.drawText("Page $pageNumber", pageWidth / 2f, pageHeight - margin / 2f, centredPaint)

            pdf.finishPage(page)

            val reportDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val file = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "${type}_Report_${reportDate}_${System.currentTimeMillis()}.pdf"
            )
            pdf.writeTo(FileOutputStream(file))
            pdf.close()

            Toast.makeText(this, getString(R.string.pdf_saved_successfully), Toast.LENGTH_LONG).show()

            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share PDF"))

        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_prefix, e.message), Toast.LENGTH_LONG).show()
        }
    }

    // ── CSV Export (unchanged logic, kept for completeness) ───────────────────
    @Suppress("unused")
    private fun generateCSV() {
        val dataList = list
        val currentMonthStr = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date())
        val displayHeader = "Current Month: $currentMonthStr"

        try {
            val reportDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val file = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "${type}_Report_${reportDate}_${System.currentTimeMillis()}.csv"
            )

            val writer = file.bufferedWriter()
            writer.write("${type.uppercase()} REPORT - $displayHeader")
            writer.newLine()
            writer.write("Name,Series Number,Amount,Status")
            writer.newLine()

            var grandTotal = 0.0
            for (c in dataList) {
                // DC Rule: always use pendingAmount — the authoritative Firestore value
                // (it already is the full outstanding bill — don't re-add baseAmount).
                val isPaidStatus = c.status.equals("paid", true)
                val amount = if (isPaidStatus) {
                    c.baseAmount
                } else {
                    c.pendingAmount
                }
                grandTotal += amount
                val statusStr   = if (isPaidStatus) "Paid" else "Unpaid"
                val escapedName = if (c.name.contains(",")) "\"${c.name}\"" else c.name
                writer.write("$escapedName,${c.id},${amount.toInt()},$statusStr")
                writer.newLine()
            }

            writer.write(",TOTAL,,₹${grandTotal.toInt()}")
            writer.newLine()
            writer.flush()
            writer.close()

            Toast.makeText(this, getString(R.string.csv_saved_in_downloads), Toast.LENGTH_LONG).show()

            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/comma-separated-values"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share CSV"))

        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.csv_error_prefix, e.message), Toast.LENGTH_LONG).show()
        }
    }
}