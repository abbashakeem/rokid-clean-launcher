# Rokid HUD Launcher

Minimal see-through home screen for Rokid Glasses: 12-hour clock, one-line weather, next three calendar events.

```
backend/    FastAPI on Render (free tier) — pulls allow-listed iCloud calendars over CalDAV, proxies OpenWeatherMap
launcher/   Kotlin Android app declaring itself as HOME; black background = transparent on the waveguide
docs/       SETUP.md — step-by-step from empty Mac to app running on the glasses
```

Data flow: iCloud CalDAV → backend (filters to `CALENDARS`, caches `calendar.json`) → `GET /get-calendar` → glasses over Wi-Fi.
Every endpoint except `/health` needs the `X-API-KEY` header.

Start at [docs/SETUP.md](docs/SETUP.md), Part 0.
