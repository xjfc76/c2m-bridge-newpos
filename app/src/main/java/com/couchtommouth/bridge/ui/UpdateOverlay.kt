package com.couchtommouth.bridge.ui

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import com.couchtommouth.bridge.BuildConfig
import com.couchtommouth.bridge.databinding.ViewUpdateOverlayBinding
import com.couchtommouth.bridge.update.UpdateManager

/**
 * The update experience: one full-screen panel over a blurred, dimmed till.
 *
 * An update used to announce itself in a stock dialog and then download behind
 * a second dialog, with the POS still live underneath — on a counter tablet
 * that reads as something going wrong rather than something being installed.
 * Here the whole screen is claimed for the duration: the page behind is blurred
 * out of use, every touch is swallowed, and there is exactly one thing to do at
 * each step.
 */
class UpdateOverlay(
    private val binding: ViewUpdateOverlayBinding,
    private val blurred: List<View>,
) : UpdateManager.UpdateUi {

    private companion object {
        // Enough blur that no one tries to read an order through it.
        const val BLUR_RADIUS_PX = 28f
    }

    // --- UpdateUi -------------------------------------------------------

    override fun onUpdateAvailable(info: UpdateManager.VersionInfo, install: () -> Unit) {
        show()
        binding.tvUpdateTitle.text = "Update available"
        binding.tvUpdateVersion.text =
            "Version ${info.versionName} — this till is on ${BuildConfig.VERSION_NAME}"
        binding.tvUpdateVersion.visibility = View.VISIBLE
        setNotes(info.releaseNotes)
        setWorking(false)
        buttons(
            primaryLabel = "Update now",
            onPrimary = install,
            secondaryLabel = "Not now",
            onSecondary = { hide() },
        )
    }

    override fun onDownloadProgress(percent: Int) {
        show()
        binding.tvUpdateTitle.text = "Updating the till"
        binding.tvUpdateVersion.visibility = View.GONE
        setNotes(null)
        setWorking(true)
        binding.progressUpdate.isIndeterminate = false
        binding.progressUpdate.progress = percent
        binding.tvUpdateStatus.text = "Downloading… $percent%"
        buttons(null, {}, null, {})
    }

    override fun onInstallStarting() {
        show()
        binding.tvUpdateTitle.text = "Updating the till"
        binding.tvUpdateVersion.visibility = View.GONE
        setNotes(null)
        setWorking(true)
        binding.progressUpdate.isIndeterminate = true
        binding.tvUpdateStatus.text = "Opening the installer…"
        buttons(null, {}, null, {})
    }

    /**
     * Android's installer is a separate screen we can't drive. Leave the panel
     * up behind it so the till isn't half-usable while it's open, but give a
     * way out — otherwise backing out of the install would strand the screen.
     */
    override fun onInstallHandedOver() {
        show()
        binding.tvUpdateTitle.text = "Finish the install"
        binding.tvUpdateVersion.visibility = View.GONE
        setNotes("Android will ask you to confirm. The till restarts on the new version once it's done.")
        setWorking(false)
        buttons(
            primaryLabel = "Close",
            onPrimary = { hide() },
            secondaryLabel = null,
            onSecondary = {},
        )
    }

    override fun onInstallPermissionNeeded(openSettings: () -> Unit) {
        show()
        binding.tvUpdateTitle.text = "Permission needed"
        binding.tvUpdateVersion.visibility = View.GONE
        setNotes(
            "Android needs this app to be allowed to install updates. " +
                "Turn the switch on, come back, then tap Update now again."
        )
        setWorking(false)
        buttons(
            primaryLabel = "Open settings",
            onPrimary = openSettings,
            secondaryLabel = "Cancel",
            onSecondary = { hide() },
        )
    }

    override fun onUpdateFailed(message: String, retry: () -> Unit) {
        show()
        binding.tvUpdateTitle.text = "Update failed"
        binding.tvUpdateVersion.visibility = View.GONE
        setNotes("$message\n\nThe till carries on working on the version it already has.")
        setWorking(false)
        buttons(
            primaryLabel = "Try again",
            onPrimary = retry,
            secondaryLabel = "Close",
            onSecondary = { hide() },
        )
    }

    // --- Panel plumbing -------------------------------------------------

    /** True while the panel is up, so the back button can be swallowed. */
    val isShowing: Boolean
        get() = binding.root.visibility == View.VISIBLE

    fun hide() {
        binding.root.visibility = View.GONE
        setBlur(false)
    }

    private fun show() {
        binding.root.visibility = View.VISIBLE
        setBlur(true)
    }

    private fun setNotes(text: String?) {
        binding.tvUpdateNotes.text = text.orEmpty()
        binding.tvUpdateNotes.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    /** Working = progress bar and the "leave it on" warning, no way to interrupt. */
    private fun setWorking(working: Boolean) {
        val visibility = if (working) View.VISIBLE else View.GONE
        binding.progressUpdate.visibility = visibility
        binding.tvUpdateStatus.visibility = visibility
        binding.tvUpdateWarning.visibility = visibility
    }

    private fun buttons(
        primaryLabel: String?,
        onPrimary: () -> Unit,
        secondaryLabel: String?,
        onSecondary: () -> Unit,
    ) {
        with(binding.btnUpdatePrimary) {
            visibility = if (primaryLabel == null) View.GONE else View.VISIBLE
            text = primaryLabel.orEmpty()
            setOnClickListener { onPrimary() }
        }
        with(binding.btnUpdateSecondary) {
            visibility = if (secondaryLabel == null) View.GONE else View.VISIBLE
            text = secondaryLabel.orEmpty()
            setOnClickListener { onSecondary() }
        }
    }

    /** Blur needs Android 12; below that the scrim alone does the dimming. */
    private fun setBlur(on: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val effect = if (on) {
            RenderEffect.createBlurEffect(BLUR_RADIUS_PX, BLUR_RADIUS_PX, Shader.TileMode.CLAMP)
        } else {
            null
        }
        blurred.forEach { it.setRenderEffect(effect) }
    }
}
