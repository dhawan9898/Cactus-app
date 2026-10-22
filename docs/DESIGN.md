# Design

## 1. Model catalog

The catalog is data (`ModelCatalog.kt`), so models can be swapped without touching logic. URL pattern:
`https://huggingface.co/Cactus-Compute/<repo>/resolve/main/<file>`

| ID | Role | Repo / file | Size | Notes |
|---|---|---|---|---|
| `lfm25-vl-450m` | Chat + vision (**default**) | `LFM2.5-VL-450M-cq` / `L1V4.zip` | ≈122 MB | Fast, fits any arm64 phone |
| `lfm25-vl-450m-hq` | Chat + vision | `LFM2.5-VL-450M-cq` / `L3V4.zip` | ≈192 MB | Better quality |
| `qwen3-0.6b` | Chat (text only) | `Qwen3-0.6B-cq` / `L2.zip` | ≈268 MB | Strong tool-use for size |
| `gemma4-e2b` | Chat + vision (+audio) | `gemma-4-e2b-it-cq` / `A4V4L1.zip` | ≈1.4 GB | Highest quality; ≥ 8 GB RAM phones |
| `whisper-tiny` | Speech-to-text (**default**) | `whisper-tiny-cq` / `CQ4.zip` | ≈22 MB | Fast, English-leaning |
| `parakeet-v3` | Speech-to-text | `parakeet-tdt-0.6b-v3-cq` / `CQ4.zip` | ≈347 MB | More accurate, multilingual |

> **Verify on device.** Cactus bundle formats are still evolving. If a bundle fails `nativeInit`, the Models screen shows the engine error; fix by changing the file name in the catalog (the Hugging Face repo tree lists current files).

Selection rules: one active chat model, one active speech model, stored in `SharedPreferences`. A chat model flagged `vision=true` enables the image button.

## 2. UX

### Screens

1. **Chat** – message list, composer row `[🖼 image] [text field] [🎤] [Send/Stop]`, top bar with `Models` and "speak replies" toggle. Assistant bubbles stream; a small footer shows tok/s and time-to-first-token. Tool executions appear as compact cards ("⚙ set_timer(seconds=300) → ok").
2. **Models** – catalog list, per item: size, capability chips, state (`Not installed` / progress bar / `Installed` / `Active`), buttons Download · Use · Delete. Engine error text is shown inline.
3. **Widget** – 4×2 default; shows last answer, Ask/Refresh buttons.

### Principles

- First-run is guided: if no chat model is active, the chat screen shows a single call-to-action to the Models screen.
- Never block the UI thread; every engine call is a suspend function.
- Show *why* something is unavailable (no model, no mic permission, no vision model) instead of disabling silently.

## 3. Prompts

System prompt (kept short for small models):

```
You are CactusPilot, a helpful assistant running privately on the user's phone.
Be concise. When the user asks you to do something on the device, call the
matching tool instead of describing how to do it.
```

Generation defaults: `max_tokens=512`, `temperature=0.7`, `top_p=0.9`, `top_k=40`, `auto_handoff=false`, `tool_rag_top_k=3`. Widget refresh uses `max_tokens=160`, `temperature=0.3`.

## 4. Tool system

```kotlin
interface Tool {
    val name: String
    val description: String
    val parameters: JSONObject      // JSON-Schema object
    val confirm: Boolean
    suspend fun execute(ctx: Context, args: JSONObject): String
}
```

`ToolRegistry.toolsJson()` produces the `[{"type":"function","function":{...}}]` array the engine expects.

| Tool | Effect | Confirm |
|---|---|---|
| `set_alarm(hour, minute, label?)` | `AlarmClock.ACTION_SET_ALARM` (skips UI) | yes |
| `set_timer(seconds, label?)` | `AlarmClock.ACTION_SET_TIMER` | no (system UI) |
| `open_app(name)` | Launch app by label match | no |
| `web_search(query)` | `ACTION_WEB_SEARCH` | no |
| `open_url(url)` | `ACTION_VIEW` (http/https only) | yes |
| `compose_sms(number, message)` | `ACTION_SENDTO smsto:` – opens composer, user taps send | no |
| `dial(number)` | `ACTION_DIAL` – never auto-calls | no |
| `flashlight(on)` | `CameraManager.setTorchMode` | no |
| `get_device_status()` | Battery %, charging, local time (returned to model) | no |
| `configure_widget(prompt, refresh_minutes?)` | Updates Ask widget prompt + schedule | yes |

Adding a tool = one class + one line in `ToolRegistry`.

### Roadmap for deeper automation

- `AccessibilityService`-based UI actions (tap/scroll/type) behind an explicit opt-in screen and a visible "agent is acting" overlay.
- Notification listener tools (summarise notifications).
- Routines: user-defined trigger (time/charging/Wi-Fi) → prompt → tools.

## 5. Widget design

- Framework: Jetpack Glance 1.1 (`GlanceAppWidget`), Material 3 colour providers.
- State: `WidgetStore` (SharedPreferences) – `prompt`, `answer`, `updatedAt`, `refreshMinutes`.
- Update path: `WidgetWorker` → completion → `WidgetStore.save` → `AskWidget().updateAll(ctx)`.
- Default prompt: "Give me a one-sentence motivational quote." so the widget shows something useful before configuration.
- Constraints: WorkManager periodic minimum is 15 min; the worker needs the model installed, otherwise it stores a "Model not installed" message instead of failing silently.

## 6. Testing strategy

| Level | Tool | Scope |
|---|---|---|
| Unit | JUnit (JVM) | JSON building/parsing, zip-slip guard, tool arg validation |
| Instrumented | AndroidX test (later) | Model load with tiny bundle, widget update |
| Manual (current) | APK from GitHub release | Device smoke checklist in `docs/CICD.md` |
