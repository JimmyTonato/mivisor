"""
Catálogo de logos de canales (channel-logos.json), publicado en la rama agenda-data junto a agenda.json.

Dos niveles separados:
    groups   → logo general del grupo/proveedor (Movistar, DAZN, Eurosport…)   ← channel_brands.json
    channels → logo específico de cada canal (M+ LaLiga, DAZN F1…) y su grupo

Fuentes, por prioridad (la primera que tenga logo manda):
    1. logos.json (manual, "channels"/"groups")
    2. futbolenlatv.es/deporte: los logos que ya trae la agenda (sin peticiones extra)
    3. iptv-org (https://iptv-org.github.io/api): canales de España y de los grupos conocidos,
       descargado como mucho UNA VEZ POR SEMANA y respetando robots.txt
    (TheSportsDB: lo añade la propia app con los canales que pide al abrir un partido.)

Caché: el catálogo anterior se conserva de una ejecución a otra; un logo encontrado no se vuelve a
buscar. Las entradas de futbolenlatv que no se vuelven a ver en 90 días se retiran.

La búsqueda trabaja con el nombre NORMALIZADO (letras decorativas Unicode → texto normal), nunca con el
texto original. Nada de esto toca enlaces ni reproducción: son solo metadatos visuales.
"""
from __future__ import annotations

import json
import os
import re
import unicodedata
from datetime import datetime, timedelta, timezone
from typing import Any, Iterable, Mapping, Optional

from providers import ProviderError

CATALOG_VERSION = 1
IPTV_CHANNELS = "https://iptv-org.github.io/api/channels.json"
IPTV_LOGOS = "https://iptv-org.github.io/api/logos.json"
IPTV_ROBOTS = "https://iptv-org.github.io/robots.txt"
IPTV_REFRESH = timedelta(days=7)
IPTV_MAX_BODY = 40 * 1024 * 1024
SEEN_TTL = timedelta(days=90)
IMAGE_FORMATS = {"PNG", "JPEG", "JPG", "WEBP"}          # la app no dibuja SVG
SOURCE_RANK = {"manual": 0, "futbolenlatv": 1, "iptv-org": 2, "thesportsdb": 3}

# ---------------------------------------------------------------------------------- normalización

def _decorative_to_ascii(ch: str) -> str:
    """Letras decorativas que NFKC no convierte (🅼 🅐 🇲…)."""
    cp = ord(ch)
    for start, base in ((0x1F130, "A"), (0x1F150, "A"), (0x1F170, "A"), (0x1F1E6, "A")):
        if start <= cp < start + 26:
            return chr(ord(base) + cp - start)
    return ch


ALIASES = (                         # mismos alias que la app (ChannelMatcher) para los grupos
    ("movistar plus+", "movistar "), ("movistar plus", "movistar "), ("movistar+", "movistar "),
    ("m+", "movistar "), ("la liga", "laliga"), ("laliga+", "laliga plus"),
)


def norm_text(name: Optional[str]) -> str:
    """'🄼🄾🅅🄸🅂🅃🄰🅁 🄻🄰🄻🄸🄶🄰' → 'movistar laliga'. Minúsculas, sin acentos ni símbolos, alias aplicados."""
    if not name:
        return ""
    text = unicodedata.normalize("NFKC", "".join(_decorative_to_ascii(c) for c in name))
    text = "".join(c for c in unicodedata.normalize("NFD", text.lower()) if unicodedata.category(c) != "Mn")
    for src, dst in ALIASES:
        text = text.replace(src, dst)
    return re.sub(r"[^a-z0-9]+", " ", text).strip()


QUALITY = {"hd", "fhd", "uhd", "sd", "4k", "1080", "1080p", "720", "720p", "hevc", "es", "spain"}


def channel_key(name: Optional[str]) -> str:
    """Clave canónica de un canal: texto normalizado sin palabras de calidad."""
    return " ".join(t for t in norm_text(name).split() if t not in QUALITY)


# ---------------------------------------------------------------------------------- grupos

class Brands:
    def __init__(self, data: Mapping[str, Any]):
        self.groups: dict[str, dict] = {}
        self._aliases: list[tuple[list[str], str]] = []
        for key, g in (data.get("groups") or {}).items():
            aliases = [a for a in (g.get("aliases") or []) if a]
            self.groups[key] = {
                "name": g.get("name") or key,
                "aliases": aliases,
                "flagship": list(g.get("flagship") or []),
                "logo": (g.get("logo") or "").strip(),
            }
            for alias in aliases:
                tokens = norm_text(alias).split()
                if tokens:
                    self._aliases.append((tokens, key))
        self._aliases.sort(key=lambda a: -len(" ".join(a[0])))

    def group_of(self, name: Optional[str]) -> Optional[str]:
        """Grupo cuyo alias es el PRINCIPIO del nombre ('DAZN LaLiga' → dazn; 'M+ LaLiga' → movistar)."""
        tokens = norm_text(name).split()
        for alias, key in self._aliases:
            if tokens[:len(alias)] == alias:
                return key
        return None


