#include <android/log.h>
#include <jni.h>

#include <cstdint>

#include "ring_buffer.h"
#include "seq_filter.h"

#define LOG_TAG "INTERCOM-native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// M0 self-test: runs the host-tested core (ring buffer + seq filter) on-device,
// so the smoke harness gets one signal that the NDK build linked and the native
// core actually executes. No audio yet — Oboe/ADPCM land in M1.
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
  LOGI("selfTest ok=%d overwrites=%llu firstPop=%d", ok ? 1 : 0,
       static_cast<unsigned long long>(rb.overwriteCount()),
       static_cast<int>(first));
  return ok ? 0xC0DE : 0;
}
