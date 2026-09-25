// Thin JNI shell over the shared PP-OCRv6 ncnn core.
//
// All inference logic (net options, blob extraction, tensor-width guard,
// top-K packing) lives in ppocr_ncnn_core.{h,cpp}, which is shared verbatim
// with the PC app (InstantJPDictDecky native/ppocr_ncnn). Keep this file to
// buffer marshalling only — a fix that changes results belongs in the core.
#include <jni.h>
#include <vector>

#include "ncnn/benchmark.h"
#include "ncnn/mat.h"
#include "ppocr_ncnn_core.h"

using ppocr_ncnn::DetNet;
using ppocr_ncnn::RecNet;

namespace {

// Last det call's split, in milliseconds, readable from Kotlin via
// lastTimingsNative. The point is to time PREPROCESSING without a logcat
// scrape: the Kotlin side measures its own part with System.nanoTime, and this
// covers the rest, so the two add up to what the caller actually spent.
double g_det_pre_ms = 0.0;
double g_det_net_ms = 0.0;

double nowMs() {
    // ncnn::get_current_time() is already milliseconds (ncnn/benchmark.h: "get
    // now timestamp in ms") — not seconds. ppocr_ncnn_core.cpp's recTopK timer
    // multiplies by 1000 and divides by 1000 when printing, so its "ms" figures
    // are really seconds; do not copy that.
    return ncnn::get_current_time();
}

// The 9.6 MB det input Mat, kept per thread across calls. ncnn::Mat::create
// goes to malloc, and 9.6 MB is above glibc's mmap threshold, so a per-call Mat
// means an mmap plus ~2,400 first-touch page faults on every detect. det_build_input
// refills an already-correct Mat in place, so the buffer survives; thread-local
// because OcrEngine keeps per-thread pools for the same reason. The alloc is
// worth keeping but it is not the big win — replacing the 768-entry LUT with the
// auto-vectorisable expression is (build: 4.3 ms → 2.0 ms for both changes).
thread_local ncnn::Mat g_det_in;

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_holopengin_instantjpdict_RecNcnn_create(JNIEnv *env, jclass, jstring paramPath_, jstring binPath_, jint targetW, jint numThreads) {
    const char *paramPath = env->GetStringUTFChars(paramPath_, 0);
    const char *binPath = env->GetStringUTFChars(binPath_, 0);

    RecNet *rec = ppocr_ncnn::rec_create(paramPath, binPath, targetW, numThreads);

    env->ReleaseStringUTFChars(paramPath_, paramPath);
    env->ReleaseStringUTFChars(binPath_, binPath);
    return (jlong) rec;
}

JNIEXPORT void JNICALL
Java_com_holopengin_instantjpdict_RecNcnn_destroy(JNIEnv *, jclass, jlong handle) {
    ppocr_ncnn::rec_destroy((RecNet *) handle);
}

JNIEXPORT jfloatArray JNICALL
Java_com_holopengin_instantjpdict_RecNcnn_inferNative(JNIEnv *env, jclass, jlong handle, jobject buffer, jint w, jint h) {
    RecNet *rec = (RecNet *) handle;
    if (!rec) return nullptr;

    float *data = (float *) env->GetDirectBufferAddress(buffer);
    if (!data) return nullptr;
    jlong capacity = env->GetDirectBufferCapacity(buffer);

    std::vector<float> out;
    if (!ppocr_ncnn::rec_infer(rec, data, (size_t) (capacity / 4), w, h, out)) return nullptr;

    jfloatArray jout = env->NewFloatArray((jsize) out.size());
    if (!jout) return nullptr;
    env->SetFloatArrayRegion(jout, 0, (jsize) out.size(), out.data());
    return jout;
}

JNIEXPORT jfloatArray JNICALL
Java_com_holopengin_instantjpdict_RecNcnn_inferTopKNative(JNIEnv *env, jclass, jlong handle, jobject buffer, jint w, jint h) {
    RecNet *rec = (RecNet *) handle;
    if (!rec) return nullptr;

    float *data = (float *) env->GetDirectBufferAddress(buffer);
    if (!data) return nullptr;
    jlong capacity = env->GetDirectBufferCapacity(buffer);

    std::vector<float> out;
    if (!ppocr_ncnn::rec_infer_topk(rec, data, (size_t) (capacity / 4), w, h, out)) return nullptr;

    jfloatArray jout = env->NewFloatArray((jsize) out.size());
    if (!jout) return nullptr;
    env->SetFloatArrayRegion(jout, 0, (jsize) out.size(), out.data());
    return jout;
}

JNIEXPORT jlong JNICALL
Java_com_holopengin_instantjpdict_DetNcnn_create(JNIEnv *env, jclass, jstring paramPath_, jstring binPath_) {
    const char *paramPath = env->GetStringUTFChars(paramPath_, 0);
    const char *binPath = env->GetStringUTFChars(binPath_, 0);

    DetNet *det = ppocr_ncnn::det_create(paramPath, binPath);

    env->ReleaseStringUTFChars(paramPath_, paramPath);
    env->ReleaseStringUTFChars(binPath_, binPath);
    return (jlong) det;
}

JNIEXPORT void JNICALL
Java_com_holopengin_instantjpdict_DetNcnn_destroy(JNIEnv *, jclass, jlong handle) {
    ppocr_ncnn::det_destroy((DetNet *) handle);
}

JNIEXPORT jfloatArray JNICALL
Java_com_holopengin_instantjpdict_DetNcnn_inferNative(JNIEnv *env, jclass, jlong handle, jobject buffer, jint w, jint h) {
    DetNet *det = (DetNet *) handle;
    if (!det) return nullptr;

    float *data = (float *) env->GetDirectBufferAddress(buffer);
    if (!data) return nullptr;
    jlong capacity = env->GetDirectBufferCapacity(buffer);

    // The float path's "preprocess" is the caller-side getPixels + NCHW loop +
    // ByteBuffer copy, so only the net half is timed here. The Mat fill
    // (fill_input) is inside det_infer and is therefore counted as net time.
    const double t0 = nowMs();
    std::vector<float> out;
    if (!ppocr_ncnn::det_infer(det, data, (size_t) (capacity / 4), w, h, out)) return nullptr;
    g_det_pre_ms = 0.0;
    g_det_net_ms = nowMs() - t0;

    jfloatArray jout = env->NewFloatArray((jsize) out.size());
    if (!jout) return nullptr;
    env->SetFloatArrayRegion(jout, 0, (jsize) out.size(), out.data());
    return jout;
}

// Preprocessing done in C++: letterbox + ImageNet normalise straight from the
// ARGB8888 pixels, then run the net. The pixel array is pinned with
// GetPrimitiveArrayCritical and released BEFORE inference, so the JVM's GC is
// never blocked across the ~250 ms extract.
JNIEXPORT jfloatArray JNICALL
Java_com_holopengin_instantjpdict_DetNcnn_inferLetterboxedNative(JNIEnv *env, jclass, jlong handle, jintArray pixels, jint resizeW, jint resizeH, jint modelSize, jint padX, jint padY) {
    DetNet *det = (DetNet *) handle;
    if (!det) return nullptr;

    ncnn::Mat& in = g_det_in;
    {
        const double t0 = nowMs();
        jint *src = (jint *) env->GetPrimitiveArrayCritical(pixels, nullptr);
        if (!src) return nullptr;
        const bool built = ppocr_ncnn::det_build_input(src, resizeW, resizeH, modelSize, padX, padY, in);
        env->ReleasePrimitiveArrayCritical(pixels, src, JNI_ABORT);
        g_det_pre_ms = nowMs() - t0;
        if (!built) return nullptr;
    }

    const double t1 = nowMs();
    std::vector<float> out;
    const bool ok = ppocr_ncnn::det_run(det, in, out);
    g_det_net_ms = nowMs() - t1;
    if (!ok) return nullptr;

    jfloatArray jout = env->NewFloatArray((jsize) out.size());
    if (!jout) return nullptr;
    env->SetFloatArrayRegion(jout, 0, (jsize) out.size(), out.data());
    return jout;
}

// Build the NCHW tensor without running the net — the parity seam. Returns the
// [3,modelSize,modelSize] floats so a test can diff them against the Kotlin
// build bit for bit; a preprocessing regression shows up here instead of as
// mysteriously different boxes.
JNIEXPORT jfloatArray JNICALL
Java_com_holopengin_instantjpdict_DetNcnn_buildLetterboxedInputNative(JNIEnv *env, jclass, jintArray pixels, jint resizeW, jint resizeH, jint modelSize, jint padX, jint padY) {
    ncnn::Mat& in = g_det_in;
    {
        jint *src = (jint *) env->GetPrimitiveArrayCritical(pixels, nullptr);
        if (!src) return nullptr;
        const bool built = ppocr_ncnn::det_build_input(src, resizeW, resizeH, modelSize, padX, padY, in);
        env->ReleasePrimitiveArrayCritical(pixels, src, JNI_ABORT);
        if (!built) return nullptr;
    }
    const size_t n = (size_t) modelSize * modelSize * 3;
    jfloatArray jout = env->NewFloatArray((jsize) n);
    if (!jout) return nullptr;
    // Planes are cstep apart, not necessarily contiguous — copy channel by
    // channel rather than assuming one block.
    for (int c = 0; c < 3; c++) {
        env->SetFloatArrayRegion(jout, (jsize) ((size_t) c * modelSize * modelSize), (jsize) (modelSize * modelSize), in.channel(c));
    }
    return jout;
}

// The float path's input half with no net and no timing noise: copies the
// direct buffer into a Mat exactly as det_infer does, and returns how long that
// took in ms (-1 on failure). The letterbox path has no equivalent step, so this
// is how the old path's third copy is priced without differencing two ~250 ms
// net calls.
JNIEXPORT jfloat JNICALL
Java_com_holopengin_instantjpdict_DetNcnn_fillInputOnlyNative(JNIEnv *env, jclass, jobject buffer, jint w, jint h) {
    const float *data = (const float *) env->GetDirectBufferAddress(buffer);
    if (!data) return -1.0f;
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    const double t0 = nowMs();
    if (!ppocr_ncnn::det_fill_input(data, (size_t) (capacity / 4), w, h, g_det_in)) return -1.0f;
    return (jfloat) (nowMs() - t0);
}

// [preprocess_ms, net_ms] of the last det call (see g_det_pre_ms).
JNIEXPORT jfloatArray JNICALL
Java_com_holopengin_instantjpdict_DetNcnn_lastTimingsNative(JNIEnv *env, jclass, jlong) {
    jfloat out[2] = {(jfloat) g_det_pre_ms, (jfloat) g_det_net_ms};
    jfloatArray jout = env->NewFloatArray(2);
    if (!jout) return nullptr;
    env->SetFloatArrayRegion(jout, 0, 2, out);
    return jout;
}

} // extern "C"
