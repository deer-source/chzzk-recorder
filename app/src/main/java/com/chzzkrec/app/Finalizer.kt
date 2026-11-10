package com.chzzkrec.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * 녹화 원본(스트림 조각을 이어 붙인 파일)을 갤러리에서 바로 재생·탐색되는
 * 일반 MP4로 바꿔 Movies/Chzzk 폴더에 저장한다. 화질 손실 없음(재인코딩 안 함).
 */
object Finalizer {
    const val FOLDER = "Movies/Chzzk"

    /** 저장된 파일 이름을 돌려준다 */
    fun finalize(ctx: Context, raw: File): String {
        if (!raw.exists() || raw.length() < 1024) { raw.delete(); return "" }
        val base = raw.name.substringBefore(".raw.")
        val rawExt = raw.name.substringAfterLast('.')

        var uri = insert(ctx, "$base.mp4", "video/mp4")
        var ok = false
        try {
            ctx.contentResolver.openFileDescriptor(uri, "rw")!!.use { pfd ->
                remux(raw, pfd.fileDescriptor)
            }
            ok = true
        } catch (e: Exception) {
            try { ctx.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
        }

        var name = "$base.mp4"
        if (!ok) {
            // 변환 실패 시 원본 그대로 보존 (VLC 등으로 재생 가능)
            name = "$base.$rawExt"
            uri = insert(ctx, name, if (rawExt == "ts") "video/mp2t" else "video/mp4")
            try {
                ctx.contentResolver.openOutputStream(uri)!!.use { out ->
                    raw.inputStream().use { it.copyTo(out, 1 shl 20) }
                }
            } catch (e: Exception) {
                // 복사도 실패하면 원본은 지우지 않고 남겨 둔다 (다음 실행 때 다시 시도)
                try { ctx.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                throw e
            }
        }
        val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        ctx.contentResolver.update(uri, done, null, null)
        raw.delete()
        return name
    }

    /** 이미 완성된 파일을 그대로 Movies/Chzzk로 옮긴다 */
    fun publish(ctx: Context, src: File, name: String, mime: String) {
        val uri = insert(ctx, name, mime)
        try {
            ctx.contentResolver.openOutputStream(uri)!!.use { out ->
                src.inputStream().use { it.copyTo(out, 1 shl 20) }
            }
        } catch (e: Exception) {
            try { ctx.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
            throw e
        }
        val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        ctx.contentResolver.update(uri, done, null, null)
        src.delete()
    }

    private fun insert(ctx: Context, name: String, mime: String): Uri {
        val v = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, mime)
            put(MediaStore.Video.Media.RELATIVE_PATH, FOLDER)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        return ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)
            ?: throw java.io.IOException("저장 위치를 만들 수 없음")
    }

    private fun remux(src: File, outFd: FileDescriptor) {
        val ex = MediaExtractor()
        var mux: MediaMuxer? = null
        try {
            ex.setDataSource(src.absolutePath)
            mux = MediaMuxer(outFd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val map = IntArray(ex.trackCount) { -1 }
            var bufSize = 4 shl 20
            var tracks = 0
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                ex.selectTrack(i)
                map[i] = mux.addTrack(f)
                tracks++
                if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                    bufSize = maxOf(bufSize, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
            }
            if (tracks == 0) throw java.io.IOException("트랙 없음")
            mux.start()
            val buf = ByteBuffer.allocate(bufSize)
            val info = MediaCodec.BufferInfo()
            var base = -1L
            val last = LongArray(ex.trackCount) { -1L }
            var written = 0L
            while (true) {
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                val ti = ex.sampleTrackIndex
                if (ti >= 0 && map[ti] >= 0) {
                    val t = ex.sampleTime
                    if (base < 0) base = t
                    // 방송 시각 기준 타임스탬프를 0부터 시작하도록 보정
                    val pts = maxOf(0L, t - base)
                    last[ti] = maxOf(last[ti], pts)
                    val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                        MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    info.set(0, n, pts, flags)
                    mux.writeSampleData(map[ti], buf, info)
                    written++
                }
                if (!ex.advance()) break
            }
            if (written == 0L) throw java.io.IOException("샘플 없음")
            mux.stop()
        } finally {
            try { mux?.release() } catch (_: Exception) {}
            ex.release()
        }
    }

    fun rawDir(ctx: Context): File {
        val d = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "rec")
        d.mkdirs()
        return d
    }

    @Suppress("unused")
    val publicPath: String get() = "${Environment.DIRECTORY_MOVIES}/Chzzk"
}
