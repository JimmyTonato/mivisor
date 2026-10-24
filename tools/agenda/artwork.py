"""
Retratos de deportistas (athletes.json) — TheSportsDB como fuente de artwork, con caché larga.

- Catálogo ligero publicado en la rama agenda-data y recuperado en cada ejecución:
    {"version": 1, "updatedAt": …, "athletes": {"tenis:carlos-alcaraz": {"name", "sport", "image",
     "source", "updatedAt"}}, "rosters": {"formula1": {"fetchedAt", "names": […]}}}
- Una cara ya encontrada se reutiliza POSITIVE_TTL (30 días); una búsqueda sin resultado no se repite
  hasta NEGATIVE_TTL (7 días). Nunca se busca la misma cara cada hora.
- Imagen preferida: strCutout → strThumb → strRender → null (placeholder en la app).
- Validación: el jugador devuelto debe ser del mismo deporte y con el mismo apellido (e inicial si se
  conoce). Si no, no hay retrato: mejor placeholder que la cara de otra persona.
- Tenis y UFC: los retratos van en el evento (homeImage / awayImage). UFC prioriza la imagen de Cito y
  usa TheSportsDB solo si Cito no trae ninguna.
- F1 y MotoGP: los retratos se preparan en el catálogo (para vistas de pilotos, clasificaciones o el
  detalle de un GP), pero NO se ponen en la tarjeta de una carrera: no es un 1 contra 1.
- La clave pública gratuita de TheSportsDB corta tras unas ~10 peticiones seguidas: se usa un
  presupuesto pequeño por ejecución y se para al primer 429. Los pendientes se buscan la hora siguiente.
"""
from __future__ import annotations

import json
import os
import time
from datetime import datetime, timedelta
from typing import Any, Callable, Iterable, Mapping, Optional

from agenda_core import Event, TvProvider, clean, parse_instant, slug, utc_iso
from matching import ascii_lower, person_parts, person_similarity
from providers import RATE_LIMITED, Http, ProviderError

TSDB_BASE = "https://www.thesportsdb.com/api/v1/json"
KEY_ENV = "THESPORTSDB_API_KEY"
PUBLIC_KEY = "123"
JOLPICA_BASE = "https://api.jolpi.ca/ergast/f1"
POSITIVE_TTL = timedelta(days=30)
NEGATIVE_TTL = timedelta(days=7)
ROSTER_TTL = timedelta(days=7)
TSDB_SPORT = {"tenis": "Tennis", "ufc": "Fighting", "formula1": "Motorsport", "motogp": "Motorsport"}
PORTRAIT_SPORTS = ("tenis", "ufc")       # retratos dentro del evento (1 contra 1)
ROSTER_SPORTS = ("formula1", "motogp")   # retratos solo en el catálogo


def athlete_key(sport: str, name: str) -> str:
    return f"{sport}:{slug(name)}"


def load_catalog(path: Optional[str]) -> dict:
    empty = {"version": 1, "athletes": {}, "rosters": {}}
    if not path or not os.path.isfile(path):
        return empty
    try:
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
    except (OSError, ValueError):
        return empty
    if not isinstance(data, dict) or not isinstance(data.get("athletes"), dict):
        return empty
    data.setdefault("rosters", {})
    return data


def best_image(player: Mapping[str, Any]) -> Optional[str]:
    """strCutout → strThumb → strRender. Cadenas vacías o que no sean URL no cuentan."""
    for key in ("strCutout", "strThumb", "strRender"):
        url = player.get(key)
        if isinstance(url, str) and url.startswith("http"):
            return url
    return None


def player_matches(player: Mapping[str, Any], wanted: str, sport: str) -> bool:
    if TSDB_SPORT.get(sport) and (player.get("strSport") or "") != TSDB_SPORT[sport]:
        return False
    return person_similarity(wanted, player.get("strPlayer") or "") >= 0.9


