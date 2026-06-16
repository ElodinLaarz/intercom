#include "voice_audio.h"

#include <android/log.h>
#include <oboe/Oboe.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>

#include "adpcm.h"
#include "seq_filter.h"
#include "voice_frame.h"

#define LOG_TAG "INTERCOM-native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace intercore::audio {
namespace {

constexpr int kJitterTargetFrames = 3;
constexpr std::uint32_t kDiagEveryFrames = 100;  // 2 s at 50 fps.
constexpr std::uint32_t kOutputWatchEveryFrames = 50;
constexpr std::size_t kQueueSlots = 17;  // 16 usable SPSC slots.
constexpr std::uint32_t kMaxSilenceFillFrames = 3;

template <typename T, std::size_t Capacity>
class SpscQueue {
 public:
  bool push(const T& item) {
    const std::size_t head = head_.load(std::memory_order_relaxed);
    const std::size_t next = (head + 1) % Capacity;
    if (next == tail_.load(std::memory_order_acquire)) {
      dropped_.fetch_add(1, std::memory_order_relaxed);
      return false;
    }
    items_[head] = item;
    head_.store(next, std::memory_order_release);
    return true;
  }

  bool pop(T& out) {
    const std::size_t tail = tail_.load(std::memory_order_relaxed);
    if (tail == head_.load(std::memory_order_acquire)) return false;
    out = items_[tail];
    tail_.store((tail + 1) % Capacity, std::memory_order_release);
    return true;
  }

  std::size_t depth() const {
    const std::size_t head = head_.load(std::memory_order_acquire);
    const std::size_t tail = tail_.load(std::memory_order_acquire);
    if (head >= tail) return head - tail;
    return Capacity - tail + head;
  }

  std::uint64_t dropped() const {
    return dropped_.load(std::memory_order_relaxed);
  }

 private:
  std::array<T, Capacity> items_{};
  std::atomic<std::size_t> head_{0};
  std::atomic<std::size_t> tail_{0};
  std::atomic<std::uint64_t> dropped_{0};
};

struct PlayoutFrame {
  bool silence = false;
  VoiceFrame frame{};

  static PlayoutFrame silenceFrame() {
    PlayoutFrame item;
    item.silence = true;
    return item;
  }

  static PlayoutFrame voiceFrame(const VoiceFrame& frame) {
    PlayoutFrame item;
    item.frame = frame;
    return item;
  }
};

int peakAbs(const std::int16_t* samples, int count) {
  int peak = 0;
  for (int i = 0; i < count; ++i) {
    const int sample = samples[i];
    const int mag =
        sample == std::numeric_limits<std::int16_t>::min() ? 32768
                                                           : std::abs(sample);
    peak = std::max(peak, mag);
  }
  return peak;
}

struct TxPacket {
  FrameBytes bytes{};
  std::uint32_t seq = 0;
  int peak = 0;
};

}  // namespace

struct TxEngine::Impl {
  class Callback : public oboe::AudioStreamDataCallback {
   public:
    explicit Callback(Impl& engine) : engine_(engine) {}

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* audioData,
                                          int32_t numFrames) override;

   private:
    Impl& engine_;
  };

  Impl(std::uint32_t epoch, std::uint32_t initialSeq)
      : epoch(epoch), nextSeq(initialSeq), callback(*this) {}

  bool start();
  void stop();
  bool takeFrame(FrameBytes& out, int timeoutMs);
  std::uint32_t nextSequence() const;
  oboe::DataCallbackResult onAudioReady(void* audioData, int32_t numFrames);

  bool popLatestPacket(TxPacket& packet);
  void logPacket(const TxPacket& packet) const;
  void encodeSamples(const std::int16_t* samples, int32_t numFrames);
  void emitFrame();

  const std::uint32_t epoch;
  std::atomic<bool> stopping{false};
  std::atomic<std::uint32_t> nextSeq{0};
  std::atomic<std::uint64_t> lateDrops{0};
  ImaState encoder{};
  std::array<std::int16_t, proto::kVoiceFrameSamples> pcm{};
  int pcmFill = 0;
  SpscQueue<TxPacket, kQueueSlots> queue;
  std::mutex waitMutex;
  std::condition_variable frameReady;
  std::shared_ptr<oboe::AudioStream> stream;
  Callback callback;
};

