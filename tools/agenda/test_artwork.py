"""Tests de retratos (TheSportsDB + athletes.json). Sin Internet."""
import os
import sys
import unittest
from datetime import datetime, timedelta, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import agenda_core as core  # noqa: E402
import artwork as art  # noqa: E402
import providers as pv  # noqa: E402

NOW = datetime(2026, 10, 23, 10, 17, tzinfo=timezone.utc)


def player(name, sport="Tennis", cutout="", thumb="", render=""):
    return {"strPlayer": name, "strSport": sport, "strCutout": cutout or None, "strThumb": thumb or None,
            "strRender": render or None, "strNationality": "Spain"}


class FakeHttp:
    def __init__(self, players=None, drivers=None):
        self.players = players or {}       # consulta → respuesta
        self.drivers = drivers
        self.calls = []

    def get_json(self, url, headers=None, params=None, label=""):
        self.calls.append((url, dict(params or {})))
        if url.endswith("drivers.json"):
            if self.drivers is None:
                raise pv.ProviderError(pv.PROVIDER_UNAVAILABLE, "HTTP 503")
            return {"MRData": {"DriverTable": {"Drivers": [{"givenName": g, "familyName": f} for g, f in self.drivers]}}}
        value = self.players.get(params["p"], {"player": None})
        if isinstance(value, Exception):
            raise value
        return value


def tennis(p1, p2):
    return core.Event(id="tennis-1", sport="tenis", competition="ATP", start=NOW + timedelta(days=1),
                      title=f"{p1} - {p2}", home=p1, away=p2)


def run(http, events, catalog=None, riders=(), sleep=None):
    a = art.Artwork(http, {}, NOW, catalog, motogp_riders=riders, sleep=sleep or (lambda s: None), clock=lambda: 0.0)
    a.enrich(events)
    return a


