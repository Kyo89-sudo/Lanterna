package com.kyo.lanterna

data class Chapter(val startMs: Long, val title: String)

data class MkvInfo(val chapters: List<Chapter>, val frameRate: Float)

/** Lettura ad accesso casuale: ritorna i byte letti o -1 a fine file. */
fun interface RandomReader {
    fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int
}

/**
 * Lettore minimo di file Matroska (MKV/WebM): estrae capitoli e frame rate
 * leggendo solo poche decine di KB (SeekHead → Chapters / Tracks).
 */
object Mkv {
    private const val ID_EBML = 0x1A45DFA3L
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_SEEKHEAD = 0x114D9B74L
    private const val ID_SEEK = 0x4DBBL
    private const val ID_SEEKID = 0x53ABL
    private const val ID_SEEKPOS = 0x53ACL
    private const val ID_CHAPTERS = 0x1043A770L
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_EDITION = 0x45B9L
    private const val ID_EDITION_HIDDEN = 0x45BDL
    private const val ID_ATOM = 0xB6L
    private const val ID_TIMESTART = 0x91L
    private const val ID_HIDDEN = 0x98L
    private const val ID_ENABLED = 0x4598L
    private const val ID_DISPLAY = 0x80L
    private const val ID_STRING = 0x85L
    private const val ID_LANG = 0x437CL
    private const val ID_LANG_BCP = 0x437DL
    private const val ID_TRACKENTRY = 0xAEL
    private const val ID_TRACKTYPE = 0x83L
    private const val ID_DEFAULTDURATION = 0x23E383L
    private const val MAX_ELEMENT = 4 * 1024 * 1024

    val EMPTY = MkvInfo(emptyList(), 0f)

    private class Header(val id: Long, val size: Long, val headerLen: Int) // size -1 = sconosciuta

    private class Element(val id: Long, val buf: ByteArray, val dataStart: Int)

    private fun header(buf: ByteArray, off: Int, end: Int): Header? {
        if (off >= end) return null
        val b0 = buf[off].toInt() and 0xFF
        if (b0 == 0) return null
        val idLen = Integer.numberOfLeadingZeros(b0) - 23
        if (idLen > 4 || off + idLen > end) return null
        var id = 0L
        for (i in 0 until idLen) id = (id shl 8) or (buf[off + i].toLong() and 0xFF)
        val so = off + idLen
        if (so >= end) return null
        val s0 = buf[so].toInt() and 0xFF
        if (s0 == 0) return null
        val sLen = Integer.numberOfLeadingZeros(s0) - 23
        if (sLen > 8 || so + sLen > end) return null
        val mask = 0xFF shr sLen
        var size = (s0 and mask).toLong()
        var allOnes = (s0 and mask) == mask
        for (i in 1 until sLen) {
            val b = buf[so + i].toLong() and 0xFF
            size = (size shl 8) or b
            if (b != 0xFFL) allOnes = false
        }
        return Header(id, if (allOnes) -1L else size, idLen + sLen)
    }

    private fun forEachChild(buf: ByteArray, start: Int, end: Int, f: (Long, Int, Int) -> Unit) {
        var pos = start
        while (pos < end) {
            val h = header(buf, pos, end) ?: return
            val ds = pos + h.headerLen
            val de = if (h.size < 0 || ds + h.size > end) end else (ds + h.size).toInt()
            f(h.id, ds, de)
            if (de <= pos) return
            pos = de
        }
    }

    private fun uint(buf: ByteArray, s: Int, e: Int): Long {
        var v = 0L
        for (i in s until minOf(e, s + 8)) v = (v shl 8) or (buf[i].toLong() and 0xFF)
        return v
    }

    private fun string(buf: ByteArray, s: Int, e: Int): String =
        String(buf, s, e - s, Charsets.UTF_8).trimEnd('\u0000').trim()

    private fun readFully(r: RandomReader, pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        var done = 0
        var empty = 0
        while (done < len) {
            val n = r.read(pos + done, buf, off + done, len - done)
            if (n < 0) break
            if (n == 0) {
                if (++empty >= 3) break
                continue
            }
            done += n
        }
        return done
    }

    private fun headerAt(r: RandomReader, pos: Long): Header? {
        val head = ByteArray(16)
        val n = readFully(r, pos, head, 0, 16)
        return header(head, 0, n)
    }

    private fun loadElement(r: RandomReader, pos: Long, fileSize: Long, expectedId: Long): Element? {
        val h = headerAt(r, pos) ?: return null
        if (h.id != expectedId || h.size < 0 || h.size > MAX_ELEMENT) return null
        val total = (h.headerLen + h.size).toInt()
        if (pos + total > fileSize) return null
        val buf = ByteArray(total)
        if (readFully(r, pos, buf, 0, total) < total) return null
        return Element(h.id, buf, h.headerLen)
    }

