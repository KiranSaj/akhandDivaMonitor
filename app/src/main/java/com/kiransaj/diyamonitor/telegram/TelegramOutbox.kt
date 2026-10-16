package com.kiransaj.diyamonitor.telegram

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.kiransaj.diyamonitor.data.Prefs
import com.kiransaj.diyamonitor.service.MonitorState
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Queues outgoing Telegram messages and delivers them in order on a background thread.
 * Network failures are retried with exponential backoff (and immediately when the
 * network comes back), so alerts survive Wi-Fi drops.
 */
class TelegramOutbox private constructor(context: Context) {

    data class Outgoing(val text: String, val photo: ByteArray?, val createdAtMs: Long = System.currentTimeMillis())

    private val prefs = Prefs(context)
    private val client = TelegramClient()
    private val queue = LinkedBlockingDeque<Outgoing>()
    private val wake = LinkedBlockingQueue<Unit>()

    init {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    wake.offer(Unit)
                }
            })
        }
        thread(name = "telegram-outbox", isDaemon = true) { loop() }
    }

    /** Queues a message. In quiet test mode it is prefixed with [TEST] and sent silently. */
    fun enqueue(text: String, photo: ByteArray? = null) {
        val prefixed = if (prefs.testMode) "[TEST] $text" else text
        while (queue.size >= MAX_QUEUE) queue.pollFirst()
        queue.offerLast(Outgoing(prefixed, photo))
        MonitorState.queueSize.value = queue.size
    }

    /**
     * Best-effort immediate send used when the app is about to die (crash/stop).
     * Waits at most [timeoutMs].
     */
    fun sendNow(text: String, timeoutMs: Long = 5_000) {
        val t = thread(name = "telegram-urgent") {
            runCatching { client.sendMessage(prefs.botToken, prefs.chatId, text, prefs.testMode) }
        }
        t.join(timeoutMs)
    }

    private fun loop() {
        var backoffMs = INITIAL_BACKOFF_MS
        while (true) {
            val item = queue.takeFirst()
            MonitorState.queueSize.value = queue.size + 1
            while (true) {
                if (System.currentTimeMillis() - item.createdAtMs > MAX_AGE_MS) {
                    Log.w(TAG, "Dropping stale message")
                    break
                }
                try {
                    deliver(item)
                    MonitorState.lastTelegramError.value = null
                    backoffMs = INITIAL_BACKOFF_MS
                    break
                } catch (e: TelegramException) {
                    if (!e.retryable) {
                        Log.e(TAG, "Not retrying: ${e.message}")
                        MonitorState.lastTelegramError.value = e.message
                        break
                    }
                    waitBeforeRetry(e, backoffMs)
                } catch (e: Exception) {
                    waitBeforeRetry(e, backoffMs)
                }
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
            MonitorState.queueSize.value = queue.size
        }
    }

    private fun waitBeforeRetry(e: Exception, backoffMs: Long) {
        Log.w(TAG, "Send failed, retrying in ${backoffMs / 1000}s: ${e.message}")
        MonitorState.lastTelegramError.value = "${e.message} (will retry)"
        wake.clear()
        wake.poll(backoffMs, TimeUnit.MILLISECONDS)
    }

    private fun deliver(item: Outgoing) {
        val token = prefs.botToken
        val chat = prefs.chatId
        if (token.isBlank() || chat.isBlank()) throw TelegramException("Telegram is not set up", retryable = false)
        if (item.photo != null) {
            client.sendPhoto(token, chat, item.photo, item.text, prefs.testMode)
            // Captions are limited to 1024 chars; send the rest as text if needed.
            if (item.text.length > 1000) client.sendMessage(token, chat, item.text.drop(1000), prefs.testMode)
        } else {
            client.sendMessage(token, chat, item.text, prefs.testMode)
        }
    }

    companion object {
        private const val TAG = "TelegramOutbox"
        private const val MAX_QUEUE = 50
        private const val INITIAL_BACKOFF_MS = 5_000L
        private const val MAX_BACKOFF_MS = 5 * 60_000L
        private const val MAX_AGE_MS = 12 * 3600_000L

        @Volatile
        private var instance: TelegramOutbox? = null

        fun get(context: Context): TelegramOutbox =
            instance ?: synchronized(this) { instance ?: TelegramOutbox(context.applicationContext).also { instance = it } }
    }
}
