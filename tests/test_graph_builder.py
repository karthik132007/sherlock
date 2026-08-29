import unittest
import tempfile
import json
from pathlib import Path
from unittest.mock import patch
from engine.graph_builder import build_graph, save_graph_outputs
from engine import crud

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

    def test_sync_graph_to_neo4j_exists_and_is_callable(self):
        self.assertTrue(hasattr(crud, "sync_graph_to_neo4j"))
        self.assertTrue(callable(crud.sync_graph_to_neo4j))

        with tempfile.TemporaryDirectory() as td:
            project = Path(td) / "case_sync"
            project.mkdir()
            entities = [{
                "id": "person_rose_mathew",
                "name": "Rose Mathew",
                "type": "PERSON",
                "confidence": 0.9,
                "source_files": ["fir.txt"],
                "chunk_ids": ["c1"],
                "aliases": ["Rose M."],
                "data": {"age": 27},
                "mentions": 1,
            }]
            mappings = [{
                "relation_id": "rel_001",
                "source": {"id": "person_rose_mathew", "name": "Rose Mathew", "type": "PERSON"},
                "relation": "KNOWS",
                "target": {"id": "person_ananya_joseph", "name": "Ananya Joseph", "type": "PERSON"},
                "confidence": 0.92,
                "evidence_text": "Rose knows Ananya",
                "source_file": "witness.txt",
                "chunk_id": "c2",
            }]

            with patch("engine.crud.Neo4jStore") as mock_store_cls:
                mock_store = mock_store_cls.return_value
                mock_store.verify_connection.return_value = True
                mock_store.delete_project.return_value = 1
                mock_store.create_node.return_value = {}
                mock_store.create_relationship.return_value = {}

                result = crud.sync_graph_to_neo4j(project, entities, mappings, verbose=False)

                self.assertTrue(result)
                mock_store.verify_connection.assert_called_once()
                mock_store.delete_project.assert_called_once_with("case_sync")
                mock_store.create_node.assert_called_once_with("case_sync", entities[0])
                mock_store.create_relationship.assert_called_once_with("case_sync", mappings[0])
                mock_store.close.assert_called_once()

    def test_save_graph_outputs_auto_syncs_to_neo4j(self):
        """Saving the JSON graph files must automatically dump the data into Neo4j."""
        with tempfile.TemporaryDirectory() as td:
            project = Path(td) / "case_auto"
            project.mkdir()
            entities = [
                {"id": "person_rose_mathew", "name": "Rose Mathew", "type": "PERSON", "confidence": 0.9, "source_files": ["fir.txt"], "chunk_ids": ["c1"], "aliases": [], "data": {"age": 27}, "mentions": 1},
                {"id": "person_ananya_joseph", "name": "Ananya Joseph", "type": "PERSON", "confidence": 0.9, "source_files": ["witness.txt"], "chunk_ids": ["c2"], "aliases": [], "data": {}, "mentions": 1},
            ]
            rels = [{"source": "Rose Mathew", "relation": "FRIEND_OF", "target": "Ananya Joseph", "confidence": 0.95, "source_file": "witness.txt", "chunk_id": "c2", "evidence_text": "friends"}]
            mappings = build_graph(entities, rels, use_llm=False, verbose=False)

            with patch("engine.crud.Neo4jStore") as mock_store_cls:
                mock_store = mock_store_cls.return_value
                mock_store.verify_connection.return_value = True
                mock_store.delete_project.return_value = 0
                mock_store.create_node.return_value = {}
                mock_store.create_relationship.return_value = {}

                paths = save_graph_outputs(project, entities, rels, mappings, verbose=False)

                # JSON files are written first...
                self.assertTrue(paths["graph_data_json"].exists())
                self.assertTrue(paths["relations_json"].exists())
                # ...then the graph is dumped into Neo4j automatically
                mock_store.verify_connection.assert_called_once()
                mock_store.delete_project.assert_called_once_with("case_auto")
                self.assertEqual(mock_store.create_node.call_count, 2)
                self.assertEqual(mock_store.create_relationship.call_count, 1)
                mock_store.close.assert_called_once()
