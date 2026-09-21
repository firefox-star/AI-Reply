# AI Reply (v3.0.1)

Floating AI reply app for Android. A small draggable bubble sits over any app — tap it, pick a tone, get **one** AI-generated reply you can copy or paste anywhere. Pure native Kotlin (no React Native, no WebView).

Based on the [replyfy](https://github.com/aryankumarx/replyfy) project by **aryankumarx** (MIT license), rebuilt from scratch as a fully native Android app.

## Download (no PC needed)

Grab the signed APK directly from the Releases page:

**https://github.com/firefox-star/AI-Reply/releases/latest**

Or the fixed link for the current version:

`https://github.com/firefox-star/AI-Reply/releases/latest/download/AI-Reply-v3.0.1-release.apk`

## Features

- Draggable floating bubble + reply panel over any app (overlay)
- 4 tones: Friendly, Professional, Playful, Fluent — one tap = one reply (not all four)
- 6 free AI presets, **Z.ai GLM-4.5-Flash is the default**:
  Z.ai / OpenRouter / Google Gemini / Mistral / Groq / Cerebras
- Your API key stays on your device (SharedPreferences). Nothing is hardcoded.
- Quick Settings tile to open the reply panel
- Optional accessibility text capture — the app works fine without it. On Android 13+ the system locks restricted accessibility apps; the app ships with a built-in 3-step unlock guide (App info → ⋮ → Allow restricted settings).

## Build with Termux (on your phone)

```bash
pkg update -y
pkg install openjdk-17 aapt apksigner git unzip -y

# minimal SDK (arch-independent, ~60MB)
mkdir -p $HOME/android-sdk && cd $HOME/android-sdk
curl -LO https://dl.google.com/android/repository/platform-36_r02.zip
unzip -q platform-36_r02.zip
mv android-16 platforms 2>/dev/null || (mkdir -p platforms && mv android-16 platforms/)

# get the code
cd ~
git clone https://github.com/firefox-star/AI-Reply.git
cd AI-Reply

# point Gradle at the SDK + Termux's native aapt2
echo "sdk.dir=$HOME/android-sdk" > local.properties
echo "android.aapt2FromMavenOverride=$PREFIX/bin/aapt2" >> gradle.properties

# build
chmod +x gradlew
./gradlew assembleDebug --no-daemon
# APK: app/build/outputs/apk/debug/app-debug.apk
```

First run downloads ~200MB of dependencies. Be patient.

## Build on PC

```bash
./gradlew assembleDebug    # debug APK, auto-signed
```

For a signed release build, create `signing.properties` in the project root (it is gitignored):

```
REPLYFY_RELEASE_STORE_FILE=/absolute/path/to/your.keystore
REPLYFY_RELEASE_STORE_PASSWORD=...
REPLYFY_RELEASE_KEY_ALIAS=...
REPLYFY_RELEASE_KEY_PASSWORD=...
```

then `./gradlew assembleRelease`. Without that file, release builds automatically fall back to the debug key so the APK stays installable.

## Requirements

- Android 7.0+ (API 24)
- Overlay permission for the bubble (the app guides you to it)
- A free API key from any of the 6 presets, pasted inside the app

## License

MIT — see [LICENSE](LICENSE). Original replyfy by aryankumarx.
