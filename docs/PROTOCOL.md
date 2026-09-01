# Bluedrop Wire Protocol (BDIP) — Version 1

Status: **frozen draft** — implementations on both ends must follow this document
exactly. Any change requires a protocol `version` bump and a changelog entry
here.

BDIP runs over an RFCOMM socket (Bluetooth Classic, service UUID
`8ce255c0-200a-11e0-ac64-0800200c9a66`, same UUID ClipSync used). It replaces
ClipSync's one-shot newline-JSON transport with a persistent, bidirectional,
binary-framed session that can carry text, images, and files.

Design goals, in priority order:

1. **Decode without ambiguity** — a reader can always find frame boundaries on a
   truncated or corrupt stream and decide *reject vs. recover* deterministically.
2. **One link per peer pair** — exactly one live session between two devices;
   heartbeat + auto-reconnect keep it feeling permanent.
3. **Implementation-agnostic** — Kotlin (Android) and C# (.NET, 32feet.NET)
   implementations are interchangeable; the spec has no dependency on either.

---

## 1. Transport and roles

- Transport: RFCOMM over Bluetooth Classic. BLE is a non-goal (throughput).
- Service record name: `Bluedrop`. Service UUID:
  `8ce255c0-200a-11e0-ac64-0800200c9a66`.
- **Both** sides keep a listening RFCOMM socket open. Either side may dial the
  other; the first successful connection wins and becomes the session. While a
  healthy session exists, a side must not dial, and must immediately close any
  extra socket it accepts.
- The side that opened the connection is the **initiator**; the side that
  accepted is the **responder**. After the HELLO exchange the session is fully
  symmetric — either side may send application messages at any time.
- Only **one session per peer device** (identified by Bluetooth address).

### 1.1 Session establishment

```
initiator                              responder
    |--- HELLO ------------------------------>|
    |<-- HELLO --------------------------------|
    |            (session established)         |
```

1. Immediately after connect, the initiator sends `HELLO` and waits up to
   **10 s** for the responder's `HELLO`.
2. The responder replies with its own `HELLO` without user interaction.
3. If either side receives a non-`HELLO` frame first, or the 10 s timer
   expires, it sends `BYE(protocol_error)` where possible and closes.

### 1.2 Legacy sniffing (migration aid)

A responder's first read is a **4-byte peek**:

- Bytes are `42 44 49 50` (`"BDIP"`) → speak BDIP.
- Anything else → fall back to ClipSync's legacy newline-JSON handler
  (`{"clip": text}\n`, single message, then close).

This lets an upgraded side interoperate with a not-yet-upgraded peer at runtime.
The legacy path is removed in v2.0.0.

### 1.3 Heartbeat

- If no frame has been **sent** for 10 s, send `PING` with an 8-byte opaque
  payload.
- A `PING` must be answered with `PONG` carrying the identical payload.
- If no frame has been **received** for 30 s (covers one missed round trip
  plus slack), consider the link dead: close the socket, tear the session down,
  and reconnect per §1.4.
- Any received frame resets the receive timer; any sent frame resets the send
  timer.

### 1.4 Reconnect

- The initiator owns reconnection: dial with backoff **1 s → 2 s → 5 s →
  10 s → 30 s (cap)**, doubling from 1 s. After 60 s of a continuously healthy
  session, backoff resets to 1 s.
- The responder never dials an existing peer proactively; it only re-arms its
  listener. (A device becomes an initiator when the user sends something while
  no session exists.)
- Reconnect attempts must stop while a session is healthy.

### 1.5 Graceful shutdown

Before closing a healthy socket (user quits the app, service stops), send
`BYE(shutdown)` and give the peer ≤1 s to read it. The peer treats `BYE` as
"do not reconnect for at least 5 s" (the quitting side is going away).

---

## 2. Framing

