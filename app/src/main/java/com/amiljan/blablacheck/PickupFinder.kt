package com.amiljan.blablacheck

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Phase 3. The price of a ride is fixed by distance, so booking the same ride from an earlier town on the driver's
 * route costs more. This finds the towns whose price is about +10 and +20 EUR over the price for your real boarding
 * stop; you then book it as "town -> destination" and agree the real pickup with the driver.
 */
@Composable
fun PickupFinder(app: App, row: DayRow, to: City, date: LocalDate, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val log = remember { mutableStateListOf<String>() }
    var picks by remember { mutableStateOf<List<Pick>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    val o = row.origin!!; val b = row.boarding!!; val base = row.ride.price!!

    fun run() {
        scope.launch {
            running = true; picks = emptyList(); log.clear()
            try {
                val origin = LatLng(row.originLat!!, row.originLng!!)
                val boarding = LatLng(b.lat ?: throw FetchError("boarding stop has no coordinates"), b.lng!!)
                log += "Route ${o.city} → ${b.city}…"
                val route = Route.osrm(origin, boarding)
                    ?: listOf(origin, boarding).also { log += "Routing service unavailable, using a straight line." }
                val total = Route.lengthKm(route)
                val step = maxOf(15.0, total / 18)
                val samples = Route.sampleFromEnd(route, step)
                log += "${total.roundToInt()} km; naming ${samples.size} points along it (1/s, OpenStreetMap)…"
                val cands = ArrayList<Candidate>()
                for ((i, s) in samples.withIndex()) {
                    val (p, km) = s
                    val isOrigin = i == samples.lastIndex
                    val t = app.geo.reverseTown(p.lat, p.lng)
                    val name = if (isOrigin) o.city else t?.first
                    if (name == null || name == b.city || cands.any { it.town == name }) continue
                    cands += Candidate(name, t?.second, p.lat, p.lng, km)
                }
                log += "Candidate towns: " + cands.joinToString { "${it.town} (${it.kmFromPickup.roundToInt()} km)" }
                if (cands.isEmpty()) throw FetchError("no towns found along the route")

                val toId = to.placeId
                val ids = HashMap<String, String?>()
                val finder = PriceFinder(cands, base, maxQuotes = 14, quoteAt = { c ->
                    delay(2500L + (0..2000).random())
                    val q = listOfNotNull(c.town, c.country).joinToString(", ")
                    val pid = ids.getOrPut(c.town) { app.fetcher.placeId(q) } ?: return@PriceFinder null
                    val rides = app.fetcher.searchIds(pid, toId, date.toString())
                    val hit = rides.firstOrNull { it.id == row.ride.id }
                        ?: rides.firstOrNull { row.ride.driver != null && it.driver == row.ride.driver && it.arr == row.ride.arr }
                    hit?.price?.let { Quote(c, it, hit.url()) }
                }, onProbe = { c, q ->
                    log += "${c.town}: " + (q?.let { "%.2f €".format(it.price) } ?: "ride not offered from here")
                })
                picks = finder.find(listOf(10, 20))
                log += "Done after ${finder.searches} searches."
            } catch (e: CancellationException) { throw e
            } catch (e: Blocked) { log += "Blocked by BlaBlaCar's bot protection. Stopped; nothing more is sent until about ${java.text.SimpleDateFormat("HH:mm").format(java.util.Date(e.until))}."
            } catch (e: CaptchaRequired) { log += "Captcha needed: Settings > Solve captcha"
            } catch (e: Exception) { log += "Error: ${e.message ?: e.javaClass.simpleName}" }
            running = false
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Pickup-town price finder", style = MaterialTheme.typography.headlineSmall)
        Text("Ride from ${o.city} to ${to.label}, you board in ${b.city}${b.street?.let { " ($it)" } ?: ""} for %.2f €.".format(base))
        Text("It searches up to 14 times, a few seconds apart, to keep BlaBlaCar's bot protection calm.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { run() }, enabled = !running) { Text(if (running) "Searching…" else "Find +10 / +20 € towns") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            items(picks) { p ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("+${p.extra} € (target %.2f €)".format(p.goal), style = MaterialTheme.typography.titleSmall)
                        if (p.quote == null) Text(p.note) else {
                            val q = p.quote
                            Text("Book from ${q.candidate.town} → ${to.label}: %.2f € (%+.2f €)".format(q.price, q.price - base))
                            Text("${q.candidate.kmFromPickup.roundToInt()} km before ${b.city} on the driver's route · ${p.note}", style = MaterialTheme.typography.bodySmall)
                            Text("Then message the driver to meet in ${b.city} instead; the price stays what the distance sets.", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(q.url))) }) { Text("Open the ride from ${q.candidate.town}") }
                        }
                    }
                }
            }
            items(log.toList()) { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        TextButton(onClick = onBack) { Text("Back") }
    }
}
