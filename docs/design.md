# Design notes

Dictate is two things wearing one app: a voice-dictation tool, and a small
system-wide **launcher** for one-tap custom commands, both reachable
without any persistent on-screen element (no floating bubble, no
permanently-visible button). The launcher is the more general of the two —
dictation is just its first, most-used entry. Dictation used to also be
duplicated inside claude-agents-android and newsdigest-android as their
own mic buttons; both were removed (2026-09-12) so this is the only place
it lives now.

## Setup checklist (do this once per install)

This app's setup is unusually easy to get 90% right and still have nothing
work, because two of the steps below produce *no error of any kind* when
skipped — the app just silently degrades to "copied to clipboard, paste it
in yourself." Confirmed live 2026-09-12: dictation appeared completely
broken ("just nothing happens") for a while, and the actual cause was
step 2 never having been done, not any code bug.

1. **Build and install.** `bash build.sh` from this repo, then
   `adb install -r build/dictate-signed.apk`. `build.sh`'s default
   `ANDROID_JAR`/`KOTLIN_STDLIB` paths point at this host's actual SDK
   locations (`~/.local/share/android-sdk/...`, `/usr/share/kotlin/...`)
   as of 2026-09-12 — they used to default to a stale Termux-era
   `~/mediabridge/...`/`$PREFIX/...` path left over from before these
   apps moved to host3-native builds, which fails with a confusing
   `NoSuchFileException` deep inside `d8`/`aapt2` rather than a clear
   "file not found."
2. **Enable the accessibility service.** Settings, Accessibility,
   "Dictate text injection", turn it on. This is the one Android
   genuinely does not let any app self-enable (a real OS restriction, not
   a bug here) — every accessibility service on every Android device
   needs this manual step. Nothing in the app can detect or warn you that
   this is missing beyond falling back to clipboard-only, so if
   dictation seems to do nothing, check this first:
   `adb shell dumpsys accessibility | grep -i "enabled services"` — an
   empty `{}` means it's off. It can also be flipped on directly without
   touching the Settings UI at all:
   `adb shell settings put secure enabled_accessibility_services dev.local.dictate/dev.local.dictate.DictateAccessibilityService`
   followed by `adb shell settings put secure accessibility_enabled 1`.
   Confirmed to survive `adb install -r` reinstalls (it's tracked by
   `Settings.Secure`, not the app's own data).
3. **Turn on the assist gesture.** Settings, System, Gestures, Navigation
   mode, gear icon next to "Gesture navigation", "Swipe to invoke
   assistant". See the section below for why this, specifically, is the
   trigger.
4. **Open the app once** to grant `RECORD_AUDIO` and set the server
   host/port — `AssistActivity` checks both and shows a toast telling you
   to do this rather than silently failing if skipped.
5. **Server side**: the STT server (`newsdigest-android/server/server.py`,
   `newsdigest-server.service`) needs to actually be running and its
   models loaded — `curl -s -H "X-Peer-Agent: 1" http://10.10.0.2:8792/status`
   should show `"whisper-medium-cpu": "ready"` (or whichever model
   `Settings.getSttModel` points at) before dictation will produce text.

## Why this exists

Voice dictation started as a feature of a single chat app (claude-agents-
android), then it became clear the actual want was "let me dictate into
*any* text field, anywhere" — not something worth tying to one app. Once
that meant a standalone app anyway, adding a couple of other one-tap
actions that used to live as Termux `.shortcuts` scripts (see below) cost
almost nothing extra, and meant retiring a Termux dependency entirely for
those.

## The trigger: Android's own assistant-invocation gesture

No custom overlay, no accessibility-gesture-detection hack. Dictate
registers itself as the device's **Digital Assistant** (the
`android.app.role.ASSISTANT` role), which two built-in Android mechanisms
already know how to invoke:

- **Swipe from a bottom screen corner** (gesture navigation's own "invoke
  assistant" gesture — Settings, System, Gestures, Navigation mode, gear
  icon next to Gesture navigation, "Swipe to invoke assistant"). This is
  the one actually in use.
- **Long-press power button**, if `Settings.Global.power_button_long_press`
  is set to `5` (assistant mode) rather than `1` (global actions / the
  power menu). Left at `1` on this device deliberately, since long-press-
  power already had an unrelated job (shutdown/restart/lock) the user
  didn't want to give up.

Both are zero-footprint: nothing drawn on screen until actually invoked,
no notification, no persistent service icon.

### The two settings this touches, and why they're separate

Android splits "who is the assistant" across two things that don't always
stay in sync:

