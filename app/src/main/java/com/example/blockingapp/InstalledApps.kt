package com.example.blockingapp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

data class AppEntry(val pkg: String, val label: String)

/** Apps that show up in the launcher (visible through the <queries> entry in the manifest). */
object InstalledApps {
    @Suppress("DEPRECATION")
    fun launchable(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        // Blocking the home screen app would send the user "home" in a loop.
        val home = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            ?.activityInfo?.packageName
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != ctx.packageName && it != home }
            .map { AppEntry(it, label(ctx, it)) }
            .sortedBy { it.label.lowercase() }
    }

    /** The app's name, or the package name if it isn't installed. */
    @Suppress("DEPRECATION")
    fun label(ctx: Context, pkg: String): String {
        val pm = ctx.packageManager
        return try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
    }
}
