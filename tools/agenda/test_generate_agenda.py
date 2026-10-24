"""
Tests del generador de agenda. Sin Internet: todas las respuestas de proveedores están simuladas.
Ejecutar: python -m unittest discover -s tools/agenda -v
"""
import io
import json
import os
import socket
import sys
import tempfile
import unittest
import urllib.error
from datetime import datetime, timedelta, timezone
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import agenda_core as core  # noqa: E402
import generate_agenda as gen  # noqa: E402
import providers as pv  # noqa: E402

# "Ahora" fijo: viernes 23/10/2026 10:17 UTC (todavía horario de verano en Madrid).
NOW = datetime(2026, 10, 23, 10, 17, tzinfo=timezone.utc)
SECRETS = {
    "FOOTBALL_DATA_TOKEN": "fd-SECRET-0001",
    "LIVE_TENNIS_API_KEY": "lt-SECRET-0002",
    "BLACKTOP_API_KEY": "bt-SECRET-0003",
}


class FakeHttp(pv.Http):
    """Responde según el inicio de la URL. Un valor Exception se lanza; lo demás se devuelve."""

    def __init__(self, routes):
        super().__init__()
        self.routes = routes
        self.calls = []

    def get_json(self, url, headers=None, params=None, label=""):
        self.calls.append((url, dict(headers or {}), dict(params or {})))
        for prefix, response in self.routes.items():
            if url.startswith(prefix):
                value = response(url, params) if callable(response) else response
                if isinstance(value, Exception):
                    raise value
                return value
        raise pv.ProviderError(pv.PROVIDER_UNAVAILABLE, "HTTP 404")


# ----------------------------------------------------------------------------- respuestas tipo

def football_payload():
    def match(mid, code, name, utc, home, away, status="TIMED"):
        return {
            "id": mid, "utcDate": utc, "status": status, "matchday": 10,
            "competition": {"id": 1, "name": name, "code": code, "emblem": f"https://crests.example/{code}.png"},
            "homeTeam": {"id": 86, "name": f"{home} CF", "shortName": home, "crest": f"https://crests.example/{home}.png"},
            "awayTeam": {"id": 559, "name": f"{away} FC", "shortName": away, "crest": None},
        }
    return {"matches": [
        match(123456, "PD", "Primera Division", "2026-10-24T16:00:00Z", "Real Madrid", "Sevilla"),
        match(123457, "PL", "Premier League", "2026-10-24T19:30:00Z", "Arsenal", "Chelsea"),
        match(123458, "ELC", "Championship", "2026-10-24T14:00:00Z", "Leeds", "Burnley"),        # 2.ª división
        match(123459, "SD", "Segunda Division", "2026-10-24T14:00:00Z", "Racing", "Eibar"),      # 2.ª división
        match(123460, "BL2", "2. Bundesliga", "2026-10-24T11:00:00Z", "Hamburg", "Kiel"),        # 2.ª división
        match(123461, "SA", "Serie A", "2026-10-25T19:45:00Z", "Inter", "Milan", status="POSTPONED"),
        match(123462, "CL", "UEFA Champions League", "2026-10-27T20:00:00Z", "Barcelona", "Bayern"),
    ]}


def tennis_payload():
    return {"data": [
        {"id": 987654, "tour": "atp", "tournament": "Paris Masters", "round": "R32",
         "start_time": "2026-10-28T13:00:00Z", "player1_name": "Carlos Alcaraz", "player2_name": "Jannik Sinner"},
        {"id": 987655, "tour": "atp", "tournament": {"name": "Bratislava Challenger", "level": "challenger"},
         "start_time": "2026-10-28T10:00:00Z", "player1_name": "A", "player2_name": "B"},
        {"id": 987656, "tour": "itf", "tournament": "M25 Madrid",
         "start_time": "2026-10-28T10:00:00Z", "player1_name": "C", "player2_name": "D"},
        {"id": 987657, "tour": "atp", "tournament": "Paris Masters", "start_time": None,      # sin hora aún
         "player1_name": "E", "player2_name": "F"},
        {"id": 987658, "tour": "atp", "tournament": "Paris Masters", "is_doubles": True,
         "start_time": "2026-10-28T15:00:00Z", "player1_name": "G/H", "player2_name": "I/J"},
        {"id": 987659, "tour": "juniors_boys", "tournament": "Orange Bowl",
         "start_time": "2026-10-28T15:00:00Z", "player1_name": "K", "player2_name": "L"},
    ], "meta": {"count": 6, "has_more": False}}


