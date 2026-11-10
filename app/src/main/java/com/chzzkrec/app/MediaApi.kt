package com.chzzkrec.app

import android.content.Context
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.net.URLEncoder

/** 클립·캐치·다시보기 주소 종류 */
class MediaRef(val kind: Kind, val id: String, val source: String) {
    enum class Kind { CHZZK_VIDEO, CHZZK_CLIP, SOOP_VOD, SOOP_STORY }
}

/** 실제로 받을 파일 하나 */
class MediaItem(
    val name: String,
    val url: String,
    val hls: Boolean,
    val referer: String,
    /** 치지직 일부 영상에서 조각 주소에 붙여야 하는 인증값 */
    val bgda: String? = null,
)

object MediaApi {
    private val CHZZK_VIDEO = Regex("chzzk\\.naver\\.com/video/(\\d+)")
    private val CHZZK_CLIP = Regex("chzzk\\.naver\\.com/(?:embed/)?clips?/([A-Za-z0-9_-]+)")
    private val SOOP_STORY = Regex("vod\\.(?:sooplive\\.com|sooplive\\.co\\.kr|afreecatv\\.com)/player/(\\d+)/catchstory", RegexOption.IGNORE_CASE)
    private val SOOP_VOD = Regex("vod\\.(?:sooplive\\.com|sooplive\\.co\\.kr|afreecatv\\.com)/(?:player/station|player|st)/(\\d+)", RegexOption.IGNORE_CASE)

    /** 붙여넣은 글에서 클립·캐치·다시보기 주소를 찾는다 */
    fun parse(text: String): MediaRef? {
        CHZZK_VIDEO.find(text)?.let { return MediaRef(MediaRef.Kind.CHZZK_VIDEO, it.groupValues[1], it.value) }
        CHZZK_CLIP.find(text)?.let { return MediaRef(MediaRef.Kind.CHZZK_CLIP, it.groupValues[1], it.value) }
        SOOP_STORY.find(text)?.let { return MediaRef(MediaRef.Kind.SOOP_STORY, it.groupValues[1], it.value) }
        SOOP_VOD.find(text)?.let { return MediaRef(MediaRef.Kind.SOOP_VOD, it.groupValues[1], it.value) }
        return null
    }

    fun resolve(ctx: Context, ref: MediaRef): List<MediaItem> = when (ref.kind) {
        MediaRef.Kind.CHZZK_VIDEO -> chzzkVideo(ref.id, Store.cookies(ctx))
        MediaRef.Kind.CHZZK_CLIP -> chzzkClip(ref.id, Store.cookies(ctx))
        MediaRef.Kind.SOOP_VOD -> soopVod(ref.id, Store.soopCookies(ctx))
        MediaRef.Kind.SOOP_STORY -> soopStory(ref.id, Store.soopCookies(ctx))
    }

