"""Tests del scraper de agenda. Ejecutar: python -m unittest discover -s tools/agenda"""
import json
import os
import sys
import tempfile
import unittest
from datetime import datetime

sys.path.insert(0, os.path.dirname(__file__))
import scrape_agenda as sa  # noqa: E402

NOW = datetime(2026, 10, 24, 12, 0, tzinfo=sa.TZ)

# Estructura con clases semánticas (tabla por día, cabecera con fecha, celdas con clase).
HTML_CLASSES = """
<html><body>
<table class="tablaPrincipal">
 <tr class="cabeceraTabla"><td colspan="5">Mañana sábado, 24/10/2026</td></tr>
 <tr>
  <td class="hora">18:00</td>
  <td class="detalles"><ul><li><span>La Liga EA Sports</span></li></ul></td>
  <td class="local"><img data-src="/img/escudos/real-madrid.png"><span>Real Madrid</span></td>
  <td class="visitante"><img src="https://cdn.example/sevilla.png"><span>Sevilla</span></td>
  <td class="canales"><ul class="listaCanales">
     <li>DAZN LaLiga (M55 O113)</li>
     <li>DAZN (Ver en directo)</li>
     <li>DAZN LaLiga Bar (M148)</li>
     <li>M+ LaLiga TV (M54)</li>
  </ul></td>
 </tr>
 <tr>
  <td class="hora">21:00</td>
  <td class="detalles"><ul><li>Premier League</li></ul></td>
  <td class="local">Arsenal</td>
  <td class="visitante">Chelsea</td>
  <td class="canales"><ul><li>DAZN 1 (M72)</li><li>Canal por confirmar</li></ul></td>
 </tr>
</table>
<table class="tablaPrincipal">
 <tr class="cabeceraTabla"><td colspan="5">Domingo, 25/10/2026</td></tr>
 <tr><td class="hora">16:15</td><td class="detalles"><ul><li>Bundesliga</li></ul></td>
  <td class="local">Stuttgart</td><td class="visitante">Borussia M'gladbach</td>
  <td class="canales"><ul><li>DAZN (Ver en directo)</li><li>LaLiga TV M3</li></ul></td></tr>
</table>
</body></html>
"""

# Estructura sin clases: fecha en <h2>, filas-separador con la competición.
HTML_PLAIN = """
<h2>Partidos de hoy sábado, 24/10/2026</h2>
<table>
 <tr><td colspan="4">NBA</td></tr>
 <tr><td>21:00</td><td>Lakers</td><td>Celtics</td><td><ul><li>Movistar Deportes (M63)</li></ul></td></tr>
 <tr><td colspan="4">Fórmula 1</td></tr>
 <tr><td>23:00</td><td>GP de Singapur · Clasificación</td><td><ul><li>DAZN F1 (M58)</li></ul></td></tr>
</table>
"""

HTML_JSONLD = """
<script type="application/ld+json">
{"@context":"https://schema.org","@graph":[
 {"@type":"SportsEvent","name":"Alcaraz - Sinner","startDate":"2026-10-24T20:15:00",
  "competitor":[{"@type":"Person","name":"Alcaraz"},{"@type":"Person","name":"Sinner"}],
  "superEvent":{"name":"ATP 500 Barcelona"}}
]}
</script>
"""


class ChannelTests(unittest.TestCase):
    def test_parse_channel(self):
        c = sa.parse_channel("DAZN LaLiga (M55 O113)")
        self.assertEqual((c.name, c.dial, c.note), ("DAZN LaLiga", "M55 O113", None))
        c = sa.parse_channel("DAZN (Ver en directo)")
        self.assertEqual((c.name, c.dial, c.note), ("DAZN", None, "Ver en directo"))
        c = sa.parse_channel("LaLiga TV M3")
        self.assertEqual(c.name, "LaLiga TV M3")
        self.assertIsNone(sa.parse_channel("DAZN 1 Bar (M148)"))
        self.assertIsNone(sa.parse_channel("Canal por confirmar"))
        c = sa.parse_channel("Gol Play PPV")
        self.assertEqual((c.name, c.note), ("Gol Play", "PPV"))


