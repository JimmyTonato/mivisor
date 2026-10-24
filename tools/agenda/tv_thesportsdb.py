"""
Enriquecimiento de TV con TheSportsDB: rellena "channels" de los partidos de fútbol con los
broadcasters que devuelve la fuente, DE CUALQUIER PAÍS (no se filtra por país).

    Event (Football-Data) ──▶ eventsround.php (una petición por liga y jornada)
                          ──▶ cruce por hora + equipos → idEvent de TheSportsDB
                          ──▶ lookuptv.php?id=idEvent → [{strChannel, strCountry}, …]
                          ──▶ Channel(name=strChannel, note=strCountry)

- `name` lleva SOLO el nombre del canal (ChannelMatcher empareja por nombre); el país va en `note`.
- Duplicados: mismo canal + mismo país se cuenta una vez. Mismo canal en otro país es otra entrada.
- Plan gratuito: lookuptv devuelve como máximo 2 broadcasters por partido, y hay 30 peticiones
  por minuto. Se acepta: se muestran los que lleguen (2, 1 o ninguno → "channels": []).
- Presupuesto por ejecución: las peticiones se espacian y se limitan; los partidos que no se
  consultan en esta ejecución conservan los canales obtenidos en ejecuciones anteriores.
- Un fallo de TheSportsDB NUNCA afecta a los eventos: se publican igual, con los canales que hubiera.
- Clave: THESPORTSDB_API_KEY (opcional). Sin ella se usa la clave pública gratuita "123".
  La clave va en la ruta de la URL, así que las URLs nunca se registran.
"""
from __future__ import annotations

import re
import time
import unicodedata
from datetime import datetime, timedelta, timezone
from typing import Any, Callable, Iterable, Mapping, Optional

from agenda_core import Channel, Event, TvProvider, clean, event_from_json, parse_instant, slug
from providers import RATE_LIMITED, Http, ProviderError

BASE = "https://www.thesportsdb.com/api/v1/json"
KEY_ENV = "THESPORTSDB_API_KEY"
PUBLIC_KEY = "123"   # clave gratuita pública documentada por TheSportsDB

# Football-Data (código) → TheSportsDB (idLeague).
LEAGUES = {
    "PL": "4328",   # English Premier League
    "PD": "4335",   # Spanish La Liga
    "SA": "4332",   # Italian Serie A
    "BL1": "4331",  # German Bundesliga
    "FL1": "4334",  # French Ligue 1
    "CL": "4480",   # UEFA Champions League
}

# Palabras que no ayudan a identificar a un equipo.
STOP = {
    "fc", "cf", "afc", "sc", "ac", "as", "ss", "ssc", "us", "rc", "rcd", "cd", "ud", "sd", "ca", "sv",
    "vfb", "vfl", "tsg", "fsv", "bv", "bsc", "osc", "ogc", "stade", "club", "de", "del", "la", "le",
    "of", "and", "the", "calcio", "football", "futbol", "balompie",
}
# Sinónimos por palabra (nombres cortos de Football-Data y variantes habituales).
SYNONYMS = {
    "barca": "barcelona", "atleti": "atletico", "internazionale": "inter", "milano": "milan",
    "munchen": "munich", "muenchen": "munich", "koln": "cologne", "koeln": "cologne",
    "spurs": "tottenham", "wolves": "wolverhampton", "man": "manchester", "utd": "united",
    "saint": "st", "olympique": "",
}
# Nombres cortos de Football-Data que necesitan el nombre completo.
ALIASES = {
    "man city": "manchester city", "man united": "manchester united", "nottingham": "nottingham forest",
    "athletic": "athletic bilbao", "betis": "real betis", "psg": "paris saint germain",
    "leverkusen": "bayer leverkusen", "bayern": "bayern munich",
    "dortmund": "borussia dortmund", "m gladbach": "borussia monchengladbach",
}


def norm_tokens(name: str) -> frozenset[str]:
    text = unicodedata.normalize("NFKD", str(name)).encode("ascii", "ignore").decode().lower()
    text = re.sub(r"[^a-z0-9]+", " ", text).strip()
    text = ALIASES.get(text, text)
    out = set()
    for token in text.split():
        token = SYNONYMS.get(token, token)
        if token and token not in STOP and not token.isdigit():
            out.add(token)
    return frozenset(out)


