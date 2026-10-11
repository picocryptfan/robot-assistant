// :BRAIN PROCESS ONLY. Robot's JNI bridge to llama.cpp (0.4.4, GGUF model files), built by tools/build_llama.sh into
// librobot_llama.so together with llama.cpp, ggml and KleidiAI from their pinned sources (THIRD_PARTY.md). Loaded only by
// brain/LlamaNative.kt, for a file you imported that Robot's own check (core Gguf.kt) read first.
//
// Minimal on purpose: load a model (memory-mapped, the CPU only), format a conversation with the model's own chat
// template (llama.cpp's Jinja support, tokenizer.chat_template), count its tokens with the model's tokenizer, stream a
// reply with a cancel flag, report timings, free everything. Text only. Nothing here logs: llama.cpp's own log goes to a
// callback that drops it, and the shared log of its common library is paused.
//
// Threads: every call but cancel() comes from LlamaEngine's one worker thread; cancel() may come from any thread and
// only raises an atomic counter (read between tokens, and inside llama_decode through the abort callback).

#include <jni.h>

#include <cerrno>
#include <cstdlib>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <exception>
#include <new>
#include <string>
#include <vector>

#include "llama.h"
#include "chat.h"
#include "log.h"

namespace {

// Kept in step with LlamaNative.kt.
constexpr jint GEN_END = 0;           // the model ended its turn (an end-of-generation token)
constexpr jint GEN_LENGTH = 1;        // maxTokens reached
constexpr jint GEN_CANCELLED = 2;     // cancel(), or the sink asked to stop
constexpr jint GEN_CONTEXT_FULL = 3;  // no room left in the context for another token
constexpr jint GEN_NOT_PREPARED = -1; // no prompt was prepared
constexpr jint GEN_DECODE_FAILED = -2;

// Why load() returned 0 (LlamaNative.LOAD_*).
constexpr jint LOAD_MODEL = 1;
constexpr jint LOAD_CONTEXT = 2;
constexpr jint LOAD_TEMPLATE = 3;

constexpr jint PREP_TEMPLATE_FAILED = -1;
constexpr jint PREP_TOKENIZE_FAILED = -2;
constexpr jint PREP_TOO_LONG = -3;

// Stats slots (LlamaNative.STATS_*).
enum Stat { S_PROMPT = 0, S_REUSED, S_PROMPT_MS, S_GEN, S_GEN_MS, S_FIRST_MS, S_POSITION, S_COUNT };

struct Robot {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    llama_sampler * sampler = nullptr;
    common_chat_templates_ptr templates;
    // The tokens in the context's memory (sequence 0), in order: what a new prompt can reuse.
    std::vector<llama_token> cached;
    // The last prepared prompt.
    std::vector<llama_token> prepared;
    // Runs are numbered by LlamaEngine; cancel(n) stops run n and any earlier one, even when it comes before run n starts.
    std::atomic<int64_t> run{0};
    std::atomic<int64_t> cancel_upto{0};
    int n_ctx = 0;
    int n_batch = 512;
};

void quiet_log(ggml_log_level, const char *, void *) {}

/** ggml's fatal errors end the :brain process (abort), without printing anything or starting a debugger. */
void quiet_abort(const char *) {}

bool cancelled(const Robot * r) {
    return r->cancel_upto.load(std::memory_order_relaxed) >= r->run.load(std::memory_order_relaxed);
}

bool abort_requested(void * data) {
    return cancelled(static_cast<Robot *>(data));
}

Robot * of(jlong h) { return reinterpret_cast<Robot *>(static_cast<intptr_t>(h)); }

/** The bytes of a Java byte[] (UTF-8 from Kotlin: never JNI's modified UTF-8, which mangles emoji). */
std::string bytes(JNIEnv * env, jbyteArray a) {
    if (a == nullptr) return {};
    const jsize n = env->GetArrayLength(a);
    std::string s(static_cast<size_t>(n), '\0');
    if (n > 0) env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(&s[0]));
    return s;
}

