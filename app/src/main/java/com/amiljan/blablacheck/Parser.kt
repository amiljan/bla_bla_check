package com.amiljan.blablacheck

import kotlinx.serialization.json.*

private fun JsonElement?.o(k: String): JsonElement? = (this as? JsonObject)?.get(k)
private fun JsonElement?.s(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun JsonElement?.d(): Double? = (this as? JsonPrimitive)?.doubleOrNull

object Parser {
    private val json = Json { ignoreUnknownKeys = true }

    /** Parses a JSON array of raw /trip/search/v9 page responses into carpool rides. */
    fun rides(pagesJson: String): List<Ride> {
        val out = ArrayList<Ride>()
        for (page in json.parseToJsonElement(pagesJson).jsonArray) {
            val uuid = page.o("search_uuid").s() ?: ""
            val items = page.o("results_content").o("results").o("search_items") as? JsonArray ?: continue
            for (item in items) {
                val t = item.o("trip") ?: continue
                val mm = t.o("multimodal_id")
                if (mm.o("source").s() != "CARPOOLING") continue
                val id = mm.o("id").s() ?: continue
                val it = t.o("travel_details").o("itinerary")
                val dep = it.o("departure"); val arr = it.o("arrival")
                val toks = t.o("price").o("tokenized_price") as? JsonArray
                val int = toks?.firstOrNull { x -> x.o("token_type").s() == "INTEGER" }.o("token").s()
                val frac = toks?.firstOrNull { x -> x.o("token_type").s() == "FRACTION" }.o("token").s()
                val price = int?.let { i -> (i + (frac ?: "").replace(',', '.')).toDoubleOrNull() }
                val driver = ((t.o("travel_details").o("provider").o("profiles") as? JsonArray)?.firstOrNull()).o("driver").o("name").s()
                out += Ride(
                    id = id,
                    dep = dep.o("time").s() ?: continue,
                    arr = arr.o("time").s() ?: continue,
                    overnight = it.o("is_overnight").s() == "true",
                    price = price,
                    priceText = (toks?.joinToString("") { x -> x.o("token").s() ?: "" } ?: "").trim(),
                    pickupName = dep.o("primary_location").s() ?: "",
                    lat = dep.o("coordinates").o("latitude").d(),
                    lng = dep.o("coordinates").o("longitude").d(),
                    driver = driver,
                    searchUuid = uuid,
                )
            }
        }
        return out
    }
}
