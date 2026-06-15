#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace intercore {

// Fixed-capacity FIFO of audio samples. Intended for single-producer /
// single-consumer use on the M1 voice path, but this implementation is plain
// single-threaded; a lock-free variant can replace it behind the same interface
// if a measured need appears (no speculative concurrency before that).
//
// On overflow the OLDEST sample is dropped (the producer never blocks) and
// overwriteCount() is bumped. That counter is voice telemetry — the DIAG line
// in V2_PLAN s4.2 reports it so a backlog shows up in the smoke output.
class RingBuffer {
 public:
  explicit RingBuffer(std::size_t capacity)
      : buf_(capacity ? capacity : 1), cap_(capacity ? capacity : 1) {}

  std::size_t capacity() const { return cap_; }
  std::size_t size() const { return size_; }
  bool empty() const { return size_ == 0; }
  bool full() const { return size_ == cap_; }
  std::uint64_t overwriteCount() const { return overwrites_; }

  // Push one sample. If full, drops the oldest (advances tail) and counts it.
  void push(std::int16_t s) {
    if (size_ == cap_) {
      tail_ = (tail_ + 1) % cap_;  // drop oldest
      ++overwrites_;
      --size_;
    }
    buf_[head_] = s;
    head_ = (head_ + 1) % cap_;
    ++size_;
  }

  // Pop the oldest sample into out. Returns false if empty.
  bool pop(std::int16_t& out) {
    if (size_ == 0) return false;
    out = buf_[tail_];
    tail_ = (tail_ + 1) % cap_;
    --size_;
    return true;
  }

 private:
  std::vector<std::int16_t> buf_;
  std::size_t cap_;
  std::size_t head_ = 0;  // next write
  std::size_t tail_ = 0;  // next read
  std::size_t size_ = 0;
  std::uint64_t overwrites_ = 0;
};

}  // namespace intercore
