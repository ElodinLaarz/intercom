#include "media_frame.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>

#include "check.h"
#include "constants.h"

using intercore::MediaFrame;
using intercore::parseMediaFrame;
using intercore::serializeMediaFrame;
namespace proto = intercore::proto;

namespace {

// A fixed golden frame. The SAME vector is asserted byte-for-byte in the Kotlin
// test (MediaFrameTest), so the two codecs cannot drift on the wire layout.
MediaFrame goldenMediaFrame() {
  MediaFrame f;
  f.epoch = 0x01020304u;
  f.seq = 0x05060708u;
  f.flags = 0x02;  // bit1 = FEC-bearing (defined but not used on TCP)
  f.len = 5;
  for (std::uint16_t i = 0; i < f.len; ++i) {
    f.payload[i] = static_cast<std::uint8_t>(i);  // 0..4
  }
  return f;
}

void serialize_matches_golden_bytes() {
  std::array<std::uint8_t, proto::kMediaHeaderBytes + 5> out{};
  const auto written = serializeMediaFrame(goldenMediaFrame(), out.data());
  CHECK(written == static_cast<std::size_t>(proto::kMediaHeaderBytes + 5));
  // epoch 0x01020304 LE
  CHECK(out[0] == 0x04 && out[1] == 0x03 && out[2] == 0x02 && out[3] == 0x01);
  // seq 0x05060708 LE
  CHECK(out[4] == 0x08 && out[5] == 0x07 && out[6] == 0x06 && out[7] == 0x05);
  CHECK(out[8] == 0x02);   // flags
  CHECK(out[9] == 0x00);   // reserved
  CHECK(out[10] == 0x05 && out[11] == 0x00);  // len=5 LE
  CHECK(out[12] == 0x00 && out[13] == 0x01);  // payload[0..1]
  CHECK(out[16] == 0x04);                      // payload[4] == 4
}

void round_trips_through_parse() {
  std::array<std::uint8_t, proto::kMediaHeaderBytes + 5> out{};
  const MediaFrame f = goldenMediaFrame();
  serializeMediaFrame(f, out.data());
  const auto got = parseMediaFrame(out.data(), out.size());
  CHECK(got.has_value());
  CHECK(got->epoch == f.epoch);
  CHECK(got->seq == f.seq);
  CHECK(got->flags == f.flags);
  CHECK(got->len == f.len);
  for (std::uint16_t i = 0; i < f.len; ++i) {
    CHECK(got->payload[i] == f.payload[i]);
  }
}

void parse_rejects_short_buffer() {
  std::array<std::uint8_t, proto::kMediaHeaderBytes + 5> out{};
  serializeMediaFrame(goldenMediaFrame(), out.data());
  CHECK(!parseMediaFrame(out.data(), proto::kMediaHeaderBytes - 1).has_value());
  CHECK(!parseMediaFrame(out.data(), 0).has_value());
}

void parse_rejects_nonzero_reserved() {
  std::array<std::uint8_t, proto::kMediaHeaderBytes + 5> out{};
  serializeMediaFrame(goldenMediaFrame(), out.data());
  out[proto::kMediaOffReserved] = 0x01;
  CHECK(!parseMediaFrame(out.data(), out.size()).has_value());
}

void parse_rejects_len_too_large() {
  // Write the full over-cap len across BOTH bytes and size the buffer to
  // header+len so parse reaches the lenField > kMediaPayloadMaxBytes guard (a
  // truncated single byte would collapse to 1 and hit size-mismatch instead).
  constexpr int tooBig = proto::kMediaPayloadMaxBytes + 1;
  std::array<std::uint8_t, proto::kMediaHeaderBytes + tooBig> out{};
  serializeMediaFrame(goldenMediaFrame(), out.data());
  out[proto::kMediaOffReserved] = 0;
  out[proto::kMediaOffLen] = static_cast<std::uint8_t>(tooBig & 0xFF);
  out[proto::kMediaOffLen + 1] = static_cast<std::uint8_t>((tooBig >> 8) & 0xFF);
  CHECK(!parseMediaFrame(out.data(), out.size()).has_value());
}

void parse_rejects_total_size_mismatch() {
  std::array<std::uint8_t, proto::kMediaHeaderBytes + 5> out{};
  serializeMediaFrame(goldenMediaFrame(), out.data());
  // len field says 5, but we pass fewer bytes
  CHECK(!parseMediaFrame(out.data(), out.size() - 1).has_value());
  CHECK(!parseMediaFrame(out.data(), out.size() + 1).has_value());
}

void unknown_flags_bit_passes_through() {
  std::array<std::uint8_t, proto::kMediaHeaderBytes + 5> out{};
  MediaFrame f = goldenMediaFrame();
  f.flags = 0x80;  // bit7 — undefined, reserved for future
  serializeMediaFrame(f, out.data());
  const auto got = parseMediaFrame(out.data(), out.size());
  CHECK(got.has_value());
  CHECK(got->flags == 0x80);
}

void zero_len_frame_round_trips() {
  MediaFrame f;
  f.epoch = 0xAAAAAAAAu;
  f.seq = 0xBBBBBBBBu;
  f.flags = 0x04;  // bit2 = share-state sentinel
  f.len = 0;
  std::array<std::uint8_t, proto::kMediaHeaderBytes> out{};
  const auto written = serializeMediaFrame(f, out.data());
  CHECK(written == static_cast<std::size_t>(proto::kMediaHeaderBytes));
  const auto got = parseMediaFrame(out.data(), out.size());
  CHECK(got.has_value());
  CHECK(got->epoch == 0xAAAAAAAAu);
  CHECK(got->seq == 0xBBBBBBBBu);
  CHECK(got->flags == 0x04);
  CHECK(got->len == 0);
}

}  // namespace

int main() {
  RUN(serialize_matches_golden_bytes);
  RUN(round_trips_through_parse);
  RUN(parse_rejects_short_buffer);
  RUN(parse_rejects_nonzero_reserved);
  RUN(parse_rejects_len_too_large);
  RUN(parse_rejects_total_size_mismatch);
  RUN(unknown_flags_bit_passes_through);
  RUN(zero_len_frame_round_trips);
  return REPORT();
}