struct RxEngine::Impl {
  class Callback : public oboe::AudioStreamDataCallback {
   public:
    explicit Callback(Impl& engine) : engine_(engine) {}

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* audioData,
                                          int32_t numFrames) override;

   private:
    Impl& engine_;
  };

  explicit Impl(std::uint32_t epoch) : seqFilter(epoch), callback(*this) {}

  bool start();
  void stop();
  bool pushFrame(const std::uint8_t* data, std::size_t len);
  oboe::DataCallbackResult onAudioReady(void* audioData, int32_t numFrames);

  bool openOutputStreamLocked();
  bool startOutputStreamLocked();
  void ensureOutputStarted();
  void watchOutputProgress(std::uint64_t acceptedCount);
  void restartOutputStream();
  void render(std::int16_t* out, int32_t numFrames);
  void prepareNextPcmFrame();
  void trimLateFrames();
  void fillCurrentWithSilence();

  SeqFilter seqFilter;
  std::atomic<bool> stopping{false};
  std::atomic<std::uint64_t> accepted{0};
  std::atomic<std::uint64_t> rejected{0};
  std::atomic<std::uint64_t> lost{0};
  std::atomic<std::uint64_t> lateDrops{0};
  std::atomic<std::uint64_t> underruns{0};
  std::atomic<std::uint64_t> decodedFrames{0};
  std::atomic<int> decodedPeak{0};
  SpscQueue<PlayoutFrame, kQueueSlots> jitter;
  std::array<std::int16_t, proto::kVoiceFrameSamples> currentPcm{};
  int currentIndex = proto::kVoiceFrameSamples;
  bool playoutStarted = false;
  std::uint64_t lastWatchDecoded = 0;
  int stalledWatchCount = 0;
  std::mutex streamMutex;
  std::shared_ptr<oboe::AudioStream> stream;
  bool streamStarted = false;
  Callback callback;
};

oboe::DataCallbackResult TxEngine::Impl::Callback::onAudioReady(
    oboe::AudioStream*, void* audioData, int32_t numFrames) {
  return engine_.onAudioReady(audioData, numFrames);
}

oboe::DataCallbackResult RxEngine::Impl::Callback::onAudioReady(
    oboe::AudioStream*, void* audioData, int32_t numFrames) {
  return engine_.onAudioReady(audioData, numFrames);
}

namespace {

bool startStream(const std::shared_ptr<oboe::AudioStream>& stream,
                 const char* label) {
  const oboe::Result result = stream->requestStart();
  if (result == oboe::Result::OK) return true;

  LOGW("AUDIO %s requestStart failed result=%d", label,
       static_cast<int>(result));
  return false;
}

void stopStream(std::shared_ptr<oboe::AudioStream>& stream) {
  if (!stream) return;
  stream->requestStop();
  stream->close();
  stream.reset();
}

}  // namespace

TxEngine::TxEngine(std::uint32_t epoch, std::uint32_t initialSeq)
    : impl_(new Impl(epoch, initialSeq)) {}

TxEngine::~TxEngine() {
  stop();
  delete impl_;
}

bool TxEngine::start() {
  return impl_->start();
}

void TxEngine::stop() {
  impl_->stop();
}

bool TxEngine::takeFrame(FrameBytes& out, int timeoutMs) {
  return impl_->takeFrame(out, timeoutMs);
}

std::uint32_t TxEngine::epoch() const {
  return impl_->epoch;
}

std::uint32_t TxEngine::nextSequence() const {
  return impl_->nextSequence();
}

std::uint32_t TxEngine::Impl::nextSequence() const {
  return nextSeq.load(std::memory_order_relaxed);
}

bool TxEngine::Impl::start() {
  stopping.store(false, std::memory_order_release);

  oboe::AudioStreamBuilder builder;
  builder.setDirection(oboe::Direction::Input);
  builder.setSharingMode(oboe::SharingMode::Shared);
  builder.setPerformanceMode(oboe::PerformanceMode::None);
  builder.setInputPreset(oboe::InputPreset::VoiceCommunication);
  builder.setFormat(oboe::AudioFormat::I16);
  builder.setChannelCount(oboe::ChannelCount::Mono);
  builder.setSampleRate(proto::kVoiceSampleRateHz);
  builder.setDataCallback(&callback);

  const oboe::Result result = builder.openStream(stream);
  if (result != oboe::Result::OK || !stream) {
    LOGW("AUDIO input open failed result=%d", static_cast<int>(result));
    return false;
  }
  LOGI("AUDIO input open epoch=%u rate=%d channels=%d sharing=%d", epoch,
       stream->getSampleRate(), stream->getChannelCount(),
       static_cast<int>(stream->getSharingMode()));

  if (startStream(stream, "input")) return true;

  stopStream(stream);
  return false;
}

