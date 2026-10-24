"""
Proveedores deportivos → Event.

Cada proveedor:
  - lee su credencial SOLO de una variable de entorno (GitHub Secret),
  - pide LISTADOS del rango de fechas necesario (nunca una petición por partido),
  - filtra localmente (competiciones/torneos de primer nivel),
  - devuelve una lista de Event (puede estar vacía: "OK con 0 eventos" no es un error),
  - o lanza ProviderError con un código genérico si la consulta falla.

Nunca se registran claves, cabeceras completas ni URLs con credenciales.
"""
from __future__ import annotations

import json
import re
import socket
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from typing import Any, Callable, Mapping, Optional

from agenda_core import Event, TZ, clean, in_window, parse_instant, slug, window

USER_AGENT = "MiVisorAgenda/2.0 (+GitHub Actions; agenda horaria)"
MAX_BODY = 8 * 1024 * 1024

# Códigos de error genéricos (los únicos que llegan a agenda.json).
PROVIDER_UNAVAILABLE = "provider_unavailable"
RATE_LIMITED = "rate_limited"
AUTHENTICATION_ERROR = "authentication_error"
INVALID_RESPONSE = "invalid_response"
TIMEOUT = "timeout"


class ProviderError(Exception):
    """Fallo de un proveedor. `code` va a agenda.json; `detail` solo al log (sin secretos)."""

    def __init__(self, code: str, detail: str = ""):
        super().__init__(code)
        self.code = code
        self.detail = detail


def code_for_status(status: int) -> str:
    if status in (401, 403):
        return AUTHENTICATION_ERROR
    if status == 429:
        return RATE_LIMITED
    if status in (408, 504):
        return TIMEOUT
    return PROVIDER_UNAVAILABLE


class Http:
    """GET JSON con timeouts y errores normalizados. Los tests lo sustituyen por un falso."""

    def __init__(self, timeout: float = 25.0, recorder: Optional[Callable[[str, bytes], None]] = None):
        self.timeout = timeout
        self.recorder = recorder

    def get_json(self, url: str, headers: Optional[Mapping[str, str]] = None,
                 params: Optional[Mapping[str, Any]] = None, label: str = "") -> Any:
        if params:
            url = f"{url}?{urllib.parse.urlencode(params)}"
        request = urllib.request.Request(url, headers={
            "User-Agent": USER_AGENT, "Accept": "application/json", **(headers or {})
        })
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as resp:
                body = resp.read(MAX_BODY + 1)
        except urllib.error.HTTPError as e:
            raise ProviderError(code_for_status(e.code), f"HTTP {e.code}") from None
        except (socket.timeout, TimeoutError):
            raise ProviderError(TIMEOUT, "timeout") from None
        except urllib.error.URLError as e:
            if isinstance(e.reason, (socket.timeout, TimeoutError)):
                raise ProviderError(TIMEOUT, "timeout") from None
            raise ProviderError(PROVIDER_UNAVAILABLE, "connection error") from None
        if len(body) > MAX_BODY:
            raise ProviderError(INVALID_RESPONSE, "response too large")
        if self.recorder:
            self.recorder(label, body)
        try:
            return json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, ValueError):
            raise ProviderError(INVALID_RESPONSE, "invalid JSON") from None


class Provider:
    key = ""          # clave en "stats" (football, tennis, formula1, motogp)
    name = ""         # identificador del proveedor en "stats"
    label = ""        # nombre en los logs
    sports: tuple[str, ...] = ()
    secret_env: Optional[str] = None

    def credential(self, env: Mapping[str, str]) -> Optional[str]:
        if not self.secret_env:
            return None
        value = (env.get(self.secret_env) or "").strip()
        if not value:
            raise ProviderError(AUTHENTICATION_ERROR, f"{self.secret_env} not configured")
        return value

    def fetch(self, http: Http, now: datetime, env: Mapping[str, str]) -> list[Event]:
        raise NotImplementedError


