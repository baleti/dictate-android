package dev.local.dictate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Owns transcription end-to-end (network call, clipboard, insertion)
 * independent of any Activity -- asked for explicitly 2026-09-20:
 * "recording should be modal, but after tapping to stop, transcription
 * should be in the background", so you can switch apps (or start another
 * dictation, queueing it) the instant you tap stop, rather than staring
 * at a progress bar. AssistActivity's stop handler calls enqueue() and
 * finishes immediately.
 *
 * NOT a foreground Service -- that was the first version of this, and
 * confirmed live 2026-09-20 that this OS version enforces a "Stop FGS
 * timeout" around ~19s regardless of foregroundServiceType (tried both
 * dataSync and mediaProcessing), well under how long a real network
 * transcription can take. This doesn't actually need a Service at all:
 * DictateAccessibilityService is already a persistently-bound
 * accessibility service (Android gives those real process-lifetime
 * protection, the same reason DictateTileService's own plain background
 * Thread has always worked fine with zero ceremony) -- as long as that's
 * enabled, this app's process is already alive for as long as this needs
 * it to be, no separate foreground-service lifecycle required.
 *
 * Feedback is a real Notification, not a Toast -- confirmed live the
 * same day that Toasts from a background (non-foreground-Activity)
 * context get silently killed by the system ("Toast already killed" in
 * logcat) on this OS version. A plain Notification isn't subject to
 * that restriction, just needs POST_NOTIFICATIONS (already requested in
 * MainActivity).
 *
 * Jobs process strictly one at a time, in the order they were enqueued
 * -- multiple dictations started in quick succession finish in the same
 * order they were recorded, not racing each other over who inserts
 * first.
 */
object DictateTranscribeService {
    private const val CHANNEL_ID = "dictate_transcribe"
    private const val NOTIFICATION_ID = 5301

    private val jobs = ConcurrentLinkedQueue<Job>()
    @Volatile private var processing = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private class Job(val context: Context, val pcm: ByteArray, val target: AccessibilityNodeInfo?)

    /** `target` is whatever DictateAccessibilityService.captureTarget()
     * returned at the moment recording was stopped -- may be null if
     * nothing was focused/found then, in which case insertion falls back
     * to a fresh lookup once transcription actually finishes. */
    fun enqueue(context: Context, pcm: ByteArray, target: AccessibilityNodeInfo?) {
        jobs.add(Job(context.applicationContext, pcm, target))
        ensureChannel(context)
        processNext()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Dictate transcription", NotificationManager.IMPORTANCE_LOW)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notify(context: Context, text: String) {
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("Dictate")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun processNext() {
        if (processing) return
        val job = jobs.poll() ?: return
        processing = true
        val queuedMore = jobs.size
        notify(job.context, if (queuedMore > 0) "Transcribing… ($queuedMore more queued)" else "Transcribing…")
        Thread {
            try {
                val text = SttClient.transcribe(job.context, job.pcm)
                mainHandler.post { finishJob(job, text) }
            } catch (e: Exception) {
                mainHandler.post {
                    notify(job.context, "Transcription failed: ${e.message}")
                    processing = false
                    processNext()
                }
            }
        }.apply { isDaemon = true; name = "DictateTranscribe"; start() }
    }

    private fun finishJob(job: Job, text: String) {
        if (text.isBlank()) {
            notify(job.context, "Heard nothing")
            processing = false
            processNext()
            return
        }
        val clipboard = job.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Dictated text", text))
        val service = DictateAccessibilityService.instance
        val injected = when {
            service == null -> false
            job.target != null -> service.insertInto(job.target, text)
            else -> service.insertText(text)
        }
        notify(job.context, if (injected) "Inserted (and copied)" else "Copied to clipboard - paste it in")
        processing = false
        processNext()
    }
}
