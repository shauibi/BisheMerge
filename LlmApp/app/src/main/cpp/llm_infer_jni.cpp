#include <jni.h>
#include <string>
#include <vector>
#include <thread>
#include <functional>
#include <sstream>
#include <streambuf>
#include <chrono>
#include <algorithm>
#include <memory>
#include <android/log.h>
#include <fstream>

#define LOG_TAG "LLMApp_JNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#include <llm/llm.hpp>
#include "tokenizer.hpp"
#include "llmconfig.hpp"
#include <MNN/Interpreter.hpp>

class Utf8StreamProcessor {
public:
    explicit Utf8StreamProcessor(std::function<void(const std::string &)> callback)
            : callback(std::move(callback)) {}
    void processStream(const char *str, size_t len) {
        utf8Buffer.append(str, len);

        size_t i = 0;
        std::string completeChars;
        while (i < utf8Buffer.size()) {
            int length = utf8CharLength(static_cast<unsigned char>(utf8Buffer[i]));
            if (length == 0) {
                // Byte not a valid UTF-8 start — discard and retry
                i++;
                continue;
            }
            if (i + length > utf8Buffer.size()) {
                // Incomplete sequence at end of buffer — wait for more data
                break;
            }
            // Validate continuation bytes must be 10xxxxxx
            bool valid = true;
            for (int j = 1; j < length && valid; j++) {
                unsigned char cb = static_cast<unsigned char>(utf8Buffer[i + j]);
                if ((cb & 0xC0) != 0x80) valid = false;
            }
            if (!valid) {
                // Corrupt sequence (e.g. truncated across token boundary
                // with new start byte mixed in) — discard leading byte
                i++;
                continue;
            }
            completeChars.append(utf8Buffer, i, length);
            i += length;
        }
        utf8Buffer = utf8Buffer.substr(i);
        if (!completeChars.empty()) {
            callback(completeChars);
        }
    }

    static int utf8CharLength(unsigned char byte) {
        if ((byte & 0x80) == 0) return 1;
        if ((byte & 0xE0) == 0xC0) return 2;
        if ((byte & 0xF0) == 0xE0) return 3;
        if ((byte & 0xF8) == 0xF0) return 4;
        return 0;
    }

private:
    std::string utf8Buffer;
    std::function<void(const std::string &)> callback;
};

static JavaVM* g_jvm = nullptr;

struct LlmSession {
    MNN::Transformer::Llm* llm = nullptr;
    std::string model_path;
    bool is_loaded = false;
    std::vector<std::pair<std::string, std::string>> history;
    bool stop_requested = false;
    bool generate_end = false;
};

struct EmbeddingSession {
    MNN::Transformer::Tokenizer* tokenizer = nullptr;
    std::unique_ptr<MNN::Interpreter> interpreter;
    MNN::Session* mnn_session = nullptr;
    std::string config_path;
    bool is_loaded = false;
    int dim = 0;
    int max_seq_len = 0;  // will be set from input tensor shape
};

static JNIEnv* getEnv(bool* need_detach) {
    *need_detach = false;
    JNIEnv* env = nullptr;
    if (g_jvm == nullptr) return nullptr;
    if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            *need_detach = true;
        } else {
            return nullptr;
        }
    }
    return env;
}

static void detachIfNeeded(bool need_detach) {
    if (need_detach && g_jvm != nullptr) {
        g_jvm->DetachCurrentThread();
    }
}

static void callJavaMethod(JNIEnv* env, jobject callback, jmethodID method_id, const std::string& arg) {
    if (env == nullptr || callback == nullptr || method_id == nullptr) return;
    jstring jstr = env->NewStringUTF(arg.c_str());
    env->CallVoidMethod(callback, method_id, jstr);
    env->DeleteLocalRef(jstr);
    if (env->ExceptionCheck()) {
        LOGE("Exception in Java callback");
        env->ExceptionClear();
    }
}

static int64_t g_last_prefill_us = 0;
static int64_t g_last_decode_us = 0;
static int64_t g_last_ttft_us = 0;
static int g_last_output_tokens = 0;

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    LOGD("JNI_OnLoad: JNI initialized successfully");
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
    g_jvm = nullptr;
    LOGD("JNI_OnUnload: JNI cleanup complete");
}

