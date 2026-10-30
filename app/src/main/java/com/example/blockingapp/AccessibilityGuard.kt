package com.example.blockingapp

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Switches [AppBlockerService] on in the accessibility settings. Needs WRITE_SECURE_SETTINGS,
 * which only a PC can grant ([GRANT_COMMAND]); without it the user has to turn it on by hand.
 */
object AccessibilityGuard {
    const val GRANT_COMMAND =
        "adb shell pm grant com.example.blockingapp android.permission.WRITE_SECURE_SETTINGS"

    fun canEnforce(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Returns true if the service is enabled in the settings (now or already). */
    fun ensureEnabled(ctx: Context): Boolean {
        val me = ComponentName(ctx, AppBlockerService::class.java)
        val cr = ctx.contentResolver
        val entries = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').filter { it.isNotBlank() }
        val listed = entries.any { ComponentName.unflattenFromString(it) == me }
        val on = Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        if (listed && on) return true
        if (!canEnforce(ctx)) return false
        return try {
            if (!listed) {
                Settings.Secure.putString(
                    cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    (entries + me.flattenToString()).joinToString(":")
                )
            }
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        } catch (_: SecurityException) {
            false
        }
    }
}
