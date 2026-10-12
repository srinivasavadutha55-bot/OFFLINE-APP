package com.signalshield.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.text.Html
import android.util.Xml
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

internal data class Cand(
    val feed: String,
    val title: String,
    val link: String?,
    val audioUrl: String?,
    val durationSec: Int?,
    val summary: String?
)

private data class Media(val url: String, val title: String, val source: String, val seconds: Int)

/**
 * Fills the offline backup hub with news, podcasts, songs and videos, with no taps needed,
 * and keeps an offline library of Wikipedia pages, search results and saved web pages.
 */
object Hub {
    private val busy = AtomicBoolean(false)
    private val libBusy = AtomicBoolean(false)
    private val idGen = AtomicLong(System.currentTimeMillis())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private const val MAX_AUDIO_BYTES = 70L * 1024L * 1024L
    private const val MAX_SONG_BYTES = 20L * 1024L * 1024L
    private const val MAX_VIDEO_BYTES = 90L * 1024L * 1024L
    private const val DAY = 24L * 3600L * 1000L

    private const val UA_WEB = "Mozilla/5.0 (Linux; Android) SignalShield/0.3"
    private const val UA_API =
        "SignalShield/0.3 (Android offline backup app; https://github.com/srinivasavadutha55-bot/OFFLINE-APP)"

    // Free, download-allowed sources on the Internet Archive.
    private const val SONG_QUERY = "collection:netlabels AND mediatype:audio"
    private const val VIDEO_QUERY =
        "mediatype:movies AND collection:(animationandcartoons OR prelinger OR nasa)"

    fun dir(ctx: Context): File = File(ctx.filesDir, "hub").also { it.mkdirs() }

    fun totalSeconds(): Int = Store.hub.value.filter { it.type in COUNTED }.sumOf { it.seconds }

    fun isOnline(ctx: Context): Boolean = networkOk(ctx, true)

    fun refreshAsync(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { refresh(app) }
    }

    fun runPendingAsync(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { runPending(app) }
    }

    fun saveLinkAsync(ctx: Context, url: String) {
        val app = ctx.applicationContext
        scope.launch {
            if (!networkOk(app, true)) {
                Store.addPending(url)
                Store.libStatus.value = "No signal. The page will be saved when signal is back."
                return@launch
            }
            Store.libStatus.value = "Saving page for offline reading..."
            val t = saveLink(app, url)
            Store.libStatus.value =
                if (t != null) "Saved \"$t\". It now works offline." else "Could not save that page. Some sites block it."
        }
    }

    // ================= backup hub =================
    fun refresh(ctx: Context) {
        if (!busy.compareAndSet(false, true)) return
        try {
            val target = Store.targetMin.value * 60
            prune(ctx, target)
            if (!networkOk(ctx, Store.allowMobile.value)) {
                Store.hubStatus.value =
                    if (Store.allowMobile.value) "Waiting for internet to fill the hub"
                    else "Waiting for Wi-Fi to fill the hub"
                return
            }
            try {
                refreshLibrary(ctx)
            } catch (e: Exception) {
            }
            if (Store.pending.value.isNotEmpty()) runPending(ctx)
            if (totalSeconds() >= target) {
                status()
                return
            }
            Store.hubStatus.value = "Filling the hub..."

            val wifi = onWifi(ctx)
            val kinds = mutableListOf<String>()
            if (Store.wantSongs.value) kinds.add("song")
            if (Store.wantPods.value) kinds.add("audio")
            if (Store.wantNews.value) kinds.add("article")
            if (Store.wantVideos.value && (wifi || Store.videoMobile.value)) kinds.add("video")
            if (kinds.isEmpty()) {
                Store.hubStatus.value = "Turn on at least one kind of content below."
                return
            }
            val share = target / kinds.size

            val arts = mutableListOf<Cand>()
            val auds = mutableListOf<Cand>()
            if ("article" in kinds || "audio" in kinds) {
                for (f in Store.feeds.value) {
                    try {
                        for (c in readFeed(f).take(12)) {
                            if (c.audioUrl != null) auds.add(c) else if (c.link != null) arts.add(c)
                        }
                    } catch (e: Exception) {
                    }
                }
            }

            fun secs(t: String): Int = Store.hub.value.filter { it.type == t }.sumOf { it.seconds }
            fun full(): Boolean = totalSeconds() >= target

            for (k in kinds) {
                when (k) {
                    "song" -> fillMedia(ctx, "song") { secs("song") < share && !full() }
                    "audio" -> for (c in auds) {
                        if (secs("audio") >= share || full()) break
                        addAudio(ctx, c)
                    }
                    "article" -> for (c in arts) {
                        if (secs("article") >= share || full()) break
                        addArticle(ctx, c)
                    }
                    "video" -> fillMedia(ctx, "video") { secs("video") < share && !full() }
                }
            }
            // Top up whatever is still short, with the lighter kinds.
            if (!full() && "song" in kinds) fillMedia(ctx, "song") { !full() }
            if (!full() && "audio" in kinds) for (c in auds) {
                if (full()) break
                addAudio(ctx, c)
            }
            if (!full() && "article" in kinds) for (c in arts) {
                if (full()) break
                addArticle(ctx, c)
            }
            status()
        } catch (e: Exception) {
            Store.hubStatus.value = "Could not fill the hub right now"
        } finally {
            busy.set(false)
        }
    }

