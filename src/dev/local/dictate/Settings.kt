package dev.local.dictate

import android.content.Context

/**
 * Host/port + STT model choice for the shared audio server (see
 * ~/src/newsdigest-android/server/server.py's /stt/transcribe) -- same
 * server the other two apps' dictation already talks to, just reused
 * here for system-wide dictation instead of one app's chat box. No
 * separate token: that server's security is the WireGuard tunnel plus a
 * fixed non-secret header (see server.py's own docstring), not a
 * credential.
 */
object Settings {
    private const val PREFS = "dictate_prefs"
    const val DEFAULT_PORT = 8792

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getHost(context: Context): String = prefs(context).getString("host", "") ?: ""

    fun setHost(context: Context, host: String) {
        prefs(context).edit().putString("host", host).apply()
    }

    fun getPort(context: Context): Int = prefs(context).getInt("port", DEFAULT_PORT)

    fun setPort(context: Context, port: Int) {
        prefs(context).edit().putInt("port", port).apply()
    }

    // Switched medium -> small -> whisper-medium-gpu, all 2026-09-20.
    // small (CPU) was faster than medium (CPU) but a real accuracy
    // regression ("Could you" transcribed as "Kuchu"). Then found ai1
    // actually has a second, otherwise-idle GPU the CPU-only assumption
    // never accounted for - medium on that GPU transcribes in ~0.5-0.8s
    // (confirmed live end to end through the real server, not just an
    // isolated benchmark), full medium accuracy, faster than CPU small
    // ever was. ai1's tts-stt-server keeps all three loaded, so this is
    // just this app's own default preference, not a hard requirement.
    fun getSttModel(context: Context): String = prefs(context).getString("stt_model", "whisper-medium-gpu") ?: "whisper-medium-gpu"

    fun setSttModel(context: Context, model: String) {
        prefs(context).edit().putString("stt_model", model).apply()
    }
}
