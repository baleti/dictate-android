package dev.local.dictate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/**
 * The trigger: tap this tile from the pulled-down Quick Settings shade,
 * from anywhere in the OS, to start/stop a dictation. Text always goes
 * to the clipboard first (the guaranteed path -- asked for explicitly:
 * "just to be safe also put that into clipboard in case something goes
 * wrong"), then DictateAccessibilityService is asked to insert it into
 * whatever's actually focused, if that service is enabled and the
 * focused field accepts it.
 */
class DictateTileService : TileService() {
    private val audioRecorder = AudioRecorder()
    @Volatile private var transcribing = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onClick() {
        super.onClick()
        if (transcribing) return
        if (audioRecorder.isRecording()) {
            stopAndTranscribe()
            return
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Open the Dictate app once to grant microphone access")
            return
        }
        audioRecorder.start()
        setTile(Tile.STATE_ACTIVE, "Recording…")
    }

    override fun onStartListening() {
        super.onStartListening()
        setTile(
            if (audioRecorder.isRecording()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE,
            if (audioRecorder.isRecording()) "Recording…" else "Dictate",
        )
    }

    private fun stopAndTranscribe() {
        val pcm = audioRecorder.stop()
        transcribing = true
        setTile(Tile.STATE_INACTIVE, "Transcribing…")
        Thread {
            try {
                val text = SttClient.transcribe(this, pcm)
                transcribing = false
                mainHandler.post {
                    setTile(Tile.STATE_INACTIVE, "Dictate")
                    if (text.isBlank()) {
                        toast("Heard nothing")
                        return@post
                    }
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Dictated text", text))
                    val injected = DictateAccessibilityService.instance?.insertText(text) ?: false
                    toast(if (injected) "Inserted (and copied)" else "Copied to clipboard - paste it in")
                }
            } catch (e: Exception) {
                transcribing = false
                mainHandler.post {
                    setTile(Tile.STATE_INACTIVE, "Dictate")
                    toast("Transcription failed: ${e.message}")
                }
            }
        }.apply { isDaemon = true; name = "DictateTranscribe"; start() }
    }

    private fun setTile(state: Int, label: String) {
        val tile = qsTile ?: return
        tile.state = state
        tile.label = label
        tile.updateTile()
    }

    private fun toast(msg: String) {
        mainHandler.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }
}