def wta_payload():
    return {"data": [
        {"fixture_id": "w-77", "tournament": "WTA Finals Riyadh", "scheduled_time": "2026-11-01T16:00:00+03:00",
         "players": {"p1": {"name": "Aryna Sabalenka"}, "p2": {"name": "Iga Swiatek"}}},
    ], "meta": {"has_more": False}}


def jolpica_payload():
    return {"MRData": {"RaceTable": {"season": "2026", "Races": [
        {"season": "2026", "round": "20", "raceName": "Mexico City Grand Prix",
         "date": "2026-10-25", "time": "20:00:00Z",
         "FirstPractice": {"date": "2026-10-23", "time": "18:30:00Z"},
         "Qualifying": {"date": "2026-10-24", "time": "22:00:00Z"}},
        {"season": "2026", "round": "21", "raceName": "São Paulo Grand Prix",
         "date": "2026-11-01", "time": "17:00:00Z",
         "Sprint": {"date": "2026-10-31", "time": "14:00:00Z"},
         "SprintQualifying": {"date": "2026-10-30", "time": "18:30:00Z"},
         "Qualifying": {"date": "2026-10-31", "time": "18:00:00Z"}},
        {"season": "2026", "round": "22", "raceName": "Las Vegas Grand Prix",       # fuera de la ventana
         "date": "2026-11-21", "time": "06:00:00Z", "Qualifying": {"date": "2026-11-20", "time": "06:00:00Z"}},
        {"season": "2026", "round": "19", "raceName": "United States Grand Prix",   # ya pasado
         "date": "2026-10-18", "time": "19:00:00Z"},
    ]}}}


def blacktop_list():
    return {"events": [
        {"id": "motogp-2026-malaysia", "name": "Malaysian Grand Prix", "dateStart": "2026-10-23",
         "dateEnd": "2026-10-25", "country": {"name": "Malaysia", "twoCode": "MY"},
         "sessions": [
             {"name": "Free Practice 1", "startTime": "2026-10-23T02:45:00Z"},
             {"name": "Practice", "startTime": "2026-10-23T07:00:00Z"},
             {"name": "Qualifying 1", "startTime": "2026-10-24T02:50:00Z"},
             {"name": "Qualifying 2", "startTime": "2026-10-24T03:15:00Z"},
             {"name": "Sprint", "startTime": "2026-10-24T07:00:00Z"},
             {"name": "Warm Up", "startTime": "2026-10-25T02:40:00Z"},
             {"name": "Race", "startTime": "2026-10-25T07:00:00Z"},
         ]},
        {"id": "motogp-2026-portugal", "name": "Portuguese Grand Prix", "dateStart": "2026-10-30",
         "dateEnd": "2026-11-01", "country": {"name": "Portugal"}},          # sesiones en el detalle
        {"id": "motogp-2026-qatar", "name": "Qatar Grand Prix", "dateStart": "2026-04-10",
         "dateEnd": "2026-04-12", "country": {"name": "Qatar"}},             # fuera de la ventana
    ]}


def blacktop_detail():
    return {"id": "motogp-2026-portugal", "sessions": [
        {"type": "Q1", "start": "2026-10-31T10:50:00Z"},
        {"type": "Q2", "start": "2026-10-31T11:15:00Z"},
        {"type": "SPR", "start": "2026-10-31T15:00:00Z"},
        {"type": "RAC", "start": "2026-11-01T13:00:00Z"},
    ]}


def all_ok_routes():
    def tennis(url, params):
        return tennis_payload() if params.get("tour") == "atp" else wta_payload()
    return {
        pv.FootballData.BASE: lambda url, params: football_payload(),
        pv.LiveTennis.BASE: tennis,
        pv.Jolpica.BASE: jolpica_payload(),
        pv.Blacktop.BASE + "/events/": blacktop_detail(),
        pv.Blacktop.BASE + "/events": blacktop_list(),
    }


def providers():
    return [cls() for cls in pv.ALL_PROVIDERS]


