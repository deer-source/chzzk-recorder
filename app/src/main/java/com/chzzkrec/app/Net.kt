package com.chzzkrec.app

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 외부 라이브러리 없이 쓰는 간단한 HTTP 도우미 */
object Net {
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    class HttpError(val code: Int) : IOException("HTTP $code")

    private fun open(url: String, cookies: String?): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "*/*")
        c.setRequestProperty("Referer", "https://chzzk.naver.com/")
        c.setRequestProperty("Origin", "https://chzzk.naver.com")
        if (!cookies.isNullOrEmpty()) c.setRequestProperty("Cookie", cookies)
        return c
    }

    fun getBytes(url: String, cookies: String? = null): ByteArray {
        val c = open(url, cookies)
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

    fun getText(url: String, cookies: String? = null): String =
        String(getBytes(url, cookies), Charsets.UTF_8)

    /** API는 400/404에도 JSON 본문을 주므로 오류 본문까지 읽는다 */
    fun getTextAnyStatus(url: String, cookies: String? = null): String {
        val c = open(url, cookies)
        try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else (c.errorStream ?: throw HttpError(code))
            return stream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }
}
