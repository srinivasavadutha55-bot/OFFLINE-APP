package com.signalshield.app

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import java.net.HttpURLConnection
import java.net.URL

class SignalReader(private val ctx: Context) {
    private val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** One entry per active SIM (works on dual-SIM phones). */
    @SuppressLint("MissingPermission")
    fun readSims(): List<SimInfo> {
        val out = mutableListOf<SimInfo>()
        try {
            val sm = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val subs = sm.activeSubscriptionInfoList ?: emptyList()
            for (s in subs) {
                val t = tm.createForSubscriptionId(s.subscriptionId)
                out.add(info(t, s.simSlotIndex + 1, s.carrierName?.toString()))
            }
        } catch (e: SecurityException) {
            // READ_PHONE_STATE not granted
        } catch (e: Exception) {
        }
        if (out.isEmpty()) {
            try {
                out.add(info(tm, 1, null))
            } catch (e: Exception) {
            }
        }
        return out
    }

    @SuppressLint("MissingPermission")
    private fun info(t: TelephonyManager, slot: Int, name: String?): SimInfo {
        var dbm: Int? = null
        var level = 0
        try {
            val ss = t.signalStrength
            if (ss != null) {
                level = ss.level
                if (Build.VERSION.SDK_INT >= 29) {
                    dbm = ss.cellSignalStrengths.firstOrNull()?.dbm
                }
            }
        } catch (e: Exception) {
        }
        val type = try {
            netTypeName(t.dataNetworkType)
        } catch (e: SecurityException) {
            "?"
        }
        val carrier = (name ?: t.networkOperatorName).orEmpty().ifBlank { "SIM $slot" }
        return SimInfo(slot, carrier, type, dbm, level)
    }

    private fun netTypeName(t: Int): String = when (t) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_LTE -> "4G"
        TelephonyManager.NETWORK_TYPE_HSPAP,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "none"
        else -> "other"
    }

    private fun caps(): NetworkCapabilities? {
        val n = cm.activeNetwork ?: return null
        return cm.getNetworkCapabilities(n)
    }

    fun validated(): Boolean {
        val c = caps() ?: return false
        return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun onWifi(): Boolean =
        caps()?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

    /** Round-trip time in ms to a tiny Google endpoint, or null if unreachable. */
    fun ping(): Long? {
        return try {
            val t0 = System.nanoTime()
            val conn = URL("https://connectivitycheck.gstatic.com/generate_204")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.useCaches = false
            conn.requestMethod = "GET"
            conn.responseCode
            conn.disconnect()
            (System.nanoTime() - t0) / 1_000_000
        } catch (e: Exception) {
            null
        }
    }

    /** 0 = no internet ... 4 = strong. */
    fun score(sims: List<SimInfo>, internetOk: Boolean, latency: Long?): Int {
        if (!internetOk || latency == null) return 0
        var s = if (onWifi()) 4 else (sims.maxOfOrNull { it.level } ?: 4).coerceIn(0, 4)
        if (latency > 2500) s = minOf(s, 1)
        else if (latency > 1200) s = minOf(s, 2)
        else if (latency > 600) s = minOf(s, 3)
        return s
    }
}