def run(routes, previous=None, env=SECRETS, now=NOW):
    http = FakeHttp(routes)
    return gen.generate(providers(), http, now, env, previous=previous), http


# ----------------------------------------------------------------------------- proveedores → Event

class FootballDataTests(unittest.TestCase):
    def test_matches_to_events_and_allowlist(self):
        http = FakeHttp({pv.FootballData.BASE: lambda u, p: football_payload()})
        events = {e.id: e for e in pv.FootballData().fetch(http, NOW, SECRETS)}
        self.assertEqual(set(events), {"football-123456", "football-123457", "football-123462"})
        rm = events["football-123456"]
        self.assertEqual((rm.sport, rm.competition, rm.home, rm.away), ("futbol", "LaLiga", "Real Madrid", "Sevilla"))
        self.assertEqual(rm.title, "Real Madrid - Sevilla")
        self.assertEqual(rm.homeLogo, "https://crests.example/Real Madrid.png")
        self.assertIsNone(rm.awayLogo)
        self.assertEqual(rm.competitionLogo, "https://crests.example/PD.png")
        self.assertEqual(rm.channels, [])
        self.assertEqual(events["football-123462"].competition, "Champions League")

    def test_requests_are_listings_with_auth_and_allowlist(self):
        http = FakeHttp({pv.FootballData.BASE: lambda u, p: {"matches": []}})
        pv.FootballData().fetch(http, NOW, SECRETS)
        self.assertLessEqual(len(http.calls), 3)   # listados por rango, no un partido por petición
        for url, headers, params in http.calls:
            self.assertEqual(url, f"{pv.FootballData.BASE}/matches")
            self.assertEqual(headers["X-Auth-Token"], SECRETS["FOOTBALL_DATA_TOKEN"])
            codes = params["competitions"].split(",")
            for lower in ("ELC", "SD", "SB", "BL2", "FL2", "EL1"):
                self.assertNotIn(lower, codes)
            days = (datetime.fromisoformat(params["dateTo"]) - datetime.fromisoformat(params["dateFrom"])).days
            self.assertLessEqual(days, 10)
        covered_to = max(datetime.fromisoformat(p["dateTo"]) for _, _, p in http.calls)
        self.assertGreaterEqual(covered_to.date(), (NOW + timedelta(days=14)).date())


class LiveTennisTests(unittest.TestCase):
    def setUp(self):
        self.http = FakeHttp({pv.LiveTennis.BASE: lambda u, p: tennis_payload() if p["tour"] == "atp" else wta_payload()})
        self.events = {e.id: e for e in pv.LiveTennis().fetch(self.http, NOW, SECRETS)}

    def test_main_tours_only(self):
        self.assertEqual(set(self.events), {"tennis-987654", "tennis-w-77"})
        ev = self.events["tennis-987654"]
        self.assertEqual((ev.sport, ev.home, ev.away), ("tenis", "Carlos Alcaraz", "Jannik Sinner"))
        self.assertEqual(ev.competition, "ATP · Paris Masters · R32")
        self.assertEqual(self.events["tennis-w-77"].competition, "WTA Finals Riyadh")
        self.assertEqual(self.events["tennis-w-77"].start, datetime(2026, 11, 1, 13, 0, tzinfo=timezone.utc))

    def test_listing_requests_with_bearer(self):
        self.assertEqual(len(self.http.calls), 2)  # un listado por circuito (ATP y WTA)
        for url, headers, params in self.http.calls:
            self.assertEqual(url, f"{pv.LiveTennis.BASE}/fixtures")
            self.assertEqual(headers["Authorization"], f"Bearer {SECRETS['LIVE_TENNIS_API_KEY']}")
            self.assertIn(params["tour"], ("atp", "wta"))

    def test_minor_tournament_filter(self):
        lt = pv.LiveTennis()
        base = {"id": 1, "tour": "atp", "start_time": "2026-10-28T10:00:00Z", "player1_name": "A", "player2_name": "B"}
        for name in ("Lima Challenger", "ITF M15 Antalya", "Futures Spain F3", "ATP Next Gen Exhibition"):
            self.assertIsNone(lt.to_event({**base, "tournament": name}, "atp"), name)
        self.assertIsNone(lt.to_event({**base, "tour": "challenger", "tournament": "Lima"}, "atp"))
        self.assertIsNotNone(lt.to_event({**base, "tournament": "Australian Open"}, "atp"))

    def test_pagination_follows_cursor(self):
        pages = iter([
            {"data": [], "meta": {"has_more": True, "next_cursor": "abc"}},
            {"data": [], "meta": {"has_more": False}},
            {"data": [], "meta": {"has_more": False}},
        ])
        http = FakeHttp({pv.LiveTennis.BASE: lambda u, p: next(pages)})
        pv.LiveTennis().fetch(http, NOW, SECRETS)
        self.assertEqual(http.calls[1][2].get("cursor"), "abc")


