"""
Durable facts the assistant should remember across conversations.

Deliberately explicit: a fact is stored only when the wearer says "remember ...". Letting the
model decide what to retain sounds better and behaves worse - it accumulates junk and you cannot
predict what it knows. Explicit capture is predictable and trivially reversible.

Recent conversation turns are NOT stored here; the phone sends those with each request, which keeps
this service stateless about dialogue and durable only about facts.
"""

from __future__ import annotations

import json
import logging
import time
import uuid
from pathlib import Path

from config import settings

log = logging.getLogger(__name__)

MAX_FACTS = 200
MAX_LEN = 500


class MemoryStore:
    def __init__(self) -> None:
        self._path = Path(settings.data_dir) / "memory.json"
        self._facts: list[dict] = []
        self._load()

    def _load(self) -> None:
        try:
            if self._path.exists():
                self._facts = json.loads(self._path.read_text())
        except Exception as exc:  # noqa: BLE001 - a corrupt file must not stop the service
            log.warning("memory unreadable, starting empty: %s", exc)
            self._facts = []

    def _save(self) -> None:
        try:
            self._path.parent.mkdir(parents=True, exist_ok=True)
            self._path.write_text(json.dumps(self._facts, indent=2))
        except Exception as exc:  # noqa: BLE001
            log.warning("memory not saved: %s", exc)

    def add(self, text: str) -> dict:
        text = text.strip()[:MAX_LEN]
        fact = {"id": uuid.uuid4().hex[:8], "text": text, "at": time.time()}
        self._facts.append(fact)
        # Oldest out first; a wearable should not accumulate unbounded state.
        del self._facts[:-MAX_FACTS]
        self._save()
        return fact

    def all(self) -> list[dict]:
        return list(self._facts)

    def delete(self, fact_id: str) -> bool:
        before = len(self._facts)
        self._facts = [f for f in self._facts if f["id"] != fact_id]
        if len(self._facts) != before:
            self._save()
            return True
        return False

    def clear(self) -> int:
        n = len(self._facts)
        self._facts = []
        self._save()
        return n

    def as_context(self) -> str:
        if not self._facts:
            return ""
        lines = [f"- {f['text']}" for f in self._facts]
        return "Things you have been asked to remember:\n" + "\n".join(lines)


memory_store = MemoryStore()
