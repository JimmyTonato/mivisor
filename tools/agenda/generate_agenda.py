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
  FOOTBALL_DATA_TOKEN, LIVE_TENNIS_API_KEY, BLACKTOP_API_KEY   (Jolpica no necesita clave)
  THESPORTSDB_API_KEY (opcional; sin ella se usa la clave pública gratuita de TheSportsDB)

Canales de TV: tv_thesportsdb.py identifica cada partido de fútbol en TheSportsDB ("tvId") y la app
pide sus canales al abrirlo. Es independiente: si falla, la agenda se publica igual.

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
from providers import ALL_PROVIDERS, INVALID_RESPONSE, Http, Provider, ProviderError  # noqa: E402
from tv_thesportsdb import TheSportsDbTv  # noqa: E402

EXIT_OK = 0
EXIT_ALL_FAILED = 3


class Result:
    def __init__(self, agenda: Optional[dict], stats: dict, log: list[str]):
        self.agenda = agenda
        self.stats = stats
        self.log = log

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
            events.extend(fresh)
            log.append(f"{provider.label}: OK - {len(fresh)} events")
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
    # Canales de TV: enriquecimiento opcional. Si falla, los eventos se publican igual.
    tv = tv or TvProvider()
    try:
        unique = tv.enrich(unique)
    except Exception as exc:  # noqa: BLE001
        log.append(f"TV: FAILED - {type(exc).__name__} - events published without TV data")
    log.extend(getattr(tv, "log", []) or [])
    if logos:
        apply_logos(unique, logos)
    return Result(build_agenda(unique, now, stats), stats, log)


def main(argv: Optional[list[str]] = None, env: Optional[Mapping[str, str]] = None,
         http_factory: Optional[Callable[[Optional[Callable]], Http]] = None,
         now: Optional[datetime] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[1])
    ap.add_argument("--out", default="agenda.json")
    ap.add_argument("--previous", help="última agenda.json publicada (para el estado stale)")
    ap.add_argument("--logos", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "logos.json"))
    ap.add_argument("--debug-dir", help="guarda las respuestas crudas de los proveedores (sin cabeceras)")
    args = ap.parse_args(argv)

    env = os.environ if env is None else env
    recorder = None
    if args.debug_dir:
        os.makedirs(args.debug_dir, exist_ok=True)
        counter = {"n": 0}

        def recorder(label: str, body: bytes) -> None:
            counter["n"] += 1
            path = os.path.join(args.debug_dir, f"{counter['n']:02d}-{label or 'response'}.json")
            with open(path, "wb") as fh:
                fh.write(body)

    http = (http_factory or (lambda rec: Http(recorder=rec)))(recorder)
    now = now or datetime.now(timezone.utc)
    previous = load_previous(args.previous)
    print(f"Previous agenda: {'found' if previous else 'none'}", file=sys.stderr)

    result = generate(
        [cls() for cls in ALL_PROVIDERS], http, now, env,
        previous=previous, logos=load_logo_overrides(args.logos),
        tv=TheSportsDbTv(http, env, now, previous),
    )
    for line in result.log:
        print(line, file=sys.stderr)
    if not result.published:
        return EXIT_ALL_FAILED
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(result.agenda, fh, ensure_ascii=False, separators=(",", ":"))
    print(f"agenda.json: {len(result.agenda['events'])} events -> {args.out}", file=sys.stderr)
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main())
