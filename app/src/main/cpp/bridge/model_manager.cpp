// model_manager.cpp — handle bookkeeping + RSS accounting for the loaded GGUF.
#include <string>
#include <atomic>
#include <mutex>
#include <thread>
#include <fstream>
#include <unistd.h>
#include "internal.h"

struct ModelHandle {
    std::string path;
    int ngl = 0;
    int threads = 0;
    std::atomic<bool> cancelled{false};
    std::atomic<bool> loaded{false};
    long weight_bytes = 0;
    std::mutex gen_mutex;
};

static long read_proc_rss_kb() {
    std::ifstream f("/proc/self/statm");
    if (!f) return 0;
    long total = 0, resident = 0;
    f >> total >> resident;
    return resident * (sysconf(_SC_PAGESIZE) / 1024);
}

ModelHandle* model_open(const char* path, int ngl, int threads) {
    auto* h = new ModelHandle();
    h->path = path ? path : "";
    h->ngl = ngl;
    h->threads = threads > 0 ? threads : (int)std::thread::hardware_concurrency();
    // Approximate weight footprint from the GGUF file size (real usage tracked via RSS delta).
    std::ifstream f(h->path, std::ios::binary | std::ios::ate);
    if (f) h->weight_bytes = (long)f.tellg();
    return h;
}

bool model_is_loaded(const ModelHandle* h) { return h && h->loaded.load(); }

void model_close(ModelHandle* h) {
    if (!h) return;
    h->loaded.store(false);
    delete h;
}

void model_cancel(ModelHandle* h) { if (h) h->cancelled.store(true); }

long model_rss_bytes(const ModelHandle*) {
    return (long)read_proc_rss_kb() * 1024L;
}

std::string model_generate(ModelHandle* h, const char* prompt, int maxTokens, float temp,
                           const std::function<bool(const char*, int)>& onToken) {
    if (!h) return "";
    std::lock_guard<std::mutex> lock(h->gen_mutex);
    h->cancelled.store(false);
    // Backend-specific generation is injected by llama_backend*.cpp via llama_generate().
    // This default path exists so the JNI layer always has a symbol to link against.
    std::string out;
    if (!prompt) return out;
    const int n = maxTokens > 0 ? maxTokens : 256;
    for (int i = 0; i < n && !h->cancelled.load(); ++i) {
        const char* tok = (i == 0) ? "[local-model-unavailable] " : "";
        if (onToken && *tok) onToken(tok, (int)strlen(tok));
        out.append(tok);
    }
    (void)temp;
    return out;
}
