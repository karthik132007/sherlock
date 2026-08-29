"""
engine/prompts.py — LLM prompt templates for Sherlock investigation pipeline.

Centralises all prompts so entity/relationship extraction stays consistent
and the investigation domain logic is not scattered across llm.py.
"""

from __future__ import annotations

import json
from typing import Any, Dict, List

# ---------------------------------------------------------------------------
# Domain vocab — mirrors AGENTS.md §8 / §9
# ---------------------------------------------------------------------------

ENTITY_TYPES = [
    "PERSON",
    "LOCATION",
    "ORGANIZATION",
    "EVENT",
    "DOCUMENT",
    "DATE",
    "OBJECT",
    "PHONE_NUMBER",
]

RELATIONSHIP_TYPES = [
    "FRIEND_OF",
    "KNOWS",
    "CALLED",
    "MESSAGED",
    "MET_WITH",
    "SEEN_AT",
    "LOCATED_AT",
    "WORKS_FOR",
    "ASSOCIATED_WITH",
    "PARTICIPATED_IN",
    "MENTIONED_IN",
    "RELATED_TO",
]

# ---------------------------------------------------------------------------
# System prompts
# ---------------------------------------------------------------------------

ENTITY_SYSTEM_PROMPT = """You are Sherlock, an investigation AI that extracts structured entities from messy case text.

Your job: extract every meaningful entity with provenance, normalise aliases, and avoid duplicates.

RULES:
- Only use these entity types: PERSON, LOCATION, ORGANIZATION, EVENT, DOCUMENT, DATE, OBJECT, PHONE_NUMBER
- Every entity MUST have: name, type, confidence (0.0-1.0), data (JSON object with metadata)
- Normalise aliases: "Rose Mathew" = "Rose M." = "R. Mathew" -> canonical "Rose Mathew". Same for Arjun Dev / Arjun D. / A. Dev, Meera Krishnan / Meera K., Vikram Rao / Vikram R., Ananya Joseph / Ananya J.
- Phone numbers: normalise to +91-XXXXX-XXXXX if present.
- data field: include relevant attributes extracted from text as JSON. Examples:
  PERSON Sara -> {"age": 27, "gender": "Female", "occupation": "Freelance Designer", "phone": "+91-90000-10001", "address": "Flat 3B, Anna Nagar"}
  LOCATION Anna Nagar -> {"address": "12/45 3rd Main Road, Anna Nagar", "city": "Chennai", "type": "apartment"}
  ORGANIZATION Loyola College -> {"org_type": "College", "location": "Chennai"}
  EVENT Discovery -> {"date": "2026-04-14", "time": "18:30 IST", "location": "Flat 3B"}
  DOCUMENT FIR -> {"doc_type": "FIR", "reference": "ANPS-2026-0414-03"}
  DATE 14 April 2026 -> {"value": "2026-04-14", "raw": "14 April 2026"}
  OBJECT Glass -> {"description": "glass with orange liquid", "location": "bedside table"}
  PHONE_NUMBER -> {"value": "+91-90000-10001", "owner": "Rose Mathew"}
  If no attributes found, use empty object {}.
- Do NOT hallucinate — only entities actually mentioned in the provided chunk text. Only include data fields supported by text.
- If a previous_entities list is provided, REUSE canonical names from it. Do NOT create a new variant if the same real-world entity already exists (e.g. if "Rose Mathew" already exists, do not also emit "Rose M.").
- Return ONLY valid JSON — no markdown, no explanation.
"""

RELATION_SYSTEM_PROMPT = """You are Sherlock, an investigation AI that extracts relationships between entities.

RULES:
- Use these canonical relation types when possible: FRIEND_OF, KNOWS, CALLED, MESSAGED, MET_WITH, SEEN_AT, LOCATED_AT, WORKS_FOR, ASSOCIATED_WITH, PARTICIPATED_IN, MENTIONED_IN, RELATED_TO
- If no canonical type fits, you MAY propose a new UPPER_SNAKE_CASE label (e.g. PRESCRIBED, SENT_MESSAGE) — but prefer canonical ones and normalise variants: "knows" / "is acquainted with" / "KNOWS" -> KNOWS
- Every relationship: source, relation, target, confidence (0.0-1.0)
- source/target must be canonical entity names from the provided entity list (or discovered in text if missing — but prefer provided list).
- Provide short evidence_text (verbatim 5-15 words from chunk) that supports the relation.
- Do NOT invent relationships not supported by text.
- Previous relationships are provided to avoid duplicates — if same (source, relation, target) already exists, SKIP it.
- Return ONLY valid JSON — no markdown, no explanation.
"""

