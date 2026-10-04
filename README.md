# SQL AI AGENTE

**Autonomous Local AI Orchestrator & Trading Engine for Android (10 → 17)**

Privacy-first hybrid architecture: Native Android UI + C++ NDK inference + embedded
Linux sandbox + automated app controller + quantitative trading engine. **No telemetry,
no analytics, no external logging — everything stays on-device.**

```
[ Jetpack Compose UI · 5 tabs ]
         │
         ├── Local Secure Vault ....... Encrypted SQLite (SQLCipher) + Android Keystore AES-256-GCM
         ├── AI Provider Engine ....... NDK llama.cpp / whisper.cpp + Gemini/Grok/DeepSeek/OpenAI/Ollama
         ├── Embedded Linux Sandbox ... PTY shell (Termux-style) + Bash/Python runtime
         ├── Device Controller ........ AccessibilityService + ML Kit OCR + GestureDescription
         └── Quant Trading Engine ..... Binance / Bitget / Bybit / Exness (REST + WebSocket)
```

## Modules

| Path | Responsibility |
|------|----------------|
| `core/vault/Vault.kt` | AES-256-GCM envelope under Android Keystore; seals all API keys/signals |
| `core/ai/ProviderRegistry.kt` | Multi-provider chat with automatic fallback chain |
| `core/ai/ModelMemoryGovernor.kt` | Evicts GGUF weights on background/trim/thermal-throttle |
| `core/linux/LinuxSubsystem.kt` | PTY-backed sandbox (`fork`+`openpty`+`execve` in `proc_exec.cpp`) |
| `core/automation/JarvisAccessibilityService.kt` | UI-tree parsing, node resolution, gesture dispatch |
| `core/trading/TradingEngine.kt` | WS market data, RSI/MACD/EMA, SL/TP risk gates, signed REST orders |
| `core/monitor/SystemMonitor.kt` | 1 Hz CPU/RAM/thermal sampling via `/proc` (native) |
| `data/db/AppDatabase.kt` | SQLCipher store: chat, trades, workflows, scripts |
| `app/src/main/cpp/` | JNI bridge: `sqlai_core.so` (model lifecycle) + `sqlai_proc.so` (PTY) |

## Build

```bash
./gradlew :app:assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease      # R8 minified (provide your own keystore)
```

Requirements: JDK 17, Android SDK 35, NDK 26.3.11579264, CMake 3.22.1.

### Optional: enable local LLM / STT backends

The APK builds with stub backends so it runs without model weights. To compile the
real engines, vendor the sources and flip the CMake flags in `app/build.gradle.kts`:

```bash
git clone --depth 1 https://github.com/ggerganov/llama.cpp \
  app/src/main/cpp/llama/llama.cpp
git clone --depth 1 https://github.com/ggerganov/whisper.cpp \
  app/src/main/cpp/whisper/whisper.cpp
```

```kotlin
arguments += listOf("-DJARVIS_ENABLE_LLAMA=ON", "-DJARVIS_ENABLE_WHISPER=ON")
```

Drop a quantized GGUF (e.g. Qwen-2.5-Coder-3B-Q4, Llama-3.2-1B-Q4) into app-private
storage and load it from the Agent tab.

### Host toolchain note (aarch64 Linux)

Android's SDK/NDK ship x86_64 host binaries. On ARM64 hosts without `binfmt_misc`,
`scripts/wrap_x86_tools.sh` wraps each tool with `qemu-x86_64-static -L /opt/x86_64-sysroot`,
preserving `argv[0]` (`-0 "$0"`) so multi-call drivers (`clang++`, `ld.lld`) dispatch
correctly.

## Privacy guarantees

- No telemetry / crash-reporting / analytics SDKs are linked.
- `network_security_config` blocks cleartext; only user-configured endpoints are reached.
- Backup & device-transfer of app data is disabled (`data_extraction_rules.xml`).
- Vault keys live only in TEE/StrongBox-backed Keystore; DB passphrase is sealed, never logged.
- `onTrimMemory`/`onStop` unload native models so background trading keeps RAM headroom.

## Automation ethics

The Accessibility controller operates **only on user request** and only with the
permission the user grants in system settings. It is designed for your own device and
your own accounts.
