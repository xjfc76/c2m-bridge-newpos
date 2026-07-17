package com.couchtommouth.bridge.config

import android.content.Context
import android.content.SharedPreferences
import com.couchtommouth.bridge.BuildConfig
import com.couchtommouth.bridge.payment.PaymentProvider

/**
 * Manages app configuration and settings stored in SharedPreferences.
 */
class AppConfig(context: Context) {

    companion object {
        private const val PREFS_NAME = "couch2mouth_bridge_prefs"
        
        // Keys
        private const val KEY_POS_URL = "pos_url"
        private const val KEY_PRINTER_ADDRESS = "printer_address"
        private const val KEY_PRINTER_NAME = "printer_name"
        private const val KEY_HAS_CASH_DRAWER = "has_cash_drawer"
        private const val KEY_HAS_PAPER_CUTTER = "has_paper_cutter"
        private const val KEY_PAYMENT_PROVIDER = "payment_provider"
        private const val KEY_SUMUP_AFFILIATE_KEY = "sumup_affiliate_key"
        private const val KEY_SUMUP_APP_ID = "sumup_app_id"
        private const val KEY_ZETTLE_CLIENT_ID = "zettle_client_id"
        private const val KEY_AUTO_PRINT_CARD = "auto_print_card"
        private const val KEY_AUTO_PRINT_CASH = "auto_print_cash"
        private const val KEY_SHOP_NAME = "shop_name"
        private const val KEY_SHOP_ADDRESS = "shop_address"
        private const val KEY_SHOP_PHONE = "shop_phone"
    }

    private val prefs: SharedPreferences = 
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ============ POS URL ============
    
    fun getPosUrl(): String {
        return prefs.getString(KEY_POS_URL, BuildConfig.POS_URL) ?: BuildConfig.POS_URL
    }
    
    fun setPosUrl(url: String) {
        prefs.edit().putString(KEY_POS_URL, url).apply()
    }

    // ============ Printer Settings ============
    
    fun getSavedPrinterAddress(): String? {
        return prefs.getString(KEY_PRINTER_ADDRESS, null)
    }
    
    fun getSavedPrinterName(): String? {
        return prefs.getString(KEY_PRINTER_NAME, null)
    }
    
    fun savePrinter(address: String, name: String) {
        prefs.edit()
            .putString(KEY_PRINTER_ADDRESS, address)
            .putString(KEY_PRINTER_NAME, name)
            .apply()
    }
    
    fun clearPrinter() {
        prefs.edit()
            .remove(KEY_PRINTER_ADDRESS)
            .remove(KEY_PRINTER_NAME)
            .apply()
    }
    
    fun hasCashDrawer(): Boolean {
        return prefs.getBoolean(KEY_HAS_CASH_DRAWER, false)
    }
    
    fun setCashDrawer(hasCashDrawer: Boolean) {
        prefs.edit().putBoolean(KEY_HAS_CASH_DRAWER, hasCashDrawer).apply()
    }
    
    fun hasPaperCutter(): Boolean {
        return prefs.getBoolean(KEY_HAS_PAPER_CUTTER, true)
    }
    
    fun setPaperCutter(hasCutter: Boolean) {
        prefs.edit().putBoolean(KEY_HAS_PAPER_CUTTER, hasCutter).apply()
    }

    // ============ Payment Provider ============
    
    fun getPaymentProvider(): PaymentProvider {
        // Default to SUMUP since it's configured
        val providerName = prefs.getString(KEY_PAYMENT_PROVIDER, PaymentProvider.SUMUP.name)
        return try {
            PaymentProvider.valueOf(providerName ?: PaymentProvider.SUMUP.name)
        } catch (e: Exception) {
            PaymentProvider.SUMUP
        }
    }
    
    fun setPaymentProvider(provider: PaymentProvider) {
        prefs.edit().putString(KEY_PAYMENT_PROVIDER, provider.name).apply()
    }
    
    // SumUp
    fun getSumUpAffiliateKey(): String {
        return prefs.getString(KEY_SUMUP_AFFILIATE_KEY, BuildConfig.SUMUP_AFFILIATE_KEY) 
            ?: BuildConfig.SUMUP_AFFILIATE_KEY
    }
    
    fun setSumUpAffiliateKey(key: String) {
        prefs.edit().putString(KEY_SUMUP_AFFILIATE_KEY, key).apply()
    }
    
    fun getSumUpAppId(): String {
        return prefs.getString(KEY_SUMUP_APP_ID, BuildConfig.SUMUP_APP_ID) 
            ?: BuildConfig.SUMUP_APP_ID
    }
    
    fun setSumUpAppId(appId: String) {
        prefs.edit().putString(KEY_SUMUP_APP_ID, appId).apply()
    }
    
    // Zettle
    fun getZettleClientId(): String {
        return prefs.getString(KEY_ZETTLE_CLIENT_ID, "") ?: ""
    }
    
    fun setZettleClientId(clientId: String) {
        prefs.edit().putString(KEY_ZETTLE_CLIENT_ID, clientId).apply()
    }

    // ============ Print Settings ============
    
    fun shouldAutoPrintCard(): Boolean {
        return prefs.getBoolean(KEY_AUTO_PRINT_CARD, BuildConfig.AUTO_PRINT_CARD)
    }
    
    fun setAutoPrintCard(autoPrint: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_PRINT_CARD, autoPrint).apply()
    }
    
    fun shouldAutoPrintCash(): Boolean {
        return prefs.getBoolean(KEY_AUTO_PRINT_CASH, BuildConfig.AUTO_PRINT_CASH)
    }
    
    fun setAutoPrintCash(autoPrint: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_PRINT_CASH, autoPrint).apply()
    }

    // ============ Shop Info ============
    
    fun getShopName(): String {
        return prefs.getString(KEY_SHOP_NAME, BuildConfig.SHOP_NAME) ?: BuildConfig.SHOP_NAME
    }
    
    fun setShopName(name: String) {
        prefs.edit().putString(KEY_SHOP_NAME, name).apply()
    }
    
    fun getShopAddress(): String {
        return prefs.getString(KEY_SHOP_ADDRESS, "") ?: ""
    }
    
    fun setShopAddress(address: String) {
        prefs.edit().putString(KEY_SHOP_ADDRESS, address).apply()
    }
    
    fun getShopPhone(): String {
        return prefs.getString(KEY_SHOP_PHONE, "") ?: ""
    }
    
    fun setShopPhone(phone: String) {
        prefs.edit().putString(KEY_SHOP_PHONE, phone).apply()
    }
}
