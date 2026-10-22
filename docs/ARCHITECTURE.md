# Architecture

## 1. Goals and constraints

| Goal | Consequence |
|---|---|
| Fully on-device, private | Cactus engine via JNI; cloud hand-off disabled (`auto_handoff=false`) |
| Runs on mid-range phones | Small default models (≈0.5B params, ≈120 MB), models downloaded on demand, never bundled |
| One APK per push, easy verification | Native engine built in CI, APK attached to a GitHub pre-release |
| Extensible automation | Tools are plain Kotlin objects registered in a `ToolRegistry`; the LLM sees them as JSON-schema functions |

Hard constraints discovered during research (Cactus v2.2.2):

- Android ABI: **arm64-v8a only**; `minSdk 21` in the engine, we use 26.
- The engine ships as `libcactus_engine.so` + two Kotlin files (`Cactus.kt`, `CactusCallbacks.kt`, package `com.cactus`). There is no stable Maven artifact for the v2 engine, so CI builds the `.so` from a **pinned git tag**.
- Model bundles are `.zip` files hosted on Hugging Face under `Cactus-Compute/*`; a bundle is unzipped into a folder that is passed to `nativeInit`.
- One engine handle = one loaded model. Chat model and speech model are therefore **two handles**.
- Engine calls are blocking and not documented as thread-safe → serialize per handle.

## 2. Layered design

```
┌───────────────────────────────────────────────────────────────┐
│ UI (Compose)  ChatScreen · ModelsScreen · Glance AskWidget    │
├───────────────────────────────────────────────────────────────┤
│ Presentation  ChatViewModel (state, confirmations)            │
├───────────────────────────────────────────────────────────────┤
│ Domain        AgentLoop  ── ToolRegistry ── Tool impls        │
│               (prompt build, tool-call loop, safety gate)     │
├───────────────────────────────────────────────────────────────┤
│ Inference     EngineHost ─┬─ CactusEngine(chat)               │
│                           └─ CactusEngine(speech)             │
│               ModelStore (catalog, download, unzip, selection)│
├───────────────────────────────────────────────────────────────┤
│ Native        com.cactus.CactusJNI → libcactus_engine.so      │
└───────────────────────────────────────────────────────────────┘
Background:  WidgetWorker (WorkManager) → AgentLoop(no tools) → WidgetStore → Glance update
```

### Package map

| Package | Key types | Responsibility |
|---|---|---|
| `ai` | `CactusEngine`, `EngineHost`, `ModelStore`, `ModelCatalog`, `AgentLoop`, `Tools` | Everything that touches the model |
| `ui` | `MainActivity`, `ChatViewModel`, `ChatScreen`, `ModelsScreen` | Presentation |
| `voice` | `PcmRecorder`, `Speaker` | 16 kHz mono PCM16 capture; Android TTS |
| `widget` | `AskWidget`, `AskWidgetReceiver`, `WidgetWorker`, `WidgetStore` | Home-screen widget and scheduled refresh |

## 3. Key flows

### 3.1 Chat turn (text / image)

1. UI sends `UserTurn(text, imagePath?)` to `ChatViewModel`.
2. ViewModel appends it to history and calls `AgentLoop.run`.
3. `AgentLoop` builds the messages JSON (`system` + history; image becomes `"images":["/abs/path.jpg"]`) and calls `CactusEngine.complete(...)` with the tools JSON.
4. Tokens stream through `CactusTokenCallback` → `Flow`/callback → UI.
5. If the response has `function_calls`, each call goes through the **safety gate** (see 3.3), is executed, and its result is appended as a `tool` message. The loop then calls the model once more (no tools) to produce the final natural-language answer. Max 3 iterations.

### 3.2 Voice turn

1. Mic button → `PcmRecorder` (AudioRecord, 16 kHz, mono, PCM16) until pressed again.
2. PCM bytes → `speechEngine.transcribe(pcm)` (`nativeTranscribe` with `pcmData`, `audioPath=null`).
3. Transcript is placed in the same pipeline as a typed message.
4. Optional: reply text → `Speaker` (Android `TextToSpeech`).

A single multimodal model (e.g. Gemma 4 E2B accepts `"audio"` in a message) could replace the separate speech model later; the catalog already models this with capability flags.

### 3.3 Automation safety gate

Every `Tool` declares `confirm: Boolean`. The ViewModel exposes a `PendingConfirmation` (tool, args, `CompletableDeferred<Boolean>`). The agent loop suspends until the user taps Allow/Deny; a denial is fed back to the model as the tool result. Rules:

- Tools that only read local state or open a system UI that itself asks the user (SMS composer, dialer, alarm UI) can skip confirmation.
- Tools with a direct side effect that the system will not confirm (open arbitrary URL, change widget) require confirmation.
- No tool can send SMS, place calls, delete data, or spend money without a system UI step.
- Tool arguments are validated (type, range) before execution; malformed calls produce an error result, never a crash.

### 3.4 Widgets

`AskWidget` (Glance) renders `WidgetStore` state: title, prompt, last answer, timestamp. Buttons: **Ask** (opens the app) and **Refresh** (enqueues `WidgetWorker`). The `configure_widget` tool lets the assistant set the prompt and schedule (`PeriodicWorkRequest`, ≥ 15 min). The worker obtains the chat engine from `EngineHost`, runs one non-streaming completion, stores the answer, and calls `AskWidget().updateAll()`. Widgets never run inference in the widget process callback itself.

## 4. Threading and memory

- Each `CactusEngine` owns a single-thread executor dispatcher → calls on one handle are serialized; the two handles run independently.
- `nativeStop(handle)` is called from the UI thread for the **Stop** button (it is the engine's cancellation hook).
- Only one chat model is loaded at a time; switching models unloads the old handle first. The speech model is loaded lazily on first mic use and released when the app goes to the background for > 2 min (planned).
- Response buffer: 128 KB `ByteArray` per call; JSON parsed with `org.json` (no extra dependency).
- Downloads use `HttpURLConnection` with HTTP Range resume into `*.part`, then unzip with zip-slip protection and delete the archive.

## 5. Failure handling

| Failure | Behaviour |
|---|---|
| `libcactus_engine.so` missing / ABI mismatch | `UnsatisfiedLinkError` caught at startup → banner "Unsupported device (needs arm64)" |
| `nativeInit` returns 0 | Error text from `nativeGetLastError()` shown on the Models screen |
| `nativeComplete` negative | Message bubble shows the error; engine stays usable |
| Download interrupted | `.part` kept; next tap resumes |
| Tool throws | Result `"error: …"` returned to model, shown in chat |

## 6. Security and privacy

- No analytics. (Cactus has telemetry hooks — `nativeSetTelemetryEnvironment` etc. — we never call them.)
- Only network use: model download from `huggingface.co`.
- `android:allowBackup="false"` so model files and chats are not uploaded by system backup.
- Permissions: `INTERNET`, `RECORD_AUDIO` (runtime), `SET_ALARM`, `POST_NOTIFICATIONS` (reserved for later), no storage permission (photo picker only).
