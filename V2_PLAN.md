# Walkie-Talkie v2 — Implementation Plan

**Date:** 2026-06-12
**Status:** pre-kickoff. Companion doc: [POSTMORTEM.md](POSTMORTEM.md) (read it first; this plan exists because of it).

---

## 1. Product definition

**A private 1:1 intercom between two Android phones over Bluetooth LE.** One phone taps **Host**, the other taps **Join**, and from then on push-to-talk voice flows both ways — phone in pocket, screen off, wired or Bluetooth headset. Later, the pair can listen to shared media together while talking. Installed by sideload via Obtainium from Forgejo releases.

User stories:
1. I tap Host; my partner taps Join; within seconds we can talk by holding a button.
2. I lock my phone and put it in my pocket; PTT still works (from the notification or a headset button).
3. I connect my Shokz mid-conversation; audio moves to the headset without dying.
4. We walk out of range and back; the link recovers by itself within a few seconds.
5. (Later) One of us plays an album; we both hear it, and voice ducks the music.

**Non-goals (permanent, for v2):**
- More than 2 peers. No rooms, no frequency lists, no roster UI.
- Host handover (meaningless at 1:1 — when either side leaves, the link ends).
- Play Store (no listing, data-safety forms, IARC, or review gates).
- Sentry / analytics / crash reporting.
- iOS, desktop, mesh, internet relay.

## 2. Locked decisions (2026-06-12)

| Decision | Choice | Rationale |
|---|---|---|
| Code reuse | **True greenfield** — zero v1 code copied | Clean slate; only lessons, runbooks, and the landmine list (Appendix A) carry over |
| Stack | **Pure native Android**: Kotlin + Jetpack Compose + C++ (Oboe, ADPCM) | Eliminates the Dart↔native bridge that produced v1's duplicated-state bug class (POSTMORTEM RC3). BLE host side was already native in v1; this finishes the job |
| Voice codec *(amended 2026-06-14)* | **ADPCM (IMA/G.726)** — was Opus | 1:1 speech needs neither Opus's bitrate-scaling nor its CPU/integration weight. <1 ms codec delay, ~4:1, no vendored lib (deletes the libopus submodule, landmine #11). End-to-end latency stays BLE-bound (~50–120 ms one-way) regardless of codec. Trade-off: no in-band FEC, so loss-resilience (light redundancy / PLC) is designed into the voice path (M1), not bolted on after a symptom appears |
| Topology | **1:1 only** | No mixer, no relay, no mix-minus engine — deletes v1's hardest audio code outright |
| End features | Background/screen-off ✓ · BT headset ✓ · shared media ✓ · handover ✗ | Per scope decision; handover is moot at 1:1 |
| Distribution | **Obtainium/sideload only** | Forgejo release pipeline → signed APK; no Play compliance surface |
| Forge & CI | **Forgejo only**, runner upgraded to build Kotlin + native + APK | One forge, full CI, or the v1 split-brain wounds repeat |
| Workflow | **Hybrid, gated** | Core built interactively with on-device testing in the loop; agent swarms only behind green CI + hardware gates on validated paths |
| minSdk | **31** (Android 12) | Both test phones exceed it; `setCommunicationDevice` is API 31+ |
| Package id | New id (e.g. `com.elodin.intercom`) | Side-by-side with v1 installs during transition |
| Test devices | Pixel 10 Pro XL (Android 16) + moto g play 2024 (Android 14), via Tailscale adb | The known rig; moto is the OEM-quirk canary |

## 3. Cardinal rules

