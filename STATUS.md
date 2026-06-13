# STATUS

Per V2_PLAN.md rule 2: status is what ran on hardware, not what compiles.
Update at every milestone gate and any on-device session.

**Current milestone: M0 — infrastructure.**

| Subsystem | Last validated on device | Device(s) | What passed |
|---|---|---|---|
| App shell (stub) | 2026-06-12 | Pixel 10 Pro XL (USB) | adb install + launch + `INTERCOM: STARTED version=0.0.1` in logcat |
| CI (Forgejo) | 2026-06-12 | `flutter` runner (Ubuntu 24.04 container) | checkout-by-sha + `assembleDebug` green in 1m23s (run 2); JDK 21 + SDK present on image. NDK/cmake still unprobed (matters from M1) |
| Smoke harness | — | — | not built |
| Radio (BLE link) | — | — | M1, not started |
| Voice pipeline | — | — | M1, not started |

## Log

- 2026-06-12 — repo created; skeleton scaffolded (Compose stub, `INTERCOM: STARTED` log line).
- 2026-06-12 — first build (59 s, Gradle 8.14/AGP 8.11.1) + first device run: installed and launched on Pixel 10 Pro XL over USB, STARTED log confirmed. Screen was locked (owner on a call) so no UI screenshot; log line is the validation.
