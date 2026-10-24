"""Tests de UFC (Cito). Sin Internet. Ejecutar: python -m unittest discover -s tools/agenda -v"""
import os
import sys
import unittest
from datetime import datetime, timedelta, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import agenda_core as core  # noqa: E402
import generate_agenda as gen  # noqa: E402
import provider_ufc as ufc  # noqa: E402
import providers as pv  # noqa: E402

NOW = datetime(2026, 10, 23, 10, 17, tzinfo=timezone.utc)
ENV = {"CITO_API_KEY": "cito-SECRET-7"}

UPCOMING = {"ok": True, "data": [
    {"slug": "ufc-333", "name": "UFC 333", "startTime": "2026-10-25T02:00:00Z", "venue": "T-Mobile Arena"},
    {"slug": "ufc-fight-night-oct-31", "name": "UFC Fight Night: Royval vs Kape", "date": "2026-10-31"},
    {"slug": "ufc-340", "name": "UFC 340", "startTime": "2027-01-20T02:00:00Z"},            # fuera de la ventana
], "meta": {}}

BOUTS_333 = {"ok": True, "data": [
    {"boutOrder": 1, "isMainEvent": True, "weightClass": "Featherweight", "cardSection": "Main Card",
     "fighters": [
         {"corner": "red", "fighterSlug": "alexander-volkanovski", "name": "Alexander Volkanovski",
          "images": {"imageUrl": "https://img.cito.example/volk.png", "flag": "https://flags.example/au.png"}},
         {"corner": "blue", "fighterSlug": "movsar-evloev", "name": "Movsar Evloev",
          "images": {"headshotUrl": "https://img.cito.example/evloev.png"}}]},
    {"boutOrder": 2, "weightClass": "Lightweight", "cardSection": "Main Card",
     "red": "mateusz-gamrot", "blue": "rafael-fiziev"},
]}

BOUTS_FN = {"ok": True, "data": {"bouts": [
    {"isMainEvent": True, "fighters": [{"corner": "red", "fighterSlug": "brandon-royval"},
                                       {"corner": "blue", "fighterSlug": "manel-kape"}]}]}}


class FakeHttp:
    def __init__(self, routes):
        self.routes, self.calls = routes, []

    def get_json(self, url, headers=None, params=None, label=""):
        self.calls.append((url, dict(headers or {})))
        for prefix in sorted(self.routes, key=len, reverse=True):
            if url.startswith(prefix):
                value = self.routes[prefix]
                if isinstance(value, Exception):
                    raise value
                return value
        raise pv.ProviderError(pv.PROVIDER_UNAVAILABLE, "HTTP 404", status=404)


def routes(**over):
    base = {
        f"{ufc.CitoUfc.BASE}/events/upcoming": UPCOMING,
        f"{ufc.CitoUfc.BASE}/events/ufc-333/bouts": BOUTS_333,
        f"{ufc.CitoUfc.BASE}/events/ufc-fight-night-oct-31/bouts": BOUTS_FN,
    }
    base.update(over)
    return base


