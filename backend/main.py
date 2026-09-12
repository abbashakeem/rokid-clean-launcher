"""Rokid HUD backend.

    POST /update-calendar   replace the cached event list (Shortcut/manual override)
    GET  /get-calendar      next N upcoming events from the allow-listed calendars
    GET  /get-weather       OpenWeatherMap current conditions, cached
    GET  /health            unauthenticated liveness check

Hosted on Render free tier: the instance sleeps when idle, so CalDAV sync runs on
request (refresh_if_stale) instead of a background timer.

Every endpoint except /health requires header  X-API-KEY: <API_KEY>.
"""
import asyncio
import logging
import secrets
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request, status
from fastapi.responses import HTMLResponse

from config import settings
from models import CalendarResponse, Event, WeatherResponse
from store import store
from weather import get_weather
from settings_store import LauncherSettings, settings_store
import caldav_sync

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")
log = logging.getLogger("main")


def verify_key(x_api_key: str = Header(default="")) -> None:
    if not secrets.compare_digest(x_api_key.encode(), settings.api_key.encode()):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "invalid or missing X-API-KEY")


_sync_lock = asyncio.Lock()


async def refresh_if_stale() -> None:
    """Sync iCloud when the cache is older than SYNC_INTERVAL_MIN.

    Called from the calendar endpoint so the service works on free hosts that sleep
    when idle (Render, Koyeb): the glasses' own request wakes the server and refreshes.
    Only one sync runs at a time; concurrent callers just get the current cache.
    """
    if not settings.caldav_enabled or _sync_lock.locked():
        return
    age_ok = store.updated_at and (datetime.now(timezone.utc) - store.updated_at) < timedelta(
        minutes=settings.sync_interval_min
    )
    if age_ok and store.source == "caldav":
        return
    async with _sync_lock:
        try:
            await asyncio.wait_for(caldav_sync.sync_once(), timeout=25)
        except Exception as exc:  # serve last good cache
            log.error("caldav sync failed: %s", exc)


@asynccontextmanager
async def lifespan(app: FastAPI):
    if settings.caldav_enabled:
        log.info("CalDAV enabled for %s, refresh when cache older than %d min",
                 settings.calendar_names, settings.sync_interval_min)
        asyncio.create_task(refresh_if_stale())  # warm the cache on boot, don't block startup
    else:
        log.info("CalDAV sync disabled (set ICLOUD_USER, ICLOUD_APP_PASSWORD, CALENDARS)")
    yield


app = FastAPI(title="Rokid HUD Backend", version="0.1.0", lifespan=lifespan)


@app.get("/health")
async def health():
    return {"ok": True, "events_cached": len(store.events), "updated_at": store.updated_at, "caldav": settings.caldav_enabled}


@app.post("/update-calendar", dependencies=[Depends(verify_key)])
async def update_calendar(events: list[Event]):
    store.replace(events, source="post")
    return {"stored": len(store.events), "updated_at": store.updated_at}


@app.get("/get-calendar", response_model=CalendarResponse, dependencies=[Depends(verify_key)])
async def get_calendar(limit: int = Query(3, ge=1, le=20)):
    await refresh_if_stale()
    return CalendarResponse(updated_at=store.updated_at, source=store.source, events=store.upcoming(limit))


@app.post("/sync-now", dependencies=[Depends(verify_key)])
async def sync_now():
    if not settings.caldav_enabled:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "CalDAV not configured")
    try:
        n = await caldav_sync.sync_once()
    except Exception as exc:
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, f"iCloud sync failed: {exc}")
    return {"synced": n}


@app.get("/config", response_model=LauncherSettings, dependencies=[Depends(verify_key)])
async def get_config():
    """Launcher settings; the glasses poll this every few minutes and on resume."""
    return settings_store.value


@app.put("/config", response_model=LauncherSettings, dependencies=[Depends(verify_key)])
async def put_config(patch: dict):
    try:
        return settings_store.update(patch)
    except Exception as exc:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, str(exc))


@app.get("/settings", response_class=HTMLResponse)
async def settings_page():
    """Tiny settings UI. The API key is entered once and kept in the browser's localStorage."""
    return SETTINGS_HTML


@app.get("/get-weather", response_model=WeatherResponse, dependencies=[Depends(verify_key)])
async def weather():
    try:
        return await get_weather()
    except Exception as exc:
        log.warning("weather fetch failed: %s", exc)
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, f"weather unavailable: {exc}")


