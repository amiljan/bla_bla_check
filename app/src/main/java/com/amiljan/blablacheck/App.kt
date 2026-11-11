package com.amiljan.blablacheck

import android.app.Application

class App : Application() {
    val repo by lazy { Repo(this) }
    val notifier by lazy { Notifier(this, repo) }
    val fetcher by lazy { WebFetcher(this) }
    val geo by lazy { Geo(this) }
    /** Trip legs by ride id, so a ride's detail page is fetched once per app run. */
    val legsCache = java.util.concurrent.ConcurrentHashMap<String, List<Leg>>()
}