class JolpicaTests(unittest.TestCase):
    def test_sessions_and_ids(self):
        http = FakeHttp({pv.Jolpica.BASE: jolpica_payload()})
        events = {e.id: e for e in pv.Jolpica().fetch(http, NOW, {})}
        self.assertEqual(set(events), {
            "formula1-2026-20-qualifying", "formula1-2026-20-race",
            "formula1-2026-21-sprint", "formula1-2026-21-qualifying", "formula1-2026-21-race",
        })   # sin libres, sin sprint qualifying, sin carreras fuera de la ventana
        race = events["formula1-2026-20-race"]
        self.assertEqual((race.sport, race.competition), ("formula1", "Fórmula 1"))
        self.assertEqual(race.title, "Mexico City Grand Prix · Carrera")
        self.assertEqual(events["formula1-2026-21-sprint"].title, "São Paulo Grand Prix · Sprint")
        self.assertEqual(http.calls[0][1], {})   # sin clave

    def test_session_without_time_is_skipped(self):
        race = {"season": "2026", "round": "5", "raceName": "X GP", "date": "2026-10-30"}
        self.assertEqual(pv.Jolpica().race_events(race), [])


class BlacktopTests(unittest.TestCase):
    def setUp(self):
        self.http = FakeHttp({pv.Blacktop.BASE + "/events/": blacktop_detail(), pv.Blacktop.BASE + "/events": blacktop_list()})
        self.events = {e.id: e for e in pv.Blacktop().fetch(self.http, NOW, SECRETS)}

    def test_sessions_to_events(self):
        self.assertEqual(set(self.events), {
            "motogp-2026-malaysia-qualifying", "motogp-2026-malaysia-sprint", "motogp-2026-malaysia-race",
            "motogp-2026-portugal-qualifying", "motogp-2026-portugal-sprint", "motogp-2026-portugal-race",
        })   # sin libres ni warm up
        q = self.events["motogp-2026-malaysia-qualifying"]
        self.assertEqual(q.start, datetime(2026, 10, 24, 2, 50, tzinfo=timezone.utc))  # Q1+Q2 → hora de Q1
        self.assertEqual((q.sport, q.competition, q.title), ("motogp", "MotoGP", "Malaysian Grand Prix · Clasificación"))

    def test_requests(self):
        urls = [c[0] for c in self.http.calls]
        self.assertEqual(urls[0], f"{pv.Blacktop.BASE}/events")
        # detalle solo del GP dentro de la ventana sin sesiones en el listado
        self.assertEqual(urls[1:], [f"{pv.Blacktop.BASE}/events/motogp-2026-portugal"])
        for _, headers, _ in self.http.calls:
            self.assertEqual(headers["x-api-key"], SECRETS["BLACKTOP_API_KEY"])


# ----------------------------------------------------------------------------- horas y formato

