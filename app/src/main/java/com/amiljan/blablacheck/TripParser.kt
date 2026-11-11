package com.amiljan.blablacheck

/**
 * The trip page ships its data as a JS object literal (not strict JSON), so this is a targeted regex parse of
 * `"itinerary":{"legs":[ {type:activeStop|inactiveStop, formattedTime, address:{primary,secondary}, action:{coordinates}} ... ]}`.
 */
object TripParser {
    private fun unescape(s: String) = Regex("""\\u([0-9a-fA-F]{4})""").replace(s) { it.groupValues[1].toInt(16).toChar().toString() }
    private fun str(chunk: String, key: String) = Regex(""""$key":"((?:[^"\\]|\\.)*)"""").find(chunk)?.groupValues?.get(1)?.let(::unescape)

    fun legs(html: String): List<Leg> {
        val start = html.indexOf(""""itinerary":{"legs":[""")
        if (start < 0) return emptyList()
        val end = html.indexOf(""""itinerarySummary"""", start).let { if (it < 0) minOf(html.length, start + 8000) else it }
        val seg = html.substring(start, end)
        return seg.split(Regex("""(?="type":"(?:activeStop|inactiveStop)")""")).mapNotNull { piece ->
            val chunk = piece.removePrefix(""""type":"""")
            val active = when {
                chunk.startsWith("activeStop") -> true
                chunk.startsWith("inactiveStop") -> false
                else -> return@mapNotNull null
            }
            val city = Regex(""""primary":"((?:[^"\\]|\\.)*)"""").find(chunk)?.groupValues?.get(1)?.let(::unescape) ?: return@mapNotNull null
            Leg(
                active = active,
                time = Regex(""""formattedTime":"(\d{1,2}:\d{2})"""").find(chunk)?.groupValues?.get(1),
                city = city,
                street = str(chunk, "secondary"),
                lat = Regex(""""latitude":(-?[\d.]+)""").find(chunk)?.groupValues?.get(1)?.toDoubleOrNull(),
                lng = Regex(""""longitude":(-?[\d.]+)""").find(chunk)?.groupValues?.get(1)?.toDoubleOrNull(),
            )
        }
    }
}
