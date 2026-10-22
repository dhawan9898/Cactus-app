# Roadmap

## v0.1 (this scaffold)

- [x] Architecture + design docs
- [x] CI/CD: native engine build, signed APK, GitHub pre-release per push to `main`
- [x] Engine wrapper over `CactusJNI` (init, streaming completion, transcription, stop)
- [x] Model catalog, resumable download, unzip, selection
- [x] Chat UI with streaming, image attach, voice input, TTS toggle
- [x] Tool system (10 tools) with confirmation gate
- [x] Glance Ask widget + WorkManager refresh

> Not yet compiled or run on a device when this was written – the first CI run is the first compile. Expect a fix-up cycle.

## v0.2 (done)

- [x] In-app updater (GitHub releases), settings screen, camera capture
- [x] Multiple conversations (history screen, rename/delete, auto titles, migration of the old single chat)
- [x] Live captions while speaking (streaming transcription, with whole-recording fallback)
- [x] Per-widget configuration (prompt + refresh interval per widget, edit from the widget)
- [x] First-run quick setup (one-tap starter model download), themed monochrome app icon
- [x] Unit tests run in CI before the APK is assembled

## v0.3

- Accessibility-service agent actions (opt-in, with overlay)
- Routines (trigger → prompt → tools)
- RAG over user documents (`nativeRagQuery`, embeddings)

## Known risks

| Risk | Mitigation |
|---|---|
| Cactus v2 bundle format may not match catalog file names | Catalog is data; Models screen shows engine error; HF repo tree is the source of truth |
| `.so` may need extra shared libs | CI inspects `NEEDED` entries and copies `libc++_shared.so` |
| Small models call tools unreliably | `tool_rag_top_k`, short tool descriptions, confirmation gate, optional `force_tools` for dedicated tool models (Needle / FunctionGemma / LFM2-Tool) |
| Large models OOM on low-RAM phones | Default is 450M; 1.4 GB model flagged "≥ 8 GB RAM" |
| NDK r27 produces 4 KB-aligned libs | Fine for sideloading; move to NDK r28 + 16 KB alignment before Play Store |
