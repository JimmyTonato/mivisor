"""
UFC · Cito UFC API (ufcapi.dev / citoapi.com). Deporte "ufc".

- Clave SOLO desde la variable de entorno CITO_API_KEY (GitHub Secret ya configurado), cabecera x-api-key.
- Plan gratuito: 500 peticiones al MES. Por eso:
    · el listado de próximas veladas se consulta como mucho cada REFRESH (6 h);
    · la cartelera de cada velada se reutiliza durante CARD_TTL (24 h);
    · como mucho MAX_CARDS carteleras por consulta.
  Consumo típico: ~4 listados + ~2 carteleras al día ≈ 180 peticiones/mes.
- La caché (ufc-cache.json) se publica en la rama agenda-data junto a agenda.json y se recupera en
  la siguiente ejecución, igual que la agenda anterior.
- Cada velada aparece como UN evento: "UFC 333 · Volkanovski - Evloev" con la hora de la velada.
  No se inventan horas para combates individuales. Si la fuente solo da el día: timeTbd = true.
- Retratos: la imagen del luchador que da Cito (imageUrl > headshotUrl > proxiedImageUrl > bodyImageUrl).
  Nunca el campo "flag".
"""
from __future__ import annotations

import re
from datetime import datetime, timedelta, timezone
from typing import Any, Mapping, Optional

from agenda_core import Event, clean, event_from_json, event_to_json, in_window, parse_instant, slug, utc_iso
from providers import INVALID_RESPONSE, Http, Provider, ProviderError, first

IMAGE_KEYS = ("imageUrl", "headshotUrl", "proxiedImageUrl", "bodyImageUrl", "image", "photo", "photoUrl")
TIME_IN_TEXT = re.compile(r"\d{1,2}:\d{2}")


class CitoUfc(Provider):
    key, name, label = "ufc", "cito", "Cito UFC"
    sports = ("ufc",)
    secret_env = "CITO_API_KEY"
    BASE = "https://api.citoapi.com/api/v1/ufc"
    REFRESH = timedelta(hours=6)
    CARD_TTL = timedelta(hours=24)
    MAX_CARDS = 3

    def __init__(self, cache: Optional[dict] = None):
        self.cache = cache if isinstance(cache, dict) else {}
        self.cache_out: dict = dict(self.cache)
        self.contacted_at: Optional[datetime] = None   # None = se usó la caché, no se llamó a Cito
        self.requests = 0

    # ------------------------------------------------------------------ API pública

    def fetch(self, http: Http, now: datetime, env: Mapping[str, str]) -> list[Event]:
        fetched = parse_instant(self.cache.get("fetchedAt"))
        if fetched and timedelta(0) <= now - fetched < self.REFRESH and isinstance(self.cache.get("events"), list):
            self.contacted_at = None
            return [e for e in (event_from_json(x) for x in self.cache["events"]) if e and in_window(e.start, now)]

        key = self.credential(env)
        headers = {"x-api-key": key}
        self.contacted_at = now
        data = self._get(http, f"{self.BASE}/events/upcoming", headers, "ufc-upcoming")
        items = unwrap_list(data, "events", "upcoming", "items", "results")

        old_cards = {c.get("slug"): c for c in self.cache.get("cards") or [] if isinstance(c, dict)}
        new_cards: list[dict] = []
        events: list[Event] = []
        for item in items:
            ev = self.to_event(item)
            if ev is None or not in_window(ev.start, now):
                continue
            slug_ = ev.ref["slug"]
            cached_card = old_cards.get(slug_)
            card_time = parse_instant((cached_card or {}).get("fetchedAt"))
            bouts = None
            if cached_card and card_time and now - card_time < self.CARD_TTL:
                bouts = cached_card.get("bouts") or []
                new_cards.append(cached_card)
            elif self.requests < 1 + self.MAX_CARDS:
                try:
                    raw = self._get(http, f"{self.BASE}/events/{slug_}/bouts", headers, f"ufc-bouts-{slug(slug_)}")
                    bouts = [normalize_bout(b) for b in unwrap_list(raw, "bouts", "fights", "items", "card")]
                    bouts = [b for b in bouts if b]
                    new_cards.append({"slug": slug_, "fetchedAt": utc_iso(now), "bouts": bouts})
                except ProviderError:
                    if cached_card:                       # cartelera anterior, aunque sea vieja
                        bouts = cached_card.get("bouts") or []
                        new_cards.append(cached_card)
            if bouts is None and cached_card:
                bouts = cached_card.get("bouts") or []
            self.apply_card(ev, bouts or [], item)
            events.append(ev)

        self.cache_out = {
            "version": 1,
            "fetchedAt": utc_iso(now),
            "events": [event_to_json(e) for e in events],
            "cards": new_cards,
        }
        return events

    # ------------------------------------------------------------------ interno

    def _get(self, http: Http, url: str, headers: dict, label: str) -> Any:
        self.requests += 1
        return http.get_json(url, headers=headers, label=label)

    def to_event(self, item: Any) -> Optional[Event]:
        if not isinstance(item, dict):
            return None
        slug_ = clean(first(item, "slug", "eventSlug", "id", "eventId"))
        name = clean(first(item, "name", "title", "eventName", "shortName"))
        if not slug_ or not name:
            return None
        raw_start = first(item, "startTime", "startsAt", "start", "dateTime", "datetime", "mainCardStart",
                          "mainCardTime", "date", "eventDate", "startDate")
        start, tbd = parse_event_start(raw_start)
        if start is None:
            return None
        return Event(
            id=f"ufc-{slug(slug_)}",
            sport="ufc",
            competition=name,
            start=start,
            title=name,
            timeTbd=tbd,
            ref={"slug": slug_},
        )

    def apply_card(self, ev: Event, bouts: list[dict], item: dict) -> None:
        main = next((b for b in bouts if b.get("main")), None) or (bouts[0] if bouts else None)
        if main is None:   # sin cartelera: ¿trae el listado el combate principal?
            main = normalize_bout(first(item, "mainEvent", "headliner", "mainBout") or {})
        if main and main.get("red") and main.get("blue"):
            ev.home, ev.away = main["red"], main["blue"]
            ev.title = f"{main['red']} - {main['blue']}"
            ev.homeImage = main.get("redImage")
            ev.awayImage = main.get("blueImage")
        ev.card = [{k: v for k, v in b.items() if k in ("red", "blue", "weightClass", "section", "order", "main")}
                   for b in bouts]