def first(obj: Any, *keys: str) -> Any:
    """Primer valor no vacío entre varias claves posibles (APIs con nombres variables)."""
    if not isinstance(obj, dict):
        return None
    for key in keys:
        value = obj.get(key)
        if value not in (None, "", [], {}):
            return value
    return None


def name_of(value: Any) -> Optional[str]:
    if isinstance(value, dict):
        return clean(first(value, "shortName", "short_name", "name", "full_name", "fullName")) or None
    return clean(value) or None


def items_of(data: Any, *keys: str) -> list:
    if isinstance(data, list):
        return data
    if isinstance(data, dict):
        for key in keys:
            value = data.get(key)
            if isinstance(value, list):
                return value
    raise ProviderError(INVALID_RESPONSE, "unexpected structure")


# --------------------------------------------------------------------------------------------
# Fútbol · Football-Data.org v4
# --------------------------------------------------------------------------------------------

class FootballData(Provider):
    key, name, label = "football", "football-data", "Football-Data"
    sports = ("futbol",)
    secret_env = "FOOTBALL_DATA_TOKEN"
    BASE = "https://api.football-data.org/v4"

    # ALLOWLIST explícita: solo primeras divisiones y torneos de primer nivel.
    # No incluye ELC (Championship) ni ninguna segunda división.
    COMPETITIONS = {
        "PD": "LaLiga",
        "PL": "Premier League",
        "SA": "Serie A",
        "BL1": "Bundesliga",
        "FL1": "Ligue 1",
        "CL": "Champions League",
        "EC": "Eurocopa",
        "WC": "Mundial",
    }
    SKIP_STATUS = {"CANCELLED", "POSTPONED", "SUSPENDED", "AWARDED"}
    # La API limita el rango dateFrom–dateTo; se pide en tramos de 9 días (2 peticiones/ejecución).
    CHUNK_DAYS = 9

    def fetch(self, http: Http, now: datetime, env: Mapping[str, str]) -> list[Event]:
        token = self.credential(env)
        lo, hi = window(now)
        day = lo.astimezone(timezone.utc).date()
        last = hi.astimezone(timezone.utc).date() + timedelta(days=1)
        events: dict[str, Event] = {}
        while day <= last:
            chunk_end = min(day + timedelta(days=self.CHUNK_DAYS), last)
            data = http.get_json(
                f"{self.BASE}/matches",
                headers={"X-Auth-Token": token},
                params={
                    "competitions": ",".join(self.COMPETITIONS),
                    "dateFrom": day.isoformat(),
                    "dateTo": chunk_end.isoformat(),
                },
                label=f"football-{day.isoformat()}",
            )
            for match in items_of(data, "matches"):
                ev = self.to_event(match)
                if ev and in_window(ev.start, now):
                    events[ev.id] = ev
            if chunk_end >= last:
                break
            day = chunk_end  # solapa un día: dateTo puede ser exclusivo
        return list(events.values())

    def to_event(self, match: dict) -> Optional[Event]:
        if not isinstance(match, dict) or match.get("id") is None:
            return None
        competition = match.get("competition") or {}
        code = competition.get("code")
        if code not in self.COMPETITIONS:      # segundas divisiones y demás: fuera
            return None
        if match.get("status") in self.SKIP_STATUS:
            return None
        start = parse_instant(match.get("utcDate"))
        if start is None:
            return None
        home_team = match.get("homeTeam") or {}
        away_team = match.get("awayTeam") or {}
        home = name_of(home_team) or "Por determinar"
        away = name_of(away_team) or "Por determinar"
        return Event(
            id=f"football-{match['id']}",
            sport="futbol",
            competition=self.COMPETITIONS[code],
            start=start,
            title=f"{home} - {away}",
            home=home,
            away=away,
            homeLogo=home_team.get("crest"),
            awayLogo=away_team.get("crest"),
            competitionLogo=competition.get("emblem"),
            ref={
                "competitionCode": code,
                "matchday": match.get("matchday"),
                "season": season_label(match.get("season")),
                "homeNames": team_names(home_team),
                "awayNames": team_names(away_team),
            },
        )


