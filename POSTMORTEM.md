# Walkie-Talkie v1 Post-Mortem

**Date:** 2026-06-12
**Status:** v1 retired. v2 is a greenfield restart — see [V2_PLAN.md](V2_PLAN.md).
**Verdict in one line:** We built the entire ship in drydock, declared it seaworthy, and then discovered water.

---

## 1. Summary

v1 set out to be a BLE walkie-talkie for Android: rooms ("frequencies"), up to 12 peers, host-mixed voice over L2CAP + Opus, host handover, shared media, invite links, Sentry, and a Play Store release. Over ~10 weeks of active development we produced ~27k lines of Dart/Kotlin/C++ with 903 green unit tests — and the first time two real phones ran the app (day ~60 of ~72), a guest could not join at all.

The four weeks that followed were a serial excavation of stacked platform bugs, each invisible until the one above it was fixed. Most are now fixed and several were validated on hardware, but the project ends with core paths (role swap, reconnect state, headset survival) still unvalidated or open, a README that claims "code-complete" for phases that never were, and ~190 commits merged since the last on-device test.

The root cause was not scope, architecture, or any individual bug. It was sequencing: integration with reality came last instead of first.

---

## 2. Timeline

| Date | Event |
|---|---|
| 2025-11-22 | Repo created. 13 commits through December. |
| Dec 2025 – Mar 2026 | Dormant (~3 months, reason not recorded; excluded from "active dev" below). |
| Apr 2026 | Active development resumes. 99 commits in April, 153 in May. UI, services, protocol, native audio, tests, Play Store docs all built in this window. |
| 2026-05-30 | **First two-phone radio test** — day ~60 of ~72 active days. Guest join completely broken (connect call site never wired; a native write silently dropped). Fixed in #454/#455. README had already declared Phases 2–5 "✅ code-complete". |
| 2026-05-31 | On-device session catalogs four systemic root causes (bitrate ladder conflict, jitter buffer never resets, GATT idle-terminate churn, control-plane watermark eating frames) plus BT-headset silence (3 stacked causes). |
| 2026-06-06 | `requestConnectionPriority(HIGH)` + bitrate ladder alignment + send-queue cap land (#474) — **validated on hardware**: queue depth 0, rx==tx at 50 fps, sustained. Discovery dropout found same day (unfiltered scan demoted by Android ~90 s in). |
| 2026-06-07 | Drift-tolerant jitter buffer (#477) kills the degrade-to-noise symptom — validated. Playout-near-silent fixed (#479, #490: voice-call stream + off-MMAP + comm-mode-before-open) — validated. BT headset fixes (#464) merged — **never device-validated**. |
| 2026-06-08 | Scan-filter fix merged (#475). Forge migration GitHub→Forgejo around this time (fresh PR numbering). |
| 2026-06-08 – 06-12 | ~160 Forgejo PRs in 5 days: decode bounds, telemetry clamps, sequence-filter edge cases — merged on Dart-only CI, no radio validation. |
| 2026-06-12 | This post-mortem. No on-device test since 06-07. |

## 3. By the numbers

| Metric | Value |
|---|---|
| Wall-clock age | ~6.5 months |
| Active development | ~10 weeks (Apr → Jun 2026) |
| Commits | 411 (local view; Forgejo main slightly ahead) |
| Tracker objects | ~495 GitHub-era issues/PRs + ~221 Forgejo-era in 5 days |
| Fix : feat commit ratio | 147 : 85 (1.7 : 1) |
| Code | 18.2k LOC Dart (59 files) + 7.5k LOC Kotlin (38 files) + C++ glue over Oboe/Opus |
| Tests | 903 Dart unit tests, 10 native C++ test binaries, **0 device/integration tests** |
| Time before first radio test | **83% of active development** |
| Hottest file | `lib/bloc/frequency_session_cubit.dart`: 53 of 411 commits (13%), >1,500 lines |

## 4. Root causes

### RC1 — Integration with reality came last
83% of dev time elapsed before two phones ever ran the app together. Unit and widget tests ran against mocked/null transports — we tested our mocks. CI (ubuntu) has no radio, so it structurally cannot catch any of the bugs that mattered. "Code-complete" in the README meant "code exists and unit tests pass," and that definition let five phases get checked off while the core join flow was broken. 903 green tests provided months of false confidence.

### RC2 — Radio/audio bugs stack and mask each other, forcing serial discovery
Every on-device bug hid the next one behind it. Each fix required a full build → install on two phones → logcat → diagnose cycle. The chain, in discovery order:

join broken → GATT idle-terminate churn → unbounded latency growth → quality floored → degrade-to-noise → near-silent playout → headset kills engine → discovery dropout.

Eight stacked platform bugs, none catchable off-device, found one at a time over two weeks. This was the irreducible cost of RC1: the later integration starts, the longer this serial chain takes, because it cannot be parallelized.

### RC3 — State and constants duplicated across three languages
Dart, Kotlin, and C++ each held copies of things that must agree:
- The Dart bitrate ladder (8/16/24 kbps) disagreed with the native ladder (16/32/48 kbps); the adaptation loop actively pinned encoders **below** the native boot default. The quality bug was the quality *system*.
- Reconnect state existed in two layers: the Dart `SequenceFilter` watermark and the native jitter buffer / peer registry. Both independently failed to reset on reconnect — two bugs with identical shape, fixed separately, one (#476) never fixed at all.

This is a bug *class*, not a bug: every datum owned in two places across a language bridge eventually disagrees.

### RC4 — God object
`frequency_session_cubit.dart` absorbed session, transport callbacks, audio control, roster, link quality, and media state. 13% of all commits touched this one file. Every subsystem change rippled through it, and its test file churned in lockstep (45 commits).

### RC5 — Self-inflicted infrastructure wounds
- Two forges with diverged mains; a mid-crunch GitHub→Forgejo migration.
- Forgejo CI cloned `main` instead of the PR head — **every PR's CI result was meaningless** until fixed (Forgejo #59).
- Forgejo CI is Dart-only (runner lacks JDK 17), so Kotlin/C++ changes merge unvalidated on the forge where development now happens.
- The opus git submodule silently breaks fresh worktrees, and `flutter build | tail` masked the native build failure.
- Merge-gate gotchas (head-sha vs merge-base, Content-Type on PR creation) each cost round-trips.

### RC6 — Priority inversion at the end
The final week produced ~160 micro-PRs (2^31 sequence-wraparound edge cases, decode clamps, telemetry bounds) while role-swap churn, native reconnect reset, and the headset path sat unvalidated on hardware. High process cost per fix, low product impact, and the backlog being ground was partially stale (issues already fixed on main).

## 5. On-device bug catalog

| # | Symptom | Root cause | Fix | Validated on device? |
|---|---|---|---|---|
| 1 | Guest cannot join at all | Connect call site never wired; native write silently dropped | #455 (2026-05-30) | Yes |
| 2 | Voice latency grows unbounded | Default ~30 ms connection interval carries ~35 pkt/s; encoder produces 50 fps | `requestConnectionPriority(HIGH)` + send-queue cap, #474 | Yes (06-06: qDepth=0, rx==tx) |
| 3 | Quality floored at 8 kbps | Dart ladder 8/16/24k vs native 16/32/48k; loss metric conflated link churn with talker loss | Ladder aligned, #474 | Yes |
| 4 | Audio degrades into delayed noise | Jitter buffer had no clock-drift tolerance | Drift-tolerant jitter buffer, #477 | Yes (06-07) |
| 5 | Playout near-silent on Pixel, loud on moto | Output stream on Media usage while app in comm mode (volume rocker controls the other stream); MMAP fast path bypasses speaker loudness DSP | `Usage::VoiceCommunication` + `Shared` + `PerformanceMode::None` (#479); comm mode before stream open (#490) | Yes (06-07) |
| 6 | BT headset connect kills audio engine | Oboe hardcoded `SharingMode::Exclusive` (impossible on BT); restart one-shot with no backoff; comm route never forced before tune-in | Shared fallback + backoff ladder + route re-bind, #464 | **No** |
| 7 | Guest loses host ~90 s into discovery | Unfiltered BLE scan demoted to opportunistic by Android; fbp first-match dedup hides revival | MSD scan filter (0xFFFF, [0x01,0x01]) + `continuousUpdates`, #475 | Yes |
| 8 | Host eats first frames after reconnect | Dart watermark only reset on JoinRequest; fast GATT auto-reconnect skips JoinRequest | Watermark reset tied to owning endpoint (Forgejo #130/#158) | **No** (merged post-06-07) |
| 9 | Stale native peer state on reconnect | `PeerAudioManager::unregisterPeer()` had zero callers; `registerPeer` returns stale state | Filed as #476 | **Open** |
| 10 | Role-swap GATT churn (status 19 → 255) | Retry ladder retries same params against intentional remote termination; never recovers | Symptom patches only (e.g. #220 drop-oldest queue cap) | **Open** |

## 6. What went right

These were correct calls and carry forward as knowledge (not code — v2 is greenfield):

- **Transport architecture.** GATT control plane + L2CAP CoC voice plane + Opus. Never revisited, never the problem. The native-host / fbp-guest split was a correct response to flutter_blue_plus lacking an advertiser and L2CAP (moot in v2: pure native).
- **Host-testable native design (late).** `playback_stream_config.h` proved Oboe-adjacent logic can be pinned by host-built tests. The 10 native test binaries caught real regressions.
- **The two-phone rig.** Tailscale adb + tag-filtered logcat + VOICE-DIAG telemetry lines found every bug within hours of looking. Built ~8 weeks too late; in v2 it's milestone 0.
- **Runbooks and recorded landmines.** DEPLOY.md, smoke-test.md, and the memory of each platform fix are the most valuable artifacts v1 produced. The full landmine list is canonized in [V2_PLAN.md](V2_PLAN.md) Appendix A.
- **Scope discipline where it existed.** Android-only, no mesh, no encryption-beyond-pairing were good cuts.
- **sqflite over Isar/Hive** (Isar stalled; Hive migrated away cleanly).

## 7. Lessons → rules for v2

| Root cause | Rule in v2 |
|---|---|
| RC1 integration last | Tracer bullet first: two phones exchanging voice is milestone 1; nothing else exists until it passes. Hardware gate on every milestone. |
| RC1 false status | "Code-complete" is banned. Status = last on-device validation date + what passed (STATUS.md). |
| RC2 serial discovery | Apply the known landmine list (Appendix A of V2_PLAN) at build time, not debug time. Keep the two-phone smoke scripted and cheap so the loop is minutes, not hours. |
| RC3 duplicated state | One language owns each datum. Constants have a single source. Per-link state is constructed per connection epoch and destroyed on disconnect — destruction is the reset. |
| RC4 god object | Hard module boundaries: radio / session / audio / UI with narrow interfaces. |
| RC5 infra wounds | One forge (Forgejo), runner fixed so CI builds Kotlin + native + APK from day 1, PR-head checkout verified, bootstrap script fails loud. |
| RC6 priority inversion | No hardening swarms on paths that have never passed a hardware gate. Agent-driven PR volume only behind green gates. |

The plan that operationalizes these rules: [V2_PLAN.md](V2_PLAN.md).
