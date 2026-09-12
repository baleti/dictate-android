package dev.local.dictate

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Injects dictated text into whichever field is focused in the
 * foreground app, system-wide -- not tied to any one app's own text
 * box. Must be enabled by hand in Settings > Accessibility (required for
 * every accessibility service on every Android device, no way around
 * it); DictateTileService checks `instance` before relying on it and
 * falls back to "it's on your clipboard, paste it in" if this hasn't
 * been enabled, or if the focused field refuses the injection (some
 * password fields and custom text renderers do).
 */
class DictateAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: DictateAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** Inserts `text` at the focused field's current cursor position
     * (replacing any active selection), the same way a real keyboard
     * commit would -- not a blind whole-field overwrite, so whatever the
     * user already typed before/after the cursor survives. Returns
     * whether an editable focused field was actually found and accepted
     * the change. */
    fun insertText(text: String): Boolean {
        val focused = findFocusedEditable() ?: return false
        if (!focused.isEditable) return false
        // Some fields (confirmed live 2026-09-12 against Vanadium's own
        // URL bar) report their placeholder/hint as `.text` while empty,
        // not a real empty string -- treating that as existing content
        // would append the dictated text right after the placeholder's
        // own wording instead of replacing it.
        val existing = if (focused.isShowingHintText) "" else (focused.text?.toString() ?: "")
        val rawStart = focused.textSelectionStart
        val rawEnd = focused.textSelectionEnd
        val selStart = if (rawStart >= 0) rawStart else existing.length
        val selEnd = if (rawEnd >= 0) rawEnd else existing.length
        val start = minOf(selStart, selEnd).coerceIn(0, existing.length)
        val end = maxOf(selStart, selEnd).coerceIn(0, existing.length)
        val newText = existing.substring(0, start) + text + existing.substring(end)

        val setArgs = Bundle()
        setArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        val ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)
        if (ok) {
            // Leaves the cursor right after the inserted text, not at the
            // very end of the field -- matches where typing it normally
            // would have left it.
            val newCursor = start + text.length
            val selArgs = Bundle()
            selArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursor)
            selArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursor)
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
            return true
        }
        // ACTION_SET_TEXT isn't implemented by every editable view --
        // confirmed live 2026-09-12 against claude-agents-android's own
        // message EditText. ACTION_PASTE is a much more universally
        // supported accessibility action, and the caller has always
        // already put `text` on the clipboard by this point (the
        // guaranteed fallback path), so this just triggers the same
        // "paste" a long-press context menu would.
        return focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    /** Finds the currently focused editable field, wherever it actually
     * is. `rootInActiveWindow` alone came back empty in practice
     * (confirmed live 2026-09-12, immediately after the assist overlay
     * closed): Android's notion of the "active" window lags behind
     * reality for a stretch right after a separate-task overlay
     * activity finishes, so relying on it (even with retries) still
     * missed the real target window. Instead this walks every window
     * currently on screen -- `windows` needs
     * `flagRetrieveInteractiveWindows` (already set in
     * accessibility_service_config.xml) -- and returns whichever one
     * still has a genuinely focused editable node, which each window
     * keeps independently of which one the system currently considers
     * "active" for input routing. This app's own (closing) window is
     * skipped by package name so a stale reference to it is never
     * returned instead of the real target. */
    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { root ->
            (root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditableFocused(root))?.let { return it }
        }
        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName == packageName) continue
            (root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditableFocused(root))?.let { return it }
        }
        return null
    }

    /** Manual fallback for when FOCUS_INPUT finds nothing: walks the
     * whole node tree for any editable node the view system still marks
     * as view-focused (View.isFocused(), independent of whether the IME
     * currently considers anything "input-focused"). Depth-first, first
     * match wins -- a screen only ever has one real focused editor. */
    private fun findEditableFocused(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isFocused) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findEditableFocused(child)?.let { return it }
        }
        return null
    }
}