class TimeAndFormatTests(unittest.TestCase):
    def test_cest_and_cet(self):
        cest = core.Event("x", "futbol", None, datetime(2026, 10, 24, 16, 0, tzinfo=timezone.utc), "a")
        cet = core.Event("y", "futbol", None, datetime(2026, 10, 26, 16, 0, tzinfo=timezone.utc), "b")
        winter = core.Event("z", "futbol", None, datetime(2027, 1, 10, 20, 0, tzinfo=timezone.utc), "c")
        self.assertEqual(core.event_to_json(cest)["start"], "2026-10-24T18:00:00+02:00")
        self.assertEqual(core.event_to_json(cet)["start"], "2026-10-26T17:00:00+01:00")
        self.assertEqual(core.event_to_json(winter)["start"], "2027-01-10T21:00:00+01:00")

    def test_dst_change_night(self):
        # 25/10/2026 01:30 UTC = 02:30 CET (ya cambiada la hora a las 01:00 UTC)
        ev = core.Event("x", "motogp", None, datetime(2026, 10, 25, 1, 30, tzinfo=timezone.utc), "t")
        self.assertEqual(core.event_to_json(ev)["start"], "2026-10-25T02:30:00+01:00")

    def test_agenda_json_v1(self):
        result, _ = run(all_ok_routes())
        agenda = result.agenda
        self.assertEqual(agenda["version"], 1)
        self.assertEqual(agenda["source"], "MiVisor Sports APIs")
        self.assertEqual(agenda["timezone"], "Europe/Madrid")
        self.assertEqual(agenda["generatedAt"], "2026-10-23T10:17:00Z")
        self.assertNotIn("futbolenlatv", json.dumps(agenda))
        starts = [e["start"] for e in agenda["events"]]
        self.assertEqual(starts, sorted(starts, key=lambda s: datetime.fromisoformat(s)))
        sports = {e["sport"] for e in agenda["events"]}
        self.assertEqual(sports, {"futbol", "tenis", "formula1", "motogp"})
        for ev in agenda["events"]:
            for key in ("id", "sport", "start", "title", "channels"):
                self.assertIn(key, ev)
            self.assertEqual(ev["channels"], [])   # nunca se inventan canales

    def test_ids_are_stable(self):
        a, _ = run(all_ok_routes())
        b, _ = run(all_ok_routes(), now=NOW + timedelta(hours=1))
        ids_a = {e["id"] for e in a.agenda["events"]}
        ids_b = {e["id"] for e in b.agenda["events"]}
        self.assertTrue(ids_a)
        self.assertEqual(ids_a & ids_b, ids_a - {i for i in ids_a if i not in ids_b})
        self.assertIn("formula1-2026-20-race", ids_a)
        self.assertIn("motogp-2026-malaysia-race", ids_a)
        self.assertIn("football-123456", ids_a)
        self.assertIn("tennis-987654", ids_a)


# ----------------------------------------------------------------------------- estados y errores

