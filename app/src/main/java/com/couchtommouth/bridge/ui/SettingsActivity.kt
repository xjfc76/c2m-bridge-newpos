package com.couchtommouth.bridge.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.couchtommouth.bridge.config.AppConfig
import com.couchtommouth.bridge.databinding.ActivitySettingsBinding
import com.couchtommouth.bridge.payment.PaymentManager
import com.couchtommouth.bridge.payment.PaymentProvider

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var config: AppConfig
    private lateinit var paymentManager: PaymentManager
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        config = AppConfig(this)
        paymentManager = PaymentManager(this)

        loadSettings()
        setupListeners()
        
        // Delay status check to allow SumUp SDK to fully initialize
        handler.postDelayed({ updateSumUpStatus() }, 300)
    }

    private fun loadSettings() {
        // POS URL
        binding.etPosUrl.setText(config.getPosUrl())

        // Printer
        val printerName = config.getSavedPrinterName()
        binding.tvPrinterName.text = printerName ?: "Not configured"
        binding.switchCashDrawer.isChecked = config.hasCashDrawer()
        binding.switchPaperCutter.isChecked = config.hasPaperCutter()

        // Payment Provider - set the radio button and update UI visibility
        val currentProvider = config.getPaymentProvider()
        when (currentProvider) {
            PaymentProvider.NONE -> binding.rgPaymentProvider.check(binding.rbNone.id)
            PaymentProvider.SUMUP -> binding.rgPaymentProvider.check(binding.rbSumUp.id)
            PaymentProvider.ZETTLE -> binding.rgPaymentProvider.check(binding.rbZettle.id)
        }
        // Update UI visibility immediately (fixes needing to click away and back)
        updatePaymentProviderUI(currentProvider)

        // SumUp credentials
        binding.etSumUpAffiliateKey.setText(config.getSumUpAffiliateKey())
        binding.etSumUpAppId.setText(config.getSumUpAppId())

        // Zettle credentials
        binding.etZettleClientId.setText(config.getZettleClientId())

        // Print settings
        binding.switchAutoPrintCard.isChecked = config.shouldAutoPrintCard()
        binding.switchAutoPrintCash.isChecked = config.shouldAutoPrintCash()

        // Shop info
        binding.etShopName.setText(config.getShopName())
        binding.etShopAddress.setText(config.getShopAddress())
        binding.etShopPhone.setText(config.getShopPhone())
    }

    private fun setupListeners() {
        // Printer setup button
        binding.btnSetupPrinter.setOnClickListener {
            startActivity(Intent(this, PrinterSetupActivity::class.java))
        }

        // Cash drawer toggle
        binding.switchCashDrawer.setOnCheckedChangeListener { _, isChecked ->
            config.setCashDrawer(isChecked)
        }

        // Paper cutter toggle
        binding.switchPaperCutter.setOnCheckedChangeListener { _, isChecked ->
            config.setPaperCutter(isChecked)
        }

        // Payment provider selection
        binding.rgPaymentProvider.setOnCheckedChangeListener { _, checkedId ->
            val provider = when (checkedId) {
                binding.rbSumUp.id -> PaymentProvider.SUMUP
                binding.rbZettle.id -> PaymentProvider.ZETTLE
                else -> PaymentProvider.NONE
            }
            config.setPaymentProvider(provider)
            updatePaymentProviderUI(provider)
        }

        // SumUp login button
        binding.btnSumUpLogin.setOnClickListener {
            if (paymentManager.isLoggedIn()) {
                paymentManager.logout()
                updateSumUpStatus()
                Toast.makeText(this, "Logged out of SumUp", Toast.LENGTH_SHORT).show()
            } else {
                paymentManager.login(this)
            }
        }

        // SumUp card reader pairing
        binding.btnSumUpCardReader.setOnClickListener {
            if (!paymentManager.isLoggedIn()) {
                Toast.makeText(this, "Please login to SumUp first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            paymentManager.openSettings(this)
        }

        // Auto print toggles
        binding.switchAutoPrintCard.setOnCheckedChangeListener { _, isChecked ->
            config.setAutoPrintCard(isChecked)
        }
        binding.switchAutoPrintCash.setOnCheckedChangeListener { _, isChecked ->
            config.setAutoPrintCash(isChecked)
        }

        // Save button
        binding.btnSave.setOnClickListener {
            saveSettings()
        }

        // Back button
        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    private fun updatePaymentProviderUI(provider: PaymentProvider) {
        // Show/hide relevant credential fields
        binding.layoutSumUp.visibility = 
            if (provider == PaymentProvider.SUMUP) android.view.View.VISIBLE else android.view.View.GONE
        binding.layoutZettle.visibility = 
            if (provider == PaymentProvider.ZETTLE) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun saveSettings() {
        // Save POS URL
        val posUrl = binding.etPosUrl.text.toString().trim()
        if (posUrl.isNotEmpty()) {
            config.setPosUrl(posUrl)
        }

        // Save SumUp credentials
        config.setSumUpAffiliateKey(binding.etSumUpAffiliateKey.text.toString().trim())
        config.setSumUpAppId(binding.etSumUpAppId.text.toString().trim())

        // Save Zettle credentials
        config.setZettleClientId(binding.etZettleClientId.text.toString().trim())

        // Save shop info
        config.setShopName(binding.etShopName.text.toString().trim())
        config.setShopAddress(binding.etShopAddress.text.toString().trim())
        config.setShopPhone(binding.etShopPhone.text.toString().trim())

        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onResume() {
        super.onResume()
        // Refresh printer info when returning from PrinterSetupActivity
        val printerName = config.getSavedPrinterName()
        binding.tvPrinterName.text = printerName ?: "Not configured"
        
        // Refresh SumUp status with delay to ensure SDK is ready
        handler.postDelayed({ updateSumUpStatus() }, 300)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        paymentManager.handleActivityResult(requestCode, resultCode, data)
        updateSumUpStatus()
    }

    private fun updateSumUpStatus() {
        val isLoggedIn = paymentManager.isLoggedIn()
        binding.tvSumUpStatus.text = if (isLoggedIn) {
            "Status: ✓ Logged in"
        } else {
            "Status: Not logged in"
        }
        binding.btnSumUpLogin.text = if (isLoggedIn) "Logout" else "Login to SumUp"
        binding.btnSumUpCardReader.isEnabled = isLoggedIn
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up handler callbacks to prevent memory leaks
        handler.removeCallbacksAndMessages(null)
    }
}
