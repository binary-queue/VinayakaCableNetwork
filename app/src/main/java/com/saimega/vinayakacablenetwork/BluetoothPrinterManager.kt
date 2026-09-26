package com.saimega.vinayakacablenetwork

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * Immutable data holder that maps a billing record to the 58mm receipt layout.
 * Build this from your CustomerModel + payment data, then call [BluetoothPrinterManager.sendPrintJob].
 *
 * Example:
 * ```kotlin
 * val receipt = ReceiptModel(
 *     customerName = customer.name,
 *     customerId   = customer.id,
 *     amountPaid   = "₹500.00",
 *     date         = "05/05/2025 11:57"
 * )
 * lifecycleScope.launch {
 *     val ok = BluetoothPrinterManager.sendPrintJob(receipt)
 *     if (!ok) Toast.makeText(ctx, "Print failed", Toast.LENGTH_SHORT).show()
 * }
 * ```
 */
data class ReceiptModel(
    val customerName: String,
    val customerId: String,
    val amountPaid: String,   // e.g. "₹500.00"
    val date: String,         // e.g. "05/05/2025 11:57"
    val businessName: String,
    val businessPhone: String,
    val businessAddress: String,
    val receiptFooter: String
)

/**
 * Singleton that owns the Bluetooth socket lifecycle and all ESC/POS print logic.
 *
 * Connection state is exposed as a [StateFlow] so any UI layer (e.g. the toolbar icon in
 * [DashboardActivity]) can react to connect / disconnect events without polling.
 */
object BluetoothPrinterManager {

    // Standard SPP UUID — understood by virtually all 58mm thermal printers
    private val PRINTER_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var socket: BluetoothSocket? = null

    private val _isConnected = MutableStateFlow(false)

    /** `true` while a socket connection to the printer is open and active. */
    val isConnected: StateFlow<Boolean> = _isConnected

