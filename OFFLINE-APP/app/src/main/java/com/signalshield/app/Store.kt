package com.signalshield.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object Store {
    private var prefs: SharedPreferences? = null

    val snapshot = MutableStateFlow(Snapshot())
    val history = MutableStateFlow<List<Int>>(emptyList())
    val zones = MutableStateFlow<List<Zone>>(emptyList())
    val outbox = MutableStateFlow<List<Queued>>(emptyList())
    val warning = MutableStateFlow<String?>(null)
    val running = MutableStateFlow(false)
    val backupOn = MutableStateFlow(false)

    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.applicationContext.getSharedPreferences("signalshield", Context.MODE_PRIVATE)
        zones.value = loadZones()
        outbox.value = loadOutbox()
    }

    // ---------- dead zones ----------
    fun addZoneHit(lat: Double, lon: Double): Long {
        val list = zones.value.toMutableList()
        val now = System.currentTimeMillis()
        val i = list.indexOfFirst { distMeters(lat, lon, it.lat, it.lon) < 400.0 }
        var id = now
        if (i >= 0) {
            val z = list[i]
            list[i] = z.copy(count = z.count + 1, lastTs = now)
            id = z.id
        } else {
            list.add(Zone(id, lat, lon, 1, 0.0, 0, now))
        }
        while (list.size > 200) list.removeAt(0)
        zones.value = list
        saveZones()
        return id
    }

    fun addDuration(id: Long, secs: Double) {
        zones.value = zones.value.map {
            if (it.id == id) {
                val n = it.samples + 1
                it.copy(avgSecs = (it.avgSecs * it.samples + secs) / n, samples = n)
            } else it
        }
        saveZones()
    }

    fun clearZones() {
        zones.value = emptyList()
        saveZones()
    }

    fun zoneAhead(lat: Double, lon: Double, bearing: Float?, speed: Float): Ahead? {
        var best: Ahead? = null
        for (z in zones.value) {
            val d = distMeters(lat, lon, z.lat, z.lon)
            if (d > 8000.0 || d < 150.0) continue
            if (bearing != null && speed > 1.5f) {
                val b = bearingTo(lat, lon, z.lat, z.lon)
                val diff = abs(((b - bearing + 540.0) % 360.0) - 180.0)
                if (diff > 70.0) continue
            }
            val cur = best
            if (cur == null || d < cur.meters) best = Ahead(z, d)
        }
        return best
    }

    fun describe(a: Ahead, speed: Float): String {
        val km = String.format(Locale.US, "%.1f", a.meters / 1000.0)
        val eta = if (speed > 1.5f) {
            " (about " + maxOf(1, (a.meters / speed / 60.0).roundToInt()) + " min away)"
        } else ""
        val dur = if (a.zone.samples > 0) {
            " It usually lasts about " + maxOf(1, (a.zone.avgSecs / 60.0).roundToInt()) + " min."
        } else ""
        return "Dead zone about $km km ahead$eta.$dur Send your messages now."
    }

    private fun saveZones() {
        val arr = JSONArray()
        zones.value.forEach {
            arr.put(JSONObject()
                .put("id", it.id).put("lat", it.lat).put("lon", it.lon)
                .put("count", it.count).put("avg", it.avgSecs)
                .put("samples", it.samples).put("ts", it.lastTs))
        }
        prefs?.edit()?.putString("zones", arr.toString())?.apply()
    }

    private fun loadZones(): List<Zone> {
        val s = prefs?.getString("zones", null) ?: return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Zone(o.getLong("id"), o.getDouble("lat"), o.getDouble("lon"),
                    o.getInt("count"), o.getDouble("avg"), o.getInt("samples"), o.getLong("ts"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---------- outbox ----------
    fun queue(to: String, text: String) {
        outbox.value = outbox.value + Queued(System.currentTimeMillis(), to, text)
        saveOutbox()
    }

    fun removeQueued(id: Long) {
        outbox.value = outbox.value.filter { it.id != id }
        saveOutbox()
    }

    private fun saveOutbox() {
        val arr = JSONArray()
        outbox.value.forEach {
            arr.put(JSONObject().put("id", it.id).put("to", it.to).put("text", it.text))
        }
        prefs?.edit()?.putString("outbox", arr.toString())?.apply()
    }

    private fun loadOutbox(): List<Queued> {
        val s = prefs?.getString("outbox", null) ?: return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Queued(o.getLong("id"), o.getString("to"), o.getString("text"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
