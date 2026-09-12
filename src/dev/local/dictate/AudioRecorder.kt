package dev.local.dictate

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

/**
 * Records raw PCM16 mono audio at 16kHz -- Whisper's own native input
 * rate (see server.py's /stt/transcribe), so the server never needs to
 * resample or decode a container/codec at all; what's captured here is
 * exactly the bytes POSTed to it.
 */
class AudioRecorder {
    companion object {
        const val SAMPLE_RATE = 16000
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile private var recording = false
    private val buffer = ByteArrayOutputStream()

    fun isRecording(): Boolean = recording

    fun start() {
        if (recording) return
        synchronized(buffer) { buffer.reset() }
        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBufSize, 4096)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        audioRecord = rec
        rec.startRecording()
        recording = true
        recordingThread = Thread {
            val readBuf = ByteArray(bufSize)
            while (recording) {
                val n = rec.read(readBuf, 0, readBuf.size)
                if (n > 0) {
                    synchronized(buffer) { buffer.write(readBuf, 0, n) }
                }
            }
        }.apply { isDaemon = true; name = "AudioRecorder"; start() }
    }

    /** Stops recording and returns the raw PCM16 mono bytes captured. */
    fun stop(): ByteArray {
        recording = false
        try { recordingThread?.join(500) } catch (_: InterruptedException) {}
        recordingThread = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        return synchronized(buffer) { buffer.toByteArray() }
    }
}
