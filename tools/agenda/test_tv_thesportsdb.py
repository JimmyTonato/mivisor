"""
Tests de la identificación de partidos en TheSportsDB (tvId). Sin Internet: respuestas simuladas con
la forma real de la API ("events", strTimestamp en UTC). Los canales los pide la app (tests en Kotlin).
Ejecutar: python -m unittest discover -s tools/agenda -v
"""
import json
import os
import sys
import unittest
from datetime import datetime, timedelta, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import agenda_core as core  # noqa: E402
import generate_agenda as gen  # noqa: E402
import providers as pv  # noqa: E402
import tv_thesportsdb as tv  # noqa: E402

NOW = datetime(2026, 10, 23, 10, 17, tzinfo=timezone.utc)


class FakeHttp:
    """Responde según el endpoint de TheSportsDB. Un valor Exception (o lista de valores) se lanza/consume."""

    def __init__(self, rounds=None, day=None):
        self.rounds = rounds if rounds is not None else {}
        self.day = day
        self.calls = []

    def get_json(self, url, headers=None, params=None, label=""):
        self.calls.append((url, dict(params or {})))
        endpoint = url.rsplit("/", 1)[-1]
        if endpoint == "eventsround.php":
            value = self.rounds.get((params["id"], params["r"]), {"events": None})
        elif endpoint == "eventsday.php":
            value = self.day if self.day is not None else {"events": None}
        else:
            value = pv.ProviderError(pv.PROVIDER_UNAVAILABLE, "HTTP 404")
        if isinstance(value, list):
            value = value.pop(0)
        if isinstance(value, Exception):
            raise value
        return value


def match(mid, home, away, utc, code="PD", matchday=10, home_names=None, away_names=None):
    return core.Event(
        id=f"football-{mid}", sport="futbol", competition="LaLiga",
        start=datetime.fromisoformat(utc.replace("Z", "+00:00")), title=f"{home} - {away}",
        home=home, away=away,
        ref={"competitionCode": code, "matchday": matchday, "season": "2026-2027",
             "homeNames": home_names or [home], "awayNames": away_names or [away]},
    )


def tsdb_event(eid, home, away, utc):
    return {"idEvent": eid, "strHomeTeam": home, "strAwayTeam": away, "strTimestamp": utc,
            "intRound": "10", "strSeason": "2026-2027", "idLeague": "4335"}


LALIGA_ROUND = {"events": [
    tsdb_event("2501", "Real Madrid", "Sevilla", "2026-10-24T19:00:00"),
    tsdb_event("2502", "Barcelona", "Atletico Madrid", "2026-10-25T19:00:00"),
    tsdb_event("2503", "Villarreal", "Girona", "2026-10-24T14:00:00"),
    tsdb_event("2504", "Getafe", "Osasuna", "2026-10-24T14:00:00"),
]}


def resolver(http, env=None, previous=None, sleep=None):
    return tv.TheSportsDbTv(http, env or {}, NOW, previous, sleep=sleep or (lambda s: None), clock=lambda: 0.0)


