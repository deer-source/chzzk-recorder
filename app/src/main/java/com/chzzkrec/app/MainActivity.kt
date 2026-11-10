package com.chzzkrec.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private class Row(val root: View, val name: TextView, val status: TextView, val btn: Button, val auto: CheckBox)

    private val ui = Handler(Looper.getMainLooper())
    private val bg = Executors.newFixedThreadPool(2)
    private lateinit var list: LinearLayout
    private lateinit var input: EditText
    private lateinit var loginBtn: Button
    private lateinit var batteryBtn: Button
    private lateinit var empty: TextView
    private val rows = LinkedHashMap<String, Row>()
    private var resumed = false
    private var lastPoll = 0L

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val tick = object : Runnable {
        override fun run() {
            if (!resumed) return
            render()
            if (System.currentTimeMillis() - lastPoll > 20_000) pollLive()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (Store.channels(this).any { it.auto }) RecorderService.send(this, RecorderService.ACT_SYNC)
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        i.action = null
        val id = Sites.parse(text)
        if (id != null) addChannel(id, null) else toast("공유한 내용에서 채널 주소를 찾지 못했습니다")
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        lastPoll = 0
        val pm = getSystemService(PowerManager::class.java)
        batteryBtn.visibility = if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
        ui.post(tick)
    }

    override fun onPause() {
        resumed = false
        ui.removeCallbacks(tick)
        super.onPause()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        root.addView(TextView(this).apply {
            text = "치지직 · 숲 녹화"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(10))
        })

        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "채널명 검색 또는 채널 주소 붙여넣기"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, _, _ -> onAdd(); true }
        }
        addRow.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addRow.addView(Button(this).apply { text = "추가"; setOnClickListener { onAdd() } })
        root.addView(addRow)

        empty = TextView(this).apply {
            text = "녹화할 채널을 추가하세요. (치지직 · 숲)\n치지직·숲 앱의 공유 버튼에서 이 앱을 골라도 추가됩니다."
            setPadding(0, dp(24), 0, dp(24))
            gravity = Gravity.CENTER
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(empty); addView(list) }
        val scroll = ScrollView(this).apply { addView(inner) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(TextView(this).apply {
            text = "저장 위치: 내장 저장공간 / ${Finalizer.FOLDER} (갤러리에서 바로 보임)\n원본 최고 화질 그대로 저장 · 긴 방송은 약 2.5GB마다 파일이 나뉩니다"
            textSize = 12f
            alpha = 0.7f
            setPadding(0, dp(8), 0, dp(4))
        })
        batteryBtn = Button(this).apply {
            text = "배터리 제한 해제 (화면 꺼짐 중 끊김 방지)"
            setOnClickListener {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    )
                } catch (_: Exception) {
                    toast("설정 > 애플리케이션 > 치지직 녹화 > 배터리에서 '제한 없음'을 선택하세요")
                }
            }
        }
        root.addView(batteryBtn)
        loginBtn = Button(this).apply { text = "로그인 관리 (성인 방송용)"; setOnClickListener { onLogin() } }
        root.addView(loginBtn)

        val frame = FrameLayout(this).apply { fitsSystemWindows = true; addView(root) }
        setContentView(frame)
    }

    private fun onLogin() {
        val naver = Store.cookies(this) != null
        val soop = Store.soopCookies(this) != null
        val items = arrayOf(
            if (naver) "네이버(치지직): 로그인됨 — 눌러서 로그아웃" else "네이버(치지직) 로그인",
            if (soop) "숲: 로그인됨 — 눌러서 로그아웃" else "숲 로그인",
        )
        AlertDialog.Builder(this)
            .setTitle("로그인 관리")
            .setItems(items) { _, w ->
                val on = if (w == 0) naver else soop
                if (on) {
                    if (w == 0) Store.setCookies(this, null) else Store.setSoopCookies(this, null)
                    toast("로그아웃했습니다")
                } else {
                    // 이전 로그인 흔적이 남아 있으면 바로 닫히므로 새로 시작
                    CookieManager.getInstance().removeAllCookies(null)
                    startActivity(Intent(this, LoginActivity::class.java).putExtra("site", if (w == 0) "naver" else "soop"))
                }
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun onAdd() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        val id = Sites.parse(text)
        if (id != null) { input.setText(""); addChannel(id, null); return }
        toast("검색 중…")
        bg.execute {
            // 치지직과 숲을 함께 검색
            var failed = 0
            val all = ArrayList<Pair<String, String>>()
            try { ChzzkApi.search(text).forEach { all.add(it.first to "[치지직] ${it.second}") } } catch (e: Exception) { failed++ }
            try {
                SoopApi.search(text).forEach { all.add(Sites.SOOP + it.first to "[숲] ${it.second} (${it.first})") }
            } catch (e: Exception) { failed++ }
            val found: List<Pair<String, String>>? = if (failed == 2) null else all
            ui.post {
                if (isFinishing) return@post
                when {
                    found == null -> toast("검색에 실패했습니다. 채널 주소를 붙여넣어 보세요")
                    found.isEmpty() -> toast("검색 결과가 없습니다")
                    else -> AlertDialog.Builder(this)
                        .setTitle("채널 선택")
                        .setItems(found.map { it.second }.toTypedArray()) { _, w ->
                            input.setText("")
                            addChannel(found[w].first, found[w].second.substringAfter("] ").substringBefore(" ("))
                        }
                        .setNegativeButton("취소", null)
                        .show()
                }
            }
        }
    }

    private fun addChannel(id: String, knownName: String?) {
        if (Store.channels(this).any { it.id == id }) { toast("이미 추가된 채널입니다"); return }
        bg.execute {
            val info = try { Sites.liveDetail(this, id) } catch (_: Exception) { null }
            if (info != null) RecorderService.live[id] = info
            val name = knownName ?: info?.channelName?.takeIf { it.isNotEmpty() }
                ?: Sites.name(id) ?: Sites.rawId(id).take(12)
            val all = Store.channels(this)
            if (all.none { it.id == id }) { all.add(Channel(id, name, false)); Store.save(this, all) }
            ui.post { toast("추가됨: $name"); render() }
        }
    }

    /** 채널들의 방송 여부를 주기적으로 확인 */
    private fun pollLive() {
        lastPoll = System.currentTimeMillis()
        val chans = Store.channels(this)
        bg.execute {
            for (c in chans) {
                if (RecorderService.jobs.containsKey(c.id)) continue
                try {
                    val info = Sites.liveDetail(this, c.id) ?: continue
                    RecorderService.live[c.id] = info
                    if (info.channelName.isNotEmpty() && info.channelName != c.name) {
                        Store.update(this, c.id) { it.name = info.channelName }
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun render() {
        val chans = Store.channels(this)
        if (chans.map { it.id } != rows.keys.toList()) {
            list.removeAllViews()
            rows.clear()
            for (c in chans) { val r = makeRow(c); rows[c.id] = r; list.addView(r.root) }
        }
        empty.visibility = if (chans.isEmpty()) View.VISIBLE else View.GONE
        for (c in chans) {
            val r = rows[c.id] ?: continue
            val job = RecorderService.jobs[c.id]
            val info = RecorderService.live[c.id]
            r.name.text = Sites.tag(c.id) + c.name
            val sb = StringBuilder()
            if (job != null) {
                if (job.bytes > 0) {
                    val sec = (System.currentTimeMillis() - job.startedAt) / 1000
                    sb.append("● 녹화 중 · ${job.quality} · ")
                    sb.append(String.format("%02d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60))
                    sb.append(" · ").append(size(job.bytes))
                } else sb.append(job.status)
            } else if (info == null) sb.append("확인 중…")
            else if (info.open) sb.append("LIVE · ").append(info.title).append(if (info.adult) " (로그인 필요)" else "")
            else sb.append("오프라인")
            RecorderService.errors[c.id]?.let { sb.append("\n⚠ ").append(it) }
            RecorderService.saved[c.id]?.let { sb.append("\n저장 완료: ").append(it) }
            r.status.text = sb
            r.btn.text = if (job != null) "녹화 중지" else "녹화 시작"
            r.btn.isEnabled = job == null || !job.stopRequested
        }
    }

    private fun size(b: Long): String =
        if (b >= 1 shl 30) String.format("%.2fGB", b / 1073741824.0) else String.format("%.0fMB", b / 1048576.0)

    private fun makeRow(c: Channel): Row {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(4))
        }
        val name = TextView(this).apply { textSize = 17f; setTypeface(typeface, Typeface.BOLD) }
        val status = TextView(this).apply { textSize = 13f; setPadding(0, dp(2), 0, dp(4)) }
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val btn = Button(this)
        val auto = CheckBox(this).apply { text = "자동 녹화"; isChecked = c.auto }
        val del = Button(this).apply { text = "삭제" }
        line.addView(btn)
        line.addView(auto, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
        line.addView(del)
        box.addView(name); box.addView(status); box.addView(line)

        btn.setOnClickListener {
            if (RecorderService.jobs.containsKey(c.id)) {
                RecorderService.send(this, RecorderService.ACT_STOP, c.id)
            } else {
                RecorderService.errors.remove(c.id)
                RecorderService.send(this, RecorderService.ACT_START, c.id)
            }
        }
        auto.setOnCheckedChangeListener { _, on ->
            Store.update(this, c.id) { it.auto = on }
            RecorderService.send(this, RecorderService.ACT_SYNC)
            if (on) toast("방송이 시작되면 자동으로 녹화합니다")
        }
        del.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("'${c.name}' 채널을 목록에서 삭제할까요?\n(이미 저장된 녹화 파일은 그대로 남습니다)")
                .setPositiveButton("삭제") { _, _ ->
                    if (RecorderService.jobs.containsKey(c.id)) RecorderService.send(this, RecorderService.ACT_STOP, c.id)
                    Store.save(this, Store.channels(this).filter { it.id != c.id })
                    RecorderService.send(this, RecorderService.ACT_SYNC)
                    render()
                }
                .setNegativeButton("취소", null)
                .show()
        }
        return Row(box, name, status, btn, auto)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        bg.shutdown()
        super.onDestroy()
    }
}
