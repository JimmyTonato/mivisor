import os
import unittest
from datetime import datetime, timedelta, timezone

from channel_logos import (IPTV_CHANNELS, IPTV_LOGOS, IPTV_ROBOTS, Brands, ChannelLogos, build_catalog,
                           channel_key, harvest_agenda, iptv_entries, load_brands, norm_text, pick_iptv_logo)
from providers import ProviderError

HERE = os.path.dirname(os.path.abspath(__file__))
NOW = datetime(2026, 10, 26, 10, 0, tzinfo=timezone.utc)


def brands() -> Brands:
    return load_brands(os.path.join(HERE, "channel_brands.json"))


class NormalizeTest(unittest.TestCase):
    def test_decorative_unicode_becomes_plain(self):
        self.assertEqual(norm_text("🄼🄾🅅🄸🅂🅃🄰🅁 🄻🄰🄻🄸🄶🄰"), "movistar laliga")
        self.assertEqual(norm_text("🅳🅰🆉🅽 🅵1"), "dazn f1")
        self.assertEqual(norm_text("M+ LaLiga"), "movistar laliga")
        self.assertEqual(norm_text("Movistar Plus+"), "movistar")
        self.assertEqual(channel_key("DAZN 1 FHD"), "dazn 1")
        self.assertEqual(channel_key("𝐃𝐀𝐙𝐍 𝐋𝐚𝐋𝐢𝐠𝐚"), "dazn laliga")


class GroupsTest(unittest.TestCase):
    def test_group_is_the_start_of_the_name(self):
        b = brands()
        self.assertEqual(b.group_of("M+ LaLiga"), "movistar")
        self.assertEqual(b.group_of("Movistar Plus+"), "movistar")
        self.assertEqual(b.group_of("🄼🄾🅅🄸🅂🅃🄰🅁 🄳🄴🄿🄾🅁🅃🄴🅂"), "movistar")
        self.assertEqual(b.group_of("DAZN LaLiga"), "dazn")          # empieza por DAZN, no por LaLiga
        self.assertEqual(b.group_of("DAZN F1"), "dazn")
        self.assertEqual(b.group_of("LaLiga TV Hypermotion"), "laliga")
        self.assertEqual(b.group_of("Eurosport 2"), "eurosport")
        self.assertEqual(b.group_of("Tennis Channel"), "tennischannel")
        self.assertIsNone(b.group_of("Primera Federación (RFEF)"))
        self.assertIsNone(b.group_of("Golf+"))                      # "gol" no es prefijo de la palabra "golf"


class IptvTest(unittest.TestCase):
    def test_pick_best_image_logo(self):
        logos = [
            {"url": "https://x/logo.svg", "format": "SVG", "in_use": True, "width": 1000},
            {"url": "https://x/white.png", "format": "PNG", "in_use": True, "tags": ["white"], "width": 800},
            {"url": "https://x/feed.png", "format": "PNG", "in_use": True, "feed": "HD", "width": 900},
            {"url": "https://x/main.png", "format": "PNG", "in_use": True, "width": 512},
            {"url": "http://x/insecure.png", "format": "PNG", "in_use": True, "width": 2000},
        ]
        self.assertEqual(pick_iptv_logo(logos), "https://x/main.png")
        self.assertIsNone(pick_iptv_logo([{"url": "https://x/a.svg", "format": "SVG"}]))

    def test_entries_spain_and_known_groups_only(self):
        channels = [
            {"id": "MovistarLaLiga.es", "name": "Movistar LaLiga", "country": "ES", "alt_names": ["M+ LaLiga"]},
            {"id": "DAZN1.de", "name": "DAZN 1", "country": "DE"},
            {"id": "Random.fr", "name": "France Random", "country": "FR"},
            {"id": "Closed.es", "name": "Old Channel", "country": "ES", "closed": "2020-01-01"},
        ]
        logos = [
            {"channel": "MovistarLaLiga.es", "url": "https://l/mlaliga.png", "format": "PNG", "width": 400},
            {"channel": "DAZN1.de", "url": "https://l/dazn1.png", "format": "PNG", "width": 400},
            {"channel": "Random.fr", "url": "https://l/r.png", "format": "PNG", "width": 400},
            {"channel": "Closed.es", "url": "https://l/c.png", "format": "PNG", "width": 400},
        ]
        out = iptv_entries(channels, logos, brands())
        self.assertEqual(set(out), {"movistar laliga", "dazn 1"})
        self.assertEqual(out["movistar laliga"]["group"], "movistar")
        self.assertEqual(out["movistar laliga"]["aliases"], ["M+ LaLiga"])
        self.assertEqual(out["dazn 1"]["group"], "dazn")


