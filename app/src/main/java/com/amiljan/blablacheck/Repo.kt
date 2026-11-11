package com.amiljan.blablacheck

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZoneId

/** Tiny persistence layer: watches as one JSON blob in SharedPreferences, exposed as a StateFlow. */
class Repo(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("bbc", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(Watch.serializer())
    private val _watches = MutableStateFlow(load())
    val watches: StateFlow<List<Watch>> = _watches

    private fun load(): List<Watch> = try {
        json.decodeFromString(ser, prefs.getString("watches", "[]")!!)
    } catch (e: Exception) { emptyList() }

    @Synchronized private fun save(list: List<Watch>) {
        _watches.value = list
        prefs.edit().putString("watches", json.encodeToString(ser, list)).apply()
    }

    @Synchronized fun add(w: Watch) = save(_watches.value + w)
    @Synchronized fun remove(id: String) = save(_watches.value.filter { it.id != id })
    @Synchronized fun update(id: String, f: (Watch) -> Watch) = save(_watches.value.map { if (it.id == id) f(it) else it })
    fun active() = _watches.value.filter { it.active }

    var ntfyTopic: String
        get() = prefs.getString("ntfy", null) ?: run {
            val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
            val t = "blabla-" + (1..12).map { chars.random() }.joinToString("")
            prefs.edit().putString("ntfy", t).apply(); t
        }
        set(v) = prefs.edit().putString("ntfy", v.trim()).apply()

    companion object {
        /** A watch is over once its stop time or the end of its travel date has passed. */
        fun expiry(w: Watch, now: Long = System.currentTimeMillis()): String? {
            val endOfDay = LocalDate.parse(w.date).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            return when {
                now >= endOfDay -> "Travel date has passed"
                now >= w.endAt -> "Stop time reached"
                else -> null
            }
        }
    }
}
