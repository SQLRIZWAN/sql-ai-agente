cmake_minimum_required(VERSION 3.22.1)
project(sqlai_agente LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

option(JARVIS_ENABLE_LLAMA  "Compile llama.cpp GGUF inference backend" OFF)
option(JARVIS_ENABLE_WHISPER "Compile whisper.cpp STT backend" OFF)

add_compile_options(-fvisibility=hidden -ffunction-sections -fdata-sections)
add_link_options(-Wl,--gc-sections -Wl,--build-id=sha1)

# ------------------------------------------------------------------
# core bridge: JNI entrypoints + sysinfo + sandbox process control
# ------------------------------------------------------------------
add_library(sqlai_core SHARED
    bridge/jni_bridge.cpp
    bridge/model_manager.cpp
    sysinfo/sysinfo.cpp
)

target_include_directories(sqlai_core PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(sqlai_core
    ${log-lib}
    ${android-lib}
    atomic
)

# ------------------------------------------------------------------
# Optional: llama.cpp (GGUF local LLM) — vendored under cpp/llama/llama.cpp
# Enable with -DJARVIS_ENABLE_LLAMA=ON after placing sources there.
# ------------------------------------------------------------------
if(JARVIS_ENABLE_LLAMA AND EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/llama/llama.cpp/CMakeLists.txt)
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(LLAMA_VULKAN OFF CACHE BOOL "" FORCE)   # enabled per-device at runtime via ggml backend
    set(LLAMA_OPENMP OFF CACHE BOOL "" FORCE)
    add_subdirectory(llama/llama.cpp EXCLUDE_FROM_ALL)
    target_sources(sqlai_core PRIVATE bridge/llama_backend.cpp)
    target_compile_definitions(sqlai_core PRIVATE JARVIS_HAVE_LLAMA=1)
    target_link_libraries(sqlai_core llama ggml)
    message(STATUS "SQLAI: llama.cpp backend ENABLED")
else()
    target_sources(sqlai_core PRIVATE bridge/llama_backend_stub.cpp)
    message(STATUS "SQLAI: llama.cpp backend stubbed (place sources in cpp/llama/llama.cpp + -DJARVIS_ENABLE_LLAMA=ON)")
endif()

# ------------------------------------------------------------------
# Optional: whisper.cpp STT
# ------------------------------------------------------------------
if(JARVIS_ENABLE_WHISPER AND EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/whisper/whisper.cpp/CMakeLists.txt)
    set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    add_subdirectory(whisper/whisper.cpp EXCLUDE_FROM_ALL)
    target_sources(sqlai_core PRIVATE bridge/whisper_backend.cpp)
    target_compile_definitions(sqlai_core PRIVATE JARVIS_HAVE_WHISPER=1)
    target_link_libraries(sqlai_core whisper ggml)
    message(STATUS "SQLAI: whisper.cpp backend ENABLED")
else()
    target_sources(sqlai_core PRIVATE bridge/whisper_backend_stub.cpp)
    message(STATUS "SQLAI: whisper.cpp backend stubbed")
endif()

# ------------------------------------------------------------------
# proot-style child process helper for the embedded Linux subsystem
# ------------------------------------------------------------------
add_library(sqlai_proc SHARED
    bridge/proc_exec.cpp
)
target_link_libraries(sqlai_proc ${log-lib} ${android-lib})

# Strip unstripped outputs during release packaging (AGP handles via jniLibs.useLegacyPackaging=false)