    private fun status() {
        val items = Store.hub.value
        fun m(t: String) = items.filter { it.type == t }.sumOf { it.seconds } / 60
        val parts = listOf(
            "news" to m("article"), "podcasts" to m("audio"),
            "songs" to m("song"), "video" to m("video")
        ).filter { it.second > 0 }.joinToString(", ") { "${it.second} min ${it.first}" }
        val total = totalSeconds() / 60
        val target = Store.targetMin.value
        Store.hubStatus.value =
            if (total >= target) "Ready: $total min ($parts)"
            else "Partly filled: $total of $target min. It will try again soon."
    }

    private fun add(item: HubItem) {
        synchronized(lock) {
            if (Store.hub.value.any { it.url == item.url && it.type == item.type }) return
            Store.setHub(Store.hub.value + item)
        }
    }

    private fun has(url: String): Boolean = Store.hub.value.any { it.url == url }

    // ================= network =================
    private fun caps(ctx: Context): NetworkCapabilities? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return null
        return cm.getNetworkCapabilities(n)
    }

    private fun onWifi(ctx: Context): Boolean {
        val c = caps(ctx) ?: return false
        return c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun networkOk(ctx: Context, allowMobile: Boolean): Boolean {
        val c = caps(ctx) ?: return false
        if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false
        return allowMobile ||
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun open(urlStr: String, ua: String = UA_WEB): HttpURLConnection {
        var u = urlStr
        for (i in 0 until 6) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 25000
            c.setRequestProperty("User-Agent", ua)
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc == null) break
                u = URL(URL(u), loc).toString()
                continue
            }
            return c
        }
        throw IOException("too many redirects")
    }

    private fun getText(url: String, ua: String = UA_WEB): String? {
        val c = open(url, ua)
        try {
            if (c.responseCode !in 200..299) return null
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** Downloads to a file. Returns bytes written, or null on failure (file is removed). */
    private fun download(url: String, f: File, max: Long, ua: String = UA_WEB): Long? {
        try {
            val conn = open(url, ua)
            try {
                if (conn.responseCode !in 200..299) return null
                if (conn.contentLengthLong > max) return null
                var total = 0L
                conn.inputStream.use { ins ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(32768)
                        while (true) {
                            val r = ins.read(buf)
                            if (r < 0) break
                            out.write(buf, 0, r)
                            total += r
                            if (total > max) throw IOException("too large")
                        }
                    }
                }
                return total
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            f.delete()
            return null
        }
    }

    // ================= news + podcast feeds =================
    private fun readFeed(f: Feed): List<Cand> {
        val c = open(f.url)
        try {
            if (c.responseCode !in 200..299) return emptyList()
            return c.inputStream.use { parseFeed(f.name, it) }
        } finally {
            c.disconnect()
        }
    }

    private fun parseDur(s: String): Int? {
        val t = s.trim()
        if (t.isEmpty()) return null
        val parts = t.split(":")
        return try {
            when (parts.size) {
                1 -> parts[0].toDouble().toInt()
                2 -> parts[0].toInt() * 60 + parts[1].toDouble().toInt()
                3 -> parts[0].toInt() * 3600 + parts[1].toInt() * 60 + parts[2].toDouble().toInt()
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseFeed(name: String, input: InputStream): List<Cand> {
        val out = mutableListOf<Cand>()
        try {
            val p = Xml.newPullParser()
            p.setInput(input, null)
            var inItem = false
            var title = ""
            var link: String? = null
            var audio: String? = null
            var dur: Int? = null
            var desc: String? = null
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    val n = p.name
                    if (n == "item" || n == "entry") {
                        inItem = true
                        title = ""; link = null; audio = null; dur = null; desc = null
                    } else if (inItem) {
                        when (n) {
                            "title" -> title = p.nextText().trim()
                            "link" -> {
                                val h = p.getAttributeValue(null, "href")
                                if (h != null) {
                                    if (link == null) link = h
                                } else {
                                    link = p.nextText().trim()
                                }
                            }
                            "enclosure" -> {
                                val t = p.getAttributeValue(null, "type") ?: ""
                                val u = p.getAttributeValue(null, "url")
                                if (u != null) {
                                    val base = u.substringBefore('?').lowercase()
                                    if (t.startsWith("audio") || base.endsWith(".mp3") || base.endsWith(".m4a")) {
                                        audio = u
                                    }
                                }
                            }
                            "itunes:duration" -> dur = parseDur(p.nextText())
                            "description", "summary" -> desc = p.nextText()
                            "content:encoded" -> desc = p.nextText()
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    val n = p.name
                    if ((n == "item" || n == "entry") && inItem) {
                        if (title.isNotBlank()) out.add(Cand(name, title, link, audio, dur, desc))
                        inItem = false
                    }
                }
                ev = p.next()
            }
        } catch (e: Exception) {
        }
        return out
    }

    // ================= articles =================
    private fun clean(s: String): String =
        Html.fromHtml(s, Html.FROM_HTML_MODE_COMPACT).toString()
            .replace(' ', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun extractText(html: String): String {
        val h = html.replace(
            Regex("(?is)<(script|style|noscript|header|footer|nav|aside|form)[^>]*>.*?</\\1>"), " ")
        val paras = Regex("(?is)<p[^>]*>(.*?)</p>").findAll(h)
            .map { clean(it.groupValues[1]) }
            .filter { it.length > 60 }
            .toList()
        return paras.joinToString("\n\n")
    }

    private fun wordCount(s: String): Int = s.split(Regex("\\s+")).count { it.isNotBlank() }

    private fun writeText(ctx: Context, title: String, body: String): Pair<Long, String> {
        val id = idGen.incrementAndGet()
        val name = "$id.txt"
        File(dir(ctx), name).writeText(title + "\n\n" + body)
        return id to name
    }

    private fun addArticle(ctx: Context, c: Cand) {
        val link = c.link ?: return
        if (has(link)) return
        try {
            val html = getText(link) ?: return
            var text = extractText(html)
            var words = wordCount(text)
            if (words < 150) {
                val s = c.summary?.let { clean(it) }.orEmpty()
                val sw = wordCount(s)
                if (sw >= 120) {
                    text = s
                    words = sw
                } else {
                    return
                }
            }
            val (id, name) = writeText(ctx, c.title, text)
            add(HubItem(id, "article", c.title, c.feed, (words * 0.3).toInt(), name,
                System.currentTimeMillis(), link))
        } catch (e: Exception) {
        }
    }

    // ================= podcasts =================
    private fun addAudio(ctx: Context, c: Cand) {
        val url = c.audioUrl ?: return
        if (has(url)) return
        val dur = c.durationSec
        if (dur != null && (dur > 45 * 60 || dur < 120)) return
        val id = idGen.incrementAndGet()
        val name = "$id.audio"
        val f = File(dir(ctx), name)
        val total = download(url, f, MAX_AUDIO_BYTES) ?: return
        val secs = dur ?: (total / 16000L).toInt()
        if (secs < 60) {
            f.delete()
            return
        }
        add(HubItem(id, "audio", c.title, c.feed, secs, name, System.currentTimeMillis(), url))
    }

    // ================= songs + videos (Internet Archive) =================
    private fun safeWords(s: String): String =
        s.replace(Regex("[^\\p{L}\\p{N} ]"), " ").trim().replace(Regex("\\s+"), " ")

    private fun iaSearch(q: String, rows: Int): List<Pair<String, String>> {
        val page = 1 + Random.nextInt(4)
        val url = "https://archive.org/advancedsearch.php?q=" + enc(q) +
            "&fl%5B%5D=identifier&fl%5B%5D=creator&rows=$rows&page=$page" +
            "&sort%5B%5D=" + enc("downloads desc") + "&output=json"
        val txt = getText(url, UA_API) ?: return emptyList()
        val docs = JSONObject(txt).optJSONObject("response")?.optJSONArray("docs") ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until docs.length()) {
            val d = docs.optJSONObject(i) ?: continue
            val id = d.optString("identifier", "")
            if (id.isEmpty()) continue
            val cr = d.opt("creator")
            val creator = when (cr) {
                is JSONArray -> cr.optString(0, "")
                is String -> cr
                else -> ""
            }
            out.add(id to creator)
        }
        return out.shuffled()
    }

    private fun iaFiles(id: String): List<JSONObject> {
        val txt = getText("https://archive.org/metadata/" + enc(id) + "/files", UA_API) ?: return emptyList()
        val arr = JSONObject(txt).optJSONArray("result") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    private fun iaUrl(id: String, name: String): String =
        "https://archive.org/download/" + enc(id) + "/" + name.split("/").joinToString("/") { enc(it) }

    private fun pickSongs(id: String, creator: String): List<Media> {
        val files = iaFiles(id)
        fun ok(f: JSONObject): Boolean {
            val len = parseDur(f.optString("length", "")) ?: return false
            val size = f.optString("size", "").toLongOrNull() ?: 0L
            return len in 90..600 && size in 1L..MAX_SONG_BYTES
        }
        val mp3 = files.filter { it.optString("format", "").contains("MP3") && ok(it) }
        val best = mp3.filter { it.optString("format") == "VBR MP3" }.ifEmpty {
            mp3.filter { it.optString("format") == "128Kbps MP3" }.ifEmpty { mp3 }
        }
        return best.shuffled().take(2).map { f ->
            val name = f.optString("name")
            val title = f.optString("title", "").ifBlank {
                name.substringAfterLast('/').substringBeforeLast('.').replace('_', ' ')
            }
            val by = creator.ifBlank { "Free music" }
            Media(iaUrl(id, name), title, "$by · Internet Archive", parseDur(f.optString("length")) ?: 180)
        }
    }

    private fun pickVideo(id: String, creator: String): List<Media> {
        val files = iaFiles(id)
        val title = files.firstOrNull { it.optString("title", "").isNotBlank() }?.optString("title")
        val vids = files.filter {
            val fmt = it.optString("format", "")
            val name = it.optString("name", "").lowercase()
            val len = parseDur(it.optString("length", "")) ?: 0
            val size = it.optString("size", "").toLongOrNull() ?: 0L
            (fmt.contains("MPEG4") || fmt.contains("h.264") || name.endsWith(".mp4")) &&
                len in 60..900 && size in 1L..MAX_VIDEO_BYTES
        }.sortedBy { it.optString("size", "").toLongOrNull() ?: Long.MAX_VALUE }
        val f = vids.firstOrNull() ?: return emptyList()
        val name = f.optString("name")
        val t = title ?: name.substringAfterLast('/').substringBeforeLast('.').replace('_', ' ')
        val by = creator.ifBlank { "Free video" }
        return listOf(Media(iaUrl(id, name), t, "$by · Internet Archive", parseDur(f.optString("length")) ?: 300))
    }

    private fun fillMedia(ctx: Context, type: String, more: () -> Boolean) {
        if (!more()) return
        try {
            val items: List<Pair<String, String>> = if (type == "song") {
                val style = safeWords(Store.musicStyle.value)
                var r = if (style.isNotEmpty()) iaSearch("$SONG_QUERY AND ($style)", 30) else emptyList()
                if (r.size < 3) r = r + iaSearch(SONG_QUERY, 30)
                r
            } else {
                iaSearch(VIDEO_QUERY, 30)
            }
            var tries = 0
            for ((id, creator) in items) {
                if (!more() || tries >= 12) break
                tries++
                val picks = try {
                    if (type == "song") pickSongs(id, creator) else pickVideo(id, creator)
                } catch (e: Exception) {
                    emptyList()
                }
                for (m in picks) {
                    if (!more()) break
                    if (has(m.url)) continue
                    val fid = idGen.incrementAndGet()
                    val name = if (type == "song") "$fid.mp3" else "$fid.mp4"
                    val f = File(dir(ctx), name)
                    val max = if (type == "song") MAX_SONG_BYTES else MAX_VIDEO_BYTES
                    download(m.url, f, max, UA_API) ?: continue
                    add(HubItem(fid, type, m.title, m.source, m.seconds, name, System.currentTimeMillis(), m.url))
                }
            }
        } catch (e: Exception) {
        }
    }

    // ================= offline library: Wikipedia, searches, saved pages =================
    private fun wikiUrl(title: String) = "https://en.wikipedia.org/wiki/" + title.replace(' ', '_')

    /** Saves one Wikipedia article for offline reading. Returns its title, or null. */
    private fun addWiki(ctx: Context, title: String, source: String): String? {
        val api = "https://en.wikipedia.org/w/api.php?action=query&prop=extracts&explaintext=1" +
            "&redirects=1&format=json&formatversion=2&titles=" + enc(title)
        val txt = getText(api, UA_API) ?: return null
        val pages = JSONObject(txt).optJSONObject("query")?.optJSONArray("pages") ?: return null
        if (pages.length() == 0) return null
        val pg = pages.getJSONObject(0)
        if (pg.has("missing")) return null
        val t = pg.optString("title", title)
        val url = wikiUrl(t)
        val old = Store.hub.value.firstOrNull { it.url == url }
        if (old != null && System.currentTimeMillis() - old.ts < 3 * DAY) return t
        var text = pg.optString("extract", "")
        if (text.length < 300) return null
        if (text.length > 40000) text = text.substring(0, 40000) + " ..."
        val (id, name) = writeText(ctx, t, text)
        if (old != null) {
            synchronized(lock) { Store.setHub(Store.hub.value.filter { it.id != old.id }) }
        }
        add(HubItem(id, "wiki", t, source, (wordCount(text) * 0.3).toInt(), name,
            System.currentTimeMillis(), url))
        return t
    }

    /** Today's featured and most-read Wikipedia pages, plus the user's own topics, every 12 hours. */
    private fun refreshLibrary(ctx: Context) {
        if (!libBusy.compareAndSet(false, true)) return
        try {
            val now = System.currentTimeMillis()
            if (now - Store.libTs.value < DAY / 2) return
            Store.setLibTs(now)
            try {
                val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                val path = String.format(Locale.US, "%04d/%02d/%02d",
                    cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
                val txt = getText("https://en.wikipedia.org/api/rest_v1/feed/featured/$path", UA_API)
                if (txt != null) {
                    val o = JSONObject(txt)
                    val titles = mutableListOf<String>()
                    o.optJSONObject("tfa")?.optJSONObject("titles")?.optString("normalized")
                        ?.takeIf { it.isNotBlank() }?.let { titles.add(it) }
                    val arts = o.optJSONObject("mostread")?.optJSONArray("articles")
                    if (arts != null) {
                        for (i in 0 until arts.length()) {
                            val a = arts.optJSONObject(i) ?: continue
                            val t = a.optJSONObject("titles")?.optString("normalized")
                                ?: a.optString("normalizedtitle", "")
                            if (t.isBlank() || t.contains(":") || t == "Main Page") continue
                            titles.add(t)
                            if (titles.size >= 9) break
                        }
                    }
                    for (t in titles) {
                        try {
                            addWiki(ctx, t, "Wikipedia · Popular today")
                        } catch (e: Exception) {
                        }
                    }
                }
            } catch (e: Exception) {
            }
            for (t in Store.topics.value) {
                try {
                    searchOnline(ctx, t, "Your topic: $t", 2)
                } catch (e: Exception) {
                }
            }
        } finally {
            libBusy.set(false)
        }
    }

    /** Looks a question up on Wikipedia and saves the best pages offline. Returns how many were saved. */
    fun searchOnline(ctx: Context, q: String, source: String = "Search: $q", limit: Int = 3): Int {
        val api = "https://en.wikipedia.org/w/api.php?action=query&list=search&srlimit=$limit" +
            "&format=json&formatversion=2&srsearch=" + enc(q)
        val txt = getText(api, UA_API) ?: return 0
        val hits = JSONObject(txt).optJSONObject("query")?.optJSONArray("search") ?: return 0
        var n = 0
        for (i in 0 until hits.length()) {
            val t = hits.optJSONObject(i)?.optString("title") ?: continue
            try {
                if (addWiki(ctx, t, source) != null) n++
            } catch (e: Exception) {
            }
        }
        return n
    }

    /** Saves a web page (for example one shared from Chrome) for offline reading. */
    fun saveLink(ctx: Context, url: String): String? {
        try {
            if (has(url)) return Store.hub.value.first { it.url == url }.title
            val html = getText(url) ?: return null
            val text = extractText(html)
            val words = wordCount(text)
            if (words < 80) return null
            val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)
                ?.groupValues?.get(1)?.let { clean(it) }?.takeIf { it.isNotBlank() }
                ?: (Uri.parse(url).host ?: "Saved page")
            val (id, name) = writeText(ctx, title, text)
            add(HubItem(id, "saved", title, Uri.parse(url).host ?: "Saved page",
                (words * 0.3).toInt(), name, System.currentTimeMillis(), url))
            return title
        } catch (e: Exception) {
            return null
        }
    }

    /** Runs searches and page saves that were asked for while there was no signal. */
    fun runPending(ctx: Context) {
        if (!networkOk(ctx, true)) return
        for (p in Store.pending.value) {
            try {
                if (p.query.startsWith("http")) {
                    val t = saveLink(ctx, p.query)
                    Store.removePending(p.id)
                    if (t != null) notify(ctx, "Saved \"$t\" for offline reading.")
                } else {
                    val n = searchOnline(ctx, p.query)
                    Store.removePending(p.id)
                    notify(ctx,
                        if (n > 0) "Your search \"${p.query}\" is ready. Open SignalShield to read it, even offline."
                        else "Nothing found for \"${p.query}\". Try other words.")
                }
            } catch (e: Exception) {
            }
        }
    }

    /** Searches everything saved on the phone. Works with no internet. */
    fun searchLocal(ctx: Context, q: String): List<HubItem> {
        val words = q.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }
        if (words.isEmpty()) return emptyList()
        val d = dir(ctx)
        val scored = mutableListOf<Pair<HubItem, Int>>()
        for (item in Store.hub.value) {
            val title = item.title.lowercase()
            var score = words.count { w -> title.contains(w) } * 10
            if (item.type == "article" || item.type == "wiki" || item.type == "saved") {
                val body = try {
                    File(d, item.file).readText().lowercase()
                } catch (e: Exception) {
                    ""
                }
                for (w in words) {
                    var i = body.indexOf(w)
                    var c = 0
                    while (i >= 0 && c < 20) {
                        c++
                        i = body.indexOf(w, i + w.length)
                    }
                    score += c
                }
                if (words.size > 1 && words.any { w -> !body.contains(w) && !title.contains(w) }) score /= 3
            }
            if (score > 0) scored.add(item to score)
        }
        return scored.sortedByDescending { it.second }.take(15).map { it.first }
    }

    private fun notify(ctx: Context, msg: String) {
        try {
            val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(ctx, MonitorService.CH_ALERT)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("SignalShield")
                .setContentText(msg)
                .setStyle(Notification.BigTextStyle().bigText(msg))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            ctx.getSystemService(NotificationManager::class.java)
                .notify((System.currentTimeMillis() % 100000).toInt() + 1000, n)
        } catch (e: Exception) {
        }
    }

    // ================= housekeeping =================
    private fun prune(ctx: Context, targetSec: Int) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val d = dir(ctx)
            val all = Store.hub.value.filter { File(d, it.file).exists() }
            val counted = all.filter { it.type in COUNTED && now - it.ts < 3 * DAY }
            var total = counted.sumOf { it.seconds }
            val oldestFirst = counted.sortedBy { it.ts }.toMutableList()
            while (total > targetSec + 20 * 60 && oldestFirst.isNotEmpty()) {
                total -= oldestFirst[0].seconds
                oldestFirst.removeAt(0)
            }
            val lib = all.filter { it.type !in COUNTED }
                .filter { now - it.ts < (if (it.type == "saved") 30 * DAY else 7 * DAY) }
                .sortedByDescending { it.ts }
                .take(120)
            val keep = oldestFirst + lib
            val names = keep.map { it.file }.toSet()
            // Leave very new files alone: they may still be downloading.
            d.listFiles()?.forEach {
                if (it.name !in names && now - it.lastModified() > 15 * 60 * 1000L) it.delete()
            }
            Store.setHub(keep)
        }
    }
}
