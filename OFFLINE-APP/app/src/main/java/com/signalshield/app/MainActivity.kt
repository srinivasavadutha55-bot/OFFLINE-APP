package com.signalshield.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.io.File

private val BAND = listOf(
    Color(0xFFC8362F), Color(0xFFD4572A), Color(0xFFD98A0B),
    Color(0xFF7F9D1C), Color(0xFF16915A)
)
private val IDLE = Color(0xFF53607A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

private fun hasCore(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

private fun startMonitor(ctx: Context) {
    ContextCompat.startForegroundService(ctx, Intent(ctx, MonitorService::class.java))
}

private fun stopMonitor(ctx: Context) {
    ctx.startService(Intent(ctx, MonitorService::class.java).setAction(MonitorService.ACTION_STOP))
}

private fun openSafely(ctx: Context, i: Intent) {
    try {
        ctx.startActivity(i)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "No app found to do that.", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun Reader(item: HubItem, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val text = remember(item.id) {
        try {
            File(Hub.dir(ctx), item.file).readText().substringAfter("\n\n")
        } catch (e: Exception) {
            "Could not open this article."
        }
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        OutlinedButton(onClick = onBack) { Text("Back") }
        Spacer(Modifier.height(12.dp))
        Text(item.title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("${item.source} · ${maxOf(1, item.seconds / 60)} min read")
        Spacer(Modifier.height(12.dp))
        Text(text, fontSize = 18.sp, lineHeight = 28.sp)
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val snap by Store.snapshot.collectAsState()
    val hist by Store.history.collectAsState()
    val zones by Store.zones.collectAsState()
    val outbox by Store.outbox.collectAsState()
    val warn by Store.warning.collectAsState()
    val running by Store.running.collectAsState()
    val backup by Store.backupOn.collectAsState()
    val hub by Store.hub.collectAsState()
    val feeds by Store.feeds.collectAsState()
    val hubStatus by Store.hubStatus.collectAsState()
    val target by Store.targetMin.collectAsState()
    val mobile by Store.allowMobile.collectAsState()
    val apps by Store.apps.collectAsState()
    val playing by AudioPlayer.playingId.collectAsState()

    var reading by remember { mutableStateOf<HubItem?>(null) }
    var usageOk by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            usageOk = Usage.hasAccess(ctx)
            delay(2000)
        }
    }

    val perms = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            res[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            startMonitor(ctx)
        } else {
            Toast.makeText(ctx, "Location permission is needed to learn dead zones.", Toast.LENGTH_LONG).show()
        }
    }

    val live = running && snap.time > 0L
    val bandColor = if (live) BAND[snap.score.coerceIn(0, 4)] else IDLE
    val readingItem = reading

    if (readingItem != null) {
        Reader(readingItem) { reading = null }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

            // ---- status band ----
            Column(Modifier.fillMaxWidth().background(bandColor).padding(18.dp)) {
                Text("SignalShield", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(
                        Modifier.height(64.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        for (i in 1..4) {
                            Box(
                                Modifier.width(13.dp).height((16 * i).dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(
                                        if (live && i <= snap.score) Color.White
                                        else Color.White.copy(alpha = 0.3f)
                                    )
                            )
                        }
                    }
                    Column {
                        Text(
                            if (live) LABELS[snap.score.coerceIn(0, 4)] else "Not monitoring",
                            color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold
                        )
                        Text(
                            when {
                                !live -> "Tap Start to begin"
                                backup -> "Backup mode is on"
                                else -> "Watching in the background"
                            },
                            color = Color.White, fontSize = 15.sp
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Canvas(
                    Modifier.fillMaxWidth().height(38.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.16f))
                ) {
                    val n = 60
                    val w = size.width / n
                    val data = hist.takeLast(n)
                    data.forEachIndexed { i, s ->
                        val hPx = (if (s == 0) 5f else 6f + s * 8f) / 38f * size.height
                        val x = (n - data.size + i) * w
                        drawRect(
                            color = Color.White.copy(alpha = if (s == 0) 0.45f else 0.95f),
                            topLeft = Offset(x, size.height - hPx),
                            size = Size(w - 2f, hPx)
                        )
                    }
                }
                Text("Last 5 minutes", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                if (live) {
                    val lat = snap.latencyMs
                    Text(
                        if (lat != null) "Delay $lat ms" else "No response from the internet",
                        color = Color.White, fontSize = 14.sp
                    )
                }
            }

            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {

                Button(
                    onClick = {
                        if (running) stopMonitor(ctx)
                        else if (hasCore(ctx)) startMonitor(ctx)
                        else launcher.launch(perms)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (running) "Stop monitoring" else "Start monitoring") }

                warn?.let {
                    Card(Modifier.fillMaxWidth()) {
                        Text(it, Modifier.padding(14.dp), fontWeight = FontWeight.Bold)
                    }
                }

                // ---- backup hub ----
                Text("Backup hub", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Fills itself with news to read and audio to hear, so you have something to do when signal drops. No taps needed.")
                val total = hub.sumOf { it.seconds } / 60
                LinearProgressIndicator(
                    progress = { (total.toFloat() / target).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("$total of $target min ready", fontWeight = FontWeight.Bold)
                Text(hubStatus)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30).forEach { m ->
                        if (m == target) Button(onClick = { Store.setTarget(m) }) { Text("$m min") }
                        else OutlinedButton(onClick = { Store.setTarget(m) }) { Text("$m min") }
                    }
                    OutlinedButton(onClick = { Hub.refreshAsync(ctx) }) { Text("Fill now") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = mobile, onCheckedChange = { Store.setMobile(it) })
                    Spacer(Modifier.width(10.dp))
                    Text("Also fill using mobile data", Modifier.weight(1f))
                }

                hub.sortedByDescending { it.ts }.forEach { item ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(item.title, fontWeight = FontWeight.Bold)
                            val kind = if (item.type == "audio") "Audio" else "Article"
                            Text("$kind · ${maxOf(1, item.seconds / 60)} min · ${item.source}")
                            if (item.type == "audio") {
                                Button(onClick = { AudioPlayer.toggle(ctx, item) }) {
                                    Text(if (playing == item.id) "Stop" else "Play")
                                }
                            } else {
                                Button(onClick = { reading = item }) { Text("Read") }
                            }
                        }
                    }
                }

                // ---- apps ----
                Text("Apps you use while travelling", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (!usageOk) {
                    Text("Allow usage access so SignalShield can learn which apps you use on trips and name them in its warnings. It only sees app names and time, never what is inside them.")
                    Button(
                        onClick = { openSafely(ctx, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Allow usage access") }
                } else if (apps.isEmpty()) {
                    Text("Nothing yet. Keep monitoring on and use your phone on a trip. Apps will appear here.")
                } else {
                    apps.take(6).forEach { a ->
                        Text("${a.label} · ${maxOf(1, a.seconds / 60)} min while travelling")
                    }
                    OutlinedButton(onClick = { Store.clearApps() }) { Text("Clear app history") }
                }

                // ---- SIMs ----
                Text("Your SIMs", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (snap.sims.isEmpty()) {
                    Text("No SIM data yet. Start monitoring and allow phone permission.")
                }
                snap.sims.forEach { s ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text("SIM ${s.slot} · ${s.carrier}", fontWeight = FontWeight.Bold)
                            val dbm = s.dbm?.let { "$it dBm" } ?: "dBm not available"
                            Text("${s.netType} · $dbm · bars ${s.level} of 4")
                        }
                    }
                }
                if (snap.sims.size > 1) {
                    val best = snap.sims.maxByOrNull { it.level }
                    if (best != null && snap.sims.any { it.level < best.level }) {
                        Text("SIM ${best.slot} (${best.carrier}) is stronger here. Consider using it for data.")
                    }
                }

                // ---- outbox ----
                Text("Outbox", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Type a message now. When signal is good, tap to send it.")
                var to by remember { mutableStateOf("") }
                var msg by remember { mutableStateOf("") }
                OutlinedTextField(to, { to = it }, label = { Text("Phone number") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(msg, { msg = it }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = {
                        if (msg.isNotBlank()) {
                            Store.queue(to.trim(), msg.trim())
                            msg = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Queue message") }

                val ready = live && snap.score >= 3
                outbox.forEach { q ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("To ${q.to.ifBlank { "(no number)" }}", fontWeight = FontWeight.Bold)
                            Text(q.text)
                            if (ready) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        openSafely(
                                            ctx,
                                            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(q.to)))
                                                .putExtra("sms_body", q.text)
                                        )
                                    }) { Text("SMS") }
                                    OutlinedButton(onClick = {
                                        var d = q.to.filter { it.isDigit() }
                                        if (d.length == 10) d = "91$d"
                                        openSafely(
                                            ctx,
                                            Intent(Intent.ACTION_VIEW,
                                                Uri.parse("https://wa.me/$d?text=" + Uri.encode(q.text)))
                                        )
                                    }) { Text("WhatsApp") }
                                }
                            } else {
                                Text("Waiting for better signal")
                            }
                            OutlinedButton(onClick = { Store.removeQueued(q.id) }) { Text("Mark sent or delete") }
                        }
                    }
                }

                // ---- zones ----
                Text("Dead zones learned", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (zones.isEmpty()) {
                    Text("None yet. Keep monitoring on while you travel and they will appear here.")
                } else {
                    Text("${zones.size} saved. You get a warning up to 10 km before one.")
                    zones.sortedByDescending { it.lastTs }.take(6).forEach { z ->
                        val dur = if (z.samples > 0) " · about ${maxOf(1, (z.avgSecs / 60).toInt())} min" else ""
                        Text("%.3f, %.3f · dropped %d times%s".format(z.lat, z.lon, z.count, dur))
                    }
                    OutlinedButton(onClick = { Store.clearZones() }) { Text("Clear saved zones") }
                }

                // ---- sources ----
                Text("Hub sources", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("News sites and podcasts the hub fills from. Add any RSS or podcast feed link.")
                feeds.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(f.name, Modifier.weight(1f))
                        OutlinedButton(onClick = { Store.removeFeed(f.url) }) { Text("Remove") }
                    }
                }
                var feedUrl by remember { mutableStateOf("") }
                OutlinedTextField(feedUrl, { feedUrl = it }, label = { Text("Feed link (https://...)") }, modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = {
                        val u = feedUrl.trim()
                        if (u.startsWith("http")) {
                            Store.addFeed(Uri.parse(u).host ?: u, u)
                            feedUrl = ""
                        } else {
                            Toast.makeText(ctx, "Paste a link starting with https://", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Add source") }

                // ---- battery ----
                Text("Keep it running", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Some phones stop background apps to save battery. Allow SignalShield to run unrestricted.")
                OutlinedButton(
                    onClick = { openSafely(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Open battery settings") }
            }
        }
    }
}
