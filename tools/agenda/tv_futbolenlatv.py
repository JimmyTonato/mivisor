"""
Canales de TV desde futbolenlatv.es/deporte (enriquecimiento; NUNCA fuente de la agenda).

    APIs deportivas ──▶ eventos fiables ──▶ /deporte (UNA descarga por ejecución) ──▶ matching ──▶ channels[]

- Antes de descargar se comprueba robots.txt con el User-Agent "MiVisor/4". Si /deporte deja de
  estar permitido NO se descarga y el servicio queda "unavailable" (robots_disallowed).
- Se descarga una sola página (/deporte) por ejecución (~24 al día) y todo se analiza en local.
- Matching por deporte con señales combinadas (fecha, hora, participantes, competición, sesión).
  Si la confianza es baja NO se asignan canales: mejor "channels": [] que el canal de otro evento.
- Si la fuente falla, los eventos conservan los canales de la agenda anterior (estado "stale").
- Las imágenes junto a tenistas/luchadores suelen ser BANDERAS: nunca se usan como retratos.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from html.parser import HTMLParser
from typing import Iterable, Optional
from urllib import robotparser
from urllib.parse import urljoin

from agenda_core import TZ, Channel, Event, TvProvider, clean, event_from_json, slug
from matching import ascii_lower, person_similarity, session_kind, team_similarity
from providers import (
    PARSE_ERROR, ROBOTS_DISALLOWED, TV_USER_AGENT, Http, ProviderError,
)

BASE = "https://www.futbolenlatv.es"
PAGE = "/deporte"
# Por debajo de esto, una página descargada "correctamente" se considera formato cambiado.
MIN_ROWS = 5

TIME_RE = re.compile(r"^\s*(\d{1,2})[:.h](\d{2})\s*(?:h|hrs?)?\s*$", re.IGNORECASE)
TIME_ANY_RE = re.compile(r"\b(\d{1,2}):(\d{2})\b")
DATE_NUM_RE = re.compile(r"\b(\d{1,2})/(\d{1,2})/(\d{4})\b")
MONTHS = {m: i for i, m in enumerate(
    ["enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre",
     "noviembre", "diciembre"], start=1)}
MONTHS["setiembre"] = 9
DATE_TEXT_RE = re.compile(r"\b(\d{1,2})\s+de\s+(" + "|".join(MONTHS) + r")(?:\s+(?:de|del)\s+(\d{4}))?\b",
                          re.IGNORECASE)
# Diales: "M54 O110", "M70", y también solo números de Movistar: "63", "307", "8 y 50".
DIAL_RE = re.compile(r"^(?:[A-Za-z]{0,3}\s?\d{1,4}(?:\s*(?:y|,|/)\s*|\s+)?)+$", re.IGNORECASE)
NOTE_WORDS = ("ver en directo", "ppv", "replay", "diferido", "resumen", "en abierto", "gratis")
SKIP_CHANNEL_RE = re.compile(r"por confirmar|^\s*$", re.IGNORECASE)
SPLIT_EVENT_RE = re.compile(r"\s+(?:-|–|—|vs\.?|v\.?)\s+", re.IGNORECASE)
FLAG_RE = re.compile(r"bandera|flag|/paises?/|/countries/|/pais/", re.IGNORECASE)

SPORT_WORDS = [
    ("formula1", re.compile(r"f[oó]rmula\s*1\b|\bf1\b|formula-1", re.IGNORECASE)),
    ("motogp", re.compile(r"moto\s*gp|mundial de motos", re.IGNORECASE)),
    ("ufc", re.compile(r"\bufc\b|\bmma\b|artes marciales mixtas", re.IGNORECASE)),
    ("tenis", re.compile(r"\btenis\b|\batp\b|\bwta\b|tennis", re.IGNORECASE)),
    ("baloncesto", re.compile(r"baloncesto|\bnba\b|\bacb\b|euroliga|basket", re.IGNORECASE)),
    ("futbol", re.compile(r"f[uú]tbol|soccer|laliga|la liga|\bchampions\b|premier league|serie a\b|bundesliga|ligue 1",
                          re.IGNORECASE)),
]

# Icono de deporte de /deporte (alt de la imagen en la celda "detalles"): la señal más fiable.
# "Automovilismo" y "Motociclismo" se afinan con la competición (F1 Academy, F2, Moto2… NO son F1/MotoGP).
ICON_SPORTS = {
    "futbol": "futbol", "futbol sala": "futsal", "tenis": "tenis", "baloncesto": "baloncesto",
    "automovilismo": "motor", "motociclismo": "motor", "mma": "ufc", "ufc": "ufc",
    "artes marciales mixtas": "ufc", "artes marciales": "ufc",
}
# Otros deportes que aparecen en /deporte: se marcan como "otro" para que nunca se confundan con los nuestros.
OTHER_SPORTS = {
    "golf", "beisbol", "balonmano", "ciclismo", "rugby", "voleibol", "voley", "boxeo", "atletismo", "padel",
    "hockey", "hockey hielo", "hockey sobre hielo", "futbol americano", "dardos", "snooker", "billar", "esports",
    "waterpolo", "natacion", "esqui", "deportes de invierno", "lucha", "judo", "cricket", "badminton", "surf",
    "triatlon", "hipica", "remo", "vela", "squash", "tenis de mesa", "petanca", "pelota vasca", "baloncesto 3x3",
}
F1_COMPETITION = re.compile(r"^(?:mundial de\s+)?(?:f[oó]rmula\s*1|f1)(?:\s+(?:gp|gran premio).*)?$", re.IGNORECASE)
MOTOGP_COMPETITION = re.compile(r"^(?:mundial de\s+)?moto\s*gp$", re.IGNORECASE)


# ============================================================================ DOM mínimo (stdlib)

VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr"}


class Node:
    __slots__ = ("tag", "attrs", "children", "parent")

    def __init__(self, tag: str, attrs: dict, parent: Optional["Node"]):
        self.tag, self.attrs, self.children, self.parent = tag, attrs, [], parent

    def text(self) -> str:
        parts: list[str] = []

        def walk(n):
            for c in n.children:
                if isinstance(c, str):
                    parts.append(c)
                elif c.tag not in ("script", "style"):
                    walk(c)
                    if c.tag in ("li", "br", "p", "div"):
                        parts.append(" ")
        walk(self)
        return clean(" ".join(parts))

    def find_all(self, *tags: str, recursive: bool = True) -> list["Node"]:
        out = []
        for c in self.children:
            if isinstance(c, Node):
                if c.tag in tags:
                    out.append(c)
                if recursive:
                    out.extend(c.find_all(*tags))
        return out

    def find(self, *tags: str) -> Optional["Node"]:
        found = self.find_all(*tags)
        return found[0] if found else None

    def classes(self) -> str:
        return (self.attrs.get("class") or "").lower()

    def has_ancestor(self, tag: str) -> bool:
        p = self.parent
        while p is not None:
            if p.tag == tag:
                return True
            p = p.parent
        return False


class _Builder(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.root = Node("#root", {}, None)
        self.cur = self.root

    def handle_starttag(self, tag, attrs):
        node = Node(tag, {k: (v or "") for k, v in attrs}, self.cur)
        self.cur.children.append(node)
        if tag not in VOID:
            self.cur = node

    def handle_startendtag(self, tag, attrs):
        self.cur.children.append(Node(tag, {k: (v or "") for k, v in attrs}, self.cur))

    def handle_endtag(self, tag):
        n = self.cur
        while n is not None and n.tag != tag:
            n = n.parent
        if n is not None and n.parent is not None:   # etiqueta sin abrir: se ignora
            self.cur = n.parent

    def handle_data(self, data):
        if data.strip():
            self.cur.children.append(data)


def parse_dom(html: str) -> Node:
    b = _Builder()
    b.feed(html)
    b.close()
    return b.root


# ============================================================================ filas de /deporte

@dataclass
class TvRow:
    start: datetime
    sport: Optional[str]
    competition: Optional[str]
    home: Optional[str]
    away: Optional[str]
    title: str
    channels: list[Channel] = field(default_factory=list)


def detect_sport(*texts: str) -> Optional[str]:
    joined = " ".join(t for t in texts if t)
    for sport, regex in SPORT_WORDS:
        if regex.search(joined):
            return sport
    return None


def sport_from_icon(alt: str, competition: Optional[str]) -> Optional[str]:
    """alt del icono ("Automovilismo", "Golf"…) + competición → deporte. None si no hay icono."""
    key = ascii_lower(alt)
    if key in ICON_SPORTS:
        sport = ICON_SPORTS[key]
    elif key in OTHER_SPORTS:
        return "otro"
    else:
        return None                  # no es un icono de deporte (p. ej. el logo de una competición)
    comp = clean(competition or "")
    if sport == "motor":
        if F1_COMPETITION.match(comp):
            return "formula1"
        if MOTOGP_COMPETITION.match(comp):
            return "motogp"
    return sport


def parse_channel(raw: str) -> Optional[Channel]:
    """'M+ LALIGA (M54 O110)' → nombre + dial; 'DAZN (Ver en directo)' → nombre + nota."""
    text = clean(raw)
    if not text or SKIP_CHANNEL_RE.search(text):
        return None
    dial = None
    notes: list[str] = []
    for inner in re.findall(r"\(([^)]*)\)", text):
        inner_c = clean(inner)
        if DIAL_RE.match(inner_c):
            dial = inner_c
        elif inner_c:
            notes.append(inner_c)
    name = clean(re.sub(r"\([^)]*\)", "", text))
    lower = name.lower()
    for word in NOTE_WORDS:
        if lower.endswith(" " + word):
            notes.append(name[-len(word):])
            name = clean(name[: -len(word)])
            lower = name.lower()
    if not name:
        return None
    return Channel(name=name, dial=dial, note=", ".join(notes) or None)


def dedupe_channels(channels: Iterable[Channel]) -> list[Channel]:
    out: dict[str, Channel] = {}
    for ch in channels:
        key = slug(ch.name)
        if key not in out:
            out[key] = ch
        else:   # completa dial/nota/logo si la repetición los trae
            prev = out[key]
            prev.dial = prev.dial or ch.dial
            prev.note = prev.note or ch.note
            prev.logo = prev.logo or ch.logo
    return list(out.values())


def img_src(node: Node, base: str) -> Optional[str]:
    img = node if node.tag == "img" else node.find("img")
    if img is None:
        return None
    src = img.attrs.get("data-src") or img.attrs.get("data-lazy-src") or img.attrs.get("src")
    if not src or src.startswith("data:"):
        return None
    return urljoin(base, src)


def is_flag(img: Node) -> bool:
    """Las imágenes junto a tenistas/luchadores suelen ser banderas: nunca retratos ni logos de canal."""
    blob = " ".join([img.attrs.get(k, "") for k in ("src", "data-src", "class", "alt", "title")])
    return bool(FLAG_RE.search(blob))


def channel_cell_items(cell: Node, base: str) -> list[Channel]:
    items: list[Channel] = []
    lis = cell.find_all("li")
    sources = lis if lis else [x for x in cell.find_all("a", "span") if not x.find("a", "span")]
    for li in sources:
        text = li.text() or li.attrs.get("title", "")
        img = li.find("img") if li.tag != "img" else li
        if not text and img is not None:
            text = clean(img.attrs.get("alt") or img.attrs.get("title") or "")
        ch = parse_channel(text)
        if ch:
            if img is not None and not is_flag(img):
                ch.logo = img_src(img, base)
            items.append(ch)
    if not items:   # último recurso: separadores
        for part in re.split(r"\s*[,|·]\s*|\n", cell.text()):
            ch = parse_channel(part)
            if ch:
                items.append(ch)
    return dedupe_channels(items)


def cell_has(cell: Node, *words: str) -> bool:
    c = cell.classes()
    return any(w in c for w in words)


def parse_row(cells: list[Node], current: dict, base: str) -> Optional[TvRow]:
    texts = [c.text() for c in cells]
    time_idx = next((i for i, t in enumerate(texts) if TIME_RE.match(t)), None)
    if time_idx is None:
        time_idx = next((i for i, t in enumerate(texts) if TIME_ANY_RE.search(t) and len(t) <= 14), None)
    if time_idx is None or current.get("date") is None:
        return None
    m = TIME_ANY_RE.search(texts[time_idx]) or TIME_RE.match(texts[time_idx])
    hour, minute = int(m.group(1)), int(m.group(2))
    if hour > 23 or minute > 59:
        return None
    d: date = current["date"]
    start = datetime(d.year, d.month, d.day, hour, minute, tzinfo=TZ)

    rest = [(i, c) for i, c in enumerate(cells) if i != time_idx]
    chan_cell = next((c for i, c in rest if cell_has(c, "canal")), None)
    if chan_cell is None:
        with_list = [c for i, c in rest if c.find("li")]
        chan_cell = with_list[-1] if with_list else (rest[-1][1] if rest else None)
    if chan_cell is None:
        return None
    channels = channel_cell_items(chan_cell, base)

    others = [c for i, c in rest if c is not chan_cell and c.text()]
    sport_cell = next((c for c in others if cell_has(c, "deporte", "sport")), None)
    comp_cell = next((c for c in others if cell_has(c, "detalle", "competici", "competition", "liga", "torneo")), None)
    home_cell = next((c for c in others if cell_has(c, "local", "home")), None)
    away_cell = next((c for c in others if cell_has(c, "visitante", "away")), None)
    event_cell = next((c for c in others if cell_has(c, "evento", "partido", "event")), None)
    free = [c for c in others if c not in (sport_cell, comp_cell, home_cell, away_cell, event_cell)]

    competition = comp_cell.text() if comp_cell else None

    # Deporte: icono de la competición (alt), celda propia, iconos de la fila o cabecera de sección.
    sport = None
    for icon_cell in (sport_cell, comp_cell):
        for img in (icon_cell.find_all("img") if icon_cell is not None else []):
            sport = sport_from_icon(img.attrs.get("alt", "") or img.attrs.get("title", ""), competition)
            if sport:
                break
        if sport:
            break
    if sport is None:
        icon_alts = " ".join(i.attrs.get("alt", "") + " " + i.attrs.get("title", "") + " " + i.classes()
                             for c in cells for i in c.find_all("img", "i") if c not in (home_cell, away_cell))
        sport = detect_sport(sport_cell.text() if sport_cell else "", icon_alts) or current.get("sport")
    home = home_cell.text() if home_cell else None
    away = away_cell.text() if away_cell else None
    title = None
    if not home and not away:
        source = event_cell or (free[-1] if free else None)
        if competition is None and len(free) >= 2 and source is free[-1]:
            competition = free[0].text()
        if source is None:
            return None
        title = source.text()
        parts = SPLIT_EVENT_RE.split(title, maxsplit=1)
        if len(parts) == 2 and all(parts):
            home, away = clean(parts[0]), clean(parts[1])
    competition = competition or current.get("competition")
    if not sport:
        sport = detect_sport(competition or "", title or "")
    title = title or " - ".join(x for x in (home, away) if x)
    if not title:
        return None
    return TvRow(start=start, sport=sport, competition=competition, home=home or None, away=away or None,
                 title=title, channels=channels)


def parse_date(text: str, today: date) -> Optional[date]:
    m = DATE_NUM_RE.search(text)
    if m:
        try:
            return date(int(m.group(3)), int(m.group(2)), int(m.group(1)))
        except ValueError:
            return None
    m = DATE_TEXT_RE.search(text)
    if m:
        month = MONTHS[m.group(2).lower()]
        year = int(m.group(3)) if m.group(3) else today.year
        if not m.group(3) and month < today.month - 6:   # "3 de enero" visto en diciembre
            year += 1
        try:
            return date(year, month, int(m.group(1)))
        except ValueError:
            return None
    low = text.lower()
    if re.search(r"\bhoy\b", low):
        return today
    if re.search(r"\bmañana\b", low) and not TIME_ANY_RE.search(low):
        return today + timedelta(days=1)
    return None


def parse_listing(html: str, now: datetime, base: str = BASE) -> list[TvRow]:
    """Todas las filas de la página, con su fecha (cabeceras) y deporte (cabeceras/iconos)."""
    root = parse_dom(html)
    today = now.astimezone(TZ).date()
    current: dict = {"date": today, "sport": None, "competition": None}
    rows: list[TvRow] = []

    def walk(node: Node):
        for child in node.children:
            if not isinstance(child, Node):
                continue
            tag = child.tag
            if tag == "tr":
                cells = child.find_all("td", "th", recursive=False)
                row = parse_row(cells, current, base) if len(cells) >= 2 else None
                if row:
                    rows.append(row)
                else:
                    header(child.text())
                continue   # no se baja dentro de una fila
            if tag in ("h1", "h2", "h3", "h4", "h5", "caption") or any(
                    k in child.classes() for k in ("cabecera", "fecha", "dia", "titulo")):
                if not child.has_ancestor("tr"):
                    header(child.text())
            walk(child)

    def header(text: str):
        if not text or len(text) > 120:
            return
        d = parse_date(text, today)
        if d:
            current["date"] = d
            current["competition"] = None
            return
        sport = detect_sport(text)
        if sport and len(text) < 40 and not TIME_ANY_RE.search(text):
            current["sport"] = sport

    walk(root)
    return rows


# ============================================================================ matching

def _kind_from_id(event_id: str) -> Optional[str]:
    tail = event_id.rsplit("-", 1)[-1]
    return tail if tail in ("qualifying", "sprint", "race") else None


def score_row(ev: Event, row: TvRow) -> float:
    """0 = no es este evento. >0 = confianza (cuanto más, mejor)."""
    if row.sport and row.sport != ev.sport and not (ev.sport == "ufc" and row.sport == "otro"):
        return 0.0
    delta = abs((row.start - ev.start).total_seconds()) / 60.0   # minutos
    sport = ev.sport

    if sport == "futbol":
        if delta > 30 or not row.home or not row.away:
            return 0.0
        h = team_similarity(ev.ref.get("homeNames") or [ev.home or ""], row.home)
        a = team_similarity(ev.ref.get("awayNames") or [ev.away or ""], row.away)
        if max(h, a) >= 0.9 and min(h, a) >= 0.5:
            return h + a + (0.2 if delta <= 5 else 0.0)
        return 0.0

    if sport == "tenis":
        if delta > 240 or row.start.date() != ev.start.astimezone(TZ).date():
            return 0.0
        names = [x for x in (row.home, row.away) if x]
        if len(names) < 2 or not ev.home or not ev.away:
            return 0.0
        straight = min(person_similarity(ev.home, names[0]), person_similarity(ev.away, names[1]))
        crossed = min(person_similarity(ev.home, names[1]), person_similarity(ev.away, names[0]))
        best = max(straight, crossed)
        return best * 2 if best >= 0.9 else 0.0

    if sport in ("formula1", "motogp"):
        if delta > 45:
            return 0.0
        kind = _kind_from_id(ev.id)
        row_kind = session_kind(f"{row.title} {row.competition or ''}")
        if row.sport is None and not (detect_sport(row.title, row.competition or "") == sport):
            return 0.0
        if kind is None or row_kind != kind:
            return 0.0
        return 2.0 - delta / 100.0

    if sport == "ufc":
        window = 36 * 60 if ev.timeTbd else 12 * 60
        if delta > window:
            return 0.0
        text = ascii_lower(f"{row.title} {row.competition or ''}")
        number = re.search(r"ufc\s*(\d{2,4})", ascii_lower(ev.competition or ev.title))
        by_number = bool(number and re.search(rf"ufc\s*{number.group(1)}\b", text))
        fighters = 0
        for name in (ev.home, ev.away):
            surname = ascii_lower(name or "").split()[-1:] or [""]
            if surname[0] and len(surname[0]) >= 3 and re.search(rf"\b{surname[0]}\b", text):
                fighters += 1
        if by_number or fighters >= 2 or (fighters == 1 and "ufc" in text):
            return 1.0 + fighters * 0.5 + (1.0 if by_number else 0.0)
        return 0.0

    # Otros deportes: participantes como fútbol, ventana corta.
    if delta > 30 or not row.home or not row.away:
        return 0.0
    h = team_similarity([ev.home or ""], row.home)
    a = team_similarity([ev.away or ""], row.away)
    return h + a if min(h, a) >= 0.9 else 0.0


def match_channels(events: list[Event], rows: list[TvRow]) -> dict[str, list[Channel]]:
    """Evento → canales del mejor match; nada si es dudoso (empate entre filas distintas)."""
    by_day: dict = {}
    for row in rows:
        by_day.setdefault(row.start.date(), []).append(row)
    out: dict[str, list[Channel]] = {}
    for ev in events:
        day = ev.start.astimezone(TZ).date()
        candidates = by_day.get(day, []) + by_day.get(day - timedelta(days=1), []) + by_day.get(day + timedelta(days=1), [])
        scored = sorted(((score_row(ev, r), r) for r in candidates), key=lambda x: -x[0])
        scored = [(s, r) for s, r in scored if s > 0]
        if not scored:
            continue
        best_score, best = scored[0]
        rivals = [r for s, r in scored[1:] if s >= best_score - 0.05 and slug(r.title) != slug(best.title)]
        if rivals:
            continue                                   # ambiguo: mejor sin canales
        # Filas repetidas del mismo evento (p. ej. en dos secciones): se unen sus canales.
        same = [r for s, r in scored if slug(r.title) == slug(best.title) and abs((r.start - best.start).total_seconds()) <= 300]
        channels = dedupe_channels(ch for r in same for ch in r.channels)
        if channels:
            out[ev.id] = channels
    return out


# ============================================================================ enriquecedor

class FutbolEnLaTv(TvProvider):
    service = "futbolenlatv"
    label = "TV /deporte"

    def __init__(self, http: Http, now: datetime, previous: Optional[dict] = None, base: str = BASE):
        self.http = http
        self.now = now
        self.base = base
        self.previous = previous
        self.log: list[str] = []
        self.status = "ok"
        self.error: Optional[str] = None
        self.rows = 0
        self.matched = 0

    def enrich(self, events: list[Event]) -> list[Event]:
        try:
            html = self._download()
            rows = parse_listing(html, self.now, self.base)
            self.rows = len(rows)
            if len(rows) < MIN_ROWS:
                raise ProviderError(PARSE_ERROR, f"only {len(rows)} rows parsed")
            found = match_channels(events, rows)
            for ev in events:
                if ev.id in found:
                    ev.channels = found[ev.id]           # /deporte manda sobre cualquier otra fuente de TV
            self.matched = len(found)
            self.log.append(f"{self.label}: OK - {self.rows} candidate rows - {self.matched} matches")
        except Exception as exc:  # noqa: BLE001 — la fuente de TV nunca rompe la agenda
            code = exc.code if isinstance(exc, ProviderError) else PARSE_ERROR
            detail = exc.detail if isinstance(exc, ProviderError) else type(exc).__name__
            reused = self._reuse_previous(events)
            self.status = "stale" if reused else "unavailable"
            self.error = code
            self.log.append(f"{self.label}: {self.status.upper()} - {code} ({detail}) - "
                            f"{reused} events keep previous channels")
        return events

    def _download(self) -> str:
        headers = {"User-Agent": TV_USER_AGENT}
        try:
            robots_txt = self.http.get_text(f"{self.base}/robots.txt", headers=headers, label="tv-robots")
        except ProviderError as exc:
            if exc.status and 400 <= exc.status < 500:
                robots_txt = ""          # sin robots.txt (4xx): todo permitido
            else:
                raise ProviderError(exc.code, f"robots.txt {exc.detail}") from None
        if not robots_allows(robots_txt, f"{self.base}{PAGE}"):
            raise ProviderError(ROBOTS_DISALLOWED, f"robots.txt disallows {PAGE}")
        return self.http.get_text(f"{self.base}{PAGE}", headers=headers, label="tv-deporte")

    def _reuse_previous(self, events: list[Event]) -> int:
        prev: dict[str, list[Channel]] = {}
        for raw in (self.previous or {}).get("events") or []:
            if isinstance(raw, dict):
                ev = event_from_json(raw)
                if ev and ev.channels:
                    prev[ev.id] = ev.channels
        n = 0
        for ev in events:
            if not ev.channels and ev.id in prev:
                ev.channels = prev[ev.id]
                n += 1
        return n


def robots_allows(robots_txt: str, url: str, agent: str = TV_USER_AGENT) -> bool:
    parser = robotparser.RobotFileParser()
    parser.parse(robots_txt.splitlines())
    return parser.can_fetch(agent, url)
