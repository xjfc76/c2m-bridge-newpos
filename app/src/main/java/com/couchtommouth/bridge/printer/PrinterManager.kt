package com.couchtommouth.bridge.printer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.couchtommouth.bridge.R
import com.couchtommouth.bridge.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.util.*

/**
 * Manages Bluetooth thermal printer connections and printing.
 * Supports standard ESC/POS printers including:
 * - TP510UB (58mm, no drawer)
 * - M335B (58/80mm, with drawer)
 */
class PrinterManager(private val context: Context) {

    companion object {
        private const val TAG = "PrinterManager"
        // Standard SPP UUID for Bluetooth serial communication
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val bluetoothManager: BluetoothManager? = 
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    // @Volatile ensures coroutines on Dispatchers.IO always see the current value
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var outputStream: OutputStream? = null
    private val config = AppConfig(context)

    // ESC/POS Commands
    private object ESC {
        val INIT = byteArrayOf(0x1B, 0x40)                    // Initialize printer
        val ALIGN_LEFT = byteArrayOf(0x1B, 0x61, 0x00)        // Left align
        val ALIGN_CENTER = byteArrayOf(0x1B, 0x61, 0x01)      // Center align
        val ALIGN_RIGHT = byteArrayOf(0x1B, 0x61, 0x02)       // Right align
        val BOLD_ON = byteArrayOf(0x1B, 0x45, 0x01)           // Bold on
        val BOLD_OFF = byteArrayOf(0x1B, 0x45, 0x00)          // Bold off
        val DOUBLE_HEIGHT_ON = byteArrayOf(0x1B, 0x21, 0x10)  // Double height
        val DOUBLE_WIDTH_ON = byteArrayOf(0x1B, 0x21, 0x20)   // Double width
        val DOUBLE_ON = byteArrayOf(0x1B, 0x21, 0x30)         // Double height & width
        val NORMAL = byteArrayOf(0x1B, 0x21, 0x00)            // Normal text
        val UNDERLINE_ON = byteArrayOf(0x1B, 0x2D, 0x01)      // Underline on
        val UNDERLINE_OFF = byteArrayOf(0x1B, 0x2D, 0x00)     // Underline off
        val CUT_PAPER = byteArrayOf(0x1D, 0x56, 0x00)         // Full cut
        val CUT_PARTIAL = byteArrayOf(0x1D, 0x56, 0x01)       // Partial cut
        val FEED_LINES = byteArrayOf(0x1B, 0x64, 0x04)        // Feed 4 lines
        val CASH_DRAWER = byteArrayOf(0x1B, 0x70, 0x00, 0x19, 0xFA.toByte())
        val LINE_SPACING_DEFAULT = byteArrayOf(0x1B, 0x32)    // Default line spacing
    }

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    fun isConnected(): Boolean = socket?.isConnected == true

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> {
        return bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
    }

    @SuppressLint("MissingPermission")
    suspend fun connectToPrinter(address: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // Disconnect existing connection
            disconnect()

            val device = bluetoothAdapter?.getRemoteDevice(address)
            if (device == null) {
                Log.e(TAG, "Device not found: $address")
                return@withContext false
            }

            Log.d(TAG, "Connecting to ${device.name} ($address)")
            
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket?.connect()
            outputStream = socket?.outputStream

            if (socket?.isConnected == true) {
                Log.d(TAG, "Connected successfully")
                // Initialize printer
                outputStream?.write(ESC.INIT)
                return@withContext true
            }

            return@withContext false
        } catch (e: IOException) {
            Log.e(TAG, "Connection failed", e)
            disconnect()
            return@withContext false
        }
    }

    fun disconnect() {
        try {
            outputStream?.close()
            socket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error disconnecting", e)
        }
        outputStream = null
        socket = null
    }

    /**
     * Ensures a live connection exists before any printer operation.
     * Re-connects using the saved address if the socket is gone or null.
     * Throws IOException if no address is saved or reconnect fails.
     */
    private suspend fun ensureConnected() {
        if (socket?.isConnected != true || outputStream == null) {
            val savedAddress = config.getSavedPrinterAddress()
                ?: throw IOException("No printer configured")
            val connected = connectToPrinter(savedAddress)
            if (!connected) throw IOException("Could not connect to printer")
        }
    }

