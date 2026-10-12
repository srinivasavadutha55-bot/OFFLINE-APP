package com.signalshield.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class MonitorService : Service() {

    companion object {
        const val ACTION_STOP = "com.signalshield.app.STOP"
        const val CH_MON = "monitor"
        const val CH_ALERT = "alerts"
        const val ID_MON = 1
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private lateinit var reader: SignalReader
    private var lm: LocationManager? = null
    private var loc: Location? = null
    private var locTime = 0L
    private var lastMove = 0L
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var prevScore = 4
    private var wasBackup = false
    private var first = true
    private var outageStart = 0L
    private var outageZone: Long? = null
    private val warned = HashMap<Long, Long>()
    private var nid = 100

    private var lastHub = 0L
    private var lastPending = 0L
    private var currentFg: String? = null
    private var lastUsageQuery = 0L

    private val locListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            loc = l
            locTime = System.currentTimeMillis()
            if (l.hasSpeed() && l.speed > 3f) lastMove = locTime
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        reader = SignalReader(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_MON, "Monitoring", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Signal alerts", NotificationManager.IMPORTANCE_HIGH))
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("en", "IN")
                ttsReady = true
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val n = monitorNotification("Starting...")
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID_MON, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(ID_MON, n)
            }
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        startLocation()
        startLoop()
        return START_STICKY
    }

    private fun startLocation() {
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        try {
            lm?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 3000L, 0f, locListener, Looper.getMainLooper())
        } catch (e: Exception) {
        }
        try {
            lm?.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, 5000L, 0f, locListener, Looper.getMainLooper())
        } catch (e: Exception) {
        }
    }

    private fun startLoop() {
        if (job != null) return
        Store.running.value = true
        job = scope.launch {
            while (isActive) {
                try {
                    step()
                } catch (e: Exception) {
                }
                delay(if (Store.backupOn.value) 8000L else 5000L)
            }
        }
    }

    private fun hubText(): String {
        val m = Hub.totalSeconds() / 60
        return if (m > 0) " Your backup hub has $m min ready." else ""
    }

    private fun appsText(): String {
        val a = Store.topAppsText(3)
        return if (a != null) " Apps you often use while travelling, like $a, will stop." else ""
    }

    /** Learns which app is on screen while you are travelling (needs Usage access). */
    @Suppress("DEPRECATION")
    private fun trackForeground(now: Long, moving: Boolean) {
        if (!Usage.hasAccess(this)) return
        try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val from = if (lastUsageQuery == 0L) now - 300000L else lastUsageQuery
            val ev = usm.queryEvents(from, now)
            val e = UsageEvents.Event()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                when (e.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> currentFg = e.packageName
                    UsageEvents.Event.MOVE_TO_BACKGROUND -> if (e.packageName == currentFg) currentFg = null
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> currentFg = null
                }
            }
            lastUsageQuery = now
            val pkg = currentFg
            if (moving && pkg != null && !Usage.ignore(this, pkg)) {
                Store.bumpApp(pkg, Usage.label(this, pkg), 5)
            }
        } catch (e: Exception) {
        }
    }

    private fun step() {
        val sims = reader.readSims()
        val ok = reader.validated()
        val latency = if (ok) reader.ping() else null
        val score = reader.score(sims, ok, latency)
        val now = System.currentTimeMillis()
        val l = loc?.takeIf { now - locTime < 60000L }

        val snap = Snapshot(
            score = score,
            sims = sims,
            latencyMs = latency,
            internetOk = ok && latency != null,
            lat = l?.latitude,
            lon = l?.longitude,
            speedMps = if (l != null && l.hasSpeed()) l.speed else 0f,
            bearing = if (l != null && l.hasBearing()) l.bearing else null,
            time = now
        )
        val backup = score <= 2

        val moving = now - lastMove < 120000L
        trackForeground(now, moving)

        // Outage tracking: record a dead zone only while travelling.
        if (score <= 1 && prevScore > 1 && outageStart == 0L) {
            outageStart = now
            outageZone = if (l != null && moving) Store.addZoneHit(l.latitude, l.longitude) else null
        } else if (score >= 2 && outageStart != 0L) {
            val secs = (now - outageStart) / 1000.0
            val z = outageZone
            if (z != null && secs > 10) Store.addDuration(z, secs)
            outageStart = 0L
            outageZone = null
        }

        if (!first) {
            if (backup && !wasBackup) {
                alert("Signal dropped. Backup mode is on." + hubText() + " Open SignalShield.")
            }
            if (!backup && wasBackup) {
                val q = Store.outbox.value.size
                alert("Signal is back." +
                    if (q > 0) " $q queued message${if (q > 1) "s are" else " is"} ready to send." else "")
            }
        }

        // Look ahead for a remembered dead zone.
        var warnText: String? = null
        var nearZone = false
        if (l != null) {
            val ah = Store.zoneAhead(l.latitude, l.longitude, snap.bearing, snap.speedMps)
            if (ah != null) {
                nearZone = true
                warnText = Store.describe(ah, snap.speedMps) + appsText() + hubText() +
                    " Send your messages now."
                val last = warned[ah.zone.id]
                if (ah.meters < 5000.0 && (last == null || now - last > 1800000L)) {
                    warned[ah.zone.id] = now
                    alert(warnText)
                }
            }
        }

        // Keep the backup hub topped up, with no taps needed.
        val have = Hub.totalSeconds()
        val target = Store.targetMin.value * 60
        val interval = if (have < target) (if (nearZone) 120000L else 600000L) else 6L * 3600000L
        if (!backup && score >= 3 && now - lastHub > interval) {
            lastHub = now
            Hub.refreshAsync(applicationContext)
        }
        // Searches and pages asked for with no signal: look them up as soon as signal is good.
        if (!backup && score >= 3 && Store.pending.value.isNotEmpty() && now - lastPending > 60000L) {
            lastPending = now
            Hub.runPendingAsync(applicationContext)
        }

        Store.warning.value = warnText
        Store.snapshot.value = snap
        Store.history.value = (Store.history.value + score).takeLast(60)
        Store.backupOn.value = backup

        prevScore = score
        wasBackup = backup
        first = false

        val simText = sims.joinToString(" | ") { "SIM${it.slot} ${it.carrier} ${it.netType}" }
        getSystemService(NotificationManager::class.java)
            .notify(ID_MON, monitorNotification("${LABELS[score]} · $simText"))
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun monitorNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, MonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Stop", stop).build()
        return Notification.Builder(this, CH_MON)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("SignalShield is watching")
            .setContentText(text)
            .setContentIntent(openApp())
            .setOngoing(true)
            .addAction(action)
            .build()
    }

    private fun alert(msg: String) {
        try {
            val n = Notification.Builder(this, CH_ALERT)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("SignalShield")
                .setContentText(msg)
                .setStyle(Notification.BigTextStyle().bigText(msg))
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java).notify(nid++, n)
        } catch (e: Exception) {
        }
        try {
            val v = getSystemService(Vibrator::class.java)
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200), -1))
        } catch (e: Exception) {
        }
        if (ttsReady) tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "ss")
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        try {
            lm?.removeUpdates(locListener)
        } catch (e: Exception) {
        }
        tts?.shutdown()
        Store.running.value = false
        super.onDestroy()
    }
}
