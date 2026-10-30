package com.example.blockingapp

import android.content.Intent
import android.database.ContentObserver
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.Settings
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Local-only VPN. Only traffic to the chosen DNS server is routed into the TUN; each DNS query
 * is either answered with NXDOMAIN (blocked domain) or forwarded to the real DNS server.
 */
class BlockingVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private var pool: ExecutorService? = null

    @Volatile
    private var running = false

    private val zoneMonitor by lazy { ZoneMonitor(this) }

    private var watchingAccessibility = false
    private val accessibilityObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            AccessibilityGuard.ensureEnabled(this@BlockingVpnService)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.setShouldRun(this, false)
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }
        startVpn()
        return START_STICKY
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun startVpn() {
        stopVpn()
        val dns = Prefs.dns(this)
        if (!Prefs.isIpv4(dns)) {
            stopSelf()
            return
        }
        val dnsBytes = InetAddress.getByName(dns).address
        val fd = Builder()
            .setSession("BlockingApp")
            .addAddress("10.111.222.1", 32)
            .addDnsServer(dns)
            .addRoute(dns, 32)
            .setBlocking(true)
            .establish()
        if (fd == null) {
            stopSelf()
            return
        }
        tun = fd
        running = true
        VpnState.running = true
        Prefs.setShouldRun(this, true)
        watchAccessibility()
        zoneMonitor.start()
        val executor = Executors.newCachedThreadPool()
        pool = executor
        reader = Thread({ readLoop(fd, dnsBytes, executor) }, "dns-vpn-reader").also { it.start() }
    }

    /** While blocking is on, switch the app blocker back on whenever it's turned off. */
    private fun watchAccessibility() {
        AccessibilityGuard.ensureEnabled(this)
        val cr = contentResolver
        cr.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, accessibilityObserver
        )
        cr.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED), false, accessibilityObserver
        )
        watchingAccessibility = true
    }

    private fun stopVpn() {
        running = false
        VpnState.running = false
        zoneMonitor.stop()
        if (watchingAccessibility) {
            contentResolver.unregisterContentObserver(accessibilityObserver)
            watchingAccessibility = false
        }
        reader?.interrupt()
        reader = null
        pool?.shutdownNow()
        pool = null
        try {
            tun?.close()
        } catch (_: Exception) {
        }
        tun = null
    }

    private fun readLoop(fd: ParcelFileDescriptor, dnsBytes: ByteArray, executor: ExecutorService) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        try {
            while (running) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                val packet = buf.copyOf(n)
                try {
                    executor.execute { handlePacket(packet, dnsBytes, output) }
                } catch (_: Exception) {
                    break
                }
            }
        } catch (_: Exception) {
            // TUN closed
        }
    }

    private fun handlePacket(p: ByteArray, dnsBytes: ByteArray, output: FileOutputStream) {
        if (p.size < 28 || (p[0].toInt() shr 4) != 4) return
        val ihl = (p[0].toInt() and 0x0F) * 4
        if (p[9].toInt() != 17 || p.size < ihl + 8) return
        for (i in 0..3) if (p[16 + i] != dnsBytes[i]) return
        if (u16(p, ihl + 2) != 53) return

        val dnsStart = ihl + 8
        val dnsLen = minOf(u16(p, ihl + 4) - 8, p.size - dnsStart)
        if (dnsLen < 12) return
        val query = p.copyOfRange(dnsStart, dnsStart + dnsLen)

        val (name, questionEnd) = parseQuestion(query) ?: return forwardAndReply(p, ihl, query, output)

        val response = if (isBlocked(name)) {
            nxdomain(query, questionEnd)
        } else {
            forward(query, dnsBytes)
        } ?: return
        writeReply(p, ihl, response, output)
    }

    private fun forwardAndReply(p: ByteArray, ihl: Int, query: ByteArray, output: FileOutputStream) {
        val dnsBytes = ByteArray(4) { p[16 + it] }
        val response = forward(query, dnsBytes) ?: return
        writeReply(p, ihl, response, output)
    }

    private fun forward(query: ByteArray, dnsBytes: ByteArray): ByteArray? = try {
        DatagramSocket().use { s ->
            protect(s)
            s.soTimeout = 4000
            s.connect(InetAddress.getByAddress(dnsBytes), 53)
            s.send(DatagramPacket(query, query.size))
            val rb = ByteArray(4096)
            val rp = DatagramPacket(rb, rb.size)
            s.receive(rp)
            rb.copyOf(rp.length)
        }
    } catch (_: Exception) {
        null
    }

    private fun isBlocked(name: String): Boolean {
        if (!Zone.inside) return false
        val n = name.lowercase().trimEnd('.')
        return Prefs.domains(this).any { n == it || n.endsWith(".$it") }
    }

    /** Returns (queried name, offset just past the first question) or null if malformed. */
    private fun parseQuestion(q: ByteArray): Pair<String, Int>? {
        if (u16(q, 4) < 1) return null
        var pos = 12
        val sb = StringBuilder()
        while (true) {
            if (pos >= q.size) return null
            val len = q[pos].toInt() and 0xFF
            if (len == 0) break
            if (len and 0xC0 != 0 || pos + 1 + len > q.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 1..len) sb.append((q[pos + i].toInt() and 0xFF).toChar())
            pos += len + 1
        }
        val end = pos + 1 + 4
        if (end > q.size) return null
        return sb.toString() to end
    }

    private fun nxdomain(query: ByteArray, questionEnd: Int): ByteArray {
        val r = query.copyOf(questionEnd)
        r[2] = 0x81.toByte() // QR=1, RD=1
        r[3] = 0x83.toByte() // RA=1, RCODE=3 (NXDOMAIN)
        r[6] = 0; r[7] = 0 // ANCOUNT
        r[8] = 0; r[9] = 0 // NSCOUNT
        r[10] = 0; r[11] = 0 // ARCOUNT
        r[4] = 0; r[5] = 1 // QDCOUNT
        return r
    }

    private fun writeReply(req: ByteArray, ihl: Int, dns: ByteArray, output: FileOutputStream) {
        val udpLen = 8 + dns.size
        val total = 20 + udpLen
        val out = ByteArray(total)
        out[0] = 0x45
        out[2] = (total shr 8).toByte(); out[3] = total.toByte()
        out[6] = 0x40 // DF
        out[8] = 64 // TTL
        out[9] = 17
        for (i in 0..3) {
            out[12 + i] = req[16 + i] // src = DNS server
            out[16 + i] = req[12 + i] // dst = client
        }
        val csum = checksum(out, 20)
        out[10] = (csum shr 8).toByte(); out[11] = csum.toByte()
        out[20] = 0; out[21] = 53
        out[22] = req[ihl]; out[23] = req[ihl + 1] // client's source port
        out[24] = (udpLen shr 8).toByte(); out[25] = udpLen.toByte()
        System.arraycopy(dns, 0, out, 28, dns.size)
        try {
            synchronized(output) { output.write(out) }
        } catch (_: Exception) {
        }
    }

    private fun checksum(b: ByteArray, len: Int): Int {
        var sum = 0
        var i = 0
        while (i < len) {
            sum += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    private fun u16(b: ByteArray, off: Int) =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    companion object {
        const val ACTION_STOP = "com.example.blockingapp.STOP"
    }
}