1. **Hardware gate.** A milestone is done when the scripted two-phone test passes on the real rig — never before. PRs touching radio/audio/native paste smoke output in the PR body.
2. **"Code-complete" is banned.** `STATUS.md` at repo root tracks, per subsystem: last on-device validation date, device pair, and exactly what passed. Updated at every gate.
3. **One owner per datum.** Every constant and every piece of session state lives in exactly one language/module. Cross-boundary copies are read-only snapshots fetched at runtime, never re-declared literals.
4. **Epoch lifecycle.** All per-link state (sequence filters, jitter buffer, codec instances) is constructed when a connection epoch begins and destroyed when it ends. There are no `reset()` methods — destruction is the reset. (v1 had two independent stale-watermark bugs; this rule deletes the class.)
5. **Build the landmine list in, don't rediscover it.** Appendix A items are applied at the milestone noted, as ordinary implementation work — not after the symptom appears.
6. **No hardening ahead of validation.** Edge-case/polish work (agent swarm or otherwise) only on paths that have passed a hardware gate.
7. **Loud failures.** Bootstrap script verifies submodules, NDK, and signing prerequisites and exits non-zero with a message. Build output is never piped through `tail`/`head`.
8. **One forge.** Everything — code, issues, CI, releases — on Forgejo. The GitHub repo is archived after v2 reaches M2.

## 4. Architecture

```
┌─────────────────────────────────────────────────┐
│ ui/        Compose. 2 screens. Renders StateFlow │
│            snapshots; sends intents. Owns nothing.│
├─────────────────────────────────────────────────┤
│ session/   Kotlin. ForegroundService + state     │
│            machine. SINGLE OWNER of link state.  │
│            Single-threaded dispatcher.           │
├──────────────────────┬──────────────────────────┤
│ radio/  Kotlin       │ audio/  C++ (JNI)         │
│ advertiser, scanner, │ Oboe in/out, ADPCM enc/dec│
│ GATT server+client,  │ jitter buffer, ring,      │
│ L2CAP socket, epochs │ telemetry counters        │
├──────────────────────┴──────────────────────────┤
│ proto/  Kotlin codec + C++ header. Constants     │
│         single-sourced (see 4.4).                │
└─────────────────────────────────────────────────┘
```

### 4.1 Roles and link lifecycle

Both phones can host or join; the asymmetry exists only during link-up.

- **Host:** advertise (with manufacturer-specific data for filterable scanning) → run GATT server → publish L2CAP PSM in a characteristic → accept one L2CAP connection → stop advertising.
- **Guest:** scan with MSD filter → GATT connect (request MTU 517, `requestConnectionPriority(HIGH)`) → read PSM → open L2CAP CoC.
- **Linked:** fully symmetric duplex voice over the L2CAP socket; control messages over GATT (or piggybacked on L2CAP — decide in M1 design, prefer whichever is simpler; v1 used GATT notify and it worked).

**Connection epoch:** a monotonically increasing integer bumped on every successful link-up. Every per-link object is owned by an `Epoch` scope object; disconnect tears the scope down. Reconnect = new epoch = fresh state everywhere, both layers, by construction.

**Reconnect:** on `GATT_DISCONNECTED` (any status), react immediately — no heartbeat-timeout waiting. Status 19 (peer terminated) is an expected event, not a retriable error. Reconnect ladder: 150/300/600/1000/1500 ms backoff, guest re-scans if the cached address fails twice (RPA rotation), give up to idle UI after ~30 s with a Rejoin button.

### 4.2 Voice path (1:1 — no mixer)

```
mic → Oboe input (Shared, mono) → ADPCM encode (IMA/G.726, 4:1; sample rate + frame size set at voice-path design)
    → seq+epoch header → L2CAP write
L2CAP read → header check (epoch must match, seq filter) → jitter buffer (drift-tolerant)
    → ADPCM decode → Oboe output (Shared, VoiceCommunication usage)
```

- PTT gates the **encode/send** side only; receive path always live while linked.
- Telemetry from day one: a `DIAG` log line every 2 s with txSeq, rxSeq, queue depth, jitter depth, underruns — the smoke script greps these.
- ADPCM is fixed-rate (no bitrate ladder, no complexity knob), so there is **nothing to make adaptive** — sidestepping v1's adaptation-loop quality bug by construction. At ~4:1 the link has ample headroom (≈64 kbps for 16 kHz mono incl. overhead vs ~700+ kbps available on 2M PHY). ADPCM carries **no in-band FEC**, so packet-loss handling is a transport concern: a light redundancy / PLC scheme is part of the voice-path design (M1), not an afterthought.

