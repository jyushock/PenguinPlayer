#include <jni.h>
#include <android/log.h>
#include <gme/gme.h>
#include <mutex>
#include <string>
#include <cstdint>

#define LOG_TAG "GmeJni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static const int SAMPLE_RATE = 44100;

struct EmuContext {
    Music_Emu* emu;
    std::mutex mtx;
};

static inline EmuContext* toCtx(jlong handle) {
    return reinterpret_cast<EmuContext*>(static_cast<uintptr_t>(handle));
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_penguin_player_GmePlayer_nativeOpen(JNIEnv* env, jobject, jbyteArray data) {
    jsize len = env->GetArrayLength(data);
    jbyte* buf = env->GetByteArrayElements(data, nullptr);
    Music_Emu* emu = nullptr;
    gme_err_t err = gme_open_data(buf, static_cast<long>(len), &emu, SAMPLE_RATE);
    env->ReleaseByteArrayElements(data, buf, JNI_ABORT);
    if (err || !emu) { LOGE("gme_open_data: %s", err ? err : "null emu"); return 0L; }
    auto* ctx = new EmuContext{emu};
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(ctx));
}

JNIEXPORT jint JNICALL
Java_com_penguin_player_GmePlayer_nativeGetTrackCount(JNIEnv*, jobject, jlong handle) {
    auto* ctx = toCtx(handle);
    if (!ctx) return 0;
    std::lock_guard<std::mutex> lock(ctx->mtx);
    return gme_track_count(ctx->emu);
}

JNIEXPORT jstring JNICALL
Java_com_penguin_player_GmePlayer_nativeGetTrackName(JNIEnv* env, jobject, jlong handle, jint track) {
    auto* ctx = toCtx(handle);
    if (!ctx) return env->NewStringUTF("");
    std::lock_guard<std::mutex> lock(ctx->mtx);
    gme_info_t* info = nullptr;
    if (gme_track_info(ctx->emu, &info, track) || !info) return env->NewStringUTF("");
    std::string name = (info->song && info->song[0]) ? info->song : "";
    gme_free_info(info);
    return env->NewStringUTF(name.c_str());
}

JNIEXPORT jint JNICALL
Java_com_penguin_player_GmePlayer_nativeGetTrackLength(JNIEnv*, jobject, jlong handle, jint track) {
    auto* ctx = toCtx(handle);
    if (!ctx) return -1;
    std::lock_guard<std::mutex> lock(ctx->mtx);
    gme_info_t* info = nullptr;
    if (gme_track_info(ctx->emu, &info, track) || !info) return -1;
    int len = info->length;
    gme_free_info(info);
    return len;
}

JNIEXPORT jboolean JNICALL
Java_com_penguin_player_GmePlayer_nativeStartTrack(JNIEnv*, jobject, jlong handle, jint track) {
    auto* ctx = toCtx(handle);
    if (!ctx) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(ctx->mtx);
    return gme_start_track(ctx->emu, track) == nullptr ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_penguin_player_GmePlayer_nativeRender(JNIEnv* env, jobject, jlong handle, jshortArray buf) {
    auto* ctx = toCtx(handle);
    if (!ctx) return JNI_FALSE;
    jsize count = env->GetArrayLength(buf);
    jshort* samples = env->GetShortArrayElements(buf, nullptr);
    gme_err_t err;
    {
        std::lock_guard<std::mutex> lock(ctx->mtx);
        err = gme_play(ctx->emu, static_cast<int>(count), samples);
    }
    env->ReleaseShortArrayElements(buf, samples, 0);
    if (err) { LOGE("gme_play: %s", err); return JNI_FALSE; }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_penguin_player_GmePlayer_nativeSetFade(JNIEnv*, jobject, jlong handle, jint startMs) {
    auto* ctx = toCtx(handle);
    if (!ctx) return;
    std::lock_guard<std::mutex> lock(ctx->mtx);
    gme_set_fade(ctx->emu, startMs);
}

JNIEXPORT void JNICALL
Java_com_penguin_player_GmePlayer_nativeSeek(JNIEnv*, jobject, jlong handle, jint msec) {
    auto* ctx = toCtx(handle);
    if (!ctx) return;
    std::lock_guard<std::mutex> lock(ctx->mtx);
    gme_seek(ctx->emu, msec);
}

JNIEXPORT jint JNICALL
Java_com_penguin_player_GmePlayer_nativeTell(JNIEnv*, jobject, jlong handle) {
    auto* ctx = toCtx(handle);
    if (!ctx) return 0;
    std::lock_guard<std::mutex> lock(ctx->mtx);
    return gme_tell(ctx->emu);
}

JNIEXPORT jboolean JNICALL
Java_com_penguin_player_GmePlayer_nativeTrackEnded(JNIEnv*, jobject, jlong handle) {
    auto* ctx = toCtx(handle);
    if (!ctx) return JNI_TRUE;
    std::lock_guard<std::mutex> lock(ctx->mtx);
    return gme_track_ended(ctx->emu) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_penguin_player_GmePlayer_nativeClose(JNIEnv*, jobject, jlong handle) {
    auto* ctx = toCtx(handle);
    if (!ctx) return;
    {
        std::lock_guard<std::mutex> lock(ctx->mtx);
        gme_delete(ctx->emu);
        ctx->emu = nullptr;
    }
    delete ctx;
}

} // extern "C"