    fun clean(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(80).trim().ifEmpty { "video" }

    private fun str(o: JSONObject, k: String): String = if (o.isNull(k)) "" else o.optString(k, "")

    // ---------- 치지직 ----------

    private fun chzzkContent(url: String, cookies: String?): JSONObject {
        val root = JSONObject(Net.getTextAnyStatus(url, cookies))
        if (root.optInt("code") != 200) throw IOException(root.optString("message", "치지직 응답 오류"))
        return root.optJSONObject("content") ?: throw IOException("영상을 찾을 수 없습니다")
    }

    private fun chzzkVideo(no: String, cookies: String?): List<MediaItem> {
        val c = chzzkContent("https://api.chzzk.naver.com/service/v3/videos/$no", cookies)
        val name = clean("${c.optJSONObject("channel")?.optString("channelName", "") ?: ""}_${str(c, "videoTitle")}")
        val videoId = str(c, "videoId")
        val inKey = str(c, "inKey")
        if (videoId.isNotEmpty() && inKey.isNotEmpty()) return listOf(chzzkPlayback(videoId, inKey, name))
        // 아직 변환이 덜 끝난 다시보기는 방송 때의 스트림으로 제공됨
        val pj = str(c, "liveRewindPlaybackJson")
        if (pj.isNotEmpty()) {
            val media = JSONObject(pj).optJSONArray("media")
            if (media != null) for (i in 0 until media.length()) {
                val path = media.getJSONObject(i).optString("path")
                if (path.isNotEmpty()) {
                    val t = Regex("[?&]hdnts=([^&]+)").find(path)?.groupValues?.get(1)
                    return listOf(MediaItem(name, path, true, Net.CHZZK, t))
                }
            }
        }
        throw IOException(if (c.optBoolean("adult")) "성인 영상 — 네이버 로그인이 필요합니다" else "받을 수 있는 영상 주소가 없습니다")
    }

    private fun chzzkClip(id: String, cookies: String?): List<MediaItem> {
        val c = chzzkContent("https://api.chzzk.naver.com/service/v1/play-info/clip/$id", cookies)
        val name = clean("${c.optJSONObject("ownerChannel")?.optString("channelName", "") ?: ""}_${str(c, "contentTitle")}")
        val videoId = str(c, "videoId")
        val inKey = str(c, "inKey")
        if (videoId.isEmpty() || inKey.isEmpty())
            throw IOException(if (c.optBoolean("adult")) "성인 클립 — 네이버 로그인이 필요합니다" else "받을 수 있는 클립 주소가 없습니다")
        return listOf(chzzkPlayback(videoId, inKey, name))
    }

    private class Rep(val height: Int, val bandwidth: Long, val base: String, val m3u: String, val mime: String)

    /** 네이버 동영상 서버에서 화질 목록을 받아 가장 좋은 것을 고른다 */
    private fun chzzkPlayback(videoId: String, inKey: String, name: String): MediaItem {
        val url = "https://apis.naver.com/neonplayer/vodplay/v2/playback/$videoId?key=${URLEncoder.encode(inKey, "UTF-8")}"
        val text = Net.getText(url, null, Net.CHZZK, "application/dash+xml").trim()
        val reps = ArrayList<Rep>()
        var separateAudio = false
        if (text.startsWith("{")) separateAudio = scanJson(JSONObject(text), "", reps)
        else separateAudio = scanMpd(text, url, reps)

        val cmp = compareBy<Rep> { it.height }.thenBy { it.bandwidth }
        // 1순위: 통째로 받을 수 있는 MP4 파일
        if (!separateAudio) {
            reps.filter { it.base.contains(".mp4") && !it.mime.startsWith("audio") }.maxWithOrNull(cmp)
                ?.let { return MediaItem(name, it.base, false, Net.CHZZK) }
        }
        // 2순위: 조각(HLS)으로 제공되는 경우
        reps.filter { it.m3u.isNotEmpty() || it.base.contains(".m3u8") }.maxWithOrNull(cmp)
            ?.let { return MediaItem(name, it.m3u.ifEmpty { it.base }, true, Net.CHZZK) }
        throw IOException("지원하지 않는 영상 형식입니다")
    }

    /** MPD(XML)에서 화질 목록 추출. 소리가 따로 분리된 형식이면 true */
    private fun scanMpd(xml: String, baseUrl: String, out: MutableList<Rep>): Boolean {
        val p = Xml.newPullParser()
        p.setInput(StringReader(xml))
        var setMime = ""
        var audio = false
        var inRep = false
        var h = 0; var bw = 0L; var m3u = ""; var base = ""; var mime = ""
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            val tag = (p.name ?: "").substringAfter(':')
            if (ev == XmlPullParser.START_TAG) {
                when (tag) {
                    "AdaptationSet" -> {
                        setMime = attr(p, "mimeType")
                        if (setMime.startsWith("audio") || attr(p, "contentType") == "audio") audio = true
                    }
                    "Representation" -> {
                        inRep = true
                        h = attr(p, "height").toIntOrNull() ?: 0
                        bw = attr(p, "bandwidth").toLongOrNull() ?: 0L
                        m3u = attr(p, "m3u")
                        mime = attr(p, "mimeType").ifEmpty { setMime }
                        if (mime.startsWith("audio")) audio = true
                        base = ""
                    }
                    "BaseURL" -> if (inRep) {
                        val t = p.nextText().trim()
                        if (t.isNotEmpty()) base = Hls.resolve(baseUrl, t)
                    }
                }
            } else if (ev == XmlPullParser.END_TAG && tag == "Representation") {
                inRep = false
                out.add(Rep(h, bw, base, m3u, mime))
            }
            ev = p.next()
        }
        return audio
    }

