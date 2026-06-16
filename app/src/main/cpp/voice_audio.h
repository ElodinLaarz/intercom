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
  bool takeFrame(FrameBytes& out, int timeoutMs);
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
