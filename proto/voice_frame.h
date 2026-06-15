#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>

#include "constants.h"

// On-wire voice frame (M1_PLAN.md §2). Little-endian, kVoiceFrameBytes (172):
//   epoch u32 | seq u32 | predSample i16 | stepIndex u8 | reserved u8 | adpcm[160]
//
// predSample/stepIndex are the pre-roll IMA predictor snapshot the decoder seeds
// from (see native/codec/adpcm.h). Frames are independent: lose one, lose one
// 20 ms gap. Parsing bounds-checks EVERY field (landmine #12); a frame that
// fails any check is dropped (nullopt) rather than trusted.
//
// This is proto/'s C++ voice-frame codec; app/.../proto/VoiceFrame.kt is the
// Kotlin mirror. Both read the layout from constants.h — the single source.

namespace intercore {

struct VoiceFrame {
  std::uint32_t epoch = 0;
  std::uint32_t seq = 0;
  std::int16_t predSample = 0;
  std::uint8_t stepIndex = 0;
  std::array<std::uint8_t, proto::kVoiceAdpcmBytes> adpcm{};
};

namespace vf_detail {

inline void putU32(std::uint8_t* p, std::uint32_t v) {
  p[0] = static_cast<std::uint8_t>(v);
  p[1] = static_cast<std::uint8_t>(v >> 8);
  p[2] = static_cast<std::uint8_t>(v >> 16);
  p[3] = static_cast<std::uint8_t>(v >> 24);
}

inline std::uint32_t getU32(const std::uint8_t* p) {
  return static_cast<std::uint32_t>(p[0]) |
         (static_cast<std::uint32_t>(p[1]) << 8) |
         (static_cast<std::uint32_t>(p[2]) << 16) |
         (static_cast<std::uint32_t>(p[3]) << 24);
}

}  // namespace vf_detail

// Serialize to exactly kVoiceFrameBytes. `out` must have room for that many.
inline void serializeVoiceFrame(const VoiceFrame& f, std::uint8_t* out) {
  vf_detail::putU32(out + proto::kVoiceOffEpoch, f.epoch);
  vf_detail::putU32(out + proto::kVoiceOffSeq, f.seq);
  const auto p = static_cast<std::uint16_t>(f.predSample);
  out[proto::kVoiceOffPredSample] = static_cast<std::uint8_t>(p);
  out[proto::kVoiceOffPredSample + 1] = static_cast<std::uint8_t>(p >> 8);
  out[proto::kVoiceOffStepIndex] = f.stepIndex;
  out[proto::kVoiceOffReserved] = 0;
  for (int i = 0; i < proto::kVoiceAdpcmBytes; ++i) {
    out[proto::kVoiceOffAdpcm + i] = f.adpcm[static_cast<std::size_t>(i)];
  }
}

// Parse + bounds-check. Returns nullopt — DROP the frame — if `len` is not
// kVoiceFrameBytes, `stepIndex` > kVoiceStepIndexMax, or `reserved` != 0.
// epoch/seq are returned as-is; the SeqFilter (native/core/seq_filter.h) gates
// them against the receiver's epoch and high-water mark.
inline std::optional<VoiceFrame> parseVoiceFrame(const std::uint8_t* in,
                                                 std::size_t len) {
  if (len != static_cast<std::size_t>(proto::kVoiceFrameBytes)) {
    return std::nullopt;
  }
  const std::uint8_t stepIndex = in[proto::kVoiceOffStepIndex];
  const std::uint8_t reserved = in[proto::kVoiceOffReserved];
  if (stepIndex > proto::kVoiceStepIndexMax || reserved != 0) {
    return std::nullopt;
  }
  VoiceFrame f;
  f.epoch = vf_detail::getU32(in + proto::kVoiceOffEpoch);
  f.seq = vf_detail::getU32(in + proto::kVoiceOffSeq);
  const auto lo = static_cast<std::uint16_t>(in[proto::kVoiceOffPredSample]);
  const auto hi = static_cast<std::uint16_t>(in[proto::kVoiceOffPredSample + 1]);
  f.predSample = static_cast<std::int16_t>(
      static_cast<std::uint16_t>(lo | static_cast<std::uint16_t>(hi << 8)));
  f.stepIndex = stepIndex;
  for (int i = 0; i < proto::kVoiceAdpcmBytes; ++i) {
    f.adpcm[static_cast<std::size_t>(i)] = in[proto::kVoiceOffAdpcm + i];
  }
  return f;
}

// Layout guards — tie the wire offsets to the single-sourced sizes so a bad
// edit to constants.h fails the host build, not a device.
static_assert(proto::kVoiceOffAdpcm == proto::kVoiceHeaderBytes,
              "adpcm must start right after the header");
static_assert(
    proto::kVoiceFrameBytes == proto::kVoiceHeaderBytes + proto::kVoiceAdpcmBytes,
    "frame = header + payload");
static_assert(proto::kVoiceFrameSamples == 2 * proto::kVoiceAdpcmBytes,
              "two 4-bit samples per adpcm byte");
static_assert(
    proto::kVoiceFrameSamples ==
        proto::kVoiceSampleRateHz * proto::kVoiceFrameMs / 1000,
    "frame samples = rate * frame_ms / 1000");

}  // namespace intercore
