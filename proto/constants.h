#pragma once

#include <cstdint>

// SINGLE SOURCE OF TRUTH for wire constants (V2_PLAN §4.4, landmine #10).
//
// v1 died in part because Dart, Kotlin, and C++ each held their own copies of
// values that had to agree, and they drifted (RC3). Here the values live once,
// in C++. The Kotlin mirror `Proto.kt` is GENERATED from this file by
// tools/gen-proto.sh and checked in; CI fails if it drifts, and fails if any of
// these literals is re-declared in hand-written Kotlin.
//
// Codec parameters (sample rate, frame size, header layout) were locked at the
// M1 voice-path design sign-off (issue #16; M1_PLAN.md §2) — see "Voice path"
// below. The rule held: lock the codec before writing voice.
//
// Generator contract: it reads lines of exactly these two shapes —
//   inline constexpr int  kName     = <value>;
//   inline constexpr char kName[]   = "<value>";
// so keep new constants in that form (one per line).

namespace intercore::proto {

inline constexpr int kProtocolVersion = 1;       // bump on ANY wire-format change
inline constexpr int kMsdCompanyId = 0xFFFF;     // BLE manufacturer id (landmine #2)
inline constexpr int kMsdPattern0 = 0x01;        // MSD scan-filter pattern, byte 0
inline constexpr int kMsdPattern1 = 0x01;        // MSD scan-filter pattern, byte 1

// Advertised Intercom service, and the GATT characteristic that publishes the
// L2CAP PSM the guest connects to. Fixed for v1 (the two rig phones).
inline constexpr char kServiceUuid[] = "b1f0c0de-1a2b-4c3d-8e5f-000000000001";
inline constexpr char kPsmCharUuid[] = "b1f0c0de-1a2b-4c3d-8e5f-000000000002";

// ---- Voice path (locked 2026-06-14, issue #16; M1_PLAN.md §2) --------------
// IMA ADPCM over 16 kHz mono PCM16, 20 ms frames. The on-wire frame is
// little-endian and SELF-CONTAINED — each header carries the pre-roll IMA
// predictor snapshot, so a single lost frame costs exactly one 20 ms gap and
// the next frame self-heals from its own header:
//   epoch u32 | seq u32 | predSample i16 | stepIndex u8 | reserved u8 | adpcm[160]
inline constexpr int kVoiceSampleRateHz  = 16000;  // wideband PCM16, mono
inline constexpr int kVoiceFrameMs       = 20;     // 50 fps
inline constexpr int kVoiceFrameSamples  = 320;    // 16000 * 20 / 1000
inline constexpr int kVoiceAdpcmBytes    = 160;    // 320 samples * 4 bit, 2/byte
inline constexpr int kVoiceHeaderBytes   = 12;     // epoch+seq+pred+step+reserved
inline constexpr int kVoiceFrameBytes    = 172;    // header + adpcm payload
inline constexpr int kVoiceStepIndexMax  = 88;     // IMA step-table top index

// Wire field offsets (bytes from frame start). Single-sourced so the Kotlin and
// C++ codecs can never disagree on the layout (V2_PLAN rule 3, landmine #10).
inline constexpr int kVoiceOffEpoch      = 0;
inline constexpr int kVoiceOffSeq        = 4;
inline constexpr int kVoiceOffPredSample = 8;
inline constexpr int kVoiceOffStepIndex  = 10;
inline constexpr int kVoiceOffReserved   = 11;
inline constexpr int kVoiceOffAdpcm      = 12;

}  // namespace intercore::proto
