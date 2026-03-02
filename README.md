# TTS Alarm 🔔

An Android alarm app that fetches custom text from a server URL and speaks it via Text-to-Speech. Falls back to a local default message if the server is unreachable.

## Features

- ⏰ Set a daily alarm time
- 🌐 Fetch alarm text from any HTTP endpoint (plain text response)
- 🔇 Automatic fallback if server is down/unreachable/slow (5s timeout)
- 🔄 Survives device reboots (alarm re-schedules automatically)
- 🗣️ Uses Android's built-in TTS — no extra APIs needed
- Repeats daily (re-schedule on alarm fire if you want — see below)

## Server Contract

Your server endpoint should:
- Accept `GET` requests
- Return `200 OK` with `Content-Type: text/plain`
- Body = the text you want spoken (e.g. `"Good morning! Today is Monday. You have 2 meetings."`)

Example minimal Python server:
```python
from flask import Flask
app = Flask(__name__)

@app.route("/alarm-text")
def alarm_text():
    return "Rise and shine! You have a standup at 10 AM.", 200, {"Content-Type": "text/plain"}

app.run(port=5000)
```

Or a static file on any hosting (Vercel, GitHub Pages, your homelab nginx).

## Build

1. Open in Android Studio (Electric Eel or newer)
2. `Build > Make Project`
3. Run on device (min Android 8.0 / API 26)

Or from CLI:
```bash
./gradlew assembleDebug
```

APK lands at: `app/build/outputs/apk/debug/app-debug.apk`

## Permissions Required

| Permission | Why |
|---|---|
| `INTERNET` | Fetch alarm text from server |
| `WAKE_LOCK` | Keep CPU alive during TTS |
| `RECEIVE_BOOT_COMPLETED` | Re-schedule alarm after reboot |
| `SCHEDULE_EXACT_ALARM` | Precise alarm timing (Android 12+) |
| `FOREGROUND_SERVICE` | Run TTS without being killed |
| `POST_NOTIFICATIONS` | Show alarm notification (Android 13+) |

## Making the Alarm Repeat Daily

Currently the alarm fires once. To make it repeat, in `AlarmService.onStartCommand()` after acquiring the wake lock, re-schedule the alarm for the next day:

```kotlin
// Re-schedule for tomorrow
val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
val hour = prefs.getInt(MainActivity.KEY_HOUR, 7)
val minute = prefs.getInt(MainActivity.KEY_MINUTE, 0)
val calendar = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, hour)
    set(Calendar.MINUTE, minute)
    set(Calendar.SECOND, 0)
    add(Calendar.DAY_OF_YEAR, 1)
}
val pi = PendingIntent.getBroadcast(this, MainActivity.ALARM_REQUEST_CODE,
    Intent(this, AlarmReceiver::class.java),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
(getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    .setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pi)
```

## File Structure

```
app/src/main/
├── java/com/example/ttsalarm/
│   ├── MainActivity.kt     — UI, alarm scheduling
│   ├── AlarmReceiver.kt    — BroadcastReceiver (hands off to service)
│   ├── AlarmService.kt     — Fetch + TTS logic (foreground service)
│   └── BootReceiver.kt     — Re-schedules alarm after reboot
├── res/layout/
│   └── activity_main.xml   — Simple UI
└── AndroidManifest.xml
```
