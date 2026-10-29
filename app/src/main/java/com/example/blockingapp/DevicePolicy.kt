package com.example.blockingapp

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager

/** Device Owner features: uninstall block, forced always-on VPN and settings restrictions. */
object DevicePolicy {
    const val ADB_COMMAND =
        "adb shell dpm set-device-owner com.example.blockingapp/.AdminReceiver"

    /** Works from a PC because the app is marked android:testOnly in the manifest. */
    const val REMOVE_COMMAND =
        "adb shell dpm remove-active-admin com.example.blockingapp/.AdminReceiver"

    private fun dpm(ctx: Context) = ctx.getSystemService(DevicePolicyManager::class.java)
    private fun admin(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)

    private fun restrictions(): List<String> = buildList {
        add(UserManager.DISALLOW_CONFIG_VPN)
        add(UserManager.DISALLOW_APPS_CONTROL)
        add(UserManager.DISALLOW_SAFE_BOOT)
        if (Build.VERSION.SDK_INT >= 29) add(UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
    }

    fun isOwner(ctx: Context): Boolean = dpm(ctx).isDeviceOwnerApp(ctx.packageName)

    /** Clears a stale "protected" flag, e.g. after device owner was removed from a PC. */
    fun syncState(ctx: Context) {
        if (!isOwner(ctx)) Prefs.setProtected(ctx, false)
    }

    /** Returns an error message, or null on success. */
    fun enableProtection(ctx: Context): String? {
        if (!isOwner(ctx)) return "App is not device owner"
        if (!Prefs.isIpv4(Prefs.dns(ctx))) return "Set a valid DNS first"
        val d = dpm(ctx)
        val a = admin(ctx)
        return try {
            d.setUninstallBlocked(a, ctx.packageName, true)
            restrictions().forEach { d.addUserRestriction(a, it) }
            // No lockdown: only DNS is routed through the VPN, so lockdown would block all other traffic.
            d.setAlwaysOnVpnPackage(a, ctx.packageName, false)
            Prefs.setProtected(ctx, true)
            null
        } catch (e: Exception) {
            "Failed: ${e.message}"
        }
    }

    fun disableProtection(ctx: Context): String? {
        if (!isOwner(ctx)) return null
        val d = dpm(ctx)
        val a = admin(ctx)
        return try {
            d.setAlwaysOnVpnPackage(a, null, false)
            restrictions().forEach { d.clearUserRestriction(a, it) }
            d.setUninstallBlocked(a, ctx.packageName, false)
            Prefs.setProtected(ctx, false)
            null
        } catch (e: Exception) {
            "Failed: ${e.message}"
        }
    }

    /** Disables protection and gives up device owner status so the app can be uninstalled. */
    fun removeOwner(ctx: Context): String? {
        disableProtection(ctx)?.let { return it }
        return try {
            dpm(ctx).clearDeviceOwnerApp(ctx.packageName)
            null
        } catch (e: Exception) {
            "Failed: ${e.message}"
        }
    }
}