# ---------------------------------------------------------------------------
# Prompt builders
# ---------------------------------------------------------------------------

def build_entity_prompt(
    batch_chunks: List[Dict[str, Any]],
    previous_entities: List[Dict[str, Any]] | None = None,
) -> str:
    """
    Build user prompt for entity extraction on a batch of chunks.
    Includes previous batch entities for deduplication.

    Args:
        batch_chunks: list of chunk dicts {chunk_id, source_file, text}
        previous_entities: entities already extracted in prior batches (canonical list)

    Returns:
        Prompt string ready to send as user message.
    """
    previous_entities = previous_entities or []
    prev_json = json.dumps(previous_entities, indent=2, ensure_ascii=False) if previous_entities else "[] (no previous entities — this is the first batch)"

    # Serialise batch with provenance markers so LLM can cite source_file/chunk_id
    batch_text_parts: List[str] = []
    for ch in batch_chunks:
        batch_text_parts.append(
            f"--- CHUNK {ch['chunk_id']} | SOURCE: {ch['source_file']} ---\n{ch['text']}\n"
        )
    batch_text = "\n".join(batch_text_parts)

    # Keep prompt under context: truncate if huge (approx)
    if len(batch_text) > 120_000:
        batch_text = batch_text[:120_000] + "\n...[TRUNCATED]"

    prompt = f"""Extract entities from the following investigation chunks.

PREVIOUSLY EXTRACTED ENTITIES (re-use canonical names, do not duplicate):
{prev_json}

CHUNKS TO PROCESS (batch size {len(batch_chunks)}):
{batch_text}

TASK:
1. Extract all entities of types: PERSON, LOCATION, ORGANIZATION, EVENT, DOCUMENT, DATE, OBJECT, PHONE_NUMBER
2. For each entity provide:
   - name: canonical form (deduplicated)
   - type: one of the allowed types
   - confidence: 0.0-1.0
   - source_file: from chunk header
   - chunk_id: from chunk header
   - aliases: list of alternate spellings seen (e.g. ["Rose M.", "R. Mathew"]) — omit if none
   - data: JSON object with metadata attributes (see RULES). Must always be present, even if {{}}.
3. If an entity was already in PREVIOUSLY EXTRACTED, do NOT re-emit it unless you found a NEW alias or NEW data. Instead skip it.
4. Normalise person aliases as described.

OUTPUT FORMAT — JSON array (empty array [] if none found):
[
  {{
    "name": "Rose Mathew",
    "type": "PERSON",
    "confidence": 0.98,
    "source_file": "01_case_summary.txt",
    "chunk_id": "01_case_summary_chunk_001",
    "aliases": ["Rose M.", "R. Mathew"],
    "data": {{"age": 27, "occupation": "Freelance Graphic Designer", "phone": "+91-90000-10001", "address": "Flat 3B, Green View Apartments, Anna Nagar"}}
  }},
  {{
    "name": "Sara",
    "type": "PERSON",
    "confidence": 0.95,
    "source_file": "witness.txt",
    "chunk_id": "witness_chunk_002",
    "aliases": [],
    "data": {{"age": 26, "occupation": "Student", "description": "witness"}}
  }}
]

Return ONLY the JSON array.
"""
    return prompt