class PortraitTests(unittest.TestCase):
    def test_cutout_preferred(self):
        http = FakeHttp({"carlos_alcaraz": {"player": [player("Carlos Alcaraz", cutout="https://r2/cut.png", thumb="https://r2/th.png")]},
                         "jannik_sinner": {"player": [player("Jannik Sinner", thumb="https://r2/sinner.png")]}})
        ev = tennis("Carlos Alcaraz", "Jannik Sinner")
        a = run(http, [ev])
        self.assertEqual(ev.homeImage, "https://r2/cut.png")
        self.assertEqual(ev.awayImage, "https://r2/sinner.png")          # thumb como respaldo
        self.assertEqual(a.catalog["athletes"]["tenis:carlos-alcaraz"]["source"], "thesportsdb")

    def test_abbreviated_names_search_by_surname_and_validate_initial(self):
        http = FakeHttp({"alcaraz": {"player": [player("Carlos Alcaraz", cutout="https://r2/cut.png")]},
                         "paul": {"player": [player("Jake Paul", sport="Fighting", thumb="https://r2/jake.png")]}})
        ev = tennis("C. Alcaraz", "T. Paul")
        run(http, [ev])
        self.assertEqual(ev.homeImage, "https://r2/cut.png")
        self.assertIsNone(ev.awayImage)                                  # otro deporte: nunca otra cara

    def test_no_portrait_is_none(self):
        ev = tennis("D. Vallejo", "L. Sonego")
        a = run(FakeHttp(), [ev])
        self.assertIsNone(ev.homeImage)
        self.assertIsNone(a.catalog["athletes"]["tenis:d-vallejo"]["image"])   # caché negativa

    def test_existing_cache_means_no_request(self):
        catalog = {"athletes": {"tenis:carlos-alcaraz": {"name": "Carlos Alcaraz", "sport": "tenis",
                                                         "image": "https://r2/cut.png", "source": "thesportsdb",
                                                         "updatedAt": "2026-10-20T00:00:00Z"},
                                "tenis:jannik-sinner": {"name": "Jannik Sinner", "sport": "tenis", "image": None,
                                                        "source": None, "updatedAt": "2026-10-22T00:00:00Z"}}}
        http = FakeHttp()
        ev = tennis("Carlos Alcaraz", "Jannik Sinner")
        run(http, [ev], catalog)
        self.assertEqual(http.calls, [u for u in http.calls if "drivers.json" in u[0]])   # ninguna búsqueda
        self.assertEqual(ev.homeImage, "https://r2/cut.png")
        self.assertIsNone(ev.awayImage)

    def test_expired_negative_cache_retries(self):
        catalog = {"athletes": {"tenis:jannik-sinner": {"name": "Jannik Sinner", "sport": "tenis", "image": None,
                                                        "updatedAt": "2026-10-01T00:00:00Z"}}}
        http = FakeHttp({"jannik_sinner": {"player": [player("Jannik Sinner", cutout="https://r2/s.png")]},
                         "carlos_alcaraz": {"player": None}})
        ev = tennis("Carlos Alcaraz", "Jannik Sinner")
        run(http, [ev], catalog)
        self.assertEqual(ev.awayImage, "https://r2/s.png")

    def test_ufc_prefers_cito_and_falls_back_to_thesportsdb(self):
        ev = core.Event(id="ufc-ufc-333", sport="ufc", competition="UFC 333", start=NOW + timedelta(days=2),
                        title="Alexander Volkanovski - Movsar Evloev", home="Alexander Volkanovski",
                        away="Movsar Evloev", homeImage="https://cito/volk.png")
        http = FakeHttp({"alexander_volkanovski": {"player": [player("Alexander Volkanovski", "Fighting", cutout="https://r2/v.png")]},
                         "movsar_evloev": {"player": [player("Movsar Evloev", "Fighting", thumb="https://r2/e.png")]}})
        a = run(http, [ev])
        self.assertEqual(ev.homeImage, "https://cito/volk.png")            # Cito primero, sin consultar
        self.assertEqual(ev.awayImage, "https://r2/e.png")
        self.assertFalse(any(p.get("p") == "alexander_volkanovski" for _, p in http.calls))
        self.assertEqual(a.catalog["athletes"]["ufc:alexander-volkanovski"]["source"], "cito")

    def test_f1_and_motogp_only_in_catalog_never_on_race_cards(self):
        race = core.Event(id="formula1-2026-20-race", sport="formula1", competition="Fórmula 1",
                          start=NOW + timedelta(days=2), title="Mexico City Grand Prix · Carrera")
        http = FakeHttp({"max_verstappen": {"player": [player("Max Verstappen", "Motorsport", cutout="https://r2/max.png")]},
                         "marc_marquez": {"player": [player("Marc Márquez", "Motorsport", thumb="https://r2/mm.png")]}},
                        drivers=[("Max", "Verstappen")])
        a = run(http, [race], riders=["Marc Márquez"])
        self.assertIsNone(race.homeImage)
        self.assertIsNone(race.awayImage)
        self.assertEqual(a.catalog["athletes"]["formula1:max-verstappen"]["image"], "https://r2/max.png")
        self.assertEqual(a.catalog["athletes"]["motogp:marc-marquez"]["image"], "https://r2/mm.png")
        self.assertEqual(a.catalog["rosters"]["formula1"]["names"], ["Max Verstappen"])

    def test_rate_limit_stops_and_budget_is_small(self):
        events = [tennis(f"A{i} Uno", f"B{i} Dos") for i in range(10)]
        limited = FakeHttp({"a0_uno": pv.ProviderError(pv.RATE_LIMITED, "HTTP 429")})
        a = run(limited, events)
        self.assertEqual(sum(1 for u, _ in limited.calls if "searchplayers" in u), 1)
        self.assertEqual((a.status, a.error), ("unavailable", "rate_limited"))
        waits = []
        b = run(FakeHttp(), [tennis(f"A{i} Uno", f"B{i} Dos") for i in range(10)], sleep=waits.append)
        self.assertEqual(b.requests, art.Artwork.MAX_REQUESTS)
        self.assertTrue(all(w >= 2.0 for w in waits))
        self.assertEqual(b.status, "ok")                                  # agotar presupuesto no es un fallo

    def test_flags_are_never_portraits(self):
        self.assertIsNone(art.best_image({"strCutout": "", "strThumb": None, "strRender": "nope"}))
        self.assertEqual(art.best_image({"strThumb": "https://r2/t.png", "strRender": "https://r2/r.png"}), "https://r2/t.png")


if __name__ == "__main__":
    unittest.main()