class CitoTests(unittest.TestCase):
    def test_event_main_event_and_card(self):
        http = FakeHttp(routes())
        p = ufc.CitoUfc()
        events = {e.id: e for e in p.fetch(http, NOW, ENV)}
        self.assertEqual(set(events), {"ufc-ufc-333", "ufc-ufc-fight-night-oct-31"})
        ev = events["ufc-ufc-333"]
        self.assertEqual((ev.sport, ev.competition, ev.home, ev.away),
                         ("ufc", "UFC 333", "Alexander Volkanovski", "Movsar Evloev"))
        self.assertEqual(ev.title, "Alexander Volkanovski - Movsar Evloev")
        self.assertEqual(ev.start, datetime(2026, 10, 25, 2, 0, tzinfo=timezone.utc))
        self.assertFalse(ev.timeTbd)
        self.assertEqual(len(ev.card), 2)
        self.assertEqual(ev.card[1], {"red": "Mateusz Gamrot", "blue": "Rafael Fiziev", "weightClass": "Lightweight",
                                      "section": "Main Card", "order": 2, "main": False})
        for url, headers in http.calls:
            self.assertEqual(headers["x-api-key"], ENV["CITO_API_KEY"])

    def test_fighter_images_never_flags(self):
        ev = next(e for e in ufc.CitoUfc().fetch(FakeHttp(routes()), NOW, ENV) if e.id == "ufc-ufc-333")
        self.assertEqual(ev.homeImage, "https://img.cito.example/volk.png")
        self.assertEqual(ev.awayImage, "https://img.cito.example/evloev.png")
        data = core.event_to_json(ev)
        self.assertEqual((data["homeImage"], data["awayImage"]), (ev.homeImage, ev.awayImage))
        self.assertNotIn("flags.example", str(data))

    def test_without_images_and_names_from_slugs(self):
        ev = next(e for e in ufc.CitoUfc().fetch(FakeHttp(routes()), NOW, ENV) if e.id == "ufc-ufc-fight-night-oct-31")
        self.assertEqual((ev.home, ev.away), ("Brandon Royval", "Manel Kape"))
        self.assertIsNone(ev.homeImage)
        self.assertNotIn("homeImage", core.event_to_json(ev))

    def test_date_without_time_is_not_invented(self):
        ev = next(e for e in ufc.CitoUfc().fetch(FakeHttp(routes()), NOW, ENV) if e.id == "ufc-ufc-fight-night-oct-31")
        self.assertTrue(ev.timeTbd)
        self.assertTrue(core.event_to_json(ev)["timeTbd"])
        self.assertEqual(ev.start.astimezone(core.TZ).date().isoformat(), "2026-10-31")

    def test_six_hour_cache_avoids_calls(self):
        p = ufc.CitoUfc()
        p.fetch(FakeHttp(routes()), NOW, ENV)
        cache = p.cache_out
        http = FakeHttp(routes())
        p2 = ufc.CitoUfc(cache)
        events = p2.fetch(http, NOW + timedelta(hours=3), ENV)
        self.assertEqual(http.calls, [])
        self.assertIsNone(p2.contacted_at)
        self.assertEqual({e.id for e in events}, {"ufc-ufc-333", "ufc-ufc-fight-night-oct-31"})
        self.assertEqual(next(e for e in events if e.id == "ufc-ufc-333").homeImage, "https://img.cito.example/volk.png")

    def test_after_refresh_cards_are_reused_for_24h(self):
        p = ufc.CitoUfc()
        p.fetch(FakeHttp(routes()), NOW, ENV)
        http = FakeHttp(routes())
        ufc.CitoUfc(p.cache_out).fetch(http, NOW + timedelta(hours=7), ENV)
        self.assertEqual([u for u, _ in http.calls], [f"{ufc.CitoUfc.BASE}/events/upcoming"])   # 1 sola petición

    def test_cito_down_and_rate_limited(self):
        for exc, code in ((pv.ProviderError(pv.PROVIDER_UNAVAILABLE, "HTTP 503"), "provider_unavailable"),
                          (pv.ProviderError(pv.code_for_status(429), "HTTP 429"), "rate_limited")):
            with self.assertRaises(pv.ProviderError) as ctx:
                ufc.CitoUfc().fetch(FakeHttp(routes(**{f"{ufc.CitoUfc.BASE}/events/upcoming": exc})), NOW, ENV)
            self.assertEqual(ctx.exception.code, code)

    def test_card_failure_keeps_event(self):
        http = FakeHttp(routes(**{f"{ufc.CitoUfc.BASE}/events/ufc-333/bouts": pv.ProviderError(pv.TIMEOUT, "timeout")}))
        ev = next(e for e in ufc.CitoUfc().fetch(http, NOW, ENV) if e.id == "ufc-ufc-333")
        self.assertEqual((ev.title, ev.home), ("UFC 333", None))     # sin combate principal, no se inventa

    def test_missing_key(self):
        with self.assertRaises(pv.ProviderError) as ctx:
            ufc.CitoUfc().fetch(FakeHttp(routes()), NOW, {})
        self.assertEqual(ctx.exception.code, "authentication_error")


class UfcInGeneratorTests(unittest.TestCase):
    def test_stats_status_and_stale(self):
        good = gen.generate([ufc.CitoUfc()], FakeHttp(routes()), NOW, ENV)
        self.assertEqual(good.stats["ufc"]["status"], "ok")
        self.assertEqual(good.stats["ufc"]["provider"], "cito")
        down = {f"{ufc.CitoUfc.BASE}/events/upcoming": pv.ProviderError(pv.TIMEOUT, "timeout")}

        class Other(pv.Provider):
            key, name, label, sports = "formula1", "jolpica", "Jolpica", ("formula1",)

            def fetch(self, http, now, env):
                return []
        stale = gen.generate([ufc.CitoUfc(), Other()], FakeHttp(routes(**down)), NOW + timedelta(hours=1), ENV,
                             previous=good.agenda)
        self.assertEqual(stale.stats["ufc"]["status"], "stale")
        ev = next(e for e in stale.agenda["events"] if e["id"] == "ufc-ufc-333")
        self.assertEqual(ev["homeImage"], "https://img.cito.example/volk.png")      # se conservan retratos

    def test_cached_run_is_ok_without_attempt(self):
        p = ufc.CitoUfc()
        p.fetch(FakeHttp(routes()), NOW, ENV)
        result = gen.generate([ufc.CitoUfc(p.cache_out)], FakeHttp({}), NOW + timedelta(hours=2), ENV)
        st = result.stats["ufc"]
        self.assertEqual((st["status"], st["cached"], st["lastSuccess"]), ("ok", True, "2026-10-23T10:17:00Z"))
        self.assertIn("Cito UFC: OK (cached) - 2 events", result.log)


if __name__ == "__main__":
    unittest.main()
