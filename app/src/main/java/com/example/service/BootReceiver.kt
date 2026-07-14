package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.PreferencesHelper

/**
 * Restores the status-bar temperature service after a reboot. specialUse foreground
 * services are permitted to start from BOOT_COMPLETED, but OEM builds vary — if the
 * start is rejected, the MainActivity fallback brings it back on next app open.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!PreferencesHelper.getStatusBarTempToggle(context)) return
        try {
            StatusBarTempService.startService(context)
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException on restrictive builds — non-fatal.
        }
    }
}
