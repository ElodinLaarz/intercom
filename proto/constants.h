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
// Codec parameters (sample rate, frame size, bitrate) are deliberately ABSENT
// until the M1 voice-path design locks them — "lock codec before voice".
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

}  // namespace intercore::proto