class StatusTests(unittest.TestCase):
    def test_all_ok(self):
        result, _ = run(all_ok_routes())
        for key in ("football", "tennis", "formula1", "motogp"):
            self.assertEqual(result.stats[key]["status"], "ok")
            self.assertEqual(result.stats[key]["lastSuccess"], "2026-10-23T10:17:00Z")
            self.assertNotIn("error", result.stats[key])
        self.assertEqual(result.stats["football"]["provider"], "football-data")
        self.assertEqual(result.stats["tennis"]["provider"], "live-tennis-api")
        self.assertEqual(result.stats["formula1"]["provider"], "jolpica")
        self.assertEqual(result.stats["motogp"]["provider"], "blacktop")
        self.assertIn("Football-Data: OK - 3 events", result.log)

    def test_ok_with_zero_events_is_not_an_error(self):
        routes = all_ok_routes()
        routes[pv.FootballData.BASE] = {"matches": []}
        result, _ = run(routes)
        self.assertEqual(result.stats["football"]["status"], "ok")
        self.assertEqual(result.stats["football"]["events"], 0)
        self.assertIn("Football-Data: OK - 0 events", result.log)

    def check_error(self, exc, expected_code):
        routes = all_ok_routes()
        routes[pv.LiveTennis.BASE] = exc
        result, _ = run(routes)
        self.assertEqual(result.stats["tennis"]["status"], "unavailable")
        self.assertEqual(result.stats["tennis"]["error"], expected_code)
        self.assertEqual(result.stats["tennis"]["events"], 0)
        self.assertNotIn("tenis", {e["sport"] for e in result.agenda["events"]})
        self.assertEqual(result.stats["football"]["status"], "ok")   # los demás no se ven afectados

    def test_timeout(self):
        self.check_error(pv.ProviderError(pv.TIMEOUT, "timeout"), "timeout")

    def test_rate_limited(self):
        self.check_error(pv.ProviderError(pv.code_for_status(429), "HTTP 429"), "rate_limited")

    def test_auth_errors(self):
        self.check_error(pv.ProviderError(pv.code_for_status(401), "HTTP 401"), "authentication_error")
        self.check_error(pv.ProviderError(pv.code_for_status(403), "HTTP 403"), "authentication_error")

    def test_invalid_response(self):
        self.check_error({"unexpected": True}, "invalid_response")

    def test_missing_secret_is_auth_error_without_request(self):
        env = {k: v for k, v in SECRETS.items() if k != "BLACKTOP_API_KEY"}
        result, http = run(all_ok_routes(), env=env)
        self.assertEqual(result.stats["motogp"]["status"], "unavailable")
        self.assertEqual(result.stats["motogp"]["error"], "authentication_error")
        self.assertFalse(any(url.startswith(pv.Blacktop.BASE) for url, _, _ in http.calls))

    def previous_agenda(self):
        prev = gen.generate(providers(), FakeHttp(all_ok_routes()), NOW - timedelta(hours=1), SECRETS).agenda
        # un partido de tenis antiguo que ya no debe conservarse
        prev["events"].append({"id": "tennis-old", "sport": "tenis", "start": "2026-10-20T12:00:00+02:00",
                               "title": "Viejo - Partido", "channels": []})
        return prev

    def test_stale_keeps_last_valid_events_of_that_sport_only(self):
        previous = self.previous_agenda()
        routes = all_ok_routes()
        routes[pv.LiveTennis.BASE] = pv.ProviderError(pv.TIMEOUT, "timeout")
        result, _ = run(routes, previous=previous)
        tennis = result.stats["tennis"]
        self.assertEqual(tennis["status"], "stale")
        self.assertEqual(tennis["error"], "timeout")
        self.assertEqual(tennis["lastSuccess"], "2026-10-23T09:17:00Z")
        self.assertEqual(tennis["lastAttempt"], "2026-10-23T10:17:00Z")
        ids = {e["id"] for e in result.agenda["events"] if e["sport"] == "tenis"}
        self.assertEqual(ids, {"tennis-987654", "tennis-w-77"})       # el caducado se elimina
        self.assertEqual(tennis["events"], 2)
        self.assertIn("Live Tennis: STALE - timeout (timeout) - using 2 cached events", result.log)

    def test_stale_drops_events_outside_window(self):
        previous = self.previous_agenda()
        routes = all_ok_routes()
        routes[pv.Jolpica.BASE] = pv.ProviderError(pv.PROVIDER_UNAVAILABLE, "HTTP 503")
        later = NOW + timedelta(days=3)   # la clasificación del GP de México fue un día anterior
        result, _ = run(routes, previous=previous, now=later)
        ids = {e["id"] for e in result.agenda["events"] if e["sport"] == "formula1"}
        self.assertNotIn("formula1-2026-20-qualifying", ids)
        self.assertIn("formula1-2026-21-race", ids)
        self.assertEqual(result.stats["formula1"]["status"], "stale")

    def test_unavailable_without_previous_data(self):
        routes = all_ok_routes()
        routes[pv.Blacktop.BASE + "/events"] = pv.ProviderError(pv.PROVIDER_UNAVAILABLE, "HTTP 500")
        result, _ = run(routes, previous=None)
        self.assertEqual(result.stats["motogp"]["status"], "unavailable")
        self.assertNotIn("lastSuccess", result.stats["motogp"])
        self.assertTrue(result.published)

    def test_all_providers_failed_publishes_nothing(self):
        failing = {prefix: pv.ProviderError(pv.PROVIDER_UNAVAILABLE, "HTTP 503") for prefix in all_ok_routes()}
        result, _ = run(failing, previous=self.previous_agenda())
        self.assertFalse(result.published)
        self.assertIn("ALL PROVIDERS FAILED", result.log[-1])
        for key in ("football", "tennis", "formula1", "motogp"):
            self.assertIn(result.stats[key]["status"], ("stale", "unavailable"))

    def test_main_exit_code_and_no_file_when_all_fail(self):
        failing = {prefix: pv.ProviderError(pv.PROVIDER_UNAVAILABLE, "HTTP 503") for prefix in all_ok_routes()}
        with tempfile.TemporaryDirectory() as tmp:
            out = os.path.join(tmp, "agenda.json")
            with mock.patch("sys.stderr", new=io.StringIO()):
                code = gen.main(["--out", out], env=SECRETS, http_factory=lambda rec: FakeHttp(failing), now=NOW)
            self.assertEqual(code, gen.EXIT_ALL_FAILED)
            self.assertFalse(os.path.exists(out))


