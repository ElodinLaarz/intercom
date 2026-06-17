#include "voice_frame.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <optional>

#include "check.h"
#include "constants.h"

using intercore::parseVoiceFrame;
using intercore::serializeVoiceFrame;
using intercore::VoiceFrame;
namespace proto = intercore::proto;

namespace {

// A fixed golden frame. The SAME vector is asserted byte-for-byte in the Kotlin
// test (VoiceFrameTest), so the two codecs cannot drift on the wire layout.
VoiceFrame goldenFrame() {
  VoiceFrame f;
  f.epoch = 0x01020304u;
  f.seq = 0x05060708u;
  f.predSample = static_cast<std::int16_t>(-2);                        // 0xFFFE
  f.stepIndex = static_cast<std::uint8_t>(proto::kVoiceStepIndexMax);  // 88, 0x58
  for (int i = 0; i < proto::kVoiceAdpcmBytes; ++i) {
    f.adpcm[static_cast<std::size_t>(i)] = static_cast<std::uint8_t>(i);  // 0..79
  }
  return f;
}

void serialize_matches_golden_bytes() {
  std::array<std::uint8_t, proto::kVoiceFrameBytes> out{};
  serializeVoiceFrame(goldenFrame(), out.data());
  CHECK(out[0] == 0x04 && out[1] == 0x03 && out[2] == 0x02 && out[3] == 0x01);
  CHECK(out[4] == 0x08 && out[5] == 0x07 && out[6] == 0x06 && out[7] == 0x05);
  CHECK(out[8] == 0xFE && out[9] == 0xFF);  // predSample -2 LE
  CHECK(out[10] == 0x58);                   // stepIndex 88
  CHECK(out[11] == 0x00);                   // reserved
  CHECK(out[12] == 0x00 && out[13] == 0x01);                // adpcm[0..1]
  CHECK(out[proto::kVoiceFrameBytes - 1] == 0x4F);          // adpcm[79] == 79
}

void round_trips_through_parse() {
  std::array<std::uint8_t, proto::kVoiceFrameBytes> out{};
  const VoiceFrame f = goldenFrame();
  serializeVoiceFrame(f, out.data());
  const auto got = parseVoiceFrame(out.data(), out.size());
  CHECK(got.has_value());
  CHECK(got->epoch == f.epoch);
  CHECK(got->seq == f.seq);
  CHECK(got->predSample == f.predSample);
  CHECK(got->stepIndex == f.stepIndex);
  CHECK(got->adpcm == f.adpcm);
}

void parse_rejects_wrong_length() {
  std::array<std::uint8_t, proto::kVoiceFrameBytes> out{};
  serializeVoiceFrame(goldenFrame(), out.data());
  CHECK(!parseVoiceFrame(out.data(), out.size() - 1).has_value());
  CHECK(!parseVoiceFrame(out.data(), out.size() + 1).has_value());
  CHECK(!parseVoiceFrame(out.data(), 0).has_value());
}

void parse_bounds_checks_step_index() {
  std::array<std::uint8_t, proto::kVoiceFrameBytes> out{};
  serializeVoiceFrame(goldenFrame(), out.data());
  out[proto::kVoiceOffStepIndex] =
      static_cast<std::uint8_t>(proto::kVoiceStepIndexMax);  // 88 = max valid
  CHECK(parseVoiceFrame(out.data(), out.size()).has_value());
  out[proto::kVoiceOffStepIndex] =
      static_cast<std::uint8_t>(proto::kVoiceStepIndexMax + 1);  // 89 — drop
  CHECK(!parseVoiceFrame(out.data(), out.size()).has_value());
  out[proto::kVoiceOffStepIndex] = 0xFF;  // 255 — drop
  CHECK(!parseVoiceFrame(out.data(), out.size()).has_value());
}

void parse_rejects_nonzero_reserved() {
  std::array<std::uint8_t, proto::kVoiceFrameBytes> out{};
  serializeVoiceFrame(goldenFrame(), out.data());
  out[proto::kVoiceOffReserved] = 0x01;
  CHECK(!parseVoiceFrame(out.data(), out.size()).has_value());
}

// epoch/seq pass through untouched across the full u32 range (the SeqFilter, not
// the parser, decides freshness), and predSample sign round-trips exactly.
void header_fields_survive_full_range() {
  VoiceFrame f = goldenFrame();
  f.epoch = 0xFFFFFFFFu;
  f.seq = 0x80000000u;
  f.predSample = std::numeric_limits<std::int16_t>::min();  // -32768 = 0x8000
  std::array<std::uint8_t, proto::kVoiceFrameBytes> out{};
  serializeVoiceFrame(f, out.data());
  CHECK(out[8] == 0x00 && out[9] == 0x80);  // -32768 LE
  const auto got = parseVoiceFrame(out.data(), out.size());
  CHECK(got.has_value());
  CHECK(got->epoch == 0xFFFFFFFFu);
  CHECK(got->seq == 0x80000000u);
  CHECK(got->predSample == std::numeric_limits<std::int16_t>::min());
}

}  // namespace

int main() {
  RUN(serialize_matches_golden_bytes);
  RUN(round_trips_through_parse);
  RUN(parse_rejects_wrong_length);
  RUN(parse_bounds_checks_step_index);
  RUN(parse_rejects_nonzero_reserved);
  RUN(header_fields_survive_full_range);
  return REPORT();
}