/** How many leading bytes of [s] end on a whole UTF-8 character (a piece can end inside one). */
size_t whole_utf8(const std::string & s) {
    size_t n = s.size();
    // Look back at most 3 bytes for the start of the last character.
    for (size_t back = 1; back <= 4 && back <= n; ++back) {
        const unsigned char c = static_cast<unsigned char>(s[n - back]);
        if ((c & 0xC0) == 0x80) continue; // a continuation byte
        size_t len = 1;
        if ((c & 0xE0) == 0xC0) len = 2;
        else if ((c & 0xF0) == 0xE0) len = 3;
        else if ((c & 0xF8) == 0xF0) len = 4;
        return back >= len ? n : n - back;
    }
    return n;
}

void free_robot(Robot * r) {
    if (r == nullptr) return;
    r->templates.reset();
    if (r->sampler) llama_sampler_free(r->sampler);
    if (r->ctx) llama_free(r->ctx);
    if (r->model) llama_model_free(r->model);
    delete r;
}

/** Clears the context's memory: after an abort or an error its state is only partly known. */
void forget(Robot * r) {
    llama_memory_clear(llama_get_memory(r->ctx), true);
    r->cached.clear();
}

double ms_since(std::chrono::steady_clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
}

} // namespace

extern "C" {

// ggml's backtrace on a fatal error forks a debugger (gdb, lldb). The link wraps fork and execlp (CMakeLists.txt) so this
// library can never start a process: these stand in for them and always fail. GGML_NO_BACKTRACE and the abort callback
// below keep ggml from trying at all. Not exported (exports.map).
pid_t __wrap_fork(void) {
    errno = ENOSYS;
    return -1;
}

int __wrap_execlp(const char *, const char *, ...) {
    errno = ENOSYS;
    return -1;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *, void *) {
    setenv("GGML_NO_BACKTRACE", "1", 1);
    ggml_set_abort_callback(quiet_abort);
    llama_log_set(quiet_log, nullptr);
    common_log_pause(common_log_main());
    llama_backend_init();
    return JNI_VERSION_1_6;
}

/**
 * Loads the model at [path] memory-mapped on the CPU and makes a context of [nCtx] tokens. [sampling]: top-k, top-p,
 * min-p, temperature (llama.cpp's own defaults unless Robot says otherwise). 0 on failure, and [why] (out, 1 element)
 * says which part failed: LOAD_MODEL, LOAD_CONTEXT or LOAD_TEMPLATE.
 */
JNIEXPORT jlong JNICALL Java_app_heyrobot_brain_LlamaNative_load(JNIEnv * env, jclass, jbyteArray path, jint nCtx, jint threads,
        jint batchThreads, jfloatArray sampling, jintArray why) {
    Robot * r = nullptr;
    jint stage = LOAD_MODEL;
    const auto failed = [&]() -> jlong {
        free_robot(r);
        if (why != nullptr && env->GetArrayLength(why) >= 1) env->SetIntArrayRegion(why, 0, 1, &stage);
        return 0;
    };
    try {
        const std::string file = bytes(env, path);
        float s[4] = {40.0f, 0.95f, 0.05f, 0.8f};
        if (sampling != nullptr && env->GetArrayLength(sampling) >= 4) env->GetFloatArrayRegion(sampling, 0, 4, s);
        r = new Robot();
        llama_model_params mp = llama_model_default_params();
        mp.n_gpu_layers = 0;
        mp.load_mode = LLAMA_LOAD_MODE_MMAP;
        mp.check_tensors = false;
        mp.progress_callback = nullptr;
        r->model = llama_model_load_from_file(file.c_str(), mp);
        if (r->model == nullptr) return failed();
        stage = LOAD_CONTEXT;
        r->vocab = llama_model_get_vocab(r->model);
        llama_context_params cp = llama_context_default_params();
        cp.n_ctx = static_cast<uint32_t>(nCtx);
        cp.n_batch = static_cast<uint32_t>(r->n_batch);
        cp.n_ubatch = static_cast<uint32_t>(r->n_batch);
        cp.n_seq_max = 1;
        cp.n_threads = threads;
        cp.n_threads_batch = batchThreads;
        cp.no_perf = false;
        // The whole sliding-window cache (Gemma 3 and others), so a reply can reuse any prefix of the last prompt.
        cp.swa_full = true;
        cp.abort_callback = abort_requested;
        cp.abort_callback_data = r;
        r->ctx = llama_init_from_model(r->model, cp);
        if (r->ctx == nullptr) return failed();
        r->n_ctx = static_cast<int>(llama_n_ctx(r->ctx));
        llama_sampler_chain_params sp = llama_sampler_chain_default_params();
        sp.no_perf = true;
        r->sampler = llama_sampler_chain_init(sp);
        llama_sampler_chain_add(r->sampler, llama_sampler_init_top_k(static_cast<int32_t>(s[0])));
        llama_sampler_chain_add(r->sampler, llama_sampler_init_top_p(s[1], 1));
        llama_sampler_chain_add(r->sampler, llama_sampler_init_min_p(s[2], 1));
        llama_sampler_chain_add(r->sampler, llama_sampler_init_temp(s[3]));
        llama_sampler_chain_add(r->sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
        // The file's own template; it throws when the template can't be parsed.
        stage = LOAD_TEMPLATE;
        r->templates = common_chat_templates_init(r->model, "");
        if (!r->templates) return failed();
        return static_cast<jlong>(reinterpret_cast<intptr_t>(r));
    } catch (...) {
        return failed();
    }
}

JNIEXPORT void JNICALL Java_app_heyrobot_brain_LlamaNative_free(JNIEnv *, jclass, jlong h) {
    free_robot(of(h));
}

/** Thread-safe: stops run [run] (and any earlier one), also when it hasn't started yet. */
JNIEXPORT void JNICALL Java_app_heyrobot_brain_LlamaNative_cancel(JNIEnv *, jclass, jlong h, jlong run) {
    Robot * r = of(h);
    if (r == nullptr) return;
    int64_t cur = r->cancel_upto.load();
    while (cur < run && !r->cancel_upto.compare_exchange_weak(cur, run)) {}
}

/** The context the model was trained with (its file's <arch>.context_length, as llama.cpp read it). */
JNIEXPORT jint JNICALL Java_app_heyrobot_brain_LlamaNative_trainContext(JNIEnv *, jclass, jlong h) {
    Robot * r = of(h);
    return r == nullptr ? 0 : llama_model_n_ctx_train(r->model);
}

/**
 * Formats a conversation ([roles]: "system", "user", "assistant"; [texts]: UTF-8) with the model's chat template and the
 * generation prompt, tokenizes it with the model's tokenizer and keeps it for generate(). [think]: the template's
 * enable_thinking. Returns the token count, or a negative PREP_* code. [flags] (out, 1 element): bit 0 the generation
 * prompt opens a <think> block the model is to close, bit 1 the template supports enable_thinking.
 */
JNIEXPORT jint JNICALL Java_app_heyrobot_brain_LlamaNative_prepare(JNIEnv * env, jclass, jlong h, jobjectArray roles,
        jobjectArray texts, jboolean think, jintArray flags) {
    Robot * r = of(h);
    if (r == nullptr) return PREP_TEMPLATE_FAILED;
    r->prepared.clear();
    try {
        const jsize n = env->GetArrayLength(texts);
        if (env->GetArrayLength(roles) != n) return PREP_TEMPLATE_FAILED;
        common_chat_templates_inputs in;
        in.use_jinja = true;
        in.add_generation_prompt = true;
        in.enable_thinking = think == JNI_TRUE;
        for (jsize i = 0; i < n; ++i) {
            common_chat_msg m;
            auto * role = static_cast<jbyteArray>(env->GetObjectArrayElement(roles, i));
            auto * text = static_cast<jbyteArray>(env->GetObjectArrayElement(texts, i));
            m.role = bytes(env, role);
            m.content = bytes(env, text);
            env->DeleteLocalRef(role);
            env->DeleteLocalRef(text);
            in.messages.push_back(std::move(m));
        }
        const common_chat_params p = common_chat_templates_apply(r->templates.get(), in);
        const std::string & prompt = p.prompt;
        if (prompt.empty() || prompt.size() > static_cast<size_t>(INT32_MAX / 2)) return PREP_TEMPLATE_FAILED;
        // A <think> the generation prompt opens and doesn't close: the reply starts inside it.
        const std::string & gen = p.generation_prompt.empty() ? prompt : p.generation_prompt;
        const size_t open = gen.rfind("<think>");
        const size_t close = gen.rfind("</think>");
        const bool opens = open != std::string::npos && (close == std::string::npos || close < open);
        // The tokens: the prompt's text may itself name special tokens (the template's markers).
        std::vector<llama_token> toks(prompt.size() + 8);
        int32_t got = llama_tokenize(r->vocab, prompt.data(), static_cast<int32_t>(prompt.size()), toks.data(),
                static_cast<int32_t>(toks.size()), true, true);
        if (got < 0 && got != INT32_MIN) {
            toks.resize(static_cast<size_t>(-got));
            got = llama_tokenize(r->vocab, prompt.data(), static_cast<int32_t>(prompt.size()), toks.data(),
                    static_cast<int32_t>(toks.size()), true, true);
        }
        if (got <= 0) return PREP_TOKENIZE_FAILED;
        toks.resize(static_cast<size_t>(got));
        if (flags != nullptr && env->GetArrayLength(flags) >= 1) {
            const jint f = (opens ? 1 : 0) | (p.supports_thinking ? 2 : 0);
            env->SetIntArrayRegion(flags, 0, 1, &f);
        }
        if (got >= r->n_ctx) return PREP_TOO_LONG;
        r->prepared = std::move(toks);
        return got;
    } catch (const std::bad_alloc &) {
        return PREP_TEMPLATE_FAILED;
    } catch (...) {
        // A template that throws (a role it refuses, a Jinja feature llama.cpp lacks).
        return PREP_TEMPLATE_FAILED;
    }
}

/**
 * Run [run] (numbered from 1, increasing): reads the prepared prompt (reusing the longest prefix already in the
 * context's memory) and streams the reply to [sink] (an object with `boolean piece(byte[])`: false stops) as whole UTF-8
 * characters, until an end-of-generation token, [maxTokens], a full context or a cancel. [stats] (out, S_COUNT elements):
 * prompt tokens, of them reused, prompt ms, generated tokens, generation ms, ms to the first token, the context position
 * after. Returns a GEN_* code.
 */
JNIEXPORT jint JNICALL Java_app_heyrobot_brain_LlamaNative_generate(JNIEnv * env, jclass, jlong h, jlong run, jint maxTokens,
        jobject sink, jlongArray stats) {
    Robot * r = of(h);
    if (r == nullptr || r->prepared.empty()) return GEN_NOT_PREPARED;
    r->run.store(run, std::memory_order_relaxed);
    jlong st[S_COUNT] = {0, 0, 0, 0, 0, -1, 0};
    jint result = GEN_END;
    const auto write_stats = [&]() {
        st[S_POSITION] = static_cast<jlong>(r->cached.size());
        if (stats != nullptr && env->GetArrayLength(stats) >= S_COUNT) env->SetLongArrayRegion(stats, 0, S_COUNT, st);
    };
    jclass cls = env->GetObjectClass(sink);
    jmethodID piece = cls == nullptr ? nullptr : env->GetMethodID(cls, "piece", "([B)Z");
    if (piece == nullptr) {
        env->ExceptionClear();
        return GEN_NOT_PREPARED;
    }
    try {
        std::vector<llama_token> prompt;
        prompt.swap(r->prepared);
        st[S_PROMPT] = static_cast<jlong>(prompt.size());
        // The longest common prefix with what the memory holds; at least the last token is read again (its logits).
        size_t keep = 0;
        while (keep < r->cached.size() && keep < prompt.size() && r->cached[keep] == prompt[keep]) ++keep;
        if (keep >= prompt.size()) keep = prompt.size() - 1;
        llama_memory_t mem = llama_get_memory(r->ctx);
        if (keep < r->cached.size()) {
            // A sliding-window or recurrent memory can't always drop a part: read everything again then.
            if (!llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(keep), -1)) {
                forget(r);
                keep = 0;
            } else {
                r->cached.resize(keep);
            }
        }
        st[S_REUSED] = static_cast<jlong>(keep);
        llama_sampler_reset(r->sampler);
        const auto t0 = std::chrono::steady_clock::now();
        for (size_t i = keep; i < prompt.size();) {
            const int32_t n = static_cast<int32_t>(std::min<size_t>(static_cast<size_t>(r->n_batch), prompt.size() - i));
            const int32_t rc = llama_decode(r->ctx, llama_batch_get_one(prompt.data() + i, n));
            if (rc != 0) {
                forget(r);
                st[S_PROMPT_MS] = static_cast<jlong>(ms_since(t0));
                write_stats();
                return rc == 2 || cancelled(r) ? GEN_CANCELLED : GEN_DECODE_FAILED;
            }
            r->cached.insert(r->cached.end(), prompt.begin() + static_cast<std::ptrdiff_t>(i), prompt.begin() + static_cast<std::ptrdiff_t>(i + n));
            i += static_cast<size_t>(n);
        }
        st[S_PROMPT_MS] = static_cast<jlong>(ms_since(t0));
        const auto t1 = std::chrono::steady_clock::now();
        std::string pending;
        char buf[256];
        int generated = 0;
        while (true) {
            if (cancelled(r)) { result = GEN_CANCELLED; break; }
            if (generated >= maxTokens) { result = GEN_LENGTH; break; }
            if (static_cast<int>(r->cached.size()) + 1 >= r->n_ctx) { result = GEN_CONTEXT_FULL; break; }
            llama_token tok = llama_sampler_sample(r->sampler, r->ctx, -1);
            if (llama_vocab_is_eog(r->vocab, tok)) { result = GEN_END; break; }
            // Control tokens aren't text, except a thinking block's tags: some files mark <think> as one, and the
            // reply's filter (core ThinkFilter) must see where thinking starts and ends, or its text would show.
            const char * raw = llama_vocab_get_text(r->vocab, tok);
            const bool think_tag = raw != nullptr && (std::strcmp(raw, "<think>") == 0 || std::strcmp(raw, "</think>") == 0);
            int32_t len = llama_token_to_piece(r->vocab, tok, buf, sizeof(buf), 0, think_tag);
            if (len < 0) len = 0; // a piece longer than the buffer: none of Robot's models has one
            pending.append(buf, static_cast<size_t>(len));
            ++generated;
            if (st[S_FIRST_MS] < 0) st[S_FIRST_MS] = static_cast<jlong>(ms_since(t0));
            const size_t whole = whole_utf8(pending);
            if (whole > 0) {
                jbyteArray out = env->NewByteArray(static_cast<jsize>(whole));
                if (out == nullptr) { env->ExceptionClear(); result = GEN_DECODE_FAILED; break; }
                env->SetByteArrayRegion(out, 0, static_cast<jsize>(whole), reinterpret_cast<const jbyte *>(pending.data()));
                const jboolean more = env->CallBooleanMethod(sink, piece, out);
                env->DeleteLocalRef(out);
                pending.erase(0, whole);
                if (env->ExceptionCheck()) { env->ExceptionClear(); result = GEN_CANCELLED; break; }
                if (more != JNI_TRUE) { result = GEN_CANCELLED; break; }
            }
            // The sampled token goes into the memory, so the next reply can reuse it.
            const int32_t rc = llama_decode(r->ctx, llama_batch_get_one(&tok, 1));
            if (rc != 0) {
                forget(r);
                result = rc == 2 || cancelled(r) ? GEN_CANCELLED : GEN_DECODE_FAILED;
                break;
            }
            r->cached.push_back(tok);
        }
        st[S_GEN] = generated;
        st[S_GEN_MS] = static_cast<jlong>(ms_since(t1));
        write_stats();
        return result;
    } catch (...) {
        forget(r);
        write_stats();
        return GEN_DECODE_FAILED;
    }
}

} // extern "C"