# ----------------------------------------------------------------------------- seguridad y E2E

class SecurityAndEndToEndTests(unittest.TestCase):
    def test_secrets_never_in_output_logs_or_debug(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = os.path.join(tmp, "agenda.json")
            prev = os.path.join(tmp, "previous.json")
            debug = os.path.join(tmp, "debug")
            with open(prev, "w") as fh:
                json.dump({"version": 1, "events": [], "stats": {}}, fh)
            routes = all_ok_routes()
            routes[pv.LiveTennis.BASE] = pv.ProviderError(pv.AUTHENTICATION_ERROR, "HTTP 401")
            stderr = io.StringIO()

            def factory(recorder):
                http = FakeHttp(routes)
                http.recorder = recorder
                return http

            with mock.patch("sys.stderr", new=stderr):
                code = gen.main(["--out", out, "--previous", prev, "--debug-dir", debug],
                                env=SECRETS, http_factory=factory, now=NOW)
            self.assertEqual(code, 0)
            blobs = [open(out, encoding="utf-8").read(), stderr.getvalue()]
            for name in os.listdir(debug):
                blobs.append(open(os.path.join(debug, name), encoding="utf-8").read())
            for secret in SECRETS.values():
                for blob in blobs:
                    self.assertNotIn(secret, blob)
            for word in ("Authorization", "X-Auth-Token", "x-api-key", "Bearer", "Traceback"):
                self.assertNotIn(word, blobs[0])
                self.assertNotIn(word, blobs[1])
            agenda = json.loads(blobs[0])
            self.assertEqual(agenda["stats"]["tennis"]["error"], "authentication_error")

    def test_http_layer_maps_errors_without_leaking_url_or_headers(self):
        http = pv.Http(timeout=1)
        cases = [
            (urllib.error.HTTPError("https://x", 429, "Too Many", {}, None), "rate_limited"),
            (urllib.error.HTTPError("https://x", 401, "No", {}, None), "authentication_error"),
            (urllib.error.HTTPError("https://x", 403, "No", {}, None), "authentication_error"),
            (urllib.error.HTTPError("https://x", 503, "Down", {}, None), "provider_unavailable"),
            (socket.timeout("timed out"), "timeout"),
            (urllib.error.URLError(socket.timeout("t")), "timeout"),
            (urllib.error.URLError("dns"), "provider_unavailable"),
        ]
        for exc, code in cases:
            with mock.patch("urllib.request.urlopen", side_effect=exc):
                with self.assertRaises(pv.ProviderError) as ctx:
                    http.get_json("https://api.example/x", headers={"X-Auth-Token": "fd-SECRET-0001"})
                self.assertEqual(ctx.exception.code, code)
                self.assertNotIn("SECRET", ctx.exception.detail)

    def test_http_layer_invalid_json(self):
        class Resp(io.BytesIO):
            def __enter__(self):
                return self

            def __exit__(self, *a):
                return False
        with mock.patch("urllib.request.urlopen", return_value=Resp(b"<html>")):
            with self.assertRaises(pv.ProviderError) as ctx:
                pv.Http().get_json("https://api.example/x")
            self.assertEqual(ctx.exception.code, "invalid_response")

    def test_logo_overrides_still_apply(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "logos.json")
            with open(path, "w", encoding="utf-8") as fh:
                json.dump({"teams": {"sevilla": "https://mis.logos/sevilla.png"},
                           "competitions": {"Fórmula 1": "https://mis.logos/f1.png"}}, fh)
            http = FakeHttp(all_ok_routes())
            result = gen.generate(providers(), http, NOW, SECRETS, logos=core.load_logo_overrides(path))
            rm = next(e for e in result.agenda["events"] if e["id"] == "football-123456")
            self.assertEqual(rm["awayLogo"], "https://mis.logos/sevilla.png")
            f1 = next(e for e in result.agenda["events"] if e["sport"] == "formula1")
            self.assertEqual(f1["competitionLogo"], "https://mis.logos/f1.png")


if __name__ == "__main__":
    unittest.main()
