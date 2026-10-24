"""
Tests de la fuente de TV /deporte. Sin Internet: HTML de fixtures y respuestas simuladas.
Ejecutar: python -m unittest discover -s tools/agenda -v
"""
import os
import sys
import unittest
from datetime import datetime, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import agenda_core as core  # noqa: E402
import providers as pv  # noqa: E402
import tv_futbolenlatv as tv  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
NOW = datetime(2026, 10, 24, 10, 0, tzinfo=timezone.utc)
ROBOTS_OK = "User-agent: *\nDisallow: /admin/\nDisallow: /buscar\n"


def fixture(name):
    with open(os.path.join(HERE, "fixtures", name), encoding="utf-8") as fh:
        return fh.read()


def utc(s):
    return datetime.fromisoformat(s.replace("Z", "+00:00"))


def football(mid, home, away, start, comp="LaLiga", home_names=None, away_names=None):
    return core.Event(id=f"football-{mid}", sport="futbol", competition=comp, start=utc(start),
                      title=f"{home} - {away}", home=home, away=away,
                      ref={"homeNames": home_names or [home], "awayNames": away_names or [away]})


def tennis(fid, p1, p2, start):
    return core.Event(id=f"tennis-{fid}", sport="tenis", competition="ATP", start=utc(start),
                      title=f"{p1} - {p2}", home=p1, away=p2)


def motor(eid, sport, start, title):
    return core.Event(id=eid, sport=sport, competition="Fórmula 1" if sport == "formula1" else "MotoGP",
                      start=utc(start), title=title)


def ufc(slug, red, blue, start, name="UFC 333", tbd=False):
    return core.Event(id=f"ufc-{slug}", sport="ufc", competition=name, start=utc(start),
                      title=f"{red} - {blue}", home=red, away=blue, timeTbd=tbd)


class FakeHttp:
    def __init__(self, robots=ROBOTS_OK, page=None):
        self.robots, self.page, self.calls = robots, page, []

    def get_text(self, url, headers=None, label=""):
        self.calls.append((url, dict(headers or {})))
        value = self.robots if url.endswith("/robots.txt") else self.page
        if isinstance(value, Exception):
            raise value
        return value


ROWS = None


def rows():
    global ROWS
    if ROWS is None:
        ROWS = tv.parse_listing(fixture("deporte_tablas.html"), NOW)
    return ROWS


class ParserTests(unittest.TestCase):
    def test_rows_dates_sports_and_competitions(self):
        r = rows()
        self.assertEqual(len(r), 15)
        first = r[0]
        self.assertEqual(first.start.isoformat(), "2026-10-24T14:00:00+02:00")
        self.assertEqual((first.sport, first.competition, first.home, first.away),
                         ("futbol", "LaLiga EA Sports", "Rayo Vallecano", "Elche"))
        self.assertEqual({x.sport for x in r}, {"futbol", "tenis", "formula1", "motogp", "ufc", "motor", "otro"})
        self.assertEqual(r[-1].start.date().isoformat(), "2026-10-25")   # cabecera de fecha

    def test_multiple_channels_dials_notes_and_bar_channels(self):
        chans = [(c.name, c.dial, c.note) for c in rows()[0].channels]
        self.assertEqual(chans, [("M+ LALIGA", "M54 O110", None), ("M+ LALIGA HDR", "M454", None),
                                 ("LaLiga TV Bar", None, None)])        # "por confirmar" se descarta
        dazn = [(c.name, c.dial, c.note) for c in rows()[1].channels]
        self.assertIn(("DAZN LaLiga", "M55 O113", None), dazn)
        self.assertIn(("DAZN", None, "Ver en directo"), dazn)

    def test_channel_logos_but_flags_are_never_logos(self):
        self.assertEqual(rows()[0].channels[0].logo, "https://static.futbolenlatv.com/img/canales/mlaliga.png")
        paul = next(x for x in rows() if x.sport == "tenis" and "Paul" in x.title)
        self.assertEqual((paul.home, paul.away), ("T. Paul", "T. Fritz"))
        logos = [c.logo for c in paul.channels]
        self.assertIn("https://static.futbolenlatv.com/img/canales/tennis.png", logos)   # canal solo con imagen
        self.assertFalse(any(l and ("bandera" in l or "flags" in l) for l in logos))

    def test_section_layout_with_text_dates(self):
        r = tv.parse_listing(fixture("deporte_secciones.html"), NOW)
        self.assertEqual(len(r), 5)
        self.assertEqual(r[0].start.isoformat(), "2026-10-26T19:00:00+01:00")    # CET tras el cambio de hora
        self.assertEqual([x.sport for x in r], ["futbol", "tenis", "baloncesto", "baloncesto", "baloncesto"])

    def test_empty_or_changed_html_gives_no_rows(self):
        self.assertEqual(tv.parse_listing("", NOW), [])
        self.assertEqual(tv.parse_listing("<html><body><div>Nueva web</div></body></html>", NOW), [])
        self.assertEqual(tv.parse_listing("<table><tr><td>sin hora</td><td>nada</td></tr></table>", NOW), [])

    def test_script_contents_are_ignored(self):
        self.assertFalse(any("99:99" in x.title for x in rows()))


