"""
Tests del enriquecimiento de TV con TheSportsDB. Sin Internet: respuestas simuladas con la forma
real de la API (claves "events" y "tvevent", strTimestamp en UTC).
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
    """Responde según el endpoint de TheSportsDB. Un valor Exception se lanza."""

    def __init__(self, rounds=None, tv_by_id=None, day=None):
        self.rounds = rounds if rounds is not None else {}
        self.tv_by_id = tv_by_id if tv_by_id is not None else {}
        self.day = day
        self.calls = []

    def get_json(self, url, headers=None, params=None, label=""):
        self.calls.append((url, dict(params or {})))
        endpoint = url.rsplit("/", 1)[-1]
        if endpoint == "eventsround.php":
            value = self.rounds.get((params["id"], params["r"]), {"events": None})
        elif endpoint == "eventsday.php":
            value = self.day if self.day is not None else {"events": None}
        elif endpoint == "lookuptv.php":
            value = self.tv_by_id.get(params["id"], {"tvevent": None})
        else:
            value = pv.ProviderError(pv.PROVIDER_UNAVAILABLE, "HTTP 404")
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


def tv_row(eid, channel, country):
    return {"idEvent": eid, "strChannel": channel, "strCountry": country, "strEventCountry": "Spain",
            "strTimeStamp": "2026-10-24 19:00:00", "strLogo": "https://r2.example/logo.png"}


LALIGA_ROUND = {"events": [
    tsdb_event("2501", "Real Madrid", "Sevilla", "2026-10-24T19:00:00"),
    tsdb_event("2502", "Barcelona", "Atletico Madrid", "2026-10-25T19:00:00"),
    tsdb_event("2503", "Villarreal", "Girona", "2026-10-24T14:00:00"),
    tsdb_event("2504", "Getafe", "Osasuna", "2026-10-24T14:00:00"),
]}


def enricher(http, env=None, previous=None):
    return tv.TheSportsDbTv(http, env or {}, NOW, previous, sleep=lambda s: None, clock=lambda: 0.0)


def channels(ev):
    return [(c.name, c.note) for c in ev.channels]


class BroadcasterTests(unittest.TestCase):
    """Se aceptan broadcasters de cualquier país: ninguno de estos tests filtra por España."""

    def run_one(self, rows):
        ev = match(1, "Real Madrid", "Sevilla", "2026-10-24T19:00:00Z")
        http = FakeHttp(rounds={("4335", "10"): LALIGA_ROUND}, tv_by_id={"2501": {"tvevent": rows}})
        enricher(http).enrich([ev])
        return ev

    def test_spain(self):
        ev = self.run_one([tv_row("2501", "DAZN LaLiga", "Spain")])
        self.assertEqual(channels(ev), [("DAZN LaLiga", "Spain")])

    def test_united_kingdom(self):
        ev = self.run_one([tv_row("2501", "Sky Sports Main Event", "United Kingdom")])
        self.assertEqual(channels(ev), [("Sky Sports Main Event", "United Kingdom")])

    def test_united_states(self):
        ev = self.run_one([tv_row("2501", "ESPN+", "United States")])
        self.assertEqual(channels(ev), [("ESPN+", "United States")])

    def test_any_other_country(self):
        ev = self.run_one([tv_row("2501", "Match! Futbol 1", "Russia")])
        self.assertEqual(channels(ev), [("Match! Futbol 1", "Russia")])

    def test_several_countries_same_event(self):
        ev = self.run_one([
            tv_row("2501", "DAZN LaLiga", "Spain"), tv_row("2501", "beIN Sports 1", "France"),
            tv_row("2501", "Sky Sports", "United Kingdom"), tv_row("2501", "ESPN", "United States"),
            tv_row("2501", "Sky Sport Calcio", "Italy"), tv_row("2501", "Sportdigital", "Germany"),
        ])
        self.assertEqual(len(ev.channels), 6)
        self.assertEqual({c.note for c in ev.channels},
                         {"Spain", "France", "United Kingdom", "United States", "Italy", "Germany"})

    def test_no_broadcaster(self):
        ev = self.run_one(None)   # {"tvevent": null}
        self.assertEqual(ev.channels, [])
        self.assertEqual(core.event_to_json(ev)["channels"], [])

    def test_duplicates_removed_but_other_country_kept(self):
        ev = self.run_one([
            tv_row("2501", "DAZN", "Spain"), tv_row("2501", "DAZN", "Spain"), tv_row("2501", " dazn ", "spain"),
            tv_row("2501", "DAZN", "Italy"),
        ])
        self.assertEqual(channels(ev), [("DAZN", "Spain"), ("DAZN", "Italy")])

    def test_country_present_goes_to_note_not_name(self):
        ev = self.run_one([tv_row("2501", "DAZN LaLiga", "Spain")])
        data = core.event_to_json(ev)["channels"][0]
        self.assertEqual(data, {"name": "DAZN LaLiga", "note": "Spain"})
        self.assertNotIn("Spain", data["name"])

    def test_country_absent(self):
        ev = self.run_one([{"idEvent": "2501", "strChannel": "Canal+ Sport", "strCountry": ""},
                           {"idEvent": "2501", "strChannel": "TNT Sports"}])
        self.assertEqual(channels(ev), [("Canal+ Sport", None), ("TNT Sports", None)])
        self.assertEqual(core.event_to_json(ev)["channels"][0], {"name": "Canal+ Sport"})

    def test_free_plan_two_broadcasters_is_fine(self):
        ev = self.run_one([tv_row("2501", "Disney+ Hotstar", "India"), tv_row("2501", "VOYO", "Romania")])
        self.assertEqual(len(ev.channels), 2)

    def test_rows_without_channel_or_from_other_event_are_ignored(self):
        ev = self.run_one([{"idEvent": "2501", "strCountry": "Spain"}, tv_row("9999", "Otro", "Peru"),
                           tv_row("2501", "Movistar Plus+", "Spain")])
        self.assertEqual(channels(ev), [("Movistar Plus+", "Spain")])

    def test_logos_are_not_taken(self):
        ev = self.run_one([tv_row("2501", "DAZN LaLiga", "Spain")])
        self.assertIsNone(ev.channels[0].logo)


class MatchingTests(unittest.TestCase):
    def test_matches_by_time_and_teams(self):
        events = [
            match(1, "Real Madrid", "Sevilla", "2026-10-24T19:00:00Z"),
            match(2, "Barça", "Atleti", "2026-10-25T19:00:00Z",
                  home_names=["Barça", "FC Barcelona", "FCB"], away_names=["Atleti", "Club Atlético de Madrid", "ATM"]),
            match(3, "Getafe", "Osasuna", "2026-10-24T14:00:00Z",
                  home_names=["Getafe", "Getafe CF"], away_names=["Osasuna", "CA Osasuna"]),
        ]
        http = FakeHttp(rounds={("4335", "10"): LALIGA_ROUND}, tv_by_id={
            "2501": {"tvevent": [tv_row("2501", "A", "Spain")]},
            "2502": {"tvevent": [tv_row("2502", "B", "France")]},
            "2504": {"tvevent": [tv_row("2504", "C", "Italy")]},
        })
        enricher(http).enrich(events)
        self.assertEqual([channels(e) for e in events], [[("A", "Spain")], [("B", "France")], [("C", "Italy")]])
        rounds = [c for c in http.calls if c[0].endswith("eventsround.php")]
        self.assertEqual(len(rounds), 1)   # una petición por liga y jornada, no por partido
        self.assertEqual(rounds[0][1], {"id": "4335", "r": "10", "s": "2026-2027"})

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
        self.assertGreaterEqual(tv.name_similarity(["Paris Saint-Germain FC"], "Paris SG"), 0.0)
        self.assertLess(tv.name_similarity(["Real Madrid"], "Real Sociedad"), 0.9)

    def test_day_fallback_when_round_has_no_data(self):
        ev = match(1, "Real Madrid", "Sevilla", "2026-10-24T19:00:00Z")
        http = FakeHttp(rounds={}, day=LALIGA_ROUND, tv_by_id={"2501": {"tvevent": [tv_row("2501", "DAZN", "Spain")]}})
        enricher(http).enrich([ev])
        self.assertEqual(channels(ev), [("DAZN", "Spain")])

    def test_only_football_and_only_supported_leagues(self):
        f1 = core.Event("formula1-2026-20-race", "formula1", "Fórmula 1", NOW + timedelta(days=1), "GP")
        euro = match(9, "Spain", "France", "2026-10-24T19:00:00Z", code="EC")
        http = FakeHttp()
        enricher(http).enrich([f1, euro])
        self.assertEqual(http.calls, [])
        self.assertEqual((f1.channels, euro.channels), ([], []))


class FailureAndBudgetTests(unittest.TestCase):
    def test_thesportsdb_down_never_breaks_events(self):
        ev = match(1, "Real Madrid", "Sevilla", "2026-10-24T19:00:00Z")
        down = pv.ProviderError(pv.PROVIDER_UNAVAILABLE, "HTTP 503")
        http = FakeHttp(rounds={("4335", "10"): down}, day=down, tv_by_id={"2501": down})
        t = enricher(http)
        out = t.enrich([ev])
        self.assertEqual(out, [ev])
        self.assertEqual(ev.channels, [])
        self.assertTrue(any("TheSportsDB" in line for line in t.log))

    def test_unexpected_exception_is_contained(self):
        class Boom:
            def get_json(self, *a, **k):
                raise RuntimeError("boom")
        ev = match(1, "Real Madrid", "Sevilla", "2026-10-24T19:00:00Z")
        t = enricher(Boom())
        self.assertEqual(t.enrich([ev]), [ev])
        self.assertIn("FAILED - RuntimeError", t.log[-1])

    def test_lookup_failure_keeps_previous_channels(self):
        ev = match(1, "Real Madrid", "Sevilla", "2026-10-24T19:00:00Z")
        previous = {"version": 1, "events": [{
            "id": "football-1", "sport": "futbol", "start": "2026-10-24T21:00:00+02:00", "title": "x",
            "channels": [{"name": "DAZN LaLiga", "note": "Spain"}, {"name": "beIN Sports", "note": "France"}]}]}
        http = FakeHttp(rounds={("4335", "10"): LALIGA_ROUND},
                        tv_by_id={"2501": pv.ProviderError(pv.TIMEOUT, "timeout")})
        enricher(http, previous=previous).enrich([ev])
        self.assertEqual(channels(ev), [("DAZN LaLiga", "Spain"), ("beIN Sports", "France")])

    def test_rate_limit_stops_requests(self):
        events = [match(i, "Real Madrid", "Sevilla", "2026-10-24T19:00:00Z") for i in range(3)]
        http = FakeHttp(rounds={("4335", "10"): pv.ProviderError(pv.RATE_LIMITED, "HTTP 429")})
        t = enricher(http)
        t.enrich(events)
        self.assertEqual(len(http.calls), 1)
        self.assertIn("rate_limited", t.log[-1])

    def test_request_budget_and_throttle(self):
        events = [match(i, f"Home {i}", f"Away {i}", "2026-10-24T19:00:00Z", matchday=i + 1) for i in range(10)]
        waits = []
        t = tv.TheSportsDbTv(FakeHttp(), {}, NOW, sleep=waits.append, clock=lambda: 0.0)
        t.MAX_REQUESTS = 4
        t.enrich(events)
        self.assertEqual(t.requests, 4)
        self.assertTrue(waits and all(w >= 2.0 for w in waits))   # < 30 peticiones por minuto

    def test_far_future_matches_are_not_looked_up(self):
        ev = match(1, "Real Madrid", "Sevilla", "2026-11-05T19:00:00Z")
        http = FakeHttp(rounds={("4335", "10"): LALIGA_ROUND})
        enricher(http).enrich([ev])
        self.assertEqual(http.calls, [])


class KeyAndIntegrationTests(unittest.TestCase):
    def test_public_key_by_default_and_secret_when_configured(self):
        http = FakeHttp(rounds={("4335", "10"): LALIGA_ROUND})
        ev = match(1, "Real Madrid", "Sevilla", "2026-10-24T19:00:00Z")
        enricher(http).enrich([ev])
        self.assertTrue(http.calls[0][0].startswith(f"{tv.BASE}/123/"))
        http2 = FakeHttp(rounds={("4335", "10"): LALIGA_ROUND})
        t = enricher(http2, env={"THESPORTSDB_API_KEY": "tsdb-SECRET-9"})
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
        self.assertEqual(result.agenda["events"][0]["channels"], [])
        self.assertIn("TV: FAILED - ValueError", "\n".join(result.log))
        self.assertNotIn("tv", result.stats)   # la TV no altera el estado de los deportes

    def test_ref_metadata_never_reaches_agenda_json(self):
        ev = match(1, "Real Madrid", "Sevilla", "2026-10-24T19:00:00Z")
        blob = json.dumps(core.event_to_json(ev))
        for word in ("ref", "matchday", "competitionCode", "homeNames", "2026-2027"):
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
        self.assertEqual(ev.home, "Barça")


if __name__ == "__main__":
    unittest.main()
