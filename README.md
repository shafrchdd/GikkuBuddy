# GikkuBuddy 🤖

Native Android Kodular AI desk buddy with Gemini Live, sensor reactions and remotely delivered emotion frames.

## Asset delivery

The extension downloads a versioned ZIP from a GitHub Release on first launch. Do **not** bundle the frames in the Kodular APK. Store extracted files under `context.getFilesDir()/gikku_buddy/emotions/`.

1. Publish `emotions-v1.zip` as a GitHub Release asset (avoid GitHub source ZIP).
2. Provide a manifest with `version`, `url`, `sha256`, `frame_count` and `emotion_count`.
3. Download asynchronously to a temporary file, validate SHA-256, safely extract (reject zip-slip paths), validate 23 folders and 345 frames, and atomically activate.
4. Reuse the cache offline. On update failure retain the last good pack.

## Planned Kodular API

- `InitializeAssets(manifestUrl)`
- `AssetsProgress(percent, bytesDownloaded, totalBytes)`
- `AssetsReady(path, version)`
- `AssetsError(message)`
- `SetEmotion(name, fps, loop)`
- `ClearEmotionCache()`

Gemini Live, audio and sensor integration will remain in native Java source and will not require bundling emotion assets.

## Release layout

`emotions-v1.zip` should contain 23 emotion directories with 15 numbered frames each. The runtime should resolve exact names case-sensitively, including `Idle`.

## Security

Use HTTPS, SHA-256 verification, ZIP size/entry limits, zip-slip protection and atomic directory replacement. Do not hardcode permanent Gemini API keys in the APK.