class IdentificationTests(unittest.TestCase):
    def test_one_request_per_round_and_ids_assigned(self):
        events = [
            match(1, "Real Madrid", "Sevilla", "2026-10-24T19:00:00Z"),
            match(2, "Barça", "Atleti", "2026-10-25T19:00:00Z",
                  home_names=["Barça", "FC Barcelona", "FCB"], away_names=["Atleti", "Club Atlético de Madrid", "ATM"]),
            match(3, "Getafe", "Osasuna", "2026-10-24T14:00:00Z",
                  home_names=["Getafe", "Getafe CF"], away_names=["Osasuna", "CA Osasuna"]),
        ]
        http = FakeHttp(rounds={("4335", "10"): LALIGA_ROUND})
        t = resolver(http)
        t.enrich(events)
        self.assertEqual([e.tvId for e in events], ["2501", "2502", "2504"])
        self.assertEqual(len(http.calls), 1)                      # una petición por jornada
        self.assertEqual(http.calls[0][1], {"id": "4335", "r": "10", "s": "2026-2027"})
        self.assertFalse(any("lookuptv" in url for url, _ in http.calls))   # los canales los pide la app
        self.assertIn("TheSportsDB: OK - 3/3 football events identified", t.log[-1])

    def test_tv_id_is_published_and_channels_stay_empty(self):
        ev = match(1, "Real Madrid", "Sevilla", "2026-10-24T19:00:00Z")
        resolver(FakeHttp(rounds={("4335", "10"): LALIGA_ROUND})).enrich([ev])
        data = core.event_to_json(ev)
        self.assertEqual(data["tvId"], "2501")
        self.assertEqual(data["channels"], [])                    # nunca se inventan canales
        self.assertEqual(core.event_from_json(data).tvId, "2501")

    def test_same_time_different_teams_is_not_confused(self):
        ev = match(5, "Villarreal", "Girona", "2026-10-24T14:00:00Z")
        self.assertEqual(tv.best_match(ev, LALIGA_ROUND["events"], timedelta(hours=3)), "2503")

    def test_wrong_kickoff_time_is_rejected(self):
        ev = match(6, "Real Madrid", "Sevilla", "2026-10-30T19:00:00Z")
        self.assertIsNone(tv.best_match(ev, LALIGA_ROUND["events"], timedelta(hours=3)))

    def test_unknown_teams_are_rejected(self):
        ev = match(7, "Equipo X", "Equipo Y", "2026-10-24T19:00:00Z")
        self.assertIsNone(tv.best_match(ev, LALIGA_ROUND["events"], timedelta(hours=3)))

    def test_name_similarity(self):
        self.assertEqual(tv.name_similarity(["FC Internazionale Milano"], "Inter Milan"), 1.0)
        self.assertEqual(tv.name_similarity(["Man City"], "Manchester City"), 1.0)
        self.assertGreaterEqual(tv.name_similarity(["Bayer 04 Leverkusen"], "Bayer Leverkusen"), 0.9)
        self.assertLess(tv.name_similarity(["Real Madrid"], "Real Sociedad"), 0.9)

    def test_day_fallback_when_round_has_no_data(self):
        ev = match(1, "Real Madrid", "Sevilla", "2026-10-24T19:00:00Z")
        resolver(FakeHttp(rounds={}, day=LALIGA_ROUND)).enrich([ev])
        self.assertEqual(ev.tvId, "2501")

    def test_only_football_and_only_supported_leagues(self):
        f1 = core.Event("formula1-2026-20-race", "formula1", "Fórmula 1", NOW + timedelta(days=1), "GP")
        euro = match(9, "Spain", "France", "2026-10-24T19:00:00Z", code="EC")
        http = FakeHttp()
        resolver(http).enrich([f1, euro])
        self.assertEqual(http.calls, [])
        self.assertIsNone(euro.tvId)


