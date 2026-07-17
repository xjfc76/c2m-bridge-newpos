package com.couchtommouth.bridge.payment

/**
 * Sealed class representing the result of a payment attempt
 */
sealed class PaymentResult {
    
    /**
     * Payment was successful
     */
    data class Success(
        val transactionId: String,
        val amount: Double,
        val paymentMethod: String,
        val cardType: String? = null,
        val cardLastFour: String? = null,
        val authCode: String? = null
    ) : PaymentResult()
    
    /**
     * Payment was cancelled by user
     */
    object Cancelled : PaymentResult()
    
    /**
     * Payment failed
     */
    data class Failed(
        val errorMessage: String,
        val errorCode: String? = null
    ) : PaymentResult()
}

/**
 * Enum for supported payment providers
 */
enum class PaymentProvider {
    NONE,
    SUMUP,
    ZETTLE
}
