#!/usr/bin/env python3
"""
Genera agenda.json (versión 1) para la Agenda deportiva de Mi Visor.

    Football-Data · Live Tennis · Blacktop · Jolpica
        ↓
    generate_agenda.py  (GitHub Actions, una vez por hora)
        ↓
    agenda.json → rama agenda-data → APK

Estado por proveedor (en "stats"):
  ok          consulta correcta; se usan sus datos nuevos (puede tener 0 eventos).
  stale       la consulta ha fallado, pero se conservan los eventos de ese deporte de la
              última agenda publicada que siguen dentro de la ventana temporal.
  unavailable la consulta ha fallado y no hay datos anteriores válidos.

Si TODOS los proveedores fallan, no se escribe agenda.json y el proceso termina con código 3:
el workflow se detiene antes de publicar y la rama agenda-data conserva la última agenda válida.

Credenciales (solo variables de entorno / GitHub Secrets):
  FOOTBALL_DATA_TOKEN, LIVE_TENNIS_API_KEY, BLACKTOP_API_KEY, CITO_API_KEY   (Jolpica no necesita clave)
  THESPORTSDB_API_KEY (opcional; sin ella se usa la clave pública gratuita de TheSportsDB)

Enriquecimiento (nunca decide qué eventos existen; si falla, la agenda se publica igual):
  tv_futbolenlatv.py  canales de TV desde futbolenlatv.es/deporte (una descarga por ejecución)
  tv_thesportsdb.py   tvId de TheSportsDB (respaldo: la app pide canales si un partido no trae)
  artwork.py          retratos (athletes.json, caché larga)
Diagnóstico: services.json (estado por servicio + historial de 30 días).

Uso:
  python generate_agenda.py --out agenda.json [--previous previous.json] [--debug-dir debug/]
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import datetime, timezone
from typing import Callable, Iterable, Mapping, Optional

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from agenda_core import (  # noqa: E402
    TvProvider, apply_logos, build_agenda, in_window, load_logo_overrides, load_previous,
    previous_events, utc_iso,
)
from artwork import Artwork, load_catalog, load_seed  # noqa: E402
from channel_logos import ChannelLogos, load_brands  # noqa: E402
from provider_ufc import CitoUfc  # noqa: E402
from providers import ALL_PROVIDERS, INVALID_RESPONSE, USER_AGENT, Http, Provider, ProviderError  # noqa: E402
from services import build_services, load_services  # noqa: E402
from tv_futbolenlatv import FutbolEnLaTv  # noqa: E402
from tv_thesportsdb import TheSportsDbTv  # noqa: E402

EXIT_OK = 0
EXIT_ALL_FAILED = 3
SEVERITY = {"ok": 0, "disabled": 0, "stale": 1, "unavailable": 2}


class Result:
    def __init__(self, agenda: Optional[dict], stats: dict, log: list[str], aux: Optional[dict] = None):
        self.agenda = agenda
        self.stats = stats
        self.log = log
        self.aux = aux or {}

    @property
    def published(self) -> bool:
        return self.agenda is not None


def generate(
    providers: Iterable[Provider],
    http: Http,
    now: datetime,
    env: Mapping[str, str],
    previous: Optional[dict] = None,
    logos: Optional[dict] = None,
    tv: Optional[TvProvider] = None,
    enrichers: Optional[Iterable[TvProvider]] = None,
) -> Result:
    previous_stats = (previous or {}).get("stats") or {}
    previous_generated = (previous or {}).get("generatedAt")
    attempt = utc_iso(now)
    stats: dict[str, dict] = {}
    log: list[str] = []
    events = []
    any_ok = False

    for provider in providers:
        before = previous_stats.get(provider.key) if isinstance(previous_stats.get(provider.key), dict) else {}
        entry = {"provider": provider.name, "lastAttempt": attempt}
        try:
            fresh = [e for e in provider.fetch(http, now, env) if in_window(e.start, now)]
            any_ok = True
            entry.update(status="ok", lastSuccess=attempt, events=len(fresh))
            if getattr(provider, "contacted_at", "n/a") is None:
                # Caché propia del proveedor (p. ej. UFC cada 6 h): no se ha llamado a la API ahora.
                cached_at = (getattr(provider, "cache", None) or {}).get("fetchedAt") or attempt
                entry.update(lastAttempt=cached_at, lastSuccess=cached_at, cached=True)
                log.append(f"{provider.label}: OK (cached) - {len(fresh)} events")
            else:
                log.append(f"{provider.label}: OK - {len(fresh)} events")
            events.extend(fresh)
        except Exception as exc:  # noqa: BLE001 — cualquier fallo de un proveedor se aísla
            if isinstance(exc, ProviderError):
                code, detail = exc.code, exc.detail
            else:  # error de formato inesperado al interpretar la respuesta
                code, detail = INVALID_RESPONSE, type(exc).__name__
            cached = previous_events(previous, provider.sports, now)
            last_success = before.get("lastSuccess") or (previous_generated if cached else None)
            if cached:
                entry.update(status="stale", events=len(cached), error=code)
                events.extend(cached)
                log.append(f"{provider.label}: STALE - {code} ({detail}) - using {len(cached)} cached events")
            else:
                entry.update(status="unavailable", events=0, error=code)
                log.append(f"{provider.label}: UNAVAILABLE - {code} ({detail}) - no cached events")
            if last_success:
                entry["lastSuccess"] = last_success
        stats[provider.key] = entry

    if not any_ok:
        log.append("ALL PROVIDERS FAILED - agenda.json not generated; the last published agenda is kept")
        return Result(None, stats, log)

    # Ids únicos (por si un proveedor repite un evento).
    unique = list({e.id: e for e in events}.values())

    # Enriquecedores (TV, tvId, retratos): opcionales. Si fallan, los eventos se publican igual.
    aux: dict[str, dict] = {}
    for enricher in [*(enrichers or []), *([tv] if tv else [])]:
        service = getattr(enricher, "service", None)
        try:
            unique = enricher.enrich(unique)
            status, error = getattr(enricher, "status", "ok"), getattr(enricher, "error", None)
        except Exception as exc:  # noqa: BLE001
            log.append(f"TV: FAILED - {type(exc).__name__} - events published without TV data")
            status, error = "unavailable", INVALID_RESPONSE
        log.extend(getattr(enricher, "log", []) or [])
        if service:
            old = aux.get(service)
            if old is None or SEVERITY.get(status, 2) > SEVERITY.get(old["status"], 2):
                aux[service] = {"status": status, "error": error}
    if logos:
        apply_logos(unique, logos)
    return Result(build_agenda(unique, now, stats), stats, log, aux)


def write_json(path: str, data: dict) -> None:
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    tmp = f"{path}.tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(data, fh, ensure_ascii=False, separators=(",", ":"))
    os.replace(tmp, path)


def load_json(path: Optional[str]) -> Optional[dict]:
    if not path or not os.path.isfile(path):
        return None
    try:
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
        return data if isinstance(data, dict) else None
    except (OSError, ValueError):
        return None


def main(argv: Optional[list[str]] = None, env: Optional[Mapping[str, str]] = None,
         http_factory: Optional[Callable[[Optional[Callable]], Http]] = None,
         now: Optional[datetime] = None, sleep: Optional[Callable[[float], None]] = None) -> int:
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[1])
    ap.add_argument("--out", default="agenda.json")
    ap.add_argument("--previous", help="última agenda.json publicada (para el estado stale)")
    ap.add_argument("--state-dir", help="ficheros anteriores de agenda-data (services.json, athletes.json, ufc-cache.json)")
    ap.add_argument("--publish-dir", help="dónde escribir services.json, athletes.json y ufc-cache.json "
                                          "(por defecto, junto a --out)")
    ap.add_argument("--logos", default=os.path.join(here, "logos.json"))
    ap.add_argument("--seed", default=os.path.join(here, "athletes_seed.json"))
    ap.add_argument("--brands", default=os.path.join(here, "channel_brands.json"))
    ap.add_argument("--debug-dir", help="guarda las respuestas crudas de las fuentes (sin cabeceras)")
    args = ap.parse_args(argv)

    env = os.environ if env is None else env
    recorder = None
    if args.debug_dir:
        os.makedirs(args.debug_dir, exist_ok=True)
        counter = {"n": 0}

        def recorder(label: str, body: bytes) -> None:
            counter["n"] += 1
            head = body[:200].lstrip().lower()
            ext = "html" if head.startswith((b"<!doctype", b"<html", b"<")) else (
                "txt" if "robots" in (label or "") else "json")
            path = os.path.join(args.debug_dir, f"{counter['n']:02d}-{label or 'response'}.{ext}")
            with open(path, "wb") as fh:
                fh.write(body)

    http = (http_factory or (lambda rec: Http(recorder=rec)))(recorder)
    now = now or datetime.now(timezone.utc)
    state = args.state_dir
    publish = args.publish_dir or os.path.dirname(os.path.abspath(args.out))
    previous = load_previous(args.previous)
    previous_services = load_services(os.path.join(state, "services.json")) if state else None
    catalog = load_catalog(os.path.join(state, "athletes.json")) if state else None
    ufc_cache = load_json(os.path.join(state, "ufc-cache.json")) if state else None
    print(f"Previous agenda: {'found' if previous else 'none'} · services: "
          f"{'found' if previous_services else 'none'} · athletes: "
          f"{len((catalog or {}).get('athletes', {}))} · ufc cache: {'found' if ufc_cache else 'none'}",
          file=sys.stderr)

    ufc = CitoUfc(ufc_cache)
    extra = {"sleep": sleep} if sleep else {}
    ids = TheSportsDbTv(http, env, now, previous, **extra)
    art = Artwork(http, env, now, catalog, motogp_riders=load_seed(args.seed).get("motogp", []),
                  after=ids, **extra)
    result = generate(
        [*(cls() for cls in ALL_PROVIDERS), ufc], http, now, env,
        previous=previous, logos=load_logo_overrides(args.logos),
        enrichers=[FutbolEnLaTv(http, now, previous), ids, art],
    )
    for line in result.log:
        print(line, file=sys.stderr)

    # services.json siempre (también si no hay agenda nueva), para ver el problema desde la APK.
    services = build_services(now, result.stats, result.aux, result.published, previous_services)
    write_json(os.path.join(publish, "services.json"), services)
    write_json(os.path.join(publish, "athletes.json"), art.to_json())
    if ufc.cache_out:
        write_json(os.path.join(publish, "ufc-cache.json"), ufc.cache_out)
    print(f"services.json: overall={services['overall']}", file=sys.stderr)

    # Catálogo de logos de canales (solo metadatos visuales). Nunca afecta a la agenda: si falla,
    # se vuelve a publicar el catálogo anterior.
    previous_logos = load_json(os.path.join(state, "channel-logos.json")) if state else None
    try:
        brands = load_brands(args.brands)
        logos_step = ChannelLogos(http, now, brands, previous_logos, load_json(args.logos) or {},
                                  agent=USER_AGENT.split("/")[0])
        events = result.agenda["events"] if result.published else (previous or {}).get("events", [])
        write_json(os.path.join(publish, "channel-logos.json"), logos_step.build(events))
        for line in logos_step.log:
            print(line, file=sys.stderr)
    except Exception as exc:   # noqa: BLE001 — los logos nunca tumban la agenda
        print(f"logos: error {type(exc).__name__}; se mantiene el catálogo anterior", file=sys.stderr)
        if previous_logos:
            write_json(os.path.join(publish, "channel-logos.json"), previous_logos)

    if not result.published:
        return EXIT_ALL_FAILED
    write_json(args.out, result.agenda)
    print(f"agenda.json: {len(result.agenda['events'])} events -> {args.out}", file=sys.stderr)
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main())
