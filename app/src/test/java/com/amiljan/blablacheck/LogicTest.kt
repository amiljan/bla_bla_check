package com.amiljan.blablacheck

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LogicTest {
    private fun cands(n: Int) = (0 until n).map { Candidate("T$it", null, 0.0, 0.0, (it + 1) * 20.0) }

    @Test fun finderPicksClosestToTargetWithFewSearches() = runBlocking {
        val base = 13.59
        val f = PriceFinder(cands(20), base, quoteAt = { c -> Quote(c, base + c.kmFromPickup * 0.1, "u${c.town}") })
        val r = f.find(listOf(10, 20))
        // price = base + 0.1/km -> +10 at 100 km (T4), +20 at 200 km (T9)
        assertEquals("T4", r[0].quote!!.candidate.town)
        assertEquals("T9", r[1].quote!!.candidate.town)
        assertTrue("used ${f.searches} searches", f.searches <= 12)
    }

    @Test fun finderTreatsNotOfferedAsTooFar() = runBlocking {
        val base = 10.0
        // ride is only offered from towns within 90 km of the boarding stop
        val f = PriceFinder(cands(20), base, quoteAt = { c -> if (c.kmFromPickup > 90) null else Quote(c, base + c.kmFromPickup * 0.1, "u") })
        val r = f.find(listOf(10, 20))
        assertEquals("T3", r[0].quote!!.candidate.town) // 80 km (+8) is the best reachable, nearest to +10
        assertNotNull(r[1].quote)
        assertTrue(r[1].note.isNotEmpty())
    }

    @Test fun finderReportsNothingWhenNeverOffered() = runBlocking {
        val r = PriceFinder(cands(8), 10.0, quoteAt = { null }).find(listOf(10))
        assertNull(r[0].quote)
    }

    @Test fun finderRespectsBudget() = runBlocking {
        val f = PriceFinder(cands(64), 10.0, maxQuotes = 3, quoteAt = { c -> Quote(c, 10.0 + c.kmFromPickup * 0.01, "u") })
        f.find(listOf(10, 20))
        assertTrue(f.searches <= 3)
    }

    @Test fun routeSamplingWalksBackFromPickup() {
        val route = listOf(LatLng(48.0, 12.0), LatLng(47.0, 12.0)) // ~111 km north-south
        val s = Route.sampleFromEnd(route, 30.0)
        assertEquals(30.0, s[0].second, 0.01)
        assertEquals(47.27, s[0].first.lat, 0.01)
        assertEquals(60.0, s[1].second, 0.01)
        assertEquals(48.0, s.last().first.lat, 0.0001) // origin always last
        assertEquals(111.2, s.last().second, 0.5)
    }

    @Test fun tripParserFindsRealOriginBeforeBoardingStop() {
        val html = """x "itinerary":{"legs":[{"type":"inactiveStop","address":{"primary":"Straubing"},"a11yLabel":"x"},{"type":"activeStop","formattedTime":"01:40","address":{"primary":"Graz","secondary":"OMV"},"action":{"type":"EMBEDDED_MAP","coordinates":{"latitude":47.05,"longitude":15.4}}},{"type":"inactiveStop","address":{"primary":"Slavonski Brod"}}]},"itinerarySummary":{} """
        val legs = TripParser.legs(html)
        assertEquals(listOf("Straubing", "Graz", "Slavonski Brod"), legs.map { it.city })
        assertFalse(legs[0].active); assertTrue(legs[1].active)
        assertEquals(47.05, legs[1].lat!!, 0.0001); assertEquals("01:40", legs[1].time); assertEquals("OMV", legs[1].street)
        assertNull(legs[0].lat)
    }

    @Test fun searchParserReadsCarpoolPriceAndSkipsBus() {
        val json = """[{"search_uuid":"U1","results_content":{"results":{"search_items":[
          {"summary_header":{}},
          {"trip":{"multimodal_id":{"source":"BUS","id":"b"},"travel_details":{"itinerary":{"departure":{"time":"01:00"},"arrival":{"time":"02:00"}}},"price":{"tokenized_price":[]}}},
          {"trip":{"multimodal_id":{"source":"CARPOOLING","id":"c1"},"travel_details":{"itinerary":{"departure":{"primary_location":"Graz","time":"06:00","coordinates":{"latitude":47.0,"longitude":15.4}},"arrival":{"time":"08:50"},"is_overnight":false},"provider":{"profiles":[{"driver":{"name":"Luka"}}]}},
           "price":{"tokenized_price":[{"token_type":"INTEGER","token":"13"},{"token_type":"FRACTION","token":",59"},{"token_type":"CURRENCY","token":" €"}]}}}]}}}]"""
        val r = Parser.rides(json)
        assertEquals(1, r.size)
        assertEquals("c1", r[0].id); assertEquals(13.59, r[0].price!!, 0.001); assertEquals("Luka", r[0].driver)
        assertEquals("13,59 €", r[0].priceText); assertTrue(r[0].url().contains("search_uuid=U1"))
    }
}