class TableTests(unittest.TestCase):
    def test_classes_structure(self):
        events, strategy = sa.parse_page(HTML_CLASSES, "futbol", sa.BASE, NOW)
        self.assertEqual(strategy, "html")
        self.assertEqual(len(events), 3)
        rm = events[0]
        self.assertEqual((rm.home, rm.away, rm.competition), ("Real Madrid", "Sevilla", "La Liga EA Sports"))
        self.assertEqual(rm.start, "2026-10-24T18:00:00+02:00")
        self.assertEqual([c.name for c in rm.channels], ["DAZN LaLiga", "DAZN", "M+ LaLiga TV"])
        self.assertEqual(rm.homeLogo, "https://www.futbolenlatv.es/img/escudos/real-madrid.png")
        self.assertEqual(rm.awayLogo, "https://cdn.example/sevilla.png")
        self.assertEqual([c.name for c in events[1].channels], ["DAZN 1"])
        # Cambio de hora (25/10/2026 → horario de invierno, +01:00)
        self.assertEqual(events[2].start, "2026-10-25T16:15:00+01:00")
        self.assertEqual(events[2].away, "Borussia M'gladbach")

    def test_plain_structure(self):
        events, _ = sa.parse_page(HTML_PLAIN, "baloncesto", sa.BASE, NOW)
        self.assertEqual(len(events), 2)
        nba, f1 = events
        self.assertEqual((nba.home, nba.away, nba.competition), ("Lakers", "Celtics", "NBA"))
        self.assertEqual(nba.channels[0].dial, "M63")
        self.assertIsNone(f1.home)
        self.assertEqual(f1.title, "GP de Singapur · Clasificación")
        self.assertEqual(f1.competition, "Fórmula 1")

    def test_jsonld_fallback_is_utc(self):
        events, strategy = sa.parse_page(HTML_JSONLD, "tenis", sa.BASE, NOW)
        self.assertEqual(strategy, "jsonld")
        self.assertEqual(events[0].home, "Alcaraz")
        self.assertEqual(events[0].competition, "ATP 500 Barcelona")
        self.assertEqual(events[0].start, "2026-10-24T22:15:00+02:00")

    def test_ids_are_stable_and_unique(self):
        a, _ = sa.parse_page(HTML_CLASSES, "futbol", sa.BASE, NOW)
        b, _ = sa.parse_page(HTML_CLASSES, "futbol", sa.BASE, NOW)
        self.assertEqual([e.id for e in a], [e.id for e in b])
        self.assertEqual(len({e.id for e in a}), len(a))


class LogoTests(unittest.TestCase):
    HTML = """
    <table><tr><td>Sábado, 24/10/2026</td></tr>
    <tr><td class="hora">18:00</td>
     <td class="detalles"><img src="/img/comp/laliga.png"><span>La Liga EA Sports</span></td>
     <td class="local">Real Madrid</td><td class="visitante">Sevilla</td>
     <td class="canales"><ul><li><img src="/img/canales/dazn-laliga.png" alt="DAZN LaLiga"></li>
       <li><img src="/img/canales/mlaliga.png"> M+ LaLiga TV (M54)</li></ul></td></tr></table>"""

    def test_logos_from_page(self):
        ev = sa.parse_page(self.HTML, "futbol", sa.BASE, NOW)[0][0]
        self.assertEqual(ev.competitionLogo, "https://www.futbolenlatv.es/img/comp/laliga.png")
        self.assertEqual([(c.name, c.logo) for c in ev.channels], [
            ("DAZN LaLiga", "https://www.futbolenlatv.es/img/canales/dazn-laliga.png"),
            ("M+ LaLiga TV", "https://www.futbolenlatv.es/img/canales/mlaliga.png"),
        ])

    def test_overrides_win(self):
        ev = sa.parse_page(self.HTML, "futbol", sa.BASE, NOW)[0][0]
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "logos.json")
            with open(path, "w", encoding="utf-8") as fh:
                json.dump({"teams": {"real madrid": "https://mis.logos/rm.png", "Sevilla": ""},
                           "channels": {"DAZN LALIGA": "https://mis.logos/dazn.png"}}, fh)
            sa.apply_logos([ev], sa.load_logo_overrides(path))
        self.assertEqual(ev.homeLogo, "https://mis.logos/rm.png")
        self.assertIsNone(ev.awayLogo)  # URL vacía = no sobrescribe
        self.assertEqual(ev.channels[0].logo, "https://mis.logos/dazn.png")
        self.assertEqual(ev.competitionLogo, "https://www.futbolenlatv.es/img/comp/laliga.png")


class OutputTests(unittest.TestCase):
    def test_main_with_local_html(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = os.path.join(tmp, "page.html")
            out = os.path.join(tmp, "agenda.json")
            with open(src, "w", encoding="utf-8") as fh:
                # Fechas relativas a hoy para que el filtro de ventana temporal no las descarte.
                today = datetime.now(sa.TZ).strftime("%d/%m/%Y")
                fh.write(HTML_CLASSES.replace("24/10/2026", today).replace("25/10/2026", today))
            self.assertEqual(sa.main(["--html", src, "--out", out]), 0)
            with open(out, encoding="utf-8") as fh:
                data = json.load(fh)
            self.assertEqual(data["version"], 1)
            self.assertEqual(data["timezone"], "Europe/Madrid")
            self.assertTrue(data["events"])
            first = data["events"][0]
            for key in ("id", "sport", "start", "title", "channels"):
                self.assertIn(key, first)
            arsenal = next(e for e in data["events"] if e.get("home") == "Arsenal")
            self.assertNotIn("homeLogo", arsenal)  # los nulos no se escriben

    def test_empty_page_fails_loudly(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = os.path.join(tmp, "page.html")
            with open(src, "w") as fh:
                fh.write("<html><body>Nada</body></html>")
            self.assertEqual(sa.main(["--html", src, "--out", os.path.join(tmp, "a.json")]), 2)


if __name__ == "__main__":
    unittest.main()
