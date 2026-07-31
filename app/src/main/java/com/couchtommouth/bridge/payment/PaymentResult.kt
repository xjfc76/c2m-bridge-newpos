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
 * The card reader SumUp has paired to this app.
 */
data class SavedCardReader(
    val type: String,
    val serialNumber: String,
    val batteryPercentage: Int?,
    val connected: Boolean
) {
    /** e.g. "Solo Lite ...123 (85%)" */
    fun describe(): String = buildString {
        append(type)
        if (serialNumber.isNotBlank()) append(" ...${serialNumber.takeLast(3)}")
        batteryPercentage?.let { append(" ($it%)") }
    }
}

/**
 * Enum for supported payment providers
 */
enum class PaymentProvider {
    NONE,
    SUMUP,
    ZETTLE
}
