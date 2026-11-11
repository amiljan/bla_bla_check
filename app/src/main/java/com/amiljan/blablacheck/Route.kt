package com.amiljan.blablacheck

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL

data class LatLng(val lat: Double, val lng: Double)

object Route {
    /** Driving route geometry from [a] to [b] via the public OSRM demo server (no key). Null when unavailable. */
    suspend fun osrm(a: LatLng, b: LatLng): List<LatLng>? = withContext(Dispatchers.IO) {
        try {
            val u = "https://router.project-osrm.org/route/v1/driving/${a.lng},${a.lat};${b.lng},${b.lat}?overview=simplified&geometries=geojson"
            val c = URL(u).openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent", "BlaBlaCheck/0.1 (personal Android app)")
            c.connectTimeout = 10_000; c.readTimeout = 20_000
            if (c.responseCode != 200) return@withContext null
            val j = Json.parseToJsonElement(c.inputStream.bufferedReader().readText()).jsonObject
            j["routes"]!!.jsonArray[0].jsonObject["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
                .map { LatLng(it.jsonArray[1].jsonPrimitive.double, it.jsonArray[0].jsonPrimitive.double) }
        } catch (e: Exception) { null }
    }

    fun lengthKm(route: List<LatLng>) = route.zipWithNext().sumOf { (a, b) -> Geo.haversineKm(a.lat, a.lng, b.lat, b.lng) }

    /**
     * Walks the polyline from its END (your boarding stop) back toward its START (the driver's origin) and returns points
     * every [stepKm], each with its distance in km from the end. The very start (the origin) is always included last.
     */
    fun sampleFromEnd(route: List<LatLng>, stepKm: Double): List<Pair<LatLng, Double>> {
        if (route.size < 2) return emptyList()
        val out = ArrayList<Pair<LatLng, Double>>()
        var walked = 0.0; var next = stepKm
        for (i in route.lastIndex downTo 1) {
            val a = route[i]; val b = route[i - 1]
            val seg = Geo.haversineKm(a.lat, a.lng, b.lat, b.lng)
            while (seg > 0 && walked + seg >= next) {
                val t = (next - walked) / seg
                out += LatLng(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t) to next
                next += stepKm
            }
            walked += seg
        }
        out += route.first() to walked
        return out
    }
}