def load_brands(path: str) -> Brands:
    with open(path, encoding="utf-8") as fh:
        return Brands(json.load(fh))


# ---------------------------------------------------------------------------------- iptv-org

def pick_iptv_logo(logos: Iterable[Mapping[str, Any]]) -> Optional[str]:
    """Mejor logo de un canal: imagen (no SVG), en uso, feed principal, no la versión blanca, la más grande."""
    best, best_score = None, None
    for lg in logos:
        url = (lg.get("url") or "").strip()
        fmt = (lg.get("format") or "").upper()
        if not url.startswith("https://") or (fmt and fmt not in IMAGE_FORMATS) or url.lower().endswith(".svg"):
            continue
        tags = {str(t).lower() for t in (lg.get("tags") or [])}
        score = (
            1 if lg.get("in_use", True) else 0,
            1 if not lg.get("feed") else 0,
            0 if "white" in tags else 1,
            min(int(lg.get("width") or 0), 1000),
        )
        if best_score is None or score > best_score:
            best, best_score = url, score
    return best


def iptv_entries(channels: Iterable[Mapping[str, Any]], logos: Iterable[Mapping[str, Any]],
                 brands: Brands, countries: tuple[str, ...] = ("ES",)) -> dict[str, dict]:
    """Canales de España y de los grupos conocidos (en cualquier país), con su mejor logo."""
    by_channel: dict[str, list] = {}
    for lg in logos:
        cid = lg.get("channel")
        if cid:
            by_channel.setdefault(cid, []).append(lg)
    out: dict[str, dict] = {}
    for ch in channels:
        if ch.get("is_nsfw") or ch.get("closed"):
            continue
        name = (ch.get("name") or "").strip()
        group = brands.group_of(name)
        if ch.get("country") not in countries and group is None:
            continue
        logo = pick_iptv_logo(by_channel.get(ch.get("id"), []))
        key = channel_key(name)
        if not logo or not key:
            continue
        aliases = sorted({a for a in (ch.get("alt_names") or []) if isinstance(a, str) and a.strip()})
        entry = out.get(key)
        # Si dos canales comparten nombre (p. ej. país distinto), se queda el de España.
        if entry and entry.get("_country") == "ES" and ch.get("country") != "ES":
            continue
        out[key] = {"name": name, "aliases": aliases, "logo": logo, "group": group, "source": "iptv-org",
                    "_country": ch.get("country")}
    for e in out.values():
        e.pop("_country", None)
    return out


def robots_allows_path(robots_txt: str, url: str, agent: str) -> bool:
    import urllib.robotparser
    parser = urllib.robotparser.RobotFileParser()
    parser.parse(robots_txt.splitlines())
    return parser.can_fetch(agent, url)


# ---------------------------------------------------------------------------------- catálogo

def harvest_agenda(events: Iterable[Mapping[str, Any]]) -> dict[str, dict]:
    """Logos de canal que ya vienen en agenda.json (futbolenlatv.es)."""
    out: dict[str, dict] = {}
    for ev in events:
        for ch in ev.get("channels") or []:
            name, logo = (ch.get("name") or "").strip(), (ch.get("logo") or "").strip()
            if name and logo.startswith("https://") and not logo.lower().endswith(".svg"):
                out.setdefault(channel_key(name), {"name": name, "logo": logo, "source": "futbolenlatv"})
    return out


def _better(new: Mapping[str, Any], old: Optional[Mapping[str, Any]]) -> bool:
    if not old or not old.get("logo"):
        return True
    return SOURCE_RANK.get(new.get("source"), 9) <= SOURCE_RANK.get(old.get("source"), 9)