SETTINGS_HTML = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>HUD launcher settings</title>
<style>
 body{font-family:-apple-system,system-ui,sans-serif;background:#000;color:#40ff5e;margin:0;padding:24px;max-width:520px}
 h1{font-size:18px;font-weight:600;letter-spacing:.1em;text-transform:uppercase;margin:0 0 20px}
 fieldset{border:1px solid #1b6b28;border-radius:8px;padding:12px 16px;margin:0 0 16px}
 legend{padding:0 6px;color:#2aa83e;font-size:12px;letter-spacing:.15em;text-transform:uppercase}
 label{display:flex;justify-content:space-between;align-items:center;gap:12px;padding:8px 0;border-bottom:1px solid #0e3a16;font-size:15px}
 label:last-child{border:0}
 select,input[type=number],input[type=password]{background:#000;color:#40ff5e;border:1px solid #2aa83e;border-radius:6px;padding:6px 8px;font-size:15px;min-width:110px}
 input[type=checkbox]{width:20px;height:20px;accent-color:#40ff5e}
 button{background:#40ff5e;color:#000;border:0;border-radius:8px;padding:12px 18px;font-size:15px;font-weight:600;width:100%;margin-top:8px}
 #status{min-height:20px;color:#2aa83e;font-size:13px;margin-top:10px}
 .hint{color:#2aa83e;font-size:12px}
</style></head><body>
<h1>HUD launcher</h1>
<fieldset><legend>Access</legend>
 <label>API key <input type="password" id="key" placeholder="X-API-KEY"></label>
 <div class="hint">Stored only in this browser. Same value as the launcher's HUD_API_KEY.</div>
</fieldset>
<form id="f">
<fieldset><legend>Sources</legend>
 <label>Calendar <select name="calendar_source"><option value="api">Backend (iCloud CalDAV)</option><option value="rokid">Rokid app (phone calendar)</option><option value="both">Both, merged</option></select></label>
 <label>Weather <select name="weather_source"><option value="api">Backend (OpenWeatherMap)</option><option value="rokid">Rokid app (phone)</option></select></label>
 <label>Agenda rows <input type="number" name="max_events" min="1" max="6"></label>
 <label>Scroll long titles <input type="checkbox" name="marquee_titles"></label>
</fieldset>
<fieldset><legend>Display</legend>
 <label>Display off after idle (s) <input type="number" name="idle_off_seconds" min="2" max="60"></label>
 <label>Auto-dim by time of day <input type="checkbox" name="auto_dim"></label>
 <label>Brightness, day (10–255) <input type="number" name="brightness_day" min="10" max="255"></label>
 <label>Brightness, night (10–255) <input type="number" name="brightness_night" min="10" max="255"></label>
</fieldset>
<fieldset><legend>Navigation card</legend>
 <label>Mode <select name="nav_card"><option value="smart">Smart: wake for turns, then sleep</option><option value="always">Always on while navigating</option><option value="off">Off</option></select></label>
 <label>Sleep after a turn (s) <input type="number" name="nav_off_seconds" min="3" max="120"></label>
 <label>Wake when turn within (m) <input type="number" name="nav_wake_distance_m" min="50" max="2000"></label>
 <label>Show our card instead of Rokid's nav page <input type="checkbox" name="nav_take_over"></label>
 <label>Volume nudge after each turn (iOS ducking workaround) <input type="checkbox" name="nav_volume_nudge"></label>
 <div class="hint">iOS lowers music for the voice prompt and sometimes never restores it. A few seconds after each instruction the glasses re-send their volume level to the phone, which can reset that.</div>
 <label>Mute all glasses audio while navigating <input type="checkbox" name="nav_mute_voice"></label>
 <div class="hint">The voice is generated on the phone and streamed over Bluetooth, so this mutes music as well for the duration of the route.</div>
</fieldset>
<button type="submit">Save</button>
<div id="status"></div>
</form>
<script>
const key=document.getElementById('key'), f=document.getElementById('f'), st=document.getElementById('status');
key.value=localStorage.getItem('hud_key')||'';
key.addEventListener('change',()=>{localStorage.setItem('hud_key',key.value);load();});
const H=()=>({'X-API-KEY':key.value,'Content-Type':'application/json'});
async function load(){ if(!key.value) return; const r=await fetch('/config',{headers:H()}); if(!r.ok){st.textContent='Key rejected ('+r.status+')';return;}
 const c=await r.json(); for(const el of f.elements){ if(!el.name) continue; if(el.type==='checkbox') el.checked=!!c[el.name]; else el.value=c[el.name]; } st.textContent='Loaded'; }
f.addEventListener('submit',async e=>{ e.preventDefault(); const body={}; for(const el of f.elements){ if(!el.name) continue; body[el.name]= el.type==='checkbox'?el.checked:(el.type==='number'?Number(el.value):el.value); }
 const r=await fetch('/config',{method:'PUT',headers:H(),body:JSON.stringify(body)}); st.textContent=r.ok?'Saved. The glasses pick it up within 5 minutes, or on wake.':'Save failed ('+r.status+')'; });
load();
</script></body></html>"""
