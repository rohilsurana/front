# Front API Contract

The app communicates with a single configurable **base URL** (set on the home screen).
All paths below are relative to that base URL.

---

## `GET /alarms`

Fetch the full alarm schedule. The app calls this on launch and on manual sync.

### Response `200 OK`

```json
{
  "alarms": [
    {
      "id": "morning",
      "label": "Morning Wake-up",
      "hour": 7,
      "minute": 0,
      "enabled": true,
      "days": ["mon", "tue", "wed", "thu", "fri"],
      "text_path": "/alarms/morning/text",
      "fallback": "Wake up! Good morning!"
    },
    {
      "id": "weekend",
      "label": "Weekend",
      "hour": 9,
      "minute": 0,
      "enabled": true,
      "days": ["sat", "sun"],
      "text_path": "/alarms/weekend/text",
      "fallback": "Rise and shine, it's the weekend!"
    }
  ]
}
```

### Fields

| Field       | Type             | Required | Description |
|-------------|------------------|----------|-------------|
| `id`        | string           | ✅       | Unique alarm identifier |
| `label`     | string           | ✅       | Display name shown in the app |
| `hour`      | int (0–23)       | ✅       | Alarm hour (24h) |
| `minute`    | int (0–59)       | ✅       | Alarm minute |
| `enabled`   | bool             | ✅       | Whether the alarm is active |
| `days`      | string[]         | ✅       | Days to fire: `mon` `tue` `wed` `thu` `fri` `sat` `sun` |
| `text_path` | string           | ✅       | Path appended to base URL to fetch TTS text at fire time |
| `fallback`  | string           | ✅       | Spoken if `text_path` fetch fails or times out |

---

## `GET /alarms/{id}/text`

Fetched by the app on a **20-minute background sync** (not at fire time) so the text
is always pre-cached and ready. Keeping this separate from `/alarms` allows the message
to be dynamic (day-aware, task-aware, weather-aware) without re-syncing the whole schedule.

### Response `200 OK`

```
Content-Type: text/plain

Good morning Rohil! Today is Monday. You have a standup at 10 and gym at 7 PM. Don't skip leg day.
```

### Sync behaviour

- `TextSyncWorker` runs every **20 minutes** via WorkManager (requires network)
- An **immediate sync** is also triggered right after `/alarms` is fetched
- On each run: fetches `{base_url}{text_path}` for every enabled alarm
- Success → updates the cache. Failure → **stale cache is preserved** (no overwrite)
- At alarm fire time the app reads directly from cache — zero network dependency
- Priority: `cached text` → `alarm.fallback`

---

## Error Handling (App Side)

| Scenario | Behaviour |
|---|---|
| `/alarms` unreachable on sync | Keep previously synced schedule, show error |
| `/alarms` returns malformed JSON | Keep previously synced schedule, show error |
| `text_path` unreachable at fire time | Speak `fallback` |
| `text_path` returns empty body | Speak `fallback` |
| No alarms configured on backend | Cancel all local alarms |

---

## Example Minimal Server (Python / Flask)

```python
from flask import Flask, jsonify
from datetime import datetime

app = Flask(__name__)

@app.route("/alarms")
def alarms():
    return jsonify({
        "alarms": [
            {
                "id": "morning",
                "label": "Morning",
                "hour": 7, "minute": 0,
                "enabled": True,
                "days": ["mon","tue","wed","thu","fri","sat","sun"],
                "text_path": "/alarms/morning/text",
                "fallback": "Wake up! Good morning!"
            }
        ]
    })

@app.route("/alarms/morning/text")
def morning_text():
    day = datetime.now().strftime("%A")
    return f"Good morning Rohil! Today is {day}. Time to get up!", 200, {
        "Content-Type": "text/plain"
    }

app.run(host="0.0.0.0", port=8765)
```
