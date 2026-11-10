package com.chzzkrec.app

import android.content.Context
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 한 채널의 생방송을 원본 화질 그대로 받아 적는 작업 */
class RecordJob(
    private val ctx: Context,
    val channelId: String,
    private val onPartDone: (File) -> Unit,
    private val onEnd: (RecordJob) -> Unit,
) : Thread("rec-$channelId") {

    @Volatile var stopRequested = false
    @Volatile var stoppedByUser = false
    @Volatile var status = "연결 중"
    @Volatile var bytes = 0L
    @Volatile var quality = ""
    @Volatile var liveId = 0L
    @Volatile var error: String? = null
    val startedAt = System.currentTimeMillis()

    private var out: OutputStream? = null
    private var curFile: File? = null
    private var curMap: String? = null
    private var partBytes = 0L
    private var partNo = 0
    private var baseName = "chzzk"
    private var hdnts: String? = null
    private val mapCache = HashMap<String, ByteArray>()

    fun requestStop(byUser: Boolean) {
        stoppedByUser = byUser
        stopRequested = true
        interrupt()
    }

    override fun run() {
        try {
            record()
        } catch (e: InterruptedException) {
            // 중지 요청
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        } finally {
            closePart()
            onEnd(this)
        }
    }

    /** API에서 방송 주소를 받아 최고 화질 재생목록 주소를 돌려준다. 방송이 끝났으면 null */
    private fun resolveVariant(): String? {
        val info = ChzzkApi.liveDetail(channelId, Store.cookies(ctx)) ?: return null
        if (!info.open) return null
        liveId = info.liveId
        val master = info.hlsUrl ?: throw java.io.IOException(
            if (info.adult) "성인 방송 — 네이버 로그인이 필요합니다" else "방송 주소를 받지 못했습니다"
        )
        hdnts = Regex("[?&]hdnts=([^&]+)").find(master)?.groupValues?.get(1)
        if (partNo == 0) {
            val ts = SimpleDateFormat("yyMMdd_HHmmss", Locale.US).format(Date())
            baseName = clean("${info.channelName}_${ts}_${info.title}").take(80)
        }
        val variants = Hls.parseMaster(Net.getText(master), master)
        val best = Hls.best(variants) ?: return master // 화질 목록이 없으면 그 자체가 재생목록
        quality = if (best.height > 0) "${best.height}p" else "${best.bandwidth / 1000}kbps"
        return best.url
    }

    private fun record() {
        var variant = resolveVariant() ?: throw java.io.IOException("방송 중이 아닙니다")
        status = "녹화 중"
        var lastSeq = -1L
        var fails = 0
        var lastNew = System.currentTimeMillis()

        while (!stopRequested) {
            val pl = try {
                Hls.parseMedia(Net.getText(variant), variant).also { fails = 0 }
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                fails++
                if (fails > 40) throw java.io.IOException("연결이 계속 끊겨 녹화를 마쳤습니다")
                if (fails % 3 == 0) {
                    // 주소 토큰 만료·방송 종료 여부 확인
                    val v = try { resolveVariant() } catch (_: Exception) { variant }
                    if (v == null) return
                    variant = v
                }
                sleep(2000)
                continue
            }

            var fresh = pl.segs.filter { it.seq > lastSeq }
            if (lastSeq < 0) fresh = fresh.takeLast(4) // 처음엔 최신 구간부터
            for (seg in fresh) {
                if (stopRequested) break
                val data = fetchSegment(seg.url)
                if (data != null) {
                    ensurePart(seg.mapUrl)
                    out!!.write(data)
                    bytes += data.size
                    partBytes += data.size
                }
                lastSeq = seg.seq
                lastNew = System.currentTimeMillis()
            }
            if (pl.ended) return

            // 변환할 때 같은 크기의 여유 공간이 한 번 더 필요함
            if (Finalizer.rawDir(ctx).usableSpace < partBytes + MIN_FREE) {
                throw java.io.IOException("저장공간이 부족해 녹화를 마쳤습니다")
            }

            if (fresh.isEmpty() && System.currentTimeMillis() - lastNew > 30_000) {
                val v = try { resolveVariant() } catch (_: Exception) { variant }
                if (v == null) return
                variant = v
                lastNew = System.currentTimeMillis()
            }
            sleep(if (fresh.isEmpty()) 1000L else (pl.targetSec * 500).toLong().coerceIn(700L, 3000L))
        }
    }

    private fun fetchSegment(url: String): ByteArray? {
        var u = url
        for (attempt in 1..4) {
            if (stopRequested) return null
            try {
                return Net.getBytes(u)
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Net.HttpError) {
                // 일부 방송은 조각 주소에 인증값을 붙여야 받을 수 있음
                val t = hdnts
                if (e.code == 403 && t != null && !u.contains("__bgda__")) {
                    u = url + (if (url.contains('?')) "&" else "?") + "__bgda__=" + t
                    continue
                }
                if (e.code == 404 && attempt >= 2) return null
            } catch (e: Exception) {
            }
            sleep(500L * attempt)
        }
        return null // 이 조각은 건너뜀
    }

    private fun fetchMap(url: String): ByteArray {
        mapCache[url]?.let { return it }
        var lastErr: Exception? = null
        for (attempt in 1..5) {
            try {
                var data: ByteArray
                try {
                    data = Net.getBytes(url)
                } catch (e: Net.HttpError) {
                    val t = hdnts
                    if (e.code == 403 && t != null)
                        data = Net.getBytes(url + (if (url.contains('?')) "&" else "?") + "__bgda__=" + t)
                    else throw e
                }
                mapCache[url] = data
                return data
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                lastErr = e
                sleep(700L * attempt)
            }
        }
        throw java.io.IOException("스트림 초기화 정보를 받지 못했습니다: ${lastErr?.message}")
    }

    /** 필요하면 새 파일을 연다 (첫 조각, 스트림 설정 변경, 파일 크기 초과) */
    private fun ensurePart(mapUrl: String?) {
        if (out != null && mapUrl == curMap && partBytes < MAX_PART) return
        val init = if (mapUrl != null) fetchMap(mapUrl) else null
        closePart()
        partNo++
        val ext = if (mapUrl != null) "mp4" else "ts"
        val name = if (partNo == 1) baseName else "${baseName}_p$partNo"
        val f = File(Finalizer.rawDir(ctx), "$name.raw.$ext")
        out = BufferedOutputStream(FileOutputStream(f), 1 shl 20)
        curFile = f
        curMap = mapUrl
        partBytes = 0
        if (init != null) { out!!.write(init); partBytes += init.size }
    }

    private fun closePart() {
        val f = curFile
        try { out?.close() } catch (_: Exception) {}
        out = null
        curFile = null
        if (f != null) onPartDone(f)
    }

    private fun clean(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().ifEmpty { "chzzk" }

    companion object {
        const val MAX_PART = 2_500L * 1024 * 1024 // 파일 하나 최대 약 2.5GB, 넘으면 다음 파일로
        const val MIN_FREE = 700L * 1024 * 1024
    }
}
