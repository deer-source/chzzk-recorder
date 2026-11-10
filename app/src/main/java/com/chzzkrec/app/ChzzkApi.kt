package com.chzzkrec.app

import org.json.JSONObject
import java.net.URLEncoder

data class LiveInfo(
    val channelName: String,
    val open: Boolean,
    val liveId: Long,
    val title: String,
    val adult: Boolean,
    val hlsUrl: String?,
)

object ChzzkApi {
    private const val BASE = "https://api.chzzk.naver.com"
    private val ID = Regex("[0-9a-f]{32}")

    /** 주소나 글자 속에서 채널 ID(32자리)를 찾는다 */
    fun parseChannelId(text: String): String? = ID.find(text.lowercase())?.value

    /** 방송 정보. 한 번도 방송한 적 없는 채널이면 null */
    fun liveDetail(channelId: String, cookies: String?): LiveInfo? {
        val body = Net.getTextAnyStatus("$BASE/service/v2/channels/$channelId/live-detail", cookies)
        val root = JSONObject(body)
        if (root.optInt("code") != 200) throw java.io.IOException(root.optString("message", "API 오류"))
        val c = root.optJSONObject("content") ?: return null
        var hls: String? = null
        val pj = c.optString("livePlaybackJson", "")
        if (pj.isNotEmpty() && pj != "null") {
            try {
                val media = JSONObject(pj).optJSONArray("media")
                if (media != null) for (i in 0 until media.length()) {
                    val m = media.getJSONObject(i)
                    if (m.optString("mediaId") == "HLS") { hls = m.optString("path"); break }
                }
            } catch (_: Exception) {}
        }
        return LiveInfo(
            channelName = c.optJSONObject("channel")?.optString("channelName", "") ?: "",
            open = c.optString("status") == "OPEN",
            liveId = c.optLong("liveId"),
            title = if (c.isNull("liveTitle")) "" else c.optString("liveTitle", ""),
            adult = c.optBoolean("adult"),
            hlsUrl = hls?.takeIf { it.isNotEmpty() },
        )
    }

    /** 채널 이름 (방송 기록이 없어도 조회됨) */
    fun channelName(channelId: String): String? = try {
        val root = JSONObject(Net.getTextAnyStatus("$BASE/service/v1/channels/$channelId"))
        root.optJSONObject("content")?.optString("channelName", "")?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }

    /** 채널명 검색 → (채널ID, 채널명) 목록 */
    fun search(keyword: String): List<Pair<String, String>> {
        val q = URLEncoder.encode(keyword, "UTF-8")
        val root = JSONObject(Net.getTextAnyStatus("$BASE/service/v1/search/channels?keyword=$q&offset=0&size=15"))
        val data = root.optJSONObject("content")?.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until data.length()) {
            val ch = data.getJSONObject(i).optJSONObject("channel") ?: continue
            val id = ch.optString("channelId")
            if (id.isNotEmpty()) out.add(id to ch.optString("channelName", id))
        }
        return out
    }
}