    private fun attr(p: XmlPullParser, name: String): String {
        for (i in 0 until p.attributeCount) {
            if (p.getAttributeName(i).substringAfter(':') == name) return p.getAttributeValue(i) ?: ""
        }
        return ""
    }

    /** 같은 정보가 JSON으로 올 때 */
    private fun scanJson(o: JSONObject, parentMime: String, out: MutableList<Rep>): Boolean {
        var audio = false
        val mime = o.optString("mimeType", parentMime)
        if (o.has("representation") && (mime.startsWith("audio") || o.optString("contentType") == "audio")) audio = true
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = o.opt(k)
            if (k == "representation" && v is JSONArray) {
                for (i in 0 until v.length()) {
                    val r = v.optJSONObject(i) ?: continue
                    val b = r.optJSONArray("baseURL")?.optJSONObject(0)?.optString("value", "") ?: ""
                    val m = r.optJSONObject("otherAttributes")?.optString("m3u", "") ?: ""
                    out.add(Rep(r.optInt("height"), r.optLong("bandwidth"), b, m, r.optString("mimeType", mime)))
                }
            } else if (v is JSONObject) {
                if (scanJson(v, mime, out)) audio = true
            } else if (v is JSONArray) {
                for (i in 0 until v.length()) {
                    val e = v.optJSONObject(i) ?: continue
                    if (scanJson(e, mime, out)) audio = true
                }
            }
        }
        return audio
    }

    // ---------- 숲 ----------

    private fun soopVod(no: String, cookies: String?): List<MediaItem> {
        val referer = "https://vod.sooplive.com/player/$no"
        val text = Net.postForm(
            "https://api.m.sooplive.com/station/video/a/view",
            "nTitleNo=$no&nApiLevel=10", cookies, referer,
        )
        val d = JSONObject(text).optJSONObject("data") ?: throw IOException("영상을 찾을 수 없습니다")
        when (d.optInt("code", 0)) {
            -6221 -> throw IOException("없는 영상입니다")
            -6205 -> throw IOException("비공개 영상입니다")
        }
        val name = clean("${str(d, "writer_nick")}_${str(d, "title")}")
        val items = soopFiles(d.optJSONArray("files"), name, referer)
        if (items.isEmpty()) {
            throw IOException(
                when {
                    str(d, "adult_status") == "notLogin" -> "성인 영상 — 숲 로그인이 필요합니다"
                    str(d, "sub_upload_type").isNotEmpty() -> "구독자 전용 영상은 받을 수 없습니다"
                    else -> "받을 수 있는 영상 주소가 없습니다"
                }
            )
        }
        return items
    }

    private fun soopFiles(files: JSONArray?, name: String, referer: String): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        if (files != null) for (i in 0 until files.length()) {
            val f = files.optJSONObject(i)?.optString("file", "") ?: ""
            if (f.startsWith("http")) out.add(MediaItem(name, f, f.contains(".m3u8"), referer))
        }
        return out
    }

    /** 캐치 스토리(캐치 모음) */
    private fun soopStory(no: String, cookies: String?): List<MediaItem> {
        val referer = "https://vod.sooplive.com/player/$no/catchstory"
        val text = Net.getTextAnyStatus(
            "https://api.m.sooplive.com/catchstory/a/view?aStoryListIdx=&nStoryIdx=$no", cookies, referer,
        )
        val data = JSONObject(text).optJSONArray("data") ?: throw IOException("캐치 스토리를 찾을 수 없습니다")
        val out = ArrayList<MediaItem>()
        for (i in 0 until data.length()) {
            val s = data.optJSONObject(i) ?: continue
            if (s.optString("story_type") != "catch") continue
            val list = s.optJSONArray("catch_list") ?: continue
            for (j in 0 until list.length()) {
                val c = list.optJSONObject(j) ?: continue
                out.addAll(soopFiles(c.optJSONArray("files"), clean("${str(c, "writer_nick")}_${str(c, "title")}"), referer).take(1))
            }
        }
        if (out.isEmpty()) throw IOException("받을 수 있는 캐치가 없습니다")
        return out
    }
}
