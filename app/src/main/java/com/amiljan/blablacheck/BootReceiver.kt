package com.amiljan.blablacheck

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val app = ctx.applicationContext as App
        app.repo.watches.value.filter { it.active && Repo.expiry(it) == null }.ifEmpty { return }
        WatchService.start(ctx)
    }
}
