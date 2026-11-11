package com.kyo.lanterna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Costruisce piccoli file MKV finti e verifica la lettura di capitoli e frame rate. */
class MkvTest {

    private fun id(v: Long): ByteArray {
        val n = when {
            v > 0xFFFFFF -> 4
            v > 0xFFFF -> 3
            v > 0xFF -> 2
            else -> 1
        }
        return ByteArray(n) { ((v shr (8 * (n - 1 - it))) and 0xFF).toByte() }
    }

    private fun u8(v: Long) = ByteArray(8) { ((v shr (8 * (7 - it))) and 0xFF).toByte() }

    private fun leaf(idv: Long, payload: ByteArray): ByteArray {
        val size = ByteArray(8)
        size[0] = 0x01
        for (i in 1..7) size[i] = ((payload.size.toLong() shr (8 * (7 - i))) and 0xFF).toByte()
        return id(idv) + size + payload
    }

    private fun leaf(idv: Long, s: String) = leaf(idv, s.toByteArray(Charsets.UTF_8))

    private fun node(idv: Long, vararg kids: ByteArray) = leaf(idv, kids.fold(ByteArray(0)) { a, b -> a + b })

    private val chapters = node(
        0x1043A770,
        node(
            0x45B9,
            node(0xB6, leaf(0x91, u8(0)), node(0x80, leaf(0x85, "Intro"), leaf(0x437C, "eng"))),
            node(
                0xB6, leaf(0x91, u8(90_000_000_000)),
                node(0x80, leaf(0x85, "Act 1"), leaf(0x437C, "eng")),
                node(0x80, leaf(0x85, "Atto 1"), leaf(0x437C, "ita"))
            ),
            node(0xB6, leaf(0x91, u8(30_000_000_000)), leaf(0x98, u8(1)), node(0x80, leaf(0x85, "Nascosto")))
        )
    )

    private val tracks = node(
        0x1654AE6B,
        node(0xAE, leaf(0x83, u8(2))),
        node(0xAE, leaf(0x83, u8(1)), leaf(0x23E383, u8(41_708_333)))
    )

    private val cluster = leaf(0x1F43B675, ByteArray(2000))
    private val ebmlHeader = node(0x1A45DFA3, leaf(0x4282, "matroska"))

    private fun reader(data: ByteArray) = RandomReader { pos, buf, off, len ->
        if (pos >= data.size) -1
        else {
            val n = minOf(len.toLong(), data.size - pos).toInt()
            System.arraycopy(data, pos.toInt(), buf, off, n)
            n
        }
    }

    private fun check(info: MkvInfo) {
        assertEquals(listOf(Chapter(0, "Intro"), Chapter(90_000, "Atto 1")), info.chapters)
        assertEquals(23.976f, info.frameRate, 0.01f)
    }

    @Test
    fun capitoliDopoICluster_tramiteSeekHead() {
        fun seekHead(pos: Long) = node(0x114D9B74, node(0x4DBB, leaf(0x53AB, id(0x1043A770)), leaf(0x53AC, u8(pos))))
        val chapPos = (seekHead(0).size + tracks.size + cluster.size).toLong()
        val segment = leaf(0x18538067, seekHead(chapPos) + tracks + cluster + chapters)
        val file = ebmlHeader + segment
        check(Mkv.read(reader(file), file.size.toLong()))
    }

    @Test
    fun capitoliPrimaDeiCluster() {
        val file = ebmlHeader + leaf(0x18538067, tracks + chapters + cluster)
        check(Mkv.read(reader(file), file.size.toLong()))
    }

    @Test
    fun fileNonMkv() {
        val file = ByteArray(5000) { (it % 251).toByte() }
        val info = Mkv.read(reader(file), file.size.toLong())
        assertTrue(info.chapters.isEmpty())
        assertEquals(0f, info.frameRate, 0f)
    }
}
