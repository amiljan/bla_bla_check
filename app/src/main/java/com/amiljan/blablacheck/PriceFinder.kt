package com.amiljan.blablacheck

import kotlin.math.abs

/** A town along the driver's route, ordered nearest-to-your-boarding-stop first. */
data class Candidate(val town: String, val country: String?, val lat: Double, val lng: Double, val kmFromPickup: Double)

/** What the same ride costs if you book it from [candidate] instead of your real boarding stop. */
data class Quote(val candidate: Candidate, val price: Double, val url: String)

data class Pick(val extra: Int, val goal: Double, val quote: Quote?, val note: String)

/**
 * Finds, for each target extra amount (e.g. +10 EUR, +20 EUR), the boarding town whose price for the SAME ride is
 * closest to base + extra. The price of a ride grows with the distance travelled, so it is monotonic along the route
 * and a binary search needs only about log2(n) searches per target (probes are shared between targets).
 *
 * [quoteAt] runs one blablacar search and returns null when the ride is not offered from that town; a null is treated
 * as "too far from the route" and pushes the search back toward your boarding stop. [maxQuotes] hard-caps the number
 * of searches so the site is never hammered.
 */
class PriceFinder(
    private val candidates: List<Candidate>,
    private val base: Double,
    private val maxQuotes: Int = 14,
    private val quoteAt: suspend (Candidate) -> Quote?,
    private val onProbe: (Candidate, Quote?) -> Unit = { _, _ -> },
) {
    private val cache = HashMap<Int, Quote?>()
    var searches = 0; private set

    private suspend fun quote(i: Int): Quote? {
        if (i in cache) return cache[i]
        if (searches >= maxQuotes) throw IllegalStateException("search budget used up")
        searches++
        val q = quoteAt(candidates[i])
        cache[i] = q
        onProbe(candidates[i], q)
        return q
    }

    suspend fun find(extras: List<Int>): List<Pick> = extras.map { extra ->
        val goal = base + extra
        try { pickFor(extra, goal) } catch (e: IllegalStateException) { Pick(extra, goal, null, "stopped: ${e.message}") }
    }

    private suspend fun pickFor(extra: Int, goal: Double): Pick {
        var lo = 0; var hi = candidates.lastIndex; var first = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val q = quote(mid)
            when {
                q == null -> hi = mid - 1
                q.price >= goal -> { first = mid; hi = mid - 1 }
                else -> lo = mid + 1
            }
        }
        val above = if (first >= 0) cache[first] else null
        val below = if (first > 0) quote(first - 1) else if (first < 0) cache.values.filterNotNull().maxByOrNull { it.price } else null
        val best = listOfNotNull(above, below).minByOrNull { abs(it.price - goal) * 1000 + it.candidate.kmFromPickup }
            ?: return Pick(extra, goal, null, "the ride is not offered from any town along the route")
        val note = when {
            first < 0 -> "highest price found is %.2f, short of the +%d target".format(best.price, extra)
            abs(best.price - goal) <= 1.0 -> "within 1 EUR of the target"
            else -> "nearest available, %.2f EUR from the target".format(abs(best.price - goal))
        }
        return Pick(extra, goal, best, note)
    }
}
