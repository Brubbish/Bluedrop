# Bluedrop — TODO

Bluedrop moves clipboard content (text, images) and files between Android and
Windows over a single Bluetooth link — no Wi-Fi, no router, no cloud. This file
tracks the fork's roadmap from the inherited ClipSync baseline (v1.3.2, text-only
clipboard) to that goal.

Guiding decisions already made:

- **Fork, don't rewrite.** The ClipSync Android shell (permissions, foreground
  service, Compose UI, tests, CI) is kept; the transport protocol is replaced.
- **Windows side stays .NET.** Fork `clipsync-windows` (32feet.NET is the mature
  Windows Bluetooth stack) and adapt it to the new protocol. A Rust PC client
  remains possible later because the protocol spec is implementation-agnostic.
- **Protocol before features.** The current one-shot newline-JSON transport
  cannot carry binary data; every feature below depends on replacing it first.

---

## Phase 0 — Rebrand & housekeeping

- [x] Rename application id/namespace `com.aubynsamuel.clipsync` → `com.bluedrop`
      (build.gradle.kts, source/test packages, manifest, notification channel,
      widget classes; FileProvider authority already used `${applicationId}`).
- [x] App display name → "Bluedrop"; new blue-drop launcher icon
      (`tools/make_icons.py` regenerates all densities); notification strings
      updated; release signing falls back to debug keys when `local.properties`
      has none.
- [x] Remove `.github/FUNDING.yml` and the buy-me-a-coffee UI.
- [x] Drop the ClipSync demo gif (screenshots to refresh after UI pass).
- [x] Local build verified (SDK at `D:\AndroidSdk`); tag `v1.3.2-bluedrop.0`
      marks the pre-protocol baseline.

## Phase 1 — Protocol redesign (the core)

- [x] Write `docs/PROTOCOL.md` and freeze it before any implementation:
      - Persistent connection per peer; hello handshake with a pairing token;
        heartbeat (PING/PONG every ~10s); graceful BYE; auto-reconnect with backoff.
      - Binary framing: magic `BDIP` + version (u8) + type (u8) + length (u32 LE)
        + payload; max frame size cap; malformed-frame handling defined.
      - Message types: `HELLO`, `PING`/`PONG`, `BYE`, `CLIPBOARD_TEXT`,
        `CLIPBOARD_IMAGE`, `FILE_META`, `FILE_CHUNK`, `FILE_ACK`, `PROGRESS`.
      - File chunking: 32–64 KiB chunks, cumulative ACK, resume not required for v1.
- [x] Android: `Bdip.kt` + `BdipSession` + reworked `BluetoothService`
      (per-peer sessions, reader/writer coroutines, outbound queue, reconnect
      with backoff). Legacy text path kept as a runtime 4-byte sniff instead of
      a build flag — upgraded peers interop with ClipSync clients until v2.0.0.
- [x] Windows: forked `clipsync-windows`, same protocol over 32feet streams —
      now lives in `windows/` of this monorepo (`Protocol/` + reworked `MainWindow`).
- [x] Round-trip tests on both ends: spec hex vectors, framing reject cases,
      handshake, bidirectional clipboard, chunk reassembly (Android 58 tests,
      Windows 11 tests — all green).
- [ ] Cutover: legacy path stays until v2.0.0 by design (see above).

## Phase 2 — Image clipboard

- [x] Android receive: PNG → cache dir → FileProvider URI on the clipboard.
- [x] Android send: share-sheet `ACTION_SEND`/`SEND_MULTIPLE` entries handle
      image URIs (re-encoded to PNG per spec §3.3).
- [x] Windows receive: PNG → `BitmapSource` → `Clipboard.SetImage`.
- [x] Windows send: Share button sends the clipboard image when present.
- [x] Progress notification on Android for transfers above a size threshold (1 MiB+, receive and send).

## Phase 3 — File transfer

- [x] Android receive: chunks → temp file → MediaStore Downloads; tap-to-open
      notification.
- [x] Android send: multi-file share-sheet → sequential transfers.
- [x] Windows receive: configurable inbox folder (default
      `%USERPROFILE%\Downloads\Bluedrop`, `InboxFolder` setting).
- [x] Windows send: Send File(s) button + window-wide drag-and-drop drop zone.
- [x] Cancel/replace semantics: **queue** — one file transfer per session at a
      time (spec §3.4); a new transfer waits for the current one.
- [x] Per-file progress beyond the status bar (receive % on Windows; progress notifications on Android).
- [x] Transfer history on both ends: list of files/images sent and received, remove record only, or delete record + stored file.
- [ ] Explorer context-menu entry.

## Phase 4 — Presence & integrations

- [x] Windows publishes link status to `%LOCALAPPDATA%\Bluedrop\link.json`
      (`{ connected, since, peer_name, updated_at }`, change-driven writes).
- [x] Battery-optimization guidance dialog on first run (exemption request +
      explanation); persistent links die under Doze otherwise.
- [x] Status contract documented with consumer examples — `docs/STATUS.md`.
- [ ] Optional `127.0.0.1` JSON endpoint (file is enough for now).

## Phase 5 — Hardening & release

- [ ] App-layer pairing token on top of Bluetooth bonding (6-digit code or QR on
      first connect).
- [ ] Size limits everywhere (frame, clipboard, file); fuzz the framer with
      truncated/corrupt streams.
- [ ] CI artifacts: signed APK + Windows installer on tags.
- [ ] `v2.0.0` release: both ends, new protocol only, migration note for
      ClipSync users.

## Non-goals

- No cloud relay, accounts, or telemetry — ever.
- No notification sync, remote input, or screen mirroring (that is KDE Connect's
  job, and it needs a network).
- No BLE transport (tens of KB/s — unusable for files).
- No iOS/macOS/Linux in the foreseeable future.
- Android ↔ Android sync (inherited from ClipSync) stays supported as long as it
  costs nothing extra.
