<div align="center">

# Bluedrop

**Drop clipboard content and files between your Android phone and Windows PC — over nothing but a Bluetooth link.**

No hotspot to enable. No router required. No cloud in sight: pairing gives you an
encrypted link, and ten meters of air is the entire network.

*蓝牙直传，不过路由器。*

[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20Windows-blue)]()
[![License](https://img.shields.io/badge/License-MIT-blue)](LICENSE)

</div>

---

## Status

Bluedrop is a fresh fork of [ClipSync](https://github.com/aubynsamuel/clipsync-android)
(a great, actively-maintained project — go star it). The fork's mission is to grow
beyond text-only clipboard sync into a general Bluetooth transfer utility:

| Capability | State |
| --- | --- |
| Text clipboard, Android ↔ Windows | ✅ over BDIP v1 (legacy ClipSync clients still accepted until v2.0.0) |
| Persistent connection + reliable binary protocol | ✅ BDIP v1 — [docs/PROTOCOL.md](docs/PROTOCOL.md), tested on both ends |
| Image clipboard | ✅ PNG both directions |
| File transfer (chunked, ACKed) | ✅ share sheet / button / drag-and-drop; progress UI is basic |
| Link-presence output for other tools | ✅ `%LOCALAPPDATA%\Bluedrop\link.json` — [docs/STATUS.md](docs/STATUS.md) |
| Pairing token, fuzzing, installers | 🚧 Phase 5 — see [TODO](TODO.md) |

Both ends are Bluedrop now: this repo (Android) and
[Brubbish/Bluedrop-windows](https://github.com/Brubbish/Bluedrop-windows) (forked
from clipsync-windows). During migration either end still interoperates with
plain ClipSync v1.3 peers via the legacy fallback.

## How it works

1. **Pair your devices** once through the OS Bluetooth settings
2. **Select devices** you want to share with, tap **Start**
3. **Copy on one device, paste on the other** — share via the notification action,
   the home-screen widget, or (once in range) the persistent link

## Install

### Android

1. Go to [Releases](../../releases)
2. Download the latest `.apk`
3. Enable "Install from unknown sources" and install

### Build from source

```bash
git clone https://github.com/Brubbish/Bluedrop
cd Bluedrop
./gradlew assembleRelease
```

Requires the Android SDK; see `app/build.gradle.kts` for min/target SDK levels.

## Privacy & security

- All transfers happen directly between your two devices over Bluetooth
- No cloud storage, no accounts, no internet dependency
- Clipboard data is never persisted; link-layer encryption comes from Bluetooth pairing
- Only pair with devices you trust

## Project layout

- `app/` — Android app (Kotlin, Jetpack Compose, foreground service hosting
  persistent BDIP sessions over RFCOMM; `app/src/main/java/com/bluedrop/bluetooth/`)
- [Bluedrop-windows](https://github.com/Brubbish/Bluedrop-windows) — the .NET 9 /
  WPF / 32feet.NET companion speaking the same protocol
- `docs/PROTOCOL.md` — the wire protocol (frozen before implementation)
- `docs/STATUS.md` — the link.json presence contract for external consumers
- `tools/make_icons.py` — regenerates the launcher icon set

## Contributing

Bug reports, feature requests, and PRs are welcome. Keep the scope in mind: Bluedrop
moves clipboard and files over Bluetooth — it is not becoming a notifications sync,
remote input, or cloud tool (see Non-goals in [TODO](TODO.md)).

## License

MIT — see [LICENSE](LICENSE).

## Acknowledgments

Bluedrop is a fork of [ClipSync](https://github.com/aubynsamuel/clipsync-android)
by aubynsamuel (MIT), re-architected around a persistent binary protocol with image
clipboard, file transfer, and link-presence output. The Android Bluetooth plumbing,
permissions handling, and Compose UI were too good to rebuild — thank you.
