package com.kiransaj.diyamonitor.telegram

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class TelegramException(message: String, val retryable: Boolean) : IOException(message)

/** Minimal Telegram Bot API client (sendMessage, sendPhoto, getUpdates). Blocking; call off the main thread. */
class TelegramClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    fun sendMessage(token: String, chatId: String, text: String, silent: Boolean = false) {
        val body = FormBody.Builder()
            .add("chat_id", chatId)
            .add("text", text.take(4000))
            .add("disable_notification", silent.toString())
            .build()
        call(token, "sendMessage", body)
    }

    fun sendPhoto(token: String, chatId: String, jpeg: ByteArray, caption: String, silent: Boolean = false) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart("caption", caption.take(1000))
            .addFormDataPart("disable_notification", silent.toString())
            .addFormDataPart("photo", "diya.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
            .build()
        call(token, "sendPhoto", body)
    }

    /** Returns the most recent chat (id to title) that messaged the bot, or null if none. */
    fun findLatestChat(token: String): Pair<String, String>? {
        // offset=-1 returns only the newest update; without it Telegram returns the oldest
        // 100 unconfirmed ones, so a recent message from a new chat could be missed.
        val json = call(token, "getUpdates", FormBody.Builder().add("offset", "-1").build())
        val results = json.optJSONArray("result") ?: return null
        for (i in results.length() - 1 downTo 0) {
            val update = results.getJSONObject(i)
            val msg = update.optJSONObject("message") ?: update.optJSONObject("channel_post") ?: update.optJSONObject("my_chat_member")
            val chat = msg?.optJSONObject("chat") ?: continue
            val title = chat.optString("title").ifBlank {
                listOf(chat.optString("first_name"), chat.optString("last_name")).filter { it.isNotBlank() }.joinToString(" ")
            }
            return chat.get("id").toString() to title
        }
        return null
    }

    private fun call(token: String, method: String, body: RequestBody): JSONObject {
        if (token.isBlank()) throw TelegramException("Bot token not set", retryable = false)
        val request = Request.Builder().url("https://api.telegram.org/bot$token/$method").post(body).build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (resp.isSuccessful && json?.optBoolean("ok") == true) return json
            val description = json?.optString("description")?.ifBlank { null } ?: "HTTP ${resp.code}"
            // 429 (rate limit) and 5xx are worth retrying; other 4xx mean bad token/chat id.
            val retryable = resp.code == 429 || resp.code >= 500
            throw TelegramException("Telegram $method failed: $description", retryable)
        }
    }
}
