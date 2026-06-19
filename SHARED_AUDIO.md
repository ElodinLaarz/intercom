# Shared Audio — design (M6)

**Date:** 2026-06-19 · **Status:** implemented, local-validation only; two-phone
rig gate pending. This is the V2_PLAN M6 "one-page design doc, reviewed before
code" gate.

## Goal

Two paired phones already run 1:1 duplex voice over Wi-Fi Direct. Let them
**listen to the same media together** — capture whatever a media app (e.g.
AntennaPod) is playing on one phone, stream it to the other, and have the
intercom voice duck the media. Either phone may be the one sharing.

## Decisions

| Topic | Choice | Why |
|---|---|---|
| Codec | **AAC-LC via Android `MediaCodec`** (Kotlin) | Hardware encode+decode on every Android; no vendored library; buildable/testable on the Windows rig; keeps media off the fragile native voice pipeline (AGENTS boundary 4). |
| Transport | **Second TCP socket on `MEDIA_STREAM_PORT` (9754)** in the existing WFD group | Voice (9753) is fixed-stride and desync-prone; media is variable-length. Separate socket = zero changes to the voice radios. |
| Wire frame | `MediaFrame`: `epoch u32 | seq u32 | flags u8 | reserved u8 | len u16 | payload[len]` (codec-neutral) | Single-sourced in `proto/constants.h`; length-delimited so variable AAC units self-frame. |
| Ownership | `MediaShareController`, built per `Linked` epoch, torn down on unlink (rule 4) | Driven off `LinkState.Linked` at the `RadioController` layer; the voice endpoints are untouched. |
| Ducking | Poll the partner's decoded-voice peak (`NativeCore.voiceRxPeak()`) → `VoiceActivityGate` (hysteresis) → `MediaPlayout.setDucked` | Reuses an atomic the voice RxEngine already maintains; the only native addition is a getter. |

## Flow

```
SHARER                                          LISTENER
AntennaPod ─AudioPlaybackCapture→ MediaCapture   WifiDirectMediaLink (RX, :9754)
  PCM 48k stereo → MediaEncoder (AAC-LC)           → MediaWire.readFrame → MediaSeqFilter
  → MediaFrame(epoch,seq,…) ─WFD :9754────────────▶  → MediaDecoder (AAC→PCM) → MediaPlayout
                                                      (USAGE_MEDIA), ducked by partner voice peak
```

- **Roles:** the WFD group owner (voice host) accepts on 9754; the guest dials
  it. The channel is bidirectional; only the side that tapped *Share Audio*
  sends. A `flags` bit2 **share-state sentinel** frame announces start/stop so
  the listener shows "Partner is sharing" and tears its decoder down cleanly.
- **Epoch/seq:** frames are stamped with the voice link's already-agreed
  `wireEpoch`; the listener's `MediaSeqFilter` drops stale-epoch/replayed frames.
- **Reconnect:** projection is owned by the foreground service, so a WFD
  auto-rejoin rebuilds the media link + codecs on the next `Linked` and resumes.
- **Threading:** TX (capture/encoder) on main + capture thread; the RX pipeline
  (decoder/playout) is built, fed, and destroyed only on the media-link worker
  thread (MediaCodec is not safe to release elsewhere mid-decode); ducking on its
  own poll thread. Best-effort throughout: no input buffer / socket backpressure
  drops the chunk rather than blocking — media yields to realtime.

## Key files

`media/`: `MediaCapture`, `MediaEncoder`, `MediaDecoder`, `MediaPlayout`,
`MediaSeqFilter`, `MediaWire`, `VoiceActivityGate`, `MediaShareController`,
`MediaProjectionRelay`, `MediaShareService`, `SilenceDetector`.
`wifidirect/WifiDirectMediaLink`. `proto/{MediaFrame.kt, media_frame.h,
constants.h}`. Native getter in `voice_audio.*` + `intercore_jni.cpp`.

## Known limitations

- **AudioPlaybackCapture is opt-out by apps.** AntennaPod and other open media
  apps are capturable; **YouTube Music / most DRM streaming apps set
  `ALLOW_CAPTURE_BY_NONE` and emit silence** — an OS policy, not fixable in-app.
  The UI surfaces a "No audio detected (DRM apps can't be shared)" hint.
- **Media + voice routing coexistence is the #1 rig risk** — voice holds
  `MODE_IN_COMMUNICATION` on the headset while media plays `USAGE_MEDIA`. On
  classic-BT (SCO) headsets SCO/A2DP are mutually exclusive; the Pixel's LE Audio
  headset can carry both. Validate on the rig.
- Ducking thresholds (`DUCK_ON_PEAK`/`DUCK_OFF_PEAK`/release) are rig-tunable.

## Gate (owner-run, per AGENTS rule 1)

Pixel 10 Pro XL + moto over Wi-Fi Direct. Confirm voice still works, then play a
podcast in AntennaPod on one phone, tap *Share Audio*, grant the projection
dialog → the other phone plays it; talk → media ducks; stop → media stops, voice
continues. Watch `MEDIA capture/encoder/link` + `DIAG` logs.
