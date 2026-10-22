# CI/CD

Workflow: `.github/workflows/build.yml` – **every push to `main`** builds the native engine and the APK, then publishes a GitHub **pre-release** named `build-<run_number>` with the APK attached.

## Pipeline

```
push to main
   │
   ├─ checkout
   ├─ JDK 17 + Android SDK + NDK r27 + CMake
   ├─ clone cactus-compute/cactus @ pinned tag (CACTUS_REF)
   ├─ restore/build libcactus_engine.so (arm64-v8a)   ← cached by tag+NDK
   ├─ copy .so → app/src/main/jniLibs/arm64-v8a/, copy Cactus*.kt from the same tag
   ├─ ./gradlew assembleRelease  (versionCode = run number)
   ├─ sign with keystore/ci-test.p12 (or your secret keystore)
   └─ create release build-N + upload CactusPilot-<version>.apk
```

## Versioning

- `versionCode` = `GITHUB_RUN_NUMBER` (monotonic → new builds always install over old ones).
- `versionName` = `0.1.<run number>`.
- Release tag `build-<run number>`; marked *pre-release* so the Releases page keeps a clean history for manual verification.

## Signing

| Mode | When | How |
|---|---|---|
| Test key (default) | No secrets configured | `keystore/ci-test.p12` (public, throwaway) – stable signature across builds |
| Your own key | Secrets set | `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` |

Create a key and secret (on a PC):

```bash
keytool -genkeypair -v -keystore release.p12 -storetype PKCS12 -alias myalias \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.p12   # paste into the ANDROID_KEYSTORE_BASE64 secret
```

> Switching keys later changes the signature, so Android will refuse to update an install signed with the old key. Uninstall once (this deletes downloaded models).

## Required repository settings

- **Settings → Actions → General → Workflow permissions → Read and write permissions** (the workflow also requests `contents: write` itself).
- No other secrets are required.

## Caching

- Native `.so` cached with `actions/cache` keyed on `CACTUS_REF` + NDK version (first build is slow, later builds skip it).
- Gradle caches via `gradle/actions/setup-gradle`.

## Upgrading the Cactus engine

1. Change `CACTUS_REF` in the workflow (and, if the JNI signatures changed, nothing else – CI copies `Cactus.kt` from the same tag over the vendored copy).
2. Push; CI rebuilds the native library.
3. Re-test model loading – bundle formats may change between engine versions.

## Updating an installed app

Two paths, both install **over** the existing app and keep downloaded models and chats:

1. **In-app (from the first build that includes it):** Settings → *Check for updates* → *Download & install*. The app also checks silently at launch (at most every 6 h) and shows a banner. It reads `https://api.github.com/repos/dhawan9898/Cactus-app/releases`, picks the highest `build-N` newer than its own `versionCode`, downloads the APK, **verifies the signing certificate matches the installed one**, then opens the system installer. The first time, Android asks you to allow "install unknown apps" for CactusPilot.
2. **Manual:** download the APK from the Releases page and open it.

Why updates work: every CI build is signed with the same key and `versionCode = run number` (always increasing). If you ever change the signing key, Android refuses the update and you must uninstall once.

## Manual smoke checklist (per release)

- [ ] App installs over previous build; models still present
- [ ] Models → download default chat model, "Use", chat streams a reply
- [ ] Attach a photo → "What is in this image?" gets an answer
- [ ] Download Whisper Tiny; mic → speak → text appears → answer
- [ ] "Set a timer for 2 minutes" → confirmation/system timer UI
- [ ] Add the Ask widget → Refresh shows an answer
- [ ] Airplane mode: chat still works

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| CI fails in native build | NDK path/version; check the "Build Cactus engine" step log |
| App crashes on start with `UnsatisfiedLinkError` | Device not arm64, or `.so` depends on `libc++_shared.so` (CI copies it automatically when `NEEDED`) |
| `nativeInit` fails for a model | Bundle layout/engine version mismatch → pick a different file in the catalog |
| "Resource not accessible by integration" in release step | Workflow permissions set to read-only |