void TxEngine::Impl::stop() {
  stopping.store(true, std::memory_order_release);
  frameReady.notify_all();
  stopStream(stream);
}

bool TxEngine::Impl::takeFrame(FrameBytes& out, int timeoutMs) {
  TxPacket packet;
  if (popLatestPacket(packet)) {
    out = packet.bytes;
    logPacket(packet);
    return true;
  }
  if (stopping.load(std::memory_order_acquire)) return false;

  std::unique_lock<std::mutex> lock(waitMutex);
  frameReady.wait_for(lock, std::chrono::milliseconds(timeoutMs), [&] {
    return stopping.load(std::memory_order_acquire) || queue.depth() > 0;
  });
  if (stopping.load(std::memory_order_acquire)) return false;
  if (!popLatestPacket(packet)) return false;

  out = packet.bytes;
  logPacket(packet);
  return true;
}

bool TxEngine::Impl::popLatestPacket(TxPacket& packet) {
  if (!queue.pop(packet)) return false;

  std::uint64_t skipped = 0;
  TxPacket newer;
  while (queue.pop(newer)) {
    packet = newer;
    skipped += 1;
  }
  if (skipped > 0) {
    lateDrops.fetch_add(skipped, std::memory_order_relaxed);
  }
  return true;
}

void TxEngine::Impl::logPacket(const TxPacket& packet) const {
  if (packet.seq % kDiagEveryFrames == 0) {
    LOGI(
        "DIAG epoch=%u txSeq=%u txPeak=%d qDepth=%zu drops=%llu lateDrops=%llu",
        epoch, packet.seq, packet.peak, queue.depth(),
        static_cast<unsigned long long>(queue.dropped()),
        static_cast<unsigned long long>(
            lateDrops.load(std::memory_order_relaxed)));
  }
}

oboe::DataCallbackResult TxEngine::Impl::onAudioReady(void* audioData,
                                                      int32_t numFrames) {
  if (stopping.load(std::memory_order_acquire)) {
    return oboe::DataCallbackResult::Stop;
  }
  if (audioData == nullptr || numFrames <= 0) {
    return oboe::DataCallbackResult::Continue;
  }
  encodeSamples(static_cast<const std::int16_t*>(audioData), numFrames);
  return oboe::DataCallbackResult::Continue;
}

void TxEngine::Impl::encodeSamples(const std::int16_t* samples,
                                   int32_t numFrames) {
  for (int32_t i = 0; i < numFrames; ++i) {
    pcm[static_cast<std::size_t>(pcmFill)] = samples[i];
    pcmFill += 1;
    if (pcmFill != proto::kVoiceFrameSamples) continue;

    emitFrame();
    pcmFill = 0;
  }
}

void TxEngine::Impl::emitFrame() {
  VoiceFrame frame;
  frame.epoch = epoch;
  frame.seq = nextSeq.fetch_add(1, std::memory_order_relaxed);
  frame.predSample = encoder.predSample;
  frame.stepIndex = encoder.stepIndex;
  const int peak = peakAbs(pcm.data(), proto::kVoiceFrameSamples);
  imaEncodeBlock(encoder, pcm.data(), frame.adpcm.data());

  TxPacket packet;
  packet.seq = frame.seq;
  packet.peak = peak;
  serializeVoiceFrame(frame, packet.bytes.data());
  if (queue.push(packet)) {
    frameReady.notify_one();
  }
}

RxEngine::RxEngine(std::uint32_t epoch) : impl_(new Impl(epoch)) {}

RxEngine::~RxEngine() {
  stop();
  delete impl_;
}

bool RxEngine::start() {
  return impl_->start();
}

void RxEngine::stop() {
  impl_->stop();
}

