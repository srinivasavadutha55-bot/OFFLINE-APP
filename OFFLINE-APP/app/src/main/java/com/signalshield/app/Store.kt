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

    val DEFAULT_FEEDS = listOf(
        Feed("BBC World News", "https://feeds.bbci.co.uk/news/world/rss.xml"),
        Feed("The Hindu - National", "https://www.thehindu.com/news/national/feeder/default.rss"),
        Feed("BBC Global News Podcast", "https://podcasts.files.bbci.co.uk/p02nq0gn.rss"),
        Feed("NPR Up First", "https://feeds.npr.org/510318/podcast.xml")
    )

    val snapshot = MutableStateFlow(Snapshot())
    val history = MutableStateFlow<List<Int>>(emptyList())
    val zones = MutableStateFlow<List<Zone>>(emptyList())
    val outbox = MutableStateFlow<List<Queued>>(emptyList())
    val warning = MutableStateFlow<String?>(null)
    val running = MutableStateFlow(false)
    val backupOn = MutableStateFlow(false)

    val hub = MutableStateFlow<List<HubItem>>(emptyList())
    val feeds = MutableStateFlow(DEFAULT_FEEDS)
    val hubStatus = MutableStateFlow("Not filled yet")
    val targetMin = MutableStateFlow(30)
    val allowMobile = MutableStateFlow(true)
    val apps = MutableStateFlow<List<AppUse>>(emptyList())

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("signalshield", Context.MODE_PRIVATE)
        prefs = p
        zones.value = loadZones()
        outbox.value = loadOutbox()
        hub.value = loadHub()
        loadFeeds()?.let { feeds.value = it }
        apps.value = loadApps()
        targetMin.value = p.getInt("target", 30)
        allowMobile.value = p.getBoolean("mobile", true)
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
            if (d > 10000.0 || d < 150.0) continue
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
        return "Dead zone about $km km ahead$eta.$dur"
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

    // ---------- hub ----------
    fun setHub(list: List<HubItem>) {
        hub.value = list
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject()
                .put("id", it.id).put("type", it.type).put("title", it.title)
                .put("source", it.source).put("seconds", it.seconds)
                .put("file", it.file).put("ts", it.ts).put("url", it.url))
        }
        prefs?.edit()?.putString("hub", arr.toString())?.apply()
    }

    private fun loadHub(): List<HubItem> {
        val s = prefs?.getString("hub", null) ?: return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                HubItem(o.getLong("id"), o.getString("type"), o.getString("title"),
                    o.getString("source"), o.getInt("seconds"), o.getString("file"),
                    o.getLong("ts"), o.getString("url"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setTarget(min: Int) {
        targetMin.value = min
        prefs?.edit()?.putInt("target", min)?.apply()
    }

    fun setMobile(on: Boolean) {
        allowMobile.value = on
        prefs?.edit()?.putBoolean("mobile", on)?.apply()
    }

    fun addFeed(name: String, url: String) {
        if (feeds.value.any { it.url == url }) return
        feeds.value = feeds.value + Feed(name, url)
        saveFeeds()
    }

    fun removeFeed(url: String) {
        feeds.value = feeds.value.filter { it.url != url }
        saveFeeds()
    }

    private fun saveFeeds() {
        val arr = JSONArray()
        feeds.value.forEach { arr.put(JSONObject().put("name", it.name).put("url", it.url)) }
        prefs?.edit()?.putString("feeds", arr.toString())?.apply()
    }

    private fun loadFeeds(): List<Feed>? {
        val s = prefs?.getString("feeds", null) ?: return null
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Feed(o.getString("name"), o.getString("url"))
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------- apps used while travelling ----------
    fun bumpApp(pkg: String, label: String, secs: Int) {
        val cur = apps.value.toMutableList()
        val i = cur.indexOfFirst { it.pkg == pkg }
        if (i >= 0) cur[i] = cur[i].copy(seconds = cur[i].seconds + secs)
        else cur.add(AppUse(pkg, label, secs))
        apps.value = cur.sortedByDescending { it.seconds }.take(30)
        val arr = JSONArray()
        apps.value.forEach {
            arr.put(JSONObject().put("pkg", it.pkg).put("label", it.label).put("sec", it.seconds))
        }
        prefs?.edit()?.putString("apps", arr.toString())?.apply()
    }

    fun clearApps() {
        apps.value = emptyList()
        prefs?.edit()?.remove("apps")?.apply()
    }

    private fun loadApps(): List<AppUse> {
        val s = prefs?.getString("apps", null) ?: return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                AppUse(o.getString("pkg"), o.getString("label"), o.getInt("sec"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun topAppsText(n: Int): String? {
        val names = apps.value.take(n).map { it.label }
        return if (names.isEmpty()) null else names.joinToString(", ")
    }
}
