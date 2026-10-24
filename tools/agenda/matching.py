"""
Normalización y similitud de nombres compartida por los enriquecedores (TV, TheSportsDB, retratos).

- Equipos: "Atlético Madrid", "At. Madrid", "Atlético de Madrid", "Atleti" → mismos tokens.
- Personas: "C. Alcaraz", "Alcaraz C.", "Carlos Alcaraz", "ALCARAZ, Carlos" → mismo apellido + inicial.
- Sesiones de motor: clasificación / sprint / carrera (y "clasificación sprint", que NO es el sprint).
Nunca se compara texto literal.
"""
from __future__ import annotations

import re
import unicodedata
from typing import Iterable, Optional

# Palabras que no ayudan a identificar a un equipo.
STOP = {
    "fc", "cf", "afc", "sc", "ac", "as", "ss", "ssc", "us", "rc", "rcd", "cd", "ud", "sd", "ca", "sv",
    "vfb", "vfl", "tsg", "fsv", "bv", "bsc", "osc", "ogc", "stade", "club", "de", "del", "la", "le", "el",
    "of", "and", "the", "calcio", "football", "futbol", "balompie", "cp",
}
# Sinónimos por palabra (abreviaturas españolas, nombres cortos de Football-Data, variantes).
SYNONYMS = {
    "barca": "barcelona", "atleti": "atletico", "at": "atletico", "atl": "atletico", "atco": "atletico",
    "ath": "athletic", "internazionale": "inter", "milano": "milan", "munchen": "munich",
    "muenchen": "munich", "koln": "cologne", "koeln": "cologne", "colonia": "cologne",
    "spurs": "tottenham", "wolves": "wolverhampton", "man": "manchester", "utd": "united",
    "saint": "st", "olympique": "", "r": "real", "dep": "deportivo", "depor": "deportivo",
    "bayer": "", "borussia": "", "psg": "paris", "sg": "", "germain": "", "st": "",
    "munchengladbach": "gladbach", "monchengladbach": "gladbach", "mgladbach": "gladbach",
    # Nombres en español de equipos extranjeros
    "marsella": "marseille", "napoles": "napoli", "turin": "torino", "brujas": "brugge", "oporto": "porto",
    "lisboa": "lisbon", "praga": "prague", "estambul": "istanbul", "londres": "london",
}
# Nombres cortos que necesitan el nombre completo.
ALIASES = {
    "man city": "manchester city", "man united": "manchester united", "nottingham": "nottingham forest",
    "nottm forest": "nottingham forest", "athletic": "athletic bilbao", "betis": "real betis",
    "psg": "paris saint germain", "bayern": "bayern munich", "m gladbach": "gladbach",
    "brighton hove": "brighton", "brighton hove albion": "brighton",
}


def ascii_lower(text: str) -> str:
    text = unicodedata.normalize("NFKD", str(text or "")).encode("ascii", "ignore").decode().lower()
    return re.sub(r"[^a-z0-9]+", " ", text).strip()


def team_tokens(name: str) -> frozenset[str]:
    text = ascii_lower(name)
    text = ALIASES.get(text, text)
    out = set()
    for token in text.split():
        token = SYNONYMS.get(token, token)
        if token and token not in STOP and not token.isdigit():
            out.add(token)
    return frozenset(out)


def team_similarity(ours: Iterable[str], theirs: Optional[str]) -> float:
    """0..1 entre nuestras variantes del nombre de un equipo y el de otra fuente."""
    if not theirs:
        return 0.0
    b = team_tokens(theirs)
    best = 0.0
    for name in ours:
        a = team_tokens(name)
        if not a or not b:
            continue
        if a == b:
            return 1.0
        if a <= b or b <= a:
            best = max(best, 0.9)
        else:
            # "Atlético" ~ "Atleti": prefijo común largo
            shared = len(a & b) + sum(
                0.8 for x in a - b for y in b - a if len(x) >= 4 and len(y) >= 4 and (x.startswith(y) or y.startswith(x))
            )
            best = max(best, min(1.0, shared / len(a | b)))
    return best


# ------------------------------------------------------------------ personas

def person_parts(name: str) -> tuple[str, Optional[str]]:
    """("alcaraz", "c"): apellido normalizado + inicial del nombre (si se conoce)."""
    raw = str(name or "").strip()
    if "," in raw:                               # "ALCARAZ, Carlos"
        last, first = raw.split(",", 1)
        raw = f"{first} {last}"
    tokens = ascii_lower(raw).split()
    if not tokens:
        return "", None
    initials = [t for t in tokens if len(t) == 1]
    words = [t for t in tokens if len(t) > 1]
    if not words:
        return tokens[-1], None
    # "Alcaraz C." (inicial al final) o "C. Alcaraz" (al principio)
    if initials and len(words) == 1:
        return words[0], initials[0]
    first = words[0] if len(words) > 1 else None
    surname = " ".join(words[1:]) if len(words) > 1 else words[0]
    return surname, (first[0] if first else (initials[0] if initials else None))


def person_similarity(a: str, b: str) -> float:
    """1.0 mismo apellido e inicial; 0.9 mismo apellido sin inicial; 0.3 apellido igual e inicial distinta."""
    sa, ia = person_parts(a)
    sb, ib = person_parts(b)
    if not sa or not sb:
        return 0.0
    same = sa == sb or sa.split()[-1] == sb.split()[-1] or sa.replace(" ", "") == sb.replace(" ", "")
    if not same:
        return 0.0
    if ia and ib:
        return 1.0 if ia == ib else 0.3
    return 0.9


# ------------------------------------------------------------------ sesiones de motor

SPRINT_QUALI = re.compile(r"clasificaci\w*\s+(?:al\s+|del\s+|para\s+el\s+)?sprint|sprint\s+(?:qualif|shootout|clasif)|shootout",
                          re.IGNORECASE)
QUALIFYING = re.compile(r"clasificaci|qualif|\bqualy\b|\bq1\b|\bq2\b|pole", re.IGNORECASE)
SPRINT = re.compile(r"\bsprint\b", re.IGNORECASE)
RACE = re.compile(r"carrera|\brace\b|gran premio|grand prix|\bgp\b", re.IGNORECASE)
PRACTICE = re.compile(r"libres|practice|\bfp\d?\b|\bfp\b|entrenamiento|warm", re.IGNORECASE)


def session_kind(text: str) -> Optional[str]:
    """qualifying | sprint | race | sprint_qualifying | practice | None."""
    text = text or ""
    if SPRINT_QUALI.search(text):
        return "sprint_qualifying"
    if PRACTICE.search(text):
        return "practice"
    if QUALIFYING.search(text):
        return "qualifying"
    if SPRINT.search(text):
        return "sprint"
    if RACE.search(text):
        return "race"
    return None
