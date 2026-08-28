# Bluedrop presence contract (`link.json`)

The Windows companion publishes its Bluetooth link status as a small JSON file
so that unrelated tools on the same PC can react to "the phone is connected"
without talking to Bluedrop's process.

## Location

```
%LOCALAPPDATA%\Bluedrop\link.json
```

## Schema

```json
{
  "connected": true,
  "since": "2026-08-28T09:15:02.314Z",
  "peer_name": "Pixel 8",
  "updated_at": "2026-08-28T11:02:47.001Z"
}
```

| Field        | Meaning                                              |
|--------------|------------------------------------------------------|
| `connected`  | `true` when at least one BDIP session is established |
| `since`      | ISO-8601 UTC of session establishment; `null` when disconnected |
| `peer_name`  | Peer device name; `null` when disconnected           |
| `updated_at` | ISO-8601 UTC of the last status change (write time)  |

The file is rewritten on every change only (no polling churn). It is **not**
guaranteed to exist — a missing, unreadable, or stale file must be treated as
"no signal", never as an error.

## Consumer rules

1. Read the file on demand; do not watch-lock it.
2. Check `updated_at` against your own staleness threshold (recommended:
   2 minutes) — a crashed Bluedrop leaves the last state behind.
3. Degrade gracefully: no file → feature unavailable.

## Minimal consumer example (PowerShell)

```powershell
$status = Get-Content "$env:LOCALAPPDATA\Bluedrop\link.json" |
    ConvertFrom-Json -ErrorAction SilentlyContinue
if ($status -and $status.connected) {
    Write-Host "Phone link up: $($status.peer_name) since $($status.since)"
} else {
    Write-Host "No Bluedrop link"
}
```

## Minimal consumer example (Python)

```python
import json, os, datetime

path = os.path.join(os.environ["LOCALAPPDATA"], "Bluedrop", "link.json")

def phone_connected(max_stale_seconds: int = 120) -> bool:
    try:
        with open(path, encoding="utf-8") as f:
            status = json.load(f)
        updated = datetime.datetime.fromisoformat(status["updated_at"])
        age = (datetime.datetime.now(datetime.timezone.utc) - updated).total_seconds()
        return bool(status["connected"]) and age < max_stale_seconds
    except (OSError, ValueError, KeyError):
        return False
```
