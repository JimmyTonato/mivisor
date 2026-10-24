"""
services.json — diagnóstico de fuentes para la sección "Servicios" de la APK.

Fuente de verdad única: los estados de los proveedores de agenda salen de los mismos "stats" que se
publican en agenda.json (ok / stale / unavailable), y los auxiliares (TV, retratos) de su enriquecedor.

Historial: solo fallos, cambios de estado y recuperaciones (nunca una entrada "OK" cada hora).
Retención: 30 días y como mucho 100 entradas por servicio. Sin claves, cabeceras, URLs ni trazas.

overall:
  ok        todos los servicios funcionan
  degraded  alguno falla o usa datos anteriores, pero hay agenda utilizable
  down      no se ha podido generar la agenda (fallan todas las fuentes de agenda)
"""
from __future__ import annotations

import json
import os
from datetime import datetime, timedelta
from typing import Optional

from agenda_core import parse_instant, utc_iso
from providers import ERROR_CODES, PROVIDER_UNAVAILABLE

SCHEMA_VERSION = 1
RETENTION = timedelta(days=30)
MAX_PER_SERVICE = 100

# (id, nombre, función, tipo, clave en stats de agenda.json)
SERVICES = (
    ("football_data", "Football-Data", "Agenda de fútbol", "core", "football"),
    ("live_tennis", "Live Tennis API", "Agenda de tenis", "core", "tennis"),
    ("jolpica", "Jolpica", "Agenda de Fórmula 1", "core", "formula1"),
    ("blacktop", "Blacktop", "Agenda de MotoGP", "core", "motogp"),
    ("cito_ufc", "Cito UFC", "Agenda UFC / imágenes UFC", "core", "ufc"),
    ("futbolenlatv", "Fútbol en la TV", "Canales de televisión", "aux", None),
    ("thesportsdb", "TheSportsDB", "Retratos / artwork", "aux", None),
    ("agenda", "Agenda GitHub", "Generación y publicación de la agenda", "system", None),
)

# Mensajes breves para personas (los mismos que traduce la APK). Nunca texto técnico crudo.
MESSAGES = {
    "timeout": "Tiempo de espera agotado",
    "rate_limited": "Límite temporal de la API",
    "authentication_error": "Error de autenticación",
    "invalid_response": "Respuesta no válida",
    "robots_disallowed": "La fuente ya no permite la consulta automática",
    "provider_unavailable": "Servicio no disponible",
    "parse_error": "Ha cambiado el formato de la fuente",
    "no_agenda": "No se ha podido generar la agenda",
}


def sanitize_code(code: Optional[str]) -> Optional[str]:
    if code is None:
        return None
    return code if code in ERROR_CODES or code == "no_agenda" else PROVIDER_UNAVAILABLE


def load_services(path: Optional[str]) -> Optional[dict]:
    if not path or not os.path.isfile(path):
        return None
    try:
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
    except (OSError, ValueError):
        return None
    if not isinstance(data, dict) or data.get("version") != SCHEMA_VERSION or not isinstance(data.get("services"), list):
        return None
    return data


def build_services(now: datetime, stats: dict, aux: dict, published: bool,
                   previous: Optional[dict] = None) -> dict:
    """
    stats: {"football": {"status", "lastAttempt", "lastSuccess", "error"}, …} (los de agenda.json)
    aux:   {"futbolenlatv": {"status", "error"}, "thesportsdb": {…}}  (estado de esta ejecución)
    """
    attempt = utc_iso(now)
    prev_by_id = {s.get("id"): s for s in (previous or {}).get("services") or [] if isinstance(s, dict)}
    history = [h for h in (previous or {}).get("history") or [] if isinstance(h, dict)]
    out_services = []

    for sid, name, role, kind, stats_key in SERVICES:
        before = prev_by_id.get(sid) or {}
        if stats_key:
            st = stats.get(stats_key) or {}
            status = st.get("status") or "unavailable"
            code = st.get("error")
            last_attempt = st.get("lastAttempt") or attempt
            last_success = st.get("lastSuccess") or before.get("lastSuccess")
        elif sid == "agenda":
            status = "ok" if published else "unavailable"
            code = None if published else "no_agenda"
            last_attempt = attempt
            last_success = attempt if published else before.get("lastSuccess")
        else:
            a = aux.get(sid)
            if a is None:                                   # no se ejecutó en esta pasada
                status, code = before.get("status", "disabled"), None
                last_attempt, last_success = before.get("lastAttempt"), before.get("lastSuccess")
            else:
                status = a.get("status") or "unavailable"
                code = a.get("error")
                last_attempt = attempt
                last_success = attempt if status == "ok" else before.get("lastSuccess")
        code = sanitize_code(code) if status != "ok" else None

        failures = 0 if status in ("ok", "disabled") else int(before.get("consecutiveFailures") or 0) + 1
        last_error = before.get("lastError") if isinstance(before.get("lastError"), dict) else None
        if code:
            last_error = {"code": code, "message": MESSAGES.get(code, MESSAGES["provider_unavailable"]), "at": last_attempt}

        # Historial: fallos (cada uno), cambios de estado y recuperaciones. Nunca "OK" repetidos.
        prev_status = before.get("status")
        if status not in ("ok", "disabled") and last_attempt == attempt:
            history.append({"service": sid, "at": attempt, "type": "error", "status": status, "code": code,
                            "message": "Sincronización fallida"})
        elif status == "ok" and prev_status and prev_status not in ("ok", "disabled"):
            history.append({"service": sid, "at": attempt, "type": "recovery", "status": "ok",
                            "message": "Servicio recuperado"})

        out_services.append({
            "id": sid, "name": name, "role": role, "kind": kind, "status": status,
            "lastAttempt": last_attempt, "lastSuccess": last_success,
            "consecutiveFailures": failures, "lastError": last_error,
        })

    core_ok = any(s["status"] in ("ok", "stale") for s in out_services if s["kind"] == "core")
    if not published or not core_ok:
        overall = "down"
    elif all(s["status"] in ("ok", "disabled") for s in out_services):
        overall = "ok"
    else:
        overall = "degraded"

    return {
        "version": SCHEMA_VERSION,
        "generatedAt": attempt,
        "overall": overall,
        "services": out_services,
        "history": prune_history(history, now),
    }


def prune_history(history: list[dict], now: datetime) -> list[dict]:
    """Quita lo de más de 30 días y deja como mucho 100 entradas por servicio (las más recientes)."""
    limit = now - RETENTION
    kept = []
    for h in history:
        at = parse_instant(h.get("at"))
        if at is not None and at >= limit:
            kept.append(h)
    kept.sort(key=lambda h: h["at"])
    per: dict[str, int] = {}
    out = []
    for h in reversed(kept):
        sid = h.get("service")
        per[sid] = per.get(sid, 0) + 1
        if per[sid] <= MAX_PER_SERVICE:
            out.append(h)
    return list(reversed(out))