def season_label(season: Any) -> Optional[str]:
    """{"startDate": "2026-08-14", "endDate": "2027-05-23"} → "2026-2027" (formato TheSportsDB)."""
    if not isinstance(season, dict):
        return None
    start, end = str(season.get("startDate") or ""), str(season.get("endDate") or "")
    if len(start) < 4 or not start[:4].isdigit():
        return None
    if len(end) >= 4 and end[:4].isdigit() and end[:4] != start[:4]:
        return f"{start[:4]}-{end[:4]}"
    return start[:4]


def team_names(team: Any) -> list[str]:
    """Variantes del nombre de un equipo (corto, completo, siglas) para cruzar con otras fuentes."""
    if not isinstance(team, dict):
        return []
    out = []
    for key in ("shortName", "name", "tla"):
        value = clean(team.get(key))
        if value and value not in out:
            out.append(value)
    return out


# --------------------------------------------------------------------------------------------
# Tenis · Live Tennis API
# --------------------------------------------------------------------------------------------

class LiveTennis(Provider):
    key, name, label = "tennis", "live-tennis-api", "Live Tennis"
    sports = ("tenis",)
    secret_env = "LIVE_TENNIS_API_KEY"
    BASE = "https://api.livetennisapi.com/api/public/v1"

    # Solo circuitos principales. Challenger, ITF y juniors quedan fuera.
    TOURS = ("atp", "wta")
    MINOR = re.compile(r"challenger|\bitf\b|futures|juniors?|\bm15\b|\bm25\b|\bw15\b|\bw35\b|\bw50\b|\bw75\b|"
                       r"\bw100\b|\bwta 125\b|\b125k?\b|exhibition|exhibici|\butr\b|qualifying|qualies|previa",
                       re.IGNORECASE)
    MAX_PAGES = 4

    def fetch(self, http: Http, now: datetime, env: Mapping[str, str]) -> list[Event]:
        key = self.credential(env)
        headers = {"Authorization": f"Bearer {key}"}
        events: dict[str, Event] = {}
        for tour in self.TOURS:
            params: dict[str, Any] = {"tour": tour, "limit": 200}
            for page in range(self.MAX_PAGES):
                data = http.get_json(f"{self.BASE}/fixtures", headers=headers, params=params,
                                     label=f"tennis-{tour}-{page}")
                for item in items_of(data, "data", "fixtures", "results", "items"):
                    ev = self.to_event(item, tour)
                    if ev and in_window(ev.start, now):
                        events[ev.id] = ev
                cursor = self.next_cursor(data)
                if not cursor:
                    break
                params = {**params, **cursor}
        return list(events.values())

    @staticmethod
    def next_cursor(data: Any) -> Optional[dict]:
        meta = data.get("meta") if isinstance(data, dict) else None
        if not isinstance(meta, dict) or not meta.get("has_more"):
            return None
        for key in ("next_cursor", "cursor"):
            if meta.get(key):
                return {"cursor": meta[key]}
        if isinstance(meta.get("next_offset"), int):
            return {"offset": meta["next_offset"]}
        if isinstance(meta.get("offset"), int) and isinstance(meta.get("count"), int):
            return {"offset": meta["offset"] + meta["count"]}
        return None

    def to_event(self, item: dict, requested_tour: str) -> Optional[Event]:
        if not isinstance(item, dict):
            return None
        fixture_id = first(item, "id", "fixture_id", "match_id")
        if fixture_id is None:
            return None
        tour = clean(first(item, "tour") or requested_tour).lower()
        if tour not in self.TOURS:
            return None
        tournament_raw = item.get("tournament")
        tournament = name_of(tournament_raw) or clean(first(item, "tournament_name")) or ""
        level = " ".join(clean(x) for x in (
            first(item, "level", "tier", "category"),
            first(tournament_raw, "level", "tier", "category") if isinstance(tournament_raw, dict) else None,
            tournament,
        ) if x)
        if self.MINOR.search(level):
            return None
        if item.get("is_doubles") or item.get("doubles"):
            return None
        p1, p2 = self.players(item)
        if not p1 or not p2 or "/" in p1 or "/" in p2:   # dobles o sin rivales definidos
            return None
        start = parse_instant(first(item, "start_time", "scheduled_time", "startTime", "scheduled_at", "start"))
        if start is None:                                  # sin hora asignada en el orden de juego
            return None
        tour_label = tour.upper()
        competition = tournament if tournament.upper().startswith(tour_label) else f"{tour_label} · {tournament}".strip(" ·")
        round_name = clean(first(item, "round"))
        if round_name:
            competition = f"{competition} · {round_name}"
        return Event(
            id=f"tennis-{fixture_id}",
            sport="tenis",
            competition=competition or tour_label,
            start=start,
            title=f"{p1} - {p2}",
            home=p1,
            away=p2,
        )

    @staticmethod
    def players(item: dict) -> tuple[Optional[str], Optional[str]]:
        p1 = clean(first(item, "player1_name", "p1_name")) or None
        p2 = clean(first(item, "player2_name", "p2_name")) or None
        if p1 and p2:
            return p1, p2
        players = item.get("players")
        if isinstance(players, dict):
            return name_of(first(players, "p1", "player1", "home")), name_of(first(players, "p2", "player2", "away"))
        if isinstance(players, list) and len(players) >= 2:
            return name_of(players[0]), name_of(players[1])
        return name_of(item.get("player1")), name_of(item.get("player2"))