bool RxEngine::pushFrame(const std::uint8_t* data, std::size_t len) {
  return impl_->pushFrame(data, len);
}

bool RxEngine::Impl::openOutputStreamLocked() {
  oboe::AudioStreamBuilder builder;
  builder.setDirection(oboe::Direction::Output);
  builder.setSharingMode(oboe::SharingMode::Shared);
  builder.setPerformanceMode(oboe::PerformanceMode::None);
  builder.setUsage(oboe::Usage::VoiceCommunication);
  builder.setContentType(oboe::ContentType::Speech);
  builder.setFormat(oboe::AudioFormat::I16);
  builder.setChannelCount(oboe::ChannelCount::Mono);
  builder.setSampleRate(proto::kVoiceSampleRateHz);
  builder.setDataCallback(&callback);

  const oboe::Result result = builder.openStream(stream);
  if (result != oboe::Result::OK || !stream) {
    LOGW("AUDIO output open failed result=%d", static_cast<int>(result));
    return false;
  }
  LOGI("AUDIO output open epoch=%u rate=%d channels=%d sharing=%d perf=%d",
       seqFilter.epoch(), stream->getSampleRate(), stream->getChannelCount(),
       static_cast<int>(stream->getSharingMode()),
       static_cast<int>(stream->getPerformanceMode()));
  return true;
}

bool RxEngine::Impl::startOutputStreamLocked() {
  if (streamStarted) return true;
  if (!stream) return false;
  if (!startStream(stream, "output")) return false;

  streamStarted = true;
  LOGI("AUDIO output start epoch=%u buffered=%zu", seqFilter.epoch(),
       jitter.depth());
  return true;
}

bool RxEngine::Impl::start() {
  stopping.store(false, std::memory_order_release);
  std::lock_guard<std::mutex> lock(streamMutex);
  return openOutputStreamLocked();
}

void RxEngine::Impl::stop() {
  stopping.store(true, std::memory_order_release);
  std::lock_guard<std::mutex> lock(streamMutex);
  streamStarted = false;
  stopStream(stream);
}

bool RxEngine::Impl::pushFrame(const std::uint8_t* data, std::size_t len) {
  const auto parsed = parseVoiceFrame(data, len);
  if (!parsed.has_value()) {
    rejected.fetch_add(1, std::memory_order_relaxed);
    return false;
  }

  const bool hadSeen = seqFilter.hasSeen();
  const std::uint32_t previous = seqFilter.high();
  if (!seqFilter.accept(parsed->epoch, parsed->seq)) {
    rejected.fetch_add(1, std::memory_order_relaxed);
    return false;
  }

  if (hadSeen) {
    const std::uint32_t delta = parsed->seq - previous;
    if (delta > 1 && static_cast<std::int32_t>(delta) > 0) {
      const std::uint32_t missing = delta - 1;
      lost.fetch_add(missing, std::memory_order_relaxed);
      const std::uint32_t fill = std::min(missing, kMaxSilenceFillFrames);
      for (std::uint32_t i = 0; i < fill; ++i) {
        jitter.push(PlayoutFrame::silenceFrame());
      }
    }
  }

  const std::uint64_t acceptedCount =
      accepted.fetch_add(1, std::memory_order_relaxed) + 1;
  jitter.push(PlayoutFrame::voiceFrame(*parsed));
  ensureOutputStarted();
  watchOutputProgress(acceptedCount);
  if (acceptedCount % kDiagEveryFrames == 0) {
    LOGI(
        "DIAG epoch=%u rxSeq=%u lost=%llu lateDrops=%llu qDepth=%zu "
        "jitterMs=%zu underruns=%llu rejected=%llu rxPeak=%d decoded=%llu",
        seqFilter.epoch(), parsed->seq,
        static_cast<unsigned long long>(lost.load(std::memory_order_relaxed)),
        static_cast<unsigned long long>(
            lateDrops.load(std::memory_order_relaxed)),
        jitter.depth(), jitter.depth() * proto::kVoiceFrameMs,
        static_cast<unsigned long long>(
            underruns.load(std::memory_order_relaxed)),
        static_cast<unsigned long long>(
            rejected.load(std::memory_order_relaxed)),
        decodedPeak.load(std::memory_order_relaxed),
        static_cast<unsigned long long>(
            decodedFrames.load(std::memory_order_relaxed)));
  }
  return true;
}