    /**
     * Print a receipt using web-controlled lines format.
     * 
     * The receipt content is now controlled by JavaScript (print-bridge.js).
     * Edit that file to change receipt layout - no app rebuild needed!
     */
    suspend fun printReceipt(receipt: ReceiptData) = withContext(Dispatchers.IO) {
        Log.d(TAG, "printReceipt called - useLines=${receipt.useLines}, items=${receipt.items.size}, total=${receipt.total}")

        try {
            ensureConnected()
            val stream = outputStream ?: throw IOException("Printer output stream unavailable")

            Log.d(TAG, "Initializing printer...")
            // Initialize
            stream.write(ESC.INIT)
            stream.write(ESC.LINE_SPACING_DEFAULT)

            // Use web-controlled lines if available
            if (receipt.useLines && receipt.lines.isNotEmpty()) {
                Log.d(TAG, "Using web-controlled format with ${receipt.lines.size} lines")
                printWebControlledReceipt(receipt.lines)
            } else {
                Log.d(TAG, "Using legacy format")
                // Fallback to legacy format (for old versions)
                printLegacyReceipt(receipt)
            }
            printLine("")

            // Feed and cut
            stream.write(ESC.FEED_LINES)
            if (config.hasPaperCutter()) {
                stream.write(ESC.CUT_PARTIAL)
            }

            stream.flush()
            Log.d(TAG, "Receipt printed successfully - all data sent to printer")

        } catch (e: IOException) {
            Log.e(TAG, "Print failed with IOException: ${e.message}", e)
            // Connection might be broken, clear it so next attempt reconnects
            disconnect()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Print failed with unexpected error: ${e.message}", e)
            throw e
        }
    }

    /**
     * Print receipt using web-controlled lines array.
     * Each line specifies its own formatting (bold, big, center, price).
     * Edit print-bridge.js to change receipt layout!
     */
    private fun printWebControlledReceipt(lines: List<ReceiptLine>) {
        val stream = outputStream ?: return
        
        for (line in lines) {
            // Skip logo for now (bitmap printing can freeze some printers)
            if (line.logo) {
                Log.d(TAG, "Skipping logo line (disabled)")
                continue
            }
            
            // Set alignment
            if (line.center) {
                stream.write(ESC.ALIGN_CENTER)
            } else {
                stream.write(ESC.ALIGN_LEFT)
            }
            
            // Set text size
            if (line.big) {
                stream.write(ESC.DOUBLE_ON)
            } else {
                stream.write(ESC.NORMAL)
            }
            
            // Set bold
            if (line.bold) {
                stream.write(ESC.BOLD_ON)
            } else {
                stream.write(ESC.BOLD_OFF)
            }
            
            // Print the line (with price if specified)
            if (line.price != null) {
                val priceStr = formatPrice(line.price.toDoubleOrNull() ?: 0.0)
                printLine(formatLine(line.text, priceStr))
            } else {
                printLine(line.text)
            }
            
            // Reset formatting
            stream.write(ESC.NORMAL)
            stream.write(ESC.BOLD_OFF)
        }
    }

    /**
     * Legacy receipt printing (for backwards compatibility with old JSON format)
     */
    private fun printLegacyReceipt(receipt: ReceiptData) {
        val stream = outputStream ?: return
        
        Log.d(TAG, "Printing legacy receipt: ${receipt.receiptNumber}")
        
        // Header (logo disabled - was causing printer freeze)
        stream.write(ESC.ALIGN_CENTER)
        stream.write(ESC.DOUBLE_ON)
        stream.write(ESC.BOLD_ON)
        printLine("CouchToMouth")
        stream.write(ESC.NORMAL)
        stream.write(ESC.BOLD_OFF)
        printLine("")
        
        // Order type/info
        if (receipt.orderType.isNotEmpty()) {
            stream.write(ESC.BOLD_ON)
            printLine(receipt.orderType)
            stream.write(ESC.BOLD_OFF)
            printLine("")
        }
        
        // Order number and date
        stream.write(ESC.ALIGN_LEFT)
        if (receipt.receiptNumber.isNotEmpty()) {
            printLine("Order: ${receipt.receiptNumber}")
        }
        if (receipt.dateTime.isNotEmpty()) {
            printLine(receipt.dateTime)
        }
        
        // Customer info
        if (receipt.customerName.isNotEmpty()) {
            printLine("Customer: ${receipt.customerName}")
        }
        if (receipt.customerPhone.isNotEmpty()) {
            printLine("Phone: ${receipt.customerPhone}")
        }
        
        // Delivery address
        if (receipt.isDelivery && receipt.deliveryAddress.isNotEmpty()) {
            printLine("")
            printLine("DELIVER TO:")
            printLine(receipt.deliveryAddress)
        }
        
        printLine("-".repeat(32))
        
        // Items
        for (item in receipt.items) {
            if (receipt.showPrices) {
                printLine(formatLine(item.name, formatPrice(item.price)))
            } else {
                stream.write(ESC.BOLD_ON)
                printLine(item.name)
                stream.write(ESC.BOLD_OFF)
            }
            // Print modifiers
            for (mod in item.modifiers) {
                if (mod.name.isNotEmpty()) {
                    printLine("  - ${mod.name}")
                }
            }
        }
        
        printLine("-".repeat(32))
        
        // Total
        if (receipt.showPrices) {
            stream.write(ESC.BOLD_ON)
            printLine(formatLine("TOTAL:", formatPrice(receipt.total)))
            stream.write(ESC.BOLD_OFF)
        }
        
        // Payment method
        if (receipt.paymentMethod.isNotEmpty()) {
            printLine("")
            printLine("Payment: ${receipt.paymentMethod}")
        }
        
        stream.write(ESC.ALIGN_CENTER)
        printLine("")
        printLine("Thank you!")
        
        Log.d(TAG, "Legacy receipt complete")
    }

