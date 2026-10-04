// jni_bridge.cpp — JNI surface for SQL AI AGENTE native core.
// Exposes model lifecycle, streaming inference callbacks and process exec to Kotlin.
#include <jni.h>
#include <android/log.h>
#include <cstdlib>
#include <cstring>
#include <string>
#include <mutex>
#include <atomic>
#include <functional>
#include "internal.h"

#define TAG "SqlAiNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

JavaVM* g_vm = nullptr;
std::mutex g_model_mutex;

// ------------------------------------------------------------------------
// Attach the calling thread to the JVM so callbacks can hop back to Kotlin.
struct EnvScope {
    JNIEnv* env = nullptr;
    bool attached = false;
    explicit EnvScope(JavaVM* vm) {
        if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) return;
        if (vm->AttachCurrentThread(&env, nullptr) == JNI_OK) attached = true;
    }
    ~EnvScope() { if (attached) g_vm->DetachCurrentThread(); }
};

jobject g_token_listener = nullptr; // global ref, set/cleared from Kotlin

void emit_token(JNIEnv* env, const char* tok, int len) {
    if (!g_token_listener || !env) return;
    static jmethodID mid = nullptr;
    if (!mid) {
        jclass cls = env->GetObjectClass(g_token_listener);
        mid = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
    }
    if (!mid) return;
    jstring js = env->NewStringUTF(std::string(tok, len).c_str());
    env->CallVoidMethod(g_token_listener, mid, js);
    env->DeleteLocalRef(js);
}

} // namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    LOGI("sqlai_core loaded (llama=%d whisper=%d)", (int)llama_available(), (int)whisper_available());
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_sqlai_agente_ui_nativebridge_NativeEngine_nativeInit(JNIEnv*, jobject) {
    return llama_available() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_sqlai_agente_ui_nativebridge_NativeEngine_nativeLoadModel(
        JNIEnv* env, jobject, jstring jpath, jint ngl, jint threads) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    std::lock_guard<std::mutex> lock(g_model_mutex);
    ModelHandle* h = model_open(path, ngl, threads);
    env->ReleaseStringUTFChars(jpath, path);
    if (!h) return 0;
    if (!llama_load(h, path, ngl, threads)) {
        // keep handle for stats even if weights absent (dev builds)
        LOGE("llama_load failed, running in degraded mode");
    }
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL
Java_com_sqlai_agente_ui_nativebridge_NativeEngine_nativeUnloadModel(JNIEnv*, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    auto* h = reinterpret_cast<ModelHandle*>(handle);
    if (h) { llama_unload(h); model_close(h); }
}

JNIEXPORT void JNICALL
Java_com_sqlai_agente_ui_nativebridge_NativeEngine_nativeSetListener(JNIEnv* env, jobject, jobject listener) {
    if (g_token_listener) env->DeleteGlobalRef(g_token_listener);
    g_token_listener = listener ? env->NewGlobalRef(listener) : nullptr;
}

JNIEXPORT jstring JNICALL
Java_com_sqlai_agente_ui_nativebridge_NativeEngine_nativeGenerate(
        JNIEnv* env, jobject, jlong handle, jstring jprompt, jint maxTokens, jfloat temp) {
    auto* h = reinterpret_cast<ModelHandle*>(handle);
    if (!h) return env->NewStringUTF("");
    const char* prompt = env->GetStringUTFChars(jprompt, nullptr);

    EnvScope scope(g_vm);
    std::string out = model_generate(h, prompt, maxTokens, temp,
        [&](const char* tok, int len) {
            EnvScope inner(g_vm);
            emit_token(inner.env, tok, len);
            return true;
        });
    env->ReleaseStringUTFChars(jprompt, prompt);
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_com_sqlai_agente_ui_nativebridge_NativeEngine_nativeCancel(JNIEnv*, jobject, jlong handle) {
    auto* h = reinterpret_cast<ModelHandle*>(handle);
    if (h) model_cancel(h);
}

JNIEXPORT jlong JNICALL
Java_com_sqlai_agente_ui_nativebridge_NativeEngine_nativeResidentBytes(JNIEnv*, jobject, jlong handle) {
    auto* h = reinterpret_cast<ModelHandle*>(handle);
    return h ? model_rss_bytes(h) : 0L;
}

JNIEXPORT jstring JNICALL
Java_com_sqlai_agente_ui_nativebridge_NativeEngine_nativeTranscribe(
        JNIEnv* env, jobject, jstring jwav, jstring jmodel) {
    if (!whisper_available()) return env->NewStringUTF("");
    const char* wav = env->GetStringUTFChars(jwav, nullptr);
    const char* mdl = env->GetStringUTFChars(jmodel, nullptr);
    std::string text = whisper_transcribe(wav, mdl);
    env->ReleaseStringUTFChars(jwav, wav);
    env->ReleaseStringUTFChars(jmodel, mdl);
    return env->NewStringUTF(text.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_sqlai_agente_ui_nativebridge_NativeEngine_nativeHasLlama(JNIEnv*, jobject) {
    return llama_available() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_sqlai_agente_ui_nativebridge_NativeEngine_nativeHasWhisper(JNIEnv*, jobject) {
    return whisper_available() ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
