package com.couchtommouth.bridge.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Keep content clear of the status and navigation bars.
 *
 * Android 15 draws every app targeting SDK 35 edge-to-edge whether it asks to
 * or not. Without this the tablet's navigation bar sits on top of whatever is
 * at the bottom of the screen — the settings button, and the bottom strip of
 * the POS page inside the WebView.
 */
fun View.padForSystemBars() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
}