class ReuseAndRateLimitTests(unittest.TestCase):
    def test_known_ids_are_reused_without_requests(self):
        ev = match(1, "Real Madrid", "Sevilla", "2026-10-24T19:00:00Z")
        previous = {"version": 1, "events": [{"id": "football-1", "sport": "futbol", "tvId": "2501",
                                              "start": "2026-10-24T21:00:00+02:00", "title": "x", "channels": []}]}
        http = FakeHttp(rounds={("4335", "10"): LALIGA_ROUND})
        t = resolver(http, previous=previous)
        t.enrich([ev])
        self.assertEqual(ev.tvId, "2501")
        self.assertEqual(http.calls, [])
        self.assertIn("1 from previous agenda", t.log[-1])

    def test_rate_limit_waits_a_minute_and_retries_once(self):
        ev = match(1, "Real Madrid", "Sevilla", "2026-10-24T19:00:00Z")
        waits = []
        http = FakeHttp(rounds={("4335", "10"): [pv.ProviderError(pv.RATE_LIMITED, "HTTP 429"), LALIGA_ROUND]})
        resolver(http, sleep=waits.append).enrich([ev])
        self.assertEqual(ev.tvId, "2501")
        self.assertIn(tv.TheSportsDbTv.RATE_LIMIT_WAIT, waits)
        self.assertEqual(len(http.calls), 2)

    def test_second_rate_limit_stops_until_next_run(self):
        events = [match(1, "Real Madrid", "Sevilla", "2026-10-24T19:00:00Z"),
                  match(2, "A", "B", "2026-10-31T19:00:00Z", matchday=11)]
        limit = pv.ProviderError(pv.RATE_LIMITED, "HTTP 429")
        http = FakeHttp(rounds={("4335", "10"): [limit, limit]})
        t = resolver(http)
        out = t.enrich(events)
        self.assertEqual(out, events)                           # los eventos siguen ahí
        self.assertEqual(len(http.calls), 2)                    # la jornada 11 queda para la próxima
        self.assertIn("PARTIAL - rate_limited", t.log[-1])

    def test_request_budget_and_throttle(self):
        events = [match(i, f"Home {i}", f"Away {i}", "2026-10-24T19:00:00Z", matchday=i + 1) for i in range(20)]
        waits = []
        t = tv.TheSportsDbTv(FakeHttp(), {}, NOW, sleep=waits.append, clock=lambda: 0.0)
        t.MAX_REQUESTS = 4
        t.enrich(events)
        self.assertEqual(t.requests, 4)
        self.assertTrue(waits and all(w >= 2.0 for w in waits))

    def test_thesportsdb_down_never_breaks_events(self):
        ev = match(1, "Real Madrid", "Sevilla", "2026-10-24T19:00:00Z")
        down = pv.ProviderError(pv.PROVIDER_UNAVAILABLE, "HTTP 503")
        t = resolver(FakeHttp(rounds={("4335", "10"): down}, day=down))
        self.assertEqual(t.enrich([ev]), [ev])
        self.assertIsNone(ev.tvId)
        self.assertEqual(ev.channels, [])

    def test_unexpected_exception_is_contained(self):
        class Boom:
            def get_json(self, *a, **k):
                raise RuntimeError("boom")
        ev = match(1, "Real Madrid", "Sevilla", "2026-10-24T19:00:00Z")
        t = resolver(Boom())
        self.assertEqual(t.enrich([ev]), [ev])
        self.assertIn("FAILED - RuntimeError", t.log[-1])


class KeyAndIntegrationTests(unittest.TestCase):
    def test_public_key_by_default_and_secret_when_configured(self):
        http = FakeHttp(rounds={("4335", "10"): LALIGA_ROUND})
        resolver(http).enrich([match(1, "Real Madrid", "Sevilla", "2026-10-24T19:00:00Z")])
        self.assertTrue(http.calls[0][0].startswith(f"{tv.BASE}/123/"))
        http2 = FakeHttp(rounds={("4335", "10"): LALIGA_ROUND})
        t = resolver(http2, env={"THESPORTSDB_API_KEY": "tsdb-SECRET-9"})
        t.enrich([match(1, "Real Madrid", "Sevilla", "2026-10-24T19:00:00Z")])
        self.assertIn("/tsdb-SECRET-9/", http2.calls[0][0])
        self.assertNotIn("tsdb-SECRET-9", "\n".join(t.log))

    def test_generate_publishes_events_even_if_tv_crashes(self):
        class BrokenTv(core.TvProvider):
            def enrich(self, events):
                raise ValueError("tv down")

        class OneProvider(pv.Provider):
            key, name, label, sports = "football", "football-data", "Football-Data", ("futbol",)

            def fetch(self, http, now, env):
                return [match(1, "Real Madrid", "Sevilla", "2026-10-24T19:00:00Z")]

        result = gen.generate([OneProvider()], None, NOW, {}, tv=BrokenTv())
        self.assertTrue(result.published)
        self.assertEqual([e["id"] for e in result.agenda["events"]], ["football-1"])
        self.assertIn("TV: FAILED - ValueError", "\n".join(result.log))
        self.assertNotIn("tv", result.stats)

    def test_ref_metadata_never_reaches_agenda_json(self):
        blob = json.dumps(core.event_to_json(match(1, "Real Madrid", "Sevilla", "2026-10-24T19:00:00Z")))
        for word in ("ref", "matchday", "competitionCode", "homeNames", "2026-2027", "tvId"):
            self.assertNotIn(word, blob)

    def test_football_data_fills_ref(self):
        m = {"id": 1, "utcDate": "2026-10-24T19:00:00Z", "status": "TIMED", "matchday": 10,
             "season": {"startDate": "2026-08-14", "endDate": "2027-05-23"},
             "competition": {"code": "PD"},
             "homeTeam": {"shortName": "Barça", "name": "FC Barcelona", "tla": "FCB"},
             "awayTeam": {"shortName": "Atleti", "name": "Club Atlético de Madrid", "tla": "ATM"}}
        ev = pv.FootballData().to_event(m)
        self.assertEqual(ev.ref["season"], "2026-2027")
        self.assertEqual(ev.ref["matchday"], 10)
        self.assertEqual(ev.ref["homeNames"], ["Barça", "FC Barcelona", "FCB"])


