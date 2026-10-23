// Copyright Alibaba Group Holding Limited.
// Portions adapted from MNN 3.6.1 MnnLlmChat, licensed under Apache-2.0.
// EPrint modifications: session trimming, streaming/error metrics and cancellation recovery.
// See third_party/licenses/MNN-3.6.1.txt and THIRD_PARTY_NOTICES.md.
//
// glm_ocr_jni.cpp — GLM-OCR / Qwen text-only 共用的 MNN-LLM 薄 JNI wrapper。
//
// 参照 MnnLlmChat（Apache-2.0，MNN 官方安卓 app）的 JNI 层裁剪：
//   保留 load / submit（流式回调）/ reset / release / maxNewTokens / updateConfig；
//   砍掉 audio、video、diffusion、benchmark、多轮历史、R1 思考链、Firebase 上报。
// 生成使用 MNN Llm::response 的标准完整路径；运行参数由 Java 层在 load 前统一注入。
//
#include <jni.h>
#include <android/log.h>
#include <functional>
#include <sstream>
#include <string>

#include "llm/llm.hpp"

#define LOG_TAG "GlmOcrSession"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

using MNN::Transformer::Llm;
using MNN::Transformer::LlmContext;
using MNN::Transformer::LlmStatus;

struct GlmSession {
    Llm* llm = nullptr;
    int maxNewTokens = 1024;
};

// 流式缓冲：引擎按字节写出，先凑齐完整 UTF-8 字符再回调 Java。
class Utf8StreamProcessor {
public:
    explicit Utf8StreamProcessor(std::function<void(const std::string&)> callback)
            : callback_(std::move(callback)) {}

    void processStream(const char* str, size_t len) {
        utf8Buffer_.append(str, len);
        size_t i = 0;
        std::string complete;
        while (i < utf8Buffer_.size()) {
            int length = utf8CharLength(static_cast<unsigned char>(utf8Buffer_[i]));
            if (length == 0 || i + length > utf8Buffer_.size()) break;
            complete.append(utf8Buffer_, i, length);
            i += length;
        }
        utf8Buffer_ = utf8Buffer_.substr(i);
        if (!complete.empty()) callback_(complete);
    }

    static int utf8CharLength(unsigned char byte) {
        if ((byte & 0x80) == 0) return 1;
        if ((byte & 0xE0) == 0xC0) return 2;
        if ((byte & 0xF0) == 0xE0) return 3;
        if ((byte & 0xF8) == 0xF0) return 4;
        return 0;
    }

private:
    std::string utf8Buffer_;
    std::function<void(const std::string&)> callback_;
};

class StreamBuffer : public std::streambuf {
public:
    using Callback = std::function<void(const char*, size_t)>;
    explicit StreamBuffer(Callback callback) : callback_(std::move(callback)) {}

protected:
    std::streamsize xsputn(const char* s, std::streamsize n) override {
        if (callback_) callback_(s, n);
        return n;
    }

private:
    Callback callback_ = nullptr;
};

struct StreamState {
    std::stringstream buffer;
    bool stopRequested = false;
    bool textEnd = false;
    bool pendingEop = false;

    void onChunk(const std::string& chunk,
                 const std::function<bool(const std::string&, bool)>& onProgress) {
        size_t eop = chunk.find("<eop>");
        std::string visible = eop == std::string::npos ? chunk : chunk.substr(0, eop);
        if (!visible.empty()) {
            buffer << visible;
            if (onProgress) stopRequested = stopRequested || onProgress(visible, false);
        }
        if (eop != std::string::npos) {
            pendingEop = true;
        }
    }

    void finalizeEop(const std::function<bool(const std::string&, bool)>& onProgress) {
        if (!pendingEop) return;
        if (onProgress) stopRequested = stopRequested || onProgress("", true);
        textEnd = true;
        pendingEop = false;
    }
};

// MNN 3.6.1 Android 预编译 libllm 的 reset() 不会清除 USER_CANCEL（与 tag 当前源码不同）。
// 只修复用户主动取消状态；INTERNAL_ERROR/TIMEOUT 保持不可复用，避免掩盖真实引擎故障。
void resetForNextResponse(Llm* llm) {
    if (llm == nullptr) return;
    llm->reset();
    auto* context = const_cast<LlmContext*>(llm->getContext());
    if (context != nullptr && context->status == LlmStatus::USER_CANCEL) {
        context->status = LlmStatus::RUNNING;
    }
}

