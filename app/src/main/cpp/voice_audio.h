#pragma once

#include <array>
#include <cstddef>
#include <cstdint>

#include "constants.h"

namespace intercore::audio {

using FrameBytes = std::array<std::uint8_t, proto::kVoiceFrameBytes>;

class TxEngine {
 public:
  explicit TxEngine(std::uint32_t epoch, std::uint32_t initialSeq = 0);
  ~TxEngine();

  bool start();
  void stop();
  // Pop up to maxFrames freshest *consecutive* frames into out (count*frameBytes),
  // dropping any older backlog first. Returns the frame count written.
  int takeBundle(std::uint8_t* out, int maxFrames, int timeoutMs);
  std::uint32_t epoch() const;
  std::uint32_t nextSequence() const;

  TxEngine(const TxEngine&) = delete;
  TxEngine& operator=(const TxEngine&) = delete;

 private:
  struct Impl;
  Impl* impl_;
};

class RxEngine {
 public:
  explicit RxEngine(std::uint32_t epoch);
  ~RxEngine();

  bool start();
  void stop();
  bool pushFrame(const std::uint8_t* data, std::size_t len);

  RxEngine(const RxEngine&) = delete;
  RxEngine& operator=(const RxEngine&) = delete;

 private:
  struct Impl;
  Impl* impl_;
};

}  // namespace intercore::audio
