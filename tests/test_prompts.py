import unittest
from engine.prompts import (
    ENTITY_SYSTEM_PROMPT,
    RELATION_SYSTEM_PROMPT,
    build_entity_prompt,
    build_relationship_prompt,
    build_timeline_prompt,
    build_single_call_entity_prompt,
    build_graph_mapping_prompt,
    CONTRADICTION_SYSTEM_PROMPT,
    CONTRADICTION_TYPES,
    build_contradiction_prompt,
    build_single_call_contradiction_prompt,
)


class TestPrompts(unittest.TestCase):
    def test_entity_system_contains_types(self):
        self.assertIn("PERSON", ENTITY_SYSTEM_PROMPT)
        self.assertIn("LOCATION", ENTITY_SYSTEM_PROMPT)
        self.assertIn("data", ENTITY_SYSTEM_PROMPT.lower())

    def test_relation_system_contains_canonical(self):
        self.assertIn("FRIEND_OF", RELATION_SYSTEM_PROMPT)
        self.assertIn("CALLED", RELATION_SYSTEM_PROMPT)

    def test_build_entity_prompt_contains_chunks(self):
        chunks = [{"chunk_id": "fir_chunk_001", "source_file": "fir.txt", "text": "Rose Mathew was found dead."}]
        prompt = build_entity_prompt(chunks, previous_entities=[])
        self.assertIn("fir_chunk_001", prompt)
        self.assertIn("Rose Mathew", prompt)
        self.assertIn("fir.txt", prompt)

    def test_build_entity_prompt_previous_entities(self):
        chunks = [{"chunk_id": "c1", "source_file": "a.txt", "text": "hello"}]
        prev = [{"name": "Rose Mathew", "type": "PERSON", "id": "person_rose_mathew", "data": {}}]
        prompt = build_entity_prompt(chunks, previous_entities=prev)
        self.assertIn("Rose Mathew", prompt)
        self.assertIn("PREVIOUSLY EXTRACTED", prompt)

    def test_build_relationship_prompt_grounded(self):
        chunks = [{"chunk_id": "c1", "source_file": "a.txt", "text": "Rose knows Ananya"}]
        entities = [{"name": "Rose Mathew", "type": "PERSON", "id": "person_rose_mathew", "data": {"age": 27}}]
        prompt = build_relationship_prompt(chunks, entities, previous_relationships=[])
        self.assertIn("Rose Mathew", prompt)
        self.assertIn("KNOWN ENTITIES", prompt)
        self.assertIn("age", prompt)

    def test_build_timeline_prompt(self):
        chunks = [{"chunk_id": "c1", "source_file": "a.txt", "text": "On 14 April 2026 at 18:30, Rose was found."}]
        prompt = build_timeline_prompt(chunks, previous_events=[])
        self.assertIn("c1", prompt)
        self.assertIn("a.txt", prompt)
        self.assertIn("timestamp", prompt.lower())

    def test_build_single_call_prompt_truncates_large(self):
        huge = "a" * 500000
        prompt = build_single_call_entity_prompt(huge)
        self.assertLess(len(prompt), 600000)

    def test_build_graph_mapping_prompt(self):
        entities = [{"id": "person_sara", "name": "Sara", "type": "PERSON", "data": {}}]
        rels = [{"source": "Sara", "relation": "KNOWS", "target": "Ananya"}]
        prompt = build_graph_mapping_prompt(entities, rels)
        self.assertIn("Sara", prompt)
        self.assertIn("KNOWS", prompt)
        self.assertIn("relation_id", prompt)

    def test_build_contradiction_prompt(self):
        chunks = [{"chunk_id": "c1", "source_file": "arjun.txt", "text": "Arjun told he went to Delhi"}]
        entities = [{"id": "person_arjun", "name": "Arjun Dev", "type": "PERSON", "data": {}}]
        prompt = build_contradiction_prompt(chunks, known_entities=entities)
        self.assertIn("Arjun Dev", prompt)
        self.assertIn("arjun.txt", prompt)
        self.assertIn("CONTRADICTIONS", prompt)
        self.assertIn("ALIBI_VS_EVIDENCE", CONTRADICTION_TYPES)
