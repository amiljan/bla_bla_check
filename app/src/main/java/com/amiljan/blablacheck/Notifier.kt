package com.amiljan.blablacheck

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class Notifier(private val ctx: Context, private val repo: Repo) {
    companion object {
        const val CH_MATCH = "matches"; const val CH_WATCH = "watcher"; const val CH_ALERT = "alerts"
        const val FG_ID = 1
    }
    private var nextId = 100
    private var lastCaptchaAlert = 0L

    init {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_MATCH, "Ride matches", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_WATCH, "Watcher status", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Problems", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun foreground(text: String): Notification =
        NotificationCompat.Builder(ctx, CH_WATCH)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("BlaBla Check is watching")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()

    private fun canPost() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun post(channel: String, title: String, text: String, url: String?, id: Int = nextId++) {
        if (!canPost()) return
        val b = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
        if (url != null) b.setContentIntent(PendingIntent.getActivity(ctx, id, Intent(Intent.ACTION_VIEW, Uri.parse(url)), PendingIntent.FLAG_IMMUTABLE))
        NotificationManagerCompat.from(ctx).notify(id, b.build())
    }

    /** Local notification plus ntfy.sh push (call from a background thread: ntfy does blocking IO). */
    fun match(w: Watch, r: Ride) {
        val day = LocalDate.parse(w.date).format(DateTimeFormatter.ofPattern("EEE d MMM"))
        val title = "${w.from.label} -> ${w.to.label}, $day"
        val text = "Departs ${r.dep}, arrives ${r.arr}${if (r.overnight) " (+1)" else ""} for ${r.priceText}" +
            (r.driver?.let { " with $it" } ?: "")
        post(CH_MATCH, title, text, r.url())
        ntfy("BlaBla match: $title", text, r.url())
    }

    fun captcha() {
        val now = System.currentTimeMillis()
        if (now - lastCaptchaAlert < 3_600_000) return
        lastCaptchaAlert = now
        val i = PendingIntent.getActivity(ctx, 7, Intent(ctx, CaptchaActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        if (canPost()) NotificationManagerCompat.from(ctx).notify(
            7, NotificationCompat.Builder(ctx, CH_ALERT).setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("BlaBlaCar wants a captcha").setContentText("Tap to solve it once so checking can continue")
                .setContentIntent(i).setAutoCancel(true).build()
        )
        ntfy("BlaBla Check: captcha needed", "Open the app and solve the captcha so checking can continue.", null)
    }

    fun test() {
        post(CH_MATCH, "BlaBla Check test", "Notifications work.", null)
        ntfy("BlaBla Check test", "ntfy push works.", null)
    }

    fun ntfy(title: String, body: String, click: String?) {
        val topic = repo.ntfyTopic
        if (topic.isBlank()) return
        try {
            val c = URL("https://ntfy.sh/$topic").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10_000; c.readTimeout = 10_000
            c.setRequestProperty("Title", title.filter { it.code in 32..126 })
            c.setRequestProperty("Tags", "car")
            if (click != null) c.setRequestProperty("Click", click)
            c.outputStream.use { it.write(body.toByteArray()) }
            c.responseCode; c.disconnect()
        } catch (e: Exception) { android.util.Log.w("BBC", "ntfy failed: $e") }
    }
}
