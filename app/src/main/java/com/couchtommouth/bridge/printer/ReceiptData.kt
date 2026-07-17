package com.couchtommouth.bridge.printer

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Data class representing a receipt to be printed.
 * Can be created from JSON sent by the POS web app.
 */
data class ReceiptData(
    @SerializedName("shop_name")
    val shopName: String = "",
    
    @SerializedName("shop_address")
    val shopAddress: String = "",
    
    @SerializedName("shop_phone")
    val shopPhone: String = "",
    
    @SerializedName("receipt_number")
    val receiptNumber: String = "",
    
    @SerializedName("date_time")
    val dateTime: String = "",
    
    @SerializedName("order_type")
    val orderType: String = "",
    
    @SerializedName("employee")
    val employee: String = "",
    
    @SerializedName("customer_name")
    val customerName: String = "",
    
    @SerializedName("customer_phone")
    val customerPhone: String = "",
    
    @SerializedName("delivery_address")
    val deliveryAddress: String = "",
    
    @SerializedName("is_delivery")
    val isDelivery: Boolean = false,
    
    @SerializedName("items")
    val items: List<ReceiptItem> = emptyList(),
    
    @SerializedName("subtotal")
    val subtotal: Double = 0.0,
    
    @SerializedName("discount")
    val discount: Double = 0.0,
    
    @SerializedName("tax")
    val tax: Double = 0.0,
    
    @SerializedName("total")
    val total: Double = 0.0,
    
    @SerializedName("payment_method")
    val paymentMethod: String = "",
    
    @SerializedName("amount_paid")
    val amountPaid: Double = 0.0,
    
    @SerializedName("change")
    val change: Double = 0.0,
    
    // Card payment details (populated after card payment)
    @SerializedName("card_type")
    val cardType: String? = null,
    
    @SerializedName("card_last_four")
    val cardLastFour: String? = null,
    
    @SerializedName("auth_code")
    val authCode: String? = null,
    
    @SerializedName("transaction_id")
    val transactionId: String? = null,
    
    // Kitchen ticket mode - hides prices when false
    @SerializedName("show_prices")
    val showPrices: Boolean = true,
    
    // Web-controlled receipt lines (new system)
    @SerializedName("use_lines")
    val useLines: Boolean = false,
    
    @SerializedName("lines")
    val lines: List<ReceiptLine> = emptyList()
) {
    companion object {
        private val gson = Gson()
        
        fun fromJson(json: String): ReceiptData {
            return gson.fromJson(json, ReceiptData::class.java)
        }
    }
    
    fun toJson(): String = gson.toJson(this)
}

/**
 * A single item on the receipt
 */
data class ReceiptItem(
    @SerializedName("name")
    val name: String = "",
    
    @SerializedName("quantity")
    val quantity: Int = 1,
    
    @SerializedName("price")
    val price: Double = 0.0,
    
    @SerializedName("modifiers")
    val modifiers: List<ReceiptModifier> = emptyList()
)

/**
 * A modifier on an item
 */
data class ReceiptModifier(
    @SerializedName("name")
    val name: String = "",
    
    @SerializedName("option")
    val option: String = "",
    
    @SerializedName("price")
    val price: Double = 0.0
)

/**
 * A single line on the receipt (web-controlled formatting)
 * 
 * Edit the JavaScript (print-bridge.js) to change receipt layout.
 * No app rebuild needed after this version!
 */
data class ReceiptLine(
    @SerializedName("text")
    val text: String = "",
    
    @SerializedName("bold")
    val bold: Boolean = false,
    
    @SerializedName("big")
    val big: Boolean = false,
    
    @SerializedName("center")
    val center: Boolean = false,
    
    @SerializedName("price")
    val price: String? = null,
    
    @SerializedName("logo")
    val logo: Boolean = false
)