class WindowAndTennisLabelTests(unittest.TestCase):
    def test_today_matches_are_kept_until_midnight_madrid(self):
        now = datetime(2026, 10, 24, 20, 0, tzinfo=timezone.utc)            # 22:00 en Madrid
        morning = datetime(2026, 10, 24, 10, 0, tzinfo=timezone.utc)        # 12:00 de hoy, hace 10 h
        yesterday = datetime(2026, 10, 23, 19, 0, tzinfo=timezone.utc)      # ayer 21:00
        self.assertTrue(core.in_window(morning, now))
        self.assertFalse(core.in_window(yesterday, now))

    def test_just_after_midnight_last_night_is_still_kept(self):
        now = datetime(2026, 10, 24, 22, 30, tzinfo=timezone.utc)           # 00:30 del 25 en Madrid
        late = datetime(2026, 10, 24, 19, 0, tzinfo=timezone.utc)           # 21:00 del 24 (hace 3,5 h)
        early = datetime(2026, 10, 24, 10, 0, tzinfo=timezone.utc)          # 12:00 del 24 (hace 12,5 h)
        self.assertTrue(core.in_window(late, now))
        self.assertFalse(core.in_window(early, now))

    def test_tennis_mislabelled_qualification_main_draw_is_kept_and_cleaned(self):
        lt = pv.LiveTennis()
        ev = lt.to_event({"id": 44514, "tour": "atp", "tournament": "Lyon (France) - Qualification",
                          "round": "ATP Lyon - Semi-finals", "start_time": "2026-10-24T12:10:00Z",
                          "player1_name": "T. Paul", "player2_name": "T. Fritz"}, "atp")
        self.assertEqual(ev.competition, "ATP · Lyon (France) · Semi-finals")
        vienna = lt.to_event({"id": 1, "tour": "atp", "tournament": "Vienna", "round": "ATP Vienna - Semi-finals",
                              "start_time": "2026-10-24T11:35:00Z", "player1_name": "A", "player2_name": "B"}, "atp")
        self.assertEqual(vienna.competition, "ATP · Vienna · Semi-finals")

    def test_tennis_qualifying_rounds_are_excluded(self):
        lt = pv.LiveTennis()
        base = {"id": 2, "tour": "atp", "tournament": "Paris", "start_time": "2026-10-28T10:00:00Z",
                "player1_name": "A", "player2_name": "B"}
        for rnd in ("Qualification Round 1", "Q2", "ATP Paris - Qualification Final", "Qualifying"):
            self.assertIsNone(lt.to_event({**base, "round": rnd}, "atp"), rnd)
        self.assertIsNotNone(lt.to_event({**base, "round": "Round of 32"}, "atp"))


if __name__ == "__main__":
    unittest.main()
