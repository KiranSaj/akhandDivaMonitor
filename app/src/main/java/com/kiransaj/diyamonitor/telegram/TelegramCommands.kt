package com.kiransaj.diyamonitor.telegram

import android.util.Log
import com.kiransaj.diyamonitor.data.Prefs
import kotlin.concurrent.thread

/**
 * Listens for bot commands (e.g. /status, /log) while monitoring runs. Only messages from
 * the configured chat are accepted. Commands sent before listening started are ignored.
 */
class TelegramCommands(
    private val prefs: Prefs,
    private val onCommand: (command: String) -> Unit,
) {
    private val client = TelegramClient()
    @Volatile private var running = false
    private var worker: Thread? = null

    fun start() {
        if (running) return
        running = true
        worker = thread(name = "telegram-commands", isDaemon = true) { loop() }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }

    private fun loop() {
        var offset: Long? = null
        while (running) {
            try {
                val token = prefs.botToken
                if (token.isBlank()) {
                    Thread.sleep(RETRY_MS)
                    continue
                }
                // Skip anything sent before monitoring started.
                val from = offset ?: ((client.latestUpdateId(token) ?: -1L) + 1).also { offset = it }
                for (msg in client.getUpdates(token, from)) {
                    offset = msg.updateId + 1
                    if (msg.chatId != prefs.chatId || !msg.text.startsWith("/")) continue
                    // "/status@MyDiyaBot extra" -> "/status"
                    val command = msg.text.substringBefore(' ').substringBefore('@').lowercase()
                    runCatching { onCommand(command) }.onFailure { Log.e(TAG, "command $command failed", it) }
                }
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                Log.w(TAG, "getUpdates failed: ${e.message}")
                try {
                    Thread.sleep(RETRY_MS)
                } catch (ie: InterruptedException) {
                    return
                }
            }
        }
    }

    companion object {
        private const val TAG = "TelegramCommands"
        private const val RETRY_MS = 15_000L
        const val HELP = "Commands:\n/status: photo and current readings\n/log: tonight's log so far (zip)"
    }
}
