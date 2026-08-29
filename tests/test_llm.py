import unittest
from unittest.mock import patch, MagicMock
import os
import json
import tempfile
from pathlib import Path

from engine.llm import (
    estimate_tokens,
    get_token_stats,
    decide_strategy,
    create_batches,
    make_entity_id,
    merge_entities,
    ensure_entity_ids,
    build_graph_mappings,
    parse_json_array,
    load_llm_config,
    LLMConfig,
    DEFAULT_CONTEXT_WINDOW_TOKENS,
    _normalize_name,
    _person_alias_match,
)


class TestTokenUtils(unittest.TestCase):
    def test_estimate_empty(self):
        self.assertEqual(estimate_tokens(""), 0)
        self.assertEqual(estimate_tokens("   "), 1)  # heuristic returns at least 1 if non-empty? but empty whitespace -> words 0, chars 3 => max 1? check

    def test_estimate_positive(self):
        self.assertGreater(estimate_tokens("hello world test"), 0)
        self.assertGreater(estimate_tokens("a" * 1000), 10)

    def test_get_token_stats(self):
        s = get_token_stats("hello world\nsecond line")
        self.assertIn("chars", s)
        self.assertIn("words", s)
        self.assertIn("estimated_tokens", s)
        self.assertEqual(s["words"], 4)

    def test_decide_fits_batched_default(self):
        txt = "hello " * 100  # small
        decision = decide_strategy(txt, chunks=None, context_window=500000, prefer_batched_when_fits=True)
        self.assertTrue(decision["fits_in_context"])
        self.assertEqual(decision["strategy"], "batched")

    def test_decide_fits_single_call_when_prefer_false(self):
        txt = "hello " * 100
        decision = decide_strategy(txt, chunks=None, context_window=500000, prefer_batched_when_fits=False)
        self.assertTrue(decision["fits_in_context"])
        self.assertEqual(decision["strategy"], "single_call")

    def test_decide_exceeds_forced_batched(self):
        txt = "word " * 100000  # ~133k tokens, > 50000
        decision = decide_strategy(txt, chunks=[1,2,3], context_window=50000, prefer_batched_when_fits=False)
        self.assertFalse(decision["fits_in_context"])
        self.assertEqual(decision["strategy"], "batched")

    def test_batches_needed_computed(self):
        txt = "hello"
        chunks = [{"a": i} for i in range(45)]
        decision = decide_strategy(txt, chunks=chunks, context_window=500000, prefer_batched_when_fits=True)
        self.assertEqual(decision["batches_needed"], 3)  # 45/20 ceil =3


class TestBatching(unittest.TestCase):
    def test_create_batches(self):
        chunks = [{"id": i} for i in range(5)]
        batches = create_batches(chunks, batch_size=2)
        self.assertEqual(len(batches), 3)
        self.assertEqual(len(batches[0]), 2)
        self.assertEqual(len(batches[2]), 1)

    def test_create_batches_invalid(self):
        with self.assertRaises(ValueError):
            create_batches([1,2], batch_size=0)

    def test_create_batches_preserves_order(self):
        chunks = list(range(10))
        batches = create_batches(chunks, batch_size=3)
        flat = [x for b in batches for x in b]
        self.assertEqual(flat, list(range(10)))


