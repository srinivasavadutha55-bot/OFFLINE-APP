package com.signalshield.app

data class SimInfo(
    val slot: Int,
    val carrier: String,
    val netType: String,
    val dbm: Int?,
    val level: Int
)

data class Zone(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val count: Int,
    val avgSecs: Double,
    val samples: Int,
    val lastTs: Long
)

data class Ahead(val zone: Zone, val meters: Double)

data class Queued(val id: Long, val to: String, val text: String)

data class Snapshot(
    val score: Int = 0,
    val sims: List<SimInfo> = emptyList(),
    val latencyMs: Long? = null,
    val internetOk: Boolean = false,
    val lat: Double? = null,
    val lon: Double? = null,
    val speedMps: Float = 0f,
    val bearing: Float? = null,
    val time: Long = 0L
)

val LABELS = listOf("No internet", "Very weak", "Weak", "Okay", "Strong")

data class HubItem(
    val id: Long,
    val type: String,      // article, audio (podcast), song, video, wiki, saved
    val title: String,
    val source: String,
    val seconds: Int,
    val file: String,
    val ts: Long,
    val url: String
)

data class Feed(val name: String, val url: String)

data class AppUse(val pkg: String, val label: String, val seconds: Int)

data class Pending(val id: Long, val query: String, val ts: Long)

/** Item types that count toward the backup hub's 15 or 30 minute target. */
val COUNTED = setOf("article", "audio", "song", "video")
