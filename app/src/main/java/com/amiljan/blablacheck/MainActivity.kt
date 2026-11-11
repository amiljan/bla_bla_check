package com.amiljan.blablacheck

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

sealed interface Screen {
    data object List : Screen
    data object Add : Screen
    data object Settings : Screen
    data object Day : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        val app = application as App
        if (app.repo.watches.value.any { it.active }) WatchService.start(this)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Root(app) } } }
    }
}

@Composable
fun Root(app: App) {
    var screen by remember { mutableStateOf<Screen>(Screen.List) }
    BackHandler(screen != Screen.List) { screen = Screen.List }
    when (screen) {
        Screen.List -> WatchList(app, onAdd = { screen = Screen.Add }, onSettings = { screen = Screen.Settings }, onDay = { screen = Screen.Day })
        Screen.Day -> DayView(app, onBack = { screen = Screen.List })
        Screen.Add -> AddWatch(app, onDone = { screen = Screen.List })
        Screen.Settings -> SettingsScreen(app, onBack = { screen = Screen.List })
    }
}

private val tf = DateTimeFormatter.ofPattern("HH:mm")
private fun fmtTime(ms: Long) = if (ms == 0L) "never" else Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm:ss"))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchList(app: App, onAdd: () -> Unit, onSettings: () -> Unit, onDay: () -> Unit) {
    val ctx = LocalContext.current
    val watches by app.repo.watches.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("BlaBla Check") }, actions = {
                TextButton(onClick = onDay) { Text("Day view") }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
            })
        },
        floatingActionButton = { FloatingActionButton(onClick = onAdd) { Icon(Icons.Default.Add, "Add search") } },
    ) { pad ->
        if (watches.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No searches yet. Tap + to watch a route.")
            }
        } else LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(watches, key = { it.id }) { w ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${w.from.label} → ${w.to.label}", style = MaterialTheme.typography.titleMedium)
                        Text("${w.date} · arrive ${w.arrFrom}–${w.arrTo}")
                        val stop = Instant.ofEpochMilli(w.endAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM HH:mm"))
                        Text("Stops $stop · ${w.matches} match${if (w.matches == 1) "" else "es"} so far", style = MaterialTheme.typography.bodySmall)
                        Text("${w.lastStatus} (checked ${fmtTime(w.lastCheck)})", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (w.active) {
                                TextButton(onClick = { WatchService.checkNow(ctx) }) { Text("Check now") }
                                TextButton(onClick = { app.repo.update(w.id) { it.copy(active = false, lastStatus = "Paused") } }) { Text("Pause") }
                            } else if (w.ended == null) {
                                TextButton(onClick = {
                                    app.repo.update(w.id) { it.copy(active = true, lastStatus = "Resumed") }
                                    WatchService.start(ctx)
                                }) { Text("Resume") }
                            }
                            TextButton(onClick = { app.repo.remove(w.id) }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CityPicker(label: String, value: City, onPick: (City) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = value.label, onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            City.entries.forEach { c -> DropdownMenuItem(text = { Text(c.label) }, onClick = { onPick(c); open = false }) }
        }
    }
}

@Composable
private fun TimeButton(label: String, value: LocalTime, onPick: (LocalTime) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    OutlinedButton(modifier = modifier, onClick = {
        TimePickerDialog(ctx, { _, h, m -> onPick(LocalTime.of(h, m)) }, value.hour, value.minute, true).show()
    }) { Text("$label ${value.format(tf)}") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateButton(label: String, value: LocalDate, onPick: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(modifier = modifier, onClick = { open = true }) { Text("$label $value") }
    if (open) {
        val st = rememberDatePickerState(initialSelectedDateMillis = value.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    st.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text("OK") }
            },
        ) { DatePicker(st) }
    }
}

@Composable
fun AddWatch(app: App, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var from by remember { mutableStateOf(City.GRAZ) }
    var to by remember { mutableStateOf(City.ZAGREB) }
    var date by remember { mutableStateOf(LocalDate.now().plusDays(1)) }
    var arrFrom by remember { mutableStateOf(LocalTime.of(6, 0)) }
    var arrTo by remember { mutableStateOf(LocalTime.of(12, 0)) }
    var stopDate by remember { mutableStateOf(date) }
    var stopTime by remember { mutableStateOf(LocalTime.of(23, 0)) }
    var err by remember { mutableStateOf<String?>(null) }
    BackHandler { onDone() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).statusBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("New search", style = MaterialTheme.typography.headlineSmall)
        CityPicker("From", from) { from = it }
        CityPicker("To", to) { to = it }
        DateButton("Travel date", date, { date = it; if (stopDate.isAfter(it)) stopDate = it }, Modifier.fillMaxWidth())
        Text("Arrive between")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeButton("from", arrFrom, { arrFrom = it }, Modifier.weight(1f))
            TimeButton("to", arrTo, { arrTo = it }, Modifier.weight(1f))
        }
        Text("Stop searching at (never later than the end of the travel date)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DateButton("", stopDate, { stopDate = it }, Modifier.weight(1f))
            TimeButton("", stopTime, { stopTime = it }, Modifier.weight(1f))
        }
        err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val endAt = stopDate.atTime(stopTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val endOfDay = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                err = when {
                    from == to -> "From and To must differ"
                    arrFrom.isAfter(arrTo) -> "Arrival window start must be before its end"
                    date.isBefore(LocalDate.now()) -> "Travel date is in the past"
                    endAt <= System.currentTimeMillis() -> "Stop time is already past"
                    endAt >= endOfDay -> "Stop time must be on or before the travel date"
                    else -> null
                }
                if (err == null) {
                    app.repo.add(Watch(UUID.randomUUID().toString(), from, to, date.toString(), arrFrom.format(tf), arrTo.format(tf), endAt))
                    WatchService.start(ctx)
                    onDone()
                }
            }) { Text("Start watching") }
            TextButton(onClick = onDone) { Text("Cancel") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: App, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var topic by remember { mutableStateOf(app.repo.ntfyTopic) }
    var msg by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).statusBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Text("Push via ntfy.sh. Install the ntfy app on any device and subscribe to this topic. Keep it secret: anyone who knows it can read your alerts. Leave empty to disable.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(topic, { topic = it }, label = { Text("ntfy topic") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button(onClick = { app.repo.ntfyTopic = topic; msg = "Saved." }) { Text("Save topic") }
        Button(onClick = {
            Thread { app.notifier.test() }.start(); msg = "Test notification sent."
        }) { Text("Send test notification") }
        HorizontalDivider()
        Text("Samsung and other phones kill background apps. Exempt this app from battery optimisation so the 5-minute checks keep running.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = {
            val pm = ctx.getSystemService(PowerManager::class.java)
            if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) msg = "Already exempt."
            else ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
        }) { Text("Disable battery optimisation") }
        HorizontalDivider()
        Text("If BlaBlaCar shows a captcha, solve it here once. The checker shares this browser session.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { ctx.startActivity(Intent(ctx, CaptchaActivity::class.java)) }) { Text("Open BlaBlaCar session / solve captcha") }
        if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.primary)
        TextButton(onClick = onBack) { Text("Back") }
    }
}
