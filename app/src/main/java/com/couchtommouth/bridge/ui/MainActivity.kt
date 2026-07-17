package com.couchtommouth.bridge.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.couchtommouth.bridge.BuildConfig
import com.couchtommouth.bridge.R
import com.couchtommouth.bridge.config.AppConfig
import com.couchtommouth.bridge.databinding.ActivityMainBinding
import com.couchtommouth.bridge.payment.PaymentManager
import com.couchtommouth.bridge.payment.PaymentResult
import com.couchtommouth.bridge.printer.PrinterManager
import com.couchtommouth.bridge.printer.ReceiptData
import com.couchtommouth.bridge.update.UpdateManager
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var printerManager: PrinterManager
    private lateinit var paymentManager: PaymentManager
    private lateinit var config: AppConfig
    private lateinit var updateManager: UpdateManager

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    companion object {
        private const val TAG = "MainActivity"
    }

    // Permission request launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            Log.d(TAG, "All permissions granted")
            initializeBluetooth()
        } else {
            Toast.makeText(this, "Bluetooth permissions required for printing", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize config
        config = AppConfig(this)

        // Initialize managers
        printerManager = PrinterManager(this)
        paymentManager = PaymentManager(this)
        updateManager = UpdateManager(this)

        // Check for app updates
        scope.launch {
            updateManager.checkForUpdates(this@MainActivity)
        }

        // Request permissions
        requestBluetoothPermissions()

        // Setup WebView
        setupWebView()

        // Setup settings button
        binding.fabSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Load the POS URL
        loadPOS()
    }

    private fun requestBluetoothPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) 
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) 
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        } else {
            // Older Android
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            initializeBluetooth()
        }
    }

    private fun initializeBluetooth() {
        // Connect to saved printer if configured
        val savedPrinter = config.getSavedPrinterAddress()
        if (savedPrinter != null) {
            scope.launch {
                val connected = printerManager.connectToPrinter(savedPrinter)
                if (connected) {
                    Log.d(TAG, "Connected to saved printer")
                    updatePrinterStatus(true)
                } else {
                    Log.w(TAG, "Failed to connect to saved printer")
                    updatePrinterStatus(false)
                }
            }
        }
        startPrinterHealthCheck()
    }

    /**
     * Background coroutine that silently reconnects to the printer if it drops mid-session.
     * Runs every 30 seconds — staff never need to intervene for brief disconnections.
     */
    private fun startPrinterHealthCheck() {
        scope.launch {
            while (true) {
                delay(30_000)
                val savedPrinter = config.getSavedPrinterAddress()
                if (savedPrinter != null && !printerManager.isConnected()) {
                    Log.d(TAG, "Health check: printer disconnected, reconnecting...")
                    val connected = printerManager.connectToPrinter(savedPrinter)
                    Log.d(TAG, "Health check reconnect = $connected")
                    updatePrinterStatus(connected)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                allowFileAccess = true
                allowContentAccess = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                loadWithOverviewMode = true
                useWideViewPort = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }

            // Add JavaScript interface for communication with POS
            addJavascriptInterface(POSBridge(), "AndroidBridge")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d(TAG, "Page loaded: $url")
                    binding.progressBar.visibility = View.GONE
                    
                    // Inject the bridge detection script
                    injectBridgeScript()
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    Log.e(TAG, "WebView error: ${error?.description}")
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    if (newProgress < 100) {
                        binding.progressBar.visibility = View.VISIBLE
                        binding.progressBar.progress = newProgress
                    } else {
                        binding.progressBar.visibility = View.GONE
                    }
                }

                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    Log.d(TAG, "JS Console: ${consoleMessage?.message()}")
                    return super.onConsoleMessage(consoleMessage)
                }
            }
        }
    }

    private fun loadPOS() {
        val url = config.getPosUrl()
        Log.d(TAG, "Loading POS: $url")
        binding.progressBar.visibility = View.VISIBLE
        binding.webView.loadUrl(url)
    }

    private fun injectBridgeScript() {
        // Inject JavaScript to detect bridge and override payment buttons
        val script = """
            (function() {
                // Mark that we're running in the bridge app
                window.isAndroidBridge = true;
                
                // Function to be called by POS when payment is requested
                window.requestCardPayment = function(amount, reference) {
                    if (window.AndroidBridge) {
                        window.AndroidBridge.processCardPayment(amount, reference);
                    }
                };
                
                window.requestCashPayment = function(amount, reference) {
                    if (window.AndroidBridge) {
                        window.AndroidBridge.processCashPayment(amount, reference);
                    }
                };
                
                window.printReceipt = function(receiptJson) {
                    if (window.AndroidBridge) {
                        window.AndroidBridge.printReceipt(receiptJson);
                    }
                };
                
                window.openCashDrawer = function() {
                    if (window.AndroidBridge) {
                        window.AndroidBridge.openCashDrawer();
                    }
                };
                
                console.log('Android Bridge initialized');
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(script) { result ->
            Log.d(TAG, "Bridge script injected: $result")
        }
    }

    private fun updatePrinterStatus(connected: Boolean) {
        Log.d(TAG, "Printer status: ${if (connected) "Connected" else "Disconnected"}")
    }

    /**
     * JavaScript Interface - called from WebView
     */
    inner class POSBridge {

        @JavascriptInterface
        fun processCardPayment(amount: String, reference: String) {
            Log.d(TAG, "Card payment requested: £$amount, ref: $reference")
            
            runOnUiThread {
                // Check if payment provider is configured
                if (!paymentManager.isConfigured()) {
                    showPaymentNotConfiguredDialog()
                    return@runOnUiThread
                }

                paymentManager.processCardPayment(
                    activity = this@MainActivity,
                    amount = amount.toDoubleOrNull() ?: 0.0,
                    reference = reference
                ) { result ->
                    handlePaymentResult(result, isCard = true)
                }
            }
        }

        @JavascriptInterface
        fun processCashPayment(amount: String, reference: String) {
            Log.d(TAG, "Cash payment requested: £$amount, ref: $reference")
            
            runOnUiThread {
                // Open cash drawer
                if (config.hasCashDrawer()) {
                    scope.launch {
                        try {
                            printerManager.openCashDrawer()
                        } catch (e: Exception) {
                            Log.e(TAG, "Drawer open failed during cash payment", e)
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "Cash drawer failed — check printer", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                
                // Notify POS that payment is complete
                notifyPaymentComplete(PaymentResult.Success(
                    transactionId = "CASH-${System.currentTimeMillis()}",
                    amount = amount.toDoubleOrNull() ?: 0.0,
                    paymentMethod = "Cash"
                ))
            }
        }

        @JavascriptInterface
        fun printReceipt(receiptJson: String) {
            Log.d(TAG, "Print receipt requested")
            
            scope.launch {
                try {
                    val receiptData = ReceiptData.fromJson(receiptJson)
                    printerManager.printReceipt(receiptData)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to print receipt", e)
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Print failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        @JavascriptInterface
        fun openCashDrawer() {
            Log.d(TAG, "Open cash drawer requested")
            
            if (config.hasCashDrawer()) {
                scope.launch {
                    try {
                        printerManager.openCashDrawer()
                    } catch (e: Exception) {
                        Log.e(TAG, "Drawer open failed", e)
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Cash drawer failed — check printer", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "No cash drawer configured", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun getPrinterStatus(): String {
            return if (printerManager.isConnected()) "connected" else "disconnected"
        }

        @JavascriptInterface
        fun getPaymentProviderStatus(): String {
            return if (paymentManager.isConfigured()) "configured" else "not_configured"
        }
    }

    private fun handlePaymentResult(result: PaymentResult, isCard: Boolean) {
        when (result) {
            is PaymentResult.Success -> {
                Log.d(TAG, "Payment successful: ${result.transactionId}")
                
                // Auto-print for card payments
                if (isCard && config.shouldAutoPrintCard()) {
                    // The receipt will be printed when the POS sends the receipt data
                    // via printReceipt() JavaScript call
                }
                
                // Notify POS
                notifyPaymentComplete(result)
            }
            is PaymentResult.Cancelled -> {
                Log.d(TAG, "Payment cancelled")
                notifyPaymentCancelled()
            }
            is PaymentResult.Failed -> {
                Log.e(TAG, "Payment failed: ${result.errorMessage}")
                notifyPaymentFailed(result.errorMessage)
            }
        }
    }

    private fun notifyPaymentComplete(result: PaymentResult.Success) {
        val script = """
            if (window.onPaymentComplete) {
                window.onPaymentComplete({
                    success: true,
                    transactionId: '${result.transactionId}',
                    amount: ${result.amount},
                    paymentMethod: '${result.paymentMethod}',
                    cardType: '${result.cardType ?: ""}',
                    cardLastFour: '${result.cardLastFour ?: ""}',
                    authCode: '${result.authCode ?: ""}'
                });
            }
        """.trimIndent()
        
        runOnUiThread {
            binding.webView.evaluateJavascript(script, null)
        }
    }

    private fun notifyPaymentCancelled() {
        val script = """
            if (window.onPaymentComplete) {
                window.onPaymentComplete({
                    success: false,
                    cancelled: true
                });
            }
        """.trimIndent()
        
        runOnUiThread {
            binding.webView.evaluateJavascript(script, null)
        }
    }

    private fun notifyPaymentFailed(errorMessage: String) {
        val script = """
            if (window.onPaymentComplete) {
                window.onPaymentComplete({
                    success: false,
                    error: '$errorMessage'
                });
            }
        """.trimIndent()
        
        runOnUiThread {
            binding.webView.evaluateJavascript(script, null)
        }
    }

    private fun showPaymentNotConfiguredDialog() {
        AlertDialog.Builder(this)
            .setTitle("Payment Not Configured")
            .setMessage("Card payment provider is not set up yet. Please configure SumUp or Zettle in Settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Handle SumUp results
        paymentManager.handleActivityResult(requestCode, resultCode, data)
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        // Always reconnect on resume — don't rely on isConnected() which returns a cached
        // value and can be stale (true) after the socket has actually been dropped.
        // connectToPrinter() calls disconnect() internally before reconnecting, so this is safe.
        val savedPrinter = config.getSavedPrinterAddress()
        if (savedPrinter != null) {
            scope.launch {
                val connected = printerManager.connectToPrinter(savedPrinter)
                Log.d(TAG, "onResume: Printer reconnect = $connected")
                updatePrinterStatus(connected)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        printerManager.disconnect()
        updateManager.cleanup()
    }
}
