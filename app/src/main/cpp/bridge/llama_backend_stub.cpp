// llama_backend_stub.cpp — linked when llama.cpp sources are not vendored.
// Keeps JNI symbols resolvable so the APK builds/runs without model weights.
#include <string>
#include <functional>
#include "internal.h"

struct ModelHandle;

bool llama_available() { return false; }

int llama_load(ModelHandle*, const char*, int, int) { return -1; }

std::string llama_generate(ModelHandle*, const char*, int, float,
                           const std::function<bool(const char*, int)>&) {
    return "";
}

void llama_unload(ModelHandle*) {}
