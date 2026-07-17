package com.couchtommouth.bridge.payment

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import com.couchtommouth.bridge.BuildConfig
import com.couchtommouth.bridge.config.AppConfig
import com.sumup.merchant.reader.api.SumUpAPI
import com.sumup.merchant.reader.api.SumUpLogin
import com.sumup.merchant.reader.api.SumUpPayment
import com.sumup.merchant.reader.api.SumUpState
import java.math.BigDecimal
import java.util.UUID

/**
 * Manages card payment processing through SumUp.
 */
class PaymentManager(private val context: Context) {

    companion object {
        private const val TAG = "PaymentManager"
        const val SUMUP_LOGIN_REQUEST_CODE = 1001
        const val SUMUP_PAYMENT_REQUEST_CODE = 1002
        const val SUMUP_SETTINGS_REQUEST_CODE = 1003
    }

    private val config = AppConfig(context)
    private var pendingPaymentAmount: Double = 0.0
    private var pendingPaymentReference: String = ""
    var paymentCallback: ((PaymentResult) -> Unit)? = null

    init {
        // Initialize SumUp SDK
        initializeSumUp()
    }

    private fun initializeSumUp() {
        val affiliateKey = config.getSumUpAffiliateKey()
        if (affiliateKey.isNotEmpty()) {
            try {
                SumUpState.init(context)
                Log.d(TAG, "SumUp SDK initialized")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize SumUp SDK", e)
            }
        }
    }

    /**
     * Check if a payment provider is configured
     */
    fun isConfigured(): Boolean {
        return when (config.getPaymentProvider()) {
            PaymentProvider.SUMUP -> config.getSumUpAffiliateKey().isNotEmpty()
            PaymentProvider.ZETTLE -> config.getZettleClientId().isNotEmpty()
            PaymentProvider.NONE -> false
        }
    }

    /**
     * Check if logged into SumUp
     */
    fun isLoggedIn(): Boolean {
        return try {
            SumUpAPI.isLoggedIn()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Start SumUp login flow
     */
    fun login(activity: Activity) {
        val affiliateKey = config.getSumUpAffiliateKey()
        if (affiliateKey.isEmpty()) {
            Log.e(TAG, "No affiliate key configured")
            return
        }

        val loginIntent = SumUpLogin.builder(affiliateKey).build()
        SumUpAPI.openLoginActivity(activity, loginIntent, SUMUP_LOGIN_REQUEST_CODE)
    }

    /**
     * Open SumUp settings (for card reader pairing)
     */
    fun openSettings(activity: Activity) {
        SumUpAPI.openCardReaderPage(activity, SUMUP_SETTINGS_REQUEST_CODE)
    }

    /**
     * Process a card payment
     */
    fun processCardPayment(activity: Activity, amount: Double, reference: String, callback: (PaymentResult) -> Unit) {
        Log.d(TAG, "Processing card payment: £$amount, ref: $reference")

        when (config.getPaymentProvider()) {
            PaymentProvider.SUMUP -> processSumUpPayment(activity, amount, reference, callback)
            PaymentProvider.ZETTLE -> {
                callback(PaymentResult.Failed("Zettle not yet implemented"))
            }
            PaymentProvider.NONE -> {
                callback(PaymentResult.Failed("No payment provider configured"))
            }
        }
    }

    private fun processSumUpPayment(activity: Activity, amount: Double, reference: String, callback: (PaymentResult) -> Unit) {
        val affiliateKey = config.getSumUpAffiliateKey()
        
        if (affiliateKey.isEmpty()) {
            callback(PaymentResult.Failed("SumUp affiliate key not configured"))
            return
        }

        // Check if logged in
        if (!SumUpAPI.isLoggedIn()) {
            Log.d(TAG, "Not logged into SumUp, starting login flow")
            pendingPaymentAmount = amount
            pendingPaymentReference = reference
            paymentCallback = callback
            login(activity)
            return
        }

        // Store callback for result handling
        paymentCallback = callback
        pendingPaymentAmount = amount
        pendingPaymentReference = reference

        // The foreignTransactionId is the key that lets the POS reconcile a
        // lost callback (money taken but sale never recorded). On the new POS
        // (newpos flavor) we send the POS order reference itself so the backend
        // can look the transaction up by it. The live app keeps its old random
        // id so its behaviour is unchanged.
        val foreignTransactionId =
            if (BuildConfig.USE_REFERENCE_AS_FOREIGN_TX_ID && reference.isNotBlank()) {
                reference
            } else {
                "C2M-${UUID.randomUUID().toString().take(8)}"
            }

        // Build payment request
        val payment = SumUpPayment.builder()
            .total(BigDecimal.valueOf(amount))
            .currency(SumUpPayment.Currency.GBP)
            .title("CouchToMouth")
            .receiptEmail(null)  // Don't send email receipt
            .receiptSMS(null)    // Don't send SMS receipt
            .addAdditionalInfo("reference", reference)
            .foreignTransactionId(foreignTransactionId)
            .skipSuccessScreen() // Return to app immediately after payment
            .build()

        // Start payment
        Log.d(TAG, "Starting SumUp checkout for £$amount")
        SumUpAPI.checkout(activity, payment, SUMUP_PAYMENT_REQUEST_CODE)
    }

    /**
     * Handle activity result from SumUp
     */
    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            SUMUP_LOGIN_REQUEST_CODE -> handleLoginResult(resultCode, data)
            SUMUP_PAYMENT_REQUEST_CODE -> handlePaymentResult(resultCode, data)
            SUMUP_SETTINGS_REQUEST_CODE -> {
                Log.d(TAG, "Returned from SumUp settings")
            }
        }
    }

