from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    api_key: str = "change-me"

    icloud_user: str = ""
    icloud_app_password: str = ""
    calendars: str = ""          # comma separated display names
    sync_interval_min: int = 15
    lookahead_days: int = 7

    owm_api_key: str = ""
    lat: float = -37.8136
    lon: float = 144.9631
    units: str = "metric"        # metric | imperial
    city: str = ""               # display name override; defaults to OWM's station name

    # Assistant. The key lives here, never on the phone, so it can be rotated in one place
    # and a lost device does not leak it.
    assistant_provider: str = "anthropic"        # anthropic | gemini (per-request override allowed)
    anthropic_api_key: str = ""
    anthropic_model: str = "claude-sonnet-5"
    gemini_api_key: str = ""
    gemini_model: str = "gemini-2.5-flash"
    assistant_max_tokens: int = 512
    assistant_system: str = (
        "You are a voice assistant on smart glasses. Replies are read on a tiny "
        "monocular display and spoken aloud, so answer in at most two short "
        "sentences. No markdown, no lists, no preamble."
    )

    tz: str = "Australia/Melbourne"
    data_dir: str = "./data"

    @property
    def calendar_names(self) -> list[str]:
        return [c.strip() for c in self.calendars.split(",") if c.strip()]

    @property
    def caldav_enabled(self) -> bool:
        return bool(self.icloud_user and self.icloud_app_password and self.calendar_names)


settings = Settings()
