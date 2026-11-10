package com.chzzkrec.app

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast

/** 네이버·숲 로그인 (성인 인증 방송 녹화용). 비밀번호는 해당 사이트 페이지에만 입력되고 앱은 로그인 쿠키만 보관한다 */
class LoginActivity : Activity() {
    private var done = false
    private var soop = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        soop = intent.getStringExtra("site") == "soop"
        val web = WebView(this)
        val root = FrameLayout(this).apply { fitsSystemWindows = true; addView(web) }
        setContentView(root)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) { check() }
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) { check() }
        }
        web.loadUrl(
            if (soop) "https://login.sooplive.com/afreeca/login.php?szFrom=full&request_uri=https%3A%2F%2Fwww.sooplive.com%2F"
            else "https://nid.naver.com/nidlogin.login?url=https%3A%2F%2Fchzzk.naver.com%2F"
        )
    }

    private fun check() {
        if (done) return
        if (soop) {
            val c = CookieManager.getInstance().getCookie("https://live.sooplive.com") ?: return
            if (Regex("(^|;\\s*)AuthTicket=[^;]+").containsMatchIn(c)) {
                done = true
                Store.setSoopCookies(this, c)
                Toast.makeText(this, "숲에 로그인되었습니다", Toast.LENGTH_SHORT).show()
                finish()
            }
            return
        }
        val all = CookieManager.getInstance().getCookie("https://nid.naver.com") ?: return
        val map = all.split(';').mapNotNull {
            val p = it.trim()
            val i = p.indexOf('=')
            if (i > 0) p.substring(0, i) to p.substring(i + 1) else null
        }.toMap()
        val aut = map["NID_AUT"]
        val ses = map["NID_SES"]
        if (!aut.isNullOrEmpty() && !ses.isNullOrEmpty()) {
            done = true
            Store.setCookies(this, "NID_AUT=$aut; NID_SES=$ses")
            Toast.makeText(this, "로그인되었습니다", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
