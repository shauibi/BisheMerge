# AGENTS.md

Quick reference for OpenCode sessions working in this repo. See `CLAUDE.md` for full architecture and data-flow details.

## Build

```bash
./gradlew assembleDebug              # debug APK
./gradlew test                       # unit tests
./gradlew test --tests "ClassName"   # single test
./gradlew assembleDebug --stacktrace # verbose on native errors
```

## Constraints Agents Get Wrong

- **NDK/CMake versions are pinned**: SDK 34, NDK 27.2.12479018, CMake 3.22.1 — don't bump without checking native build.
- **KSP version must match Kotlin exactly**: `2.0.21` → `2.0.22-1.0.28` will break. Current pair: `2.0.21` / `2.0.21-1.0.28`.
- **MNN source is a sibling directory**: `CMakeLists.txt` expects it at `../../../../../MNN` relative to `app/src/main/cpp/`. Not in this repo.
- **`libMNN.so` goes in `app/src/main/jniLibs/arm64-v8a/`** — must exist before build.
- **Scoped Storage**: C++ can't read `/storage/emulated/0/`. Documents must be copied to app internal storage via SAF before passing path to JNI.
- **LLM and Embedding sessions are global singletons** (`LLMApplication.sessionPtr` / `embeddingSessionPtr`). Don't create multiple.

## Key Files

| What | Where |
|------|-------|
| JNI declarations | `app/src/main/java/com/llmapp/jni/NativeLib.kt` |
| JNI implementation | `app/src/main/cpp/llm_infer_jni.cpp` |
| CMake config | `app/src/main/cpp/CMakeLists.txt` |
| App singleton | `app/src/main/java/com/llmapp/LLMApplication.kt` |
| Room DB | `app/src/main/java/com/llmapp/data/AppDatabase.kt` |
| RAG pipeline | `app/src/main/java/com/llmapp/data/RagRepository.kt` |
| Vosk speech | `app/src/main/java/com/llmapp/asr/VoskSpeechRecognizer.kt` |

## Style Notes

UI uses 10-16dp rounded corners, muted colors, long-press to delete (no always-visible delete buttons). Follow existing Compose patterns — don't introduce XML layouts for new screens.