- `Settings.Secure.assistant` — a legacy, mostly-vestigial setting.
- `RoleManager`'s `android.app.role.ASSISTANT` role holder — the actual
  modern source of truth. `adb shell cmd role get-role-holders
  android.app.role.ASSISTANT` / `add-role-holder` / `remove-role-holder`
  is how this gets managed on a GrapheneOS build with no Settings UI
  exposing it at all (confirmed live 2026-09-12: no "Default apps" /
  "Digital assistant app" screen exists in this build's Settings app).

Installing an app with an `ACTION_ASSIST` intent-filter can cause
`RoleManager`'s fallback mechanism to auto-assign that role to it *and*
silently flip `power_button_long_press` from `1` to `5` as a side effect
— confirmed live the same day. If long-press-power stops showing your
power menu after installing something like this, check
`adb shell settings get global power_button_long_press` before assuming
anything else broke.

## AssistActivity: the launcher itself

A translucent, no-title-bar activity (`res/values/styles.xml`'s
`TranslucentOverlay` — not the platform's bundled
`Theme.Translucent.NoTitleBar`, which didn't actually render translucently
on this OS build) that draws a small centered card over whatever app was
in the foreground, dimmed behind it rather than replaced by it.

Two things had to be gotten right for that "overlay on top of the real
app" feel to actually work:

1. **`android:taskAffinity=""` + `android:launchMode="singleInstance"`.**
   Without these, AssistActivity shared a task with `MainActivity` (both
   default to the app's own task affinity) — so if `MainActivity` had
   ever been opened and left resident (e.g. for setup), invoking the
   gesture resumed *that* task first, showing the settings screen dimmed
   behind the card instead of whatever app was actually in front.
   Confirmed live 2026-09-12. An empty task affinity plus singleInstance
   guarantees this activity never merges with any other task.
2. **A real translucent theme**, not just a semi-transparent scrim drawn
   in code. The card's dim background (`root.setBackgroundColor(...)` in
   `AssistActivity.kt`) only shows the previous app through it if the
   *window itself* is translucent — hence the custom style rather than
   depending on a possibly-version-specific platform one.

`TranslucentOverlay`'s parent is `android:Theme.Material.NoActionBar`, not
the older `android:Theme.Black.NoTitleBar` it started on — that first
choice was only ever about fixing translucency (above), but it also meant
every default-styled widget (the transcription `ProgressBar`, notably)
rendered with pre-Material chrome instead of the thin animated bar
Material gives for free. Swapped 2026-09-12 ("progress bar looks really
ugly, make it look more modern"); translucency itself comes from the
explicit `windowIsTranslucent`/`windowBackground` overrides, which don't
depend on which base theme they sit on top of.

The card shows a small extensible menu (`actions` in `AssistActivity.kt`),
each entry a plain label plus a callback that rebuilds the card's content
for whatever comes next — tapping "Dictate" swaps it into a recording
status view, tapping "Spawn Claude session" swaps it into a submenu.
Adding a new command later is one more entry in that list, not a new
architecture. There's no "Launcher" header text above the menu (removed
2026-09-12 — wasn't needed).

Icons in that list are plain black-silhouette PNGs (`res/drawable/`,
tinted at load time via `ColorFilter`, same pattern as claude-agents-
android's and newsdigest-android's player controls) — asked for
explicitly to keep this plain and simple rather than colorful platform
emoji.

## Text injection: DictateAccessibilityService

Dictated text always goes to the clipboard first (the guaranteed path —
"in case something goes wrong"), then `DictateAccessibilityService`
attempts to insert it at the focused field's actual cursor position via
`ACTION_SET_TEXT` (splicing into the existing text/selection, not a blind
overwrite), falling back to `ACTION_PASTE` if the target view doesn't
implement `ACTION_SET_TEXT` — confirmed live that a plain `EditText` in
claude-agents-android needed the paste fallback specifically.

Injection has to happen *after* `AssistActivity.finish()`, with a short
retry loop (up to 5 attempts, 250ms apart) — `insertText()` acts on
whichever window is currently focused, which while the overlay is still
on screen is the overlay itself, not the app underneath.

**Finding the real target field is the hard part**, and went through two
rounds of fixes:

1. `rootInActiveWindow.findFocus(FOCUS_INPUT)` alone came back empty in
   practice — a manual tree-walk fallback (`findEditableFocused()`,
   depth-first search for any node with `isEditable && isFocused`) was
   added for when the IME-level focus concept finds nothing even though
   a field still visually shows a cursor.
2. Even that combination still failed in live testing after the overlay
   closed. Root cause, isolated 2026-09-12 by calling `insertText()`
   directly via a temporary `adb shell am broadcast` hook (bypassing the
   whole mic/websocket/AssistActivity path to test this one function in
   isolation): `rootInActiveWindow` itself lags behind reality for a
   stretch right after a separate-task overlay activity finishes, so
   even with retries it kept pointing at the wrong (or no) window.
   `findFocusedEditable()` now falls back further, to walking *every*
   window currently on screen (`AccessibilityService.windows`, which
   needs `flagRetrieveInteractiveWindows` in
   `accessibility_service_config.xml`) and returning whichever one still
   has a genuinely focused editable node — each window tracks its own
   focus independently of which one the system currently calls "active."
   This app's own window is skipped by package name so a stale reference
   to it is never picked over the real target.

The same isolated testing also caught a second, unrelated bug: some
fields (confirmed against Vanadium's URL bar) report their *hint/
placeholder* text as `.text` while genuinely empty, not a real empty
string. Treating that as existing content appended the dictated text
right after the placeholder's own wording (e.g. landed as `"Search
DuckDuckGo or type URLhello world"`). Fixed by checking
`focused.isShowingHintText` and treating those fields as truly empty.

**Must be enabled by hand in Settings, Accessibility** — required for
every accessibility service on every Android device, no way around it,
and there is no in-app way to detect or prompt for this being missing
beyond the clipboard-only fallback below. This is easy to forget and
produces zero errors when skipped (see the Setup checklist at the top of
this doc) — check `adb shell dumpsys accessibility | grep -i "enabled
services"` if dictation ever seems to silently do nothing.
`DictateTileService`/`AssistActivity` both check
`DictateAccessibilityService.instance` before relying on it and fall back
to "it's on your clipboard" if it's null (not enabled) or the focused
field simply refuses the action.

## Transcription progress: what the server can and can't report

`/stt/stream` (a WebSocket on the shared server,
`newsdigest-android/server/server.py`) reports real per-segment progress,
not a client-side time estimate — `WhisperEngine.transcribe_streaming()`
calls back as each segment is actually decoded, since `model.transcribe()`
returns a lazy generator rather than the whole result at once.

That real signal turned out to be coarser than it sounds. Benchmarked
directly against the model on 2026-09-12: one `model.transcribe()` call on
this CPU costs a near-fixed **~4-8 seconds regardless of how short the
audio is**, because Whisper's encoder always runs a full internal ~30s
window no matter the input length. A first attempt at fixing this by
chunking the incoming audio into fixed ~4s windows (to force more
frequent updates) made things *worse*, not better — it just paid that
fixed ~4-8s cost once per chunk instead of once total, since chunking
doesn't reduce the per-call overhead at all. Reverted.

Net effect: a typical short dictated sentence almost always produces
exactly **one** real segment, so the true completion percentage just sits
at 0 for the entire wait and then jumps to 100. There's no way to get a
real fractional percentage out of Whisper inside that single unavoidable
compute window — but the server can still honestly report that it's
alive: `/stt/stream` sends a heartbeat every ~0.7s with elapsed processing
time (not a guessed fraction) while waiting on the first real segment.
`AssistActivity` shows this as an indeterminate (animated) progress bar
with "Transcribing… (N.Ns)" text, switching to a real determinate
percentage the moment an actual segment lands (which happens more than
once for clips spanning multiple ~30s windows).

## Commands migrated off Termux's `.shortcuts`

Three of the old Termux Widget shortcuts (`~/.shortcuts/` on the phone)
got reimplemented natively here instead, retiring that Termux dependency
for these specific actions:

- **`md-clip-plain`** → `MarkdownStrip.kt` + the "Strip markdown
  (clipboard)" menu entry. Same regex passes, same order, operating on
  the real Android clipboard directly instead of shelling out to
  `termux-clipboard-get`/`-set`.
- **`claude*-*` (12 scripts: claude/claude2/claude3 × host3/host6/hub/
  wsl)** → `PeerAgentClient.kt` + the "Spawn Claude session" submenu.
  Same request each old script made (`POST http://<host>:8787/run/
  <account>` with the `X-Peer-Agent` header), just one submenu instead of
  12 separate Termux:Widget icons.

**Not migrated**: `restart_sshd` (restarts Termux's own sshd service) —
this needs to run *inside* Termux's own process to control its own
service supervisor; there's no way for a separate app like Dictate to
trigger that the same way. Left as a Termux-only shortcut if still
needed.

## Read Aloud: the second app hanging off this menu

`actions` gained a second entry (2026-09-12): "Read Aloud", which reads
whatever app was in the foreground aloud. It lives in its own separate
repo, [read-aloud-android](https://github.com/baleti/read-aloud-android)
— that project's scope (a per-app accessibility profile system, gesture-
driving, its own TTS pipeline) is wide enough to justify a repo of its
own, this app just needed to stay the trigger since it already owns the
Digital Assistant slot.

`readAloud()` sends an **explicit** broadcast
(`Intent(ACTION).setPackage("dev.local.readaloud")`) rather than a plain
`sendBroadcast(Intent(ACTION))` — confirmed live that the implicit form
never reaches a manifest-declared `<receiver>` in another app at all
(Android 8+ restricts implicit broadcasts to static receivers), with no
error or log to point at why. Unlike `startDictating()`'s
`finishWithResult()`, this needs no retry-delay loop of its own after
`finish()` — read-aloud-android's own accessibility service already
retries internally while waiting for window focus to return here, so one
short fixed delay before sending the broadcast is enough.

## Quick Settings tile

`DictateTileService` is the original, still-present trigger from before
the assist-gesture path existed — tap the tile from the pulled-down
shade to start/stop recording directly, no menu. Kept as an alternative
for anyone who prefers a tile over the gesture; the gesture-based
`AssistActivity` menu is the recommended path since it needs no shade
pull-down and scales to more than one command.
