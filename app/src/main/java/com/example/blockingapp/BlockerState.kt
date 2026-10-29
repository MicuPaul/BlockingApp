package com.example.blockingapp

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** UI state shared by the blocklist and settings screens. */
class BlockerState(private val ctx: Context) {
    var domains by mutableStateOf(Prefs.domains(ctx).sorted())
        private set
    var dns by mutableStateOf(Prefs.dns(ctx))
    var domainError by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var isOwner by mutableStateOf(false)
        private set
    var isProtected by mutableStateOf(false)
        private set

    init {
        refreshPolicy()
    }

    fun refreshPolicy() {
        DevicePolicy.syncState(ctx)
        isOwner = DevicePolicy.isOwner(ctx)
        isProtected = Prefs.isProtected(ctx)
    }

    fun addDomain(input: String): Boolean {
        val d = Prefs.normalizeDomain(input)
        if (!d.contains('.') || d.contains(' ')) {
            domainError = "Enter a site like example.com"
            return false
        }
        domainError = null
        update(domains + d)
        return true
    }

    fun removeDomain(domain: String) = update(domains - domain)

    private fun update(list: Collection<String>) {
        val sorted = list.toSortedSet()
        Prefs.setDomains(ctx, sorted)
        domains = sorted.toList()
    }

    fun saveDns(): Boolean {
        val value = dns.trim()
        if (!Prefs.isIpv4(value)) {
            message = "DNS must be an IPv4 address, e.g. 9.9.9.9"
            return false
        }
        message = null
        dns = value
        Prefs.setDns(ctx, value)
        return true
    }

    /** Returns true if protection is now on. */
    fun enableProtection(): Boolean {
        if (!saveDns()) return false
        message = DevicePolicy.enableProtection(ctx)
        refreshPolicy()
        return message == null
    }

    fun disableProtection() {
        message = DevicePolicy.disableProtection(ctx)
        refreshPolicy()
    }

    fun removeOwner() {
        message = DevicePolicy.removeOwner(ctx)
        refreshPolicy()
    }
}
