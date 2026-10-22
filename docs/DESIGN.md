# Design

## 1. Model catalog

The catalog is data (`ModelCatalog.kt`). It lists only **runnable bundles** for the pinned engine: the v2 engine refuses any folder that lacks `components/manifest.json`. These are the `*-cq4.zip` archives `cactus download` fetches from `Cactus-Compute/<model>` at the newest Hugging Face tag that is ≤ the engine version.

URL pattern: `https://huggingface.co/Cactus-Compute/<repo>/resolve/<tag>/<file>`

| ID | Role | Repo @ tag / file | Size | Notes |
|---|---|---|---|---|
| `lfm2-vl-450m` | Chat + vision (**default**) | `LFM2-VL-450M` @ `v2.0` / `lfm2-vl-450m-cq4.zip` | ≈383 MB | Fast, fits any arm64 phone |
| `gemma4-e2b` | Chat + vision | `gemma-4-E2B-it` @ `v2.0.1` / `gemma-4-e2b-it-cq4.zip` | ≈2.7 GB | Highest quality; 8 GB+ RAM phones |
| `whisper-base` | Speech-to-text (**default**) | `whisper-base` @ `v2.0` / `whisper-base-cq4.zip` | ≈113 MB | Fast |
| `parakeet-v3` | Speech-to-text | `parakeet-tdt-0.6b-v3` @ `v2.2.2` / `parakeet-tdt-0.6b-v3-cq4.zip` | ≈384 MB | More accurate, multilingual |

> The `*-cq` repos and `weights/*-int4.zip` files on Hugging Face use an older bundle layout (`config.txt` + per-layer `.weights`, no `components/`) and **fail to load** on engine v2.2.2 ("Failed to create model - check config.txt exists"). After unpacking, the app verifies `config.txt` and `components/manifest.json` and reports a clear error otherwise.
> Upgrading the engine: list the repo's tags, pick the newest ≤ engine version, pick the `cq4` zip, update the row.

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

## 3. Prompts, memory and answer quality

**What the engine does (verified in the Cactus v2.2.2 source and the bundle):**
- The engine is stateless per call: we send the whole conversation every time and it reuses its KV cache for the unchanged prefix.
- The bundles allow a cache of up to 128k tokens. The only built-in limit is *rolling KV compaction*: once a chat passes 4096 tokens the engine compacts the cache to 2048, which drops older context. It reads `CACTUS_KV_COMPRESS_AT/TO` from the environment when a model is created, so the app sets them from the **Conversation memory** setting (4K / **8K** / 16K / 32K) and reloads the model when it changes.
- When tools are passed, their JSON goes into the system prompt on every call. Small models answer ordinary questions worse with ten tool definitions in the prompt.

**What the app does about it:**
- **Token-budgeted history** instead of a fixed number of turns: the oldest turns are dropped only when the estimated prompt would exceed `memory − reply length − reserve`. The prompt always keeps the system message and the latest user turn.
- **Only the latest image** stays attached to the history (images cost ~300+ tokens each), so follow-up questions about it still work.
- **Tools only when the message looks like a device action** (`ToolRouter`), otherwise plain chat.
- **System prompt** with the current date/time, instructions to use the whole conversation, to admit uncertainty about recent events, and to answer in Markdown.
- **Calmer sampling by default:** temperature 0.3, repetition penalty 1.05, top-p 0.9, top-k 40, reply up to 1024 tokens (adjustable up to 4096).
- **Optional Wikipedia grounding** (🌐 chip / Settings): the question is searched on Wikipedia and the top excerpts are given to the model for that turn only. This is how the app gets fresher facts than the model's training data; it is not a general web search.
- **Model choice matters most:** the 450M model has little knowledge and weak multi-turn reasoning. Gemma 4 E2B is far better and is marked as recommended.

Answers are rendered as Markdown (headings, lists, bold/italic, inline code, fenced code blocks with a Copy button, quotes, links) while they stream.

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
- **Each widget has its own state**, keyed by `appWidgetId` in `WidgetStore`: `prompt`, last `answer`, `updatedAt`, `refreshMinutes`.
- **Configuration:** `WidgetConfigActivity` opens when a widget is added (cancelling it cancels the add) and from the widget's *Edit* button / long-press *Reconfigure*. The user sets the prompt and a refresh interval (manual … daily).
- **Refresh:** `WidgetScheduler` keeps one periodic WorkManager job per widget (≥ 15 min, an Android limit) plus a one-off job for *Refresh*. `WidgetWorker` runs the prompt through the active chat model, stores the answer and calls `AskWidget().update()` for that widget. Deleting a widget cancels its job and clears its state.
- The assistant's `configure_widget` tool edits the most recently configured widget (and tells the user to add one if none exists).
- Constraint: the worker needs the model installed, otherwise the widget shows the error text instead of failing silently.

## 5b. Conversations and voice

- **Conversations:** `ConversationStore` keeps `filesDir/conversations/index.json` (id, title, updatedAt) plus one `<id>.json` per conversation, written atomically. Titles come from the first user message and can be renamed. The history screen opens, renames and deletes; *New chat* keeps the old one in the list (and does nothing if the current chat is empty). The single-chat file of older builds is imported on first run.
- **Live captions:** while recording, 0.5 s PCM chunks are fed to the engine's streaming transcription (`confirmed` text is appended, `pending` replaces the tail) and shown above the composer. On stop the remaining text is flushed and sent. If the voice model cannot stream, or returns nothing, the whole recording is transcribed in one call instead.

## 6. Testing strategy

| Level | Tool | Scope |
|---|---|---|
| Unit | JUnit (JVM) | JSON building/parsing, zip-slip guard, tool arg validation |
| Instrumented | AndroidX test (later) | Model load with tiny bundle, widget update |
| Manual (current) | APK from GitHub release | Device smoke checklist in `docs/CICD.md` |
