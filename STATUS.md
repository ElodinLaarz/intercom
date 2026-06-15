# STATUS

Per V2_PLAN.md rule 2: status is what ran on hardware, not what compiles.
Update at every milestone gate and any on-device session.

**Current milestone: M0 — infrastructure.**

| Subsystem | Last validated on device | Device(s) | What passed |
|---|---|---|---|
| App shell (stub) | 2026-06-12 | Pixel 10 Pro XL (USB) | adb install + launch + `INTERCOM: STARTED version=0.0.1` in logcat |
| CI (Forgejo) | 2026-06-14 | `flutter` runner (Ubuntu 24.04 container) | NDK 27.2.12479018 + cmake 3.22.1 installed in-CI via sdkmanager; preflight (bootstrap) green; host `ctest` 2/2; NDK-backed `assembleDebug` BUILD SUCCESSFUL (run 7, PR #9). PR-head-sha checkout verified |
| Native core (ring/seq) | 2026-06-14 | CI host (Ubuntu) | host `ctest` 2/2: `RingBuffer` drop-oldest + overwrite count, `SeqFilter` epoch gate + 2^32-wrap. On-device `NativeCore.selfTest`=0xC0DE verified at the M0 phone gate |
| Smoke harness | — | — | not built |
| Radio (BLE link) | — | — | M1, not started |
| Voice pipeline | — | — | M1, not started |

## Log

- 2026-06-12 — repo created; skeleton scaffolded (Compose stub, `INTERCOM: STARTED` log line).
- 2026-06-12 — first build (59 s, Gradle 8.14/AGP 8.11.1) + first device run: installed and launched on Pixel 10 Pro XL over USB, STARTED log confirmed. Screen was locked (owner on a call) so no UI screenshot; log line is the validation.
- 2026-06-14 — codec decision Opus→ADPCM recorded (PR #8). CI taken native: in-CI sdkmanager installs NDK r27c + cmake 3.22.1, fail-loud bootstrap preflight, host `ctest` (ring_buffer, seq_filter), NDK-backed `assembleDebug` — all green (PR #9, run 7). Riskiest M0 infra item (runner native toolchain) proven. Phones not yet paired; M0 gate still open.
