package com.amiljan.blablacheck

import kotlinx.serialization.Serializable

/** The four cities the app supports. placeId is the base64 id returned by blablacar's /location/suggestions. */
enum class City(val label: String, val lat: Double, val lng: Double, val placeId: String) {
    KRAPINA("Krapina", 46.1608, 15.8808, "eyJpIjoiQ2hJSndfZkdacEx0WlVjUlV6QXZmR3diYU1zIiwicCI6MSwidiI6MSwidCI6WzRdfQ=="),
    ZAGREB("Zagreb", 45.8150, 15.9819, "eyJpIjoiQ2hJSk9jd0N5WkxXWlVjUmlzTDdLSllrUlRvIiwicCI6MSwidiI6MSwidCI6WzRdfQ=="),
    GRAZ("Graz", 47.0707, 15.4395, "eyJpIjoiQ2hJSnUyVXdGNGMxYmtjUm05M2YwdEdLanY0IiwicCI6MSwidiI6MSwidCI6WzRdfQ=="),
    VIENNA("Vienna", 48.2082, 16.3738, "eyJpIjoiQ2hJSm44bzJVWjRIYlVjUlJsdWlVWXJsd3YwIiwicCI6MSwidiI6MSwidCI6WzRdfQ==");
}

/** One ongoing search: a single direction, one date, an arrival-time window and a hard stop time. */
@Serializable
data class Watch(
    val id: String,
    val from: City,
    val to: City,
    val date: String,            // ISO yyyy-MM-dd (travel date)
    val arrFrom: String,         // HH:mm, start of arrival window
    val arrTo: String,           // HH:mm, end of arrival window
    val endAt: Long,             // epoch millis: stop searching at this moment (never later than end of travel date)
    val active: Boolean = true,
    val seen: Set<String> = emptySet(),
    val lastCheck: Long = 0,
    val lastStatus: String = "Not checked yet",
    val matches: Int = 0,
    val ended: String? = null,   // reason when finished/expired
)

/** A carpool ride as shown on a search result card. Location/time are the passenger's pick-up, not the driver's origin. */
data class Ride(
    val id: String,
    val dep: String,             // HH:mm
    val arr: String,             // HH:mm
    val overnight: Boolean,
    val price: Double?,
    val priceText: String,
    val pickupName: String,
    val lat: Double?,
    val lng: Double?,
    val driver: String?,
    val searchUuid: String,
) {
    fun url() = "https://www.blablacar.hr/trip?source=CARPOOLING&id=$id&requested_seats=1&search_origin=HOME&search_uuid=$searchUuid"
}

class CaptchaRequired : Exception("BlaBlaCar is asking for a captcha")
class FetchError(msg: String) : Exception(msg)

fun hmToMin(s: String): Int { val p = s.split(":"); return p[0].toInt() * 60 + p[1].toInt() }
