# Intercom

A private 1:1 push-to-talk intercom between two Android phones over Bluetooth LE
or Wi-Fi Direct. Pure native: Kotlin + Jetpack Compose + C++ (Oboe + ADPCM).
Distributed by sideload (Obtainium) from Forgejo releases. Android 12+ (minSdk 31).

## Install (Obtainium)

[![Add to Obtainium](https://img.shields.io/badge/Obtainium-Add%20Intercom-1e88e5?style=for-the-badge&logo=android&logoColor=white)](https://apps.obtainium.imranr.dev/redirect?r=obtainium%3A%2F%2Fadd%2Fhttp%3A%2F%2F100.95.212.39%3A3000%2Flaarz%2Fintercom)

Reachable over Tailscale only (tailnet-private, no token for read). Tap the badge
on the phone to add this repo as an Obtainium source, or add it manually:

1. Obtainium → **Add App** → URL `http://100.95.212.39:3000/laarz/intercom`
   (MagicDNS alt: `http://laarz-desk-1:3000/laarz/intercom`) → source **Forgejo**.
2. Obtainium tracks the latest release; new versions appear on refresh.

Manual sideload fallback: [Releases](http://100.95.212.39:3000/laarz/intercom/releases).

This is the greenfield v2 restart of `walkie-talkie`. No v1 code was carried over —
only lessons.

| Doc | What |
|---|---|
| [V2_PLAN.md](V2_PLAN.md) | The governing plan: architecture, milestones, hardware gates, platform landmine list. Read before working here. |
| [POSTMORTEM.md](POSTMORTEM.md) | Why v1 was retired and what this repo must not repeat. |
| [STATUS.md](STATUS.md) | Per-subsystem, last-validated-on-device dates. The only status that counts. |
| [M1_PLAN.md](M1_PLAN.md) | Current milestone (M1 — tracer bullet) design + ordered todo. Start here when picking up M1. |

## Build

```
./gradlew assembleDebug
```

Requires JDK 17+ and an Android SDK (`local.properties` → `sdk.dir`). Native code
(Oboe + ADPCM) arrives in M1 and will add NDK + cmake requirements.

## Rules of the repo

1. A milestone is done when the scripted two-phone test passes on real hardware — never before.
2. "Code-complete" is banned vocabulary. STATUS.md tracks what was validated, where, when.
3. PRs touching radio/audio/native paste smoke output in the PR body.

See V2_PLAN.md §3 for the full list.
