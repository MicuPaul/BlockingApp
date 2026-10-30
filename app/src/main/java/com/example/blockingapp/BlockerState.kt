package com.example.blockingapp

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** UI state shared by the blocklist and settings screens. */
class BlockerState(private val ctx: Context) {
    var domains by mutableStateOf(Prefs.domains(ctx).sorted())
        private set
    var apps by mutableStateOf(Prefs.apps(ctx).sorted())
        private set

    /** The DNS text field; only [saveDns] (behind the password) stores it. */
    var dns by mutableStateOf(Prefs.dns(ctx))
    var savedDns by mutableStateOf(Prefs.dns(ctx))
        private set
    var zone by mutableStateOf(Prefs.zone(ctx))
        private set
    var domainError by mutableStateOf<String?>(null)
    var dnsMessage by mutableStateOf<String?>(null)

    /** Result of the protection actions. */
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

    fun addApp(pkg: String) = updateApps(apps + pkg)

    fun removeApp(pkg: String) = updateApps(apps - pkg)

    private fun updateApps(list: Collection<String>) {
        val sorted = list.toSortedSet()
        Prefs.setApps(ctx, sorted)
        apps = sorted.toList()
    }

    /** Null removes the zone, so blocking applies everywhere again. */
    fun saveZone(area: ZoneArea?) {
        Prefs.setZone(ctx, area)
        zone = area
    }

    fun saveDns(): Boolean {
        val value = dns.trim()
        if (!Prefs.isIpv4(value)) {
            dnsMessage = "DNS must be an IPv4 address, e.g. 9.9.9.9"
            return false
        }
        dnsMessage = null
        dns = value
        savedDns = value
        Prefs.setDns(ctx, value)
        return true
    }

    /** Returns true if protection is now on. Uses the saved DNS, not the text field. */
    fun enableProtection(): Boolean {
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
