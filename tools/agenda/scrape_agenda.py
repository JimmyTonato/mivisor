#!/usr/bin/env python3
"""
Genera agenda.json (partidos + canales de TV) para la sección "Agenda deportiva" de Mi Visor.

Fuente: futbolenlatv.es. Se ejecuta en GitHub Actions cada 30 minutos; la app solo descarga
el JSON resultante (unos pocos KB), así la TV no tiene que descargar ni analizar HTML.

Estrategias, en orden:
  1. Tablas HTML: filas con hora (HH:MM), equipos y lista de canales; cabeceras con fecha dd/mm/aaaa.
  2. JSON-LD (schema.org SportsEvent) como respaldo. El sitio publica startDate en UTC sin indicarlo.

Buenas prácticas: respeta robots.txt, hace una sola petición por página y se identifica con su
User-Agent. Uso personal; los datos son del sitio de origen y la app lo indica.

Uso:
  python scrape_agenda.py --out agenda.json [--debug-dir debug/] [--html fichero.html --sport futbol]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import unicodedata
from dataclasses import asdict, dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Iterable, Optional
from urllib import robotparser
from urllib.parse import urljoin
from zoneinfo import ZoneInfo

from bs4 import BeautifulSoup, Tag

TZ = ZoneInfo("Europe/Madrid")
BASE = "https://www.futbolenlatv.es"

# Páginas a consultar. Si alguna no existe o robots.txt la prohíbe, se omite sin romper el resto.
SOURCES = [
    ("futbol", f"{BASE}/deporte/futbol"),
    ("baloncesto", f"{BASE}/deporte/baloncesto"),
    ("tenis", f"{BASE}/deporte/tenis"),
    ("motor", f"{BASE}/deporte/motor"),
]

USER_AGENT = "MiVisorAgenda/1.0 (+https://github.com/; uso personal, 1 peticion/30 min)"
SCHEMA_VERSION = 1

TIME_RE = re.compile(r"^\s*(\d{1,2})[:.h](\d{2})\s*$")
TIME_ANY_RE = re.compile(r"\b(\d{1,2}):(\d{2})\b")
DATE_RE = re.compile(r"\b(\d{1,2})/(\d{1,2})/(\d{4})\b")
DIAL_RE = re.compile(r"^(?:[A-Za-z]{1,3}\s?\d{1,4}\s*)+$")
NOTE_WORDS = ("ver en directo", "ppv", "replay", "diferido", "resumen", "en abierto", "gratis")
SKIP_CHANNEL_RE = re.compile(r"\bbar(es)?\b|por confirmar|^\s*$", re.IGNORECASE)


@dataclass
class Channel:
    name: str
    dial: Optional[str] = None
    note: Optional[str] = None
    logo: Optional[str] = None


@dataclass
class Event:
    id: str
    sport: str
    competition: Optional[str]
    start: str                      # ISO 8601 con desfase (+01:00 / +02:00)
    home: Optional[str]
    away: Optional[str]
    title: str
    channels: list[Channel] = field(default_factory=list)
    homeLogo: Optional[str] = None
    awayLogo: Optional[str] = None
    competitionLogo: Optional[str] = None


# --------------------------------------------------------------------------------------------
# Utilidades de texto
# --------------------------------------------------------------------------------------------

def clean(text: Optional[str]) -> str:
    return re.sub(r"\s+", " ", (text or "").replace("\xa0", " ")).strip()


def slug(text: str) -> str:
    norm = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode().lower()
    return re.sub(r"[^a-z0-9]+", "-", norm).strip("-")


def parse_channel(raw: str) -> Optional[Channel]:
    """'DAZN LaLiga (M55 O113)' → nombre + dial; 'DAZN (Ver en directo)' → nombre + nota."""
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


def event_id(sport: str, start: datetime, home: Optional[str], away: Optional[str], title: str) -> str:
    key = f"{sport}|{start.strftime('%Y-%m-%d')}|{slug(home or '')}|{slug(away or '')}|{slug(title)}"
    return hashlib.sha1(key.encode()).hexdigest()[:12]


def img_url(cell: Tag, base: str) -> Optional[str]:
    img = cell.find("img")
    if not img:
        return None
    src = img.get("data-src") or img.get("data-lazy-src") or img.get("src")
    if not src or src.startswith("data:"):
        return None
    return urljoin(base, src)


def classes(tag: Tag) -> str:
    return " ".join(tag.get("class") or []).lower()


# --------------------------------------------------------------------------------------------
# Estrategia 1: tablas HTML
# --------------------------------------------------------------------------------------------

def channel_logos(cell: Tag, base: str) -> dict[str, str]:
    """Texto del canal → URL de su logo (si la lista de canales incluye imágenes)."""
    logos = {}
    for li in cell.find_all("li"):
        text = clean(li.get_text(" "))
        url = img_url(li, base)
        if not text and url:  # canal representado solo con imagen: el alt hace de nombre
            img = li.find("img")
            text = clean(img.get("alt") or img.get("title") or "")
        if text and url:
            logos[text] = url
    return logos


def channel_items(cell: Tag) -> list[str]:
    items = []
    for li in cell.find_all("li"):
        text = clean(li.get_text(" "))
        if not text and li.find("img"):
            img = li.find("img")
            text = clean(img.get("alt") or img.get("title") or "")
        if text:
            items.append(text)
    if items:
        return items
    links = [clean(a.get_text(" ")) for a in cell.find_all(["a", "span"]) if not a.find(["a", "span"])]
    if len(links) > 1:
        return [i for i in links if i]
    # Último recurso: separadores comunes.
    return [p for p in re.split(r"\s*[,|/·]\s*|\n", cell.get_text("\n")) if clean(p)]


def parse_row(cells: list[Tag], base: str) -> Optional[dict]:
    texts = [clean(c.get_text(" ")) for c in cells]
    time_idx = next((i for i, t in enumerate(texts) if TIME_RE.match(t)), None)
    if time_idx is None:
        # A veces la hora va acompañada de un icono o del estado: buscar HH:MM dentro de la celda.
        time_idx = next((i for i, t in enumerate(texts) if TIME_ANY_RE.search(t) and len(t) <= 12), None)
    if time_idx is None:
        return None
    m = TIME_ANY_RE.search(texts[time_idx])
    hour, minute = int(m.group(1)), int(m.group(2))

    rest = [(i, c) for i, c in enumerate(cells) if i != time_idx and texts[i]]
    if not rest:
        return None

    # Celda de canales: por clase, o la última celda con lista.
    chan_idx = next((i for i, c in rest if "canal" in classes(c)), None)
    if chan_idx is None:
        with_list = [i for i, c in rest if c.find("li")]
        chan_idx = with_list[-1] if with_list else rest[-1][0]

    home_cell = next((c for i, c in rest if any(k in classes(c) for k in ("local", "home"))), None)
    away_cell = next((c for i, c in rest if any(k in classes(c) for k in ("visitante", "away"))), None)
    comp_cell = next((c for i, c in rest if any(k in classes(c) for k in ("detalle", "competicion", "competition", "liga"))), None)

    others = [c for i, c in rest if i != chan_idx and c is not comp_cell and c is not home_cell and c is not away_cell]
    if home_cell is None and away_cell is None:
        # Sin clases: si hay celda de competición separada, los equipos son las dos siguientes.
        if comp_cell is None and len(others) >= 3:
            comp_cell, others = others[0], others[1:]
        if len(others) >= 2:
            home_cell, away_cell = others[0], others[1]
        elif len(others) == 1:
            single = clean(others[0].get_text(" "))
            # "Equipo A - Equipo B" en una sola celda
            parts = re.split(r"\s+(?:-|vs\.?|–)\s+", single, maxsplit=1)
            if len(parts) == 2:
                return dict(hour=hour, minute=minute, home=parts[0], away=parts[1], title=single,
                            competition=clean(comp_cell.get_text(" ")) if comp_cell else None,
                            channels=channel_items(cells[chan_idx]), homeLogo=None, awayLogo=None,
                            competitionLogo=img_url(comp_cell, base) if comp_cell else None,
                            channelLogos=channel_logos(cells[chan_idx], base))
            return dict(hour=hour, minute=minute, home=None, away=None, title=single,
                        competition=clean(comp_cell.get_text(" ")) if comp_cell else None,
                        channels=channel_items(cells[chan_idx]), homeLogo=None, awayLogo=None,
                        competitionLogo=img_url(comp_cell, base) if comp_cell else None,
                        channelLogos=channel_logos(cells[chan_idx], base))

    home = clean(home_cell.get_text(" ")) if home_cell else None
    away = clean(away_cell.get_text(" ")) if away_cell else None
    if not home and not away:
        return None
    return dict(
        hour=hour, minute=minute, home=home or None, away=away or None,
        title=f"{home} - {away}" if home and away else (home or away),
        competition=clean(comp_cell.get_text(" ")) if comp_cell else None,
        channels=channel_items(cells[chan_idx]),
        homeLogo=img_url(home_cell, base) if home_cell else None,
        awayLogo=img_url(away_cell, base) if away_cell else None,
        competitionLogo=img_url(comp_cell, base) if comp_cell else None,
        channelLogos=channel_logos(cells[chan_idx], base),
    )


def parse_tables(html: str, sport: str, base: str, today: datetime) -> list[Event]:
    soup = BeautifulSoup(html, "html.parser")
    events: list[Event] = []
    current_date = today.date()
    current_comp: Optional[str] = None
    seen_rows = set()

    for node in soup.find_all(True):
        name = node.name
        if name == "tr":
            if id(node) in seen_rows:
                continue
            seen_rows.add(id(node))
            cells = node.find_all(["td", "th"], recursive=False)
            row_text = clean(node.get_text(" "))
            row = parse_row(cells, base) if len(cells) >= 2 else None
            if row is None:
                d = DATE_RE.search(row_text)
                if d:
                    current_date = datetime(int(d.group(3)), int(d.group(2)), int(d.group(1))).date()
                    current_comp = None
                elif row_text and len(row_text) < 80 and not TIME_ANY_RE.search(row_text):
                    current_comp = row_text  # fila-separador con el nombre de la competición
                continue
            start = datetime(current_date.year, current_date.month, current_date.day,
                             row["hour"], row["minute"], tzinfo=TZ)
            channels = []
            for raw in row["channels"]:
                ch = parse_channel(raw)
                if ch:
                    ch.logo = row["channelLogos"].get(raw)
                    channels.append(ch)
            channels = dedupe_channels(channels)
            comp = row["competition"] or current_comp
            events.append(Event(
                id=event_id(sport, start, row["home"], row["away"], row["title"]),
                sport=sport, competition=comp, start=start.isoformat(),
                home=row["home"], away=row["away"], title=row["title"], channels=channels,
                homeLogo=row["homeLogo"], awayLogo=row["awayLogo"],
                competitionLogo=row["competitionLogo"],
            ))
        elif name in ("h1", "h2", "h3", "h4", "caption") or "cabecera" in classes(node) or "fecha" in classes(node):
            if node.find_parent("tr"):
                continue
            d = DATE_RE.search(clean(node.get_text(" ")))
            if d:
                current_date = datetime(int(d.group(3)), int(d.group(2)), int(d.group(1))).date()
                current_comp = None
    return events


def dedupe_channels(channels: Iterable[Channel]) -> list[Channel]:
    out: dict[str, Channel] = {}
    for ch in channels:
        key = slug(ch.name)
        if key in out:
            prev = out[key]
            prev.dial = prev.dial or ch.dial
            prev.note = prev.note or ch.note
            prev.logo = prev.logo or ch.logo
        else:
            out[key] = ch
    return list(out.values())


# --------------------------------------------------------------------------------------------
# Estrategia 2: JSON-LD (schema.org)
# --------------------------------------------------------------------------------------------

def parse_jsonld(html: str, sport: str) -> list[Event]:
    soup = BeautifulSoup(html, "html.parser")
    events: list[Event] = []
    for script in soup.find_all("script", attrs={"type": "application/ld+json"}):
        try:
            data = json.loads(script.string or script.get_text() or "")
        except (ValueError, TypeError):
            continue
        for item in walk_jsonld(data):
            if "Event" not in str(item.get("@type", "")):
                continue
            start_raw = item.get("startDate")
            if not start_raw:
                continue
            try:
                start = datetime.fromisoformat(str(start_raw).replace("Z", "+00:00"))
            except ValueError:
                continue
            if start.tzinfo is None:  # el sitio publica UTC sin indicarlo
                start = start.replace(tzinfo=timezone.utc)
            start = start.astimezone(TZ)
            home = name_of(item.get("homeTeam"))
            away = name_of(item.get("awayTeam"))
            competitors = item.get("competitor") or []
            if (not home or not away) and isinstance(competitors, list) and len(competitors) >= 2:
                home, away = name_of(competitors[0]), name_of(competitors[1])
            title = clean(item.get("name")) or " - ".join(x for x in (home, away) if x)
            comp = name_of(item.get("superEvent")) or name_of(item.get("organizer"))
            channels = []
            for b in as_list(item.get("broadcastOfEvent")) + as_list(item.get("publication")):
                ch = parse_channel(name_of(b.get("publishedOn") if isinstance(b, dict) else None) or name_of(b) or "")
                if ch:
                    channels.append(ch)
            events.append(Event(
                id=event_id(sport, start, home, away, title), sport=sport, competition=comp,
                start=start.isoformat(), home=home, away=away, title=title,
                channels=dedupe_channels(channels),
            ))
    return events


def walk_jsonld(data):
    if isinstance(data, list):
        for x in data:
            yield from walk_jsonld(x)
    elif isinstance(data, dict):
        yield data
        for key in ("@graph", "itemListElement", "subEvent", "item"):
            if key in data:
                yield from walk_jsonld(data[key])


def as_list(value) -> list:
    if value is None:
        return []
    return value if isinstance(value, list) else [value]


def name_of(value) -> Optional[str]:
    if isinstance(value, dict):
        return clean(value.get("name")) or None
    if isinstance(value, str):
        return clean(value) or None
    return None


# --------------------------------------------------------------------------------------------
# Combinación, descarga y salida
# --------------------------------------------------------------------------------------------

def parse_page(html: str, sport: str, base: str, now: datetime) -> tuple[list[Event], str]:
    table_events = parse_tables(html, sport, base, now)
    if table_events:
        return table_events, "html"
    return parse_jsonld(html, sport), "jsonld"


def load_logo_overrides(path: str) -> dict[str, dict[str, str]]:
    """logos.json: {"teams": {...}, "competitions": {...}, "channels": {...}} — nombre → URL."""
    if not path or not os.path.isfile(path):
        return {"teams": {}, "competitions": {}, "channels": {}}
    with open(path, encoding="utf-8") as fh:
        data = json.load(fh)
    return {k: {slug(n): u for n, u in (data.get(k) or {}).items() if u} for k in ("teams", "competitions", "channels")}


def apply_logos(events: list[Event], logos: dict[str, dict[str, str]]) -> None:
    """Los logos de logos.json tienen prioridad sobre los de la web (son los que tú eliges)."""
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


def merge(events: list[Event], now: datetime, keep_hours_back: int = 4, days_ahead: int = 14) -> list[Event]:
    lo = now - timedelta(hours=keep_hours_back)
    hi = now + timedelta(days=days_ahead)
    by_id: dict[str, Event] = {}
    for ev in events:
        start = datetime.fromisoformat(ev.start)
        if not (lo <= start <= hi):
            continue
        if ev.id in by_id:
            by_id[ev.id].channels = dedupe_channels(by_id[ev.id].channels + ev.channels)
        else:
            by_id[ev.id] = ev
    return sorted(by_id.values(), key=lambda e: (e.start, e.competition or "", e.title))


def fetch(url: str, timeout: int = 25) -> Optional[str]:
    import requests  # import local: los tests no necesitan red

    robots = robotparser.RobotFileParser()
    robots.set_url(urljoin(url, "/robots.txt"))
    try:
        robots.read()
        if not robots.can_fetch(USER_AGENT, url):
            print(f"  robots.txt no permite {url}; se omite", file=sys.stderr)
            return None
    except Exception as exc:  # robots inaccesible: seguimos con prudencia
        print(f"  aviso robots.txt: {exc}", file=sys.stderr)
    resp = requests.get(url, timeout=timeout, headers={
        "User-Agent": USER_AGENT,
        "Accept": "text/html,application/xhtml+xml",
        "Accept-Language": "es-ES,es;q=0.9",
    })
    if resp.status_code != 200:
        print(f"  HTTP {resp.status_code} en {url}", file=sys.stderr)
        return None
    resp.encoding = resp.apparent_encoding or "utf-8"
    return resp.text


def to_json(events: list[Event], now: datetime, stats: dict) -> dict:
    return {
        "version": SCHEMA_VERSION,
        "source": "futbolenlatv.es",
        "generatedAt": now.astimezone(timezone.utc).isoformat().replace("+00:00", "Z"),
        "timezone": "Europe/Madrid",
        "stats": stats,
        "events": [
            {k: v for k, v in asdict(e).items() if v not in (None, [], "")} | {"channels": [
                {k: v for k, v in asdict(c).items() if v is not None} for c in e.channels
            ]}
            for e in events
        ],
    }


def main(argv: Optional[list[str]] = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="agenda.json")
    ap.add_argument("--debug-dir", help="guarda el HTML descargado (para ajustar el parser)")
    ap.add_argument("--html", help="analiza un HTML local en vez de descargar")
    ap.add_argument("--sport", default="futbol")
    ap.add_argument("--logos", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "logos.json"),
                    help="mapa propio de logos (nombre → URL)")
    args = ap.parse_args(argv)

    now = datetime.now(TZ)
    all_events: list[Event] = []
    stats: dict[str, dict] = {}

    pages = [(args.sport, args.html, None)] if args.html else [(s, None, u) for s, u in SOURCES]
    for sport, path, url in pages:
        try:
            if path:
                html = open(path, encoding="utf-8").read()
            else:
                print(f"Descargando {url}", file=sys.stderr)
                html = fetch(url)
            if not html:
                stats[sport] = {"events": 0, "status": "omitido"}
                continue
            if args.debug_dir:
                os.makedirs(args.debug_dir, exist_ok=True)
                with open(os.path.join(args.debug_dir, f"{sport}.html"), "w", encoding="utf-8") as fh:
                    fh.write(html)
            events, strategy = parse_page(html, sport, url or BASE, now)
            with_channels = sum(1 for e in events if e.channels)
            stats[sport] = {"events": len(events), "withChannels": with_channels, "strategy": strategy}
            print(f"  {sport}: {len(events)} eventos ({with_channels} con canales) vía {strategy}", file=sys.stderr)
            all_events.extend(events)
        except Exception as exc:
            stats[sport] = {"events": 0, "status": f"error: {exc}"}
            print(f"  {sport}: ERROR {exc}", file=sys.stderr)

    merged = merge(all_events, now)
    apply_logos(merged, load_logo_overrides(args.logos))
    if not merged:
        print("No se ha extraído ningún evento: revisa el HTML guardado en --debug-dir", file=sys.stderr)
        return 2
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(to_json(merged, now, stats), fh, ensure_ascii=False, separators=(",", ":"))
    print(f"OK: {len(merged)} eventos → {args.out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
