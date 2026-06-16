#include <android/log.h>
#include <jni.h>

#include <array>
#include <cstdint>
#include <memory>
#include <mutex>

#include "constants.h"
#include "ring_buffer.h"
#include "seq_filter.h"
#include "voice_audio.h"

#define LOG_TAG "INTERCOM-native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kMaxBundleFrames = 4;  // safety cap on frames per socket write

std::mutex g_audio_mutex;
std::shared_ptr<intercore::audio::TxEngine> g_tx;
std::shared_ptr<intercore::audio::RxEngine> g_rx;
bool g_last_tx_valid = false;
std::uint32_t g_last_tx_epoch = 0;
std::uint32_t g_last_tx_next_seq = 0;

std::uint32_t wireEpoch(jlong epoch) {
  return static_cast<std::uint32_t>(epoch);
}

void rememberTxSequence(const std::shared_ptr<intercore::audio::TxEngine>& tx) {
  if (!tx) return;

  g_last_tx_epoch = tx->epoch();
  g_last_tx_next_seq = tx->nextSequence();
  g_last_tx_valid = true;
}

std::uint32_t initialTxSequenceFor(std::uint32_t epoch) {
  if (g_tx && g_tx->epoch() == epoch) return g_tx->nextSequence();
  if (g_last_tx_valid && g_last_tx_epoch == epoch) return g_last_tx_next_seq;
  return 0;
}

}  // namespace

// M0 self-test: runs the host-tested core (ring buffer + seq filter) on-device,
// so the smoke harness gets one signal that the NDK build linked and the native
// core actually executes. Voice path entry points live below.
extern "C" JNIEXPORT jint JNICALL
Java_com_elodin_intercom_NativeCore_selfTest(JNIEnv*, jobject) {
  intercore::RingBuffer rb(4);
  for (std::int16_t i = 0; i < 6; ++i) rb.push(i);  // overflows by 2
  std::int16_t first = 0;
  bool popped = rb.pop(first);

  intercore::SeqFilter sf(1);
  bool accepted = sf.accept(1, 100);
  bool duplicate = sf.accept(1, 100);
  bool wrong_epoch = sf.accept(2, 101);

  bool ok = popped && rb.overwriteCount() == 2 && accepted && !duplicate &&
            !wrong_epoch;
  LOGI("selfTest ok=%d overwrites=%llu firstPop=%d protoV=%d", ok ? 1 : 0,
       static_cast<unsigned long long>(rb.overwriteCount()),
       static_cast<int>(first), intercore::proto::kProtocolVersion);
  return ok ? 0xC0DE : 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_elodin_intercom_NativeCore_startGuestCapture(JNIEnv*, jobject,
                                                      jlong epoch) {
  const std::uint32_t nextEpoch = wireEpoch(epoch);
  std::uint32_t initialSeq = 0;
  {
    std::lock_guard<std::mutex> lock(g_audio_mutex);
    initialSeq = initialTxSequenceFor(nextEpoch);
  }

  auto engine =
      std::make_shared<intercore::audio::TxEngine>(nextEpoch, initialSeq);
  if (!engine->start()) return JNI_FALSE;

  std::shared_ptr<intercore::audio::TxEngine> old;
  {
    std::lock_guard<std::mutex> lock(g_audio_mutex);
    old = std::move(g_tx);
    rememberTxSequence(old);
    g_tx = engine;
  }
  if (old) old->stop();
  LOGI("AUDIO capture started epoch=%u seq=%u", nextEpoch, initialSeq);
  return JNI_TRUE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_elodin_intercom_NativeCore_takeGuestBundle(JNIEnv* env, jobject,
                                                    jint maxFrames,
                                                    jint timeoutMs) {
  std::shared_ptr<intercore::audio::TxEngine> engine;
  {
    std::lock_guard<std::mutex> lock(g_audio_mutex);
    engine = g_tx;
  }
  if (!engine) return nullptr;

  int cap = maxFrames;
  if (cap < 1) cap = 1;
  if (cap > kMaxBundleFrames) cap = kMaxBundleFrames;

  std::array<std::uint8_t,
             kMaxBundleFrames * intercore::proto::kVoiceFrameBytes>
      buf{};
  const int count = engine->takeBundle(buf.data(), cap, timeoutMs);
  if (count <= 0) return nullptr;

  const jsize bytes = count * intercore::proto::kVoiceFrameBytes;
  jbyteArray out = env->NewByteArray(bytes);
  if (out == nullptr) return nullptr;
  env->SetByteArrayRegion(out, 0, bytes,
                          reinterpret_cast<const jbyte*>(buf.data()));
  return out;
}

extern "C" JNIEXPORT void JNICALL
Java_com_elodin_intercom_NativeCore_stopGuestCapture(JNIEnv*, jobject) {
  std::shared_ptr<intercore::audio::TxEngine> engine;
  {
    std::lock_guard<std::mutex> lock(g_audio_mutex);
    engine = std::move(g_tx);
    rememberTxSequence(engine);
    g_tx.reset();
  }
  if (engine) {
    engine->stop();
    LOGI("AUDIO capture stopped");
  }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_elodin_intercom_NativeCore_startHostPlayout(JNIEnv*, jobject,
                                                     jlong epoch) {
  auto engine =
      std::make_shared<intercore::audio::RxEngine>(wireEpoch(epoch));
  if (!engine->start()) return JNI_FALSE;

  std::shared_ptr<intercore::audio::RxEngine> old;
  {
    std::lock_guard<std::mutex> lock(g_audio_mutex);
    old = std::move(g_rx);
    g_rx = engine;
  }
  if (old) old->stop();
  LOGI("AUDIO playout ready epoch=%u", wireEpoch(epoch));
  return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_elodin_intercom_NativeCore_pushHostFrame(JNIEnv* env, jobject,
                                                  jbyteArray bytes) {
  if (bytes == nullptr) return JNI_FALSE;
  std::shared_ptr<intercore::audio::RxEngine> engine;
  {
    std::lock_guard<std::mutex> lock(g_audio_mutex);
    engine = g_rx;
  }
  if (!engine) return JNI_FALSE;

  const jsize len = env->GetArrayLength(bytes);
  std::array<std::uint8_t, intercore::proto::kVoiceFrameBytes> frame{};
  if (len == intercore::proto::kVoiceFrameBytes) {
    env->GetByteArrayRegion(bytes, 0, len,
                            reinterpret_cast<jbyte*>(frame.data()));
  } else {
    LOGW("AUDIO rx drop wrong frame size len=%d", static_cast<int>(len));
    return JNI_FALSE;
  }

  return engine->pushFrame(frame.data(), frame.size()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_elodin_intercom_NativeCore_stopHostPlayout(JNIEnv*, jobject) {
  std::shared_ptr<intercore::audio::RxEngine> engine;
  {
    std::lock_guard<std::mutex> lock(g_audio_mutex);
    engine = std::move(g_rx);
    g_rx.reset();
  }
  if (engine) {
    engine->stop();
    LOGI("AUDIO playout stopped");
  }
}