class TestEntityUtils(unittest.TestCase):
    def test_make_entity_id(self):
        self.assertEqual(make_entity_id("Rose Mathew", "PERSON"), "person_rose_mathew")
        self.assertEqual(make_entity_id("Anna Nagar", "LOCATION"), "location_anna_nagar")
        self.assertEqual(make_entity_id("  Sara  ", "PERSON"), "person_sara")

    def test_normalize_name(self):
        self.assertEqual(_normalize_name("Rose Mathew"), "rose mathew")
        self.assertEqual(_normalize_name(" Rose  Mathew! "), "rose mathew")

    def test_person_alias_match(self):
        self.assertTrue(_person_alias_match("Rose Mathew", "Rose M."))
        self.assertTrue(_person_alias_match("R. Mathew", "Rose Mathew"))
        self.assertTrue(_person_alias_match("Rose Mathew", "Rose Mathew"))
        self.assertFalse(_person_alias_match("Rose Mathew", "Ananya Joseph"))
        self.assertFalse(_person_alias_match("Rose Mathew", "R. Kumar"))  # surname mismatch

    def test_merge_single(self):
        ents = [{"name": "Rose Mathew", "type": "PERSON", "confidence": 0.9, "source_file": "fir.txt", "chunk_id": "c1", "aliases": [], "data": {"age": 27}}]
        merged = merge_entities(ents)
        self.assertEqual(len(merged), 1)
        self.assertEqual(merged[0]["name"], "Rose Mathew")
        self.assertEqual(merged[0]["data"]["age"], 27)
        self.assertIn("id", merged[0])

    def test_merge_alias_dedup_person(self):
        ents = [
            {"name": "Rose Mathew", "type": "PERSON", "confidence": 0.9, "source_file": "fir.txt", "chunk_id": "c1", "aliases": [], "data": {}},
            {"name": "Rose M.", "type": "PERSON", "confidence": 0.8, "source_file": "witness.txt", "chunk_id": "c2", "aliases": [], "data": {}},
            {"name": "R. Mathew", "type": "PERSON", "confidence": 0.7, "source_file": "fir.txt", "chunk_id": "c3", "aliases": [], "data": {}},
        ]
        merged = merge_entities(ents)
        self.assertEqual(len(merged), 1)
        self.assertEqual(merged[0]["name"], "Rose Mathew")  # longest preferred
        self.assertIn("R. Mathew", merged[0]["aliases"] + [merged[0]["name"]])

    def test_merge_different_types_not_merged(self):
        ents = [
            {"name": "Rose", "type": "PERSON", "confidence": 0.9, "source_file": "a.txt", "chunk_id": "c1", "aliases": [], "data": {}},
            {"name": "Rose", "type": "LOCATION", "confidence": 0.9, "source_file": "a.txt", "chunk_id": "c1", "aliases": [], "data": {}},
        ]
        merged = merge_entities(ents)
        self.assertEqual(len(merged), 2)

    def test_merge_data_union(self):
        ents = [
            {"name": "Rose Mathew", "type": "PERSON", "confidence": 0.9, "source_file": "a.txt", "chunk_id": "c1", "aliases": [], "data": {"age": 27}},
            {"name": "Rose M.", "type": "PERSON", "confidence": 0.9, "source_file": "b.txt", "chunk_id": "c2", "aliases": [], "data": {"occupation": "Designer"}},
        ]
        merged = merge_entities(ents)
        self.assertEqual(merged[0]["data"]["age"], 27)
        self.assertEqual(merged[0]["data"]["occupation"], "Designer")

    def test_ensure_ids(self):
        ents = [{"name": "Sara", "type": "PERSON", "confidence": 0.9, "source_files": ["a.txt"], "chunk_ids": ["c1"], "aliases": [], "mentions": 1}]
        out = ensure_entity_ids(ents)
        self.assertEqual(out[0]["id"], "person_sara")
        self.assertIn("data", out[0])