def search_query(name: str) -> str:
    """Nombre completo si lo hay; si viene abreviado ("C. Alcaraz") se busca por apellido."""
    surname, initial = person_parts(name)
    words = [w for w in ascii_lower(name).split() if len(w) > 1]
    query = " ".join(words) if len(words) >= 2 else surname
    return query.replace(" ", "_")


class Artwork(TvProvider):
    """Enriquecedor: añade retratos a los eventos y mantiene el catálogo athletes.json."""
    service = "thesportsdb"
    label = "TheSportsDB artwork"
    MAX_REQUESTS = 6           # por ejecución (además de las de tvId)
    MIN_INTERVAL = 2.5

    def __init__(self, http: Http, env: Mapping[str, str], now: datetime, catalog: Optional[dict] = None,
                 motogp_riders: Iterable[str] = (), budget: Optional[int] = None, after: Any = None,
                 sleep: Callable[[float], None] = time.sleep, clock: Callable[[], float] = time.monotonic):
        self.http = http
        self.key = (env.get(KEY_ENV) or "").strip() or PUBLIC_KEY
        self.now = now
        self.catalog = catalog if catalog else {"version": 1, "athletes": {}, "rosters": {}}
        self.catalog.setdefault("athletes", {})
        self.catalog.setdefault("rosters", {})
        self.motogp_riders = [clean(x) for x in motogp_riders if clean(x)]
        self.budget = self.MAX_REQUESTS if budget is None else max(0, budget)
        self.sleep, self.clock = sleep, clock
        self.after = after          # otro cliente de TheSportsDB que va antes (tvId): si le cortan, no se insiste
        self.requests = 0
        self.last_request: Optional[float] = None
        self.stopped: Optional[str] = None
        self.errors = 0
        self.new = 0
        self.log: list[str] = []
        self.status = "ok"
        self.error: Optional[str] = None

    # ------------------------------------------------------------------ API pública

    def enrich(self, events: list[Event]) -> list[Event]:
        try:
            self._enrich(events)
        except Exception as exc:  # noqa: BLE001 — los retratos nunca rompen la agenda
            self.status, self.error = "unavailable", "invalid_response"
            self.log.append(f"{self.label}: FAILED - {type(exc).__name__}")
        return events

    def to_json(self) -> dict:
        self.catalog["version"] = 1
        self.catalog["updatedAt"] = utc_iso(self.now)
        return self.catalog

    # ------------------------------------------------------------------ interno

    def _enrich(self, events: list[Event]) -> None:
        pending: list[tuple[str, str]] = []

        # 1) Eventos 1 contra 1: imagen ya conocida o pendiente de buscar.
        for ev in events:
            if ev.sport not in PORTRAIT_SPORTS:
                continue
            for side in ("home", "away"):
                name = getattr(ev, side)
                if not name or name.lower().startswith("por determinar"):
                    continue
                own = getattr(ev, f"{side}Image")
                if own:                                  # Cito ya la trae: se registra como fuente
                    self._remember(ev.sport, name, own, "cito")
                    continue
                entry = self._fresh(ev.sport, name)
                if entry is not None:
                    setattr(ev, f"{side}Image", entry.get("image"))
                elif (ev.sport, name) not in pending:
                    pending.append((ev.sport, name))

        # 2) Pilotos de F1 y MotoGP: solo catálogo (sin tocar las tarjetas de carrera).
        for sport, names in (("formula1", self._f1_roster()), ("motogp", self.motogp_riders)):
            self.catalog["rosters"].setdefault(sport, {})["names"] = list(names)
            for name in names:
                if self._fresh(sport, name) is None and (sport, name) not in pending:
                    pending.append((sport, name))

        # 3) Búsquedas nuevas, primero las de eventos (más útiles).
        for sport, name in pending:
            if self.stopped:
                break
            image = self._search(sport, name)
            if image is False:
                continue                                 # fallo técnico: se reintenta otra hora
            self._remember(sport, name, image or None, "thesportsdb")
            if image:
                self.new += 1
            for ev in events:
                if ev.sport == sport:
                    for side in ("home", "away"):
                        if getattr(ev, side) == name and not getattr(ev, f"{side}Image"):
                            setattr(ev, f"{side}Image", image or None)

        left = sum(1 for s, n in pending if self._fresh(s, n) is None)
        if self.stopped == RATE_LIMITED or (self.errors and not self.new and self.requests and self.errors >= self.requests):
            self.status, self.error = "unavailable", self.stopped or "provider_unavailable"
        state = "OK" if self.status == "ok" else self.status.upper()
        self.log.append(f"{self.label}: {state} - {self.new} new portraits, {len(self.catalog['athletes'])} cached, "
                        f"{left} pending, {self.requests} requests")

    def _fresh(self, sport: str, name: str) -> Optional[dict]:
        entry = self.catalog["athletes"].get(athlete_key(sport, name))
        if not isinstance(entry, dict):
            return None
        updated = parse_instant(entry.get("updatedAt"))
        if updated is None:
            return None
        ttl = POSITIVE_TTL if entry.get("image") else NEGATIVE_TTL
        return entry if self.now - updated < ttl else None

    def _remember(self, sport: str, name: str, image: Optional[str], source: str) -> None:
        key = athlete_key(sport, name)
        old = self.catalog["athletes"].get(key) or {}
        if old.get("image") == image and old.get("source") == source and self._fresh(sport, name):
            return
        self.catalog["athletes"][key] = {"name": name, "sport": sport, "image": image,
                                         "source": source if image else None, "updatedAt": utc_iso(self.now)}

    def _search(self, sport: str, name: str):
        """URL, "" si no hay retrato válido, o False si la consulta falló (se reintentará)."""
        data = self._get("searchplayers.php", {"p": search_query(name)}, f"art-{slug(name)}")
        if data is None:
            return False
        players = data.get("player") if isinstance(data, dict) else None
        if not isinstance(players, list):
            return ""
        for player in players:
            if isinstance(player, dict) and player_matches(player, name, sport):
                return best_image(player) or ""
        return ""

    def _get(self, endpoint: str, params: dict, label: str) -> Any:
        if self.after is not None and getattr(self.after, "stopped", None) == RATE_LIMITED:
            self.stopped = RATE_LIMITED
        if self.stopped:
            return None
        if self.requests >= self.budget:
            self.stopped = "request budget reached"
            return None
        if self.last_request is not None:
            wait = self.MIN_INTERVAL - (self.clock() - self.last_request)
            if wait > 0:
                self.sleep(wait)
        self.requests += 1
        try:
            return self.http.get_json(f"{TSDB_BASE}/{self.key}/{endpoint}", params=params, label=label)
        except ProviderError as exc:
            self.errors += 1
            if exc.code == RATE_LIMITED:
                self.stopped = RATE_LIMITED
            return None
        finally:
            self.last_request = self.clock()

    def _f1_roster(self) -> list[str]:
        """Pilotos de la temporada (Jolpica, sin clave), renovados como mucho una vez por semana."""
        roster = self.catalog["rosters"].get("formula1") or {}
        fetched = parse_instant(roster.get("fetchedAt"))
        if fetched and self.now - fetched < ROSTER_TTL and roster.get("names"):
            return list(roster["names"])
        try:
            data = self.http.get_json(f"{JOLPICA_BASE}/{self.now.year}/drivers.json", params={"limit": 100},
                                      label="art-f1-drivers")
            drivers = (((data or {}).get("MRData") or {}).get("DriverTable") or {}).get("Drivers") or []
            names = [clean(f"{d.get('givenName', '')} {d.get('familyName', '')}") for d in drivers if isinstance(d, dict)]
            names = [n for n in names if n]
            if names:
                self.catalog["rosters"]["formula1"] = {"fetchedAt": utc_iso(self.now), "names": names}
                return names
        except ProviderError:
            pass
        return list(roster.get("names") or [])


def load_seed(path: Optional[str]) -> dict:
    """athletes_seed.json: listas editables (p. ej. pilotos de MotoGP de la temporada)."""
    if not path or not os.path.isfile(path):
        return {}
    try:
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}
