# AI Reply (v3.2.0)

Floating AI reply app for Android — now with a **full ChatGPT-style chat inside**.

- **Floating bubble** over any chat app: tap, pick a tone, get ONE AI-generated reply to copy/paste.
- **Whole-chat reading** (optional accessibility): reads the messages you received AND the ones you sent, even scrolling up to fold older history into the reply context.
- **AI Reply Chat** — a real chatbox like ChatGPT/Claude/Gemini: streaming replies, conversation history, markdown + code blocks, model selector (free GLM series only), and settings where you plug your API (not in the chat screen).
- **Updates without downloading anything**: the chat interface is a web app. Improvements deployed to the cloud chat arrive in the app instantly; even the built-in offline chat hot-updates itself from this repo (`appui/version.json`). Only deep system-level changes ever need a new APK.

Based on the [replyfy](https://github.com/aryankumarx/replyfy) project by **aryankumarx** (MIT license), rebuilt from scratch as a fully native Android app.

## Download (no PC needed)

Grab the signed APK directly from the Releases page:

**https://github.com/firefox-star/AI-Reply/releases/latest**

Or the fixed link for the current version:

`https://github.com/firefox-star/AI-Reply/releases/latest/download/AI-Reply-v3.2.0-release.apk`

## How the no-download updates work

- The app's main screen is a thin WebView shell.
- Chat UI + logic live in the cloud chat app (and in `appui/index.html` as an offline fallback).
- At every launch the shell checks `appui/version.json` on GitHub; a newer version is downloaded, swapped in and reloaded — **no APK download, no reinstall**.
- The API key stays on the phone (native storage), shared between the chat and the floating bubble.

## Features

- ChatGPT-style chat: streaming, conversations sidebar, stop/regenerate, copy, markdown + code blocks
- Model selector — free GLM series only: GLM-4.5-Flash (default), GLM-4-Flash, GLM-4-Flash-250414, GLM-4V-Flash
- Cloud mode (no API key needed) or plug your own free Z.ai / BigModel key in Settings
- Draggable floating bubble + reply panel over any app (overlay)
- 4 tones: Friendly, Professional, Playful, Fluent — one tap = one reply (not all four)
- Whole-chat reading with scroll-up history (accessibility, optional)
- Battery-optimization whitelist shortcut — built to stand against aggressive killers (Infinix/Transsion etc.)
- Hot-updatable UI — see above
- Quick Settings tile to open the reply panel

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
