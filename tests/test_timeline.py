import unittest
import tempfile
import json
from pathlib import Path

from engine.llm import _try_parse_timeline_timestamp, sort_timeline_events, normalize_timeline_event
from engine.timeline import save_timeline_outputs

class TestTimelineParsing(unittest.TestCase):
    def test_iso_date(self):
        dt = _try_parse_timeline_timestamp("2026-04-14 18:30")
        self.assertIsNotNone(dt)
        self.assertEqual(dt.year, 2026)
        self.assertEqual(dt.month, 4)
        self.assertEqual(dt.day, 14)
        self.assertEqual(dt.hour, 18)

    def test_human_date(self):
        dt = _try_parse_timeline_timestamp("14 April 2026 18:30 IST")
        self.assertIsNotNone(dt)
        self.assertEqual(dt.year, 2026)

    def test_iso_with_T(self):
        dt = _try_parse_timeline_timestamp("2026-04-14T15:30:00+05:30")
        self.assertIsNotNone(dt)

    def test_us_style(self):
        dt = _try_parse_timeline_timestamp("April 14, 2026 at 6:30 pm")
        self.assertIsNotNone(dt)

    def test_unparseable(self):
        self.assertIsNone(_try_parse_timeline_timestamp("sometime last week"))

    def test_sort_chronologically(self):
        events = [
            {"timestamp": "2026-04-14 18:30", "event": "found dead"},
            {"timestamp": "2026-04-14 15:30", "event": "death window start"},
            {"timestamp": "2026-04-14 16:00", "event": "last seen"},
        ]
        sorted_ev = sort_timeline_events(events)
        self.assertEqual(sorted_ev[0]["timestamp"], "2026-04-14 15:30")
        self.assertEqual(sorted_ev[1]["timestamp"], "2026-04-14 16:00")
        self.assertEqual(sorted_ev[2]["timestamp"], "2026-04-14 18:30")

    def test_sort_stable_and_unparseable_at_end(self):
        events = [
            {"timestamp": "unparseable time", "event": "vague"},
            {"timestamp": "2026-04-14 10:00", "event": "early"},
            {"timestamp": "2026-04-12 09:00", "event": "earliest"},
        ]
        sorted_ev = sort_timeline_events(events)
        # parseable first sorted, then unparseable
        self.assertEqual(sorted_ev[0]["timestamp"], "2026-04-12 09:00")
        self.assertEqual(sorted_ev[1]["timestamp"], "2026-04-14 10:00")
        self.assertEqual(sorted_ev[2]["timestamp"], "unparseable time")

    def test_sort_same_timestamp_preserves_order(self):
        events = [
            {"timestamp": "2026-04-14 18:30", "event": "b second"},
            {"timestamp": "2026-04-14 18:30", "event": "a first"},
        ]
        sorted_ev = sort_timeline_events(events)
        # stable sort should keep original order for equal keys
        self.assertEqual(sorted_ev[0]["event"], "b second")

    def test_normalize_event(self):
        raw = {"timestamp": "2026-04-14 18:30", "event": "Rose found dead", "source_file": "a.txt", "chunk_id": "c1", "confidence": 0.9, "evidence_text": "found dead at 18:30"}
        norm = normalize_timeline_event(raw)
        self.assertIsNotNone(norm)
        self.assertEqual(norm["timestamp"], "2026-04-14 18:30")
        self.assertIn("_parsed_datetime", norm)

    def test_normalize_missing_fields_returns_none(self):
        self.assertIsNone(normalize_timeline_event({"event": "no timestamp"}))
        self.assertIsNone(normalize_timeline_event({"timestamp": "2026-04-14"}))
        self.assertIsNone(normalize_timeline_event("not a dict"))

    def test_save_timeline_sorts(self):
        with tempfile.TemporaryDirectory() as td:
            project = Path(td) / "case"
            project.mkdir()
            events = [
                {"timestamp": "2026-04-14 18:30", "event": "found", "source_file": "a.txt", "chunk_id": "c1", "confidence": 0.9, "evidence_text": "found"},
                {"timestamp": "2026-04-14 12:00", "event": "lunch", "source_file": "a.txt", "chunk_id": "c1", "confidence": 0.9, "evidence_text": "lunch"},
            ]
            paths = save_timeline_outputs(project, events, verbose=False)
            self.assertTrue(paths["timeline_json"].exists())
            data = json.loads(paths["timeline_json"].read_text(encoding="utf-8"))
            self.assertTrue(data["sorted"])
            self.assertEqual(data["timeline"][0]["timestamp"], "2026-04-14 12:00")
            self.assertEqual(data["total_events"], 2)
            self.assertEqual(data["timeline_start"], "2026-04-14 12:00")