def name_similarity(ours: Iterable[str], theirs: Optional[str]) -> float:
    """0..1 entre nuestras variantes de nombre y el nombre de TheSportsDB."""
    if not theirs:
        return 0.0
    b = norm_tokens(theirs)
    best = 0.0
    for name in ours:
        a = norm_tokens(name)
        if not a or not b:
            continue
        if a == b:
            return 1.0
        if a <= b or b <= a:
            best = max(best, 0.9)
        else:
            best = max(best, len(a & b) / len(a | b))
    return best


class TheSportsDbTv(TvProvider):
    label = "TheSportsDB"
    LOOKAHEAD = timedelta(hours=96)   # se consultan los partidos de los próximos 4 días
    MAX_REQUESTS = 80                 # por ejecución (≈ 3 min al ritmo permitido)
    MIN_INTERVAL = 2.1                # segundos entre peticiones: < 30 por minuto
    MAX_KICKOFF_GAP = timedelta(hours=3)
    MAX_CONSECUTIVE_ERRORS = 3

    def __init__(self, http: Http, env: Mapping[str, str], now: datetime, previous: Optional[dict] = None,
                 sleep: Callable[[float], None] = time.sleep, clock: Callable[[], float] = time.monotonic):
        self.http = http
        self.key = (env.get(KEY_ENV) or "").strip() or PUBLIC_KEY
        self.now = now
        self.sleep = sleep
        self.clock = clock
        self.requests = 0
        self.last_request: Optional[float] = None
        self.errors_in_row = 0
        self.stopped: Optional[str] = None
        self.log: list[str] = []
        self.previous_channels = self._previous_channels(previous)

    # ------------------------------------------------------------------ API pública

    def enrich(self, events: list[Event]) -> list[Event]:
        """Nunca lanza excepciones: ante cualquier fallo los eventos se devuelven tal cual."""
        try:
            self._enrich(events)
        except Exception as exc:  # noqa: BLE001
            self.log.append(f"{self.label}: FAILED - {type(exc).__name__} - events published without new channels")
        return events

    # ------------------------------------------------------------------ interno

    def _enrich(self, events: list[Event]) -> None:
        football = [e for e in events if e.sport == "futbol"]
        # 1) Lo ya conocido de ejecuciones anteriores (datos reales de la fuente, no inventados).
        reused = 0
        for ev in football:
            cached = self.previous_channels.get(ev.id)
            if cached and not ev.channels:
                ev.channels = list(cached)
                reused += 1

        # 2) Partidos a consultar ahora: los más próximos primero.
        horizon = self.now + self.LOOKAHEAD
        due = sorted(
            (e for e in football if e.start <= horizon and e.start >= self.now - timedelta(hours=3)),
            key=lambda e: e.start,
        )
        ids = self._resolve_ids(due)

        looked = with_channels = total = 0
        for ev in due:
            tsdb_id = ids.get(ev.id)
            if not tsdb_id or self.stopped:
                continue
            channels = self._lookup_tv(tsdb_id)
            if channels is None:
                continue
            looked += 1
            ev.channels = merge_channels([], channels)
            if ev.channels:
                with_channels += 1
                total += len(ev.channels)

        if self.stopped:
            self.log.append(
                f"{self.label}: PARTIAL - {self.stopped} - {looked} events updated, "
                f"{reused} kept from the previous agenda"
            )
        else:
            self.log.append(
                f"{self.label}: OK - {looked} events checked, {with_channels} with channels "
                f"({total} channels), {len(due) - len(ids)} not matched, {reused} reused"
            )

    def _resolve_ids(self, due: list[Event]) -> dict[str, str]:
        """Nuestro id → idEvent de TheSportsDB, con una petición por liga y jornada."""
        rounds: dict[tuple[str, str, str], list[Event]] = {}
        for ev in due:
            league = LEAGUES.get(ev.ref.get("competitionCode"))
            matchday, season = ev.ref.get("matchday"), ev.ref.get("season")
            if league and matchday and season:
                rounds.setdefault((league, str(matchday), season), []).append(ev)

        ids: dict[str, str] = {}
        for (league, matchday, season), group in rounds.items():
            if self.stopped:
                break
            data = self._get("eventsround.php", {"id": league, "r": matchday, "s": season},
                             f"tv-round-{league}-{matchday}")
            candidates = records(data, "events")
            if not candidates:
                # Respaldo: partidos de esa liga por día (útil si la jornada no coincide).
                for day in sorted({e.start.astimezone(timezone.utc).date().isoformat() for e in group}):
                    candidates += records(self._get("eventsday.php", {"d": day, "l": league},
                                                    f"tv-day-{league}-{day}"), "events")
            for ev in group:
                found = best_match(ev, candidates, self.MAX_KICKOFF_GAP)
                if found:
                    ids[ev.id] = found
        return ids

    def _lookup_tv(self, tsdb_id: str) -> Optional[list[Channel]]:
        data = self._get("lookuptv.php", {"id": tsdb_id}, f"tv-lookup-{tsdb_id}")
        if data is None:
            return None
        return broadcasters(records(data, "tvevent"), tsdb_id)

    def _get(self, endpoint: str, params: dict, label: str) -> Any:
        if self.stopped:
            return None
        if self.requests >= self.MAX_REQUESTS:
            self.stopped = "request budget reached"
            return None
        if self.last_request is not None:
            wait = self.MIN_INTERVAL - (self.clock() - self.last_request)
            if wait > 0:
                self.sleep(wait)
        self.requests += 1
        try:
            data = self.http.get_json(f"{BASE}/{self.key}/{endpoint}", params=params, label=label)
            self.errors_in_row = 0
            return data
        except ProviderError as exc:
            self.errors_in_row += 1
            if exc.code == RATE_LIMITED:
                self.stopped = RATE_LIMITED
            elif self.errors_in_row >= self.MAX_CONSECUTIVE_ERRORS:
                self.stopped = exc.code
            return None
        finally:
            self.last_request = self.clock()

    @staticmethod
    def _previous_channels(previous: Optional[dict]) -> dict[str, list[Channel]]:
        out: dict[str, list[Channel]] = {}
        for raw in (previous or {}).get("events") or []:
            if isinstance(raw, dict) and raw.get("sport") == "futbol":
                ev = event_from_json(raw)
                if ev and ev.channels:
                    out[ev.id] = ev.channels
        return out


