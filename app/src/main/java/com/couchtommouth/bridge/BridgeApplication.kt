package com.couchtommouth.bridge

import android.app.Application
import android.util.Log
import com.sumup.merchant.reader.api.SumUpState

/**
 * Application entry point. SumUp requires [SumUpState.init] here (not in an Activity)
 * so the card-reader settings page can render and connect properly.
 */
class BridgeApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            SumUpState.init(this)
            Log.d(TAG, "SumUp SDK initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SumUp SDK", e)
        }
    }

    companion object {
        private const val TAG = "BridgeApplication"
    }
}