    fun read(r: RandomReader, fileSize: Long): MkvInfo {
        val ebml = headerAt(r, 0) ?: return EMPTY
        if (ebml.id != ID_EBML || ebml.size < 0) return EMPTY
        var pos = ebml.headerLen + ebml.size
        val seg = headerAt(r, pos) ?: return EMPTY
        if (seg.id != ID_SEGMENT) return EMPTY
        val segStart = pos + seg.headerLen
        val segEnd = if (seg.size < 0) fileSize else minOf(fileSize, segStart + seg.size)

        val targets = HashMap<Long, Long>()
        val seekHeads = ArrayList<Long>()

        // elementi di primo livello prima dei cluster
        pos = segStart
        var count = 0
        scan@ while (pos < segEnd && count++ < 64) {
            val h = headerAt(r, pos) ?: break@scan
            when (h.id) {
                ID_SEEKHEAD -> seekHeads.add(pos)
                ID_CHAPTERS, ID_TRACKS -> if (!targets.containsKey(h.id)) targets[h.id] = pos
                ID_CLUSTER -> break@scan
            }
            if (h.size < 0) break@scan
            pos += h.headerLen + h.size
        }

        // indice (SeekHead), anche più di uno
        var i = 0
        while (i < seekHeads.size && i < 4) {
            val el = loadElement(r, seekHeads[i++], fileSize, ID_SEEKHEAD) ?: continue
            forEachChild(el.buf, el.dataStart, el.buf.size) { id, s, e ->
                if (id == ID_SEEK) {
                    var sid = -1L
                    var spos = -1L
                    forEachChild(el.buf, s, e) { cid, cs, ce ->
                        if (cid == ID_SEEKID) sid = uint(el.buf, cs, ce)
                        else if (cid == ID_SEEKPOS) spos = uint(el.buf, cs, ce)
                    }
                    if (sid >= 0 && spos >= 0) {
                        val abs = segStart + spos
                        if (sid == ID_SEEKHEAD) {
                            if (!seekHeads.contains(abs)) seekHeads.add(abs)
                        } else if ((sid == ID_CHAPTERS || sid == ID_TRACKS) && !targets.containsKey(sid)) {
                            targets[sid] = abs
                        }
                    }
                }
            }
        }

        val chapters = targets[ID_CHAPTERS]?.let { loadElement(r, it, fileSize, ID_CHAPTERS) }
            ?.let { parseChapters(it) } ?: emptyList()
        val fps = targets[ID_TRACKS]?.let { loadElement(r, it, fileSize, ID_TRACKS) }
            ?.let { parseFps(it) } ?: 0f
        return MkvInfo(chapters, fps)
    }

    private fun parseChapters(el: Element): List<Chapter> {
        val editions = ArrayList<List<Chapter>>()
        forEachChild(el.buf, el.dataStart, el.buf.size) { id, s, e ->
            if (id == ID_EDITION) {
                var hidden = false
                val list = ArrayList<Chapter>()
                forEachChild(el.buf, s, e) { cid, cs, ce ->
                    if (cid == ID_EDITION_HIDDEN) hidden = uint(el.buf, cs, ce) != 0L
                    else if (cid == ID_ATOM) parseAtom(el.buf, cs, ce)?.let { list.add(it) }
                }
                if (!hidden && list.isNotEmpty()) editions.add(list)
            }
        }
        val first = editions.firstOrNull() ?: return emptyList()
        return first.sortedBy { it.startMs }.mapIndexed { idx, c ->
            if (c.title.isBlank()) c.copy(title = "Capitolo ${idx + 1}") else c
        }
    }

    private fun parseAtom(buf: ByteArray, s: Int, e: Int): Chapter? {
        var start = -1L
        var hidden = false
        var enabled = true
        var title = ""
        var titleIt = ""
        forEachChild(buf, s, e) { id, cs, ce ->
            when (id) {
                ID_TIMESTART -> start = uint(buf, cs, ce)
                ID_HIDDEN -> hidden = uint(buf, cs, ce) != 0L
                ID_ENABLED -> enabled = uint(buf, cs, ce) != 0L
                ID_DISPLAY -> {
                    var str = ""
                    var lang = ""
                    forEachChild(buf, cs, ce) { did, ds, de ->
                        if (did == ID_STRING) str = string(buf, ds, de)
                        else if (did == ID_LANG || did == ID_LANG_BCP) lang = string(buf, ds, de)
                    }
                    if (title.isEmpty()) title = str
                    if (lang.startsWith("it") && str.isNotEmpty()) titleIt = str
                }
            }
        }
        if (start < 0 || hidden || !enabled) return null
        return Chapter(start / 1_000_000L, titleIt.ifEmpty { title })
    }

    private fun parseFps(el: Element): Float {
        var fps = 0f
        forEachChild(el.buf, el.dataStart, el.buf.size) { id, s, e ->
            if (id == ID_TRACKENTRY && fps == 0f) {
                var type = 0L
                var dur = 0L
                forEachChild(el.buf, s, e) { cid, cs, ce ->
                    if (cid == ID_TRACKTYPE) type = uint(el.buf, cs, ce)
                    else if (cid == ID_DEFAULTDURATION) dur = uint(el.buf, cs, ce)
                }
                if (type == 1L && dur > 0) fps = (1e9 / dur).toFloat()
            }
        }
        return fps
    }
}