void putLong(JNIEnv* env, jobject map, jmethodID putMethod, const char* key, jlong value) {
    jclass longClass = env->FindClass("java/lang/Long");
    jmethodID longInit = env->GetMethodID(longClass, "<init>", "(J)V");
    jstring keyStr = env->NewStringUTF(key);
    jobject valueObj = env->NewObject(longClass, longInit, value);
    env->CallObjectMethod(map, putMethod, keyStr, valueObj);
    env->DeleteLocalRef(keyStr);
    env->DeleteLocalRef(valueObj);
    env->DeleteLocalRef(longClass);
}

void putString(JNIEnv* env, jobject map, jmethodID putMethod, const char* key,
               const std::string& value) {
    jstring keyStr = env->NewStringUTF(key);
    jstring valueStr = env->NewStringUTF(value.c_str());
    env->CallObjectMethod(map, putMethod, keyStr, valueStr);
    env->DeleteLocalRef(keyStr);
    env->DeleteLocalRef(valueStr);
}

jobject newErrorMap(JNIEnv* env, const char* message) {
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
    jmethodID putMethod = env->GetMethodID(hashMapClass, "put",
            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    jobject map = env->NewObject(hashMapClass, hashMapInit);
    putString(env, map, putMethod, "error", message);
    env->DeleteLocalRef(hashMapClass);
    return map;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_otool_eprint_scan_vlm_GlmOcrSession_nativeInit(JNIEnv* env, jobject thiz,
                                                        jstring configPath,
                                                        jstring configJson) {
    const char* configPathCStr = env->GetStringUTFChars(configPath, nullptr);
    std::string configPathStr(configPathCStr != nullptr ? configPathCStr : "");
    env->ReleaseStringUTFChars(configPath, configPathCStr);
    std::string configJsonStr;
    if (configJson != nullptr) {
        const char* configJsonCStr = env->GetStringUTFChars(configJson, nullptr);
        configJsonStr = configJsonCStr != nullptr ? configJsonCStr : "";
        env->ReleaseStringUTFChars(configJson, configJsonCStr);
    }
    LOGD("nativeInit createLLM: %s", configPathStr.c_str());
    auto* session = new GlmSession();
    session->llm = Llm::createLLM(configPathStr);
    if (session->llm == nullptr) {
        delete session;
        LOGE("createLLM failed: %s", configPathStr.c_str());
        jclass exClass = env->FindClass("java/lang/IllegalStateException");
        if (exClass != nullptr) {
            env->ThrowNew(exClass, ("createLLM failed: " + configPathStr).c_str());
        }
        return 0;
    }
    if (!configJsonStr.empty()) {
        session->llm->set_config(configJsonStr);
        LOGD("set_config applied: %s", configJsonStr.c_str());
    }
    if (!session->llm->load()) {
        // 附带生效配置便于经 logcat 定位后端/精度相关的加载失败（如 GPU 后端不支持当前量化）。
        std::string err = "LLM load() failed: " + configPathStr + " config=" + configJsonStr;
        LOGE("%s", err.c_str());
        delete session->llm;
        delete session;
        jclass exClass = env->FindClass("java/lang/IllegalStateException");
        if (exClass != nullptr) env->ThrowNew(exClass, err.c_str());
        return 0;
    }
    LOGD("nativeInit success, session=%p", session);
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT jobject JNICALL
Java_com_otool_eprint_scan_vlm_GlmOcrSession_nativeSubmit(JNIEnv* env, jobject thiz,
                                                          jlong sessionPtr, jstring promptStr,
                                                          jobject progressListener) {
    auto* session = reinterpret_cast<GlmSession*>(sessionPtr);
    if (session == nullptr || session->llm == nullptr) {
        return newErrorMap(env, "session not loaded");
    }
    const char* promptCStr = env->GetStringUTFChars(promptStr, nullptr);
    std::string prompt(promptCStr != nullptr ? promptCStr : "");
    env->ReleaseStringUTFChars(promptStr, promptCStr);

    // 流式回调：chunk 为增量文本，isEop 时传 null（与上游 LlmSession 约定一致）。
    jmethodID onProgressMethod = nullptr;
    if (progressListener != nullptr) {
        jclass listenerClass = env->GetObjectClass(progressListener);
        onProgressMethod = env->GetMethodID(listenerClass, "onProgress", "(Ljava/lang/String;)Z");
        env->DeleteLocalRef(listenerClass);
    }
    StreamState state;
    std::function<bool(const std::string&, bool)> onProgress =
            [&](const std::string& chunk, bool isEop) -> bool {
        if (progressListener == nullptr || onProgressMethod == nullptr) return false;
        jstring chunkStr = isEop ? nullptr : env->NewStringUTF(chunk.c_str());
        jboolean stop = env->CallBooleanMethod(progressListener, onProgressMethod, chunkStr);
        if (chunkStr != nullptr) env->DeleteLocalRef(chunkStr);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            return true;
        }
        if (stop == JNI_TRUE && session->llm->getContext() != nullptr) {
            const_cast<LlmContext*>(session->llm->getContext())->status = LlmStatus::USER_CANCEL;
        }
        return stop == JNI_TRUE;
    };

    Utf8StreamProcessor utf8Processor(
            [&](const std::string& chunk) { state.onChunk(chunk, onProgress); });
    StreamBuffer streamBuffer(
            [&](const char* str, size_t len) { utf8Processor.processStream(str, len); });
    std::ostream outputStream(&streamBuffer);

    int maxNewTokens = session->maxNewTokens;
    try {
        resetForNextResponse(session->llm);
        // 图片由引擎解析 prompt 内嵌的 <img>绝对路径</img> 并进入视觉编码器。
        // 让 response 自己维护 prefill/decode/终止状态，避免手工逐 token 解码破坏状态机。
        session->llm->response(prompt, &outputStream, "<eop>", maxNewTokens);
        state.finalizeEop(onProgress);
    } catch (const std::exception& e) {
        LOGE("nativeSubmit exception: %s", e.what());
        return newErrorMap(env, e.what());
    } catch (...) {
        LOGE("nativeSubmit unknown exception");
        return newErrorMap(env, "unknown native exception");
    }

    const LlmContext* ctx = session->llm->getContext();
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
    jmethodID putMethod = env->GetMethodID(hashMapClass, "put",
            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    jobject map = env->NewObject(hashMapClass, hashMapInit);
    putString(env, map, putMethod, "text", state.buffer.str());
    putLong(env, map, putMethod, "prompt_len", ctx != nullptr ? ctx->prompt_len : 0);
    putLong(env, map, putMethod, "decode_len", ctx != nullptr ? ctx->gen_seq_len : 0);
    putLong(env, map, putMethod, "vision_time", ctx != nullptr ? ctx->vision_us : 0);
    putLong(env, map, putMethod, "prefill_time", ctx != nullptr ? ctx->prefill_us : 0);
    putLong(env, map, putMethod, "decode_time", ctx != nullptr ? ctx->decode_us : 0);
    if (state.stopRequested && !state.textEnd) {
        putString(env, map, putMethod, "stopped", "true");
    }
    env->DeleteLocalRef(hashMapClass);
    LOGD("nativeSubmit done: prompt_len=%d decode_len=%d vision=%lldus prefill=%lldus decode=%lldus",
         ctx != nullptr ? ctx->prompt_len : 0, ctx != nullptr ? ctx->gen_seq_len : 0,
         (long long) (ctx != nullptr ? ctx->vision_us : 0),
         (long long) (ctx != nullptr ? ctx->prefill_us : 0),
         (long long) (ctx != nullptr ? ctx->decode_us : 0));
    return map;
}

JNIEXPORT void JNICALL
Java_com_otool_eprint_scan_vlm_GlmOcrSession_nativeReset(JNIEnv* env, jobject thiz,
                                                         jlong sessionPtr) {
    auto* session = reinterpret_cast<GlmSession*>(sessionPtr);
    if (session != nullptr && session->llm != nullptr) resetForNextResponse(session->llm);
}

JNIEXPORT void JNICALL
Java_com_otool_eprint_scan_vlm_GlmOcrSession_nativeRelease(JNIEnv* env, jobject thiz,
                                                           jlong sessionPtr) {
    auto* session = reinterpret_cast<GlmSession*>(sessionPtr);
    if (session == nullptr) return;
    LOGD("nativeRelease session=%p", session);
    delete session->llm;
    delete session;
}

JNIEXPORT void JNICALL
Java_com_otool_eprint_scan_vlm_GlmOcrSession_nativeSetMaxNewTokens(JNIEnv* env, jobject thiz,
                                                                   jlong sessionPtr,
                                                                   jint maxNewTokens) {
    auto* session = reinterpret_cast<GlmSession*>(sessionPtr);
    if (session != nullptr && maxNewTokens > 0) session->maxNewTokens = maxNewTokens;
}

JNIEXPORT jboolean JNICALL
Java_com_otool_eprint_scan_vlm_GlmOcrSession_nativeUpdateConfig(JNIEnv* env, jobject thiz,
                                                                jlong sessionPtr,
                                                                jstring configJson) {
    auto* session = reinterpret_cast<GlmSession*>(sessionPtr);
    if (session == nullptr || session->llm == nullptr || configJson == nullptr) return JNI_FALSE;
    const char* configJsonCStr = env->GetStringUTFChars(configJson, nullptr);
    bool ok = session->llm->set_config(configJsonCStr != nullptr ? configJsonCStr : "");
    env->ReleaseStringUTFChars(configJson, configJsonCStr);
    LOGD("nativeUpdateConfig ok=%d", ok);
    return ok ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
