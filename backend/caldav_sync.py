"""Pull events from iCloud CalDAV for an allow-list of calendars.

Only calendars whose display name is in settings.calendar_names are read, which is
what filters out the hidden/unwanted calendars the Rokid app would otherwise mirror.
"""
import asyncio
import logging
from datetime import date, datetime, time, timedelta, timezone
from zoneinfo import ZoneInfo

import caldav
import recurring_ical_events
from icalendar import Calendar as ICal

from config import settings
from models import Event
from store import store

log = logging.getLogger("caldav")
ICLOUD_URL = "https://caldav.icloud.com"


def _to_dt(value, tz: ZoneInfo) -> tuple[datetime, bool]:
    """Normalise a DTSTART/DTEND value to an aware datetime. Returns (dt, all_day)."""
    if isinstance(value, datetime):
        if value.tzinfo is None:
            value = value.replace(tzinfo=tz)
        return value.astimezone(tz), False
    if isinstance(value, date):
        return datetime.combine(value, time.min, tzinfo=tz), True
    raise TypeError(f"unsupported date value {value!r}")


def fetch_events() -> list[Event]:
    """Blocking; run in a thread."""
    tz = ZoneInfo(settings.tz)
    now = datetime.now(tz)
    window_end = now + timedelta(days=settings.lookahead_days)

    client = caldav.DAVClient(
        url=ICLOUD_URL,
        username=settings.icloud_user,
        password=settings.icloud_app_password,
    )
    principal = client.principal()
    wanted = set(settings.calendar_names)
    found_names: list[str] = []
    events: list[Event] = []

    for cal in principal.calendars():
        name = str(cal.name or "")
        found_names.append(name)
        if name not in wanted:
            continue
        for obj in cal.search(start=now, end=window_end, event=True, expand=False):
            ical = ICal.from_ical(obj.data)
            # expand recurrences ourselves; iCloud's server-side expand is unreliable
            for comp in recurring_ical_events.of(ical).between(now, window_end):
                start, all_day = _to_dt(comp.get("DTSTART").dt, tz)
                end_prop = comp.get("DTEND") or comp.get("DTSTART")
                end, _ = _to_dt(end_prop.dt, tz)
                if all_day and end <= start:
                    end = start + timedelta(days=1)
                events.append(
                    Event(
                        title=str(comp.get("SUMMARY", "(untitled)")),
                        start_time=start,
                        end_time=end,
                        location=(str(comp.get("LOCATION")) or None) if comp.get("LOCATION") else None,
                        all_day=all_day,
                        calendar=name,
                    )
                )

    missing = wanted - set(found_names)
    if missing:
        log.warning("calendars not found on iCloud: %s (available: %s)", sorted(missing), found_names)

    # de-duplicate (same title+start can appear via shared calendars)
    seen: set[tuple] = set()
    unique = []
    for e in sorted(events, key=lambda e: e.start_time):
        key = (e.title, e.start_time, e.calendar)
        if key not in seen:
            seen.add(key)
            unique.append(e)
    return unique


async def sync_once() -> int:
    events = await asyncio.to_thread(fetch_events)
    store.replace(events, source="caldav")
    log.info("synced %d events from %s", len(events), settings.calendar_names)
    return len(events)
