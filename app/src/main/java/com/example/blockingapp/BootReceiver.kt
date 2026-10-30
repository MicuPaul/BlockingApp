package com.example.blockingapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService

/**
 * Turns blocking back on after a reboot or an app update if it was on before, so restarting the
 * phone doesn't switch it off. Only Stop (behind the password) clears [Prefs.shouldRun].
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // Needs the VPN consent the user already gave; without it the system dialog would be required.
        if (!Prefs.shouldRun(context) || VpnService.prepare(context) != null) return
        try {
            startVpn(context)
        } catch (_: IllegalStateException) {
            // Android refused a background start (battery optimization); the next Start fixes it.
        }
    }
}