class RealPageTests(unittest.TestCase):
    """Extracto REAL saneado de /deporte (24/10/2026)."""

    @classmethod
    def setUpClass(cls):
        cls.rows = tv.parse_listing(fixture("deporte_real_extracto.html"), NOW)

    def find(self, text):
        return next(r for r in self.rows if text in r.title)

    def test_all_rows_with_date_and_time(self):
        self.assertEqual(len(self.rows), 35)
        first = self.rows[0]
        self.assertEqual(first.start.isoformat(), "2026-10-24T00:50:00+02:00")
        self.assertTrue(all(r.start.date().isoformat() == "2026-10-24" for r in self.rows))

    def test_sport_from_competition_icon(self):
        self.assertEqual(self.find("Peñarol").sport, "futbol")
        self.assertEqual(self.find("Boston Celtics").sport, "baloncesto")
        self.assertEqual(self.find("Bravos de Margarita").sport, "otro")            # béisbol
        self.assertEqual(self.find("Independiente Futsal").sport, "futsal")         # fútbol sala ≠ fútbol
        golf = [r for r in self.rows if r.competition == "BMW Ladies Championship"][0]
        self.assertEqual(golf.sport, "otro")                                         # "Championship" no es Champions

    def test_f1_academy_and_moto2_moto3_are_not_f1_or_motogp(self):
        self.assertEqual(self.find("Opening Race").sport, "motor")                  # F1 Academy
        moto = {r.competition: r.sport for r in self.rows if "Phillip Island" in r.title}
        self.assertEqual(moto, {"MotoGP": "motogp", "Moto3": "motor", "Moto2": "motor"})

    def test_single_event_cell_and_session(self):
        quali = [r for r in self.rows if r.competition == "MotoGP" and "Clasificación" in r.title][0]
        self.assertEqual(quali.title, "G.P. Australia (Phillip Island) Clasificación")
        self.assertEqual(quali.start.strftime("%H:%M"), "01:50")
        self.assertEqual([(c.name, c.dial, c.note) for c in quali.channels],
                         [("MotoGP Videopass", None, None), ("DAZN", None, "Ver en directo"), ("DAZN MotoGP", "M71 O105", None)])

    def test_movistar_numeric_dials_and_notes(self):
        mlb = self.find("Milwaukee Brewers")
        self.assertEqual([(c.name, c.dial, c.note) for c in mlb.channels],
                         [("M+ Vamos", "8 y 50", None), ("M+ #Vamos Bar", "307", None), ("M+ Deportes", "63", None)])
        futsal = self.find("Independiente Futsal")
        self.assertEqual([(c.name, c.note) for c in futsal.channels], [("LPF Play", "plan plus")])

    def test_team_images_and_flags_are_never_channel_logos(self):
        for r in self.rows:
            for c in r.channels:
                self.assertIsNone(c.logo)            # en el extracto los canales no traen imagen

    def test_matching_motogp_qualifying_on_real_rows(self):
        ev = motor("motogp-2026-australia-qualifying", "motogp", "2026-10-23T23:50:00Z", "Australian Grand Prix · Clasificación")
        got = tv.match_channels([ev], self.rows)
        self.assertEqual([c.name for c in got[ev.id]], ["MotoGP Videopass", "DAZN", "DAZN MotoGP"])
        sprint = motor("motogp-2026-australia-sprint", "motogp", "2026-10-24T04:00:00Z", "Australian Grand Prix · Sprint")
        self.assertEqual([c.name for c in tv.match_channels([sprint], self.rows)[sprint.id]],
                         ["MotoGP Videopass", "DAZN", "DAZN MotoGP"])

    def test_moto2_never_matches_motogp(self):
        # Clasificación de Moto2 a las 04:40: una clasificación de MotoGP a esa hora NO debe tomar sus canales.
        ev = motor("motogp-2026-x-qualifying", "motogp", "2026-10-24T02:40:00Z", "X · Clasificación")
        self.assertEqual(tv.match_channels([ev], self.rows), {})


