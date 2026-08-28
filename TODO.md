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

- [ ] Rename application id/namespace `com.aubynsamuel.clipsync` → `com.bluedrop`
      (update `build.gradle.kts`, source packages, `AndroidManifest`, test packages,
      FileProvider authority, notification channel ids).
- [ ] App display name → "Bluedrop"; new launcher icon (a blue drop); notification
      strings updated.
- [ ] Remove `.github/FUNDING.yml` (points at the original author).
- [ ] Refresh `demo/` screenshots after the UI rebrand; drop the ClipSync gif in
      the meantime.
- [ ] Verify CI still builds after the rename; cut a `v1.3.2-bluedrop.0` tag as
      the pre-protocol baseline.

## Phase 1 — Protocol redesign (the core)

- [ ] Write `docs/PROTOCOL.md` and freeze it before any implementation:
      - Persistent connection per peer; hello handshake with a pairing token;
        heartbeat (PING/PONG every ~10s); graceful BYE; auto-reconnect with backoff.
      - Binary framing: magic `BDIP` + version (u8) + type (u8) + length (u32 LE)
        + payload; max frame size cap; malformed-frame handling defined.
      - Message types: `HELLO`, `PING`/`PONG`, `BYE`, `CLIPBOARD_TEXT`,
        `CLIPBOARD_IMAGE`, `FILE_META`, `FILE_CHUNK`, `FILE_ACK`, `PROGRESS`.
      - File chunking: 32–64 KiB chunks, cumulative ACK, resume not required for v1.
- [ ] Android: replace the one-shot `readLine()` JSON path in `BluetoothService`
      with a per-peer session (reader/writer coroutines over a persistent socket,
      outbound queue, connection manager, reconnect state machine). Keep the
      legacy text path behind a build flag until the Windows side catches up.
- [ ] Windows: fork `clipsync-windows` → `Bluedrop-windows`; port the same
      protocol over 32feet streams.
- [ ] Round-trip tests on both ends: framing encode/decode, chunk reassembly,
      oversized/truncated frame rejection, reconnect behavior.
- [ ] Cutover: text clipboard runs on the new protocol end to end; legacy path
      deleted.

## Phase 2 — Image clipboard

- [ ] Android receive: `CLIPBOARD_IMAGE` payload (PNG bytes) → clipboard URI via
      a content provider over the cache dir; optional auto-copy toggle honored.
- [ ] Android send: share-sheet entries that receive image URIs → stream to
      `CLIPBOARD_IMAGE`; notification action sends the clipboard image if present.
- [ ] Windows receive: set `Clipboard.SetImage` (DIB conversion from PNG bytes).
- [ ] Windows send: clipboard contains an image → send button pushes it; also
      accept drag-and-drop images into the tray/app window.
- [ ] Progress notification on Android for transfers above a size threshold.

## Phase 3 — File transfer

- [ ] Android receive: `FILE_META` + chunks → Downloads via MediaStore; completed
      notification with "open" action.
- [ ] Android send: multi-file share-sheet; sequential transfer queue.
- [ ] Windows receive: configurable inbox folder (default
      `%USERPROFILE%\Downloads\Bluedrop`); per-file progress UI.
- [ ] Windows send: app drop zone + (optional) Explorer context-menu entry.
- [ ] Cancel/replace semantics: a new transfer preempts or queues (pick one,
      document it).

## Phase 4 — Presence & integrations

- [ ] Windows side publishes link status to `%LOCALAPPDATA%\Bluedrop\link.json`
      (`{ connected, since, peer_name }`, written on every change) and optionally
      a `127.0.0.1` JSON endpoint. This is the documented integration contract —
      external tools (e.g. Alfred) read it and must degrade to "no signal" when
      absent.
- [ ] Battery-optimization guidance screen on first run (exemption request +
      explanation), since the persistent link dies under Doze otherwise.
- [ ] Document a minimal consumer example for the status contract.

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