    // ─────────────────────────────────────────────────────────────────────────
    // CONNECTION
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Opens an RFCOMM socket to [device] on [Dispatchers.IO].
     * Any existing connection is closed first.
     *
     * @return `true` on success, `false` on [IOException] or [SecurityException].
     */
    suspend fun connect(device: BluetoothDevice): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                disconnect() // ensure a clean slate
                socket = device.createRfcommSocketToServiceRecord(PRINTER_UUID)
                socket?.connect()
                _isConnected.value = true
                true
            } catch (e: IOException) {
                e.printStackTrace()
                disconnect()
                false
            } catch (e: SecurityException) {
                e.printStackTrace()
                disconnect()
                false
            }
        }
    }

    /**
     * Closes the active socket and resets [isConnected] to `false`.
     * Safe to call even when already disconnected.
     */
    fun disconnect() {
        try { socket?.close() } catch (e: IOException) { e.printStackTrace() }
        socket = null
        _isConnected.value = false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRINTING — high-level entry point
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Converts a [ReceiptModel] into ESC/POS commands and sends them to the printer.
     *
     * The customer name is rendered as a **bitmap** (via [textToBitmap]) so that
     * Telugu / non-ASCII characters print correctly even on printers without the font.
     * The rest of the receipt uses standard ESC/POS text commands.
     *
     * Runs entirely on [Dispatchers.IO] — safe to call from any coroutine scope.
     */
    suspend fun sendPrintJob(data: ReceiptModel): Boolean {
        if (socket == null || !_isConnected.value) return false

        return withContext(Dispatchers.IO) {
            try {
                val os = socket!!.outputStream

                // ESC/POS commands
                val init       = byteArrayOf(0x1B, 0x40)
                val center     = byteArrayOf(0x1B, 0x61, 0x01)
                val left       = byteArrayOf(0x1B, 0x61, 0x00)
                val boldOn     = byteArrayOf(0x1B, 0x45, 0x01)
                val boldOff    = byteArrayOf(0x1B, 0x45, 0x00)
                val feedAndCut = byteArrayOf(0x0A, 0x0A, 0x0A, 0x1D, 0x56, 0x42, 0x00)

                fun line(text: String) = "${text.take(32)}\n"

                os.write(init)

                // ── Header (centred) ──────────────────────────────────
                os.write(center)
                os.write(line(data.businessName).toByteArray(Charsets.UTF_8))
                if (data.businessPhone.isNotBlank()) {
                    os.write(line(data.businessPhone).toByteArray(Charsets.UTF_8))
                }
                if (data.businessAddress.isNotBlank()) {
                    os.write(line(data.businessAddress).toByteArray(Charsets.UTF_8))
                }
                os.write("--------------------------------\n".toByteArray(Charsets.UTF_8))

                // ── Customer name as BITMAP (Telugu-safe) ─────────────
                os.write(left)
                val nameBitmap = createTeluguNameBitmap(
                    "Customer: ${data.customerName}  ID:${data.customerId}"
                )
                // Send bitmap inline using GS v 0 raster command
                sendBitmapBytes(os, nameBitmap)

                // ── Date and amount as plain text ─────────────────────
                os.write(line("Date    : ${data.date}").toByteArray(Charsets.UTF_8))

                os.write(boldOn)
                os.write(line("Amount Paid: ${data.amountPaid}").toByteArray(Charsets.UTF_8))
                os.write(boldOff)

                // ── Footer (centred) ──────────────────────────────────
                os.write("--------------------------------\n".toByteArray(Charsets.UTF_8))
                os.write(center)
                // Telugu footer rendered as bitmap so it prints correctly on any printer
                if (data.receiptFooter.isNotBlank()) {
                    val footerBitmap = createTeluguBitmap(data.receiptFooter)
                    sendBitmapBytes(os, footerBitmap)
                }

                os.write(feedAndCut)
                os.flush()
                true
            } catch (e: IOException) {
                e.printStackTrace()
                disconnect()
                false
            }
        }
    }

    /**
     * Convenience wrapper: renders a Telugu (or any Unicode) name string
     * into a high-contrast monochrome bitmap sized for a 58mm printer.
     */
    fun createTeluguNameBitmap(text: String): Bitmap =
        textToBitmap(text, textSize = 26f, bold = false)

    /**
     * Alias matching the API name requested — identical to [createTeluguNameBitmap].
     * Use this when rendering any Telugu string (name, footer, etc.) for printing.
     */
    fun createTeluguBitmap(text: String): Bitmap =
        textToBitmap(text, textSize = 26f, bold = false)

    /**
     * Writes raw raster bitmap bytes directly to an [OutputStream] using
     * the GS v 0 command. Called internally by [sendPrintJob].
     */
    private fun sendBitmapBytes(os: java.io.OutputStream, bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        val bytesPerRow = (width + 7) / 8

        val header = byteArrayOf(
            0x1D, 0x76, 0x30, 0x00,
            (bytesPerRow and 0xFF).toByte(),
            ((bytesPerRow shr 8) and 0xFF).toByte(),
            (height and 0xFF).toByte(),
            ((height shr 8) and 0xFF).toByte()
        )
        os.write(header)

        val rowBytes = ByteArray(bytesPerRow)
        for (y in 0 until height) {
            rowBytes.fill(0)
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                val luminance = (Color.red(pixel) * 0.299
                        + Color.green(pixel) * 0.587
                        + Color.blue(pixel) * 0.114)
                if (luminance < 128) {
                    rowBytes[x / 8] = (rowBytes[x / 8].toInt() or (0x80 shr (x % 8))).toByte()
                }
            }
            os.write(rowBytes)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BITMAP PRINTING — for non-ASCII scripts (Telugu, Hindi, etc.)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Renders [text] into a monochrome [Bitmap] using Android's Canvas + Paint.
     * The resulting bitmap is sized to fit the 58mm paper width (384 pixels).
     *
     * @param text     The string to render (supports any Unicode script)
     * @param textSize Font size in pixels (default 28f works well on 58mm)
     * @param bold     Whether to render in bold typeface
     * @return A 1-bit-compatible monochrome Bitmap (white background, black text)
     */
    fun textToBitmap(
        text: String,
        textSize: Float = 28f,
        bold: Boolean = false
    ): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            this.textSize = textSize
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

        // Measure the text to determine bitmap dimensions
        val width = 384 // 58mm printer standard pixel width
        val textWidth = paint.measureText(text)
        val fm = paint.fontMetrics
        val lineHeight = (fm.descent - fm.ascent + fm.leading).toInt()

        // Support multi-line if text is wider than the paper
        val lines = mutableListOf<String>()
        if (textWidth <= width) {
            lines.add(text)
        } else {
            // Simple word-wrap
            val words = text.split(" ")
            var current = ""
            for (word in words) {
                val test = if (current.isEmpty()) word else "$current $word"
                if (paint.measureText(test) <= width) {
                    current = test
                } else {
                    if (current.isNotEmpty()) lines.add(current)
                    current = word
                }
            }
            if (current.isNotEmpty()) lines.add(current)
        }

        val height = (lineHeight * lines.size).coerceAtLeast(lineHeight)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        var y = -fm.ascent // baseline of the first line
        for (line in lines) {
            canvas.drawText(line, 0f, y, paint)
            y += lineHeight
        }

        return bitmap
    }

    /**
     * Sends a [Bitmap] to the thermal printer using ESC/POS raster bit-image commands.
     *
     * Converts each row of pixels into a 1-bit-per-pixel byte array and sends it
     * using the GS v 0 command (raster bit image).
     *
     * @param bitmap The monochrome bitmap to print (should be 384px wide for 58mm)
     * @return `true` on success
     */
    suspend fun printBitmap(bitmap: Bitmap): Boolean {
        if (socket == null || !_isConnected.value) return false

        return withContext(Dispatchers.IO) {
            try {
                val os = socket!!.outputStream
                val width = bitmap.width
                val height = bitmap.height

                // Bytes per row (8 pixels per byte, rounded up)
                val bytesPerRow = (width + 7) / 8

                // GS v 0  —  raster bit image
                // Format: 1D 76 30 m xL xH yL yH [data]
                //   m = 0 (normal mode)
                //   xL, xH = bytes per row (little-endian)
                //   yL, yH = height in dots (little-endian)
                val header = byteArrayOf(
                    0x1D, 0x76, 0x30, 0x00,
                    (bytesPerRow and 0xFF).toByte(),
                    ((bytesPerRow shr 8) and 0xFF).toByte(),
                    (height and 0xFF).toByte(),
                    ((height shr 8) and 0xFF).toByte()
                )
                os.write(header)

                // Send pixel data row by row
                val rowBytes = ByteArray(bytesPerRow)
                for (y in 0 until height) {
                    rowBytes.fill(0)
                    for (x in 0 until width) {
                        val pixel = bitmap.getPixel(x, y)
                        // Threshold: if the pixel is dark enough, set the bit
                        val luminance = (Color.red(pixel) * 0.299
                                + Color.green(pixel) * 0.587
                                + Color.blue(pixel) * 0.114)
                        if (luminance < 128) {
                            // ESC/POS: bit = 1 means "print black dot"
                            rowBytes[x / 8] = (rowBytes[x / 8].toInt() or (0x80 shr (x % 8))).toByte()
                        }
                    }
                    os.write(rowBytes)
                }

                os.flush()
                true
            } catch (e: IOException) {
                e.printStackTrace()
                disconnect()
                false
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRINTING — low-level ESC/POS builder
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Formats and sends a billing receipt over the active Bluetooth socket.
     *
     * Layout (58 mm / 32 chars per line):
     * ```
     *      Vinayaka Cable Network
     * --------------------------------
     * Customer: <name>
     * Date    : <date>
     * Amount Paid: ₹<amount>   ← BOLD
     * --------------------------------
     *           Thank you!
     * [paper feed + partial cut]
     * ```
     *
     * @return `true` on success; `false` when not connected or an [IOException] occurs
     *         (socket is closed automatically on failure).
     */
    suspend fun printBillingReceipt(
        customerName: String,
        amount: String,
        date: String,
        businessName: String,
        businessPhone: String,
        businessAddress: String,
        receiptFooter: String
    ): Boolean {
        if (socket == null || !_isConnected.value) return false

        return withContext(Dispatchers.IO) {
            try {
                val os = socket!!.outputStream

                // ── ESC/POS command byte arrays ────────────────────────────
                val init       = byteArrayOf(0x1B, 0x40)              // ESC @ — reset printer
                val center     = byteArrayOf(0x1B, 0x61, 0x01)        // ESC a 1 — center align
                val left       = byteArrayOf(0x1B, 0x61, 0x00)        // ESC a 0 — left align
                val boldOn     = byteArrayOf(0x1B, 0x45, 0x01)        // ESC E 1 — bold ON
                val boldOff    = byteArrayOf(0x1B, 0x45, 0x00)        // ESC E 0 — bold OFF
                val feedAndCut = byteArrayOf(                          // 3× LF + partial cut
                    0x0A, 0x0A, 0x0A,
                    0x1D, 0x56, 0x42, 0x00
                )

                // Truncate to 32 chars max (58 mm paper width) and append newline
                fun line(text: String) = "${text.take(32)}\n"

                // ── Send receipt bytes ─────────────────────────────────────
                os.write(init)

                // Header — centred
                os.write(center)
                os.write(line(businessName).toByteArray(Charsets.UTF_8))
                if (businessPhone.isNotBlank()) os.write(line(businessPhone).toByteArray(Charsets.UTF_8))
                if (businessAddress.isNotBlank()) os.write(line(businessAddress).toByteArray(Charsets.UTF_8))
                os.write("--------------------------------\n".toByteArray(Charsets.UTF_8))

                // Body — left aligned
                os.write(left)
                os.write(line("Customer: $customerName").toByteArray(Charsets.UTF_8))
                os.write(line("Date    : $date").toByteArray(Charsets.UTF_8))

                // Amount — bold
                os.write(boldOn)
                os.write(line("Amount Paid: $amount").toByteArray(Charsets.UTF_8))
                os.write(boldOff)

                // Footer — centred
                os.write("--------------------------------\n".toByteArray(Charsets.UTF_8))
                os.write(center)
                if (receiptFooter.isNotBlank()) os.write(line(receiptFooter).toByteArray(Charsets.UTF_8))

                // Paper feed and cut
                os.write(feedAndCut)
                os.flush()
                true
            } catch (e: IOException) {
                e.printStackTrace()
                disconnect() // broken socket — reset so UI reflects disconnected state
                false
            }
        }
    }
}