void RxEngine::Impl::ensureOutputStarted() {
  if (jitter.depth() < kJitterTargetFrames) return;

  std::lock_guard<std::mutex> lock(streamMutex);
  startOutputStreamLocked();
}

void RxEngine::Impl::watchOutputProgress(std::uint64_t acceptedCount) {
  if (acceptedCount % kOutputWatchEveryFrames != 0) return;
  if (jitter.depth() < kJitterTargetFrames) {
    stalledWatchCount = 0;
    lastWatchDecoded = decodedFrames.load(std::memory_order_relaxed);
    return;
  }

  const std::uint64_t decoded =
      decodedFrames.load(std::memory_order_relaxed);
  if (decoded != lastWatchDecoded) {
    stalledWatchCount = 0;
    lastWatchDecoded = decoded;
    return;
  }

  stalledWatchCount += 1;
  if (stalledWatchCount < 2) return;

  LOGW("AUDIO output stalled epoch=%u decoded=%llu qDepth=%zu; restarting",
       seqFilter.epoch(), static_cast<unsigned long long>(decoded),
       jitter.depth());
  restartOutputStream();
  stalledWatchCount = 0;
  lastWatchDecoded = decodedFrames.load(std::memory_order_relaxed);
}

void RxEngine::Impl::restartOutputStream() {
  std::lock_guard<std::mutex> lock(streamMutex);
  streamStarted = false;
  stopStream(stream);
  if (!openOutputStreamLocked()) return;
  startOutputStreamLocked();
}

oboe::DataCallbackResult RxEngine::Impl::onAudioReady(void* audioData,
                                                      int32_t numFrames) {
  if (stopping.load(std::memory_order_acquire)) {
    return oboe::DataCallbackResult::Stop;
  }
  if (audioData == nullptr || numFrames <= 0) {
    return oboe::DataCallbackResult::Continue;
  }
  render(static_cast<std::int16_t*>(audioData), numFrames);
  return oboe::DataCallbackResult::Continue;
}

void RxEngine::Impl::render(std::int16_t* out, int32_t numFrames) {
  int32_t written = 0;
  while (written < numFrames) {
    if (currentIndex >= proto::kVoiceFrameSamples) prepareNextPcmFrame();

    const int available = proto::kVoiceFrameSamples - currentIndex;
    const int count = std::min<int>(available, numFrames - written);
    std::memcpy(out + written, currentPcm.data() + currentIndex,
                static_cast<std::size_t>(count) * sizeof(std::int16_t));
    currentIndex += count;
    written += count;
  }
}

void RxEngine::Impl::prepareNextPcmFrame() {
  if (!playoutStarted) {
    if (jitter.depth() < kJitterTargetFrames) {
      underruns.fetch_add(1, std::memory_order_relaxed);
      fillCurrentWithSilence();
      return;
    }
    playoutStarted = true;
  }

  trimLateFrames();

  PlayoutFrame item;
  if (!jitter.pop(item)) {
    playoutStarted = false;
    underruns.fetch_add(1, std::memory_order_relaxed);
    fillCurrentWithSilence();
    return;
  }

  if (item.silence) {
    fillCurrentWithSilence();
    return;
  }

  ImaState seed;
  seed.predSample = item.frame.predSample;
  seed.stepIndex = item.frame.stepIndex;
  imaDecodeBlock(seed, item.frame.adpcm.data(), currentPcm.data());
  decodedPeak.store(peakAbs(currentPcm.data(), proto::kVoiceFrameSamples),
                    std::memory_order_relaxed);
  decodedFrames.fetch_add(1, std::memory_order_relaxed);
  currentIndex = 0;
}

void RxEngine::Impl::trimLateFrames() {
  std::uint64_t dropped = 0;
  PlayoutFrame skipped;
  while (jitter.depth() > kJitterTargetFrames && jitter.pop(skipped)) {
    dropped += 1;
  }
  if (dropped == 0) return;

  lateDrops.fetch_add(dropped, std::memory_order_relaxed);
}

void RxEngine::Impl::fillCurrentWithSilence() {
  currentPcm.fill(0);
  currentIndex = 0;
}

}  // namespace intercore::audio
