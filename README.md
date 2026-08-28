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
| Text clipboard, Android ↔ Windows | ✅ works today (inherited from ClipSync v1.3.2) |
| Persistent connection + reliable binary protocol | 🚧 Phase 1 — see [TODO](TODO.md) |
| Image clipboard | 🚧 Phase 2 |
| File transfer with progress | 🚧 Phase 3 |
| Link-presence output for other tools | 🚧 Phase 4 |

Until the protocol rewrite lands, the Android app pairs with the
[upstream ClipSync Windows companion](https://github.com/aubynsamuel/clipsync-windows/releases).
After the cutover, both ends must be Bluedrop.

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

- `app/` — Android app (Kotlin, Jetpack Compose, foreground service hosting an RFCOMM server)
- Windows companion — will live in a sibling repo (`Bluedrop-windows`, forked from
  [clipsync-windows](https://github.com/aubynsamuel/clipsync-windows)) once Phase 1 starts
- `docs/PROTOCOL.md` — the wire protocol (written first; both ends implement it)

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
