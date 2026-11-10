package com.chzzkrec.app

import org.json.JSONObject
import java.net.URLEncoder

/** 숲(SOOP, 옛 아프리카TV) 방송 정보와 스트림 주소 */
object SoopApi {
    private const val API = "https://live.sooplive.com/afreeca/player_live_api.php"

    class Live(
        val info: LiveInfo,
        val bno: String,
        val rmd: String,
        val cdn: String,
        /** 화질 이름과 표시 이름, 높은 화질부터 */
        val presets: List<Pair<String, String>>,
        val passwordLocked: Boolean,
        val loginRequired: Boolean,
    )

    class Stream(val url: String, val aid: String, val quality: String)

    fun referer(bid: String) = "https://play.sooplive.com/$bid"

    private val URL_ID = Regex(
        "(?:sooplive\\.com|sooplive\\.co\\.kr|afreecatv\\.com)/(?:#/player/|station/)?([A-Za-z0-9_]+)"
    )

    /** 숲 주소에서 스트리머 아이디를 찾는다 */
    fun parseId(text: String): String? {
        val id = URL_ID.find(text)?.groupValues?.get(1) ?: return null
        return id.takeIf { it.lowercase() !in setOf("station", "player", "live", "directory", "search") }
    }

    private fun form(vararg kv: Pair<String, String>): String =
        kv.joinToString("&") { "${it.first}=${URLEncoder.encode(it.second, "UTF-8")}" }

    private fun call(bid: String, cookies: String?, vararg extra: Pair<String, String>): JSONObject {
        val body = form(
            "bid" to bid, "from_api" to "0", "mode" to "landing",
            "player_type" to "html5", "stream_type" to "common", *extra,
        )
        val text = Net.postForm("$API?bjid=$bid", body, cookies, referer(bid))
        return JSONObject(text).optJSONObject("CHANNEL") ?: throw java.io.IOException("숲 응답 오류")
    }

    private fun str(o: JSONObject, k: String): String = if (o.isNull(k)) "" else o.optString(k, "")

    fun live(bid: String, cookies: String?): Live {
        val c = call(bid, cookies, "type" to "live", "bno" to "", "pwd" to "")
        val result = c.optInt("RESULT", 0)
        val login = result == -6
        val open = result == 1 || login
        val presets = ArrayList<Triple<String, String, Long>>()
        val vp = c.optJSONArray("VIEWPRESET")
        if (vp != null) for (i in 0 until vp.length()) {
            val p = vp.getJSONObject(i)
            val name = p.optString("name")
            if (name.isEmpty() || name == "auto") continue
            val res = p.optString("label_resolution").filter { it.isDigit() }.toLongOrNull() ?: 0L
            val bps = p.optString("bps").filter { it.isDigit() }.toLongOrNull() ?: 0L
            val label = if (res > 0) "${res}p" else p.optString("label", name)
            // 해상도 우선, 같으면 비트레이트. 정보가 없으면 '원본'을 맨 앞으로
            val score = res * 1_000_000 + bps + (if (name == "original") 500_000 else 0)
            presets.add(Triple(name, label, score))
        }
        presets.sortByDescending { it.third }
        val info = LiveInfo(
            channelName = str(c, "BJNICK"),
            open = open,
            liveId = str(c, "BNO").toLongOrNull() ?: 0L,
            title = str(c, "TITLE"),
            adult = login,
            hlsUrl = null,
        )
        return Live(
            info, str(c, "BNO"), str(c, "RMD"), str(c, "CDN"),
            presets.map { it.first to it.second },
            str(c, "BPWD") == "Y", login,
        )
    }

    /** 가장 높은 화질부터 차례로 시도해 받을 수 있는 스트림을 돌려준다 */
    fun stream(bid: String, lv: Live, cookies: String?): Stream? {
        if (lv.bno.isEmpty() || lv.rmd.isEmpty()) return null
        val rt = when {
            lv.cdn.contains("gs_cdn") -> "gs_cdn_pc_web"
            lv.cdn.contains("lg_cdn") -> "lg_cdn_pc_web"
            else -> lv.cdn
        }
        for ((q, label) in lv.presets) {
            try {
                val a = call(bid, cookies, "type" to "aid", "bno" to lv.bno, "pwd" to "", "quality" to q)
                if (a.optInt("RESULT", 0) != 1) continue
                val aid = str(a, "AID")
                if (aid.isEmpty()) continue
                val url = "${lv.rmd}/broad_stream_assign.html?return_type=${URLEncoder.encode(rt, "UTF-8")}" +
                    "&broad_key=${lv.bno}-common-$q-hls"
                val view = JSONObject(Net.getText(url, cookies, referer(bid))).optString("view_url")
                if (view.isEmpty()) continue
                return Stream(view, aid, label)
            } catch (_: Exception) {
            }
        }
        return null
    }

    /** 스트리머 닉네임 (방송 중이 아닐 때용) */
    fun nick(bid: String): String? {
        for (host in listOf("chapi.sooplive.com", "chapi.sooplive.co.kr")) {
            try {
                val root = JSONObject(Net.getTextAnyStatus("https://$host/api/$bid/station", null, referer(bid)))
                val n = root.optJSONObject("station")?.optString("user_nick", "")
                if (!n.isNullOrEmpty()) return n
            } catch (_: Exception) {
            }
        }
        return null
    }

    /** 스트리머 검색 → (아이디, 닉네임) */
    fun search(keyword: String): List<Pair<String, String>> {
        val q = URLEncoder.encode(keyword, "UTF-8")
        var last: Exception? = null
        for (host in listOf("sch.sooplive.com", "sch.sooplive.co.kr")) {
            try {
                val text = Net.getTextAnyStatus(
                    "https://$host/api.php?m=bjSearch&v=3.0&szOrder=score&szKeyword=$q&nPageNo=1&nListCnt=10&t=json",
                    null, "https://www.sooplive.com/",
                )
                val data = JSONObject(text).optJSONArray("DATA") ?: continue
                val out = ArrayList<Pair<String, String>>()
                for (i in 0 until data.length()) {
                    val o = data.getJSONObject(i)
                    val id = o.optString("user_id")
                    if (id.isNotEmpty()) out.add(id to o.optString("user_nick", id))
                }
                return out
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: java.io.IOException("검색 실패")
    }
}