# --------------------------------------------------------------------------------------------
# Fórmula 1 · Jolpica (API compatible con Ergast, sin clave)
# --------------------------------------------------------------------------------------------

class Jolpica(Provider):
    key, name, label = "formula1", "jolpica", "Jolpica"
    sports = ("formula1",)
    BASE = "https://api.jolpi.ca/ergast/f1"
    SESSIONS = (("Qualifying", "qualifying", "Clasificación"), ("Sprint", "sprint", "Sprint"))

    def fetch(self, http: Http, now: datetime, env: Mapping[str, str]) -> list[Event]:
        lo, hi = window(now)
        events: list[Event] = []
        for year in sorted({lo.year, hi.year}):   # solo la(s) temporada(s) que toca la ventana
            data = http.get_json(f"{self.BASE}/{year}.json", params={"limit": 100}, label=f"f1-{year}")
            try:
                races = data["MRData"]["RaceTable"]["Races"]
            except (KeyError, TypeError):
                raise ProviderError(INVALID_RESPONSE, "unexpected structure") from None
            if not isinstance(races, list):
                raise ProviderError(INVALID_RESPONSE, "unexpected structure")
            for race in races:
                events.extend(ev for ev in self.race_events(race) if in_window(ev.start, now))
        return events

    def race_events(self, race: dict) -> list[Event]:
        if not isinstance(race, dict):
            return []
        season, rnd, name = race.get("season"), race.get("round"), clean(race.get("raceName"))
        if not season or not rnd or not name:
            return []
        sessions = [(race.get(key), kind, label) for key, kind, label in self.SESSIONS]
        sessions.append(({"date": race.get("date"), "time": race.get("time")}, "race", "Carrera"))
        out = []
        for info, kind, label in sessions:
            if not isinstance(info, dict) or not info.get("date") or not info.get("time"):
                continue   # sin hora confirmada: no se inventa
            start = parse_instant(f"{info['date']}T{info['time']}")
            if start is None:
                continue
            out.append(Event(
                id=f"formula1-{season}-{rnd}-{kind}",
                sport="formula1",
                competition="Fórmula 1",
                start=start,
                title=f"{name} · {label}",
            ))
        return out


# --------------------------------------------------------------------------------------------
# MotoGP · Orange Cat Blacktop
# --------------------------------------------------------------------------------------------

