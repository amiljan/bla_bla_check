package com.amiljan.blablacheck

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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

/** A ride plus what the trip page revealed about where the driver really starts. */
data class DayRow(
    val ride: Ride,
    val legs: List<Leg>? = null,
    val originLat: Double? = null,
    val originLng: Double? = null,
    val approx: Boolean = false,
    val km: Double? = null,
    val note: String = "loading…",
) {
    /** The driver's true start is the first stop of the itinerary, whether or not it is where we would board. */
    val origin: Leg? get() = legs?.firstOrNull()
    val boarding: Leg? get() = legs?.firstOrNull { it.active }
}

/** Phase 2: every posted ride for a day, ordered by how close the driver's real start is to a chosen place. */
@Composable
fun DayView(app: App, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var from by remember { mutableStateOf(City.GRAZ) }
    var to by remember { mutableStateOf(City.ZAGREB) }
    var date by remember { mutableStateOf(LocalDate.now().plusDays(1)) }
    var me by remember { mutableStateOf<City?>(City.GRAZ) } // null = custom text
    var custom by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<DayRow>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<DayRow?>(null) }
    BackHandler { if (picking != null) picking = null else onBack() }
    picking?.let { PickupFinder(app, it, to, date, onBack = { picking = null }); return }

    fun sorted(l: List<DayRow>) = l.sortedWith(compareBy({ it.km ?: Double.MAX_VALUE }, { it.ride.dep }))

    fun load() {
        scope.launch {
            loading = true; rows = emptyList(); status = "Finding your location…"
            try {
                val (mLat, mLng) = me?.let { it.lat to it.lng } ?: run {
                    val p = app.geo.geocode(custom) ?: throw FetchError("Could not find \"$custom\"")
                    p.lat to p.lng
                }
                status = "Searching ${from.label} → ${to.label}…"
                val rides = app.fetcher.search(from, to, date.toString())
                rows = sorted(rides.map { DayRow(it) })
                for ((i, ride) in rides.withIndex()) {
                    status = "Reading trip pages ${i + 1}/${rides.size}…"
                    var legs = app.legsCache[ride.id]
                    if (legs == null) {
                        legs = TripParser.legs(app.fetcher.tripPage(ride.url()))
                        if (legs.isNotEmpty()) app.legsCache[ride.id] = legs
                        delay(2000L + (0..2000).random())
                    }
                    val o = legs.firstOrNull()
                    var lat = o?.lat; var lng = o?.lng; var approx = false
                    if (o != null && lat == null) {
                        app.geo.geocode(o.city)?.let { lat = it.lat; lng = it.lng; approx = true }
                    }
                    val row = DayRow(
                        ride, legs, lat, lng, approx,
                        km = if (lat != null && lng != null) Geo.haversineKm(lat!!, lng!!, mLat, mLng) else null,
                        note = if (o == null) "trip page unreadable" else "",
                    )
                    rows = sorted(rows.map { if (it.ride.id == ride.id) row else it })
                }
                status = "${rides.size} rides, nearest real start first"
            } catch (e: CancellationException) { throw e
            } catch (e: Blocked) {
                status = "Blocked by BlaBlaCar's bot protection. Not sending anything until about ${java.text.SimpleDateFormat("HH:mm").format(java.util.Date(e.until))}; rides above were read before the block."
            } catch (e: CaptchaRequired) { status = "Captcha needed: Settings > Solve captcha"
            } catch (e: Exception) { status = "Error: ${e.message ?: e.javaClass.simpleName}" }
            loading = false
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Day view", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { CityPicker("From", from) { from = it } }
            Box(Modifier.weight(1f)) { CityPicker("To", to) { to = it } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            DateButton("Date", date, { date = it }, Modifier.weight(1f))
            Box(Modifier.weight(1f)) { MeCityPicker(me, { me = it }) }
        }
        if (me == null) OutlinedTextField(custom, { custom = it }, label = { Text("My location (place name)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { load() }, enabled = !loading && from != to && (me != null || custom.isNotBlank())) { Text(if (loading) "Loading…" else "Load rides") }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows, key = { it.ride.id }) { r ->
                Card(Modifier.fillMaxWidth().clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.ride.url()))) }) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val o = r.origin
                        Text(
                            if (o != null) "Starts in ${o.city}" + (o.street?.let { " ($it)" } ?: "") else "Start unknown",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (r.km != null) Text("${if (r.approx) "≈ " else ""}${r.km.roundToInt()} km from your location")
                        val b = r.boarding
                        Text("${r.ride.dep} → ${r.ride.arr}${if (r.ride.overnight) " (+1)" else ""} · ${r.ride.priceText}" + (r.ride.driver?.let { " · $it" } ?: ""))
                        if (b != null && o != null && !o.active) Text("You would board in ${b.city}${b.street?.let { " ($it)" } ?: ""} at ${b.time ?: "?"}", style = MaterialTheme.typography.bodySmall)
                        if (r.note.isNotEmpty()) Text(r.note, style = MaterialTheme.typography.bodySmall)
                        if (o != null && b != null && r.ride.price != null && (!o.active || o.city != b.city) && r.originLat != null)
                            TextButton(onClick = { picking = r }) { Text("Find +10 / +20 € pickup towns") }
                    }
                }
            }
        }
        TextButton(onClick = onBack) { Text("Back") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeCityPicker(value: City?, onPick: (City?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = value?.label ?: "Other…", onValueChange = {}, readOnly = true, label = { Text("My location") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) }, modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            City.entries.forEach { c -> DropdownMenuItem(text = { Text(c.label) }, onClick = { onPick(c); open = false }) }
            DropdownMenuItem(text = { Text("Other…") }, onClick = { onPick(null); open = false })
        }
    }
}
