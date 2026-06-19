# Intercom

A private 1:1 push-to-talk intercom between two Android phones over Wi-Fi Direct,
with optional **shared listening** — stream one phone's media (e.g. a podcast in
AntennaPod) to the other so you hear it together, with voice ducking the media.
Pure native: Kotlin + Jetpack Compose + C++ (Oboe + ADPCM). Distributed by
sideload (Obtainium) from GitHub releases. Android 12+ (minSdk 31).

> Shared audio uses Android's playback capture, which apps can opt out of:
> open media apps (AntennaPod, etc.) work; most DRM streaming apps (e.g. YouTube
> Music) block capture and will share silence. The Bluetooth LE transport is
> deprecated; Wi-Fi Direct is the supported path.

## Install (Obtainium)

Tap on the phone, or add `https://github.com/ElodinLaarz/intercom` in Obtainium → **Add App** (GitHub source, auto-detected):

[![Add via GitHub](https://img.shields.io/badge/Obtainium-Add%20via%20GitHub-24292e?style=for-the-badge&logo=github&logoColor=white)](https://apps.obtainium.imranr.dev/redirect?r=obtainium%3A%2F%2Fadd%2Fhttps%3A%2F%2Fgithub.com%2FElodinLaarz%2Fintercom)

Obtainium tracks the latest release; new versions appear on refresh. Manual sideload:
[GitHub Releases](https://github.com/ElodinLaarz/intercom/releases).

This is the greenfield v2 restart of `walkie-talkie`. No v1 code was carried over —
only lessons.

| Doc | What |
|---|---|
| [V2_PLAN.md](V2_PLAN.md) | The governing plan: architecture, milestones, hardware gates, platform landmine list. Read before working here. |
| [POSTMORTEM.md](POSTMORTEM.md) | Why v1 was retired and what this repo must not repeat. |
| [STATUS.md](STATUS.md) | Per-subsystem, last-validated-on-device dates. The only status that counts. |
| [M1_PLAN.md](M1_PLAN.md) | Current milestone (M1 — tracer bullet) design + ordered todo. Start here when picking up M1. |
| [SHARED_AUDIO.md](SHARED_AUDIO.md) | M6 shared-audio design: AAC-LC over Wi-Fi Direct, voice ducking, known limits. |

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
