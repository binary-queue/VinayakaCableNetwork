package com.saimega.vinayakacablenetwork

import android.content.Context
import com.dantsu.escposprinter.EscPosPrinter
import com.dantsu.escposprinter.connection.bluetooth.BluetoothPrintersConnections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object BluetoothPrinterHelper {

    suspend fun printReceipt(
        context: Context,
        customerName: String,
        customerId: String,
        date: String,
        baseAmount: Double,
        extraCharges: Double,
        totalPaid: Double,
        paymentMode: String,
        paymentNumber: String,
        receiptNumber: String = "",
        remainingAmount: Double = 0.0,
        collectorUsername: String = "",
        previousOutstanding: Double = 0.0,
        alreadyPaid: Double = 0.0,
        totalPayable: Double = 0.0,
        businessName: String,
        businessPhone: String,
        businessAddress: String,
        receiptFooter: String
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Find first paired bluetooth printer
                val printerConnection = BluetoothPrintersConnections.selectFirstPaired()
                if (printerConnection == null) {
                    return@withContext false
                }

                // Initialize printer. 
                // Settings for 58mm printer: 203 dpi, 48mm printing width, 32 characters per line.
                // Works gracefully on 80mm printers as well (will just occupy the left 48mm).
                val printer = EscPosPrinter(printerConnection, 203, 48f, 32)

                val modeText = if (paymentNumber.isNotEmpty()) "$paymentMode ($paymentNumber)" else paymentMode

                val receiptTitle   = context.getString(R.string.payment_receipt)
                val seriesLabel    = context.getString(R.string.series_number_label)
                val thankYouLine   = context.getString(R.string.thank_you_payment)

                val receiptText = """
                    [C]<b>$businessName</b>
                    [C]$businessPhone
                    [C]$businessAddress
                    [C]$receiptTitle
                    [L]
                    [C]--------------------------------
                    [L]Name: [R]$customerName
                    [L]$seriesLabel: [R]$customerId
                    [L]Receipt No: [R]$receiptNumber
                    [L]Date: [R]$date
                    [L]Bill Amount: [R]Rs.$baseAmount
                    [L]Previous Due: [R]Rs.$previousOutstanding
                    [L]Extra Charges: [R]Rs.$extraCharges
                    [L]Already Paid: [R]Rs.$alreadyPaid
                    [L]Total Payable: [R]Rs.$totalPayable
                    [C]--------------------------------
                    [L]<b>Amount Paid:</b> [R]<b>Rs.$totalPaid</b>
                    [L]Balance: [R]Rs.$remainingAmount
                    [C]--------------------------------
                    [L]Mode: [R]$modeText
                    [L]Collector: [R]$collectorUsername
                    [L]
                    [C]${if (receiptFooter.isNotBlank()) receiptFooter else thankYouLine}
                    [L]
                    [L]
                """.trimIndent()

                // Depending on the printer, printFormattedTextAndCut may cut the paper
                // For 58mm printers without a cutter, it will just feed paper.
                printer.printFormattedTextAndCut(receiptText)
                printer.disconnectPrinter()
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }
}
