package com.example.blockingapp

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object VpnState {
    var running by mutableStateOf(false)
}

object Prefs {
    private const val FILE = "blocker"
    private const val KEY_PROTECTED = "protected"
    private const val KEY_DOMAINS = "domains"
    private const val KEY_DNS = "dns"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun domains(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_DOMAINS, emptySet())?.toSet() ?: emptySet()

    fun setDomains(ctx: Context, domains: Set<String>) {
        prefs(ctx).edit().putStringSet(KEY_DOMAINS, domains.toSet()).apply()
    }

    fun dns(ctx: Context): String = prefs(ctx).getString(KEY_DNS, "") ?: ""

    fun isProtected(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_PROTECTED, false)

    fun setProtected(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_PROTECTED, value).apply()
    }

    fun setDns(ctx: Context, dns: String) {
        prefs(ctx).edit().putString(KEY_DNS, dns).apply()
    }

    /** Normalizes user input like "https://www.Example.com/path" to "www.example.com". */
    fun normalizeDomain(input: String): String =
        input.trim().lowercase()
            .removePrefix("https://").removePrefix("http://")
            .substringBefore('/').substringBefore(':')
            .trimEnd('.')

    fun isIpv4(s: String): Boolean {
        val parts = s.split('.')
        return parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
    }
}
