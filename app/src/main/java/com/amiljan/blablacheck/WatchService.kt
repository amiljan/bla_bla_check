package com.amiljan.blablacheck

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Foreground service: every 5 minutes checks each active watch, notifies on new matches, ends expired watches. */
class WatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private val app get() = application as App

    companion object {
        const val INTERVAL_MS = 5 * 60_000L
        @Volatile private var instance: WatchService? = null
        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, WatchService::class.java))
        /** Check immediately instead of waiting for the next 5-minute tick. */
        fun checkNow(ctx: Context) { val s = instance; if (s != null) s.kick.trySend(Unit) else start(ctx) }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        val n = app.notifier.foreground("Starting…")
        if (Build.VERSION.SDK_INT >= 34) startForeground(Notifier.FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(Notifier.FG_ID, n)
        if (job == null) job = scope.launch { loop() }
        return START_STICKY
    }

    override fun onDestroy() { instance = null; scope.cancel(); super.onDestroy() }

    private suspend fun loop() {
        var forced = true // first pass and "Check now" ignore the spacing rule
        while (true) {
            expire()
            val active = app.repo.active()
            if (active.isEmpty()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return }
            val wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bbc:check")
            wl.acquire(4 * 60_000L)
            try { for (w in active) if (forced || System.currentTimeMillis() - w.lastCheck >= INTERVAL_MS - 15_000) check(w.id) } finally { if (wl.isHeld) wl.release() }
            expire()
            val n = app.repo.active().size
            if (n == 0) continue
            val t = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            getSystemService(android.app.NotificationManager::class.java)
                .notify(Notifier.FG_ID, app.notifier.foreground("$n search${if (n == 1) "" else "es"} active · last check $t"))
            val soonest = app.repo.active().minOf { it.endAt } - System.currentTimeMillis()
            forced = withTimeoutOrNull(minOf(INTERVAL_MS, maxOf(soonest, 1_000L))) { kick.receive() } != null
        }
    }

    private fun expire() {
        for (w in app.repo.active()) Repo.expiry(w)?.let { why ->
            app.repo.update(w.id) { it.copy(active = false, ended = why, lastStatus = "Ended: $why") }
        }
    }

    private suspend fun check(id: String) {
        val w = app.repo.watches.value.firstOrNull { it.id == id && it.active } ?: return
        Log.i("BBC", "check ${w.from.label}->${w.to.label} ${w.date}")
        try {
            val rides = app.fetcher.search(w.from, w.to, w.date)
            val lo = hmToMin(w.arrFrom); val hi = hmToMin(w.arrTo)
            val hits = rides.filter {
                val a = hmToMin(it.arr) + if (it.overnight) 1440 else 0
                a in lo..hi && it.id !in w.seen
            }
            withContext(Dispatchers.IO) { hits.forEach { app.notifier.match(w, it) } }
            app.repo.update(id) {
                it.copy(seen = it.seen + hits.map { h -> h.id }, matches = it.matches + hits.size,
                    lastCheck = System.currentTimeMillis(),
                    lastStatus = "OK: ${rides.size} rides that day, ${hits.size} new match${if (hits.size == 1) "" else "es"}")
            }
        } catch (e: Blocked) {
            val t = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(e.until))
            app.repo.update(id) { it.copy(lastCheck = System.currentTimeMillis(), lastStatus = "Blocked by BlaBlaCar, retrying after $t") }
            withContext(Dispatchers.IO) { app.notifier.blocked(e.until) }
        } catch (e: CaptchaRequired) {
            app.repo.update(id) { it.copy(lastCheck = System.currentTimeMillis(), lastStatus = "Captcha needed: open Settings > Solve captcha") }
            withContext(Dispatchers.IO) { app.notifier.captcha() }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            Log.w("BBC", "check failed", e)
            app.repo.update(id) { it.copy(lastCheck = System.currentTimeMillis(), lastStatus = "Error: ${e.message ?: e.javaClass.simpleName}") }
        }
    }
}
