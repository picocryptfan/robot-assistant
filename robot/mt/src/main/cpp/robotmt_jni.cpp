// SPDX-License-Identifier: GPL-3.0-only
// The JNI surface of librobotmt.so: three functions for app.heyrobot.mt.Slimt (the :mt process only).
// Paths and text cross as UTF-8 byte arrays, never through GetStringUTFChars (modified UTF-8 mangles emoji).
// Nothing is logged; a failure is the robotmt::Error code written into err[0], and no C++ exception leaves a function.
#include <jni.h>

#include <cstdint>
#include <string>

#include "robotmt.h"

namespace {

std::string bytes(JNIEnv* env, jbyteArray a) {
  if (a == nullptr) return std::string();
  jsize n = env->GetArrayLength(a);
  std::string s(static_cast<size_t>(n), '\0');
  if (n > 0) env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte*>(s.data()));
  return s;
}

void set_error(JNIEnv* env, jintArray err, int code) {
  if (err == nullptr || env->GetArrayLength(err) < 1) return;
  jint c = code;
  env->SetIntArrayRegion(err, 0, 1, &c);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL Java_app_heyrobot_mt_Slimt_nativeOpen(JNIEnv* env, jclass, jbyteArray model, jbyteArray vocabulary,
                                                                           jbyteArray shortlist, jintArray err) {
  int error = robotmt::kOk;
  robotmt::Engine* e = nullptr;
  try {
    e = robotmt::open(bytes(env, model), bytes(env, vocabulary), bytes(env, shortlist), &error);
  } catch (...) {  // copying the paths ran out of memory: no C++ exception crosses JNI
    error = robotmt::kFiles;
  }
  set_error(env, err, error);
  return static_cast<jlong>(reinterpret_cast<intptr_t>(e));
}

extern "C" JNIEXPORT void JNICALL Java_app_heyrobot_mt_Slimt_nativeClose(JNIEnv*, jclass, jlong handle) {
  robotmt::close(reinterpret_cast<robotmt::Engine*>(static_cast<intptr_t>(handle)));
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_app_heyrobot_mt_Slimt_nativeTranslate(JNIEnv* env, jclass, jlong handle, jbyteArray utf8,
                                                                                   jintArray err) {
  int error = robotmt::kOk;
  std::string target;
  bool ok = false;
  try {
    ok = robotmt::translate(reinterpret_cast<robotmt::Engine*>(static_cast<intptr_t>(handle)), bytes(env, utf8), &target, &error);
  } catch (...) {  // copying the line ran out of memory: no C++ exception crosses JNI
    error = robotmt::kTranslate;
  }
  set_error(env, err, error);
  if (!ok) return nullptr;
  jbyteArray out = env->NewByteArray(static_cast<jsize>(target.size()));
  if (out == nullptr) return nullptr;  // OutOfMemoryError pending
  if (!target.empty()) env->SetByteArrayRegion(out, 0, static_cast<jsize>(target.size()), reinterpret_cast<const jbyte*>(target.data()));
  return out;
}
