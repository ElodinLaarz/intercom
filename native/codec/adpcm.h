#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <limits>

#include "constants.h"

// IMA ADPCM (DVI/IMA variant) — the M1 voice codec (M1_PLAN.md §2).
//
// The encoder keeps a CONTINUOUS predictor across frames for quality; the caller
// snapshots (predSample, stepIndex) into each frame header *before* encoding a
// 320-sample block, so the decoder is self-contained: it seeds from the header
// and decodes that block independent of any other frame. A lost frame therefore
// costs exactly one 20 ms gap, and the next frame self-heals from its own
// header. Encode and decode share identical predictor-update math, so a decoder
// seeded from the snapshot reproduces the encoder's reconstruction exactly.

namespace intercore {

namespace adpcm_detail {

// IMA step-size table (index 0..kVoiceStepIndexMax). Quantiser step per index.
inline constexpr std::array<int, 89> kStepTable = {
    7,     8,     9,     10,    11,    12,    13,    14,    16,    17,    19,
    21,    23,    25,    28,    31,    34,    37,    41,    45,    50,    55,
    60,    66,    73,    80,    88,    97,    107,   118,   130,   143,   157,
    173,   190,   209,   230,   253,   279,   307,   337,   371,   408,   449,
    494,   544,   598,   658,   724,   796,   876,   963,   1060,  1166,  1282,
    1411,  1552,  1707,  1878,  2066,  2272,  2499,  2749,  3024,  3327,  3660,
    4026,  4428,  4871,  5358,  5894,  6484,  7132,  7845,  8630,  9493,  10442,
    11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794,
    32767};

// Index adjustment per 3-bit magnitude (the sign bit is excluded).
inline constexpr std::array<int, 8> kIndexTable = {-1, -1, -1, -1, 2, 4, 6, 8};

// Guard the tables against an editing miscount at compile time, and pin the top
// index to the single-sourced kVoiceStepIndexMax.
static_assert(kStepTable.size() == 89, "IMA step table must have 89 entries");
static_assert(kStepTable[0] == 7 && kStepTable[88] == 32767,
              "IMA step table endpoints");
static_assert(
    kStepTable[static_cast<std::size_t>(proto::kVoiceStepIndexMax)] == 32767,
    "kVoiceStepIndexMax must address the top step");
static_assert(kIndexTable.size() == 8, "IMA index table must have 8 entries");

constexpr int clampStepIndex(int i) {
  if (i < 0) return 0;
  if (i > proto::kVoiceStepIndexMax) return proto::kVoiceStepIndexMax;
  return i;
}

constexpr int clampSample(int s) {
  constexpr int lo = std::numeric_limits<std::int16_t>::min();
  constexpr int hi = std::numeric_limits<std::int16_t>::max();
  if (s < lo) return lo;
  if (s > hi) return hi;
  return s;
}

}  // namespace adpcm_detail

// Continuous IMA predictor state. One per stream direction per epoch: construct
// fresh when the epoch begins, destroy on disconnect — no reset() (rule 4).
struct ImaState {
  std::int16_t predSample = 0;
  std::uint8_t stepIndex = 0;
};

// Encode one PCM16 sample to a 4-bit nibble, advancing `st`.
inline std::uint8_t imaEncodeSample(ImaState& st, std::int16_t sample) {
  const int step = adpcm_detail::kStepTable[st.stepIndex];
  int diff = static_cast<int>(sample) - st.predSample;
  int nibble = 0;
  if (diff < 0) {
    nibble = 8;
    diff = -diff;
  }
  int vpdiff = step >> 3;
  if (diff >= step) {
    nibble |= 4;
    diff -= step;
    vpdiff += step;
  }
  if (diff >= (step >> 1)) {
    nibble |= 2;
    diff -= step >> 1;
    vpdiff += step >> 1;
  }
  if (diff >= (step >> 2)) {
    nibble |= 1;
    vpdiff += step >> 2;
  }
  const int pred = st.predSample + ((nibble & 8) ? -vpdiff : vpdiff);
  st.predSample = static_cast<std::int16_t>(adpcm_detail::clampSample(pred));
  st.stepIndex = static_cast<std::uint8_t>(adpcm_detail::clampStepIndex(
      st.stepIndex + adpcm_detail::kIndexTable[nibble & 7]));
  return static_cast<std::uint8_t>(nibble & 0x0F);
}

// Decode one 4-bit nibble to a PCM16 sample, advancing `st`.
inline std::int16_t imaDecodeSample(ImaState& st, std::uint8_t nibble) {
  const int step = adpcm_detail::kStepTable[st.stepIndex];
  int vpdiff = step >> 3;
  if (nibble & 4) vpdiff += step;
  if (nibble & 2) vpdiff += step >> 1;
  if (nibble & 1) vpdiff += step >> 2;
  const int pred = st.predSample + ((nibble & 8) ? -vpdiff : vpdiff);
  st.predSample = static_cast<std::int16_t>(adpcm_detail::clampSample(pred));
  st.stepIndex = static_cast<std::uint8_t>(adpcm_detail::clampStepIndex(
      st.stepIndex + adpcm_detail::kIndexTable[nibble & 7]));
  return st.predSample;
}

// Encode kVoiceFrameSamples (320) PCM16 samples into kVoiceAdpcmBytes (160)
// bytes — sample 2i in the low nibble, sample 2i+1 in the high nibble. `st` is
// the CONTINUOUS encoder state (advanced); snapshot it before calling to fill
// the frame header.
inline void imaEncodeBlock(ImaState& st, const std::int16_t* pcm,
                           std::uint8_t* out) {
  for (int i = 0; i < proto::kVoiceAdpcmBytes; ++i) {
    const std::uint8_t lo = imaEncodeSample(st, pcm[2 * i]);
    const std::uint8_t hi = imaEncodeSample(st, pcm[2 * i + 1]);
    out[i] = static_cast<std::uint8_t>((hi << 4) | lo);
  }
}

// Decode kVoiceAdpcmBytes (160) bytes into kVoiceFrameSamples (320) PCM16
// samples. `seed` is taken BY VALUE (the per-frame header snapshot), so decoding
// is independent of any other frame — self-contained (M1_PLAN.md §2).
inline void imaDecodeBlock(ImaState seed, const std::uint8_t* in,
                           std::int16_t* pcm) {
  for (int i = 0; i < proto::kVoiceAdpcmBytes; ++i) {
    pcm[2 * i] = imaDecodeSample(seed, static_cast<std::uint8_t>(in[i] & 0x0F));
    pcm[2 * i + 1] =
        imaDecodeSample(seed, static_cast<std::uint8_t>((in[i] >> 4) & 0x0F));
  }
}

}  // namespace intercore
