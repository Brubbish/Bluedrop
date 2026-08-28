<div align="center">

# Bluedrop

**Drop clipboard content and files between your Android phone and Windows PC — over nothing but a Bluetooth link.**

No hotspot to enable. No router required. No cloud in sight: pairing gives you an
encrypted link, and ten meters of air is the entire network.

*蓝牙直传，不过路由器。*

[![Platforms](https://img.shields.io/badge/Platforms-Android%20%7C%20Windows-blue)]()
[![License](https://img.shields.io/badge/License-MIT-blue)](LICENSE)

</div>

---

Bluedrop is a **two-ended app in one repo**. Both ends speak the same wire
protocol (**BDIP v1**) over Bluetooth RFCOMM: persistent per-peer sessions,
heartbeat, auto-reconnect, chunked file transfer with ACKs. The protocol spec
is the single source of truth for both implementations, and both test suites
run the same shared vectors — one repo, one contract, two runtimes.

| Capability | State |
| --- | --- |
| Text clipboard, Android ↔ Windows | ✅ over BDIP v1 (legacy ClipSync clients still accepted until v2.0.0) |
| Persistent connection + reliable binary protocol | ✅ [docs/PROTOCOL.md](docs/PROTOCOL.md), tested on both ends |
| Image clipboard | ✅ PNG both directions |
| File transfer (chunked, ACKed) | ✅ share sheet / button / drag-and-drop; progress UI is basic |
| Link-presence output for other tools | ✅ `%LOCALAPPDATA%\Bluedrop\link.json` — [docs/STATUS.md](docs/STATUS.md) |
| Pairing token, fuzzing, polish | 🚧 Phase 5 — see [TODO](TODO.md) |

During migration either end still interoperates with plain ClipSync v1.3
peers via a legacy one-shot fallback.

## Repository layout

```
docs/        wire protocol (PROTOCOL.md) and presence contract (STATUS.md)
protocol/    vectors.json — frame test vectors shared by BOTH test suites
tools/       make_icons.py — one drop artwork → Android mipmaps + Windows .ico
android/     the Android app (Kotlin, Compose; Gradle project root)
windows/     the Windows companion (.NET 9, WPF, 32feet.NET; solution root)
```

## How it works

1. **Pair your devices** once through the OS Bluetooth settings
2. **Select devices** you want to share with, tap **Start**
3. **Copy on one device, paste on the other** — share via the notification
   action, the home-screen widget, the app's share button, or
   drag-and-drop / share sheet for files

## Install

Grab both ends from [Releases](../../releases) — every push publishes one
combined release containing:

- `Bluedrop-<version>-arm64-v8a.apk` — install on your phone
- `Bluedrop-<version>-universal.apk` — any device/emulator
- `Bluedrop-Setup-<version>.exe` — Windows installer (self-contained x64,
  no .NET runtime needed)

## Build from source

```bash
git clone https://github.com/Brubbish/Bluedrop
cd Bluedrop

# Android (needs the Android SDK; see android/app/build.gradle.kts for SDK levels)
cd android && ./gradlew assembleDebug

# Windows (needs the .NET 9 SDK)
cd windows && dotnet build BluedropWindows.csproj
dotnet test BluedropWindows.Tests/BluedropWindows.Tests.csproj
```

Launcher icons for both ends regenerate from one source:
`python tools/make_icons.py` (requires Pillow).

## Privacy & security

- All transfers happen directly between your two devices over Bluetooth
- No cloud storage, no accounts, no internet dependency
- Clipboard data is never persisted; link-layer encryption comes from Bluetooth pairing
- Only pair with devices you trust

## Contributing

Bug reports, feature requests, and PRs are welcome. Keep the scope in mind:
Bluedrop moves clipboard and files over Bluetooth — it is not becoming a
notifications sync, remote input, or cloud tool (see Non-goals in [TODO](TODO.md)).

## License

MIT — see [LICENSE](LICENSE).

## Acknowledgments

Bluedrop is a fork of
[ClipSync for Android](https://github.com/aubynsamuel/clipsync-android) and
[ClipSync for Windows](https://github.com/aubynsamuel/clipsync-windows)
by Aubyn Samuel (MIT), re-architected around a persistent binary protocol
with image clipboard, file transfer, and link-presence output. The Bluetooth
plumbing, permissions handling, and UI shells were too good to rebuild —
thank you.
