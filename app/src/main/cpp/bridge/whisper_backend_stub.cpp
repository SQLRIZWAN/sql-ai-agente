// whisper_backend_stub.cpp — linked when whisper.cpp sources are not vendored.
#include <string>
#include "internal.h"

struct ModelHandle;

bool whisper_available() { return false; }

std::string whisper_transcribe(const char*, const char*) { return ""; }
