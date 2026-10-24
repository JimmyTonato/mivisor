"""
TheSportsDB: identifica cada partido de fútbol en TheSportsDB y publica su id como "tvId".

    Event (Football-Data) ──▶ eventsround.php (UNA petición por liga y jornada)
                          ──▶ cruce por hora + equipos ──▶ tvId en agenda.json

Los CANALES los pide la app al abrir un partido (lookuptv.php?id=tvId), desde la conexión de cada
usuario, y los guarda. Así GitHub Actions hace solo unas pocas peticiones por hora y no choca con el
límite de la clave gratuita (compartida por todo el mundo y que corta tras ~10 peticiones seguidas).

- Los ids ya conocidos se reutilizan de la agenda anterior: solo se piden las jornadas nuevas.
- Ante un 429 se espera un minuto y se reintenta una vez; si vuelve a fallar, se deja para la
  siguiente ejecución (los partidos aparecen igual, solo sin tvId todavía).
- Un fallo de TheSportsDB NUNCA afecta a los eventos ni a los estados ok/stale/unavailable.
- Clave: THESPORTSDB_API_KEY (opcional). Sin ella se usa la clave pública gratuita "123".
  La clave va en la ruta de la URL, así que las URLs nunca se registran.
"""
from __future__ import annotations

import re
import time
import unicodedata
from datetime import datetime, timedelta, timezone
from typing import Any, Callable, Iterable, Mapping, Optional

from agenda_core import Event, TvProvider, parse_instant
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
    MAX_REQUESTS = 12                 # por ejecución: una por liga y jornada pendiente
    MIN_INTERVAL = 2.5                # segundos entre peticiones
    RATE_LIMIT_WAIT = 61.0            # TheSportsDB: tras un 429, esperar un minuto
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
        self.previous_ids = self._previous_ids(previous)

    # ------------------------------------------------------------------ API pública

    def enrich(self, events: list[Event]) -> list[Event]:
        """Nunca lanza excepciones: ante cualquier fallo los eventos se devuelven tal cual."""
        try:
            self._enrich(events)
        except Exception as exc:  # noqa: BLE001
            self.log.append(f"{self.label}: FAILED - {type(exc).__name__} - events published without tvId")
        return events

    # ------------------------------------------------------------------ interno

    def _enrich(self, events: list[Event]) -> None:
        football = [e for e in events if e.sport == "futbol"]
        reused = 0
        for ev in football:
            if not ev.tvId and ev.id in self.previous_ids:
                ev.tvId = self.previous_ids[ev.id]
                reused += 1

        # Jornadas con partidos aún sin identificar, las más próximas primero.
        rounds: dict[tuple[str, str, str], list[Event]] = {}
        for ev in sorted(football, key=lambda e: e.start):
            if ev.tvId:
                continue
            league = LEAGUES.get(ev.ref.get("competitionCode"))
            matchday, season = ev.ref.get("matchday"), ev.ref.get("season")
            if league and matchday and season:
                rounds.setdefault((league, str(matchday), season), []).append(ev)

        found = 0
        for (league, matchday, season), group in rounds.items():
            if self.stopped:
                break
            data = self._get("eventsround.php", {"id": league, "r": matchday, "s": season},
                             f"tv-round-{league}-{matchday}")
            candidates = records(data, "events")
            if not candidates and not self.stopped:
                # Respaldo: partidos de esa liga por día (útil si la jornada no coincide).
                for day in sorted({e.start.astimezone(timezone.utc).date().isoformat() for e in group}):
                    candidates += records(self._get("eventsday.php", {"d": day, "l": league},
                                                    f"tv-day-{league}-{day}"), "events")
            for ev in group:
                tv_id = best_match(ev, candidates, self.MAX_KICKOFF_GAP)
                if tv_id:
                    ev.tvId = tv_id
                    found += 1

        identified = sum(1 for e in football if e.tvId)
        status = f"PARTIAL - {self.stopped}" if self.stopped else "OK"
        self.log.append(
            f"{self.label}: {status} - {identified}/{len(football)} football events identified "
            f"({found} new, {reused} from previous agenda, {self.requests} requests)"
        )

    def _get(self, endpoint: str, params: dict, label: str) -> Any:
        if self.stopped:
            return None
        if self.requests >= self.MAX_REQUESTS:
            self.stopped = "request budget reached"
            return None
        for attempt in (1, 2):
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
                if exc.code == RATE_LIMITED and attempt == 1:
                    self.sleep(self.RATE_LIMIT_WAIT)   # documentado: esperar un minuto y reintentar
                    continue
                if exc.code == RATE_LIMITED or self.errors_in_row >= self.MAX_CONSECUTIVE_ERRORS:
                    self.stopped = exc.code
                return None
            finally:
                self.last_request = self.clock()
        return None

    @staticmethod
    def _previous_ids(previous: Optional[dict]) -> dict[str, str]:
        out: dict[str, str] = {}
        for raw in (previous or {}).get("events") or []:
            if isinstance(raw, dict) and raw.get("sport") == "futbol" and raw.get("id") and raw.get("tvId"):
                out[str(raw["id"])] = str(raw["tvId"])
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
