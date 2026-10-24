"""Tests de services.json (estados, historial 30 días, seguridad). Sin Internet."""
import io
import json
import os
import pathlib
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import generate_agenda as gen  # noqa: E402
import services as sv  # noqa: E402

NOW = datetime(2026, 10, 24, 18, 17, tzinfo=timezone.utc)
T = "2026-10-24T18:17:00Z"


def ok_stats(**over):
    base = {k: {"provider": k, "status": "ok", "lastAttempt": T, "lastSuccess": T, "events": 1}
            for k in ("football", "tennis", "formula1", "motogp", "ufc")}
    base.update(over)
    return base


AUX_OK = {"futbolenlatv": {"status": "ok", "error": None}, "thesportsdb": {"status": "ok", "error": None}}


def by_id(data):
    return {s["id"]: s for s in data["services"]}


class StatusTests(unittest.TestCase):
    def test_all_ok(self):
        data = sv.build_services(NOW, ok_stats(), AUX_OK, True)
        self.assertEqual(data["version"], 1)
        self.assertEqual(data["overall"], "ok")
        self.assertEqual([s["id"] for s in data["services"]], [x[0] for x in sv.SERVICES])
        fd = by_id(data)["football_data"]
        self.assertEqual((fd["name"], fd["role"], fd["status"], fd["consecutiveFailures"], fd["lastError"]),
                         ("Football-Data", "Agenda de fútbol", "ok", 0, None))
        self.assertEqual(data["history"], [])                       # nunca entradas "OK"

    def test_stale_is_degraded_with_history(self):
        stats = ok_stats(tennis={"status": "stale", "error": "timeout", "lastAttempt": T,
                                 "lastSuccess": "2026-10-24T16:17:00Z"})
        data = sv.build_services(NOW, stats, AUX_OK, True)
        lt = by_id(data)["live_tennis"]
        self.assertEqual(data["overall"], "degraded")
        self.assertEqual((lt["status"], lt["lastSuccess"], lt["consecutiveFailures"]), ("stale", "2026-10-24T16:17:00Z", 1))
        self.assertEqual(lt["lastError"], {"code": "timeout", "message": "Tiempo de espera agotado", "at": T})
        self.assertEqual(data["history"], [{"service": "live_tennis", "at": T, "type": "error", "status": "stale",
                                            "code": "timeout", "message": "Sincronización fallida"}])

    def test_unavailable_and_consecutive_failures(self):
        prev = sv.build_services(NOW - timedelta(hours=1), ok_stats(motogp={"status": "unavailable", "error": "rate_limited",
                                                                            "lastAttempt": "2026-10-24T17:17:00Z"}), AUX_OK, True)
        data = sv.build_services(NOW, ok_stats(motogp={"status": "unavailable", "error": "rate_limited", "lastAttempt": T}),
                                 AUX_OK, True, prev)
        bt = by_id(data)["blacktop"]
        self.assertEqual((bt["status"], bt["consecutiveFailures"]), ("unavailable", 2))
        self.assertEqual(bt["lastError"]["message"], "Límite temporal de la API")
        self.assertEqual(len([h for h in data["history"] if h["service"] == "blacktop"]), 2)

    def test_recovery_entry_once(self):
        failing = sv.build_services(NOW - timedelta(hours=1), ok_stats(tennis={"status": "stale", "error": "timeout",
                                                                              "lastAttempt": "2026-10-24T17:17:00Z"}), AUX_OK, True)
        back = sv.build_services(NOW, ok_stats(), AUX_OK, True, failing)
        again = sv.build_services(NOW + timedelta(hours=1), ok_stats(), AUX_OK, True, back)
        types = [h["type"] for h in again["history"] if h["service"] == "live_tennis"]
        self.assertEqual(types, ["error", "recovery"])
        self.assertEqual(by_id(back)["live_tennis"]["lastError"]["code"], "timeout")   # se conserva el último error
        self.assertEqual(by_id(back)["live_tennis"]["consecutiveFailures"], 0)

    def test_aux_failures_are_degraded_not_down(self):
        aux = {"futbolenlatv": {"status": "unavailable", "error": "parse_error"},
               "thesportsdb": {"status": "unavailable", "error": "rate_limited"}}
        data = sv.build_services(NOW, ok_stats(), aux, True)
        self.assertEqual(data["overall"], "degraded")
        self.assertEqual(by_id(data)["futbolenlatv"]["lastError"]["message"], "Ha cambiado el formato de la fuente")

    def test_robots_message(self):
        data = sv.build_services(NOW, ok_stats(), {"futbolenlatv": {"status": "unavailable", "error": "robots_disallowed"}}, True)
        self.assertEqual(by_id(data)["futbolenlatv"]["lastError"]["message"],
                         "La fuente ya no permite la consulta automática")

    def test_no_agenda_is_down(self):
        stats = {k: {"status": "unavailable", "error": "timeout", "lastAttempt": T}
                 for k in ("football", "tennis", "formula1", "motogp", "ufc")}
        data = sv.build_services(NOW, stats, {}, False)
        self.assertEqual(data["overall"], "down")
        self.assertEqual(by_id(data)["agenda"]["lastError"]["message"], "No se ha podido generar la agenda")

    def test_zero_events_is_not_an_error(self):
        data = sv.build_services(NOW, ok_stats(motogp={"status": "ok", "events": 0, "lastAttempt": T, "lastSuccess": T}),
                                 AUX_OK, True)
        self.assertEqual(data["overall"], "ok")

    def test_error_is_sanitized(self):
        data = sv.build_services(NOW, ok_stats(tennis={"status": "stale", "error": "requests.exceptions.ConnectTimeout: "
                                                                                   "https://x?key=abc", "lastAttempt": T}),
                                 AUX_OK, True)
        blob = json.dumps(data)
        self.assertNotIn("ConnectTimeout", blob)
        self.assertNotIn("key=abc", blob)
        self.assertEqual(by_id(data)["live_tennis"]["lastError"]["code"], "provider_unavailable")


