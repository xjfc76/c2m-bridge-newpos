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
import com.sumup.merchant.reader.models.ReaderType
import com.sumup.merchant.reader.models.SavedCardReaderDetailsResult
import java.math.BigDecimal
import java.util.UUID

/**
 * Manages card payment processing through SumUp.
 * SumUp SDK init lives in [com.couchtommouth.bridge.BridgeApplication].
 */
class PaymentManager(private val context: Context) {

    companion object {
        private const val TAG = "PaymentManager"
        const val SUMUP_LOGIN_REQUEST_CODE = 1001
        const val SUMUP_PAYMENT_REQUEST_CODE = 1002
        const val SUMUP_SETTINGS_REQUEST_CODE = 1003
        const val SUMUP_TOKEN_LOGIN_REQUEST_CODE = 1004
    }

    private val config = AppConfig(context)
    private var pendingPaymentAmount: Double = 0.0
    private var pendingPaymentReference: String = ""
    var paymentCallback: ((PaymentResult) -> Unit)? = null

    /** Access tokens the POS server holds for us; see [SumUpSession]. */
    val sumUpSession = SumUpSession(context)

    /**
     * Set by [com.couchtommouth.bridge.ui.MainActivity]: fetch a token from the
     * POS and log the SDK in with it. The boolean asks for the SumUp password
     * screen as a fallback, which is wanted mid-sale but not on a quiet start-up.
     */
    var silentLoginRequest: ((Activity, Boolean) -> Unit)? = null

    /** One silent re-login per rejected token, so a bad token can't loop. */
    private var tokenRetryUsed = false

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
     * Start SumUp login flow (the password screen). The fallback for when
     * silent login can't be used — normally nobody should ever see this.
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
     * Log in with an access token the POS issued ("transparent authentication").
     * Shows no UI: the SDK returns straight to us through onActivityResult.
     */
    fun loginWithToken(activity: Activity, accessToken: String) {
        val affiliateKey = config.getSumUpAffiliateKey()
        if (affiliateKey.isEmpty()) {
            Log.e(TAG, "No affiliate key configured")
            return
        }

        val loginIntent = SumUpLogin.builder(affiliateKey).accessToken(accessToken).build()
        SumUpAPI.openLoginActivity(activity, loginIntent, SUMUP_TOKEN_LOGIN_REQUEST_CODE)
    }

    /**
     * The merchant the POS signed us in as, for the status line in Settings.
     * Comes from our own token response rather than the SDK, so it is only
     * known on a silently-logged-in session — null after a password login.
     */
    fun currentMerchantCode(): String? = sumUpSession.lastMerchantCode

    /**
     * Open SumUp settings (for card reader pairing)
     */
    fun openSettings(activity: Activity) {
        SumUpAPI.openCardReaderPage(activity, SUMUP_SETTINGS_REQUEST_CODE)
    }

