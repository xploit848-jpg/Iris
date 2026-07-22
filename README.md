# IRIS — Phase 1 (Foundation)

## What's built
- `AndroidManifest.xml` — every permission from your Phase 1 list, plus the three
  service declarations (foreground, accessibility, notification listener).
- Dark theme (`themes.xml`, `colors.xml`) — minimal, no Material library dependency.
- `OrbView.kt` — custom-drawn animated orb with 4 states (idle/listening/thinking/speaking),
  each with its own pulse speed and color.
- `MainActivity.kt` — bottom-tab nav across Home / History / Memory / Settings, all
  swapped via plain `FrameLayout` + inflate (no Fragments, no androidx).
- `PermissionManager.kt` — single source of truth for all 19 permissions. Runtime
  permissions batch-request; the 5 special-access ones (accessibility, notification
  listener, overlay, battery optimization, all-files) each get their own Settings
  intent, since Android won't let you batch those.
- Settings page renders a live-status row per permission — tap "Grant" to trigger
  the right flow, tap "Grant All" to fire every runtime request + the first missing
  special one.
- `MemoryStore.kt` — file-backed (ndjson) conversation + memory log, internal
  storage only, no network calls anywhere in this class. Swaps to SQLite/Room later
  if the module needs querying beyond a flat log.
- Stub services for foreground/accessibility/notification-listener so the manifest
  resolves and the app compiles — their real logic is later modules (Stage 7, 9, 10
  in your roadmap).

## Known constraints baked in
- **No lambdas or anonymous classes anywhere** — every listener is a named class
  (see `TabClickListener`, `OrbPulseUpdateListener`, etc.) because D8 in this
  toolchain crashes on JDK21-compiled lambdas/anonymous classes.
- **No androidx/Material** — `PermissionManager` and `MainActivity` use plain
  framework APIs (`checkSelfPermission`, `requestPermissions`, `ListView` +
  `ArrayAdapter`) since there's no Gradle here to pull in androidx jars. If you
  want Material components later, that's the point where Gradle becomes worth
  the pain.

## Build
Edit the SDK paths at the top of `build.sh` to match whatever `android-sdk` path
you're already using for AetherCompanion, then:

```
chmod +x build.sh
./build.sh
```

Output: `build/iris-debug.apk`.

## Live voice mode

IRIS can use the OpenAI Realtime API for one continuously open microphone
session with server-side voice activity detection. The permanent API key must
stay on the session server; it is never included in the APK.

Start the local token server from the repository root:

```
node --env-file=idk server/session-server.mjs
```

The Android debug build uses `http://localhost:8787/session` for a session
server running on the same Android device. For an emulator using a server on
the development computer, change it to `http://10.0.2.2:8787/session`; for a
physical device using a server on another computer, change
`REALTIME_SESSION_URL` in `app/build.gradle.kts` to the host computer's LAN
address and ensure the phone can reach port 8787. If the server is unavailable,
IRIS falls back to the existing Android speech recognizer.

## Not yet built (next modules, per your roadmap)
- Offline STT/TTS wiring into the orb states
- Accessibility service logic (tap/swipe/read screen)
- Notification listener logic (summarize, "who sent them?")
- Foreground service actually starting from Home page / boot
- llama.cpp JNI bridge (separate module — cross-compiling C++ in Termux)
