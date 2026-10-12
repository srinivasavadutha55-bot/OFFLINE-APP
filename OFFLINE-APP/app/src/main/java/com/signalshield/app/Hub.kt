package com.signalshield.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.text.Html
import android.util.Xml
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class Cand(
    val feed: String,
    val title: String,
    val link: String?,
    val audioUrl: String?,
    val durationSec: Int?,
    val summary: String?
)

/** Fills the offline backup hub with articles and podcast audio, with no taps needed. */
object Hub {
    private val busy = AtomicBoolean(false)
    private val idGen = AtomicLong(System.currentTimeMillis())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val MAX_AUDIO_BYTES = 70L * 1024L * 1024L

    fun dir(ctx: Context): File = File(ctx.filesDir, "hub").also { it.mkdirs() }

    fun totalSeconds(): Int = Store.hub.value.sumOf { it.seconds }

    fun refreshAsync(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { refresh(app) }
    }

    fun refresh(ctx: Context) {
        if (!busy.compareAndSet(false, true)) return
        try {
            val target = Store.targetMin.value * 60
            prune(ctx, target)
            if (totalSeconds() >= target) {
                status()
                return
            }
            if (!networkOk(ctx, Store.allowMobile.value)) {
                Store.hubStatus.value =
                    if (Store.allowMobile.value) "Waiting for internet to fill the hub"
                    else "Waiting for Wi-Fi to fill the hub"
                return
            }
            Store.hubStatus.value = "Filling the hub..."

            val arts = mutableListOf<Cand>()
            val auds = mutableListOf<Cand>()
            for (f in Store.feeds.value) {
                try {
                    for (c in readFeed(f).take(12)) {
                        if (c.audioUrl != null) auds.add(c) else if (c.link != null) arts.add(c)
                    }
                } catch (e: Exception) {
                }
            }

            fun articleSecs(): Int = Store.hub.value.filter { it.type == "article" }.sumOf { it.seconds }

            for (c in arts) {
                if (articleSecs() >= target / 2) break
                addArticle(ctx, c)
            }
            for (c in auds) {
                if (totalSeconds() >= target) break
                addAudio(ctx, c)
            }
            for (c in arts) {
                if (totalSeconds() >= target) break
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
        val a = items.filter { it.type == "article" }.sumOf { it.seconds } / 60
        val p = items.filter { it.type == "audio" }.sumOf { it.seconds } / 60
        val target = Store.targetMin.value
        Store.hubStatus.value =
            if (a + p >= target) "Ready: ${a + p} min ($a min reading, $p min audio)"
            else "Partly filled: ${a + p} of $target min. It will try again soon."
    }

    private fun add(item: HubItem) {
        Store.setHub(Store.hub.value + item)
    }

    // ---------- network ----------
    private fun networkOk(ctx: Context, allowMobile: Boolean): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return false
        val c = cm.getNetworkCapabilities(n) ?: return false
        if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false
        return allowMobile ||
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun open(urlStr: String): HttpURLConnection {
        var u = urlStr
        for (i in 0 until 6) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) SignalShield/0.2")
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

    private fun getText(url: String): String? {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) return null
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    // ---------- feeds ----------
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

    // ---------- articles ----------
    private fun clean(s: String): String =
        Html.fromHtml(s, Html.FROM_HTML_MODE_COMPACT).toString()
            .replace('\u00a0', ' ')
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

    private fun addArticle(ctx: Context, c: Cand) {
        val link = c.link ?: return
        if (Store.hub.value.any { it.url == link }) return
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
            val id = idGen.incrementAndGet()
            val name = "$id.txt"
            File(dir(ctx), name).writeText(c.title + "\n\n" + text)
            add(HubItem(id, "article", c.title, c.feed, (words * 0.3).toInt(), name,
                System.currentTimeMillis(), link))
        } catch (e: Exception) {
        }
    }

    // ---------- audio ----------
    private fun addAudio(ctx: Context, c: Cand) {
        val url = c.audioUrl ?: return
        if (Store.hub.value.any { it.url == url }) return
        val dur = c.durationSec
        if (dur != null && (dur > 45 * 60 || dur < 120)) return
        val id = idGen.incrementAndGet()
        val name = "$id.audio"
        val f = File(dir(ctx), name)
        try {
            val conn = open(url)
            try {
                if (conn.responseCode !in 200..299) return
                if (conn.contentLengthLong > MAX_AUDIO_BYTES) return
                var total = 0L
                conn.inputStream.use { ins ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(32768)
                        while (true) {
                            val r = ins.read(buf)
                            if (r < 0) break
                            out.write(buf, 0, r)
                            total += r
                            if (total > MAX_AUDIO_BYTES) throw IOException("too large")
                        }
                    }
                }
                val secs = dur ?: (total / 16000L).toInt()
                if (secs < 60) {
                    f.delete()
                    return
                }
                add(HubItem(id, "audio", c.title, c.feed, secs, name, System.currentTimeMillis(), url))
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            f.delete()
        }
    }

    // ---------- housekeeping ----------
    private fun prune(ctx: Context, targetSec: Int) {
        val now = System.currentTimeMillis()
        val d = dir(ctx)
        var keep = Store.hub.value.filter {
            now - it.ts < 3L * 24L * 3600L * 1000L && File(d, it.file).exists()
        }
        var total = keep.sumOf { it.seconds }
        val oldestFirst = keep.sortedBy { it.ts }.toMutableList()
        while (total > targetSec + 20 * 60 && oldestFirst.isNotEmpty()) {
            total -= oldestFirst[0].seconds
            oldestFirst.removeAt(0)
        }
        val ids = oldestFirst.map { it.id }.toSet()
        keep = keep.filter { it.id in ids }
        val names = keep.map { it.file }.toSet()
        d.listFiles()?.forEach { if (it.name !in names) it.delete() }
        Store.setHub(keep)
    }
}