def build_relationship_prompt(
    batch_chunks: List[Dict[str, Any]],
    entities_in_context: List[Dict[str, Any]],
    previous_relationships: List[Dict[str, Any]] | None = None,
) -> str:
    """
    Build user prompt for relationship extraction for a batch.
    Sends full entities with data (name, type, id, data) so LLM can use metadata.
    """
    previous_relationships = previous_relationships or []
    prev_rel_json = json.dumps(previous_relationships, indent=2, ensure_ascii=False) if previous_relationships else "[] (first batch)"

    # Send full entity objects with data — crucial per user spec
    entity_list_str = json.dumps(
        [
            {
                "id": e.get("id") or f"{e.get('type','').lower()}_{e.get('name','').lower().replace(' ','_')}",
                "name": e["name"],
                "type": e["type"],
                "data": e.get("data", {}),
            }
            for e in entities_in_context
        ],
        indent=2,
        ensure_ascii=False,
    ) if entities_in_context else "[]"

    batch_text_parts: List[str] = []
    for ch in batch_chunks:
        batch_text_parts.append(
            f"--- CHUNK {ch['chunk_id']} | SOURCE: {ch['source_file']} ---\n{ch['text']}\n"
        )
    batch_text = "\n".join(batch_text_parts)
    if len(batch_text) > 120_000:
        batch_text = batch_text[:120_000] + "\n...[TRUNCATED]"

    prompt = f"""Extract relationships between entities from these chunks.

KNOWN ENTITIES (full objects with data — use canonical names/ids below):
{entity_list_str}

PREVIOUSLY EXTRACTED RELATIONSHIPS (skip duplicates — same source/relation/target):
{prev_rel_json}

CHUNKS:
{batch_text}

TASK:
1. For each relationship evident in the text, emit:
   - source: canonical entity name
   - relation: UPPER_SNAKE_CASE (prefer {", ".join(RELATIONSHIP_TYPES)})
   - target: canonical entity name
   - confidence: 0.0-1.0
   - source_file, chunk_id (from header)
   - evidence_text: short verbatim snippet (5-15 words)
2. Only emit relations where BOTH source and target are either in KNOWN ENTITIES or clearly mentioned in chunk text.
3. Skip any (source, relation, target) already in PREVIOUSLY EXTRACTED.
4. Normalise relation labels: "knows", "KNOWS", "is acquainted with" -> KNOWS

OUTPUT FORMAT — JSON array:
[
  {{
    "source": "Rose Mathew",
    "relation": "FRIEND_OF",
    "target": "Ananya Joseph",
    "confidence": 0.95,
    "source_file": "06_witness_statement_ananya.txt",
    "chunk_id": "06_witness_statement_ananya_chunk_042",
    "evidence_text": "Rose Mathew was friends with Ananya Joseph"
  }}
]

Return ONLY the JSON array.
"""
    return prompt


def build_single_call_entity_prompt(
    warehouse_text: str,
    chunk_metadata: List[Dict[str, Any]] | None = None,
) -> str:
    """
    Prompt when the whole warehouse fits in context — send entire file.
    chunk_metadata optional: list of {source_file, chunk_id} ranges to help LLM cite provenance.
    """
    if len(warehouse_text) > 400_000:
        # Hard cut for safety even if token window says 500k (chars ~ tokens*4)
        warehouse_text = warehouse_text[:400_000] + "\n...[WAREHOUSE TRUNCATED]"

    prompt = f"""Extract ALL entities from the full investigation warehouse below.

The warehouse contains source boundaries like:
  SOURCE_FILE: xyz.txt ... END_SOURCE: xyz.txt
Use those to set source_file for each entity. If chunk_metadata were provided, cite the most relevant chunk_id nearby.

WAREHOUSE TEXT:
{warehouse_text}

TASK — same rules as batch mode:
- Types: {", ".join(ENTITY_TYPES)}
- Normalise aliases (Rose Mathew = Rose M. = R. Mathew, etc.)
- Provide name, type, confidence, source_file, chunk_id (estimate chunk_id if not provided: e.g. source_file + "_chunk_001"), aliases, data (JSON metadata per RULES)
- Example: Sara PERSON -> {{"name":"Sara","type":"PERSON","confidence":0.95,"source_file":"x.txt","chunk_id":"x_chunk_001","aliases":[],"data":{{"age":26}}}}
- No hallucinations, valid JSON array only.

OUTPUT: JSON array as specified in batch prompt.
"""
    return prompt


