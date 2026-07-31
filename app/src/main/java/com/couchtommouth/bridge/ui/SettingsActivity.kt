package com.couchtommouth.bridge.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.couchtommouth.bridge.BuildConfig
import com.couchtommouth.bridge.R
import com.couchtommouth.bridge.config.AppConfig
import com.couchtommouth.bridge.databinding.ActivitySettingsBinding
import com.couchtommouth.bridge.databinding.ViewSettingRowBinding
import com.couchtommouth.bridge.payment.PaymentManager
import com.couchtommouth.bridge.payment.PaymentProvider

/**
 * Device settings for one till.
 *
 * Everything is edited through a dialog rather than typed into the page, and
 * each dialog saves as it closes — a live till should not have free-text boxes
 * one stray tap away from repointing it at the wrong POS, and there is no
 * unsaved state to lose if someone walks away mid-edit.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var config: AppConfig
    private lateinit var paymentManager: PaymentManager
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()

        config = AppConfig(this)
        paymentManager = PaymentManager(this)

        binding.tvVersion.text =
            "C2M POS bridge ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        setupListeners()
        refresh()

        // The SumUp SDK settles its login/pairing state a moment after start.
        handler.postDelayed({ updateSumUpStatus() }, 300)
    }

    // --- Wiring ---------------------------------------------------------

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnSetupPrinter.setOnClickListener {
            startActivity(Intent(this, PrinterSetupActivity::class.java))
        }

        binding.switchCashDrawer.setOnCheckedChangeListener { _, isChecked ->
            config.setCashDrawer(isChecked)
        }
        binding.switchPaperCutter.setOnCheckedChangeListener { _, isChecked ->
            config.setPaperCutter(isChecked)
        }
        binding.switchAutoPrintCard.setOnCheckedChangeListener { _, isChecked ->
            config.setAutoPrintCard(isChecked)
        }
        binding.switchAutoPrintCash.setOnCheckedChangeListener { _, isChecked ->
            config.setAutoPrintCash(isChecked)
        }

        binding.btnSumUpLogin.setOnClickListener {
            if (paymentManager.isLoggedIn()) {
                confirmSignOut()
            } else {
                paymentManager.login(this)
            }
        }

        binding.btnSumUpCardReader.setOnClickListener {
            if (!paymentManager.isLoggedIn()) {
                toast("Sign in to SumUp first")
                return@setOnClickListener
            }
            paymentManager.openSettings(this)
        }
    }

    /** Redraw every value from config. Cheap, so it runs on any change. */
    private fun refresh() {
        binding.tvPrinterName.text = config.getSavedPrinterName() ?: "Not set up"
        binding.switchCashDrawer.isChecked = config.hasCashDrawer()
        binding.switchPaperCutter.isChecked = config.hasPaperCutter()
        binding.switchAutoPrintCard.isChecked = config.shouldAutoPrintCard()
        binding.switchAutoPrintCash.isChecked = config.shouldAutoPrintCash()

        bindRow(binding.rowShopName, "Shop name", config.getShopName()) {
            editValue("Shop name", config.getShopName(), "Couch to Mouth") {
                config.setShopName(it)
            }
        }
        bindRow(binding.rowShopAddress, "Address", config.getShopAddress()) {
            editValue("Address", config.getShopAddress(), "12 High Street") {
                config.setShopAddress(it)
            }
        }
        bindRow(binding.rowShopPhone, "Phone", config.getShopPhone()) {
            editValue(
                "Phone",
                config.getShopPhone(),
                "01234 567890",
                InputType.TYPE_CLASS_PHONE,
            ) { config.setShopPhone(it) }
        }

        bindRow(binding.rowPosUrl, "POS address", config.getPosUrl()) {
            editValue(
                "POS address",
                config.getPosUrl(),
                "https://…",
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
                required = true,
            ) { config.setPosUrl(it) }
        }
        bindRow(
            binding.rowPaymentProvider,
            "Card payment provider",
            providerName(config.getPaymentProvider()),
        ) { chooseProvider() }
        bindRow(binding.rowAffiliateKey, "SumUp affiliate key", config.getSumUpAffiliateKey()) {
            editValue(
                "SumUp affiliate key",
                config.getSumUpAffiliateKey(),
                "sup_afk_…",
                required = true,
            ) { config.setSumUpAffiliateKey(it) }
        }
        bindRow(binding.rowAppId, "SumUp app ID", config.getSumUpAppId()) {
            editValue(
                "SumUp app ID",
                config.getSumUpAppId(),
                "CouchToMouth POS",
                required = true,
            ) { config.setSumUpAppId(it) }
        }

        updateSumUpStatus()
    }

    // --- Rows + dialogs -------------------------------------------------

    private fun bindRow(
        row: ViewSettingRowBinding,
        label: String,
        value: String?,
        onEdit: () -> Unit,
    ) {
        row.tvRowLabel.text = label
        val blank = value.isNullOrBlank()
        row.tvRowValue.text = if (blank) "Not set" else value
        row.tvRowValue.setTextColor(
            ContextCompat.getColor(
                this,
                if (blank) R.color.text_secondary else R.color.text_primary,
            )
        )
        row.root.setOnClickListener { onEdit() }
        row.btnRowEdit.setOnClickListener { onEdit() }
    }

    private fun editValue(
        title: String,
        current: String,
        hint: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        required: Boolean = false,
        onSave: (String) -> Unit,
    ) {
        val input = EditText(this).apply {
            setText(current)
            this.hint = hint
            setSingleLine()
            this.inputType = inputType
            setSelection(text?.length ?: 0)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val entered = input.text.toString().trim()
                // Blanking the POS address or the SumUp keys takes the till off
                // the air, and it isn't obvious from here that it has.
                if (required && entered.isEmpty()) {
                    toast("$title can't be empty")
                    return@setPositiveButton
                }
                onSave(entered)
                refresh()
                toast("$title saved")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Zettle is deliberately absent: the checkout path for it isn't built, so
     * offering it would only ever produce "not yet implemented" at the counter.
     */
    private fun chooseProvider() {
        val options = arrayOf(PaymentProvider.SUMUP, PaymentProvider.NONE)
        val labels: Array<CharSequence> = options.map { providerName(it) as CharSequence }
            .toTypedArray()
        // -1 leaves nothing ticked, which is right if the till is still on Zettle.
        val current = options.indexOf(config.getPaymentProvider())

        AlertDialog.Builder(this)
            .setTitle("Card payment provider")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                config.setPaymentProvider(options[which])
                dialog.dismiss()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmSignOut() {
        AlertDialog.Builder(this)
            .setTitle("Sign out of SumUp?")
            .setMessage(
                "The till signs itself back in automatically, so this is only " +
                    "useful for switching to a different SumUp account."
            )
            .setPositiveButton("Sign out") { _, _ ->
                paymentManager.logout()
                updateSumUpStatus()
                toast("Signed out of SumUp")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun providerName(provider: PaymentProvider): String = when (provider) {
        PaymentProvider.SUMUP -> "SumUp"
        PaymentProvider.ZETTLE -> "Zettle (PayPal)"
        PaymentProvider.NONE -> "None — cash only"
    }

    // --- Status ---------------------------------------------------------

    private fun updateSumUpStatus() {
        val isLoggedIn = paymentManager.isLoggedIn()
        val merchantCode = paymentManager.currentMerchantCode()

        binding.tvSumUpStatus.text = when {
            // Signed in off a token from the POS: nobody typed a password.
            isLoggedIn && merchantCode != null -> "✓ Signed in automatically ($merchantCode)"
            isLoggedIn -> "✓ Signed in to SumUp"
            else -> "Not signed in — the till signs itself in once the POS is open"
        }
        binding.tvSumUpStatus.setTextColor(statusColor(isLoggedIn))
        binding.btnSumUpLogin.text = if (isLoggedIn) "Sign out of SumUp" else "Sign in to SumUp"
        binding.btnSumUpCardReader.isEnabled = isLoggedIn

        val reader = if (isLoggedIn) paymentManager.savedCardReader() else null
        binding.tvCardReaderStatus.text = when {
            !isLoggedIn -> "Card reader: sign in first"
            reader == null -> "No card reader paired"
            reader.connected -> "✓ Reader connected — ${reader.describe()}"
            // Solo/Solo Lite drop the BLE link when idle; it wakes for the sale.
            else -> "Reader paired — ${reader.describe()}, asleep until a sale"
        }
        binding.tvCardReaderStatus.setTextColor(statusColor(reader?.connected == true))

        // Wake the reader so the status settles on connected without a sale.
        if (reader != null && !reader.connected) {
            paymentManager.prepareForCheckout()
        }
    }

    private fun statusColor(good: Boolean): Int = ContextCompat.getColor(
        this,
        if (good) R.color.success else R.color.text_secondary,
    )

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        handler.postDelayed({ updateSumUpStatus() }, 300)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        paymentManager.handleActivityResult(requestCode, resultCode, data)
        updateSumUpStatus()
        // Pairing/login state can land a moment after the SumUp screen closes.
        handler.postDelayed({ updateSumUpStatus() }, 1_500)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
