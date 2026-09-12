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

from fastapi import Depends, FastAPI, Header, HTTPException, Query, status

from config import settings
from models import CalendarResponse, Event, WeatherResponse
from store import store
from weather import get_weather
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


@app.get("/get-weather", response_model=WeatherResponse, dependencies=[Depends(verify_key)])
async def weather():
    try:
        return await get_weather()
    except Exception as exc:
        log.warning("weather fetch failed: %s", exc)
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, f"weather unavailable: {exc}")
