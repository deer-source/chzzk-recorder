package com.chzzkrec.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Channel(val id: String, var name: String, var auto: Boolean)

/** 채널 목록과 로그인 쿠키 저장 */
object Store {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("store", Context.MODE_PRIVATE)

    @Synchronized
    fun channels(ctx: Context): MutableList<Channel> {
        val out = ArrayList<Channel>()
        try {
            val arr = JSONArray(prefs(ctx).getString("channels", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Channel(o.getString("id"), o.optString("name", ""), o.optBoolean("auto")))
            }
        } catch (_: Exception) {}
        return out
    }

    @Synchronized
    fun save(ctx: Context, list: List<Channel>) {
        val arr = JSONArray()
        for (c in list) arr.put(JSONObject().put("id", c.id).put("name", c.name).put("auto", c.auto))
        prefs(ctx).edit().putString("channels", arr.toString()).apply()
    }

    @Synchronized
    fun update(ctx: Context, id: String, f: (Channel) -> Unit) {
        val list = channels(ctx)
        list.firstOrNull { it.id == id }?.let { f(it); save(ctx, list) }
    }

    fun cookies(ctx: Context): String? = prefs(ctx).getString("cookies", null)?.takeIf { it.isNotEmpty() }

    fun setCookies(ctx: Context, v: String?) {
        prefs(ctx).edit().putString("cookies", v ?: "").apply()
    }
}
