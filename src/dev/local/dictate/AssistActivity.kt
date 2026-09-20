package dev.local.dictate

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * The long-press-power trigger: registered as this device's Digital
 * Assistant app (see AndroidManifest's ACTION_ASSIST intent-filter), so
 * Android launches this directly on that gesture -- asked for explicitly
 * 2026-09-12 as the no-persistent-footprint alternative to a Quick
 * Settings tile.
 *
 * Shows a small extensible action menu first, not just Dictate directly
 * -- asked for explicitly the same day ("in the future we might want to
 * launch more actions"). Add a new entry to `actions` below for anything
 * else that should hang off this same long-press-power slot later; each
 * one gets a row here and owns rebuilding the card's content for
 * whatever it needs afterward (Dictate rebuilds it into a recording
 * status view, for instance).
 *
 * A translucent overlay, not a normal full-screen swap, so it reads like
 * a transient assistant popup over whatever app you were just in rather
 * than a jarring app switch (matches
 * android:theme="@android:style/Theme.Translucent.NoTitleBar" in the
 * manifest).
 *
 * Dictate's own recording UI stays modal (this overlay, tap to stop) --
 * but transcription and insertion happen in DictateTranscribeService
 * afterward, not here, so this overlay closes the instant recording
 * stops rather than sitting through the whole transcription wait (see
 * stopAndTranscribe()'s own doc). The field being dictated into is
 * captured (capturedTarget, see its own doc) as early as onCreate() --
 * this overlay's own window takes real input focus the moment it's
 * shown (confirmed live 2026-09-20: capturing any later, even at
 * "stop", consistently came back empty), so onCreate() is the earliest,
 * narrowest window available to still catch the previous app's field
 * before that handoff happens.
 */
class AssistActivity : Activity() {
    private val audioRecorder = AudioRecorder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var card: LinearLayout
    private var stopped = false

    // Captured in onCreate(), NOT at record-stop time -- confirmed live
    // 2026-09-20 that captureTarget() always came back empty when called
    // at stop-tap time instead. Root cause: this overlay's own window
    // takes real input focus the instant it's shown (translucency and
    // FLAG_NOT_TOUCH_MODAL only affect touch/visuals, not which single
    // window the system considers focused), so whatever field you were
    // typing in stops reporting itself as accessibility-focused the
    // moment this Activity appears -- well before "Dictate" is even
    // tapped, let alone "stop". Capturing here instead, as early in this
    // Activity's own lifecycle as possible, is still a race against that
    // same focus handoff, but a much narrower one.
    private var capturedTarget: android.view.accessibility.AccessibilityNodeInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        capturedTarget = DictateAccessibilityService.instance?.captureTarget()
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)

        val dp = { v: Int -> Theme.dp(this, v) }
        val root = FrameLayout(this).apply {
            setBackgroundColor(0x88000000.toInt())
            setOnClickListener { finish() }
        }
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.roundedDrawable(Theme.surface, this@AssistActivity, radiusDp = 16, strokeColor = Theme.primary)
            isClickable = true // swallows taps so they don't fall through to root's dismiss-on-click
        }
        val cardParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        cardParams.gravity = Gravity.CENTER
        root.addView(card, cardParams)
        setContentView(root)

        showActionMenu()
    }

    // Each entry is an optional icon (a plain black-silhouette PNG name
    // from res/drawable, tinted at load time -- asked for explicitly
    // 2026-09-12: "more plain simple ones... over colorful complex ones",
    // replacing an earlier emoji-per-row version), a label, and what
    // happens on tap. Append more here (or to a submenu like
    // showSpawnMenu below) rather than building a second entry point.
    // The last two were migrated off Termux's .shortcuts entirely --
    // asked for explicitly 2026-09-12.
    private data class MenuItem(val icon: String?, val label: String, val onTap: () -> Unit)

    private val actions: List<MenuItem> = listOf(
        MenuItem("ic_mic", "Dictate") { startDictating() },
        MenuItem("ic_readaloud", "Read Aloud") { readAloud() },
        MenuItem("ic_clipboard", "Strip markdown (clipboard)") { stripClipboardMarkdown() },
        MenuItem("ic_terminal", "Spawn Claude session") { showSpawnMenu() },
    )

    private fun showActionMenu() = buildMenu(actions)

    /** Shared by the top-level menu and showSpawnMenu's submenu -- same
     * row styling, same card, just a different item list. */
    private fun buildMenu(items: List<MenuItem>) {
        card.removeAllViews()
        card.setPadding(0, 0, 0, 0)
        card.setOnClickListener(null)
        val dp = { v: Int -> Theme.dp(this, v) }
        for ((i, item) in items.withIndex()) {
            if (i > 0) {
                val divider = View(this)
                divider.setBackgroundColor(Theme.primary and 0x33FFFFFF.toInt())
                card.addView(divider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
            }
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(24), dp(18), dp(24), dp(18))
            row.isClickable = true
            row.background = Theme.rippleOn(Theme.roundedDrawable(Color.TRANSPARENT, this, radiusDp = 0))
            row.setOnClickListener { item.onTap() }
            if (item.icon != null) {
                val iv = ImageView(this)
                val id = resources.getIdentifier(item.icon, "drawable", packageName)
                if (id != 0) {
                    iv.setImageDrawable(getDrawable(id))
                    iv.setColorFilter(Theme.onBackground, PorterDuff.Mode.SRC_IN)
                }
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                val iconSize = dp(22)
                val iconParams = LinearLayout.LayoutParams(iconSize, iconSize)
                iconParams.marginEnd = dp(14)
                row.addView(iv, iconParams)
            }
            val label = TextView(this)
            label.text = item.label
            label.textSize = 16f
            label.setTextColor(Theme.onBackground)
            row.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            card.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    // -------------------------------------------------------- markdown strip

    // Ported from the old Termux .shortcuts/md-clip-plain (same regex
    // passes, see MarkdownStrip) -- reads/writes the real Android
    // clipboard directly instead of termux-clipboard-get/set, since this
    // app already has that access.
    private fun stripClipboardMarkdown() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        val original = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (original.isNullOrEmpty()) {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val plain = MarkdownStrip.strip(original)
        clipboard.setPrimaryClip(ClipData.newPlainText("Plain text", plain))
        Toast.makeText(this, "Clipboard stripped of markdown", Toast.LENGTH_SHORT).show()
        finish()
    }

    // -------------------------------------------------------- spawn session

    // Ported from the old Termux .shortcuts/claude*-* family (12
    // separate scripts: claude/claude2/claude3 x host3/host6/hub/wsl) --
    // one submenu here instead, asked for explicitly ("quite a few
    // commands to spawn claude sessions, group them into one button").
    private fun showSpawnMenu() {
        val items = mutableListOf<MenuItem>()
        items.add(MenuItem(null, "←  Back") { showActionMenu() })
        for (target in PeerAgentClient.targets) {
            items.add(MenuItem(null, target.label) { spawnSession(target) })
        }
        buildMenu(items)
    }

    private fun spawnSession(target: PeerAgentClient.SpawnTarget) {
        buildMenu(emptyList())
        val dp = { v: Int -> Theme.dp(this, v) }
        card.setPadding(dp(28), dp(24), dp(28), dp(24))
        card.addView(TextView(this).apply {
            text = "Starting ${target.label}…"
            textSize = 16f
            setTextColor(Theme.onBackground)
        })
        Thread {
            val result = PeerAgentClient.spawn(target)
            mainHandler.post {
                Toast.makeText(applicationContext, result, Toast.LENGTH_LONG).show()
                finish()
            }
        }.apply { isDaemon = true; name = "SpawnSession"; start() }
    }

    // -------------------------------------------------------- read aloud

    // Read Aloud lives in its own separate app/repo (read-aloud-android,
    // added 2026-09-12) rather than being folded into this one -- its
    // scope (a per-app accessibility profile system, gesture-driving,
    // its own TTS pipeline) is wide enough to justify a repo of its own,
    // this app just needs to be the trigger since it already owns the
    // long-press-power slot. The Intent's package MUST be set explicitly:
    // confirmed live 2026-09-12 that a plain implicit sendBroadcast()
    // never reaches a manifest-declared receiver in another app at all
    // (Android 8+ restricts implicit broadcasts to static receivers) --
    // this silently did nothing the first time it was tried, no error,
    // no log, just no effect.
    //
    // No retry-delay loop the way finishWithResult()'s tryInsert needs
    // below: read-aloud-android's own accessibility service already
    // retries internally while waiting for window focus to return here.
    private fun readAloud() {
        finish()
        mainHandler.postDelayed({
            sendBroadcast(
                Intent("dev.local.readaloud.action.READ_CURRENT_SCREEN").setPackage("dev.local.readaloud"),
            )
        }, 300)
    }

    // ------------------------------------------------------------ dictate

    private fun startDictating() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Open the Dictate app once to grant microphone access", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (Settings.getHost(this).isBlank()) {
            Toast.makeText(this, "Open the Dictate app once to set the server host", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val dp = { v: Int -> Theme.dp(this, v) }
        card.removeAllViews()
        card.setPadding(dp(28), dp(24), dp(28), dp(24))
        val statusView = TextView(this).apply {
            text = "● Recording… tap to stop"
            textSize = 16f
            setTextColor(Theme.onBackground)
        }
        card.addView(statusView)
        card.addView(TextView(this).apply {
            text = "Copied to clipboard, and inserted where you were typing if possible"
            textSize = 12f
            setTextColor(Theme.muted)
            setPadding(0, dp(6), 0, 0)
        })
        card.setOnClickListener { stopAndTranscribe(statusView) }

        audioRecorder.start()
    }

    // Recording itself stays modal (this overlay, tap to stop) -- but
    // everything past that point (the actual network transcription call,
    // then insertion) hands off to DictateTranscribeService and this
    // overlay closes immediately, rather than staying up showing a
    // progress bar through the whole wait. Asked for explicitly
    // 2026-09-20: "I want the recording to be modal, but after tapping
    // to stop recording, the transcription should be in the background"
    // -- replacing an earlier version of this screen that stayed open
    // showing live synthesis progress (real per-segment percentage from
    // the server, falling back to an elapsed-time indeterminate state)
    // through the whole transcription. That live-progress plumbing
    // (SttClient.transcribeStreaming, the server's /stt/stream) is still
    // there for anything that wants it later; this flow just no longer
    // needs it, since there's no screen left to show it on.
    //
    // Uses capturedTarget (grabbed in onCreate(), see its own doc) rather
    // than capturing fresh here -- confirmed live 2026-09-20 that by the
    // time "stop" is tapped, this overlay has long since taken window
    // focus for itself, so a capture attempt this late always came back
    // empty. Handing the (hopefully non-null) node to the service now,
    // instead of letting it look up "whatever's focused" once
    // transcription finishes, is what's supposed to make it safe to
    // switch apps, or start another dictation, the instant this
    // returns -- DictateTranscribeService still falls back to a fresh
    // lookup at completion time if this is null, which is why dictation
    // has kept working even while this capture-at-open path gets sorted
    // out.
    private fun stopAndTranscribe(statusView: TextView) {
        if (stopped) return
        stopped = true
        val pcm = audioRecorder.stop()
        DictateTranscribeService.enqueue(this, pcm, capturedTarget)
        Toast.makeText(this, "Transcribing in background…", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onBackPressed() {
        if (audioRecorder.isRecording()) audioRecorder.stop()
        super.onBackPressed()
    }
}