    private fun handleLoginResult(resultCode: Int, data: Intent?) {
        val extras = data?.extras
        val resultCode = extras?.getInt(SumUpAPI.Response.RESULT_CODE)
        val message = extras?.getString(SumUpAPI.Response.MESSAGE)

        Log.d(TAG, "Login result: code=$resultCode, message=$message")

        if (SumUpAPI.isLoggedIn()) {
            Log.d(TAG, "Successfully logged into SumUp")
            // If we have a pending payment, process it now
            if (pendingPaymentAmount > 0 && paymentCallback != null) {
                val activity = context as? Activity
                if (activity != null) {
                    processSumUpPayment(activity, pendingPaymentAmount, pendingPaymentReference, paymentCallback!!)
                }
            }
        } else {
            Log.e(TAG, "Login failed: $message")
            paymentCallback?.invoke(PaymentResult.Failed("Login failed: $message"))
            paymentCallback = null
        }
    }

    private fun handlePaymentResult(resultCode: Int, data: Intent?) {
        val extras = data?.extras
        val sumUpResultCode = extras?.getInt(SumUpAPI.Response.RESULT_CODE)
        val message = extras?.getString(SumUpAPI.Response.MESSAGE)
        val txCode = extras?.getString(SumUpAPI.Response.TX_CODE)
        val receiptSent = extras?.getBoolean(SumUpAPI.Response.RECEIPT_SENT) ?: false

        Log.d(TAG, "Payment result: code=$sumUpResultCode, message=$message, txCode=$txCode")

        val result = when (sumUpResultCode) {
            SumUpAPI.Response.ResultCode.SUCCESSFUL -> {
                PaymentResult.Success(
                    transactionId = txCode ?: "UNKNOWN",
                    amount = pendingPaymentAmount,
                    paymentMethod = "Card (SumUp)",
                    cardType = null,
                    cardLastFour = null,
                    authCode = null
                )
            }
            SumUpAPI.Response.ResultCode.ERROR_TRANSACTION_FAILED -> {
                PaymentResult.Failed(message ?: "Transaction failed")
            }
            SumUpAPI.Response.ResultCode.ERROR_GEOLOCATION_REQUIRED -> {
                PaymentResult.Failed("Location permission required")
            }
            SumUpAPI.Response.ResultCode.ERROR_INVALID_PARAM -> {
                PaymentResult.Failed("Invalid payment parameters")
            }
            SumUpAPI.Response.ResultCode.ERROR_INVALID_TOKEN -> {
                PaymentResult.Failed("Session expired - please log in again")
            }
            SumUpAPI.Response.ResultCode.ERROR_NO_CONNECTIVITY -> {
                PaymentResult.Failed("No internet connection")
            }
            SumUpAPI.Response.ResultCode.ERROR_PERMISSION_DENIED -> {
                PaymentResult.Failed("Permission denied")
            }
            SumUpAPI.Response.ResultCode.ERROR_NOT_LOGGED_IN -> {
                PaymentResult.Failed("Not logged in to SumUp")
            }
            SumUpAPI.Response.ResultCode.ERROR_DUPLICATE_FOREIGN_TX_ID -> {
                PaymentResult.Failed("Duplicate transaction")
            }
            SumUpAPI.Response.ResultCode.ERROR_INVALID_AFFILIATE_KEY -> {
                PaymentResult.Failed("Invalid affiliate key")
            }
            else -> {
                // Check if cancelled
                if (message?.contains("cancelled", ignoreCase = true) == true ||
                    message?.contains("canceled", ignoreCase = true) == true) {
                    PaymentResult.Cancelled
                } else {
                    PaymentResult.Failed(message ?: "Payment failed (code: $sumUpResultCode)")
                }
            }
        }

        // Clear pending payment
        pendingPaymentAmount = 0.0
        pendingPaymentReference = ""

        // Invoke callback
        paymentCallback?.invoke(result)
        paymentCallback = null
    }

    /**
     * Logout from SumUp
     */
    fun logout() {
        try {
            SumUpAPI.logout()
            Log.d(TAG, "Logged out of SumUp")
        } catch (e: Exception) {
            Log.e(TAG, "Error logging out", e)
        }
    }
}