    /**
     * The reader SumUp has paired to this app, or null when none is set up.
     * Survives the reader sleeping/going out of range — only pairing clears it.
     */
    fun savedCardReader(): SavedCardReader? {
        return try {
            val details = SumUpAPI.getSavedCardReaderDetails()
                as? SavedCardReaderDetailsResult.SavedCardReaderDetails ?: return null
            SavedCardReader(
                type = readerTypeName(details.readerType),
                serialNumber = details.serialNumber.orEmpty(),
                batteryPercentage = details.lastKnownBatteryPercentage,
                connected = isCardReaderConnected()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not read saved card reader details", e)
            null
        }
    }

    /** Whether the reader is connected over Bluetooth right now (it sleeps between sales). */
    fun isCardReaderConnected(): Boolean {
        return try {
            SumUpAPI.isCardReaderConnected()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * What the POS asks about before offering a card sale: logged in with a reader
     * paired. A sleeping reader still counts — [prepareForCheckout] wakes it.
     */
    fun isCardTerminalReady(): Boolean {
        if (!isConfigured()) return false
        if (config.getPaymentProvider() != PaymentProvider.SUMUP) return true
        return isLoggedIn() && savedCardReader() != null
    }

    /**
     * Reconnect the paired reader ahead of a sale. Must run on the main thread —
     * the SDK builds a Handler internally.
     */
    fun prepareForCheckout() {
        if (!isLoggedIn() || savedCardReader() == null) return
        try {
            SumUpAPI.prepareForCheckout()
        } catch (e: Exception) {
            Log.e(TAG, "prepareForCheckout failed", e)
        }
    }

    private fun readerTypeName(type: ReaderType?): String = when (type) {
        ReaderType.SOLO -> "Solo"
        ReaderType.SOLO_LITE -> "Solo Lite"
        ReaderType.AIR -> "Air"
        ReaderType.THREE_G -> "3G"
        ReaderType.PIN_PLUS -> "PIN+"
        else -> "Card reader"
    }

    /**
     * Process a card payment
     */
    fun processCardPayment(activity: Activity, amount: Double, reference: String, callback: (PaymentResult) -> Unit) {
        Log.d(TAG, "Processing card payment: £$amount, ref: $reference")

        // One automatic re-authentication per sale.
        tokenRetryUsed = false

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

        // Not logged in: hold the sale, sign in, and let handleLoginResult
        // resume it. Silent login first; the password screen only if the POS
        // can't supply a token.
        if (!SumUpAPI.isLoggedIn()) {
            Log.d(TAG, "Not logged into SumUp, signing in before the sale")
            pendingPaymentAmount = amount
            pendingPaymentReference = reference
            paymentCallback = callback
            val silent = silentLoginRequest
            if (silent != null) {
                silent(activity, true)
            } else {
                login(activity)
            }
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
            SUMUP_LOGIN_REQUEST_CODE -> handleLoginResult(resultCode, data, silent = false)
            SUMUP_TOKEN_LOGIN_REQUEST_CODE -> handleLoginResult(resultCode, data, silent = true)
            SUMUP_PAYMENT_REQUEST_CODE -> handlePaymentResult(resultCode, data)
            SUMUP_SETTINGS_REQUEST_CODE -> {
                Log.d(TAG, "Returned from SumUp settings")
            }
        }
    }

    private fun handleLoginResult(resultCode: Int, data: Intent?, silent: Boolean) {
        val extras = data?.extras
        val sumUpResultCode = extras?.getInt(SumUpAPI.Response.RESULT_CODE)
        val message = extras?.getString(SumUpAPI.Response.MESSAGE)
        val kind = if (silent) "token login" else "login"

        Log.d(TAG, "$kind result: code=$sumUpResultCode, message=$message")

        if (SumUpAPI.isLoggedIn()) {
            Log.d(TAG, "Signed into SumUp via $kind")
            // If we have a pending payment, process it now
            if (pendingPaymentAmount > 0 && paymentCallback != null) {
                val activity = context as? Activity
                if (activity != null) {
                    processSumUpPayment(activity, pendingPaymentAmount, pendingPaymentReference, paymentCallback!!)
                }
            }
            return
        }

        Log.e(TAG, "$kind failed: $message")
        if (silent) {
            // The token was refused (expired, wrong scopes, revoked). Bin it so
            // the next attempt fetches a fresh one rather than replaying this.
            sumUpSession.invalidate()
            // Mid-sale we still have to take the money: fall back to the
            // password screen, which resumes the held payment on success.
            if (pendingPaymentAmount > 0 && paymentCallback != null) {
                val activity = context as? Activity
                if (activity != null) {
                    Log.w(TAG, "Silent login rejected mid-sale; falling back to the SumUp login screen")
                    login(activity)
                    return
                }
            }
            // Nothing waiting on it — stay quiet and try again next launch.
            return
        }
        paymentCallback?.invoke(PaymentResult.Failed("Login failed: $message"))
        paymentCallback = null
    }

    private fun handlePaymentResult(resultCode: Int, data: Intent?) {
        val extras = data?.extras
        val sumUpResultCode = extras?.getInt(SumUpAPI.Response.RESULT_CODE)
        val message = extras?.getString(SumUpAPI.Response.MESSAGE)
        val txCode = extras?.getString(SumUpAPI.Response.TX_CODE)
        val receiptSent = extras?.getBoolean(SumUpAPI.Response.RECEIPT_SENT) ?: false

        Log.d(TAG, "Payment result: code=$sumUpResultCode, message=$message, txCode=$txCode")

        // A session SumUp no longer accepts, mid-sale. Fetch a fresh token and
        // retry once: the pending amount/reference are still held, so
        // handleLoginResult picks the sale back up. Staff see the reader wake
        // up again rather than an error at the counter.
        if (sumUpResultCode == SumUpAPI.Response.ResultCode.ERROR_INVALID_TOKEN && !tokenRetryUsed) {
            val activity = context as? Activity
            val silent = silentLoginRequest
            if (activity != null && silent != null && paymentCallback != null) {
                tokenRetryUsed = true
                sumUpSession.invalidate()
                Log.w(TAG, "SumUp rejected the session mid-sale; re-authenticating and retrying")
                silent(activity, true)
                return
            }
        }

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
            // Drop the held token too, so signing back in fetches a fresh one.
            sumUpSession.invalidate()
            Log.d(TAG, "Logged out of SumUp")
        } catch (e: Exception) {
            Log.e(TAG, "Error logging out", e)
        }
    }
}