### 4.3 Audio platform setup (order matters — v1 receipts in Appendix A)

1. `AudioManager.MODE_IN_COMMUNICATION` + `setCommunicationDevice(...)` **before** any stream opens.
2. Output stream: `Usage::VoiceCommunication`, `ContentType::Speech`, `SharingMode::Shared`, `PerformanceMode::None`.
3. Input stream: `Shared`; default `VoiceRecognition` preset (v1-validated); switch to `VoiceCommunication` preset only if echo/AGC problems appear on hardware.
4. Foreground service with `foregroundServiceType="microphone"` + active MediaSession + audio focus — without the session, Android 11+ silently suppresses background mic.
5. Every stream restart path is a bounded backoff ladder; never a one-shot immediate reopen.

### 4.4 Constants single-sourcing

All wire/codec constants (sample rate, frame ms, bitrate, message ids, MSD bytes, PSM characteristic UUID) live in `proto/constants.h`. Kotlin reads them through a generated `Proto.kt` (small codegen step in the build, or JNI getters — pick at M0, codegen preferred so they're compile-time visible). CI runs a checker that greps the Kotlin tree for re-declared literal values of those constants and fails on a hit.

### 4.5 Security

L2CAP and GATT use the **secure** (encrypted, bonded) variants. Bonding is a one-time system dialog per phone pair — acceptable friction for a private 1:1 tool, and it gives link-layer encryption for free. During M1 the insecure variants may be used to reduce iteration friction; the switch to secure is an M3 gate item, not optional.

## 5. Milestones

Estimates assume v1's part-time pace. Each gate is run by `tools/smoke` against both rig phones; gate output is pasted into the milestone-closing PR.

### M0 — Infrastructure first (~3–5 days)
The rig before the app. Deliverables:
- New Forgejo repo, new package id, bootstrap script (checks JDK/NDK/submodules, fails loud).
- **Runner image upgraded**: JDK 17 + Android SDK + NDK + cmake. This is the riskiest infra item — do it first. Fallback while blocked: local builds + manual gate, timeboxed to one week before reconsidering.
- CI on every PR: ktlint/detekt → Kotlin unit tests → **native host-built C++ tests** → `assembleDebug` → APK artifact. CI checks out and echoes the **PR head sha** (v1's Forgejo CI tested `main` for weeks; verify this explicitly).
- Constants-duplication checker wired into CI.
- `tools/smoke`: connects to both phones over Tailscale adb, installs the APK, launches, tails tag-filtered logcat, asserts expected lines, exits non-zero on failure. Proven against a stub app that just logs `STARTED`.
- `STATUS.md` created.

**Gate:** one PR goes green on real CI; smoke script installs and verifies the stub on both phones.

### M1 — Tracer bullet (~1 week)
One-directional voice between the two phones. Two debug buttons (Host / Join), no real UI, no service, screen on.
- Advertise with MSD; scan with MSD filter + continuous updates.
- GATT connect: MTU 517, `requestConnectionPriority(HIGH)` on both sides, PSM exchange, L2CAP CoC open.
- Audio pipeline per §4.2/§4.3, one direction (guest → host), open mic (no PTT yet).
- DIAG telemetry lines.

**Gate:** 120 s of continuous one-way voice; smoke asserts rx rate == tx rate ±5%, jitter depth bounded, zero disconnects; a human confirms it sounds like a voice.

### M2 — Duplex, PTT, resilience (~1 week)
- Full duplex; hold-to-talk PTT both sides (gates encode/send only).
- Epoch lifecycle complete; immediate disconnect reaction + reconnect ladder per §4.1.
- Kill tests: toggle Bluetooth, walk out of range, force-stop one side.

**Gate:** 10 consecutive reconnect storms — link recovers < 5 s each, **zero frames eaten after any reconnect** (the v1 watermark class, asserted via seq continuity in DIAG); then 10 min continuous duplex with stable jitter depth. *After this gate, agent-swarm work is permitted on validated paths (rule 6).*

### M3 — Product shell (~1 week)
- Real UI: one main screen (Host / Join → linked view with PTT button + link status), minimal settings.
- ForegroundService owns the session; notification with PTT affordance; MediaSession + audio focus; screen-off operation.
- Switch to secure/bonded GATT + L2CAP (§4.5).
- Volume rocker controls the correct stream (voice-call) — regression-asserted.

**Gate:** 30-minute screen-off, phones-in-pocket conversation; PTT from notification works; doze behavior observed and documented; bonded link survives re-link.

### M4 — BT headset (~1 week)
All known v1 landmines pre-applied, so this is verification plus the unknowns:
- Headset connect **before** link, **during** link, disconnect **mid-talk** — engine survives all (backoff ladder, Shared mode, route re-bind).
- Headset media-button → PTT toggle via MediaSession (stretch goal; if flaky, cut without blocking the milestone).

**Gate:** Shokz matrix (connect-before / connect-during / disconnect-during / reconnect), 3× each, audio survives every case on both phones.

### M5 — Hardening + release (~1 week)
- 2-hour soak test; battery drain measured and recorded in STATUS.md.
- OEM pass on the moto (known canary: BLE advertising payload overflow — keep adv payload minimal).
- Forgejo release workflow: tag → signed release APK → Forgejo release; Obtainium manifest documented in README. Signing secrets verified by producing one real release.
- `SMOKE.md`: the full manual checklist for release candidates.

**Gate:** both phones running a release APK **installed via Obtainium**, full SMOKE.md pass.

### M6 — Shared media (design-gated, ~1–2 weeks)
The one genuinely new feature. **Starts with a one-page design doc, reviewed before any code** — sync strategy (stream the audio over the link vs. play-local-and-sync-clocks), voice ducking, who owns transport bandwidth. v1's implementation is reference material for the design, not for code.

**Gate:** shared playback perceptibly in sync (≤ ~100 ms), voice ducks media, PTT still meets latency expectations while media plays.

**Total: ~6–8 weeks calendar at v1 pace.** v1 spent 10 weeks to reach a broken state with triple the scope; this estimate banks on the landmine list and the rig existing from day 0.

## 6. Testing strategy

| Layer | What | When |
|---|---|---|
| C++ host tests | jitter buffer (incl. drift cases), ring buffer, codec round-trip, seq/epoch filter, stream-config values. Every component compiles on host; Oboe behind a thin interface | every PR (CI) |
| Kotlin unit | session state machine (incl. reconnect/epoch transitions), protocol codec with **bounds tests on every wire field** (v1's final week was dozens of missing-clamp fixes — bake them in from the first message type) | every PR (CI) |
| Smoke T1 | scripted: install both phones, link, 120 s voice, DIAG asserts | every radio/audio/native PR + nightly |
| Smoke T2 | scripted: reconnect storm, duplex soak, screen-off | milestone gates + weekly |
| Manual | SMOKE.md checklist incl. headset matrix | release candidates |

## 7. Dev workflow

- **Interactive core:** radio/session/audio built pair-style (human + Claude) with the rig in the loop; smoke runs before merge, output in PR body.
- **Agent swarm:** permitted after the M2 gate, only on smoke-covered paths, with CI green on the PR-head sha (require the `pull_request`-context run, not the push run — v1 gotcha). Logic-only PRs (codec bounds, UI polish) need CI only; anything touching radio/audio/native needs T1 smoke evidence.
- **PR hygiene:** conventional commits; small PRs fine but no backlog-grinding sprees on ungated paths; issues triaged against `STATUS.md` reality before work starts (v1's imported backlog was partially stale).

## 8. Risks

| Risk | Mitigation |
|---|---|
| Forgejo runner can't get JDK17/SDK/NDK | M0's first task; timeboxed 1 week; fallback = local builds + manual gates while resolving. Locked to Forgejo regardless — the fallback changes *where builds run*, not the forge |
| Compose/Kotlin-service ramp-up (coming from Flutter) | UI is deliberately two screens; session/service layer is where care goes. Budget M3 generously |
| L2CAP CoC behavior differs across OEMs | The two rig phones are the only support targets for v2.0; moto is the canary, tested from M1 |
| Secure-channel (bonding) friction or quirks | Introduced at M3 with its own gate item; insecure fallback retained behind a debug flag during development only |
| Media-share sync is genuinely hard | Quarantined in M6 behind a design-doc gate; cannot destabilize voice milestones |
| Old habit regression: building ahead of gates | Rules 1, 2, 6; STATUS.md makes drift visible; milestone order is the enforcement |

## Appendix A — Platform landmine list (canonical)

Every item cost real debugging time in v1. Apply at the milestone noted; treat deviations as decisions to document, not defaults.

| # | Landmine | Rule | v1 receipt | Applied |
|---|---|---|---|---|
| 1 | Default BLE connection interval (~30 ms) carries ~35 pkt/s; 50 fps voice backlogs unboundedly | `requestConnectionPriority(CONNECTION_PRIORITY_HIGH)` both sides on connect; cap the send queue | #474, validated 06-06 | M1 |
| 2 | Unfiltered BLE scans get demoted to opportunistic ~90 s in; first-match dedup hides recovery | Scan with manufacturer-data filter (0xFFFF, [0x01,0x01] pattern) + continuous updates | #475 | M1 |
| 3 | `SharingMode::Exclusive` fails on BT routes; MMAP fast path bypasses speaker loudness DSP; Media usage puts playout on the wrong volume stream in comm mode | Output: `Shared` + `Usage::VoiceCommunication` + `ContentType::Speech` + `PerformanceMode::None` | #464/#479, validated 06-07 | M1 |
| 4 | Streams opened before comm mode/routing are mis-routed and ignore `setCommunicationDevice` | `MODE_IN_COMMUNICATION` + `setCommunicationDevice` **before** opening streams | #490 | M1 |
| 5 | Background mic silently suppressed on Android 11+ despite `foregroundServiceType=microphone` | Pair the service with an active MediaSession + audio focus | v1 design note | M3 |
| 6 | Heartbeat-only disconnect detection leaves 15 s zombie links | React to `GATT_DISCONNECTED` immediately, any status | 05-31 findings | M2 |
| 7 | GATT status 19 = peer terminated intentionally; blind same-params retry ladders never recover | Treat 19 as an event (re-evaluate, re-scan), not a retriable error | Blocker 1, never fully fixed in v1 | M2 |
| 8 | One-shot immediate restarts race route settling (esp. BT connect) and then give up | Every restart path: bounded backoff ladder (150/300/600/1000/1500 ms) | #464 | M1 |
| 9 | Per-link state surviving reconnect eats frames (watermarks, jitter buffers — in **every** layer that holds state) | Epoch-scoped construction/destruction; no reset() methods | Blocker 2, #130, #476 (one never fixed) | M2 |
| 10 | Constants duplicated across languages drift and fight (bitrate ladders) | Single source in `proto/constants.h` + codegen + CI duplicate-literal check | 05-31 root cause #1 | M0 |
| 11 | ~~Git submodule (opus) silently absent in fresh clones~~ — **N/A in v2** (ADPCM needs no vendored codec, 2026-06-14); the broader trap stands: piping builds through `tail` hides native failures | Bootstrap verifies JDK/NDK/cmake + fails loud; never filter build output | v1 worktree trap | M0 |
| 12 | Unbounded wire-field decodes (lengths, counts, floats) | Bounds-check every field at decode; property/bounds tests per message type from the start | ~dozens of June clamp PRs | M1+ |
| 13 | Motorola BLE advertising payload overflow | Keep advertising payload minimal; moto is the canary device | #381 | M1 |
| 14 | CI that doesn't check out the PR head validates nothing | CI echoes head sha; merge gate requires the `pull_request`-context run | Forgejo #59 | M0 |
