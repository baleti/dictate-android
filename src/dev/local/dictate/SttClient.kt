package dev.local.dictate

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to server.py's /stt/transcribe -- same protocol as
 * claude-agents-android's and newsdigest-android's own copies of this
 * class. Raw PCM16 mono 16kHz body in, {"text": "..."} back.
 */
object SttClient {
    fun transcribe(context: Context, pcm16: ByteArray): String {
        val host = Settings.getHost(context)
        val port = Settings.getPort(context)
        val model = Settings.getSttModel(context)
        val url = URL("http://$host:$port/stt/transcribe?model=$model")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("X-Peer-Agent", "1")
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.connectTimeout = 8000
            conn.readTimeout = 30000
            conn.setFixedLengthStreamingMode(pcm16.size)
            conn.outputStream.use { it.write(pcm16) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code: $text")
            return JSONObject(text).optString("text", "")
        } finally {
            conn.disconnect()
        }
    }

    /** Same transcription, but over /stt/stream instead of the plain
     * one-shot POST -- the server reports real per-segment progress
     * (see server.py's WhisperEngine.transcribe_streaming) instead of
     * the client having to guess from past run times. Blocks the
     * calling thread until onDone/onError fires (or the connection
     * itself fails, surfaced via onError too) -- call this from a
     * background thread, same as transcribe() above.
     *
     * onProgress may fire zero or more times before onDone; progress is
     * 0..1 (the last completed segment's end time over the clip's total
     * duration -- most short dictated clips only ever produce one real
     * segment, so this often just sits at 0 until the final jump to 1),
     * textSoFar is the transcript accumulated up to that segment, and
     * elapsedSeconds is how long the server has been working so far --
     * sent on every message, including a ~0.7s heartbeat the server
     * sends even when no new segment has completed yet, so there's
     * always something honest to show instead of a frozen "uploading"
     * label (asked for explicitly 2026-09-12: "is there nothing server
     * can report before?"). */
    fun transcribeStreaming(
        context: Context,
        pcm16: ByteArray,
        onProgress: (progress: Float, textSoFar: String, elapsedSeconds: Float) -> Unit,
        onDone: (text: String) -> Unit,
        onError: (message: String) -> Unit,
    ) {
        val host = Settings.getHost(context)
        val port = Settings.getPort(context)
        val model = Settings.getSttModel(context)
        val client = WebSocketClient(host, port, "/stt/stream", mapOf("X-Peer-Agent" to "1"))
        client.connect(object : WebSocketClient.Listener {
            override fun onOpen() {
                client.sendText(JSONObject().apply { put("model", model) }.toString())
                client.sendBinary(pcm16)
            }

            override fun onText(text: String) {
                val obj = JSONObject(text)
                when (obj.optString("type")) {
                    "progress" -> onProgress(
                        obj.optDouble("progress", 0.0).toFloat(),
                        obj.optString("text_so_far", ""),
                        obj.optDouble("elapsed", 0.0).toFloat(),
                    )
                    "done" -> {
                        onDone(obj.optString("text", ""))
                        client.close()
                    }
                    "error" -> {
                        onError(obj.optString("message", "unknown server error"))
                        client.close()
                    }
                }
            }

            override fun onFailure(error: Throwable) {
                onError(error.message ?: error.javaClass.simpleName)
            }
        })
        // connect() blocks until the socket closes -- by the time this
        // returns, one of onDone/onError/onFailure has already fired.
    }
}
