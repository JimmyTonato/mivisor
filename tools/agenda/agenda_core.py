"""
Modelo común de la agenda y utilidades compartidas por todos los proveedores.

Todos los proveedores (Football-Data, Live Tennis, Blacktop, Jolpica) producen `Event`.
`Event` se serializa a agenda.json versión 1, el formato que ya entiende la APK.
La APK no sabe qué proveedor generó cada evento.
"""
from __future__ import annotations

import json
import os
import re
import unicodedata
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Iterable, Optional
from zoneinfo import ZoneInfo

TZ = ZoneInfo("Europe/Madrid")
SCHEMA_VERSION = 1
SOURCE_NAME = "MiVisor Sports APIs"

# Ventana temporal de la agenda: todo el día de hoy (y como mínimo 6 h atrás) y los próximos 14 días.
HOURS_BACK = 6
DAYS_AHEAD = 14

# Valores de "sport" que entiende la APK. "motor" y "baloncesto" se mantienen por compatibilidad.
SPORTS = ("futbol", "tenis", "formula1", "motogp", "ufc", "motor", "baloncesto")


@dataclass
class Channel:
    name: str
    dial: Optional[str] = None
    note: Optional[str] = None
    logo: Optional[str] = None


@dataclass
class Event:
    id: str                      # estable entre ejecuciones (id del proveedor con prefijo)
    sport: str
    competition: Optional[str]
    start: datetime              # siempre con zona horaria
    title: str
    home: Optional[str] = None
    away: Optional[str] = None
    channels: list[Channel] = field(default_factory=list)  # nunca se inventan: puede ir vacío
    homeLogo: Optional[str] = None
    awayLogo: Optional[str] = None
    competitionLogo: Optional[str] = None
    # Identificador del partido para pedir sus canales de TV (lo usa la app al abrir el partido).
    tvId: Optional[str] = None
    # Retratos de los participantes en eventos 1 contra 1 (tenis, UFC). Opcionales: nunca banderas.
    homeImage: Optional[str] = None
    awayImage: Optional[str] = None
    # La fuente solo da el día, no la hora (p. ej. una velada UFC aún sin horario): no se inventa.
    timeTbd: bool = False
    # Cartelera (UFC): [{"red", "blue", "weightClass", "section", "order", "main"}]. Opcional.
    card: list = field(default_factory=list)
    # Datos internos del proveedor (jornada, temporada, nombres completos…) para enriquecer el
    # evento. NUNCA se escriben en agenda.json.
    ref: dict = field(default_factory=dict, compare=False, repr=False)


# --------------------------------------------------------------------------------------------
# Texto y fechas
# --------------------------------------------------------------------------------------------

def clean(text) -> str:
    return re.sub(r"\s+", " ", str(text or "").replace("\xa0", " ")).strip()


def slug(text: str) -> str:
    norm = unicodedata.normalize("NFKD", str(text)).encode("ascii", "ignore").decode().lower()
    return re.sub(r"[^a-z0-9]+", "-", norm).strip("-")


def parse_instant(value, assume: timezone | ZoneInfo = timezone.utc) -> Optional[datetime]:
    """ISO 8601 → datetime con zona. Sin zona explícita se asume [assume] (UTC por defecto)."""
    if value is None:
        return None
    text = str(value).strip()
    if not text:
        return None
    text = text.replace("Z", "+00:00")
    if "T" not in text and " " in text:
        text = text.replace(" ", "T", 1)
    try:
        dt = datetime.fromisoformat(text)
    except ValueError:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=assume)
    return dt


def to_madrid_iso(dt: datetime) -> str:
    """Hora local de presentación (Europe/Madrid), con su desfase real (CET/CEST)."""
    return dt.astimezone(TZ).isoformat()


