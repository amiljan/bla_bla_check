package com.amiljan.blablacheck

import android.app.Application

class App : Application() {
    val repo by lazy { Repo(this) }
    val notifier by lazy { Notifier(this, repo) }
    val fetcher by lazy { WebFetcher(this) }
}
