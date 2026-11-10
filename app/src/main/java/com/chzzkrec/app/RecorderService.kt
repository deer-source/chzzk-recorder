package com.chzzkrec.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** 녹화와 자동 녹화 감시를 맡는 포그라운드 서비스 (화면이 꺼져도 계속 동작) */
class RecorderService : Service() {

    companion object {
        const val ACT_START = "start"
        const val ACT_STOP = "stop"
        const val ACT_STOP_ALL = "stop_all"
        const val ACT_SYNC = "sync"
        const val EXTRA_ID = "id"
        private const val NOTIF_ID = 1
        private const val CH = "rec"

        val jobs = ConcurrentHashMap<String, RecordJob>()
        val live = ConcurrentHashMap<String, LiveInfo>()
        val errors = ConcurrentHashMap<String, String>()
        val saved = ConcurrentHashMap<String, String>()
        val finalizing = AtomicInteger(0)
        /** 사용자가 직접 멈춘 방송은 같은 방송 동안 자동으로 다시 녹화하지 않는다 */
        val skipLive = ConcurrentHashMap<String, Long>()

        fun send(ctx: Context, action: String, id: String? = null) {
            val i = Intent(ctx, RecorderService::class.java).setAction(action)
            if (id != null) i.putExtra(EXTRA_ID, id)
            ctx.startForegroundService(i)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val finalizer = Executors.newSingleThreadExecutor()
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var monitor: Thread? = null
    @Volatile private var alive = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!alive) return
            notifyNow()
            main.postDelayed(this, 5000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        alive = true
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "녹화 상태", NotificationManager.IMPORTANCE_LOW))

        val pm = getSystemService(PowerManager::class.java)
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "chzzkrec:rec").apply { setReferenceCounted(false) }
        @Suppress("DEPRECATION")
        wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "chzzkrec:wifi").apply { setReferenceCounted(false) }

        // 지난번에 앱이 강제 종료되어 남은 원본이 있으면 마저 저장
        if (jobs.isEmpty() && finalizing.get() == 0) {
            Finalizer.rawDir(this).listFiles()?.filter { it.name.contains(".raw.") }?.forEach {
                enqueueFinalize("", it)
            }
        }

        monitor = Thread({ monitorLoop() }, "monitor").also { it.start() }
        main.postDelayed(ticker, 5000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg()
        try { wake?.acquire() } catch (_: Exception) {}
        try { wifi?.acquire() } catch (_: Exception) {}

        val id = intent?.getStringExtra(EXTRA_ID)
        when (intent?.action) {
            ACT_START -> if (id != null) { skipLive.remove(id); startJob(id) }
            ACT_STOP -> if (id != null) jobs[id]?.requestStop(true)
            ACT_STOP_ALL -> jobs.values.forEach { it.requestStop(true) }
        }
        monitor?.interrupt() // 설정이 바뀌었으니 바로 한 번 확인
        main.postDelayed({ checkIdle() }, 1500)
        notifyNow()
        return START_STICKY
    }

    private fun startFg() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun startJob(id: String) {
        if (jobs.containsKey(id)) return
        errors.remove(id)
        saved.remove(id)
        val job = RecordJob(
            applicationContext, id,
            onPartDone = { f -> enqueueFinalize(id, f) },
            onEnd = { j ->
                main.post {
                    jobs.remove(id, j)
                    if (j.stoppedByUser && j.liveId != 0L) skipLive[id] = j.liveId
                    j.error?.let { errors[id] = it }
                    notifyNow()
                    checkIdle()
                }
            },
        )
        jobs[id] = job
        job.start()
        notifyNow()
    }

    private fun enqueueFinalize(id: String, f: File) {
        finalizing.incrementAndGet()
        val task = Runnable {
            try {
                val name = Finalizer.finalize(applicationContext, f)
                if (name.isNotEmpty() && id.isNotEmpty()) saved[id] = name
            } catch (e: Exception) {
                if (id.isNotEmpty()) errors[id] = "저장 실패: ${e.message}"
            } finally {
                finalizing.decrementAndGet()
                main.post { notifyNow(); checkIdle() }
            }
        }
        try { finalizer.execute(task) } catch (_: Exception) { Thread(task).start() }
    }

    /** 자동 녹화가 켜진 채널의 방송 시작을 감시 */
    private fun monitorLoop() {
        while (alive) {
            try {
                for (ch in Store.channels(this).filter { it.auto }) {
                    if (!alive) break
                    if (jobs.containsKey(ch.id)) continue
                    try {
                        val info = ChzzkApi.liveDetail(ch.id, Store.cookies(this)) ?: continue
                        live[ch.id] = info
                        if (info.open && skipLive[ch.id] != info.liveId) {
                            main.post { if (alive) startJob(ch.id) }
                        }
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
            try { Thread.sleep(40_000) } catch (_: InterruptedException) {}
        }
    }

    private fun autoCount(): Int = Store.channels(this).count { it.auto }

    private fun checkIdle() {
        if (!alive) return
        if (jobs.isEmpty() && finalizing.get() == 0 && autoCount() == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun buildNotification(): Notification {
        val rec = jobs.size
        val auto = autoCount()
        val text = buildString {
            if (rec > 0) append("녹화 중 ${rec}개")
            if (finalizing.get() > 0) { if (isNotEmpty()) append(" · "); append("저장 중") }
            if (auto > 0) { if (isNotEmpty()) append(" · "); append("자동 녹화 대기 ${auto}개") }
            if (isEmpty()) append("대기 중")
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("치지직 녹화")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (rec > 0) {
            val stop = PendingIntent.getForegroundService(
                this, 1, Intent(this, RecorderService::class.java).setAction(ACT_STOP_ALL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(Notification.Action.Builder(null, "모두 중지", stop).build())
        }
        return b.build()
    }

    private fun notifyNow() {
        if (!alive) return
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        alive = false
        main.removeCallbacksAndMessages(null)
        monitor?.interrupt()
        jobs.values.forEach { it.requestStop(false) }
        finalizer.shutdown()
        try { wake?.release() } catch (_: Exception) {}
        try { wifi?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
