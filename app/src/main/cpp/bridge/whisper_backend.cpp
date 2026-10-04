// whisper_backend.cpp — real whisper.cpp STT (compiled only with -DJARVIS_ENABLE_WHISPER=ON).
#ifdef JARVIS_HAVE_WHISPER
#include "whisper.h"
#include <string>
#include <vector>
#include <cstdio>
#include "internal.h"

struct ModelHandle;

bool whisper_available() { return true; }

std::string whisper_transcribe(const char* wavPath, const char* modelPath) {
    if (!wavPath || !modelPath) return "";
    whisper_context_params cparams = whisper_context_default_params();
    whisper_context* ctx = whisper_init_from_file_with_params(modelPath, cparams);
    if (!ctx) return "";

    // PCM 16 kHz mono s16 expected; loader is provided by the Kotlin audio capture path.
    std::vector<float> pcm;
    FILE* f = fopen(wavPath, "rb");
    if (f) {
        fseek(f, 44, SEEK_SET); // skip canonical WAV header
        short s;
        while (fread(&s, sizeof(short), 1, f) == 1) pcm.push_back(s / 32768.0f);
        fclose(f);
    }
    std::string result;
    if (!pcm.empty()) {
        whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        params.n_threads = 4;
        params.print_progress = false;
        params.print_special = false;
        if (whisper_full(ctx, params, pcm.data(), (int)pcm.size()) == 0) {
            const int n = whisper_full_n_segments(ctx);
            for (int i = 0; i < n; ++i) {
                result += whisper_full_get_segment_text(ctx, i);
            }
        }
    }
    whisper_free(ctx);
    return result;
}
#endif // JARVIS_HAVE_WHISPER