# ---------------------------------------------------------------------- funciones puras

def records(data: Any, key: str) -> list[dict]:
    """TheSportsDB responde {key: [..]}, {key: null}, cuerpo vacío o {key: "texto de error"}."""
    if not isinstance(data, dict):
        return []
    value = data.get(key)
    return [r for r in value if isinstance(r, dict)] if isinstance(value, list) else []


def best_match(ev: Event, candidates: list[dict], max_gap: timedelta) -> Optional[str]:
    """idEvent del candidato con la misma hora (± max_gap) y al menos un equipo inequívoco."""
    best_id, best_score = None, 0.0
    for c in candidates:
        start = parse_instant(c.get("strTimestamp") or c.get("strTimeStamp"))
        if start is None or abs(start - ev.start) > max_gap or not c.get("idEvent"):
            continue
        home = name_similarity(ev.ref.get("homeNames") or [ev.home or ""], c.get("strHomeTeam"))
        away = name_similarity(ev.ref.get("awayNames") or [ev.away or ""], c.get("strAwayTeam"))
        # Un equipo solo juega un partido por jornada: con uno inequívoco y la hora basta.
        if max(home, away) < 0.9 and home + away < 1.2:
            continue
        score = home + away
        if score > best_score:
            best_id, best_score = str(c["idEvent"]), score
    return best_id


def broadcasters(rows: list[dict], tsdb_id: Optional[str] = None) -> list[Channel]:
    """Todos los broadcasters válidos, de cualquier país, sin duplicados."""
    out: list[Channel] = []
    for row in rows:
        if tsdb_id and row.get("idEvent") not in (None, "", tsdb_id):
            continue
        name = clean(row.get("strChannel"))
        if not name:
            continue
        country = clean(row.get("strCountry")) or None
        out.append(Channel(name=name, note=country))
    return merge_channels([], out)


def merge_channels(existing: list[Channel], new: Iterable[Channel]) -> list[Channel]:
    """Une listas sin duplicados: mismo canal + mismo país = duplicado; otro país = otra entrada."""
    seen = set()
    out = []
    for ch in [*existing, *new]:
        key = (slug(ch.name), slug(ch.note or ""))
        if key in seen:
            continue
        seen.add(key)
        out.append(ch)
    return out
