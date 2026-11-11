package com.amiljan.blablacheck

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.*

data class GeoPoint(val lat: Double, val lng: Double, val name: String)

/** OpenStreetMap Nominatim geocoding with a persistent cache and the 1 request/second etiquette. */
class Geo(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("geo", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private var last = 0L

    companion object {
        fun haversineKm(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
            val r = 6371.0
            val dLat = Math.toRadians(bLat - aLat); val dLng = Math.toRadians(bLng - aLng)
            val x = sin(dLat / 2).pow(2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLng / 2).pow(2)
            return 2 * r * asin(sqrt(x))
        }
    }

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val wait = 1100 - (System.currentTimeMillis() - last)
            if (wait > 0) delay(wait)
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.setRequestProperty("User-Agent", "BlaBlaCheck/0.1 (personal Android app)")
                c.setRequestProperty("Accept-Language", "en")
                c.connectTimeout = 10_000; c.readTimeout = 15_000
                if (c.responseCode != 200) null else c.inputStream.bufferedReader().readText()
            } catch (e: Exception) { null } finally { last = System.currentTimeMillis() }
        }
    }

    /** Place name to coordinates (biased to the region this app is about). Null if unknown or offline. */
    suspend fun geocode(q: String): GeoPoint? {
        val key = "g:" + q.trim().lowercase()
        prefs.getString(key, null)?.let { c -> c.split("|").let { return GeoPoint(it[0].toDouble(), it[1].toDouble(), it.drop(2).joinToString("|")) } }
        val body = get("https://nominatim.openstreetmap.org/search?format=json&limit=1&countrycodes=hr,at,de,si,hu,rs,ba,cz,sk,it,ch,me&q=" +
            URLEncoder.encode(q, "UTF-8")) ?: return null
        val o = (Json.parseToJsonElement(body) as? JsonArray)?.firstOrNull()?.jsonObject ?: return null
        val p = GeoPoint(o["lat"]!!.jsonPrimitive.content.toDouble(), o["lon"]!!.jsonPrimitive.content.toDouble(),
            o["display_name"]?.jsonPrimitive?.content ?: q)
        prefs.edit().putString(key, "${p.lat}|${p.lng}|${p.name}").apply()
        return p
    }
}
