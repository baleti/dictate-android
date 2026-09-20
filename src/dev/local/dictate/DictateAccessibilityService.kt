package dev.local.dictate

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.util.Log
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

    /** Grabs a reference to whatever's focused right now, to insert into
     * LATER -- asked for explicitly 2026-09-20 ("recording should be
     * modal, but after tapping to stop, transcription should be in the
     * background"). AssistActivity calls this the instant "stop" is
     * tapped, while its own overlay is still up (so findFocusedEditable's
     * own-package skip still correctly excludes it), then hands the
     * result to DictateTranscribeService instead of re-discovering focus
     * once transcription finishes -- by then the user may have switched
     * away entirely, and a fresh lookup at that point would target
     * whatever's now on screen instead of what was actually intended. */
    fun captureTarget(): AccessibilityNodeInfo? = findFocusedEditable().also {
        Log.d("DictateInsert", "captureTarget: found=${it != null} pkg=${it?.packageName} editable=${it?.isEditable}")
    }

    /** Inserts `text` at the currently-focused field's current cursor
     * position (replacing any active selection), the same way a real
     * keyboard commit would -- not a blind whole-field overwrite, so
     * whatever the user already typed before/after the cursor survives.
     * Returns whether an editable focused field was actually found and
     * accepted the change. Does a fresh focus lookup -- see insertInto()
     * for inserting into a node captured earlier instead. */
    fun insertText(text: String): Boolean {
        val focused = findFocusedEditable() ?: return false
        return insertInto(focused, text)
    }

    /** Same as insertText(), but into a specific node (e.g. one
     * captureTarget() returned earlier) instead of whatever's focused
     * right now. refresh()es it first since real time may have passed
     * since it was captured; if the node is no longer valid at all (the
     * screen it belonged to is gone), falls back to a fresh focus lookup
     * rather than silently failing outright. */
    fun insertInto(node: AccessibilityNodeInfo, text: String): Boolean {
        val refreshOk = node.refresh()
        Log.d("DictateInsert", "insertInto: refresh=$refreshOk")
        val focused = if (refreshOk) node else {
            val fresh = findFocusedEditable()
            Log.d("DictateInsert", "insertInto: refresh failed, fresh lookup found=${fresh != null}")
            fresh ?: return false
        }
        if (!focused.isEditable) {
            Log.d("DictateInsert", "insertInto: node not editable, pkg=${focused.packageName}")
            return false
        }
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
        Log.d("DictateInsert", "insertInto: ACTION_SET_TEXT ok=$ok pkg=${focused.packageName}")
        if (ok) {
            // Leaves the cursor right after the inserted text, not at the
            // very end of the field -- matches where typing it normally
            // would have left it.
            val newCursor = start + text.length
            val selArgs = Bundle()
            selArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursor)
            selArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursor)
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
            maybeAutoSend(focused)
            return true
        }
        // ACTION_SET_TEXT isn't implemented by every editable view --
        // confirmed live 2026-09-12 against claude-agents-android's own
        // message EditText. ACTION_PASTE is a much more universally
        // supported accessibility action, and the caller has always
        // already put `text` on the clipboard by this point (the
        // guaranteed fallback path), so this just triggers the same
        // "paste" a long-press context menu would.
        val pasted = focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Log.d("DictateInsert", "insertInto: ACTION_PASTE pasted=$pasted")
        if (pasted) maybeAutoSend(focused)
        return pasted
    }

    /** Auto-submits after inserting into claude-agents-android's own chat
     * input specifically -- asked for explicitly 2026-09-20 ("when Claude
     * Agents app is in focus with the input field in focus, could you
     * after the transcription also press the send button... it can take
     * a while, so it's good if you can do it automatically"). Deliberately
     * scoped to that one app's package (checked against the focused
     * field itself, not the accessibility service's own idea of "active
     * window") so dictating into any other app never gets an unexpected
     * auto-submit. Searches within the SAME window the field came from
     * (focused.window?.root), not rootInActiveWindow, for the same
     * staleness reason findFocusedEditable() avoids it. */
    private fun maybeAutoSend(focused: AccessibilityNodeInfo) {
        if (focused.packageName != "dev.local.claudeagents") return
        val root = focused.window?.root ?: return
        findClickableNodeByText(root, "Send")?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findClickableNodeByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.isClickable && node.text?.toString() == text) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findClickableNodeByText(child, text)?.let { return it }
        }
        return null
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
        // Loosened fallback for claude-agents-android specifically --
        // asked for explicitly 2026-09-20 ("the input field doesn't have
        // to be in focus... focus could be on anything else and it still
        // after transcribing could automatically send it to the chat").
        // Requiring literal input focus meant scrolling the chat or
        // tapping a message bubble (either of which drops IME focus
        // without meaning "don't insert here") made insertion fail
        // outright, not just skip auto-send. That app's chat screen has
        // exactly one message EditText, so finding ANY editable node
        // there -- not requiring focus at all -- is safe, and keeps
        // maybeAutoSend() working too since it keys off this same node's
        // packageName.
        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName != "dev.local.claudeagents") continue
            findAnyEditable(root)?.let { return it }
        }
        return null
    }

    private fun findAnyEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findAnyEditable(child)?.let { return it }
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
