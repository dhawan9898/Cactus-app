# CactusPilot

An **offline-first Android AI assistant** powered by the [Cactus](https://github.com/cactus-compute/cactus) on-device inference engine.

| Capability | How |
|---|---|
| Text chat | On-device LLM/VLM, streamed token-by-token |
| Image understanding | Vision-language model (image attached to a chat turn) |
| Voice | Mic → on-device speech-to-text (Whisper/Parakeet) → chat → optional text-to-speech |
| Task automation | LLM tool-calling → Android Intents / system APIs, with user confirmation for risky actions |
| Widgets | Jetpack Glance "Ask" widget whose prompt is re-run on a schedule by WorkManager |

Everything runs on the phone. No account, no cloud (cloud hand-off is explicitly disabled).

## Docs

- [Architecture](docs/ARCHITECTURE.md) – layers, data flow, threading, memory strategy
- [Design](docs/DESIGN.md) – UX, model catalog, tool system, widgets
- [CI/CD](docs/CICD.md) – GitHub Actions pipeline that publishes an APK on every push to `main`
- [Roadmap](docs/ROADMAP.md) – what is done, what is next, known risks

## Quick start

1. Create a GitHub repo, then push this folder to its `main` branch.
2. Wait for the **Build & Release APK** workflow (Actions tab). It builds the native Cactus engine, the APK, and creates a pre-release `build-<run number>`.
3. Download the `.apk` from the **Releases** page and install it (arm64 phones only).
4. Open the app → **Models** → download *LFM2.5-VL 450M* (chat + vision, ~120 MB) and *Whisper Tiny* (voice, ~22 MB).

## Repo layout

```
app/                         Android application module (Kotlin, Jetpack Compose, Glance)
  src/main/java/com/cactus/  Vendored Cactus JNI bindings (Apache-2.0, pinned tag, overwritten by CI)
  src/main/java/dev/cactuspilot/
    ai/      engine wrapper, model catalog + downloader, tools, agent loop
    ui/      Compose screens + ViewModel
    voice/   mic recorder, text-to-speech
    widget/  Glance widget + scheduled refresh worker
docs/                        design documents
keystore/ci-test.p12         TEST-ONLY signing key so every CI build can update the previous install
.github/workflows/build.yml  CI/CD
```

## Signing note

`keystore/ci-test.p12` is a **throwaway, public** key whose only purpose is to give every CI build the same signature, so you can install a new build over the old one without losing downloaded models. Never use it for a Play Store release; set the `ANDROID_KEYSTORE_BASE64` secret flow described in [CI/CD](docs/CICD.md) instead.
