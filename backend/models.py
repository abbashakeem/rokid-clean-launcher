from datetime import datetime
from pydantic import BaseModel, Field


class Event(BaseModel):
    title: str
    start_time: datetime
    end_time: datetime
    location: str | None = None
    all_day: bool = False
    calendar: str | None = None


class CalendarResponse(BaseModel):
    updated_at: datetime | None
    source: str
    events: list[Event] = Field(default_factory=list)


class WeatherResponse(BaseModel):
    temp: int
    condition: str
    text: str
    fetched_at: datetime
