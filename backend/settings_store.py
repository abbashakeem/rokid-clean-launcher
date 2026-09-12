"""Launcher settings edited from the web page and fetched by the glasses (no cable needed)."""
import json
import logging
from pathlib import Path
from typing import Any

from pydantic import BaseModel, Field

from config import settings as env

log = logging.getLogger("settings")


class LauncherSettings(BaseModel):
    # data sources
    calendar_source: str = Field("api", pattern="^(api|rokid|both)$")   # backend CalDAV, Rokid phone schedule, or merged
    weather_source: str = Field("api", pattern="^(api|rokid)$")         # backend OpenWeatherMap or Rokid phone weather
    max_events: int = Field(4, ge=1, le=6)
    # display
    idle_off_seconds: int = Field(5, ge=2, le=60)
    auto_dim: bool = True
    brightness_day: int = Field(140, ge=10, le=255)
    brightness_night: int = Field(60, ge=10, le=255)
    # navigation card
    nav_card: str = Field("smart", pattern="^(off|always|smart)$")      # smart = wake for a change, sleep after nav_off_seconds
    nav_off_seconds: int = Field(10, ge=3, le=120)
    nav_wake_distance_m: int = Field(300, ge=50, le=2000)               # wake when the next turn is within this distance
    nav_mute_voice: bool = False                                        # mute Bluetooth media on the glasses while navigating
    # misc
    marquee_titles: bool = True


class SettingsStore:
    def __init__(self) -> None:
        self.path = Path(env.data_dir) / "settings.json"
        self.value = LauncherSettings()
        if self.path.exists():
            try:
                self.value = LauncherSettings(**json.loads(self.path.read_text()))
            except Exception as exc:
                log.warning("bad settings.json, using defaults: %s", exc)

    def update(self, patch: dict[str, Any]) -> LauncherSettings:
        merged = self.value.model_dump()
        merged.update({k: v for k, v in patch.items() if k in merged})
        self.value = LauncherSettings(**merged)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(json.dumps(self.value.model_dump(), indent=2))
        return self.value


settings_store = SettingsStore()
