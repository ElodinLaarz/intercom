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

std::mutex g_audio_mutex;
std::shared_ptr<intercore::audio::TxEngine> g_tx;
std::shared_ptr<intercore::audio::RxEngine> g_rx;

std::uint32_t wireEpoch(jlong epoch) {
  return static_cast<std::uint32_t>(epoch);
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
  auto engine =
      std::make_shared<intercore::audio::TxEngine>(wireEpoch(epoch));
  if (!engine->start()) return JNI_FALSE;

  std::shared_ptr<intercore::audio::TxEngine> old;
  {
    std::lock_guard<std::mutex> lock(g_audio_mutex);
    old = std::move(g_tx);
    g_tx = engine;
  }
  if (old) old->stop();
  LOGI("AUDIO guest capture started epoch=%u", wireEpoch(epoch));
  return JNI_TRUE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_elodin_intercom_NativeCore_takeGuestFrame(JNIEnv* env, jobject,
                                                   jint timeoutMs) {
  std::shared_ptr<intercore::audio::TxEngine> engine;
  {
    std::lock_guard<std::mutex> lock(g_audio_mutex);
    engine = g_tx;
  }
  if (!engine) return nullptr;

  intercore::audio::FrameBytes frame{};
  if (!engine->takeFrame(frame, timeoutMs)) return nullptr;

  jbyteArray out = env->NewByteArray(intercore::proto::kVoiceFrameBytes);
  if (out == nullptr) return nullptr;
  env->SetByteArrayRegion(
      out, 0, intercore::proto::kVoiceFrameBytes,
      reinterpret_cast<const jbyte*>(frame.data()));
  return out;
}

extern "C" JNIEXPORT void JNICALL
Java_com_elodin_intercom_NativeCore_stopGuestCapture(JNIEnv*, jobject) {
  std::shared_ptr<intercore::audio::TxEngine> engine;
  {
    std::lock_guard<std::mutex> lock(g_audio_mutex);
    engine = std::move(g_tx);
    g_tx.reset();
  }
  if (engine) {
    engine->stop();
    LOGI("AUDIO guest capture stopped");
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
  LOGI("AUDIO host playout ready epoch=%u", wireEpoch(epoch));
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
    LOGI("AUDIO host playout stopped");
  }
}
