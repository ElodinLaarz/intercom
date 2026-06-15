# AGENTS.md

This is the canonical guide for agents working in this repository. Keep
`CLAUDE.md` as a pointer to this file so the rules do not fork.

## Project Rules

1. A milestone is done only when the scripted two-phone test passes on real
   hardware.
2. Do not call work "code-complete". Update `STATUS.md` with what was validated,
   where, when, and on which device pair.
3. PRs touching radio, audio, or native code include smoke output in the PR body.
4. Every constant and piece of session state has one owner. Cross-boundary copies
   are read-only snapshots fetched or generated from the owner.
5. Build known landmines into the implementation at the milestone where they are
   required; do not rediscover them from symptoms.
6. Keep all project code, issues, CI, and releases on Forgejo.

## Code Style

1. Prefer guard clauses and early returns over nested `if` / `else` blocks.
2. Do not keep an `else` after a branch that returns, throws, stops the current
   operation, or otherwise completes the decision.
3. Keep nesting shallow. If a block needs more than two levels, extract a helper,
   invert the condition, or turn the edge cases into guards.
4. Use flat `when` statements or sequential guards for multi-case validation.
5. Expression `if` / `else` is acceptable when it is just choosing a value, such
   as a label or a small immutable assignment, and it reads more clearly than a
   statement block.
6. Prefer names that describe the radio/session reality over UI wording. The UI
   says Host and Guest; lifecycle code should still make ownership and state
   transitions explicit.

## Boundaries

1. Compose UI sends Host/Guest intents and renders status; it owns no radio or
   link state.
2. Kotlin session code owns the state machine, single-threaded dispatch, and
   connection epoch lifecycle.
3. Kotlin `radio/` owns advertising, scanning, GATT, and L2CAP sockets.
4. Native audio code owns Oboe, ADPCM, jitter buffering, and DSP only; it does
   not own BLE or session state.
5. `proto/constants.h` is the source of protocol constants. Generated Kotlin may
   mirror it, but hand-written duplicate literals should not appear.
6. Audio callback threads communicate through SPSC rings and must not touch
   session state directly.

## Local Checks

Run the smallest check that covers the change, then widen the gate when radio,
audio, native, or generated protocol code is touched.

```powershell
java -jar C:\Users\caleb\AppData\Local\Temp\lint\ktlint.jar "app/src/**/*.kt" "!app/src/main/kotlin/com/elodin/intercom/proto/Proto.kt"
java -jar C:\Users\caleb\AppData\Local\Temp\lint\detekt.jar --input app/src --excludes "**/proto/Proto.kt" --build-upon-default-config --config config/detekt.yml
.\gradlew.bat --no-daemon testDebugUnitTest
.\gradlew.bat --no-daemon assembleDebug
```

Set `ANDROID_HOME` / `ANDROID_SDK_ROOT` to the local Android SDK if Gradle cannot
find it.