class Blacktop(Provider):
    key, name, label = "motogp", "blacktop", "Blacktop"
    sports = ("motogp",)
    secret_env = "BLACKTOP_API_KEY"
    BASE = "https://api.ocblacktop.com/v1/moto-gp"

    PRACTICE = re.compile(r"practice|\bfp\s?\d*\b|\bp\d\b|warm|libre|\btest", re.IGNORECASE)
    SPRINT = re.compile(r"sprint|\bspr\b", re.IGNORECASE)
    QUALIFYING = re.compile(r"qualif|\bq\s?\d?\b|\bqp\b|clasif", re.IGNORECASE)
    RACE = re.compile(r"\brace\b|\brac\b|carrera|grand prix|\bgp\b", re.IGNORECASE)
    LABELS = {"qualifying": "Clasificación", "sprint": "Sprint", "race": "Carrera"}

    def fetch(self, http: Http, now: datetime, env: Mapping[str, str]) -> list[Event]:
        key = self.credential(env)
        headers = {"x-api-key": key}
        data = http.get_json(f"{self.BASE}/events", headers=headers, label="motogp-events")
        lo, hi = window(now)
        events: list[Event] = []
        for raw in items_of(data, "events", "data", "upcoming", "results", "items"):
            if not isinstance(raw, dict) or not self.overlaps(raw, lo, hi):
                continue
            sessions = raw.get("sessions")
            if not isinstance(sessions, list):
                # Detalle solo de los 1-2 Grandes Premios dentro de la ventana (no por sesión).
                event_id = first(raw, "id", "eventId", "slug")
                if event_id is None:
                    continue
                detail = http.get_json(f"{self.BASE}/events/{urllib.parse.quote(str(event_id))}",
                                       headers=headers, label=f"motogp-{slug(str(event_id))}")
                sessions = first(detail, "sessions") or first(first(detail, "event", "data") or {}, "sessions") or []
            events.extend(ev for ev in self.session_events(raw, sessions) if in_window(ev.start, now))
        return events

    @staticmethod
    def overlaps(raw: dict, lo: datetime, hi: datetime) -> bool:
        start = parse_instant(first(raw, "dateStart", "date_start", "startDate", "date"), assume=TZ)
        end = parse_instant(first(raw, "dateEnd", "date_end", "endDate"), assume=TZ) or start
        if start is None:
            return False
        return end.date() >= lo.astimezone(TZ).date() and start.date() <= hi.astimezone(TZ).date()

    def classify(self, name: str) -> Optional[str]:
        if not name or self.PRACTICE.search(name):
            return None
        if self.SPRINT.search(name):
            return "sprint"
        if self.QUALIFYING.search(name):
            return "qualifying"
        if self.RACE.search(name):
            return "race"
        return None

    def session_events(self, raw: dict, sessions: list) -> list[Event]:
        event_name = clean(first(raw, "name", "title", "eventName"))
        country = name_of(raw.get("country")) or name_of(raw.get("location")) or event_name
        start_event = parse_instant(first(raw, "dateStart", "date_start", "startDate", "date"), assume=TZ)
        season = start_event.year if start_event else None
        if not event_name or not season:
            return []
        chosen: dict[str, datetime] = {}
        for session in sessions if isinstance(sessions, list) else []:
            if not isinstance(session, dict):
                continue
            label = clean(first(session, "name", "type", "shortName", "short_name", "code", "sessionType"))
            kind = self.classify(label)
            if not kind:
                continue
            when = first(session, "startTime", "start_time", "start", "dateStart", "startDate", "scheduledStart")
            if when is None and session.get("date") and session.get("time"):
                when = f"{session['date']}T{session['time']}"
            start = parse_instant(when)
            if start is None:
                continue
            # Q1 y Q2 → una sola "Clasificación" a la hora de la primera.
            if kind not in chosen or start < chosen[kind]:
                chosen[kind] = start
        return [
            Event(
                id=f"motogp-{season}-{slug(country)}-{kind}",
                sport="motogp",
                competition="MotoGP",
                start=start,
                title=f"{event_name} · {self.LABELS[kind]}",
            )
            for kind, start in chosen.items()
        ]


ALL_PROVIDERS = (FootballData, LiveTennis, Jolpica, Blacktop)
