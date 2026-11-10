package com.chzzkrec.app

import android.content.Context

/** 채널 ID가 "soop:"으로 시작하면 숲, 아니면 치지직 */
object Sites {
    const val SOOP = "soop:"
    fun isSoop(id: String) = id.startsWith(SOOP)
    fun rawId(id: String) = id.removePrefix(SOOP)
    fun tag(id: String) = if (isSoop(id)) "[숲] " else "[치지직] "
    fun referer(id: String) = if (isSoop(id)) SoopApi.referer(rawId(id)) else Net.CHZZK

    /** 방송 상태. 치지직에서 방송 기록이 없는 채널이면 null */
    fun liveDetail(ctx: Context, id: String): LiveInfo? =
        if (isSoop(id)) SoopApi.live(rawId(id), Store.soopCookies(ctx)).info
        else ChzzkApi.liveDetail(id, Store.cookies(ctx))

    fun name(id: String): String? =
        if (isSoop(id)) SoopApi.nick(rawId(id)) else ChzzkApi.channelName(id)

    /** 붙여넣은 주소에서 채널 ID를 찾는다 */
    fun parse(text: String): String? {
        SoopApi.parseId(text)?.let { return SOOP + it }
        return ChzzkApi.parseChannelId(text)
    }
}
