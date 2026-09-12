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
    lat: float = 51.5074
    lon: float = -0.1278
    units: str = "metric"        # metric | imperial

    tz: str = "Europe/London"
    data_dir: str = "./data"

    @property
    def calendar_names(self) -> list[str]:
        return [c.strip() for c in self.calendars.split(",") if c.strip()]

    @property
    def caldav_enabled(self) -> bool:
        return bool(self.icloud_user and self.icloud_app_password and self.calendar_names)


settings = Settings()
