package com.kyo.lanterna

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Verifica che il read-ahead restituisca il file identico byte per byte,
 * anche quando il "server" risponde con pezzi più corti o vuoti.
 */
class PrefetcherTest {

    private val pool = Executors.newFixedThreadPool(4)
    private val rnd = Random(42)
    private val file = ByteArray(9 * 1024 * 1024 + 12345).also { rnd.nextBytes(it) }

    @After
    fun tearDown() {
        pool.shutdownNow()
    }

    /** Server finto: a volte dà meno byte del richiesto, a volte una risposta vuota. */
    private fun flakyReader(seed: Int) = object : ChunkReader {
        private val r = Random(seed)
        override fun read(buf: ByteArray, fileOffset: Long, bufOffset: Int, length: Int): Int {
            if (fileOffset >= file.size) return -1
            if (r.nextInt(20) == 0) return 0
            val max = minOf(length.toLong(), file.size - fileOffset).toInt()
            val n = if (r.nextBoolean()) max else 1 + r.nextInt(max)
            System.arraycopy(file, fileOffset.toInt(), buf, bufOffset, n)
            return n
        }
    }

    private fun readAll(p: Prefetcher, expected: Int, seed: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val r = Random(seed)
        val tmp = ByteArray(300_000)
        while (true) {
            val n = p.read(tmp, 7, 1 + r.nextInt(tmp.size - 7))
            if (n < 0) break
            out.write(tmp, 7, n)
        }
        assertEquals(expected, out.size())
        return out.toByteArray()
    }

    @Test
    fun interoFileDallInizio() {
        val p = Prefetcher(flakyReader(1), 0, file.size.toLong(), Prefetcher.CHUNK, 32, pool)
        assertArrayEquals(file, readAll(p, file.size, 1))
        p.close()
    }

    @Test
    fun primiByteCorretti() {
        // È ciò che usa il player per riconoscere il formato (MKV, MP4...)
        val p = Prefetcher(flakyReader(2), 0, file.size.toLong(), Prefetcher.CHUNK, 32, pool)
        val head = ByteArray(4096)
        var got = 0
        while (got < head.size) {
            val n = p.read(head, got, head.size - got)
            check(n > 0) { "fine dati inattesa dopo $got byte" }
            got += n
        }
        assertArrayEquals(file.copyOfRange(0, 4096), head)
        p.close()
    }

    @Test
    fun partenzaDaMetaConLunghezzaLimitata() {
        val start = 3 * 1024 * 1024 + 777
        val len = 4 * 1024 * 1024 + 99
        val p = Prefetcher(flakyReader(3), start.toLong(), len.toLong(), Prefetcher.CHUNK, 8, pool)
        assertArrayEquals(file.copyOfRange(start, start + len), readAll(p, len, 3))
        p.close()
    }

    @Test
    fun saltiRipetuti() {
        repeat(20) { i ->
            val start = rnd.nextInt(file.size - 1)
            val len = minOf(file.size - start, 1 + rnd.nextInt(3 * 1024 * 1024))
            val p = Prefetcher(flakyReader(100 + i), start.toLong(), len.toLong(), Prefetcher.CHUNK, 4, pool)
            // legge solo una parte e chiude, come fa il player dopo un salto
            val tmp = ByteArray(64 * 1024)
            var got = 0
            while (got < minOf(len, 500_000)) {
                val n = p.read(tmp, 0, tmp.size)
                if (n < 0) break
                assertArrayEquals(file.copyOfRange(start + got, start + got + n), tmp.copyOf(n))
                got += n
            }
            p.close()
        }
    }
}
