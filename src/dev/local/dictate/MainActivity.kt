package dev.local.dictate

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Onboarding + settings, not a dictation UI of its own -- the actual
 * dictation happens system-wide via DictateTileService (the trigger) and
 * DictateAccessibilityService (the injector). This screen exists to
 * grant RECORD_AUDIO once, configure the shared server's host/port, and
 * walk through the two manual steps Android requires for those (adding
 * the Quick Settings tile, enabling the Accessibility Service) -- neither
 * can be done programmatically, on this or any Android device.
 */
class MainActivity : Activity() {
    private lateinit var accessibilityStatus: TextView

    private fun watch(field: EditText, onChanged: (String) -> Unit) {
        field.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = onChanged(s?.toString() ?: "")
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        val pad = Theme.dp(this, 20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Theme.bg)
        }
        val dp = { v: Int -> Theme.dp(this, v) }
        fun spacer(v: Int) = TextView(this).apply { setPadding(0, dp(v), 0, 0) }
        fun sectionLabel(label: String) = TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(Theme.onSurfaceVariant)
        }

        root.addView(TextView(this).apply {
            text = "Dictate"
            textSize = 22f
            setTextColor(Theme.onBackground)
        })
        root.addView(TextView(this).apply {
            text = "System-wide voice dictation. Once set up: pull down Quick " +
                "Settings, tap the Dictate tile to start recording, tap it " +
                "again to stop -- the transcribed text is inserted into " +
                "whatever field is focused, and always copied to the " +
                "clipboard too as a fallback."
            textSize = 13f
            setTextColor(Theme.onSurfaceVariant)
            setPadding(0, dp(8), 0, dp(20))
        })

        val hostField = EditText(this).apply {
            hint = "Host (e.g. your WireGuard peer address)"
            setText(Settings.getHost(this@MainActivity))
            Theme.styleEditText(this, this@MainActivity)
        }
        root.addView(hostField)
        watch(hostField) { Settings.setHost(this, it.trim()) }
        root.addView(spacer(8))

        val portField = EditText(this).apply {
            hint = "Port"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Settings.getPort(this@MainActivity).toString())
            Theme.styleEditText(this, this@MainActivity)
        }
        root.addView(portField)
        watch(portField) { it.trim().toIntOrNull()?.let { port -> Settings.setPort(this, port) } }

        root.addView(spacer(24))
        root.addView(sectionLabel("Setup steps"))
        root.addView(spacer(8))

        root.addView(TextView(this).apply {
            text = "1. Add the tile: pull down Quick Settings twice, tap the " +
                "pencil/edit icon, drag \"Dictate\" into your active tiles."
            textSize = 13f
            setTextColor(Theme.onSurfaceVariant)
            setPadding(0, 0, 0, dp(12))
        })

        root.addView(TextView(this).apply {
            text = "2. Enable text injection (optional but recommended -- " +
                "without it, dictated text only goes to the clipboard):"
            textSize = 13f
            setTextColor(Theme.onSurfaceVariant)
            setPadding(0, 0, 0, dp(8))
        })
        val accessibilityButton = Button(this).apply {
            text = "Open Accessibility Settings"
            setTextColor(Theme.onPrimary)
            Theme.stylePrimaryButton(this, this@MainActivity)
            setOnClickListener { startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        root.addView(accessibilityButton)

        accessibilityStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(Theme.muted)
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(accessibilityStatus)

        setContentView(ScrollView(this).apply { setBackgroundColor(Theme.bg); addView(root) })
    }

    override fun onResume() {
        super.onResume()
        // Only reflects reality while this process is alive (a static
        // instance the service sets on connect) -- good enough for a
        // status line the user checks right after flipping the toggle in
        // system Settings and coming back here, which is the only time
        // it actually matters.
        accessibilityStatus.text = if (DictateAccessibilityService.instance != null) {
            "Text injection: enabled"
        } else {
            "Text injection: not enabled yet (clipboard-only until it is)"
        }
    }
}