def build_normalization_prompt(entities: List[Dict[str, Any]]) -> str:
    """
    Prompt to deduplicate/merge entity list after all batches.
    LLM-as-judge for alias resolution.
    """
    raw = json.dumps(entities, indent=2, ensure_ascii=False)
    return f"""You are given a raw entity list extracted from batched chunks. Deduplicate aliases.

RAW ENTITIES:
{raw}

TASK:
- Merge entities that refer to same real person/object (e.g. "Arjun Dev" + "Arjun D." + "A. Dev" -> single "Arjun Dev")
- Keep the most complete canonical name.
- Merge source_files, aliases, and data (union of data fields, prefer non-empty values), keep max confidence.
- Return deduplicated JSON array with same schema but with merged source_files (array), aliases (array), data (object).

Return ONLY JSON array.
"""


# ---------------------------------------------------------------------------
# Graph mapping prompt — entities + relations -> relation_id : node1 relation node2
# ---------------------------------------------------------------------------

GRAPH_MAPPING_SYSTEM_PROMPT = """You are Sherlock, an investigation graph builder.

You are given ALL extracted entities (with id, name, type, data) and ALL extracted relationships (source, relation, target, confidence, evidence).

Your task: produce the final knowledge graph as a list of relation-centered objects with rich node embeddings.

Rules:
- Each entity must have an id: lowercased type + "_" + normalized name (e.g. person_sara, person_rose_mathew, location_anna_nagar). If id missing, generate it.
- For each relationship, output one object with:
  relation_id: "rel_001", "rel_002", ...
  source: full node object {id, name, type, data, source_files, chunk_ids, aliases if any}
  relation: UPPER_SNAKE_CASE (from input)
  target: full node object {id, name, type, data, source_files, chunk_ids, aliases if any}
  confidence, evidence_text, source_file, chunk_id (from relationship)
- Use EXACT entity objects from input (do not invent new entities). Embed the full data.
- If a relationship references an entity not in the entity list, SKIP it.
- Preserve provenance and confidence.
- Return ONLY valid JSON — no markdown.
"""

def build_graph_mapping_prompt(
    entities: List[Dict[str, Any]],
    relationships: List[Dict[str, Any]],
) -> str:
    """
    Build prompt for graph mapping: entities+relations -> relation_id node1 relation node2
    Sends full entities with data + relations (per user spec).
    """
    entities_json = json.dumps(entities, indent=2, ensure_ascii=False)
    relationships_json = json.dumps(relationships, indent=2, ensure_ascii=False)

    # Truncate if huge
    if len(entities_json) > 80000:
        entities_json = entities_json[:80000] + "\n...[TRUNCATED]"
    if len(relationships_json) > 80000:
        relationships_json = relationships_json[:80000] + "\n...[TRUNCATED]"

    prompt = f"""Build the final knowledge graph from entities and relationships below.

ENTITIES (with data):
{entities_json}

RELATIONSHIPS:
{relationships_json}

TASK:
1. Assign each relationship a relation_id: rel_001, rel_002, ... in order given.
2. For each relationship, embed the full source and target node objects (with id, name, type, data, source_files, chunk_ids, aliases).
   Example entity id generation: Sara PERSON -> id="person_sara"
3. Output a JSON array where each element is:
{{
  "relation_id": "rel_001",
  "source": {{"id": "person_sara", "name": "Sara", "type": "PERSON", "data": {{"age": 26}}, "source_files": ["witness.txt"], "chunk_ids": ["witness_chunk_001"], "aliases": []}},
  "relation": "FRIEND_OF",
  "target": {{"id": "person_ananya_joseph", "name": "Ananya Joseph", "type": "PERSON", "data": {{}}, "source_files": ["witness.txt"], "chunk_ids": ["witness_chunk_001"], "aliases": []}},
  "confidence": 0.95,
  "evidence_text": "Sara was friends with Ananya",
  "source_file": "witness.txt",
  "chunk_id": "witness_chunk_001"
}}

4. Use ONLY entities/relationships from input. Do not hallucinate.
5. If input already has relation_id, keep it; otherwise generate sequential.

Return ONLY the JSON array of graph mappings.
"""
    return prompt

