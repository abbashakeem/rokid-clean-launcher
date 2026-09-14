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
import json
import logging
import secrets
from contextlib import asynccontextmanager
from pathlib import Path
from datetime import datetime, timedelta, timezone

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request, status
from fastapi.responses import FileResponse, HTMLResponse

from config import settings
from models import CalendarResponse, Event, WeatherResponse, AssistantRequest, AssistantResponse, AssistantTurn
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


DIST = Path(__file__).parent / "dist"


@app.get("/app-version", dependencies=[Depends(verify_key)])
async def app_version():
    """Metadata for the launcher's self-update. Written by scripts/publish-update.sh."""
    meta = DIST / "version.json"
    if not meta.exists():
        return {"version_code": 0, "version_name": "", "available": False}
    data = json.loads(meta.read_text())
    data["available"] = (DIST / data.get("file", "app.apk")).exists()
    return data


@app.get("/app.apk", dependencies=[Depends(verify_key)])
async def app_apk():
    meta = DIST / "version.json"
    name = json.loads(meta.read_text()).get("file", "app.apk") if meta.exists() else "app.apk"
    apk = DIST / name
    if not apk.exists():
        raise HTTPException(status.HTTP_404_NOT_FOUND, "no build published")
    return FileResponse(apk, media_type="application/vnd.android.package-archive", filename="hud-launcher.apk")


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
 <label>Auto-update the launcher <input type="checkbox" name="auto_update"></label>
 <label>Spare button opens <select name="shortcut_app"><option value="">App picker</option><option value="Translation">Translation</option><option value="Teleprompter">Teleprompter</option><option value="Subtitles">Subtitles</option><option value="Music">Music</option><option value="Camera">Camera</option><option value="Sound recorder">Sound recorder</option><option value="Navigation">Navigation</option><option value="Vision AI">Vision AI</option><option value="Device info">Device info</option><option value="Settings">Settings</option><option value="Rokid home">Rokid home</option></select></label>
</fieldset>
<fieldset><legend>Display</legend>
 <label>Display off after idle (s) <input type="number" name="idle_off_seconds" min="2" max="60"></label>
 <label>Auto-dim by time of day <input type="checkbox" name="auto_dim"></label>
 <label>Brightness, day (10–255) <input type="number" name="brightness_day" min="10" max="255"></label>
 <label>Brightness, night (10–255) <input type="number" name="brightness_night" min="10" max="255"></label>
</fieldset>
<fieldset><legend>Navigation card</legend>
 <label>Tint the phone's map to HUD green <input type="checkbox" name="nav_map_tint"></label>
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


async def _live_context() -> str:
    """
    Today's weather and the next few events, so the assistant can answer from the wearer's own
    data instead of declining.

    Every lookup is best-effort and isolated: a broken weather provider must not stop the
    assistant answering a question that had nothing to do with weather.
    """
    parts: list[str] = []
    now = datetime.now(timezone.utc).astimezone()
    parts.append(f"Current local time: {now:%A %d %B %Y, %H:%M}.")

    try:
        w = await get_weather()
        parts.append(f"Current weather: {w.text}, feels like {getattr(w, 'feels_like', w.temp)}.")
    except Exception as exc:  # noqa: BLE001 - degrade, never fail the request
        log.info("assistant: weather context unavailable: %s", exc)

    try:
        await refresh_if_stale()
        events = store.upcoming(5)
        if events:
            lines = [
                f"- {e.title} at {e.start_time}" + (f" ({e.location})" if e.location else "")
                for e in events
            ]
            parts.append("Upcoming calendar events:\n" + "\n".join(lines))
        else:
            parts.append("No upcoming calendar events.")
    except Exception as exc:  # noqa: BLE001
        log.info("assistant: calendar context unavailable: %s", exc)

    return "\n".join(parts)


def _system_prompt(context: str) -> str:
    return settings.assistant_system + ("\n\n" + context if context else "")


async def _call_anthropic(req: AssistantRequest) -> tuple[str, str]:
    if not settings.anthropic_api_key:
        raise HTTPException(
            status.HTTP_503_SERVICE_UNAVAILABLE, "anthropic not configured: set ANTHROPIC_API_KEY")
    payload = {
        "model": settings.anthropic_model,
        "max_tokens": settings.assistant_max_tokens,
        "system": _system_prompt(req.context),
        "messages": [{"role": t.role, "content": t.content} for t in req.messages],
    }
    async with httpx.AsyncClient(timeout=30.0) as client:
        r = await client.post(
            "https://api.anthropic.com/v1/messages",
            headers={
                "x-api-key": settings.anthropic_api_key,
                "anthropic-version": "2023-06-01",
                "content-type": "application/json",
            },
            json=payload,
        )
    if r.status_code != 200:
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, f"anthropic {r.status_code}: {r.text[:300]}")
    body = r.json()
    text = "".join(b.get("text", "") for b in body.get("content", []) if b.get("type") == "text")
    return text.strip(), body.get("model", settings.anthropic_model)


async def _call_gemini(req: AssistantRequest) -> tuple[str, str]:
    if not settings.gemini_api_key:
        raise HTTPException(
            status.HTTP_503_SERVICE_UNAVAILABLE, "gemini not configured: set GEMINI_API_KEY")
    # Gemini names the assistant role "model", not "assistant", and carries the system prompt
    # in a separate systemInstruction field rather than inline.
    contents = [
        {"role": ("model" if t.role == "assistant" else "user"), "parts": [{"text": t.content}]}
        for t in req.messages
    ]
    payload: dict = {
        "contents": contents,
        "systemInstruction": {"parts": [{"text": _system_prompt(req.context)}]},
        "generationConfig": {"maxOutputTokens": settings.assistant_max_tokens},
    }
    if settings.gemini_search:
        # Lets the model search the web for anything outside its training data. Google does not
        # allow mixing search tools with non-search tools in one request, so if we ever give the
        # assistant callable tools instead of prompt-injected context, this has to give way.
        payload["tools"] = [{"google_search": {}}]
    url = (
        f"https://generativelanguage.googleapis.com/v1beta/models/"
        f"{settings.gemini_model}:generateContent"
    )
    async with httpx.AsyncClient(timeout=30.0) as client:
        r = await client.post(
            url,
            headers={"x-goog-api-key": settings.gemini_api_key, "content-type": "application/json"},
            json=payload,
        )
    if r.status_code != 200:
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, f"gemini {r.status_code}: {r.text[:300]}")
    body = r.json()
    parts = (body.get("candidates") or [{}])[0].get("content", {}).get("parts", [])
    text = "".join(p.get("text", "") for p in parts)
    return text.strip(), settings.gemini_model


@app.post("/assistant", response_model=AssistantResponse, dependencies=[Depends(verify_key)])
async def assistant(req: AssistantRequest) -> AssistantResponse:
    """
    Answer a transcribed question, holding the provider key server-side.

    Stateless on purpose: the phone sends the whole conversation each time, so a restart here
    loses nothing and this service never becomes a session store. Speech-to-text happens on the
    phone (Apple's on-device recogniser), so only text crosses the network.

    Provider is the server default unless the request overrides it, so the phone can offer a
    choice without a redeploy.
    """
    provider = req.provider or settings.assistant_provider
    req = req.model_copy(update={"context": await _live_context()})
    try:
        if provider == "gemini":
            reply, model = await _call_gemini(req)
        else:
            reply, model = await _call_anthropic(req)
    except httpx.HTTPError as exc:
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, f"upstream unreachable: {exc}") from exc
    return AssistantResponse(reply=reply, model=model, provider=provider)