def build_catalog(brands: Brands, previous: Optional[Mapping[str, Any]], now: datetime,
                  agenda_events: Iterable[Mapping[str, Any]] = (),
                  iptv: Optional[dict[str, dict]] = None, iptv_fetched: Optional[str] = None,
                  manual: Optional[Mapping[str, Mapping[str, str]]] = None) -> dict:
    stamp = now.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    prev_channels = dict((previous or {}).get("channels") or {})
    channels: dict[str, dict] = {}

    # 1) Caché: lo anterior se conserva (iptv-org se sustituye entero si hay descarga nueva).
    for key, e in prev_channels.items():
        if iptv is not None and e.get("source") == "iptv-org":
            continue
        if e.get("source") == "futbolenlatv":
            seen = _parse(e.get("seen"))
            if seen and now - seen > SEEN_TTL:
                continue
        channels[key] = dict(e)

    # 2) iptv-org (nuevo) → 3) futbolenlatv (agenda actual) → 4) manual. Cada paso solo sustituye si su
    #    fuente tiene igual o más prioridad que la que había.
    for key, e in (iptv or {}).items():
        if _better(e, channels.get(key)):
            channels[key] = {**e}
    for key, e in harvest_agenda(agenda_events).items():
        old = channels.get(key)
        if _better(e, old):
            channels[key] = {**(old or {}), **e, "seen": stamp}
        elif old is not None and old.get("source") == "futbolenlatv":
            old["seen"] = stamp
    for name, url in ((manual or {}).get("channels") or {}).items():
        if url:
            key = channel_key(name)
            channels[key] = {**channels.get(key, {}), "name": name, "logo": url, "source": "manual"}

    for key, e in channels.items():
        e["group"] = e.get("group") or brands.group_of(e.get("name")) or brands.group_of(key)
        if not e["group"]:
            e.pop("group", None)

    # Grupos: logo propio → logo manual → logo de la MARCA (un canal que se llame como la marca, p. ej. "DAZN").
    # Nunca el de un canal numerado ("DAZN 1"): si no, los canales sin logo heredarían el logo de otro canal.
    numbered_logos = {e.get("logo") for e in channels.values() if re.search(r"\d", e.get("name") or "")}
    groups: dict[str, dict] = {}
    manual_groups = {norm_text(k): v for k, v in ((manual or {}).get("groups") or {}).items() if v}
    for key, g in brands.groups.items():
        logo, source = g["logo"] or manual_groups.get(norm_text(g["name"])) or manual_groups.get(key), "manual"
        if not logo:
            for flag in [g["name"], *g["flagship"]]:
                if re.search(r"\d", flag):
                    continue
                e = channels.get(channel_key(flag))
                if e and e.get("logo") and e["logo"] not in numbered_logos:
                    logo, source = e["logo"], e.get("source")
                    break
        groups[key] = {"name": g["name"], "aliases": g["aliases"], **({"logo": logo, "source": source} if logo else {})}

    return {
        "version": CATALOG_VERSION,
        "updated": stamp,
        "iptv_fetched": iptv_fetched or (previous or {}).get("iptv_fetched"),
        "groups": groups,
        "channels": dict(sorted(channels.items())),
    }


def _parse(value: Optional[str]) -> Optional[datetime]:
    try:
        return datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc) if value else None
    except ValueError:
        return None


class ChannelLogos:
    """Paso del workflow: lee el catálogo anterior, refresca iptv-org si toca y genera el nuevo."""

    def __init__(self, http, now: datetime, brands: Brands, previous: Optional[Mapping[str, Any]] = None,
                 manual: Optional[Mapping[str, Mapping[str, str]]] = None, agent: str = "MiVisorAgenda"):
        self.http, self.now, self.brands, self.previous, self.manual, self.agent = http, now, brands, previous, manual, agent
        self.log: list[str] = []

    def iptv_due(self) -> bool:
        last = _parse((self.previous or {}).get("iptv_fetched"))
        return last is None or self.now - last >= IPTV_REFRESH

    def fetch_iptv(self) -> Optional[dict[str, dict]]:
        try:
            try:
                robots = self.http.get_text(IPTV_ROBOTS, label="logos-robots")
            except ProviderError as exc:
                if exc.status and 400 <= exc.status < 500:
                    robots = ""                 # sin robots.txt: todo permitido
                else:
                    raise
            if not robots_allows_path(robots, IPTV_CHANNELS, self.agent):
                self.log.append("logos: iptv-org no permitido por robots.txt; se mantiene la caché")
                return None
            channels = self.http.get_json(IPTV_CHANNELS, label="logos-iptv-channels", max_body=IPTV_MAX_BODY)
            logos = self.http.get_json(IPTV_LOGOS, label="logos-iptv-logos", max_body=IPTV_MAX_BODY)
            if not isinstance(channels, list) or not isinstance(logos, list):
                self.log.append("logos: respuesta de iptv-org no válida; se mantiene la caché")
                return None
            entries = iptv_entries(channels, logos, self.brands)
            self.log.append(f"logos: iptv-org {len(entries)} canales con logo")
            return entries
        except ProviderError as exc:
            self.log.append(f"logos: iptv-org no disponible ({exc.code}); se mantiene la caché")
            return None
        except Exception as exc:  # noqa: BLE001 — iptv-org es opcional: nunca impide el catálogo
            self.log.append(f"logos: iptv-org falló ({type(exc).__name__}); se mantiene la caché")
            return None

    def build(self, agenda_events: Iterable[Mapping[str, Any]]) -> dict:
        iptv, fetched = None, None
        if self.iptv_due():
            iptv = self.fetch_iptv()
            if iptv is not None:
                fetched = self.now.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        catalog = build_catalog(self.brands, self.previous, self.now, agenda_events, iptv, fetched, self.manual)
        with_logo = sum(1 for e in catalog["channels"].values() if e.get("logo"))
        groups = sum(1 for g in catalog["groups"].values() if g.get("logo"))
        self.log.append(f"logos: {with_logo} canales y {groups}/{len(catalog['groups'])} grupos con logo")
        return catalog