class TestGraphMapping(unittest.TestCase):
    def test_simple(self):
        entities = [
            {"id": "person_rose_mathew", "name": "Rose Mathew", "type": "PERSON", "confidence": 0.98, "source_files": ["fir.txt"], "chunk_ids": ["c1"], "aliases": [], "data": {"age": 27}, "mentions": 1},
            {"id": "person_ananya_joseph", "name": "Ananya Joseph", "type": "PERSON", "confidence": 0.96, "source_files": ["witness.txt"], "chunk_ids": ["c2"], "aliases": [], "data": {}, "mentions": 1},
        ]
        rels = [{"source": "Rose Mathew", "relation": "FRIEND_OF", "target": "Ananya Joseph", "confidence": 0.95, "source_file": "witness.txt", "chunk_id": "c2", "evidence_text": "Rose was friends with Ananya"}]
        mappings = build_graph_mappings(entities, rels)
        self.assertEqual(len(mappings), 1)
        m = mappings[0]
        self.assertEqual(m["relation_id"], "rel_001")
        self.assertEqual(m["source"]["name"], "Rose Mathew")
        self.assertEqual(m["target"]["name"], "Ananya Joseph")
        self.assertIn("data", m["source"])
        self.assertEqual(m["source"]["data"]["age"], 27)

    def test_missing_entity_skipped(self):
        entities = [{"id": "person_rose_mathew", "name": "Rose Mathew", "type": "PERSON", "confidence": 0.9, "source_files": [], "chunk_ids": [], "aliases": [], "data": {}, "mentions": 1}]
        rels = [{"source": "Rose Mathew", "relation": "KNOWS", "target": "Ghost Person", "confidence": 0.9, "source_file": "x.txt", "chunk_id": "c1", "evidence_text": "Rose knows Ghost"}]
        mappings = build_graph_mappings(entities, rels)
        self.assertEqual(len(mappings), 0)

    def test_alias_resolution(self):
        entities = [{"id": "person_rose_mathew", "name": "Rose Mathew", "type": "PERSON", "confidence": 0.9, "source_files": [], "chunk_ids": [], "aliases": ["Rose M."], "data": {}, "mentions": 1},
                    {"id": "person_arjun_dev", "name": "Arjun Dev", "type": "PERSON", "confidence": 0.9, "source_files": [], "chunk_ids": [], "aliases": [], "data": {}, "mentions": 1}]
        rels = [{"source": "Rose M.", "relation": "KNOWS", "target": "Arjun Dev", "confidence": 0.9, "source_file": "x.txt", "chunk_id": "c1", "evidence_text": "Rose M. knows Arjun"}]
        mappings = build_graph_mappings(entities, rels)
        self.assertEqual(len(mappings), 1)
        self.assertEqual(mappings[0]["source"]["name"], "Rose Mathew")


class TestParseJson(unittest.TestCase):
    def test_plain_array(self):
        self.assertEqual(parse_json_array('[{"a":1}]'), [{"a": 1}])

    def test_fenced(self):
        txt = '```json\n[{"a": 1}]\n```'
        self.assertEqual(parse_json_array(txt), [{"a": 1}])

    def test_wrapped_object_entities(self):
        txt = '{"entities": [{"name": "Sara"}]}'
        self.assertEqual(parse_json_array(txt), [{"name": "Sara"}])

    def test_single_object(self):
        txt = '{"name": "Sara", "type": "PERSON"}'
        out = parse_json_array(txt)
        self.assertEqual(out, [{"name": "Sara", "type": "PERSON"}])

    def test_array_with_trailing_text(self):
        txt = 'Here is JSON: [{"a": 1}] some trailing'
        out = parse_json_array(txt)
        self.assertEqual(out, [{"a": 1}])


class TestLLMConfig(unittest.TestCase):
    def test_load_env_fallback(self):
        with patch.dict(os.environ, {"OPENAI_API_KEY": "sk-test1234567890", "LLM_MODEL": "gpt-4o-mini"}, clear=False):
            cfg = load_llm_config(project_path=None)
            self.assertEqual(cfg.provider, "openai")
            self.assertTrue(cfg.api_key.startswith("sk-test"))

    def test_load_from_file(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td)
            llm_json = {"provider": "openai", "model": "gpt-4o-mini", "api_key": "sk-file-key-12345", "context_window": 99999}
            (p / "llm.json").write_text(json.dumps(llm_json), encoding="utf-8")
            cfg = load_llm_config(project_path=p)
            self.assertEqual(cfg.model, "gpt-4o-mini")
            self.assertEqual(cfg.context_window, 99999)
            self.assertEqual(cfg.api_key, "sk-file-key-12345")

    def test_provider_alias(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td)
            (p / "llm.json").write_text(json.dumps({"provider": "chatgpt", "api_key": "sk-1234567890"}), encoding="utf-8")
            cfg = load_llm_config(project_path=p)
            self.assertEqual(cfg.provider, "openai")