class MatchingTests(unittest.TestCase):
    def match(self, *events):
        return tv.match_channels(list(events), rows())

    def test_football_with_spanish_abbreviations(self):
        ev = football(1, "Atleti", "Deportivo", "2026-10-24T19:00:00Z",
                      home_names=["Atleti", "Club Atlético de Madrid", "ATM"], away_names=["Deportivo", "RC Deportivo"])
        got = self.match(ev)
        self.assertEqual([c.name for c in got["football-1"]], ["DAZN LaLiga", "DAZN", "Orange Fútbol 1"])

    def test_football_same_time_other_match_not_confused(self):
        chelsea = football(2, "Chelsea", "Tottenham", "2026-10-24T19:00:00Z", comp="Premier League",
                           home_names=["Chelsea", "Chelsea FC"], away_names=["Tottenham", "Tottenham Hotspur FC"])
        self.assertEqual([c.name for c in self.match(chelsea)["football-2"]], ["DAZN 1"])

    def test_paris_clubs_at_same_time_are_kept_apart(self):
        psg = football(3, "PSG", "Marseille", "2026-10-25T15:00:00Z", comp="Ligue 1",
                       home_names=["PSG", "Paris Saint-Germain FC"], away_names=["Marseille", "Olympique de Marseille"])
        pfc = football(4, "Paris FC", "Lyon", "2026-10-25T15:00:00Z", comp="Ligue 1",
                       home_names=["Paris FC"], away_names=["Lyon", "Olympique Lyonnais"])
        got = self.match(psg, pfc)
        self.assertEqual([c.name for c in got["football-3"]], ["DAZN 2"])
        self.assertEqual([c.name for c in got["football-4"]], ["beIN SPORTS"])

    def test_football_wrong_time_is_rejected(self):
        ev = football(5, "Rayo Vallecano", "Elche", "2026-10-24T16:00:00Z")   # 18:00 en Madrid, TV dice 14:00
        self.assertEqual(self.match(ev), {})

    def test_doubtful_match_is_rejected(self):
        ev = football(6, "Real Madrid", "Elche", "2026-10-24T12:00:00Z")      # solo coincide un equipo
        self.assertEqual(self.match(ev), {})

    def test_tennis_by_surnames_and_initials(self):
        ev = tennis(44514, "T. Paul", "T. Fritz", "2026-10-24T10:10:00Z")
        self.assertEqual([c.name for c in self.match(ev)["tennis-44514"]], ["Movistar Plus+", "Tennis Channel"])
        ev2 = tennis(44524, "D. Vallejo", "L. Sonego", "2026-10-24T11:35:00Z")
        self.assertEqual([c.name for c in self.match(ev2)["tennis-44524"]], ["Movistar Plus+ 2"])

    def test_tennis_other_players_rejected(self):
        ev = tennis(1, "C. Alcaraz", "J. Sinner", "2026-10-24T10:10:00Z")
        self.assertEqual(self.match(ev), {})

    def test_formula1_by_session_kind_and_time(self):
        quali = motor("formula1-2026-20-qualifying", "formula1", "2026-10-24T20:00:00Z", "Mexico City Grand Prix · Clasificación")
        race = motor("formula1-2026-20-race", "formula1", "2026-10-24T20:00:00Z", "Mexico City Grand Prix · Carrera")
        got = self.match(quali, race)
        self.assertEqual([c.name for c in got["formula1-2026-20-qualifying"]], ["DAZN F1", "DAZN"])
        self.assertNotIn("formula1-2026-20-race", got)                         # la sesión no coincide

    def test_f1_academy_never_matches_formula1(self):
        race = motor("formula1-2026-20-race", "formula1", "2026-10-24T19:50:00Z", "Mexico City Grand Prix · Carrera")
        self.assertEqual(self.match(race), {})                                  # "Race 1" de F1 Academy

    def test_motogp_race(self):
        ev = motor("motogp-2026-malaysia-race", "motogp", "2026-10-25T05:00:00Z", "Malaysian Grand Prix · Carrera")
        self.assertEqual([c.name for c in self.match(ev)["motogp-2026-malaysia-race"]], ["DAZN MotoGP", "DAZN"])

    def test_ufc_by_number_and_fighters(self):
        ev = ufc("ufc-333", "Alexander Volkanovski", "Movsar Evloev", "2026-10-25T02:00:00Z")
        got = self.match(ev)
        self.assertEqual([(c.name, c.note) for c in got["ufc-ufc-333"]], [("DAZN", "PPV"), ("UFC Fight Pass", None)])

    def test_ufc_other_event_rejected(self):
        ev = ufc("ufc-334", "Islam Makhachev", "Arman Tsarukyan", "2026-10-25T02:00:00Z", name="UFC 334")
        self.assertEqual(self.match(ev), {})


