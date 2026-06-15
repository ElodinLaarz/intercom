#pragma once

#include <cstdint>

namespace intercore {

// Rejects voice/control frames that belong to a stale connection epoch or that
// arrive out of order / duplicated.
//
// Sequence numbers are 32-bit and wrap. We compare with serial-number arithmetic
// (RFC 1982): seq is "newer" than the high-water mark iff (int32_t)(seq - high)
// is positive. The 2^32 boundary is then just another step, not a cliff — v1 had
// multiple 2^31-wrap bugs, and this deletes the class.
//
// Epoch lifecycle (V2_PLAN rule 4): a filter belongs to exactly one epoch. A
// frame from a different epoch is rejected. There is no reset() — construct a
// fresh filter when a new epoch begins; destruction is the reset.
class SeqFilter {
 public:
  explicit SeqFilter(std::uint32_t epoch) : epoch_(epoch) {}

  std::uint32_t epoch() const { return epoch_; }
  bool hasSeen() const { return seen_; }
  std::uint32_t high() const { return high_; }

  // True iff (epoch, seq) is in-epoch and strictly newer than everything seen.
  // Updates the high-water mark on accept; leaves it untouched on reject.
  bool accept(std::uint32_t epoch, std::uint32_t seq) {
    if (epoch != epoch_) return false;
    if (!seen_) {
      seen_ = true;
      high_ = seq;
      return true;
    }
    if (static_cast<std::int32_t>(seq - high_) > 0) {
      high_ = seq;
      return true;
    }
    return false;  // old or duplicate
  }

 private:
  std::uint32_t epoch_;
  bool seen_ = false;
  std::uint32_t high_ = 0;
};

}  // namespace intercore
