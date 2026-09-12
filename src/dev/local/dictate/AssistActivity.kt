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
import android.widget.ProgressBar
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
 * Injection timing matters here: DictateAccessibilityService.insertText()
 * acts on whichever window is currently focused, which while THIS
 * activity is on screen is this activity itself, not the app you were
 * using. finish() is called BEFORE inserting, with a short delay after
 * to let window focus actually return to the previous app first --
 * calling insertText() synchronously right after finish() would still
 * often see this activity's own (already-gone) window as focused.
 */
class AssistActivity : Activity() {
    private val audioRecorder = AudioRecorder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var card: LinearLayout
    private var stopped = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    // Real per-segment progress from the server (server.py's
    // /stt/stream, WhisperEngine.transcribe_streaming), not a client-side
    // time estimate -- asked for explicitly 2026-09-12 ("can we make
    // server report progress to make it more reliable"). Whisper decodes
    // and yields segments incrementally rather than all at once, so each
    // one's own end-of-segment timestamp over the clip's total duration
    // is an honest percentage, not a guess.
    //
    // Benchmarked server-side the same day: a short dictated clip almost
    // always decodes as ONE segment (Whisper's per-call cost is ~fixed
    // regardless of clip length under ~30s), so that real percentage
    // just sits at 0 for the whole wait, then jumps to 100 -- asked
    // again ("is there nothing server can report before?"). While
    // progress is still 0 the bar goes indeterminate and the stage text
    // shows elapsed processing time instead (honest, not a guessed
    // fraction); once a real segment lands it switches back to a
    // determinate percentage.
    private var progressBar: ProgressBar? = null
    private var stageView: TextView? = null

    private fun stopAndTranscribe(statusView: TextView) {
        if (stopped) return
        stopped = true
        val pcm = audioRecorder.stop()

        val dp = { v: Int -> Theme.dp(this, v) }
        card.removeAllViews()
        card.setOnClickListener(null)
        card.setPadding(dp(28), dp(24), dp(28), dp(24))
        card.addView(TextView(this).apply {
            text = "Transcribing…"
            textSize = 16f
            setTextColor(Theme.onBackground)
        })
        // Tinted to the app's own palette rather than the system accent
        // color -- the Material parent theme (see styles.xml) already
        // gets this a modern thin animated bar for free, this just makes
        // it match everything else drawn in this card.
        val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = false
            progressTintList = android.content.res.ColorStateList.valueOf(Theme.primary)
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Theme.primary)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Theme.primary and 0x33FFFFFF.toInt())
        }
        val pbParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4))
        pbParams.topMargin = dp(14)
        card.addView(pb, pbParams)
        progressBar = pb
        // Starts as "Uploading audio…" -- there's real latency before
        // the server's first segment/progress message can possibly
        // arrive (network round-trip, then decoding enough of the clip
        // to finish even one segment), and that gap shouldn't silently
        // look like nothing is happening.
        val stage = TextView(this).apply {
            text = "Uploading audio…"
            textSize = 11f
            setTextColor(Theme.muted)
            setPadding(0, dp(4), 0, 0)
        }
        card.addView(stage)
        stageView = stage

        Thread {
            SttClient.transcribeStreaming(
                context = this,
                pcm16 = pcm,
                onProgress = { progress, textSoFar, elapsedSeconds ->
                    mainHandler.post {
                        if (progress <= 0f && textSoFar.isBlank()) {
                            progressBar?.isIndeterminate = true
                            stageView?.text = "Transcribing… (${"%.1f".format(elapsedSeconds)}s)"
                        } else {
                            progressBar?.isIndeterminate = false
                            progressBar?.progress = (progress * 100).toInt()
                            stageView?.text = if (textSoFar.isBlank()) "Transcribing…" else "Transcribing… “$textSoFar”"
                        }
                    }
                },
                onDone = { text ->
                    mainHandler.post { finishWithResult(text) }
                },
                onError = { message ->
                    mainHandler.post {
                        Toast.makeText(applicationContext, "Transcription failed: $message", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                },
            )
        }.apply { isDaemon = true; name = "AssistTranscribe"; start() }
    }

    private fun finishWithResult(text: String) {
        progressBar?.isIndeterminate = false
        progressBar?.progress = 100
        if (text.isBlank()) {
            Toast.makeText(applicationContext, "Heard nothing", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Dictated text", text))
        finish()
        // See this class's own doc -- insertText() needs the PREVIOUS
        // app's window to actually be focused again first, which doesn't
        // happen synchronously with finish(). A single fixed delay
        // still missed in practice (confirmed live 2026-09-12), so this
        // retries a few times instead of gambling on one guess being
        // long enough -- cheap and harmless once it succeeds (later
        // attempts just never fire, removeCallbacks isn't even needed
        // since each attempt bails out immediately if a previous one
        // already won).
        var attempt = 0
        lateinit var tryInsert: () -> Unit
        tryInsert = {
            val injected = DictateAccessibilityService.instance?.insertText(text) ?: false
            attempt++
            if (injected || attempt >= 5) {
                Toast.makeText(
                    applicationContext,
                    if (injected) "Inserted (and copied)" else "Copied to clipboard - paste it in",
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                mainHandler.postDelayed(tryInsert, 250)
            }
        }
        mainHandler.postDelayed(tryInsert, 300)
    }

    override fun onBackPressed() {
        if (audioRecorder.isRecording()) audioRecorder.stop()
        super.onBackPressed()
    }
}