def utc_iso(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def window(now: datetime) -> tuple[datetime, datetime]:
    """
    Desde las 00:00 de hoy (hora de Madrid) hasta dentro de 14 días: los partidos de hoy se
    conservan aunque ya se hayan jugado, y los de días anteriores desaparecen. Si es muy temprano,
    se mantienen además las últimas horas (un partido de anoche que aún pueda estar en juego).
    """
    midnight = now.astimezone(TZ).replace(hour=0, minute=0, second=0, microsecond=0)
    return min(midnight, now - timedelta(hours=HOURS_BACK)), now + timedelta(days=DAYS_AHEAD)


def in_window(dt: datetime, now: datetime) -> bool:
    lo, hi = window(now)
    return lo <= dt <= hi


# --------------------------------------------------------------------------------------------
# Serialización agenda.json v1
# --------------------------------------------------------------------------------------------

def event_to_json(ev: Event) -> dict:
    data = {
        "id": ev.id,
        "sport": ev.sport,
        "competition": ev.competition,
        "start": to_madrid_iso(ev.start),
        "home": ev.home,
        "away": ev.away,
        "title": ev.title,
        "homeLogo": ev.homeLogo,
        "awayLogo": ev.awayLogo,
        "competitionLogo": ev.competitionLogo,
        "tvId": ev.tvId,
        "homeImage": ev.homeImage,
        "awayImage": ev.awayImage,
    }
    data = {k: v for k, v in data.items() if v not in (None, "")}
    if ev.timeTbd:
        data["timeTbd"] = True
    if ev.card:
        data["card"] = ev.card
    data["channels"] = [{k: v for k, v in vars(c).items() if v is not None} for c in ev.channels]
    return data


def event_from_json(data: dict) -> Optional[Event]:
    """Lee un evento de un agenda.json anterior (para conservarlo como "stale")."""
    try:
        start = parse_instant(data.get("start"), assume=TZ)
        if start is None or not data.get("id") or not data.get("sport"):
            return None
        channels = [
            Channel(name=c["name"], dial=c.get("dial"), note=c.get("note"), logo=c.get("logo"))
            for c in data.get("channels") or [] if isinstance(c, dict) and c.get("name")
        ]
        return Event(
            id=str(data["id"]), sport=str(data["sport"]), competition=data.get("competition"),
            start=start, title=data.get("title") or " - ".join(x for x in (data.get("home"), data.get("away")) if x),
            home=data.get("home"), away=data.get("away"), channels=channels,
            homeLogo=data.get("homeLogo"), awayLogo=data.get("awayLogo"), competitionLogo=data.get("competitionLogo"),
            tvId=str(data["tvId"]) if data.get("tvId") else None,
            homeImage=data.get("homeImage"), awayImage=data.get("awayImage"),
            timeTbd=bool(data.get("timeTbd")),
            card=[c for c in data.get("card") or [] if isinstance(c, dict)],
        )
    except (KeyError, TypeError, AttributeError):
        return None


def build_agenda(events: Iterable[Event], now: datetime, stats: dict) -> dict:
    ordered = sorted(events, key=lambda e: (e.start, e.competition or "", e.title))
    return {
        "version": SCHEMA_VERSION,
        "source": SOURCE_NAME,
        "generatedAt": utc_iso(now),
        "timezone": "Europe/Madrid",
        "stats": stats,
        "events": [event_to_json(e) for e in ordered],
    }


def load_previous(path: Optional[str]) -> Optional[dict]:
    """Última agenda publicada (rama agenda-data). Si no existe o no es válida → None."""
    if not path or not os.path.isfile(path):
        return None
    try:
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
    except (OSError, ValueError):
        return None
    if not isinstance(data, dict) or data.get("version") != SCHEMA_VERSION or not isinstance(data.get("events"), list):
        return None
    return data


def previous_events(previous: Optional[dict], sports: Iterable[str], now: datetime) -> list[Event]:
    """Eventos anteriores de esos deportes que siguen dentro de la ventana (no los caducados)."""
    if not previous:
        return []
    wanted = set(sports)
    out = []
    for raw in previous.get("events", []):
        if not isinstance(raw, dict) or raw.get("sport") not in wanted:
            continue
        ev = event_from_json(raw)
        if ev and in_window(ev.start, now):
            out.append(ev)
    return out


# --------------------------------------------------------------------------------------------
# Logos propios (logos.json): tienen prioridad sobre los del proveedor
# --------------------------------------------------------------------------------------------

def load_logo_overrides(path: Optional[str]) -> dict[str, dict[str, str]]:
    """logos.json: {"teams": {...}, "competitions": {...}, "channels": {...}} — nombre → URL."""
    empty = {"teams": {}, "competitions": {}, "channels": {}}
    if not path or not os.path.isfile(path):
        return empty
    try:
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
    except (OSError, ValueError):
        return empty
    return {k: {slug(n): u for n, u in (data.get(k) or {}).items() if u} for k in empty}


def apply_logos(events: Iterable[Event], logos: dict[str, dict[str, str]]) -> None:
    teams, comps, chans = logos["teams"], logos["competitions"], logos["channels"]
    for ev in events:
        if ev.home and slug(ev.home) in teams:
            ev.homeLogo = teams[slug(ev.home)]
        if ev.away and slug(ev.away) in teams:
            ev.awayLogo = teams[slug(ev.away)]
        if ev.competition and slug(ev.competition) in comps:
            ev.competitionLogo = comps[slug(ev.competition)]
        for ch in ev.channels:
            if slug(ch.name) in chans:
                ch.logo = chans[slug(ch.name)]


# --------------------------------------------------------------------------------------------
# Punto de extensión para un futuro proveedor de TV
# --------------------------------------------------------------------------------------------

class TvProvider:
    """
    SportsProvider → Event → TvProvider (opcional) → Event enriquecido → agenda.json.
    Esta implementación base no añade nada. Un proveedor de TV (tv_thesportsdb.py) solo añade
    canales confirmados por una fuente real para cada evento concreto: nunca se inventan.
    """
    log: list[str] = []

    def enrich(self, events: list[Event]) -> list[Event]:
        return events
