package com.couchtommouth.bridge.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.couchtommouth.bridge.R
import com.couchtommouth.bridge.config.AppConfig
import com.couchtommouth.bridge.databinding.ActivityPrinterSetupBinding
import com.couchtommouth.bridge.printer.PrinterManager
import kotlinx.coroutines.*

class PrinterSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPrinterSetupBinding
    private lateinit var printerManager: PrinterManager
    private lateinit var config: AppConfig
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val deviceAdapter = BluetoothDeviceAdapter { device ->
        connectToPrinter(device)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPrinterSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        printerManager = PrinterManager(this)
        config = AppConfig(this)

        setupUI()
        loadPairedDevices()
    }

    private fun setupUI() {
        binding.rvDevices.apply {
            layoutManager = LinearLayoutManager(this@PrinterSetupActivity)
            adapter = deviceAdapter
        }

        binding.btnRefresh.setOnClickListener {
            loadPairedDevices()
        }

        binding.btnTestPrint.setOnClickListener {
            testPrint()
        }

        binding.btnTestDrawer.setOnClickListener {
            testDrawer()
        }

        binding.btnBack.setOnClickListener {
            finish()
        }

        // Show current printer
        val currentPrinter = config.getSavedPrinterName()
        if (currentPrinter != null) {
            binding.tvCurrentPrinter.text = "Current: $currentPrinter"
            binding.btnTestPrint.isEnabled = true
            binding.btnTestDrawer.isEnabled = config.hasCashDrawer()
        } else {
            binding.tvCurrentPrinter.text = "No printer selected"
            binding.btnTestPrint.isEnabled = false
            binding.btnTestDrawer.isEnabled = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun loadPairedDevices() {
        binding.progressBar.visibility = View.VISIBLE
        
        val devices = printerManager.getPairedDevices()
        
        // Filter to likely printers (usually have "printer" in name or specific prefixes)
        val printerDevices = devices.filter { device ->
            val name = device.name?.lowercase() ?: ""
            name.contains("printer") || 
            name.contains("pos") || 
            name.contains("thermal") ||
            name.contains("tp5") ||      // TP510UB
            name.contains("m335") ||     // M335B
            name.startsWith("bt") ||
            name.startsWith("pt-") ||
            name.startsWith("rpp")
        }.ifEmpty { devices }  // If no matches, show all

        deviceAdapter.submitList(printerDevices)
        binding.progressBar.visibility = View.GONE

        if (printerDevices.isEmpty()) {
            Toast.makeText(this, "No paired Bluetooth devices found. Please pair your printer in Android Settings first.", Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToPrinter(device: BluetoothDevice) {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatus.text = "Connecting to ${device.name}..."

        scope.launch {
            val connected = printerManager.connectToPrinter(device.address)

            binding.progressBar.visibility = View.GONE

            if (connected) {
                config.savePrinter(device.address, device.name ?: "Unknown")
                binding.tvCurrentPrinter.text = "Current: ${device.name}"
                binding.tvStatus.text = "Connected successfully!"
                binding.btnTestPrint.isEnabled = true
                binding.btnTestDrawer.isEnabled = config.hasCashDrawer()
                
                Toast.makeText(this@PrinterSetupActivity, "Printer connected!", Toast.LENGTH_SHORT).show()
            } else {
                binding.tvStatus.text = "Connection failed"
                Toast.makeText(this@PrinterSetupActivity, "Failed to connect. Make sure the printer is on and nearby.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun testPrint() {
        binding.tvStatus.text = "Printing test page..."
        
        scope.launch {
            try {
                // Always reconnect before a test — never trust isConnected() which can be stale
                val address = config.getSavedPrinterAddress()
                if (address != null) {
                    printerManager.connectToPrinter(address)
                }
                
                printerManager.printTestPage()
                binding.tvStatus.text = "Test page printed!"
                Toast.makeText(this@PrinterSetupActivity, "Test page sent to printer", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                binding.tvStatus.text = "Print failed: ${e.message}"
                Toast.makeText(this@PrinterSetupActivity, "Print failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun testDrawer() {
        binding.tvStatus.text = "Opening cash drawer..."
        
        scope.launch {
            try {
                // Always reconnect before a test — never trust isConnected() which can be stale
                val address = config.getSavedPrinterAddress()
                if (address != null) {
                    printerManager.connectToPrinter(address)
                }
                
                printerManager.openCashDrawer()
                binding.tvStatus.text = "Drawer command sent!"
                Toast.makeText(this@PrinterSetupActivity, "Cash drawer command sent", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                binding.tvStatus.text = "Failed: ${e.message}"
                Toast.makeText(this@PrinterSetupActivity, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        // Release the Bluetooth socket so MainActivity can reconnect cleanly on resume.
        // Without this, the socket stays open after testing, blocking MainActivity's reconnect.
        printerManager.disconnect()
    }
}

/**
 * Adapter for displaying Bluetooth devices
 */
class BluetoothDeviceAdapter(
    private val onDeviceClick: (BluetoothDevice) -> Unit
) : RecyclerView.Adapter<BluetoothDeviceAdapter.ViewHolder>() {

    private var devices: List<BluetoothDevice> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(newDevices: List<BluetoothDevice>) {
        devices = newDevices
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_bluetooth_device, parent, false)
        return ViewHolder(view)
    }

    @SuppressLint("MissingPermission")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = devices[position]
        holder.tvName.text = device.name ?: "Unknown Device"
        holder.tvAddress.text = device.address
        holder.itemView.setOnClickListener { onDeviceClick(device) }
    }

    override fun getItemCount(): Int = devices.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvDeviceName)
        val tvAddress: TextView = view.findViewById(R.id.tvDeviceAddress)
    }
}
