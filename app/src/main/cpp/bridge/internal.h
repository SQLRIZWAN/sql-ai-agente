// internal.h — shared internal API between jni_bridge.cpp and the backend files.
#pragma once

#include <string>
#include <functional>

struct ModelHandle;

// model_manager.cpp
ModelHandle* model_open(const char* path, int ngl, int threads);
void model_close(ModelHandle* h);
bool model_is_loaded(const ModelHandle* h);
std::string model_generate(ModelHandle* h, const char* prompt, int maxTokens, float temp,
                           const std::function<bool(const char*, int)>& onToken);
void model_cancel(ModelHandle* h);
long model_rss_bytes(const ModelHandle* h);

// llama backend (real or stub)
bool llama_available();
int llama_load(ModelHandle* h, const char* path, int ngl, int threads);
std::string llama_generate(ModelHandle* h, const char* prompt, int maxTokens, float temp,
                           const std::function<bool(const char*, int)>& onToken);
void llama_unload(ModelHandle* h);

// whisper backend (real or stub)
bool whisper_available();
std::string whisper_transcribe(const char* wavPath, const char* modelPath);
