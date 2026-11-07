// Thin JNI shell over the shared kana-size ncnn core.
//
// All graph logic, net options and logit extraction live in
// kana_size_core.{h,cpp}, which is shared verbatim with the PC app. Keep this
// file to JNI marshalling only — a fix that changes results belongs in the
// core.
#include <jni.h>
#include <android/log.h>

#include <string>
#include <vector>

#include "kana_size_core.h"

#define LOG_TAG "KanaSize"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

kana_size::Model* asModel(jlong h) { return reinterpret_cast<kana_size::Model*>(h); }

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_holopengin_instantjpdict_KanaSizeNcnn_create(
        JNIEnv* env, jclass, jstring paramPath_, jstring binPath_) {
    const char* paramPath = env->GetStringUTFChars(paramPath_, nullptr);
    const char* binPath = env->GetStringUTFChars(binPath_, nullptr);
    std::string param = paramPath ? paramPath : "";
    std::string bin = binPath ? binPath : "";
    if (paramPath) env->ReleaseStringUTFChars(paramPath_, paramPath);
    if (binPath) env->ReleaseStringUTFChars(binPath_, binPath);
    if (param.empty() || bin.empty()) {
        LOGE("create: empty path");
        return 0;
    }

    auto* m = kana_size::create(param.c_str(), bin.c_str());
    if (m == nullptr) {
        LOGE("create: model load failed %s", param.c_str());
        return 0;
    }
    LOGI("kana size model loaded (ncnn, fp32 pinned): %s", param.c_str());
    return reinterpret_cast<jlong>(m);
}

JNIEXPORT void JNICALL
Java_com_holopengin_instantjpdict_KanaSizeNcnn_destroy(JNIEnv*, jclass, jlong handle) {
    kana_size::destroy(asModel(handle));
}

/** Logits for [n] windows: win is n*40 byte values, base is n pair indices (0..19). */
JNIEXPORT jfloatArray JNICALL
Java_com_holopengin_instantjpdict_KanaSizeNcnn_batch(
        JNIEnv* env, jclass, jlong handle, jintArray winArr, jintArray baseArr, jint n) {
    kana_size::Model* m = asModel(handle);
    if (m == nullptr || n <= 0) return nullptr;
    if (env->GetArrayLength(winArr) < n * kana_size::SEQ ||
        env->GetArrayLength(baseArr) < n) {
        LOGE("batch: win/base shorter than n=%d", n);
        return nullptr;
    }
    std::vector<int> win((size_t) n * kana_size::SEQ);
    std::vector<int> bases(n);
    env->GetIntArrayRegion(winArr, 0, n * kana_size::SEQ, win.data());
    env->GetIntArrayRegion(baseArr, 0, n, bases.data());

    std::vector<float> out;
    if (!kana_size::logits(m, win.data(), bases.data(), n, out)) {
        LOGE("batch: inference failed for n=%d", n);
        return nullptr;
    }

    jfloatArray res = env->NewFloatArray(n);
    if (res != nullptr) env->SetFloatArrayRegion(res, 0, n, out.data());
    return res;
}

}  // extern "C"