class HistoryRetentionTests(unittest.TestCase):
    def test_older_than_30_days_removed(self):
        old = [{"service": "live_tennis", "at": "2026-09-20T10:00:00Z", "type": "error", "code": "timeout"},
               {"service": "live_tennis", "at": "2026-10-20T10:00:00Z", "type": "error", "code": "timeout"}]
        data = sv.build_services(NOW, ok_stats(), AUX_OK, True, {"version": 1, "services": [], "history": old})
        self.assertEqual([h["at"] for h in data["history"]], ["2026-10-20T10:00:00Z"])

    def test_max_100_per_service(self):
        hist = [{"service": "blacktop", "at": f"2026-10-{10 + i // 24:02d}T{i % 24:02d}:17:00Z", "type": "error"}
                for i in range(150)]
        hist.append({"service": "jolpica", "at": "2026-10-11T00:00:00Z", "type": "error"})
        out = sv.prune_history(hist, NOW)
        self.assertEqual(sum(1 for h in out if h["service"] == "blacktop"), 100)
        self.assertEqual(out[-1]["at"], hist[149]["at"])                   # se quitan las más antiguas
        self.assertEqual(sum(1 for h in out if h["service"] == "jolpica"), 1)


class EndToEndTests(unittest.TestCase):
    """main(): agenda.json + services.json + athletes.json + ufc-cache.json, y recuperación del estado anterior."""

    class Http:
        def __init__(self, fail_all=False):
            self.fail_all = fail_all

        def get_json(self, url, headers=None, params=None, label=""):
            import providers as pv
            if self.fail_all or "thesportsdb" in url:
                raise pv.ProviderError(pv.TIMEOUT, "timeout")
            if "football-data" in url:
                return {"matches": []}
            if "jolpi" in url:
                return {"MRData": {"RaceTable": {"Races": []}}}
            if "ocblacktop" in url:
                return {"events": []}
            if "citoapi" in url:
                return {"ok": True, "data": []}
            if "livetennis" in url:
                return {"data": [], "meta": {"has_more": False}}
            raise pv.ProviderError(pv.PROVIDER_UNAVAILABLE, "HTTP 404", status=404)

        def get_text(self, url, headers=None, label=""):
            import providers as pv
            raise pv.ProviderError(pv.TIMEOUT, "timeout")

    ENV = {"FOOTBALL_DATA_TOKEN": "fd-SECRET-1", "LIVE_TENNIS_API_KEY": "lt-SECRET-2",
           "BLACKTOP_API_KEY": "bt-SECRET-3", "CITO_API_KEY": "cito-SECRET-4", "THESPORTSDB_API_KEY": "tsdb-SECRET-5"}

    def run_main(self, tmp, fail_all=False, state=None, now=NOW):
        args = ["--out", os.path.join(tmp, "out", "agenda.json")]
        if state:
            args += ["--state-dir", state, "--previous", os.path.join(state, "agenda.json")]
        with mock.patch("sys.stderr", new=io.StringIO()) as err:
            code = gen.main(args, env=self.ENV, http_factory=lambda rec: self.Http(fail_all), now=now,
                            sleep=lambda s: None)
        return code, err.getvalue()

    def read(self, tmp, name):
        with open(os.path.join(tmp, "out", name), encoding="utf-8") as fh:
            return json.load(fh)

    def test_files_history_across_runs_and_no_secrets(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, log1 = self.run_main(tmp)
            self.assertEqual(code, 0)
            agenda, services = self.read(tmp, "agenda.json"), self.read(tmp, "services.json")
            self.assertEqual(agenda["version"], 1)
            self.assertIn("ufc", agenda["stats"])
            self.assertEqual(services["overall"], "degraded")               # TV y TheSportsDB caídos
            self.assertEqual(by_id(services)["football_data"]["status"], "ok")
            self.assertEqual(by_id(services)["futbolenlatv"]["status"], "unavailable")
            self.assertTrue(os.path.isfile(os.path.join(tmp, "out", "athletes.json")))
            self.assertTrue(os.path.isfile(os.path.join(tmp, "out", "ufc-cache.json")))

            # Segunda ejecución con el estado publicado: el historial se conserva y crece.
            state = os.path.join(tmp, "out")
            code, log2 = self.run_main(tmp, state=state, now=NOW + timedelta(hours=1))
            services2 = self.read(tmp, "services.json")
            tv_errors = [h for h in services2["history"] if h["service"] == "futbolenlatv"]
            self.assertEqual(len(tv_errors), 2)
            self.assertEqual(by_id(services2)["futbolenlatv"]["consecutiveFailures"], 2)
            self.assertIn("Cito UFC: OK (cached)", log2)                    # caché de 6 h

            blobs = [log1, log2] + [pathlib.Path(tmp, "out", f).read_text(encoding="utf-8")
                                    for f in os.listdir(os.path.join(tmp, "out"))]
            for secret in self.ENV.values():
                for blob in blobs:
                    self.assertNotIn(secret, blob)
            for word in ("Traceback", "Authorization", "x-api-key", "X-Auth-Token"):
                for blob in blobs:
                    self.assertNotIn(word, blob)

    def test_total_failure_keeps_agenda_but_writes_services(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, _ = self.run_main(tmp, fail_all=True)
            self.assertEqual(code, gen.EXIT_ALL_FAILED)
            self.assertFalse(os.path.exists(os.path.join(tmp, "out", "agenda.json")))
            services = self.read(tmp, "services.json")
            self.assertEqual(services["overall"], "down")
            self.assertEqual(by_id(services)["agenda"]["status"], "unavailable")


if __name__ == "__main__":
    unittest.main()
