#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>

#include "constants.h"

// On-wire media frame (shared-audio path, coded audio over Wi-Fi Direct only).
// Little-endian, variable length (header 12 bytes + coded-audio payload):
//   epoch u32 | seq u32 | flags u8 | reserved u8 | len u16 | payload[len]
//
// flags bit0 = stereo, bit1 = FEC-bearing, bit2 = share-state sentinel;
// other bits reserved (sender sends 0). The parser is lenient on flags
// (unknown bits pass through) but strict on reserved (drop if nonzero).
//
// This is proto/'s C++ media-frame codec; app/.../proto/MediaFrame.kt is the
// Kotlin mirror. Both read the layout from constants.h — the single source.

namespace intercore {

struct MediaFrame {
  std::uint32_t epoch = 0;
  std::uint32_t seq = 0;
  std::uint8_t flags = 0;
  std::array<std::uint8_t, proto::kMediaPayloadMaxBytes> payload{};
  std::uint16_t len = 0;
};

namespace mf_detail {

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

}  // namespace mf_detail

// Serialize to kMediaHeaderBytes + f.len bytes. `out` must have room.
// Returns the number of bytes written.
inline std::size_t serializeMediaFrame(const MediaFrame& f, std::uint8_t* out) {
  mf_detail::putU32(out + proto::kMediaOffEpoch, f.epoch);
  mf_detail::putU32(out + proto::kMediaOffSeq, f.seq);
  out[proto::kMediaOffFlags] = f.flags;
  out[proto::kMediaOffReserved] = 0;
  const auto len = f.len;
  out[proto::kMediaOffLen] = static_cast<std::uint8_t>(len);
  out[proto::kMediaOffLen + 1] = static_cast<std::uint8_t>(len >> 8);
  for (std::uint16_t i = 0; i < len; ++i) {
    out[proto::kMediaOffPayload + i] = f.payload[i];
  }
  return static_cast<std::size_t>(proto::kMediaHeaderBytes) + len;
}

// Parse + bounds-check. Returns nullopt — DROP the frame — if
// `len < kMediaHeaderBytes`, `reserved != 0`, the encoded len field
// exceeds kMediaPayloadMaxBytes, or the total size does not match
// kMediaHeaderBytes + lenField. Unknown flags bits pass through
// (lenient parser policy). epoch/seq are returned as-is; the media
// SeqFilter gates them later.
inline std::optional<MediaFrame> parseMediaFrame(const std::uint8_t* in,
                                                  std::size_t len) {
  if (len < static_cast<std::size_t>(proto::kMediaHeaderBytes)) {
    return std::nullopt;
  }
  const std::uint8_t reserved = in[proto::kMediaOffReserved];
  if (reserved != 0) {
    return std::nullopt;
  }
  const std::uint16_t lenField =
      static_cast<std::uint16_t>(in[proto::kMediaOffLen]) |
      (static_cast<std::uint16_t>(in[proto::kMediaOffLen + 1]) << 8);
  if (lenField > proto::kMediaPayloadMaxBytes) {
    return std::nullopt;
  }
  if (len != static_cast<std::size_t>(proto::kMediaHeaderBytes) + lenField) {
    return std::nullopt;
  }
  MediaFrame f;
  f.epoch = mf_detail::getU32(in + proto::kMediaOffEpoch);
  f.seq = mf_detail::getU32(in + proto::kMediaOffSeq);
  f.flags = in[proto::kMediaOffFlags];
  f.len = lenField;
  for (std::uint16_t i = 0; i < lenField; ++i) {
    f.payload[i] = in[proto::kMediaOffPayload + i];
  }
  return f;
}

// Layout guards — tie the wire offsets to the single-sourced sizes so a bad
// edit to constants.h fails the host build, not a device.
static_assert(proto::kMediaOffEpoch == 0,
              "epoch must be at offset 0");
static_assert(proto::kMediaOffLen + 2 == proto::kMediaOffPayload,
              "payload must start right after len (10+2==12)");
static_assert(proto::kMediaOffPayload == proto::kMediaHeaderBytes,
              "payload offset must equal header size (12==12)");
static_assert(proto::kMediaOffReserved + 1 == proto::kMediaOffLen,
              "reserved and len must be adjacent (9+1==10)");

}  // namespace intercore