    /**
     * Open cash drawer (sends pulse to drawer connected to printer)
     */
    suspend fun openCashDrawer() = withContext(Dispatchers.IO) {
        try {
            ensureConnected()
            val stream = outputStream ?: throw IOException("Output stream unavailable")
            Log.d(TAG, "Opening cash drawer")
            stream.write(ESC.CASH_DRAWER)
            stream.flush()
            Log.d(TAG, "Cash drawer opened")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to open cash drawer", e)
            disconnect()
            throw e
        }
    }

    /**
     * Print a test page
     */
    suspend fun printTestPage() = withContext(Dispatchers.IO) {
        try {
            ensureConnected()
            val stream = outputStream ?: throw IOException("Output stream unavailable")

            stream.write(ESC.INIT)
            stream.write(ESC.ALIGN_CENTER)
            stream.write(ESC.DOUBLE_ON)
            stream.write(ESC.BOLD_ON)
            printLine("PRINTER TEST")
            stream.write(ESC.NORMAL)
            stream.write(ESC.BOLD_OFF)
            printLine("")
            printLine("C2M POS")
            printLine("Bridge App v1.0")
            printLine("")
            stream.write(ESC.ALIGN_LEFT)
            printLine("Normal text")
            stream.write(ESC.BOLD_ON)
            printLine("Bold text")
            stream.write(ESC.BOLD_OFF)
            printLine("-".repeat(32))
            printLine("If you can read this,")
            printLine("printing is working!")
            printLine("")
            stream.write(ESC.FEED_LINES)
            if (config.hasPaperCutter()) {
                stream.write(ESC.CUT_PARTIAL)
            }
            stream.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Test print failed", e)
            disconnect()  // clear the broken socket so next attempt reconnects cleanly
            throw e       // let caller show the error
        }
    }

    private fun printLine(text: String) {
        // Use CP437 encoding for ESC/POS compatibility (£ = 0x9C in CP437)
        // Replace £ with the CP437 byte directly
        val bytes = text.replace("£", "\u009C").toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0x0A)
        outputStream?.write(bytes)
    }

    /**
     * Print the Couch to Mouth logo from drawable resources
     */
    private fun printLogo() {
        try {
            val stream = outputStream ?: return
            
            // Load logo from drawable
            val bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.receipt_logo)
            if (bitmap == null) {
                Log.w(TAG, "Could not load receipt_logo")
                return
            }
            
            // Scale to printer width (max ~384 pixels for 58mm, ~576 for 80mm)
            val maxWidth = 200  // Keep it reasonable size for receipt
            val scale = maxWidth.toFloat() / bitmap.width
            val scaledWidth = maxWidth
            val scaledHeight = (bitmap.height * scale).toInt()
            
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
            
            // Convert to monochrome and print
            printBitmap(scaledBitmap)
            
            scaledBitmap.recycle()
            bitmap.recycle()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to print logo", e)
        }
    }

    /**
     * Print a bitmap using ESC/POS raster bit image command
     */
    private fun printBitmap(bitmap: Bitmap) {
        val stream = outputStream ?: return
        
        val width = bitmap.width
        val height = bitmap.height
        
        // Width must be multiple of 8
        val printWidth = (width + 7) / 8 * 8
        
        // GS v 0 command: Print raster bit image
        // Format: GS v 0 m xL xH yL yH d1...dk
        val xL = (printWidth / 8) and 0xFF
        val xH = ((printWidth / 8) shr 8) and 0xFF
        val yL = height and 0xFF
        val yH = (height shr 8) and 0xFF
        
        // Command header
        stream.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00, xL.toByte(), xH.toByte(), yL.toByte(), yH.toByte()))
        
        // Convert bitmap to monochrome bytes
        for (y in 0 until height) {
            for (x in 0 until printWidth step 8) {
                var byte = 0
                for (bit in 0 until 8) {
                    val px = x + bit
                    if (px < width) {
                        val pixel = bitmap.getPixel(px, y)
                        // Convert to grayscale and threshold
                        val gray = (0.299 * ((pixel shr 16) and 0xFF) +
                                   0.587 * ((pixel shr 8) and 0xFF) +
                                   0.114 * (pixel and 0xFF)).toInt()
                        // Dark pixels = 1 (print), light pixels = 0 (no print)
                        if (gray < 128) {
                            byte = byte or (0x80 shr bit)
                        }
                    }
                }
                stream.write(byte)
            }
        }
        
        stream.flush()
    }

    /**
     * Format price with £ symbol (will be converted to CP437 in printLine)
     */
    private fun formatPrice(amount: Double): String {
        return "£${"%.2f".format(amount)}"
    }

    /**
     * Format a line with left and right text (for item - price alignment)
     */
    private fun formatLine(left: String, right: String, width: Int = 32): String {
        val spaces = width - left.length - right.length
        return if (spaces > 0) {
            left + " ".repeat(spaces) + right
        } else {
            // Truncate left side if too long
            left.take(width - right.length - 1) + " " + right
        }
    }
}
