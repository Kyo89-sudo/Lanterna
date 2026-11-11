package com.kyo.lanterna

import android.content.Context
import android.net.ConnectivityManager
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Cerca nella rete locale i PC con condivisione SMB (porta 445) e ne legge il nome NetBIOS. */
object Discovery {
    data class Host(val ip: String, val name: String)

    private fun localIpv4(context: Context): Inet4Address? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return null
        val lp = cm.getLinkProperties(net) ?: return null
        return lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress } as? Inet4Address
    }

    fun scan(context: Context, onProgress: (Int) -> Unit): List<Host> {
        val addr = localIpv4(context) ?: throw IOException("Nessuna rete attiva")
        val b = addr.address
        val base = "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}.${b[2].toInt() and 0xFF}."
        val self = b[3].toInt() and 0xFF
        val targets = (1..254).filter { it != self }
        val pool = Executors.newFixedThreadPool(48)
        val found = Collections.synchronizedList(ArrayList<Host>())
        val done = AtomicInteger()
        val lastPct = AtomicInteger(-1)
        targets.forEach { i ->
            pool.execute {
                val ip = base + i
                if (portOpen(ip, 445, 600)) {
                    found.add(Host(ip, netbiosName(ip) ?: reverseName(ip) ?: ip))
                }
                val pct = done.incrementAndGet() * 100 / targets.size
                if (lastPct.getAndSet(pct) != pct) onProgress(pct)
            }
        }
        pool.shutdown()
        pool.awaitTermination(60, TimeUnit.SECONDS)
        return found.toList().sortedBy { it.ip.substringAfterLast('.').toIntOrNull() ?: 0 }
    }

    private fun portOpen(ip: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs) }
        true
    } catch (e: Exception) {
        false
    }

    private fun reverseName(ip: String): String? = try {
        val n = InetAddress.getByName(ip).canonicalHostName
        if (n == ip) null else n.substringBefore('.')
    } catch (e: Exception) {
        null
    }

    /** Richiesta NetBIOS "node status" (UDP 137): risponde con il nome del PC. */
    private fun netbiosName(ip: String): String? = try {
        DatagramSocket().use { s ->
            s.soTimeout = 800
            val q = ByteArray(50)
            q[0] = 0x4C; q[1] = 0x41
            q[5] = 1
            q[12] = 0x20
            q[13] = 'C'.code.toByte(); q[14] = 'K'.code.toByte()
            for (i in 15 until 45) q[i] = 'A'.code.toByte()
            q[45] = 0
            q[47] = 0x21
            q[49] = 1
            val addr = InetAddress.getByName(ip)
            s.send(DatagramPacket(q, q.size, addr, 137))
            val r = ByteArray(1024)
            val pkt = DatagramPacket(r, r.size)
            s.receive(pkt)
            parseNodeStatus(r, pkt.length)
        }
    } catch (e: Exception) {
        null
    }

    private fun parseNodeStatus(r: ByteArray, n: Int): String? {
        var pos = 12
        if (pos >= n) return null
        pos += when {
            r[pos].toInt() == 0x20 -> 34
            (r[pos].toInt() and 0xC0) == 0xC0 -> 2
            else -> return null
        }
        pos += 10
        if (pos >= n) return null
        val count = r[pos].toInt() and 0xFF
        pos++
        for (i in 0 until count) {
            if (pos + 18 > n) break
            val name = String(r, pos, 15, Charsets.US_ASCII).trim()
            val suffix = r[pos + 15].toInt() and 0xFF
            val flags = ((r[pos + 16].toInt() and 0xFF) shl 8) or (r[pos + 17].toInt() and 0xFF)
            if ((suffix == 0x00 || suffix == 0x20) && (flags and 0x8000) == 0 && name.isNotEmpty()) return name
            pos += 18
        }
        return null
    }
}
