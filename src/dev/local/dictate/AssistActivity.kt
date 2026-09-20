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
 * Dictate's own recording UI, and now transcription too, stay modal --
 * this overlay stays open (showing a "Transcribing…" status) through the
 * whole stop -> transcribe -> insert sequence, closing only once that's
 * done (see stopAndTranscribe()'s own doc). The field being dictated into is
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
        // FLAG_NOT_FOCUSABLE (which implies FLAG_NOT_TOUCH_MODAL, kept
        // explicit anyway) keeps real window/accessibility focus on
        // whatever app is underneath for as long as this overlay is
        // shown -- without it, THIS window takes that focus the instant
        // it's shown (see capturedTarget's own doc), which used to only
        // matter for an instant back when transcription ran in the
        // background and this overlay closed right after "stop" was
        // tapped. Now that it stays open through the whole transcribe
        // (modal again, see stopAndTranscribe()'s own doc), that same
        // stolen focus lasted the whole wait -- confirmed live
        // 2026-09-20: captured node went stale (refresh=false) AND the
        // fresh-lookup fallback came up empty too by the time
        // transcription finished, because the real app never had focus
        // back to look up. Touches on the card itself (tap to stop, menu
        // rows) still work fine unfocused -- they're plain touch/click
        // listeners, not anything that needs key/IME focus.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)

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
        val elapsedView = TextView(this).apply {
            text = "Copied to clipboard, and inserted where you were typing if possible"
            textSize = 12f
            setTextColor(Theme.muted)
            setPadding(0, dp(6), 0, 0)
        }
        card.addView(elapsedView)
        card.setOnClickListener { stopAndTranscribe(statusView, elapsedView) }

        audioRecorder.start()
    }

    // Went modal -> background -> modal again, same day (2026-09-20).
    // Backgrounding transcription made sense while CPU whisper-medium
    // took several real seconds; once GPU whisper-medium (see Settings'
    // own doc) dropped that to ~0.5-0.8s end to end, background handoff
    // was solving a problem that had basically stopped existing, at the
    // cost of a slower-feeling flow (a toast instead of watching it
    // finish) -- undone: "get it back to being modal... just show the
    // progress of transcribing... I imagine this would be practically
    // immediate now... this would allow us to just press stop, have very
    // short popup and have it send immediately." DictateTranscribeService
    // (the background-queue version of this) is gone; its one useful
    // trick -- falling back to a fresh focused-node lookup if
    // capturedTarget is null -- is kept below.
    //
    // Uses capturedTarget (grabbed in onCreate(), see its own doc) rather
    // than capturing fresh here -- confirmed live 2026-09-20 that by the
    // time "stop" is tapped, this overlay has long since taken window
    // focus for itself, so a capture attempt this late always came back
    // empty.
    private fun stopAndTranscribe(statusView: TextView, elapsedView: TextView) {
        if (stopped) return
        stopped = true
        val pcm = audioRecorder.stop()
        statusView.text = "Transcribing…"
        val startNanos = System.nanoTime()
        val tick = object : Runnable {
            override fun run() {
                val elapsedSec = (System.nanoTime() - startNanos) / 1_000_000_000.0
                elapsedView.text = "%.1fs".format(elapsedSec)
                mainHandler.postDelayed(this, 100)
            }
        }
        mainHandler.post(tick)
        Thread {
            try {
                val text = SttClient.transcribe(this, pcm)
                mainHandler.post {
                    mainHandler.removeCallbacks(tick)
                    finishWithResult(text)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    mainHandler.removeCallbacks(tick)
                    Toast.makeText(this, "Transcription failed: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.apply { isDaemon = true; name = "DictateTranscribe"; start() }
    }

    private fun finishWithResult(text: String) {
        if (text.isBlank()) {
            Toast.makeText(this, "Heard nothing", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Dictated text", text))
        val service = DictateAccessibilityService.instance
        val injected = when {
            service == null -> false
            capturedTarget != null -> service.insertInto(capturedTarget!!, text)
            else -> service.insertText(text)
        }
        Toast.makeText(this, if (injected) "Inserted (and copied)" else "Copied to clipboard - paste it in", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onBackPressed() {
        if (audioRecorder.isRecording()) audioRecorder.stop()
        super.onBackPressed()
    }
}
