import unittest
import tempfile
import json
from pathlib import Path
from engine.graph_builder import build_graph, save_graph_outputs

class TestGraphBuilder(unittest.TestCase):
    def test_build_graph_deterministic(self):
        entities = [
            {"id": "person_rose_mathew", "name": "Rose Mathew", "type": "PERSON", "confidence": 0.9, "source_files": ["fir.txt"], "chunk_ids": ["c1"], "aliases": [], "data": {}, "mentions": 1},
            {"id": "person_ananya_joseph", "name": "Ananya Joseph", "type": "PERSON", "confidence": 0.9, "source_files": ["witness.txt"], "chunk_ids": ["c2"], "aliases": [], "data": {}, "mentions": 1},
        ]
        rels = [{"source": "Rose Mathew", "relation": "FRIEND_OF", "target": "Ananya Joseph", "confidence": 0.9, "source_file": "witness.txt", "chunk_id": "c2", "evidence_text": "friends"}]
        mappings = build_graph(entities, rels, use_llm=False, verbose=False)
        self.assertEqual(len(mappings), 1)
        self.assertIn("relation_id", mappings[0])
        self.assertEqual(mappings[0]["relation"], "FRIEND_OF")

    def test_save_graph_outputs_creates_files(self):
        with tempfile.TemporaryDirectory() as td:
            project = Path(td) / "case_test"
            project.mkdir()
            # need llm.json to avoid warning? not required
            entities = [
                {"id": "person_sara", "name": "Sara", "type": "PERSON", "confidence": 0.95, "source_files": ["a.txt"], "chunk_ids": ["c1"], "aliases": [], "data": {"age": 26}, "mentions": 1}
            ]
            rels = []
            mappings = []
            # add one relation to test graph
            entities.append({"id": "person_ananya_joseph", "name": "Ananya Joseph", "type": "PERSON", "confidence": 0.9, "source_files": ["a.txt"], "chunk_ids": ["c1"], "aliases": [], "data": {}, "mentions": 1})
            rels.append({"source": "Sara", "relation": "KNOWS", "target": "Ananya Joseph", "confidence": 0.9, "source_file": "a.txt", "chunk_id": "c1", "evidence_text": "Sara knows Ananya"})
            mappings = build_graph(entities, rels, use_llm=False, verbose=False)
            paths = save_graph_outputs(project, entities, rels, mappings, verbose=False)
            self.assertTrue(paths["relations_json"].exists())
            self.assertTrue(paths["graph_data_json"].exists())
            self.assertTrue(paths["graph_json"].exists())
            # validate json
            data = json.loads(paths["graph_data_json"].read_text(encoding="utf-8"))
            self.assertIn("graph", data)
            self.assertEqual(data["total_entities"], 2)
            self.assertEqual(len(data["graph"]), 1)
            self.assertIn("graph_by_id", data)
            # relations.json
            rel_data = json.loads(paths["relations_json"].read_text(encoding="utf-8"))
            self.assertIn("relationships", rel_data)

    def test_save_empty_graph(self):
        with tempfile.TemporaryDirectory() as td:
            project = Path(td) / "proj"
            project.mkdir()
            paths = save_graph_outputs(project, [], [], [], verbose=False)
            self.assertTrue(paths["graph_data_json"].exists())
            data = json.loads(paths["graph_data_json"].read_text(encoding="utf-8"))
            self.assertEqual(data["total_entities"], 0)
            self.assertEqual(len(data["graph"]), 0)
