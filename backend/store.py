"""In-memory cache mirrored to calendar.json so a restart keeps the last good list."""
import json
import logging
from datetime import datetime, timezone
from pathlib import Path

from config import settings
from models import Event

log = logging.getLogger("store")


class CalendarStore:
    def __init__(self) -> None:
        self.path = Path(settings.data_dir) / "calendar.json"
        self.events: list[Event] = []
        self.updated_at: datetime | None = None
        self.source: str = "none"
        self._load()

    def _load(self) -> None:
        if not self.path.exists():
            return
        try:
            raw = json.loads(self.path.read_text())
            self.events = [Event(**e) for e in raw.get("events", [])]
            self.updated_at = datetime.fromisoformat(raw["updated_at"]) if raw.get("updated_at") else None
            self.source = raw.get("source", "file")
            log.info("loaded %d events from %s", len(self.events), self.path)
        except Exception as exc:  # corrupt file should never take the API down
            log.warning("could not read %s: %s", self.path, exc)

    def replace(self, events: list[Event], source: str) -> None:
        self.events = sorted(events, key=lambda e: e.start_time)
        self.updated_at = datetime.now(timezone.utc)
        self.source = source
        self.path.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "updated_at": self.updated_at.isoformat(),
            "source": source,
            "events": [e.model_dump(mode="json") for e in self.events],
        }
        self.path.write_text(json.dumps(payload, indent=2))

    def upcoming(self, limit: int) -> list[Event]:
        now = datetime.now(timezone.utc)
        out = []
        for e in self.events:
            end = e.end_time if e.end_time.tzinfo else e.end_time.replace(tzinfo=timezone.utc)
            if end >= now:
                out.append(e)
        return out[:limit]


store = CalendarStore()
