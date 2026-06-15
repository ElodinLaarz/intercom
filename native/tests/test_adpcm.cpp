#include "adpcm.h"

#include <array>
#include <cmath>
#include <cstdint>
#include <limits>

#include "check.h"
#include "constants.h"

using intercore::ImaState;
using intercore::imaDecodeBlock;
using intercore::imaEncodeBlock;
using intercore::imaEncodeSample;
namespace proto = intercore::proto;

namespace {

constexpr int kSamples = proto::kVoiceFrameSamples;  // 320
constexpr int kBytes = proto::kVoiceAdpcmBytes;      // 160
constexpr double kPi = 3.14159265358979323846;

// IMA is lossy, but encode and decode share predictor math, so re-encoding the
// decoder's OWN reconstruction from the same seed must reproduce the identical
// bytes — an exact invariant a desynced codec would break.
void encode_decode_is_idempotent() {
  std::array<std::int16_t, kSamples> pcm{};
  for (int i = 0; i < kSamples; ++i) {
    pcm[i] = static_cast<std::int16_t>(((i * 173) % 9001) - 4500);  // jagged
  }
  ImaState enc{};
  const ImaState seed = enc;  // snapshot taken before the block
  std::array<std::uint8_t, kBytes> bytes1{};
  imaEncodeBlock(enc, pcm.data(), bytes1.data());

  std::array<std::int16_t, kSamples> recon{};
  imaDecodeBlock(seed, bytes1.data(), recon.data());

  ImaState enc2 = seed;
  std::array<std::uint8_t, kBytes> bytes2{};
  imaEncodeBlock(enc2, recon.data(), bytes2.data());
  CHECK(bytes1 == bytes2);

  std::array<std::int16_t, kSamples> recon2{};
  imaDecodeBlock(seed, bytes2.data(), recon2.data());
  CHECK(recon == recon2);
}

// decode seeds from the header BY VALUE, so a block decodes the same regardless
// of what the decoder did before — the self-contained-frame property
// (M1_PLAN §2): a lost frame cannot corrupt the next one.
void decode_is_self_contained() {
  std::array<std::int16_t, kSamples> pcm{};
  for (int i = 0; i < kSamples; ++i) {
    pcm[i] = static_cast<std::int16_t>((i % 50) * 600 - 15000);
  }
  ImaState enc{};
  enc.predSample = 1234;
  enc.stepIndex = 20;
  const ImaState seed = enc;
  std::array<std::uint8_t, kBytes> bytes{};
  imaEncodeBlock(enc, pcm.data(), bytes.data());

  std::array<std::int16_t, kSamples> out1{};
  imaDecodeBlock(seed, bytes.data(), out1.data());

  // Run an unrelated decode through a separate state, then decode (seed,bytes)
  // again — it must be byte-identical, proving decode carries no history.
  std::array<std::int16_t, kSamples> junk{};
  ImaState other{};
  other.predSample = -9000;
  other.stepIndex = 70;
  imaDecodeBlock(other, bytes.data(), junk.data());

  std::array<std::int16_t, kSamples> out2{};
  imaDecodeBlock(seed, bytes.data(), out2.data());
  CHECK(out1 == out2);
}

// At step index 0 (step 7, step>>3 == 0) a zero nibble is a zero delta and the
// index decrements toward its floor, so an all-zero payload holds output exactly
// at the seeded predictor. Confirms predSample is the predictor BASIS the frame
// self-heals from, and that the lower index clamp does not underflow.
void zero_payload_holds_predictor() {
  std::array<std::uint8_t, kBytes> zero{};
  ImaState seed{};
  seed.predSample = 4321;
  seed.stepIndex = 0;
  std::array<std::int16_t, kSamples> out{};
  imaDecodeBlock(seed, zero.data(), out.data());
  for (int i = 0; i < kSamples; ++i) CHECK(out[i] == 4321);
}

// 160 bytes decode to EXACTLY 320 samples and predSample is pre-roll state: the
// first output is predict(predSample, nibble0), not the raw predSample — which
// is why it is 320 samples, not 321. nibble 7 at index 0 yields +11.
void first_sample_is_predicted_not_raw() {
  std::array<std::uint8_t, kBytes> bytes{};
  bytes[0] = 0x07;  // low nibble of byte 0 = sample 0
  ImaState seed{};  // predSample 0, stepIndex 0
  std::array<std::int16_t, kSamples> out{};
  imaDecodeBlock(seed, bytes.data(), out.data());
  CHECK(out[0] == 11);
  CHECK(out[0] != seed.predSample);
}

// A maximal-swing signal ramps the step index to the top; it must saturate at
// kVoiceStepIndexMax and never index past the 89-entry table (landmine #12 in
// the codec — an OOB table read would be undefined).
void step_index_saturates_at_top() {
  ImaState st{};
  for (int n = 0; n < 4000; ++n) {
    const std::int16_t s = (n & 1) ? std::numeric_limits<std::int16_t>::max()
                                   : std::numeric_limits<std::int16_t>::min();
    imaEncodeSample(st, s);
    CHECK(st.stepIndex <= proto::kVoiceStepIndexMax);
  }
  CHECK(st.stepIndex == proto::kVoiceStepIndexMax);
}

// A continuous encoder + per-frame snapshot decode (the real pipeline) clears
// well over 10 dB SNR on a tone — only a broken codec fails. It is still lossy.
void sine_round_trip_has_sane_snr() {
  constexpr int kBlocks = 8;
  const double w = 2.0 * kPi * 1000.0 / proto::kVoiceSampleRateHz;
  ImaState enc{};
  double sigPow = 0.0;
  double errPow = 0.0;
  for (int b = 0; b < kBlocks; ++b) {
    std::array<std::int16_t, kSamples> pcm{};
    for (int i = 0; i < kSamples; ++i) {
      const int n = b * kSamples + i;
      pcm[i] = static_cast<std::int16_t>(std::lround(8000.0 * std::sin(w * n)));
    }
    const ImaState seed = enc;  // snapshot per frame
    std::array<std::uint8_t, kBytes> bytes{};
    imaEncodeBlock(enc, pcm.data(), bytes.data());
    std::array<std::int16_t, kSamples> out{};
    imaDecodeBlock(seed, bytes.data(), out.data());
    for (int i = 0; i < kSamples; ++i) {
      const double s = pcm[i];
      const double e = s - out[i];
      sigPow += s * s;
      errPow += e * e;
    }
  }
  CHECK(errPow > 0.0);            // genuinely lossy
  CHECK(sigPow > errPow * 10.0);  // SNR > 10 dB
}

}  // namespace

int main() {
  RUN(encode_decode_is_idempotent);
  RUN(decode_is_self_contained);
  RUN(zero_payload_holds_predictor);
  RUN(first_sample_is_predicted_not_raw);
  RUN(step_index_saturates_at_top);
  RUN(sine_round_trip_has_sane_snr);
  return REPORT();
}