All integers are **unsigned, little-endian**. Strings are UTF-8.

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+---------------+-------+-------+-------------------------------+
| magic "BDIP"  |  ver  | type  |          length (u32)         |
| 42 44 49 50   |  u8   |  u8   |              LE               |
+---------------+-------+-------+-------------------------------+
|                       payload (length bytes)                   |
+---------------------------------------------------------------+
```

- Header is exactly **10 bytes**.
- `ver` = 1 for this document. A receiver that sees an unknown `ver` must send
  `BYE(protocol_error)` and close — no partial negotiation.
- `length` counts payload bytes only.
- **Frame size cap**: `length ≤ 8 MiB (8_388_608)`. A frame announcing more is
  a protocol error. No allocation larger than `length` is ever required.

### 2.1 Error handling

| Condition                | Action                                                        |
|--------------------------|---------------------------------------------------------------|
| bad magic                | `BYE(protocol_error)`, close, reconnect (§1.4)                |
| unknown `ver`            | same as above                                                 |
| unknown `type`           | **skip**: read and discard `length` bytes, keep the session   |
| `length` > cap           | `BYE(protocol_error)`, close                                  |
| EOF mid-header/payload   | treat as link loss (no BYE), close, reconnect                 |
| payload fails to parse   | discard the frame, keep the session; log it                   |

Unknown `type` being skippable is what makes future additions safe on v1.

---

## 3. Message types

| Type | Name            | Payload                                              |
|------|-----------------|------------------------------------------------------|
| 0x01 | HELLO           | JSON (§3.1)                                          |
| 0x02 | PING            | 8 opaque bytes                                      |
| 0x03 | PONG            | 8 opaque bytes (echo of PING)                        |
| 0x04 | BYE             | u8 reason, then UTF-8 JSON detail (§3.2)             |
| 0x10 | CLIPBOARD_TEXT  | raw UTF-8 text (no wrapper)                          |
| 0x11 | CLIPBOARD_IMAGE | PNG file bytes (single frame)                        |
| 0x20 | FILE_META       | JSON (§3.3)                                          |
| 0x21 | FILE_CHUNK      | u32 LE chunk index, then raw bytes                   |
| 0x22 | FILE_ACK        | JSON (§3.4)                                         |
| 0x30 | PROGRESS        | reserved — receivers may send during file transfer   |

### 3.1 HELLO

```json
{
  "name": "Pixel 8",            // human-readable device name
  "proto": 1,                   // protocol version this build speaks
  "caps": ["text", "image", "file"],
  "token": ""                   // pairing token, "" = not enforced (v1)
}
```

- Both sides use `min(mine, theirs)` for feature gating; a cap absent from
  either list is disabled. Unknown caps are ignored.
- If both `token` values are non-empty and differ, reply `BYE(pairing_failed)`
  and close. (Token distribution is out of band; enforced from Phase 5 on.)

### 3.2 BYE

Reason codes (u8): `0` shutdown, `1` protocol_error, `2` busy (another session
already live), `3` pairing_failed. Detail JSON may carry `{"message": "..."}`
for logs/UI.

### 3.3 CLIPBOARD_TEXT / CLIPBOARD_IMAGE

- TEXT payload is the clipboard string itself. Receivers must tag the local
  clipboard as *remote-origin* so any future auto-share logic does not echo it
  back (loop prevention).
- IMAGE payload is a complete PNG file (≤ 8 MiB). One frame, no chunking.
  Senders must re-encode exotic formats (HEIF, WebP…) to PNG first.
- A receiver that cannot apply the payload (clipboard unavailable, image too
  large for the OS) drops it silently and logs; there is no clipboard-level
  error frame in v1.

### 3.4 File transfer

```
sender                                 receiver
  |--- FILE_META ------------------------>|
  |--- FILE_CHUNK(0) -------------------->|
  |<-- FILE_ACK {"received": N0} ---------|
  |--- FILE_CHUNK(1) -------------------->|
  |<-- FILE_ACK {"received": N0+N1} ------|
  |    ... (cumulative ACK per chunk) ...  |
  |<-- FILE_ACK {"done": true} -----------|   after last chunk verifies
```

FILE_META JSON:

```json
{
  "id": "b3f2…",               // opaque transfer id, sender-chosen, unique per session
  "name": "report.pdf",
  "size": 1048576,             // exact total bytes, u64
  "mime": "application/pdf",
  "chunkSize": 61440,          // bytes per chunk (fixed for this transfer)
  "chunks": 18                 // ceil(size / chunkSize), u32
}
```

FILE_CHUNK payload: `u32 LE` index then exactly `chunkSize` bytes (last chunk
may be short).

FILE_ACK JSON: `{"id": "...", "received": <u64 cumulative bytes>}` during the
transfer, and `{"id": "...", "done": true, "path": "…"}` on success or
`{"id": "...", "error": "…"}` on failure (bad index, size mismatch, disk full).

Rules:

- **Sender abort (v1-compatible convention):** a sender may abandon a
  transfer mid-stream by sending `FILE_ACK {"id": "...", "error": "cancelled
  by sender"}`. A receiver that gets an error ack matching its current
  incoming transfer must delete the temp file and discard the transfer.
  Receivers that predate this convention ignore the frame and clean up on
  session close instead — no version bump required.
- **One file transfer per session at a time**; queue additional files. Clipboard
  frames may interleave with chunks — receivers must handle interleaving.
- Chunk size: 60 KiB (`61440`). May be smaller for the final chunk only.
- v1 has no resume: a dropped link restarts the transfer from FILE_META.
- Sender paces: after each chunk, wait for its ACK before the next chunk
  (stop-and-wait). On RFCOMM this trades a little throughput for trivial flow
  control and back-pressure; revisit in v2 with sliding windows if profiling
  demands it.
- Receiver writes to a temp file and renames into the destination directory
  only after `size` is verified — no partially-written files in Downloads.

---

## 4. Size limits summary

| Item                     | Limit       |
|--------------------------|-------------|
| Frame payload            | 8 MiB       |
| CLIPBOARD_TEXT           | 1 MiB (send-side cap; larger → refuse locally) |
| CLIPBOARD_IMAGE          | 8 MiB       |
| File chunk               | 60 KiB      |
| Single file (v1 policy)  | 512 MiB (send-side cap, no framing reason)    |

---

## 5. Test vectors

Hex bytes for a complete frame.

**PING** (payload `00 11 22 33 44 55 66 77`):

```
42 44 49 50 01 02 08 00 00 00 00 11 22 33 44 55 66 77
```

**CLIPBOARD_TEXT** `"hi"`:

```
42 44 49 50 01 10 02 00 00 00 68 69
```

**BYE** reason `shutdown`, detail `{"message":"quit"}` (1 + 18 bytes):

```
42 44 49 50 01 04 13 00 00 00 00 7b 22 6d 65 73 73 61 67 65 22 3a 22 71 75 69 74 22 7d
```

Framers on both ends must round-trip these exact byte sequences and must
**reject** (as protocol error) a frame whose first four bytes are `42 44 49 51`,
whose `ver` is `02`, or whose length field exceeds the cap.

---

## 6. Implementation notes

- Reader loop: read 10-byte header fully (EOF → link loss), validate, then read
  exactly `length` payload bytes. Never trust `length` before the cap check.
- Writer side must write header + payload atomically enough that interleaved
  coroutines/tasks cannot splice frames — serialize writes behind a single
  writer (mutex/actor/channel).
- Timeouts: connect 10 s, HELLO 10 s, PONG slack inside the 30 s receive timer.
- The Bluetooth address is the peer identity. Persist nothing else per peer in
  v1 (token excepted, Phase 5).
