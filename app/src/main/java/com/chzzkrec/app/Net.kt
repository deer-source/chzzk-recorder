package com.chzzkrec.app

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 외부 라이브러리 없이 쓰는 간단한 HTTP 도우미 */
object Net {
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    const val CHZZK = "https://chzzk.naver.com/"

    class HttpError(val code: Int) : IOException("HTTP $code")

    private fun open(url: String, cookies: String?, referer: String, accept: String = "*/*"): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("Referer", referer)
        val u = URL(referer)
        c.setRequestProperty("Origin", "${u.protocol}://${u.host}")
        if (!cookies.isNullOrEmpty()) c.setRequestProperty("Cookie", cookies)
        return c
    }

    fun getBytes(url: String, cookies: String? = null, referer: String = CHZZK, accept: String = "*/*"): ByteArray {
        val c = open(url, cookies, referer, accept)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw HttpError(code)
            val len = c.contentLength
            val out = ByteArrayOutputStream(if (len > 0) len else 64 * 1024)
            c.inputStream.use { it.copyTo(out, 64 * 1024) }
            return out.toByteArray()
        } finally {
            c.disconnect()
        }
    }

    fun getText(url: String, cookies: String? = null, referer: String = CHZZK, accept: String = "*/*"): String =
        String(getBytes(url, cookies, referer, accept), Charsets.UTF_8)

    /** API는 400/404에도 JSON 본문을 주므로 오류 본문까지 읽는다 */
    fun getTextAnyStatus(url: String, cookies: String? = null, referer: String = CHZZK): String {
        val c = open(url, cookies, referer)
        try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else (c.errorStream ?: throw HttpError(code))
            return stream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    fun postForm(url: String, body: String, cookies: String?, referer: String): String {
        val c = open(url, cookies, referer)
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code !in 200..299) throw HttpError(code)
            return c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    /** 큰 파일 내려받기. 끊기면 받은 곳부터 이어받는다 */
    fun download(url: String, file: File, referer: String, stop: () -> Boolean, progress: (Long, Long) -> Unit) {
        var fails = 0
        var total = -1L
        val buf = ByteArray(256 * 1024)
        while (true) {
            if (stop()) throw InterruptedException()
            val have = if (file.exists()) file.length() else 0L
            if (total > 0 && have >= total) return
            val c = open(url, null, referer)
            try {
                if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
                val code = c.responseCode
                if (code == 416 && have > 0) return
                if (code !in 200..299) throw HttpError(code)
                val append = code == 206
                val len = c.contentLengthLong
                total = if (len >= 0) (if (append) have + len else len) else -1L
                var done = if (append) have else 0L
                FileOutputStream(file, append).use { out ->
                    c.inputStream.use { inp ->
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            fails = 0
                            progress(done, total)
                            if (stop()) throw InterruptedException()
                        }
                    }
                }
                if (total < 0 || done >= total) return
            } catch (e: InterruptedException) {
                throw e
            } catch (e: HttpError) {
                // 주소 만료·권한 오류는 다시 해도 같으므로 바로 실패
                if (e.code in 400..499 && e.code != 408 && e.code != 429) throw e
                fails++
                if (fails > 8) throw e
                Thread.sleep(1500L * fails)
            } catch (e: Exception) {
                fails++
                if (fails > 8) throw e
                Thread.sleep(1500L * fails)
            } finally {
                c.disconnect()
            }
        }
    }
}