class CatalogTest(unittest.TestCase):
    events = [{"channels": [
        {"name": "M+ LaLiga", "logo": "https://futbolenlatv/mlaliga.png"},
        {"name": "DAZN F1", "logo": "https://futbolenlatv/daznf1.png"},
        {"name": "Gol Play", "logo": ""},
        {"name": "Vector", "logo": "https://futbolenlatv/v.svg"},
    ]}]
    iptv = {
        "movistar laliga": {"name": "Movistar LaLiga", "logo": "https://iptv/mlaliga.png", "group": "movistar", "source": "iptv-org"},
        "movistar": {"name": "Movistar Plus+", "logo": "https://iptv/mplus.png", "group": "movistar", "source": "iptv-org"},
        "dazn 1": {"name": "DAZN 1", "logo": "https://iptv/dazn1.png", "group": "dazn", "source": "iptv-org"},
    }

    def test_two_levels_and_source_priority(self):
        cat = build_catalog(brands(), None, NOW, self.events, self.iptv, "2026-10-26T10:00:00Z", {})
        ch = cat["channels"]
        self.assertEqual(ch["movistar laliga"]["logo"], "https://futbolenlatv/mlaliga.png")   # futbolenlatv > iptv-org
        self.assertEqual(ch["movistar laliga"]["group"], "movistar")
        self.assertEqual(ch["dazn f1"]["group"], "dazn")
        self.assertNotIn("vector", ch)                                                           # SVG fuera
        # Grupo: logo general (de su canal insignia), distinto del de cada canal.
        self.assertEqual(cat["groups"]["movistar"]["logo"], "https://iptv/mplus.png")
        self.assertNotIn("logo", cat["groups"]["dazn"])                    # "DAZN 1" no es el logo de la marca
        self.assertNotIn("logo", cat["groups"]["tennischannel"])
        self.assertIn("m+", cat["groups"]["movistar"]["aliases"])

    def test_group_logo_is_brand_never_numbered_channel(self):
        iptv = {"dazn 1": {"name": "DAZN 1", "logo": "https://iptv/dazn1.png", "group": "dazn", "source": "iptv-org"},
                "dazn": {"name": "DAZN", "logo": "https://iptv/dazn-brand.png", "group": "dazn", "source": "iptv-org"}}
        cat = build_catalog(brands(), None, NOW, [], {"dazn 1": iptv["dazn 1"]}, None, {})
        self.assertNotIn("logo", cat["groups"]["dazn"])                    # solo hay DAZN 1: el grupo queda sin logo
        cat = build_catalog(brands(), None, NOW, [], iptv, None, {})
        self.assertEqual(cat["groups"]["dazn"]["logo"], "https://iptv/dazn-brand.png")

    def test_manual_wins_and_cache_is_kept(self):
        manual = {"channels": {"DAZN F1": "https://mine/f1.png"}, "groups": {"DAZN": "https://mine/dazn.png"}}
        first = build_catalog(brands(), None, NOW, self.events, self.iptv, "2026-10-26T10:00:00Z", manual)
        self.assertEqual(first["channels"]["dazn f1"]["logo"], "https://mine/f1.png")
        self.assertEqual(first["groups"]["dazn"]["logo"], "https://mine/dazn.png")
        # Siguiente ejecución sin agenda ni iptv-org nuevos: todo sigue en caché.
        later = build_catalog(brands(), first, NOW + timedelta(hours=1), [], None, None, {})
        self.assertEqual(later["channels"]["movistar laliga"]["logo"], "https://futbolenlatv/mlaliga.png")
        self.assertEqual(later["channels"]["dazn 1"]["logo"], "https://iptv/dazn1.png")
        self.assertEqual(later["iptv_fetched"], "2026-10-26T10:00:00Z")
        # 100 días sin ver un canal de futbolenlatv: se retira.
        old = build_catalog(brands(), first, NOW + timedelta(days=100), [], None, None, {})
        self.assertNotIn("movistar laliga", old["channels"])

    def test_harvest_keeps_first_and_ignores_empty(self):
        h = harvest_agenda(self.events)
        self.assertEqual(set(h), {"movistar laliga", "dazn f1"})


class FakeHttp:
    def __init__(self, robots="", fail=None):
        self.robots, self.fail, self.calls = robots, fail, []

    def get_text(self, url, headers=None, label=""):
        self.calls.append(url)
        if self.fail == "robots404":
            raise ProviderError("not_found", "HTTP 404", status=404)
        return self.robots

    def get_json(self, url, headers=None, params=None, label="", max_body=0):
        self.calls.append(url)
        if self.fail == "json":
            raise ProviderError("provider_unavailable", "x")
        if url == IPTV_CHANNELS:
            return [{"id": "Teledeporte.es", "name": "Teledeporte", "country": "ES"}]
        if url == IPTV_LOGOS:
            return [{"channel": "Teledeporte.es", "url": "https://l/tdp.png", "format": "PNG", "width": 300}]
        raise AssertionError(url)


class StepTest(unittest.TestCase):
    def test_weekly_refresh_with_robots(self):
        http = FakeHttp(fail="robots404")
        cat = ChannelLogos(http, NOW, brands()).build([])
        self.assertEqual(cat["channels"]["teledeporte"]["group"], "rtve")
        self.assertEqual(cat["iptv_fetched"], "2026-10-26T10:00:00Z")
        # Al día siguiente no se vuelve a descargar.
        http2 = FakeHttp()
        ChannelLogos(http2, NOW + timedelta(days=1), brands(), cat).build([])
        self.assertEqual(http2.calls, [])
        # A la semana, sí.
        http3 = FakeHttp()
        ChannelLogos(http3, NOW + timedelta(days=7), brands(), cat).build([])
        self.assertIn(IPTV_ROBOTS, http3.calls)

    def test_robots_disallow_or_failure_keeps_cache(self):
        http = FakeHttp(robots="User-agent: *\nDisallow: /api/\n")
        step = ChannelLogos(http, NOW, brands())
        cat = step.build([])
        self.assertNotIn(IPTV_CHANNELS, http.calls)
        self.assertIsNone(cat["iptv_fetched"])
        self.assertTrue(any("robots" in line for line in step.log))
        failing = ChannelLogos(FakeHttp(fail="json"), NOW, brands())
        self.assertIsNone(failing.build([])["iptv_fetched"])


if __name__ == "__main__":
    unittest.main()
