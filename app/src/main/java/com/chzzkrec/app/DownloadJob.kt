package com.chzzkrec.app

import android.content.Context
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/** 클립·캐치·다시보기 한 건을 내려받아 Movies/Chzzk에 저장 */
class DownloadJob(
    private val ctx: Context,
    private val ref: MediaRef,
    private val onEnd: (DownloadJob) -> Unit,
) : Thread("dl-${ref.id}") {

    @Volatile var title: String = ref.source
    @Volatile var status = "정보 확인 중"
    @Volatile var finished = false
    @Volatile var error: String? = null
    @Volatile var cancelled = false
    val key: String get() = "${ref.kind}:${ref.id}"

    private var out: OutputStream? = null
    private var curFile: File? = null

    fun cancel() {
        cancelled = true
        interrupt()
    }

    override fun run() {
        try {
            val items = MediaApi.resolve(ctx, ref)
            title = items[0].name + if (items.size > 1) " 외 ${items.size - 1}개" else ""
            for ((i, item) in items.withIndex()) {
                if (cancelled) throw InterruptedException()
                val prefix = if (items.size > 1) "(${i + 1}/${items.size}) " else ""
                // 이름이 같은 파일이 여러 개면 번호를 붙임
                val same = items.count { it.name == item.name } > 1
                val name = if (same) "${item.name}_${i + 1}" else item.name
                if (item.hls) hls(item, name, prefix) else whole(item, name, prefix)
            }
            status = "저장 완료"
        } catch (e: InterruptedException) {
            status = "취소됨"
        } catch (e: Exception) {
            if (cancelled) status = "취소됨"
            else { error = e.message ?: e.javaClass.simpleName; status = "실패" }
        } finally {
            try { out?.close() } catch (_: Exception) {}
            curFile?.delete()
            finished = true
            onEnd(this)
        }
    }

    private fun size(b: Long): String =
        if (b >= 1 shl 30) String.format("%.2fGB", b / 1073741824.0) else String.format("%.0fMB", b / 1048576.0)

    /** 파일 하나로 제공되는 영상 */
    private fun whole(item: MediaItem, name: String, prefix: String) {
        val f = File(Finalizer.rawDir(ctx), "$name.dl.mp4")
        f.delete()
        curFile = f
        Net.download(item.url, f, item.referer, { cancelled }) { done, total ->
            status = prefix + if (total > 0) "받는 중 ${done * 100 / total}% · ${size(done)} / ${size(total)}"
            else "받는 중 ${size(done)}"
        }
        status = prefix + "저장 중"
        Finalizer.publish(ctx, f, "$name.mp4", "video/mp4")
        curFile = null
    }

    /** 조각(HLS)으로 제공되는 영상 */
    private fun hls(item: MediaItem, name: String, prefix: String) {
        val masterText = Net.getText(item.url, null, item.referer)
        val best = Hls.best(Hls.parseMaster(masterText, item.url))
        val pl = if (best == null) Hls.parseMedia(masterText, item.url)
        else Hls.parseMedia(Net.getText(best.url, null, item.referer), best.url)
        if (pl.encrypted) throw IOException("암호화된 영상은 받을 수 없습니다")
        if (pl.segs.isEmpty()) throw IOException("영상 조각 목록이 비어 있습니다")

        val total = pl.segs.size
        var bytes = 0L
        var partBytes = 0L
        var partNo = 0
        var curMap: String? = null
        val maps = HashMap<String, ByteArray>()

        fun closePart() {
            val f = curFile ?: return
            try { out?.close() } catch (_: Exception) {}
            out = null
            status = prefix + "저장 중 (MP4 변환)"
            Finalizer.finalize(ctx, f)
            curFile = null
        }

        for ((i, seg) in pl.segs.withIndex()) {
            if (cancelled) throw InterruptedException()
            val data = fetch(seg.url, item)
            if (out == null || seg.mapUrl != curMap || partBytes > RecordJob.MAX_PART) {
                val init = seg.mapUrl?.let { m -> maps.getOrPut(m) { fetch(m, item) } }
                closePart()
                partNo++
                val ext = if (seg.mapUrl != null) "mp4" else "ts"
                val f = File(Finalizer.rawDir(ctx), (if (partNo == 1) name else "${name}_p$partNo") + ".raw.$ext")
                out = BufferedOutputStream(FileOutputStream(f), 1 shl 20)
                curFile = f
                curMap = seg.mapUrl
                partBytes = 0
                if (init != null) { out!!.write(init); partBytes += init.size }
            }
            out!!.write(data)
            bytes += data.size
            partBytes += data.size
            status = prefix + "받는 중 ${(i + 1) * 100 / total}% · ${size(bytes)}"
            if (i % 20 == 0 && Finalizer.rawDir(ctx).usableSpace < partBytes + RecordJob.MIN_FREE)
                throw IOException("저장공간이 부족합니다")
        }
        closePart()
    }

    /** 조각 하나 받기. 다시보기는 빠진 구간이 생기면 안 되므로 끝까지 재시도 후 실패 처리 */
    private fun fetch(url: String, item: MediaItem): ByteArray {
        var u = url
        var last: Exception? = null
        for (attempt in 1..8) {
            if (cancelled) throw InterruptedException()
            try {
                return Net.getBytes(u, null, item.referer)
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Net.HttpError) {
                last = e
                val t = item.bgda
                if (e.code == 403 && t != null && !u.contains("__bgda__")) {
                    u = url + (if (url.contains('?')) "&" else "?") + "__bgda__=" + t
                    continue
                }
            } catch (e: Exception) {
                last = e
            }
            sleep(1000L * attempt)
        }
        throw IOException("내려받기 실패: ${last?.message}")
    }
}
