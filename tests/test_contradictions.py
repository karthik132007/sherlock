import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

from engine.contradictions import (
    load_processed_context,
    run_contradictions_pipeline,
    save_contradiction_outputs,
)
from engine.llm import (
    _contradiction_dedup_key,
    deduplicate_contradictions,
    extract_contradictions_auto,
    extract_contradictions_batched,
    extract_contradictions_single_call,
    normalize_contradiction,
)
from engine.prompts import (
    CONTRADICTION_SYSTEM_PROMPT,
    CONTRADICTION_TYPES,
    build_contradiction_prompt,
    build_single_call_contradiction_prompt,
)


class TestContradictions(unittest.TestCase):
    def test_contradiction_types_defined(self):
        self.assertIn("ALIBI_VS_EVIDENCE", CONTRADICTION_TYPES)
        self.assertIn("STATEMENT_VS_STATEMENT", CONTRADICTION_TYPES)
        self.assertIn("TIMELINE_CONFLICT", CONTRADICTION_TYPES)
        self.assertIn("RELATIONSHIP_DENIAL", CONTRADICTION_TYPES)

    def test_contradiction_system_prompt(self):
        self.assertIn("Sherlock", CONTRADICTION_SYSTEM_PROMPT)
        self.assertIn("CONTRADICTIONS", CONTRADICTION_SYSTEM_PROMPT)
        self.assertIn("ALIBI_VS_EVIDENCE", CONTRADICTION_SYSTEM_PROMPT)

    def test_build_contradiction_prompt_with_context(self):
        chunks = [
            {"chunk_id": "c1", "source_file": "witness_arjun.txt", "text": "I was in Delhi all evening."},
            {"chunk_id": "c2", "source_file": "cctv_log.txt", "text": "16:15 CCTV shows Arjun entering Mumbai hotel."},
        ]
        entities = [{"name": "Arjun Dev", "type": "PERSON", "data": {"age": 30}, "aliases": ["Arjun D."]}]
        relations = [{"source": "Arjun Dev", "relation": "SEEN_AT", "target": "Mumbai Hotel"}]
        timeline = [{"timestamp": "2026-04-14 16:15", "event": "CCTV log entry", "source_file": "cctv_log.txt"}]
        prev = [{"contradiction_id": "contra_001", "summary": "Old contradiction"}]

        prompt = build_contradiction_prompt(
            batch_chunks=chunks,
            known_entities=entities,
            known_relations=relations,
            known_timeline=timeline,
            previous_contradictions=prev,
        )

        self.assertIn("Arjun Dev", prompt)
        self.assertIn("Mumbai Hotel", prompt)
        self.assertIn("witness_arjun.txt", prompt)
        self.assertIn("cctv_log.txt", prompt)
        self.assertIn("ESTABLISHED CASE KNOWLEDGE", prompt)
        self.assertIn("KNOWN ENTITIES", prompt)
        self.assertIn("CHRONOLOGICAL TIMELINE", prompt)
        self.assertIn("Old contradiction", prompt)

    def test_build_single_call_prompt(self):
        text = "Arjun said he went to Delhi. But CCTV saw him in Mumbai."
        entities = [{"name": "Arjun Dev", "type": "PERSON", "data": {}}]
        prompt = build_single_call_contradiction_prompt(text, known_entities=entities)
        self.assertIn("FULL CASE WAREHOUSE TEXT", prompt)
        self.assertIn("Arjun Dev", prompt)
        self.assertIn("Mumbai", prompt)

    def test_normalize_contradiction_valid(self):
        raw = {
            "contradiction_id": "contra_001",
            "type": "ALIBI_VS_EVIDENCE",
            "summary": "Arjun Delhi alibi vs Mumbai CCTV",
            "description": "Arjun claims he was in Delhi, but CCTV captured him in Mumbai.",
            "severity": "CRITICAL",
            "confidence": 0.98,
            "entities_involved": ["Arjun Dev", "Gateway Hotel"],
            "conflicting_points": [
                {
                    "claim": "Arjun claims he was in Delhi",
                    "speaker_or_source": "Arjun Dev (Witness)",
                    "source_file": "witness.txt",
                    "chunk_id": "c1",
                    "quote": "I was in Delhi all evening",
                },
                {
                    "claim": "CCTV captured Arjun in Mumbai",
                    "speaker_or_source": "CCTV Log",
                    "source_file": "cctv.txt",
                    "chunk_id": "c2",
                    "quote": "16:15 CCTV captured Arjun",
                },
            ],
            "resolution_status": "POTENTIAL_LIE",
            "investigation_lead": "Interrogate Arjun about Mumbai CCTV",
        }
        norm = normalize_contradiction(raw)
        self.assertIsNotNone(norm)
        self.assertEqual(norm["contradiction_id"], "contra_001")
        self.assertEqual(norm["type"], "ALIBI_VS_EVIDENCE")
        self.assertEqual(norm["severity"], "CRITICAL")
        self.assertEqual(norm["confidence"], 0.98)
        self.assertEqual(len(norm["conflicting_points"]), 2)
        self.assertEqual(norm["resolution_status"], "POTENTIAL_LIE")
        self.assertIn("Arjun Dev", norm["entities_involved"])

    def test_normalize_contradiction_fallbacks(self):
        # Missing optional fields, raw string points
        raw = {
            "title": "Conflicting times",
            "type": "timeline mismatch",
            "severity": "severe",
            "confidence": 1.4,
            "entities": ["Witness A"],
            "points": ["Witness A said 4 PM", "Witness B said 7 PM"],
        }
        norm = normalize_contradiction(raw, default_source="report.txt", default_chunk="c0", index=5)
        self.assertIsNotNone(norm)
        self.assertEqual(norm["contradiction_id"], "contra_005")
        self.assertEqual(norm["type"], "TIMELINE_CONFLICT")
        self.assertEqual(norm["severity"], "CRITICAL")
        self.assertEqual(norm["confidence"], 1.0)
        self.assertEqual(norm["resolution_status"], "UNRESOLVED")
        self.assertEqual(len(norm["conflicting_points"]), 2)

    def test_normalize_invalid(self):
        self.assertIsNone(normalize_contradiction("not a dict"))
        self.assertIsNone(normalize_contradiction({}))
        self.assertIsNone(normalize_contradiction({"confidence": 0.9}))

    def test_deduplication(self):
        c1 = {
            "contradiction_id": "contra_999",
            "type": "ALIBI_VS_EVIDENCE",
            "summary": "Arjun Delhi travel claim vs Mumbai CCTV footage",
            "entities_involved": ["Arjun Dev"],
            "conflicting_points": [],
        }
        c2 = {
            "contradiction_id": "contra_888",
            "type": "ALIBI_VS_EVIDENCE",
            "summary": "Arjun Delhi travel claim vs Mumbai CCTV footage",
            "entities_involved": ["Arjun Dev"],
            "conflicting_points": [],
        }
        c3 = {
            "contradiction_id": "contra_777",
            "type": "STATEMENT_VS_STATEMENT",
            "summary": "Witness X vs Witness Y red car color",
            "entities_involved": ["Witness X", "Witness Y"],
            "conflicting_points": [],
        }
        deduped = deduplicate_contradictions([c1, c2, c3])
        self.assertEqual(len(deduped), 2)
        self.assertEqual(deduped[0]["contradiction_id"], "contra_001")
        self.assertEqual(deduped[1]["contradiction_id"], "contra_002")

    def test_load_processed_context(self):
        with tempfile.TemporaryDirectory() as td:
            proj = Path(td)
            proc = proj / "processed"
            proc.mkdir()

            (proc / "entities.json").write_text(
                json.dumps({"entities": [{"name": "Rose", "type": "PERSON"}]}), encoding="utf-8"
            )
            (proc / "relations.json").write_text(
                json.dumps({"relations": [{"source": "Rose", "relation": "KNOWS", "target": "Arjun"}]}),
                encoding="utf-8",
            )
            (proc / "timeline.json").write_text(
                json.dumps({"timeline": [{"timestamp": "2026-04-14", "event": "Event 1"}]}), encoding="utf-8"
            )

            ctx = load_processed_context(proj)
            self.assertEqual(len(ctx["entities"]), 1)
            self.assertEqual(len(ctx["relations"]), 1)
            self.assertEqual(len(ctx["timeline"]), 1)
            self.assertEqual(ctx["entities"][0]["name"], "Rose")

    def test_save_contradiction_outputs(self):
        with tempfile.TemporaryDirectory() as td:
            proj = Path(td)
            contra = [
                {
                    "contradiction_id": "contra_001",
                    "type": "ALIBI_VS_EVIDENCE",
                    "summary": "Alibi contradiction",
                    "description": "Details",
                    "severity": "CRITICAL",
                    "confidence": 0.95,
                    "entities_involved": ["Arjun"],
                    "conflicting_points": [],
                    "resolution_status": "POTENTIAL_LIE",
                    "investigation_lead": "Lead 1",
                },
                {
                    "contradiction_id": "contra_002",
                    "type": "STATEMENT_VS_STATEMENT",
                    "summary": "Witness discrepancy",
                    "description": "Details 2",
                    "severity": "HIGH",
                    "confidence": 0.90,
                    "entities_involved": ["Meera"],
                    "conflicting_points": [],
                    "resolution_status": "UNRESOLVED",
                    "investigation_lead": "Lead 2",
                },
            ]
            paths = save_contradiction_outputs(proj, contra, verbose=False)
            self.assertTrue(paths["contradictions_json"].exists())
            self.assertTrue(paths["contradiction_data_json"].exists())

            data = json.loads(paths["contradictions_json"].read_text(encoding="utf-8"))
            self.assertEqual(data["total_contradictions"], 2)
            self.assertEqual(data["severity_breakdown"]["CRITICAL"], 1)
            self.assertEqual(data["severity_breakdown"]["HIGH"], 1)
            self.assertEqual(data["types_breakdown"]["ALIBI_VS_EVIDENCE"], 1)
            self.assertEqual(len(data["contradictions"]), 2)

    @patch("engine.llm.call_llm")
    def test_run_contradictions_pipeline_mocked(self, mock_call):
        mock_response = json.dumps([
            {
                "contradiction_id": "contra_001",
                "type": "ALIBI_VS_EVIDENCE",
                "summary": "Arjun told he went to Delhi but CCTV shows him in Mumbai",
                "description": "Arjun told police he was in Delhi at 16:15, but Gateway CCTV captured him in Mumbai.",
                "severity": "CRITICAL",
                "confidence": 0.98,
                "entities_involved": ["Arjun Dev", "Gateway Hotel"],
                "conflicting_points": [
                    {
                        "claim": "Arjun told he went to Delhi",
                        "speaker_or_source": "Arjun Dev",
                        "source_file": "statement.txt",
                        "chunk_id": "c1",
                        "quote": "I was in Delhi",
                    },
                    {
                        "claim": "CCTV shows him in Mumbai",
                        "speaker_or_source": "CCTV Log",
                        "source_file": "cctv.txt",
                        "chunk_id": "c2",
                        "quote": "CCTV captured him in Mumbai",
                    },
                ],
                "resolution_status": "POTENTIAL_LIE",
                "investigation_lead": "Interrogate Arjun with CCTV footage",
            }
        ])
        mock_call.return_value = mock_response

        with tempfile.TemporaryDirectory() as td:
            proj = Path(td) / "case_test"
            proj.mkdir()
            (proj / "warehouse.txt").write_text(
                "Arjun stated he was in Delhi. CCTV recorded Arjun entering Mumbai hotel.", encoding="utf-8"
            )

            res = run_contradictions_pipeline(proj, batch_size=20, verbose=False)
            self.assertEqual(len(res["contradictions"]), 1)
            c = res["contradictions"][0]
            self.assertEqual(c["type"], "ALIBI_VS_EVIDENCE")
            self.assertEqual(c["severity"], "CRITICAL")
            self.assertTrue((proj / "processed" / "contradictions.json").exists())


if __name__ == "__main__":
    unittest.main()
