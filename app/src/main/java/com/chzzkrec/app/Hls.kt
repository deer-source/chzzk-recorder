package com.chzzkrec.app

import java.net.URL

/** HLS 재생목록 해석 (순수 문자열 처리) */
object Hls {
    data class Variant(val url: String, val height: Int, val bandwidth: Long)
    data class Seg(val seq: Long, val url: String, val mapUrl: String?)
    data class Media(val segs: List<Seg>, val ended: Boolean, val targetSec: Double)

    fun resolve(base: String, ref: String): String =
        try { URL(URL(base), ref).toString() } catch (e: Exception) { ref }

    private fun attr(line: String, key: String): String? {
        val m = Regex("(?:^|[,:])$key=(\"[^\"]*\"|[^,]*)").find(line) ?: return null
        return m.groupValues[1].trim('"')
    }

    /** 마스터 재생목록의 화질 목록. 화질 목록이 아니면 빈 목록 */
    fun parseMaster(text: String, base: String): List<Variant> {
        val out = ArrayList<Variant>()
        val lines = text.lines().map { it.trim() }
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                val bw = attr(l, "BANDWIDTH")?.toLongOrNull() ?: 0L
                val h = attr(l, "RESOLUTION")?.substringAfter('x', "")?.toIntOrNull() ?: 0
                var j = i + 1
                while (j < lines.size && (lines[j].isEmpty() || lines[j].startsWith("#"))) j++
                if (j < lines.size) out.add(Variant(resolve(base, lines[j]), h, bw))
                i = j
            }
            i++
        }
        return out
    }

    /** 가장 높은 화질 (해상도 우선, 같으면 비트레이트) */
    fun best(vs: List<Variant>): Variant? =
        vs.maxWithOrNull(compareBy<Variant> { it.height }.thenBy { it.bandwidth })

    fun parseMedia(text: String, base: String): Media {
        val segs = ArrayList<Seg>()
        var seq = 0L
        var map: String? = null
        var ended = false
        var target = 2.0
        for (raw in text.lines()) {
            val l = raw.trim()
            if (l.isEmpty()) continue
            when {
                l.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                    seq = l.substringAfter(':').trim().toLongOrNull() ?: 0L
                l.startsWith("#EXT-X-TARGETDURATION:") ->
                    target = l.substringAfter(':').trim().toDoubleOrNull() ?: 2.0
                l.startsWith("#EXT-X-MAP:") ->
                    map = attr(l, "URI")?.let { resolve(base, it) }
                l.startsWith("#EXT-X-ENDLIST") -> ended = true
                l.startsWith("#") -> {}
                else -> { segs.add(Seg(seq, resolve(base, l), map)); seq++ }
            }
        }
        return Media(segs, ended, target)
    }
}