# ---------------------------------------------------------------------- funciones puras

def unwrap_list(data: Any, *keys: str) -> list:
    """Cito responde {ok, data, meta}; data puede ser la lista o un objeto con la lista dentro."""
    if isinstance(data, list):
        return data
    if not isinstance(data, dict):
        raise ProviderError(INVALID_RESPONSE, "unexpected structure")
    if data.get("ok") is False:
        raise ProviderError(INVALID_RESPONSE, "api error")
    inner = data.get("data", data)
    if isinstance(inner, list):
        return inner
    if isinstance(inner, dict):
        for key in keys:
            if isinstance(inner.get(key), list):
                return inner[key]
    raise ProviderError(INVALID_RESPONSE, "unexpected structure")


def parse_event_start(raw: Any) -> tuple[Optional[datetime], bool]:
    """Fecha con hora → (instante, False). Solo día → (mediodía UTC de ese día, True): sin inventar hora."""
    if raw in (None, ""):
        return None, False
    text = str(raw).strip()
    if not TIME_IN_TEXT.search(text):
        try:
            day = datetime.fromisoformat(text[:10]).date()
        except ValueError:
            return None, False
        return datetime(day.year, day.month, day.day, 12, 0, tzinfo=timezone.utc), True
    dt = parse_instant(text)
    return (dt, False) if dt else (None, False)


def fighter_name(value: Any) -> Optional[str]:
    if isinstance(value, dict):
        name = clean(first(value, "name", "fullName", "fighterName", "displayName"))
        if name:
            return name
        value = first(value, "fighterSlug", "slug")
    text = clean(value)
    if not text:
        return None
    if "-" in text and " " not in text:          # "alexander-volkanovski" → "Alexander Volkanovski"
        return " ".join(w.capitalize() for w in text.split("-") if w)
    return text


def fighter_image(value: Any) -> Optional[str]:
    """Retrato del luchador. Nunca la bandera."""
    if not isinstance(value, dict):
        return None
    for source in (value.get("images"), value):
        if isinstance(source, dict):
            for key in IMAGE_KEYS:
                url = source.get(key)
                if isinstance(url, str) and url.startswith("http") and "flag" not in url.lower():
                    return url
    return None


def normalize_bout(bout: Any) -> Optional[dict]:
    if not isinstance(bout, dict):
        return None
    red = blue = None
    fighters = bout.get("fighters") or bout.get("corners")
    if isinstance(fighters, list) and len(fighters) >= 2:
        by_corner = {str((f or {}).get("corner", "")).lower(): f for f in fighters if isinstance(f, dict)}
        red = by_corner.get("red") or fighters[0]
        blue = by_corner.get("blue") or fighters[1]
    elif isinstance(fighters, dict):
        red, blue = fighters.get("red"), fighters.get("blue")
    if red is None and blue is None:
        red = first(bout, "red", "redCorner", "fighter1", "fighterA", "home")
        blue = first(bout, "blue", "blueCorner", "fighter2", "fighterB", "away")
    red_name, blue_name = fighter_name(red), fighter_name(blue)
    if not red_name or not blue_name:
        return None
    order = first(bout, "boutOrder", "order", "position")
    main = bool(first(bout, "isMainEvent", "mainEvent", "main")) or order in (1, "1")
    return {
        "red": red_name, "blue": blue_name,
        "redImage": fighter_image(red), "blueImage": fighter_image(blue),
        "weightClass": clean(first(bout, "weightClass", "division")) or None,
        "section": clean(first(bout, "cardSection", "section", "segment")) or None,
        "order": order if isinstance(order, int) else None,
        "main": main,
    }
