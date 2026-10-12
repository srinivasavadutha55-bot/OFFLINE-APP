package com.signalshield.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

object AudioPlayer {
    private var mp: MediaPlayer? = null
    val playingId = MutableStateFlow<Long?>(null)

    fun toggle(ctx: Context, item: HubItem, queue: List<HubItem> = emptyList()) {
        if (playingId.value == item.id) {
            stop()
            return
        }
        stop()
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(
                        if (item.type == "song") AudioAttributes.CONTENT_TYPE_MUSIC
                        else AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build())
            p.setWakeMode(ctx.applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            p.setDataSource(File(Hub.dir(ctx), item.file).absolutePath)
            p.setOnCompletionListener {
                // Play the next song in the list, if there is one.
                val i = queue.indexOfFirst { it.id == item.id }
                val next = if (i >= 0 && i + 1 < queue.size) queue[i + 1] else null
                stop()
                if (next != null) toggle(ctx, next, queue)
            }
            p.setOnErrorListener { _, _, _ -> stop(); true }
            p.prepare()
            p.start()
            mp = p
            playingId.value = item.id
        } catch (e: Exception) {
            try {
                p.release()
            } catch (e2: Exception) {
            }
            playingId.value = null
        }
    }

    fun stop() {
        try {
            mp?.stop()
        } catch (e: Exception) {
        }
        try {
            mp?.release()
        } catch (e: Exception) {
        }
        mp = null
        playingId.value = null
    }
}