JNIEXPORT jlong JNICALL
Java_com_llmapp_jni_NativeLib_createSession(JNIEnv* env, jobject thiz, jstring model_path) {
    if (model_path == nullptr) {
        LOGE("createSession: null model_path");
        return 0;
    }
    const char* path = env->GetStringUTFChars(model_path, nullptr);
    LOGD("Creating session with model path: %s", path);
    auto* session = new LlmSession();
    session->model_path = std::string(path);
    env->ReleaseStringUTFChars(model_path, path);
    LOGD("Session created successfully at %p", session);
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL
Java_com_llmapp_jni_NativeLib_destroySession(JNIEnv* env, jobject thiz, jlong ptr) {
    if (ptr == 0) return;
    auto* session = reinterpret_cast<LlmSession*>(ptr);
    LOGD("Destroying session at %p", session);
    if (session->llm != nullptr) {
        MNN::Transformer::Llm::destroy(session->llm);
        session->llm = nullptr;
    }
    delete session;
}

JNIEXPORT jboolean JNICALL
Java_com_llmapp_jni_NativeLib_loadModel(JNIEnv* env, jobject thiz, jlong ptr) {
    if (ptr == 0) {
        LOGE("loadModel: null session pointer");
        return JNI_FALSE;
    }
    auto* session = reinterpret_cast<LlmSession*>(ptr);
    if (session->is_loaded) {
        LOGD("Model already loaded");
        return JNI_TRUE;
    }

    std::string config_path = session->model_path;
    if (config_path.back() != '/' && config_path.back() != '\\') {
        config_path += "/";
    }
    config_path += "config.json";
    LOGD("Loading model from: %s", config_path.c_str());

    std::ifstream config_file(config_path);
    if (!config_file.good()) {
        LOGE("config.json not found at: %s", config_path.c_str());
        return JNI_FALSE;
    }
    config_file.close();
    LOGD("config.json exists");

    session->llm = MNN::Transformer::Llm::createLLM(config_path);
    if (session->llm == nullptr) {
        LOGE("Failed to create LLM instance - config may be invalid");
        return JNI_FALSE;
    }
    LOGD("LLM instance created successfully");

    session->llm->set_config("{\"tmp_path\":\"tmp\"}");

    LOGD("Model directory: %s", session->model_path.c_str());
    std::string md = session->model_path;
    auto check = [&](const char* name) {
        std::string p = md + "/" + name;
        std::ifstream f(p);
        LOGD("  %s %s", f.good() ? "[OK]" : "[MISSING]", p.c_str());
    };
    check("llm.mnn");
    check("llm.mnn.weight");
    check("tokenizer.mtok");
    check("tokenizer.txt");
    check("visual.mnn");

    std::string config_str = session->llm->dump_config();
    LOGD("Effective config: %s", config_str.c_str());

    bool loaded = session->llm->load();
    if (!loaded) {
        LOGE("Failed to load model - check model files and memory");
        MNN::Transformer::Llm::destroy(session->llm);
        session->llm = nullptr;
        return JNI_FALSE;
    }

    session->is_loaded = true;
    LOGD("Model loaded successfully");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_llmapp_jni_NativeLib_setModelPath(JNIEnv* env, jobject thiz, jlong ptr, jstring path) {
    if (ptr == 0 || path == nullptr) {
        LOGE("setModelPath: invalid session pointer or path");
        return JNI_FALSE;
    }
    auto* session = reinterpret_cast<LlmSession*>(ptr);
    const char* new_path = env->GetStringUTFChars(path, nullptr);
    session->model_path = std::string(new_path);
    env->ReleaseStringUTFChars(path, new_path);
    LOGD("Model path updated to: %s", session->model_path.c_str());
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_llmapp_jni_NativeLib_clearHistory(JNIEnv* env, jobject thiz, jlong ptr) {
    if (ptr == 0) return;
    auto* session = reinterpret_cast<LlmSession*>(ptr);
    session->history.clear();
    if (session->llm != nullptr) {
        session->llm->eraseHistory(0, 0);
    }
    LOGD("History and KV cache cleared");
}

// 获取当前对话轮数（一问一答为一轮）
JNIEXPORT jint JNICALL
Java_com_llmapp_jni_NativeLib_getHistoryTurnCount(JNIEnv* env, jobject thiz, jlong ptr) {
    if (ptr == 0) return 0;
    auto* session = reinterpret_cast<LlmSession*>(ptr);
    return static_cast<jint>(session->history.size() / 2);
}

// 裁剪对话历史，仅保留最近 maxTurns 轮
JNIEXPORT void JNICALL
Java_com_llmapp_jni_NativeLib_trimHistory(JNIEnv* env, jobject thiz, jlong ptr, jint maxTurns) {
    if (ptr == 0 || maxTurns < 1) return;
    auto* session = reinterpret_cast<LlmSession*>(ptr);
    int currentTurns = static_cast<int>(session->history.size() / 2);
    if (currentTurns <= maxTurns) return;

    int removeTurns = currentTurns - maxTurns;
    int removeEntries = removeTurns * 2;
    session->history.erase(session->history.begin(), session->history.begin() + removeEntries);

    if (session->llm != nullptr) {
        // 擦除 KV cache 中对应的旧轮记录
        session->llm->eraseHistory(0, removeTurns);
    }
    LOGD("History trimmed: %d -> %d turns", currentTurns, maxTurns);
}

JNIEXPORT jstring JNICALL
Java_com_llmapp_jni_NativeLib_runTest(JNIEnv* env, jobject thiz, jlong ptr, jstring input) {
    if (ptr == 0 || input == nullptr) {
        return env->NewStringUTF("Error: invalid session or input");
    }
    auto* session = reinterpret_cast<LlmSession*>(ptr);
    if (!session->is_loaded || session->llm == nullptr) {
        return env->NewStringUTF("Error: model not loaded");
    }
    const char* input_str = env->GetStringUTFChars(input, nullptr);
    std::string prompt(input_str);
    env->ReleaseStringUTFChars(input, input_str);
    LOGD("Running test with input: %s", prompt.c_str());
    session->llm->reset();
    std::ostringstream response_stream;
    session->llm->response(prompt, &response_stream);
    std::string response = response_stream.str();
    LOGD("Test result: %s", response.c_str());
    return env->NewStringUTF(response.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_llmapp_jni_NativeLib_getNativeVersion(JNIEnv* env, jobject thiz) {
    return env->NewStringUTF("1.0.0-mnn");
}

JNIEXPORT jstring JNICALL
Java_com_llmapp_jni_NativeLib_getLastTiming(JNIEnv* env, jobject thiz) {
    char buf[128];
    snprintf(buf, sizeof(buf), "prefill=%lld ms, decode=%lld ms",
             (long long)(g_last_prefill_us / 1000),
             (long long)(g_last_decode_us / 1000));
    return env->NewStringUTF(buf);
}

JNIEXPORT jlong JNICALL
Java_com_llmapp_jni_NativeLib_getLastTTFT(JNIEnv* env, jobject thiz) {
    return static_cast<jlong>(g_last_ttft_us);
}

JNIEXPORT jint JNICALL
Java_com_llmapp_jni_NativeLib_getLastOutputTokens(JNIEnv* env, jobject thiz) {
    return static_cast<jint>(g_last_output_tokens);
}

JNIEXPORT void JNICALL
Java_com_llmapp_jni_NativeLib_inferenceStream(
        JNIEnv* env, jobject thiz, jlong ptr,
        jstring prompt, jstring image_path, jobject callback) {

    if (ptr == 0 || prompt == nullptr || callback == nullptr) {
        LOGE("inferenceStream: null session, prompt or callback");
        return;
    }
    auto* session = reinterpret_cast<LlmSession*>(ptr);
    if (!session->is_loaded || session->llm == nullptr) {
        LOGE("inferenceStream: model not loaded");
        return;
    }

    const char* prompt_str = env->GetStringUTFChars(prompt, nullptr);
    std::string prompt_copy(prompt_str);
    env->ReleaseStringUTFChars(prompt, prompt_str);

    std::string image_path_str;
    if (image_path != nullptr) {
        const char* img_str = env->GetStringUTFChars(image_path, nullptr);
        image_path_str = std::string(img_str);
        env->ReleaseStringUTFChars(image_path, img_str);
        if (!image_path_str.empty()) {
            LOGD("Multimodal inference with image: %s", image_path_str.c_str());
        }
    }

    LOGD("Starting streaming inference, history size: %zu", session->history.size());

    jobject callback_global = env->NewGlobalRef(callback);
    jclass callback_class = env->GetObjectClass(callback);
    if (callback_class == nullptr) {
        LOGE("Failed to get callback class");
        env->DeleteGlobalRef(callback_global);
        return;
    }
    jmethodID on_token_id = env->GetMethodID(callback_class, "onToken", "(Ljava/lang/String;)V");
    jmethodID on_complete_id = env->GetMethodID(callback_class, "onComplete", "(Ljava/lang/String;)V");
    jmethodID on_error_id = env->GetMethodID(callback_class, "onError", "(Ljava/lang/String;)V");
    if (on_token_id == nullptr || on_complete_id == nullptr || on_error_id == nullptr) {
        LOGE("Failed to get callback method IDs");
        env->DeleteGlobalRef(callback_global);
        return;
    }

    session->history.push_back({"user", prompt_copy});
    session->stop_requested = false;
    session->generate_end = false;

    std::thread([callback_global, on_token_id, on_complete_id, on_error_id,
                 prompt_copy, image_path_str, session]() {
        bool need_detach = false;
        JNIEnv* env = getEnv(&need_detach);
        if (env == nullptr) {
            LOGE("Failed to get JNIEnv in inference thread");
            JNIEnv* tmpEnv = nullptr;
            if (g_jvm != nullptr && g_jvm->AttachCurrentThread(&tmpEnv, nullptr) == JNI_OK) {
                tmpEnv->DeleteGlobalRef(callback_global);
                g_jvm->DetachCurrentThread();
            }
            return;
        }

        std::string full_response;
        std::mutex response_mutex;

        class LlmStreamBuffer : public std::streambuf {
        public:
            using Callback = std::function<void(const char* str, size_t len)>;
            LlmStreamBuffer(Callback callback) : callback_(std::move(callback)) {}
        protected:
            std::streamsize xsputn(const char* s, std::streamsize n) override {
                if (callback_) callback_(s, n);
                return n;
            }
            int overflow(int c) override {
                if (c != EOF && callback_) { char ch = static_cast<char>(c); callback_(&ch, 1); }
                return c;
            }
            int sync() override { return 0; }
        private:
            Callback callback_ = nullptr;
        };

        std::string batched_token;
        auto last_token_time = std::chrono::steady_clock::now();
        const auto batch_interval = std::chrono::milliseconds(30);
        bool first_token_received = false;
        int output_token_cnt = 0;
        auto t_start = std::chrono::steady_clock::now();

        Utf8StreamProcessor utf8_processor([&](const std::string& complete_chars) {
            std::lock_guard<std::mutex> lock(response_mutex);
            full_response += complete_chars;
            batched_token += complete_chars;
            output_token_cnt++;
            if (!first_token_received) {
                first_token_received = true;
                auto t_now = std::chrono::steady_clock::now();
                g_last_ttft_us = std::chrono::duration_cast<std::chrono::microseconds>(t_now - t_start).count();
            }
            auto now = std::chrono::steady_clock::now();
            if (now - last_token_time >= batch_interval || batched_token.size() >= 16) {
                if (!batched_token.empty()) {
                    callJavaMethod(env, callback_global, on_token_id, batched_token);
                    batched_token.clear();
                }
                last_token_time = now;
            }
        });

        auto stream_callback = [&utf8_processor](const char* str, size_t len) {
            utf8_processor.processStream(str, len);
        };

        LlmStreamBuffer stream_buffer{stream_callback};
        std::ostream output_ostream(&stream_buffer);

        auto* ctx = session->llm->getContext();
        if (ctx->status != MNN::Transformer::LlmStatus::RUNNING) {
            LOGD("Resetting model status from %d to RUNNING", static_cast<int>(ctx->status));
            const_cast<MNN::Transformer::LlmContext*>(ctx)->status = MNN::Transformer::LlmStatus::RUNNING;
        }
        session->llm->reset();

        if (prompt_copy.empty()) {
            LOGE("Empty prompt");
            callJavaMethod(env, callback_global, on_error_id, "Empty prompt");
            env->DeleteGlobalRef(callback_global);
            detachIfNeeded(need_detach);
            return;
        }

        try {
            bool reuseKV = session->llm->reuse_kv();
            if (reuseKV) {
                LOGD("reuse_kv mode: only sending current prompt, history via KV cache");
                if (!image_path_str.empty()) {
                    MNN::Transformer::MultimodalPrompt mp;
                    mp.prompt_template = "<img>" + image_path_str + "</img>\n" + prompt_copy;
                    session->llm->response(mp, &output_ostream, nullptr, -1);
                } else {
                    session->llm->response(prompt_copy, &output_ostream, nullptr, -1);
                }
            } else {
                if (!image_path_str.empty()) {
                    MNN::Transformer::MultimodalPrompt mp;
                    mp.prompt_template = "<img>" + image_path_str + "</img>\n" + prompt_copy;
                    session->llm->response(mp, &output_ostream, nullptr, -1);
                } else {
                    session->llm->response(session->history, &output_ostream, nullptr, -1);
                }
            }
        } catch (const std::exception& e) {
            LOGE("Exception during response: %s", e.what());
            callJavaMethod(env, callback_global, on_error_id, std::string("Exception: ") + e.what());
            env->DeleteGlobalRef(callback_global);
            detachIfNeeded(need_detach);
            return;
        } catch (...) {
            LOGE("Unknown exception during response");
            callJavaMethod(env, callback_global, on_error_id, "Unknown exception");
            env->DeleteGlobalRef(callback_global);
            detachIfNeeded(need_detach);
            return;
        }

        LOGD("Inference complete, full response length: %zu", full_response.size());

        if (!batched_token.empty()) {
            callJavaMethod(env, callback_global, on_token_id, batched_token);
            batched_token.clear();
        }

        g_last_prefill_us = ctx->prefill_us;
        g_last_decode_us = ctx->decode_us;
        g_last_output_tokens = output_token_cnt;
        if (!first_token_received) {
            auto t_end = std::chrono::steady_clock::now();
            g_last_ttft_us = std::chrono::duration_cast<std::chrono::microseconds>(t_end - t_start).count();
        }
        LOGD("Timing: prefill=%lld us, decode=%lld us, ttft=%lld us, tokens=%d",
             (long long)g_last_prefill_us, (long long)g_last_decode_us,
             (long long)g_last_ttft_us, g_last_output_tokens);

        if (!full_response.empty()) {
            session->history.push_back({"assistant", full_response});
        }

        callJavaMethod(env, callback_global, on_complete_id, full_response);

        env->DeleteGlobalRef(callback_global);
        detachIfNeeded(need_detach);
    }).detach();
}

// ── Embedding / RAG ───────────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_com_llmapp_jni_NativeLib_createEmbeddingSession(JNIEnv* env, jobject thiz, jstring model_path) {
    if (model_path == nullptr) {
        LOGE("createEmbeddingSession: null model_path");
        return 0;
    }
    const char* path = env->GetStringUTFChars(model_path, nullptr);
    auto* session = new EmbeddingSession();
    session->config_path = std::string(path);
    env->ReleaseStringUTFChars(model_path, path);
    LOGD("Embedding session created at %p", session);
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL
Java_com_llmapp_jni_NativeLib_destroyEmbeddingSession(JNIEnv* env, jobject thiz, jlong ptr) {
    if (ptr == 0) return;
    auto* session = reinterpret_cast<EmbeddingSession*>(ptr);
    delete session->tokenizer;
    session->tokenizer = nullptr;
    if (session->mnn_session && session->interpreter) {
        session->interpreter->releaseSession(session->mnn_session);
    }
    session->mnn_session = nullptr;
    session->interpreter.reset();
    delete session;
    LOGD("Embedding session destroyed");
}

JNIEXPORT jboolean JNICALL
Java_com_llmapp_jni_NativeLib_loadEmbeddingModel(JNIEnv* env, jobject thiz, jlong ptr) {
    if (ptr == 0) return JNI_FALSE;
    auto* session = reinterpret_cast<EmbeddingSession*>(ptr);
    if (session->is_loaded) return JNI_TRUE;

    std::string dir = session->config_path;
    if (dir.back() != '/' && dir.back() != '\\') dir += "/";
    std::string config_path = dir + "config.json";
    LOGD("Loading embedding model from: %s", config_path.c_str());

    std::ifstream fs(config_path);
    if (!fs.good()) {
        LOGE("config.json not found: %s", config_path.c_str());
        return JNI_FALSE;
    }
    std::string json_str((std::istreambuf_iterator<char>(fs)), std::istreambuf_iterator<char>());
    fs.close();

    rapidjson::Document doc;
    doc.Parse(json_str.c_str());
    if (doc.HasParseError()) {
        LOGE("Failed to parse config.json");
        return JNI_FALSE;
    }

    std::string model_file = dir + (doc.HasMember("llm_model") ?
        doc["llm_model"].GetString() : "bge_small_zh.mnn");
    std::string tokenizer_file = dir + (doc.HasMember("tokenizer_file") ?
        doc["tokenizer_file"].GetString() : "tokenizer.txt");

    LOGD("  model: %s", model_file.c_str());
    LOGD("  tokenizer: %s", tokenizer_file.c_str());

    // Load tokenizer
    session->tokenizer = MNN::Transformer::Tokenizer::createTokenizer(tokenizer_file);
    if (session->tokenizer == nullptr) {
        LOGE("Failed to create tokenizer: %s", tokenizer_file.c_str());
        return JNI_FALSE;
    }
    LOGD("Tokenizer loaded");

    // Use Interpreter API for direct inference
    auto interpreter = std::unique_ptr<MNN::Interpreter>(MNN::Interpreter::createFromFile(model_file.c_str()));
    if (interpreter == nullptr) {
        LOGE("Interpreter::createFromFile failed: %s", model_file.c_str());
        delete session->tokenizer;
        session->tokenizer = nullptr;
        return JNI_FALSE;
    }

    // Configure: CPU backend, 4 threads, Tensor mode with I/O names
    MNN::ScheduleConfig sconfig;
    sconfig.type = MNN_FORWARD_CPU;
    sconfig.numThread = 4;
    sconfig.path.mode = MNN::ScheduleConfig::Path::Tensor;
    sconfig.path.inputs = {"input_ids", "attention_mask"};
    sconfig.path.outputs = {"last_hidden_state"};

    auto sess = interpreter->createSession(sconfig);
    if (sess == nullptr) {
        // Fallback: try Op mode
        sconfig.path.mode = MNN::ScheduleConfig::Path::Op;
        sess = interpreter->createSession(sconfig);
        if (sess == nullptr) {
            LOGE("createSession failed in both modes");
            delete session->tokenizer;
            session->tokenizer = nullptr;
            return JNI_FALSE;
        }
    }

    // Resize to dummy input to determine embedding dim
    auto* in_ids = interpreter->getSessionInput(sess, "input_ids");
    auto* in_mask = interpreter->getSessionInput(sess, "attention_mask");
    auto* out = interpreter->getSessionOutput(sess, "last_hidden_state");
    if (in_ids == nullptr || in_mask == nullptr || out == nullptr) {
        LOGE("Failed to get I/O tensors: ids=%p mask=%p out=%p", in_ids, in_mask, out);
        interpreter->releaseSession(sess);
        delete session->tokenizer;
        session->tokenizer = nullptr;
        return JNI_FALSE;
    }

    // Store max sequence length from original tensor shape
    session->max_seq_len = in_ids->channel();  // [1, 512, 1, 1] -> channel=512
    int max_len = session->max_seq_len;

    LOGD("Max seq len = %d", max_len);

    // Don't resize — use the full 512-length tensor with padding
    // This avoids COMPUTE_SIZE_ERROR with dynamic shapes
    int* id_data = in_ids->host<int>();
    int* mask_data = in_mask->host<int>();
    std::fill(id_data, id_data + max_len, 0);
    std::fill(mask_data, mask_data + max_len, 0);
    id_data[0] = 101;   // [CLS]
    id_data[1] = 102;   // [SEP]
    mask_data[0] = 1;
    mask_data[1] = 1;

    auto result = interpreter->runSession(sess);
    if (result != MNN::NO_ERROR) {
        LOGE("Dummy forward failed with code %d", result);
        interpreter->releaseSession(sess);
        delete session->tokenizer;
        session->tokenizer = nullptr;
        return JNI_FALSE;
    }

    // Read output to get dim. Output is [1, 512, 512] NCHW (batch, seq_len, hidden_size)
    // height() = hidden_size = 512
    float* out_data = out->host<float>();
    int out_size = out->elementSize();
    LOGD("Dummy forward ok, output elems=%d, shape=[%d,%d,%d]", out_size,
         out->batch(), out->channel(), out->height());

    session->dim = out->height();  // hidden_size = 512
    LOGD("Embedding dim = %d", session->dim);

    // Store the interpreter + session instead of Express module
    session->interpreter = std::move(interpreter);
    session->mnn_session = sess;

    session->is_loaded = true;
    LOGD("Embedding model loaded, dim=%d", session->dim);
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_llmapp_jni_NativeLib_getEmbeddingDim(JNIEnv* env, jobject thiz, jlong ptr) {
    if (ptr == 0) return 0;
    auto* session = reinterpret_cast<EmbeddingSession*>(ptr);
    return session->dim;
}

JNIEXPORT jfloatArray JNICALL
Java_com_llmapp_jni_NativeLib_computeEmbedding(JNIEnv* env, jobject thiz, jlong ptr, jstring text) {
    if (ptr == 0 || text == nullptr) {
        return env->NewFloatArray(0);
    }
    auto* session = reinterpret_cast<EmbeddingSession*>(ptr);
    if (!session->is_loaded || session->tokenizer == nullptr ||
        session->interpreter == nullptr || session->mnn_session == nullptr) {
        LOGE("computeEmbedding: model not loaded");
        return env->NewFloatArray(0);
    }

    const char* input_str = env->GetStringUTFChars(text, nullptr);
    std::string input(input_str);
    env->ReleaseStringUTFChars(text, input_str);

    // Tokenize
    std::vector<int> ids = session->tokenizer->encode(input);
    if (ids.empty()) {
        LOGE("computeEmbedding: empty tokenization");
        return env->NewFloatArray(0);
    }

    // MNN's load_special() doesn't support suffix_tokens_, so manually append [SEP]=102
    // encode() already prepends [CLS]=101 via prefix_tokens_ (if configured in tokenizer.txt)
    ids.push_back(102);

    // Diagnostic: log first 10 token IDs
    {
        std::string tok_str;
        int n = std::min(10, static_cast<int>(ids.size()));
        for (int i = 0; i < n; i++) {
            if (i > 0) tok_str += ", ";
            tok_str += std::to_string(ids[i]);
        }
        LOGD("computeEmbedding: input=\"%s\"..., ids[0..%d]=[%s] (total=%d)", input.substr(0, 60).c_str(), n-1, tok_str.c_str(), (int)ids.size());
    }

    // Truncate to max_seq_len, pad with zeros to full length
    int max_len = session->max_seq_len > 0 ? session->max_seq_len : 512;
    int seq_len = std::min(static_cast<int>(ids.size()), max_len);

    // Get tensors at their original (full) size, no resize
    auto* in_ids = session->interpreter->getSessionInput(session->mnn_session, "input_ids");
    auto* in_mask = session->interpreter->getSessionInput(session->mnn_session, "attention_mask");
    auto* out = session->interpreter->getSessionOutput(session->mnn_session, "last_hidden_state");
    if (in_ids == nullptr || in_mask == nullptr || out == nullptr) {
        LOGE("computeEmbedding: failed to get tensors");
        return env->NewFloatArray(0);
    }

    // Fill with padding (all zeros, then fill actual tokens)
    int* id_data = in_ids->host<int>();
    int* mask_data = in_mask->host<int>();
    std::fill(id_data, id_data + max_len, 0);
    std::fill(mask_data, mask_data + max_len, 0);
    for (int i = 0; i < seq_len; i++) {
        id_data[i] = ids[i];
        mask_data[i] = 1;
    }

    // Run inference
    auto result = session->interpreter->runSession(session->mnn_session);
    if (result != MNN::NO_ERROR) {
        LOGE("computeEmbedding: runSession failed code=%d", result);
        return env->NewFloatArray(0);
    }

    // Read output. Output is [1, 512, 512] NCHW (batch, seq_len, hidden_size)
    // CLS token is at position 0: out_data[0..dim-1] = first 512 elements in NCHW layout
    float* out_data = out->host<float>();
    int dim = session->dim;
    int out_total = out->elementSize();
    LOGD("computeEmbedding: output elems=%d, dim=%d, seq_len=%d", out_total, dim, seq_len);
    if (out_total <= 0 || out_data == nullptr) {
        LOGE("computeEmbedding: output empty");
        return env->NewFloatArray(0);
    }

    // CLS embedding = first dim elements of flat NCHW array (position 0)
    {
        float norm = 0.f;
        for (int i = 0; i < dim; i++) norm += out_data[i] * out_data[i];
        norm = std::sqrt(norm);
        LOGD("computeEmbedding: out[0..4]=[%.4f, %.4f, %.4f, %.4f, %.4f] norm=%.4f dim=%d",
             out_data[0], out_data[1], out_data[2], out_data[3], out_data[4], norm, dim);
    }
    jfloatArray jresult = env->NewFloatArray(dim);
    env->SetFloatArrayRegion(jresult, 0, dim, out_data);
    return jresult;
}

} // extern "C"
