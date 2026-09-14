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
    feels_like: int
    temp_min: int
    temp_max: int
    sunrise: datetime
    sunset: datetime
    condition: str          # OWM "main" group, e.g. Clouds
    description: str        # OWM description, e.g. "scattered clouds"
    icon: str               # OWM icon code, e.g. 04d (d=day, n=night)
    city: str
    text: str
    fetched_at: datetime


class AssistantTurn(BaseModel):
    """One message in the conversation. The phone owns the history; we stay stateless."""

    role: str = Field(pattern="^(user|assistant)$")
    content: str = Field(min_length=1, max_length=8000)


class AssistantRequest(BaseModel):
    messages: list[AssistantTurn] = Field(min_length=1, max_length=40)
    # Omit to use the server default; the phone can override per request.
    provider: str | None = Field(default=None, pattern="^(anthropic|gemini)$")


class AssistantResponse(BaseModel):
    reply: str
    model: str
    provider: str
