package com.saimega.vinayakacablenetwork

import android.content.Intent
import android.content.ClipData
import android.net.Uri
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.content.Context
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

class ReceiptActivity : BaseActivity() {

    private lateinit var tvReceiptBusinessName: TextView
    private lateinit var tvReceiptBusinessContact: TextView
    private lateinit var tvReceiptName: TextView
    private lateinit var tvReceiptSeries: TextView
    private lateinit var tvReceiptNumber: TextView
    private lateinit var tvReceiptDate: TextView
    private lateinit var tvReceiptBillLabel: TextView
    private lateinit var tvReceiptBaseAmount: TextView
    private lateinit var tvReceiptPreviousOutstanding: TextView
    private lateinit var tvReceiptExtraCharges: TextView
    private lateinit var tvReceiptAlreadyPaid: TextView
    private lateinit var tvReceiptTotalPayable: TextView
    private lateinit var tvReceiptAmount: TextView
    private lateinit var tvReceiptBalance: TextView
    private lateinit var tvReceiptCollector: TextView
    private lateinit var tvReceiptMode: TextView
    private lateinit var btnPrint: MaterialButton
    private lateinit var btnShare: MaterialButton
    private lateinit var btnBluetoothPrint: MaterialButton

    private val db = FirebaseFirestore.getInstance()
    private var businessSettings = BusinessSettings()
    private var customerId: String = ""
    private var latestPayment: PaymentModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receipt)

        customerId = intent.getStringExtra("CUSTOMER_ID") ?: run {
            Toast.makeText(this, getString(R.string.no_customer_id), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        bindViews()
        loadReceiptData()

        btnPrint.setOnClickListener { printReceipt() }
        btnShare.setOnClickListener { shareReceipt() }
        btnBluetoothPrint.setOnClickListener { printBluetoothReceipt() }
    }

    private fun bindViews() {
        tvReceiptBusinessName = findViewById(R.id.tvReceiptHeader)
        tvReceiptBusinessContact = findViewById(R.id.tvReceiptBusinessContact)
        tvReceiptName         = findViewById(R.id.tvReceiptName)
        tvReceiptSeries       = findViewById(R.id.tvReceiptSeries)
        tvReceiptNumber       = findViewById(R.id.tvReceiptNumber)
        tvReceiptDate         = findViewById(R.id.tvReceiptDate)
        tvReceiptBillLabel    = findViewById(R.id.tvReceiptBillLabel)
        tvReceiptBaseAmount   = findViewById(R.id.tvReceiptBaseAmount)
        tvReceiptPreviousOutstanding = findViewById(R.id.tvReceiptPreviousOutstanding)
        tvReceiptExtraCharges = findViewById(R.id.tvReceiptExtraCharges)
        tvReceiptAlreadyPaid  = findViewById(R.id.tvReceiptAlreadyPaid)
        tvReceiptTotalPayable = findViewById(R.id.tvReceiptTotalPayable)
        tvReceiptAmount       = findViewById(R.id.tvReceiptAmount)
        tvReceiptBalance      = findViewById(R.id.tvReceiptBalance)
        tvReceiptCollector    = findViewById(R.id.tvReceiptCollector)
        tvReceiptMode         = findViewById(R.id.tvReceiptMode)
        btnPrint              = findViewById(R.id.btnPrint)
        btnShare              = findViewById(R.id.btnShare)
        btnBluetoothPrint     = findViewById(R.id.btnBluetoothPrint)

        btnPrint.isEnabled = false
        btnShare.isEnabled = false
        btnBluetoothPrint.isEnabled = false
    }

    private fun loadReceiptData() {
        val paymentId = intent.getStringExtra("PAYMENT_ID")
        val action = intent.getStringExtra("ACTION")

        lifecycleScope.launch {
            try {
                businessSettings = runCatching { BusinessSettingsRepository().load() }
                    .getOrDefault(BusinessSettings())
                val doc = withContext(Dispatchers.IO) {
                    if (!paymentId.isNullOrEmpty()) {
                        val d = db.collection("payments").document(paymentId).get().await()
                        if (d.exists()) d else null
                    } else {
                        val snapshot = db.collection("payments")
                            .whereEqualTo("customerId", customerId)
                            .orderBy("timestamp", Query.Direction.DESCENDING)
                            .limit(1)
                            .get()
                            .await()
                        if (snapshot.isEmpty) null else snapshot.documents[0]
                    }
                }

                if (doc == null) {
                    Toast.makeText(
                        this@ReceiptActivity,
                        "No payment records found.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                latestPayment = PaymentModel(
                    paymentId     = doc.id,
                    customerId    = doc.getString("customerId") ?: customerId,
                    name          = doc.getString("name") ?: customerId,
                    teluguName    = doc.getString("teluguName") ?: "",
                    phone          = doc.getString("phone") ?: "",
                    vcNumber       = doc.getString("vcNumber") ?: "",
                    packageName    = doc.getString("packageName") ?: "",
                    baseAmount    = (doc.get("baseAmount") as? Number)?.toDouble() ?: 0.0,
                    billAmount    = (doc.get("billAmount") as? Number)?.toDouble() ?: 0.0,
                    previousOutstanding = (doc.get("previousOutstanding") as? Number)?.toDouble() ?: 0.0,
                    alreadyPaid = (doc.get("alreadyPaid") as? Number)?.toDouble() ?: 0.0,
                    extraCharges  = (doc.get("extraCharges") as? Number)?.toDouble() ?: 0.0,
                    total         = (doc.get("total") as? Number)?.toDouble() ?: 0.0,
                    paid          = (doc.get("paid") as? Number)?.toDouble() ?: 0.0,
                    remaining     = (doc.get("remaining") as? Number)?.toDouble() ?: 0.0,
                    paymentMode   = doc.getString("paymentMode") ?: "Cash",
                    paymentNumber = doc.getString("paymentNumber") ?: "",
                    remarks       = doc.getString("remarks") ?: "",
                    smsRequested  = doc.getBoolean("smsRequested") ?: false,
                    receiptNumber = doc.getString("receiptNumber") ?: "",
                    collectorUsername = doc.getString("collectorUsername") ?: "",
                    date          = doc.getString("date") ?: "N/A",
                    timestamp     = (doc.get("timestamp") as? Number)?.toLong() ?: 0L
                )

                populateUI()

                if (action == "PRINT") {
                    // Try PDF print automatically
                    printReceipt()
                } else if (action == "SHARE") {
                    shareReceipt()
                } else if (action == "DOWNLOAD") {
                    downloadReceipt()
                } else if (action == "WHATSAPP") {
                    shareToWhatsApp()
                }

            } catch (e: Exception) {
                Log.e("ReceiptActivity", "Failed to load payment: ${e.message}", e)
                Toast.makeText(
                    this@ReceiptActivity,
                    "Failed to load receipt: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun populateUI() {
        val p = latestPayment ?: return
        tvReceiptBusinessName.text = businessSettings.name
        tvReceiptBusinessContact.text = listOf(businessSettings.phone, businessSettings.address)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        tvReceiptName.text         = p.displayName(LocaleHelper.getLanguage(this))
        tvReceiptSeries.text       = customerId
        tvReceiptNumber.text       = p.receiptNumber.ifBlank { getString(R.string.dash) }
        tvReceiptDate.text         = p.date
        tvReceiptBillLabel.text = getString(R.string.monthly_bill_label)
        tvReceiptBaseAmount.text   = "₹ ${formatAmount(if (p.billAmount > 0.0) p.billAmount else p.baseAmount)}"
        tvReceiptPreviousOutstanding.text = "₹ ${formatAmount(p.previousOutstanding)}"
        tvReceiptExtraCharges.text = getString(R.string.rupee_value, formatAmount(p.extraCharges))
        tvReceiptAlreadyPaid.text = "₹ ${formatAmount(p.alreadyPaid)}"
        tvReceiptTotalPayable.text = "₹ ${formatAmount(p.total)}"
        tvReceiptAmount.text       = "₹ ${formatAmount(p.paid)}"
        tvReceiptBalance.text      = "₹ ${formatAmount(p.remaining.coerceAtLeast(0.0))}"
        tvReceiptCollector.text    = p.collectorUsername.ifBlank { getString(R.string.dash) }
        tvReceiptMode.text         = buildModeText(p.paymentMode, p.paymentNumber)

        btnPrint.isEnabled = true
        btnShare.isEnabled = true
        btnBluetoothPrint.isEnabled = true
    }

    private fun generateReceiptPdf(): File? {
        return try {
            val pdfDocument = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(600, 800, 1).create()
            val page = pdfDocument.startPage(pageInfo)

            drawReceiptOnCanvas(page.canvas, 600f, 800f)
            pdfDocument.finishPage(page)

            val file = File(cacheDir, "receipt.pdf")
            pdfDocument.writeTo(FileOutputStream(file))
            pdfDocument.close()
            file
        } catch (e: Exception) {
            Log.e("ReceiptActivity", "PDF creation failed: ${e.message}", e)
            null
        }
    }

    private fun printReceipt() {
        // Guard: avoid IllegalStateException if Activity is no longer in a valid state
        if (isFinishing || isDestroyed) {
            Toast.makeText(this, "Cannot print — activity is closing", Toast.LENGTH_SHORT).show()
            return
        }

        val pdfFile = generateReceiptPdf() ?: run {
            Toast.makeText(this, getString(R.string.could_not_generate_pdf), Toast.LENGTH_SHORT).show()
            return
        }

        val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
        val jobName = "Receipt_${customerId}"

        val adapter = object : PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes,
                cancellationSignal: CancellationSignal?,
                callback: LayoutResultCallback,
                extras: Bundle?
            ) {
                if (cancellationSignal?.isCanceled == true) {
                    callback.onLayoutCancelled()
                    return
                }
                val info = PrintDocumentInfo.Builder(jobName)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(1)
                    .build()
                callback.onLayoutFinished(info, true)
            }

            override fun onWrite(
                pages: Array<out PageRange>?,
                destination: ParcelFileDescriptor,
                cancellationSignal: CancellationSignal?,
                callback: WriteResultCallback
            ) {
                var input: FileInputStream? = null
                var output: FileOutputStream? = null
                try {
                    input = FileInputStream(pdfFile)
                    output = FileOutputStream(destination.fileDescriptor)
                    input.copyTo(output)
                    callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: IOException) {
                    Log.e("ReceiptActivity", "Print write error: ${e.message}", e)
                    callback.onWriteFailed(e.message)
                } finally {
                    input?.close()
                    output?.close()
                }
            }
        }

        try {
            printManager.print(jobName, adapter, PrintAttributes.Builder().build())
        } catch (e: IllegalStateException) {
            Log.e("ReceiptActivity", "Print failed: ${e.message}", e)
            Toast.makeText(this, "Print error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun printBluetoothReceipt() {
        val permissions = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(android.Manifest.permission.BLUETOOTH, android.Manifest.permission.BLUETOOTH_ADMIN, android.Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
            return
        }

        val p = latestPayment ?: return
        lifecycleScope.launch {
            Toast.makeText(this@ReceiptActivity, "Connecting to printer...", Toast.LENGTH_SHORT).show()
            val success = BluetoothPrinterHelper.printReceipt(
                context = this@ReceiptActivity,
                customerName = p.name,
                customerId = customerId,
                date = p.date,
                baseAmount = if (p.billAmount > 0.0) p.billAmount else p.baseAmount,
                extraCharges = p.extraCharges,
                totalPaid = p.paid,
                paymentMode = p.paymentMode,
                paymentNumber = p.paymentNumber,
                receiptNumber = p.receiptNumber,
                remainingAmount = p.remaining.coerceAtLeast(0.0),
                collectorUsername = p.collectorUsername,
                previousOutstanding = p.previousOutstanding,
                alreadyPaid = p.alreadyPaid,
                totalPayable = p.total,
                businessName = businessSettings.name,
                businessPhone = businessSettings.phone,
                businessAddress = businessSettings.address,
                receiptFooter = businessSettings.receiptFooter
            )
            if (success) {
                Toast.makeText(this@ReceiptActivity, "Printed successfully via Bluetooth", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@ReceiptActivity, "Failed to connect. Is printer paired & on?", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun shareReceipt() {
        val pdfFile = generateReceiptPdf() ?: run {
            Toast.makeText(this, getString(R.string.could_not_generate_pdf), Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", pdfFile)

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(contentResolver, pdfFile.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share Bill via..."))

        } catch (e: Exception) {
            Log.e("ReceiptActivity", "Share error: ${e.message}", e)
            Toast.makeText(this, getString(R.string.error_prefix, e.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun downloadReceipt() {
        val pdfFile = generateReceiptPdf() ?: run {
            Toast.makeText(this, getString(R.string.could_not_generate_pdf), Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dateStr = latestPayment?.date?.replace("/", "-") ?: "invoice"
            val outFile = File(downloadsDir, "Invoice_${customerId}_$dateStr.pdf")
            pdfFile.copyTo(outFile, overwrite = true)

            Toast.makeText(this, getString(R.string.saved_in_downloads), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e("ReceiptActivity", "Download error: ${e.message}", e)
            Toast.makeText(this, getString(R.string.error_prefix, e.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun shareToWhatsApp() {
        val payment = latestPayment ?: return
        try {
            val phoneDigits = payment.phone.filter(Char::isDigit)
            val whatsappPhone = when {
                phoneDigits.length == 10 -> "91$phoneDigits"
                phoneDigits.startsWith("0") -> phoneDigits.drop(1)
                else -> phoneDigits
            }
            val bill = if (payment.billAmount > 0.0) payment.billAmount else payment.baseAmount
            val message = getString(
                R.string.whatsapp_receipt_message,
                businessSettings.name,
                businessSettings.phone,
                businessSettings.address,
                payment.receiptNumber.ifBlank { payment.paymentId },
                payment.displayName(LocaleHelper.getLanguage(this)),
                customerId,
                payment.date,
                formatAmount(bill),
                formatAmount(payment.previousOutstanding),
                formatAmount(payment.extraCharges),
                formatAmount(payment.alreadyPaid),
                formatAmount(payment.total),
                formatAmount(payment.paid),
                formatAmount(payment.remaining.coerceAtLeast(0.0)),
                buildModeText(payment.paymentMode, payment.paymentNumber),
                payment.collectorUsername,
                businessSettings.receiptFooter
            )
            val whatsappUri = Uri.parse("https://wa.me/$whatsappPhone?text=${Uri.encode(message)}")
            val whatsappIntent = Intent(Intent.ACTION_VIEW, whatsappUri).setPackage("com.whatsapp")
            if (whatsappPhone.isNotBlank() && whatsappIntent.resolveActivity(packageManager) != null) {
                startActivity(whatsappIntent)
            } else {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, message)
                }
                startActivity(Intent.createChooser(shareIntent, getString(R.string.view_share_receipt)))
            }
        } catch (e: Exception) {
            Log.e("ReceiptActivity", "WhatsApp share error: ${e.message}", e)
            Toast.makeText(this, getString(R.string.error_prefix, e.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun drawReceiptOnCanvas(canvas: Canvas, width: Float, height: Float) {
        val p = latestPayment ?: return
        val paint = Paint().apply { isAntiAlias = true }

        // Background
        paint.color = Color.WHITE
        canvas.drawRect(0f, 0f, width, height, paint)

        // Green header band
        paint.color = Color.parseColor("#2E7D32")
        canvas.drawRect(0f, 0f, width, 120f, paint)

        paint.color     = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize  = 20f
        paint.typeface  = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText(businessSettings.name, width / 2, 65f, paint)

        paint.textSize = 13f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        canvas.drawText(getString(R.string.payment_receipt), width / 2, 92f, paint)

        // Body rows
        val labelX = 40f
        val valueX = width - 40f
        var y      = 150f
        val rowGap = 45f
        paint.textSize = 13f

        fun drawRow(label: String, value: String, valueColor: Int = Color.parseColor("#212121"), valueSize: Float = 13f) {
            val divPaint = Paint().apply {
                color       = Color.parseColor("#E0E0E0")
                strokeWidth = 1f
            }
            canvas.drawLine(labelX, y - 16f, valueX, y - 16f, divPaint)

            paint.textAlign = Paint.Align.LEFT
            paint.typeface  = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            paint.color     = Color.parseColor("#757575")
            paint.textSize  = 13f
            canvas.drawText(label, labelX, y, paint)

            paint.textAlign = Paint.Align.RIGHT
            paint.typeface  = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.color     = valueColor
            paint.textSize  = valueSize
            canvas.drawText(value, valueX, y, paint)

            y += rowGap
        }

        drawRow(getString(R.string.customer_name_label),  p.displayName(LocaleHelper.getLanguage(this)))
        drawRow(getString(R.string.series_number_label),  customerId)
        drawRow(getString(R.string.receipt_number_label), p.receiptNumber.ifBlank { getString(R.string.dash) })
        drawRow(getString(R.string.date_label),            p.date)
        drawRow(getString(R.string.monthly_bill_label), "₹ ${formatAmount(if (p.billAmount > 0.0) p.billAmount else p.baseAmount)}")
        drawRow(getString(R.string.previous_outstanding_label), "₹ ${formatAmount(p.previousOutstanding)}")
        drawRow(getString(R.string.extra_charges_label),  "₹ ${formatAmount(p.extraCharges)}")
        drawRow(getString(R.string.already_paid_label), "₹ ${formatAmount(p.alreadyPaid)}")
        drawRow(getString(R.string.total_payable_label), "₹ ${formatAmount(p.total)}")
        drawRow(
            getString(R.string.total_paid),
            "₹ ${formatAmount(p.paid)}",
            valueColor = Color.parseColor("#2E7D32"),
            valueSize  = 18f
        )
        drawRow(getString(R.string.payment_mode_label), buildModeText(p.paymentMode, p.paymentNumber))
        drawRow(getString(R.string.collector_label), p.collectorUsername.ifBlank { getString(R.string.dash) })
        if (p.remarks.isNotBlank()) drawRow(getString(R.string.payment_remarks), p.remarks)

        // Footer
        y += 20f
        paint.textAlign = Paint.Align.CENTER
        paint.typeface  = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
        paint.color     = Color.parseColor("#558B2F")
        paint.textSize  = 12f
        canvas.drawText(businessSettings.receiptFooter, width / 2, y, paint)
    }

    private fun buildModeText(mode: String, number: String): String =
        if (mode.equals("Cash", ignoreCase = true) || number.isEmpty()) mode
        else "$mode ($number)"

    private fun formatAmount(amount: Double): String =
        if (amount == amount.toLong().toDouble()) amount.toLong().toString()
        else String.format("%.2f", amount)
}