class SourceTests(unittest.TestCase):
    def events(self):
        return [football(1, "Atleti", "Deportivo", "2026-10-24T19:00:00Z",
                         home_names=["Atleti", "Club Atlético de Madrid"]),
                tennis(9, "C. Alcaraz", "J. Sinner", "2026-10-26T14:30:00Z")]

    def test_ok_single_download_and_identifies_itself(self):
        http = FakeHttp(page=fixture("deporte_tablas.html"))
        src = tv.FutbolEnLaTv(http, NOW)
        evs = src.enrich(self.events())
        self.assertEqual([u for u, _ in http.calls],
                         ["https://www.futbolenlatv.es/robots.txt", "https://www.futbolenlatv.es/deporte"])
        self.assertTrue(all(h["User-Agent"] == "MiVisor/4" for _, h in http.calls))
        self.assertEqual(src.status, "ok")
        self.assertEqual(len(evs[0].channels), 3)
        self.assertEqual(evs[1].channels, [])                                 # sin match: vacío
        self.assertEqual(src.log[-1], "TV /deporte: OK - 15 candidate rows - 1 matches")

    def test_robots_disallowed_never_downloads(self):
        http = FakeHttp(robots="User-agent: *\nDisallow: /deporte\n", page=fixture("deporte_tablas.html"))
        src = tv.FutbolEnLaTv(http, NOW)
        evs = src.enrich(self.events())
        self.assertEqual(len(http.calls), 1)                                  # solo robots.txt
        self.assertEqual((src.status, src.error), ("unavailable", "robots_disallowed"))
        self.assertEqual(len(evs), 2)                                         # la agenda sigue intacta

    def test_robots_for_our_agent(self):
        robots = "User-agent: MiVisor\nDisallow: /deporte\n\nUser-agent: *\nAllow: /\n"
        self.assertFalse(tv.robots_allows(robots, "https://www.futbolenlatv.es/deporte"))
        self.assertTrue(tv.robots_allows(ROBOTS_OK, "https://www.futbolenlatv.es/deporte"))

    def test_missing_robots_404_allows(self):
        http = FakeHttp(robots=pv.ProviderError(pv.PROVIDER_UNAVAILABLE, "HTTP 404", status=404),
                        page=fixture("deporte_tablas.html"))
        src = tv.FutbolEnLaTv(http, NOW)
        src.enrich(self.events())
        self.assertEqual(src.status, "ok")

    def test_robots_server_error_does_not_download(self):
        http = FakeHttp(robots=pv.ProviderError(pv.PROVIDER_UNAVAILABLE, "HTTP 503", status=503))
        src = tv.FutbolEnLaTv(http, NOW)
        src.enrich(self.events())
        self.assertEqual(len(http.calls), 1)
        self.assertEqual(src.status, "unavailable")

    def test_timeout(self):
        src = tv.FutbolEnLaTv(FakeHttp(page=pv.ProviderError(pv.TIMEOUT, "timeout")), NOW)
        src.enrich(self.events())
        self.assertEqual((src.status, src.error), ("unavailable", "timeout"))

    def test_changed_or_empty_html_is_parse_error(self):
        for page in ("", "<html><body><p>Nuevo diseño</p></body></html>"):
            src = tv.FutbolEnLaTv(FakeHttp(page=page), NOW)
            src.enrich(self.events())
            self.assertEqual((src.status, src.error), ("unavailable", "parse_error"), page)

    def test_failure_keeps_previous_channels_as_stale(self):
        previous = {"version": 1, "events": [{"id": "football-1", "sport": "futbol", "title": "x",
                                              "start": "2026-10-24T21:00:00+02:00",
                                              "channels": [{"name": "DAZN LaLiga", "dial": "M55"}]}]}
        src = tv.FutbolEnLaTv(FakeHttp(page=pv.ProviderError(pv.TIMEOUT, "timeout")), NOW, previous)
        evs = src.enrich(self.events())
        self.assertEqual(src.status, "stale")
        self.assertEqual([(c.name, c.dial) for c in evs[0].channels], [("DAZN LaLiga", "M55")])

    def test_deporte_overrides_other_tv_sources(self):
        evs = self.events()
        evs[0].channels = [core.Channel("Otro canal", note="India")]
        tv.FutbolEnLaTv(FakeHttp(page=fixture("deporte_tablas.html")), NOW).enrich(evs)
        self.assertEqual(evs[0].channels[0].name, "DAZN LaLiga")


if __name__ == "__main__":
    unittest.main()
