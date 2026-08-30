import unittest
import tempfile
import json
from pathlib import Path
from unittest.mock import patch, MagicMock

from main import validate_project
from engine.chunk import semantic_chunk_text
from engine.entity_extraction import run_extraction_pipeline
from engine.llm import LLMConfig


class TestValidateProject(unittest.TestCase):
    def test_missing_dir(self):
        with self.assertRaises(SystemExit):
            validate_project(Path("/tmp/does_not_exist_12345_sherlock_xyz"))

    def test_missing_warehouse(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td)
            with self.assertRaises(SystemExit):
                validate_project(p)

    def test_empty_warehouse(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td)
            (p / "warehouse.txt").write_text("", encoding="utf-8")
            with self.assertRaises(SystemExit):
                validate_project(p)

    def test_valid_project(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td)
            (p / "warehouse.txt").write_text("hello world", encoding="utf-8")
            out = validate_project(p)
            self.assertEqual(out, p / "warehouse.txt")


class TestMainChunkSmoke(unittest.TestCase):
    def test_chunk_smoke_on_sample(self):
        p = Path("data/sample_case/warehouse.txt")
        if not p.exists():
            self.skipTest("sample_case missing")
        text = p.read_text(encoding="utf-8")
        from main import validate_project
        # validate sample_case project dir
        proj = Path("data/sample_case")
        wp = validate_project(proj)
        self.assertTrue(wp.exists())
        chunks = semantic_chunk_text(text, chunk_size=1200, overlap=200)
        self.assertGreater(len(chunks), 0)

    def test_main_py_runs_without_extract(self):
        import subprocess, sys
        with tempfile.TemporaryDirectory() as td:
            proj = Path(td) / "case_tmp"
            proj.mkdir()
            (proj / "warehouse.txt").write_text("""========================================
SOURCE_FILE: fir.txt
SOURCE_TYPE: TEXT
========================================

Rose Mathew was found dead in Flat 3B at 18:30 on 14 April 2026. Ananya Joseph discovered the body. Arjun Dev was called at 19:00.

========================================
END_SOURCE: fir.txt
========================================
""", encoding="utf-8")
            # run main.py without --extract
            result = subprocess.run([sys.executable, "main.py", "--project", str(proj)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, msg=result.stderr)
            self.assertTrue((proj / "processed" / "chunks.json").exists())
            data = json.loads((proj / "processed" / "chunks.json").read_text(encoding="utf-8"))
            self.assertGreater(data["total_chunks"], 0)


class TestExtractionPipelineMocked(unittest.TestCase):
    def fake_llm_entity_response(self, *args, **kwargs):
        # return a JSON array string as LLM would
        return json.dumps([
            {"name": "Rose Mathew", "type": "PERSON", "confidence": 0.98, "source_file": "fir.txt", "chunk_id": "fir_chunk_001", "aliases": ["Rose M."], "data": {"age": 27, "occupation": "Designer"}},
            {"name": "Ananya Joseph", "type": "PERSON", "confidence": 0.96, "source_file": "fir.txt", "chunk_id": "fir_chunk_001", "aliases": [], "data": {}},
            {"name": "Flat 3B", "type": "LOCATION", "confidence": 0.95, "source_file": "fir.txt", "chunk_id": "fir_chunk_001", "aliases": [], "data": {"type": "residence"}},
        ])

    def fake_llm_relation_response(self, *args, **kwargs):
        return json.dumps([
            {"source": "Rose Mathew", "relation": "FRIEND_OF", "target": "Ananya Joseph", "confidence": 0.95, "source_file": "fir.txt", "chunk_id": "fir_chunk_001", "evidence_text": "Rose was friends with Ananya"},
            {"source": "Rose Mathew", "relation": "RESIDES_AT", "target": "Flat 3B", "confidence": 0.93, "source_file": "fir.txt", "chunk_id": "fir_chunk_001", "evidence_text": "Rose resided at Flat 3B"},
        ])

    @patch("engine.llm.call_llm")
    def test_full_extraction_mocked(self, mock_call):
        # sequence: first call for entities batch1, second for relations batch1
        mock_call.side_effect = [self.fake_llm_entity_response(), self.fake_llm_relation_response()]

        with tempfile.TemporaryDirectory() as td:
            proj = Path(td) / "case_mock"
            proj.mkdir()
            (proj / "warehouse.txt").write_text("""========================================
SOURCE_FILE: fir.txt
SOURCE_TYPE: TEXT
========================================

Rose Mathew was found dead in Flat 3B. She was friends with Ananya Joseph. Rose resided at Flat 3B.

========================================
END_SOURCE: fir.txt
========================================
""", encoding="utf-8")
            # also need llm.json? not strictly required, will use env fallback
            # create dummy llm.json
            (proj / "llm.json").write_text(json.dumps({"provider": "openai", "model": "gpt-4o-mini", "api_key": "sk-test-mock-1234567890", "context_window": 128000}), encoding="utf-8")

            result = run_extraction_pipeline(proj, batch_size=20, verbose=False)

            self.assertGreater(len(result["entities"]), 0)
            self.assertGreater(len(result["relationships"]), 0)
            # check files written
            self.assertTrue((proj / "processed" / "entities.json").exists())
            self.assertTrue((proj / "processed" / "relations.json").exists())
            self.assertTrue((proj / "processed" / "graph_data.json").exists())

            # validate entities.json structure
            ents_data = json.loads((proj / "processed" / "entities.json").read_text(encoding="utf-8"))
            self.assertIn("entities", ents_data)
            for e in ents_data["entities"]:
                self.assertIn("id", e)
                self.assertIn("name", e)
                self.assertIn("type", e)
                self.assertIn("data", e)
                self.assertIn("source_files", e)

            # graph_data.json should have relation_id : node relation node
            graph_data = json.loads((proj / "processed" / "graph_data.json").read_text(encoding="utf-8"))
            self.assertIn("graph", graph_data)
            if graph_data["graph"]:
                m = graph_data["graph"][0]
                self.assertIn("relation_id", m)
                self.assertIn("source", m)
                self.assertIn("target", m)
                self.assertIn("data", m["source"])
                self.assertIn("data", m["target"])

    @patch("engine.llm.call_llm")
    def test_timeline_mocked(self, mock_call):
        # fake timeline response
        def fake_timeline(*args, **kwargs):
            # distinguish by system prompt? just return timeline events
            # check if prompt contains "timeline" lower
            # simpler: return timeline JSON array
            # But extraction pipeline will call entity then timeline if enabled; we need to handle sequence
            # For this test, we mock extract_timeline auto directly via timeline pipeline
            return json.dumps([
                {"timestamp": "2026-04-14 18:30", "event": "Rose found dead", "source_file": "fir.txt", "chunk_id": "fir_chunk_001", "confidence": 0.98, "evidence_text": "found dead at 18:30"},
                {"timestamp": "2026-04-14 15:30", "event": "death window start", "source_file": "postmortem.txt", "chunk_id": "postmortem_chunk_001", "confidence": 0.9, "evidence_text": "window 15:30"},
            ])
        mock_call.side_effect = fake_timeline

        with tempfile.TemporaryDirectory() as td:
            proj = Path(td) / "case_tl"
            proj.mkdir()
            (proj / "warehouse.txt").write_text("""========================================
SOURCE_FILE: fir.txt
SOURCE_TYPE: TEXT
========================================

On 14 April 2026 at 18:30 Rose Mathew was found dead. Window 15:30 start.

========================================
END_SOURCE: fir.txt
========================================
""", encoding="utf-8")
            (proj / "llm.json").write_text(json.dumps({"provider": "openai", "model": "gpt-4o-mini", "api_key": "sk-test-12345", "context_window": 128000}), encoding="utf-8")

            from engine.timeline import run_timeline_pipeline
            result = run_timeline_pipeline(proj, batch_size=20, verbose=False)
            self.assertEqual(len(result["timeline"]), 2)
            # should be sorted chronologically 15:30 before 18:30
            self.assertEqual(result["timeline"][0]["timestamp"], "2026-04-14 15:30")
            self.assertTrue((proj / "processed" / "timeline.json").exists())

    @patch("engine.llm.call_llm")
    def test_dedup_across_batches(self, mock_call):
        # two batches, second repeats entity with alias
        responses = [
            json.dumps([{"name": "Rose Mathew", "type": "PERSON", "confidence": 0.9, "source_file": "a.txt", "chunk_id": "a_chunk_001", "aliases": [], "data": {"age": 27}}]),
            json.dumps([{"name": "Rose M.", "type": "PERSON", "confidence": 0.9, "source_file": "b.txt", "chunk_id": "b_chunk_001", "aliases": [], "data": {"occupation": "Designer"}}]),
            json.dumps([]),  # no relations for this test? we will test entities only
        ]
        # For entity extraction with 2 batches, we need 2 calls. Then relationship extraction would add more calls, so we disable relationships.
        mock_call.side_effect = responses

        with tempfile.TemporaryDirectory() as td:
            proj = Path(td) / "case_dedup"
            proj.mkdir()
            # create 25 chunks worth of text to force 2 batches (batch 20)
            # easiest: create warehouse with many sentences
            text = "\n".join([f"Sentence {i} about Rose Mathew." for i in range(100)])
            warehouse = f"""========================================
SOURCE_FILE: a.txt
SOURCE_TYPE: TEXT
========================================

{text}

========================================
END_SOURCE: a.txt
========================================
"""
            (proj / "warehouse.txt").write_text(warehouse, encoding="utf-8")
            (proj / "llm.json").write_text(json.dumps({"provider": "openai", "model": "gpt-4o-mini", "api_key": "sk-test", "context_window": 128000}), encoding="utf-8")

            result = run_extraction_pipeline(proj, batch_size=20, extract_relationships=False, verbose=False)
            # despite two raw entities with alias, merged should be 1
            self.assertEqual(len(result["entities"]), 1)
            self.assertEqual(result["entities"][0]["name"], "Rose Mathew")

    @patch("engine.llm.call_llm")
    def test_contradictions_pipeline_mocked(self, mock_call):
        mock_call.return_value = json.dumps([
            {
                "contradiction_id": "contra_001",
                "type": "ALIBI_VS_EVIDENCE",
                "summary": "Arjun Delhi travel claim vs Mumbai CCTV footage",
                "description": "Arjun claims he was in Delhi, but CCTV captured him in Mumbai.",
                "severity": "CRITICAL",
                "confidence": 0.96,
                "entities_involved": ["Arjun Dev", "Gateway Hotel"],
                "conflicting_points": [
                    {
                        "claim": "Arjun claimed he flew to Delhi",
                        "speaker_or_source": "Arjun Dev",
                        "source_file": "witness.txt",
                        "chunk_id": "c1",
                        "quote": "I took the flight to Delhi",
                    },
                    {
                        "claim": "CCTV recorded Arjun in Mumbai",
                        "speaker_or_source": "CCTV Log",
                        "source_file": "cctv.txt",
                        "chunk_id": "c2",
                        "quote": "CCTV captured Arjun in Mumbai",
                    },
                ],
                "resolution_status": "POTENTIAL_LIE",
                "investigation_lead": "Interrogate Arjun with CCTV footage",
            }
        ])

        with tempfile.TemporaryDirectory() as td:
            proj = Path(td) / "case_contra"
            proj.mkdir()
            (proj / "warehouse.txt").write_text("""========================================
SOURCE_FILE: witness.txt
SOURCE_TYPE: TEXT
========================================

Arjun claimed he flew to Delhi.

========================================
END_SOURCE: witness.txt
========================================
""", encoding="utf-8")
            (proj / "llm.json").write_text(json.dumps({"provider": "openai", "model": "gpt-4o-mini", "api_key": "sk-test", "context_window": 128000}), encoding="utf-8")

            from engine.contradictions import run_contradictions_pipeline
            result = run_contradictions_pipeline(proj, batch_size=20, verbose=False)
            self.assertEqual(len(result["contradictions"]), 1)
            self.assertEqual(result["contradictions"][0]["type"], "ALIBI_VS_EVIDENCE")
            self.assertTrue((proj / "processed" / "contradictions.json").exists())

