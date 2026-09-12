package dev.local.dictate

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Spawns a new claude session on a peer host via its peer-agent HTTP
 * bridge -- ported from the old Termux .shortcuts/claude*-* family
 * (claude/claude2/claude3 x host3/host6/hub/wsl, 12 scripts total),
 * migrated off Termux entirely as part of moving those shortcuts into
 * this app's own assist menu (asked for explicitly 2026-09-12). Same
 * request each of those made: POST /run/<account> with the X-Peer-Agent
 * header, no body.
 */
object PeerAgentClient {
    data class SpawnTarget(val label: String, val host: String, val account: String)

    val targets: List<SpawnTarget> = listOf(
        SpawnTarget("claude @ hub", "10.10.0.1", "claude"),
        SpawnTarget("claude2 @ hub", "10.10.0.1", "claude2"),
        SpawnTarget("claude3 @ hub", "10.10.0.1", "claude3"),
        SpawnTarget("claude @ host3", "10.10.0.2", "claude"),
        SpawnTarget("claude2 @ host3", "10.10.0.2", "claude2"),
        SpawnTarget("claude3 @ host3", "10.10.0.2", "claude3"),
        SpawnTarget("claude @ wsl", "10.10.0.3", "claude"),
        SpawnTarget("claude2 @ wsl", "10.10.0.3", "claude2"),
        SpawnTarget("claude3 @ wsl", "10.10.0.3", "claude3"),
        SpawnTarget("claude @ host6", "10.10.0.4", "claude"),
        SpawnTarget("claude2 @ host6", "10.10.0.4", "claude2"),
        SpawnTarget("claude3 @ host6", "10.10.0.4", "claude3"),
    )

    /** Returns the spawned session id/name on success, or a short
     * description of the failure -- never throws, same "always show
     * something" behavior the old script's termux-notification gave. */
    fun spawn(target: SpawnTarget): String {
        return try {
            val url = URL("http://${target.host}:8787/run/${target.account}")
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-Peer-Agent", "1")
                conn.connectTimeout = 8000
                conn.readTimeout = 30000
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) return "Failed (HTTP $code): ${body.take(120)}"
                val session = try { JSONObject(body).optString("session", "") } catch (e: Exception) { "" }
                if (session.isNotBlank()) session else "Failed: ${body.take(120)}"
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            "Failed: ${e.message}"
        }
    }
}
