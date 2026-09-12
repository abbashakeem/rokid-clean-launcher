"""OpenWeatherMap current-conditions proxy with a 10 minute cache."""
import logging
from datetime import datetime, timedelta, timezone

import httpx

from config import settings
from models import WeatherResponse

log = logging.getLogger("weather")
OWM_URL = "https://api.openweathermap.org/data/2.5/weather"
CACHE_TTL = timedelta(minutes=10)

_cache: WeatherResponse | None = None


async def get_weather() -> WeatherResponse:
    global _cache
    now = datetime.now(timezone.utc)
    if _cache and now - _cache.fetched_at < CACHE_TTL:
        return _cache
    if not settings.owm_api_key:
        raise RuntimeError("OWM_API_KEY not configured")

    params = {
        "lat": settings.lat,
        "lon": settings.lon,
        "units": settings.units,
        "appid": settings.owm_api_key,
    }
    async with httpx.AsyncClient(timeout=10) as client:
        resp = await client.get(OWM_URL, params=params)
        resp.raise_for_status()
        data = resp.json()

    temp = round(data["main"]["temp"])
    feels = round(data["main"].get("feels_like", data["main"]["temp"]))
    w = data["weather"][0] if data.get("weather") else {}
    condition = w.get("main", "Unknown")
    description = w.get("description", condition).capitalize()
    unit = "°F" if settings.units == "imperial" else "°C"
    _cache = WeatherResponse(
        temp=temp,
        feels_like=feels,
        condition=condition,
        description=description,
        icon=w.get("icon", "01d"),
        city=settings.city or data.get("name", ""),
        text=f"{temp}{unit} · {condition}",
        fetched_at=now,
    )
    return _cache
