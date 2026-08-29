# Sherlock — AI Investigation Knowledge Graph

## Project Goal

Sherlock is a **desktop investigation intelligence application** that converts unstructured case information into a structured **knowledge graph**.

Users create investigation cases and provide text-based evidence. Sherlock processes the evidence, extracts entities and relationships, builds a graph, and allows future investigation features such as timeline reconstruction, evidence tracking, contradiction detection, and natural-language querying.

The project is being built as a **48-hour hackathon MVP**.

### Core MVP goal

```text
Raw Investigation Data
        ↓
Text Warehouse
        ↓
Chunking
        ↓
Entity Extraction
        ↓
Relationship Extraction
        ↓
Knowledge Graph
        ↓
Graph Visualization
```

Do not over-engineer the project.

The initial milestone is:

> **Take messy case text → automatically build a useful, explainable knowledge graph.**

---

# System Architecture

Sherlock consists of two primary components:

```text
┌───────────────────────────┐
│       Java Desktop App    │
│                           │
│ Case Management           │
│ File Input                │
│ Local Folder Management   │
└──────────────┬────────────┘
               │
               │ warehouse.txt
               ▼
┌───────────────────────────┐
│       Python Engine       │
│                           │
│ Text Processing           │
│ Chunking                  │
│ Entity Extraction         │
│ Relationship Extraction   │
│ Graph Construction        │
└──────────────┬────────────┘
               │
               ▼
┌───────────────────────────┐
│   File-based Graph        │
│  (processed/ + Neo4j next)│
│                           │
│ relations.json            │
│ graph_data.json           │
│  → Neo4j (future)         │
└───────────────────────────┘
```

---

# 1. Java Desktop Application

Java is responsible for the **user-facing desktop application and local project management**.

Java should NOT perform heavy NLP, LLM extraction, or knowledge graph reasoning.

Its responsibilities are:

* Create and manage investigation cases.
* Handle user input.
* Handle file selection.
* Create local folders.
* Store raw evidence files.
* Maintain the project's local file structure.
* Generate/update `warehouse.txt`.
* Trigger the Python processing pipeline.
* Later display graph results and investigation data.

---

# 2. Local Folder Structure

When the user installs and opens Sherlock, the application should create:

```text
Documents/
└── Sherlock/
```

When a user creates a new case:

Example case:

```text
Operation Rose
```

Create:

```text
Documents/
└── Sherlock/
    └── Operation Rose/
        ├── data/           # raw .txt evidence (copied)
        ├── processed/      # chunks.json, entities.json, relations.json, graph_data.json
        ├── llm.json        # per-case LLM config (provider/model/api_key) — written by UI
        └── warehouse.txt   # combined source-boundaried text
```

`graph/` is reserved for future Neo4j/visualization; current MVP uses `processed/` only.

The folder name should be based on the case/project name provided by the user.

Use safe filename normalization if necessary.

Example:

```text
Operation Rose
```

may become:

```text
operation_rose
```

internally if required.

---

# 3. Raw Data Storage

All user-provided `.txt` files should be copied into:

```text
{project_name}/data/
```

Example:

```text
Documents/
└── Sherlock/
    └── operation_rose/
        ├── data/
        │   ├── fir.txt
        │   ├── postmortem_report.txt
        │   ├── witness_statement.txt
        │   └── call_logs.txt
        │
        └── warehouse.txt
```

The original raw files should remain unchanged.

---

# 4. Warehouse System

Java should combine the text content from all files in:

```text
{project_name}/data/
```

into one master file:

```text
warehouse.txt
```

However, the warehouse must preserve the original source boundaries.

Required format:

```text
========================================
SOURCE_FILE: fir.txt
SOURCE_TYPE: TEXT
========================================

[file contents]

========================================
END_SOURCE: fir.txt
========================================


========================================
SOURCE_FILE: postmortem_report.txt
SOURCE_TYPE: TEXT
========================================

[file contents]

========================================
END_SOURCE: postmortem_report.txt
========================================
```

This is extremely important.

The Python pipeline must always know which extracted information came from which original file.

Do NOT simply concatenate all text without metadata.

---

# 5. Python Processing Engine

Once `warehouse.txt` is ready, Java triggers the Python processing engine.

Python is responsible for:

```text
warehouse.txt
       ↓
Token-window check (vs LLM context window, e.g. 500k) → decide batched vs single-call
       ↓
Document Parsing
       ↓
Chunking (semantic, per-source provenance)
       ↓
Entity Extraction (batched LLM, 20 chunks/batch, dedup via previous-batch context)
       ↓
Relationship Extraction (batched LLM, grounded on entities with data)
       ↓
Graph Construction (node-relation-node mapping with embedded data → relation_id)
       ↓
File-based Graph (processed/relations.json + graph_data.json) → Neo4j (future)
```

The Python engine is modular but intentionally flat for hackathon speed.

Actual structure (implemented):

```text
./
├── main.py                          # warehouse → token check → chunking → optional --extract
├── llm.json.example                 # per-provider template (see §5.1)
│
├── engine/
│   ├── chunk.py                     # semantic chunking, warehouse source parsing
│   ├── llm.py                       # LLM client (multi-provider), token estimate, batching, merge/dedup, graph mappings
│   ├── prompts.py                   # prompts for entity / relation / graph mapping (with data field)
│   ├── entity_extraction.py         # orchestrates entity → relation → graph, writes processed/*.json
│   ├── graph_builder.py             # deterministic + LLM graph mapping: relation_id : node1 relation node2
│   └── crud.py                      # (reserved for Neo4j CRUD, currently empty)
```

Do not combine everything into one massive Python file, but do not over-split either.

## 5.1 LLM Configuration — per-case `llm.json`

The UI collects LLM settings and writes `<case>/llm.json`. Python never hard-codes keys.

```json
{
  "provider": "openai | openrouter | deepseek | groq | together | mistral | ollama | custom",
  "model": "gpt-4o-mini",
  "api_key": "sk-...",
  "base_url": "https://api.openai.com/v1",
  "context_window": 128000,
  "temperature": 0.1
}
```

Flexible keys accepted (`provider`/`provider_name`, `model`/`model_name`, `api_key`/`apiKey`/`key`, `base_url`/`baseUrl`). `engine/llm.py:PROVIDER_REGISTRY` maps each provider to its default `base_url` and `context_window` (e.g. `openai 128k`, `openrouter 200k`, `deepseek 64k`, `ollama http://localhost:11434/v1`). Priority: `<case>/llm.json` → env (`OPENAI_API_KEY`, `OPENROUTER_API_KEY`, `DEEPSEEK_API_KEY`, `LLM_API`) → CLI `--model`/`--context-window` overrides. All providers are OpenAI-compatible via `openai.OpenAI` SDK; `extra_headers` (e.g. OpenRouter `HTTP-Referer`) are injected automatically. `main.py` and `entity_extraction.py` resolve config via `load_llm_config(project_path)` and log the masked source.

---

# 6. Chunking Strategy

The Python pipeline reads:

```text
warehouse.txt
```

The system must preserve:

* Source filename
* Source document ID
* Character position or chunk number
* Chunk text

Example chunk (as written by `engine/chunk.py:501`):

```json
{
  "chunk_id": "fir_001_chunk_03",
  "source_file": "fir.txt",
  "chunk_index": 3,
  "text": "...",
  "metadata": {
    "char_start": 0,
    "char_end": 1200,
    "warehouse_char_start": 134,
    "warehouse_char_end": 1334,
    "char_length": 1200
  }
}
```

Implemented chunking (`engine/chunk.py`) is **semantic** (sentence split → TF-IDF cosine → percentile breakpoint) with char-based defaults `DEFAULT_CHUNK_SIZE=1200` / `DEFAULT_OVERLAP=200` (≈300 tokens), configurable via `main.py --chunk-size`/`--overlap`.

## Token-window / batching strategy (replaces small vs large thresholds)

After `warehouse.txt` is loaded, `engine/llm.py` estimates tokens (`tiktoken` if available else `max(chars/4, words*1.33)`) and compares to the LLM `context_window` from `llm.json` (see §5.1, default 128k–500k per provider).

```text
estimated_tokens <= context_window → strategy=batched (20 chunks/batch with previous-batch dedup) or single_call if --single-call
estimated_tokens >  context_window → strategy=batched (forced, 20 chunks/batch)
```

Batched mode sends `20` chunks per LLM call and includes `previous_entities`/`previous_relationships` so the LLM must reuse canonical names (`Rose Mathew` not `Rose M.`) and skip duplicate `(source,relation,target)`. Local `merge_entities()` also collapses aliases (`R. Mathew` / `Rose M.` / `Arjun D.` / `A. Dev`) via first-name + surname-initial matching and merges `data` fields. Final decision is logged with `batches_needed = ceil(chunks/20)` and saved in `processed/chunks.json` already handles per-source provenance via warehouse marker parsing (`SOURCE_FILE:` / `END_SOURCE:`).

---

# 7. Extraction Pipeline

Extraction should be separated into multiple stages.

Do not ask one LLM call to perform everything in an uncontrolled manner.

Recommended pipeline:

```text
CHUNK
  ↓
ENTITY EXTRACTION
  ↓
RELATIONSHIP EXTRACTION
  ↓
ENTITY NORMALIZATION
  ↓
GRAPH RECORD CREATION
```

---

# 8. Entity Extraction

Extract useful entities from the text.

Initial entity types:

```text
PERSON
LOCATION
ORGANIZATION
EVENT
DOCUMENT
DATE
OBJECT
PHONE_NUMBER
```

Example:

Input (chunk `fir_001_chunk_03`):

```text
Rose Mathew was found dead in her apartment in Anna Nagar.
```

Extract (via `engine/prompts.py` + `engine/llm.py:extract_entities_batched`, 20 chunks/batch):

```json
[
  {
    "id": "person_rose_mathew",
    "name": "Rose Mathew",
    "type": "PERSON",
    "confidence": 0.98,
    "source_file": "fir.txt",
    "chunk_id": "fir_001_chunk_03",
    "aliases": ["R. Mathew", "Rose M."],
    "data": {"age": 27, "occupation": "Freelance Graphic Designer", "phone": "+91-90000-10001", "address": "Flat 3B, Green View Apartments"},
    "mentions": 1
  },
  {
    "id": "location_anna_nagar",
    "name": "Anna Nagar",
    "type": "LOCATION",
    "confidence": 0.99,
    "source_file": "fir.txt",
    "chunk_id": "fir_001_chunk_03",
    "aliases": [],
    "data": {"city": "Chennai", "type": "neighbourhood"},
    "mentions": 1
  }
]
```

Each extracted entity retains provenance **and rich `data`**. The LLM is prompted (`ENTITY_SYSTEM_PROMPT`) to return `data` as a JSON object with relevant attributes (PERSON → age/occupation/phone/address; LOCATION → city/address; DOCUMENT → reference; etc., or `{}` if none). `engine/llm.py:make_entity_id` creates deterministic `id` (`type_lower + "_" + slug`), and `merge_entities()` collapses aliases (`Rose M.`/`R. Mathew` → `Rose Mathew`) and unions `data` fields.

---

# 9. Relationship Extraction

Relationships are extracted in a **second batched stage** grounded on the already-extracted entities **with data**.

Example input (chunk `witness_001_chunk_02` + entities `Rose Mathew {data}`, `Ananya Joseph {data}`):

```text
Rose Mathew was friends with Ananya Joseph.
```

`engine/prompts.py:build_relationship_prompt` sends `KNOWN ENTITIES: [{id, name, type, data}]` + previous batch relations for dedup.

Output (`processed/relations.json` via `engine/llm.py:extract_relationships_batched`):

```json
{
  "source": "Rose Mathew",
  "relation": "FRIEND_OF",
  "target": "Ananya Joseph",
  "confidence": 0.95,
  "source_file": "witness_statement.txt",
  "chunk_id": "witness_001_chunk_02",
  "evidence_text": "Rose Mathew was friends with Ananya Joseph"
}
```

Raw relations are stored in both `processed/relations.json` (spec name) and `processed/relationships.json` (compat).

Initial relationship types can include:

```text
FRIEND_OF
KNOWS
CALLED
MESSAGED
MET_WITH
SEEN_AT
LOCATED_AT
WORKS_FOR
ASSOCIATED_WITH
PARTICIPATED_IN
MENTIONED_IN
RELATED_TO
```

The system may also allow an LLM to generate useful relationship labels.

However, normalize relationship labels where possible.

Example:

```text
"knows"
"KNOWS"
"is acquainted with"
```

should preferably become:

```text
KNOWS
```

---

# 10. Node → Relationship → Node Model

The primary graph structure is:

```text
NODE
  ↓
RELATIONSHIP
  ↓
NODE
```

Example:

```text
Rose Mathew
      │
      │ FRIEND_OF
      ▼
Ananya Joseph
```

However, nodes and relationships must contain useful metadata.

Example node (as stored in `processed/entities.json`):

```json
{
  "id": "person_rose_mathew",
  "name": "Rose Mathew",
  "type": "PERSON",
  "confidence": 0.98,
  "source_files": ["fir.txt", "postmortem_report.txt"],
  "chunk_ids": ["fir_001_chunk_03"],
  "aliases": ["R. Mathew", "Rose M."],
  "data": {"age": 27, "occupation": "Freelance Graphic Designer", "phone": "+91-90000-10001"},
  "mentions": 14
}
```

Raw relationship (`processed/relations.json`):

```json
{
  "source": "Rose Mathew",
  "relation": "FRIEND_OF",
  "target": "Ananya Joseph",
  "confidence": 0.95,
  "source_file": "witness_statement.txt",
  "chunk_id": "witness_001_chunk_02",
  "evidence_text": "Rose Mathew was friends with Ananya Joseph"
}
```

Enriched mapping (`processed/graph_data.json` via `engine/graph_builder.py:build_graph_mappings`):

```json
{
  "relation_id": "rel_001",
  "source": {"id": "person_rose_mathew", "name": "Rose Mathew", "type": "PERSON", "data": {"age": 27}, "source_files": ["fir.txt"], "chunk_ids": ["fir_001_chunk_03"], "aliases": ["R. Mathew"]},
  "relation": "FRIEND_OF",
  "target": {"id": "person_ananya_joseph", "name": "Ananya Joseph", "type": "PERSON", "data": {}},
  "confidence": 0.95,
  "evidence_text": "Rose Mathew was friends with Ananya Joseph",
  "source_file": "witness_statement.txt",
  "chunk_id": "witness_001_chunk_02"
}
```

Deterministic mapping is default; `engine/llm.py:create_graph_mappings_via_llm` + `engine/prompts.py:GRAPH_MAPPING_SYSTEM_PROMPT` can also generate mappings via LLM (fallback to deterministic if LLM returns invalid).

---

# 11. Important: Node Data

Nodes should not be empty circles containing only names.

Each node should accumulate information.

Example:

```text
Rose Mathew
│
├── Type: PERSON
├── Mentions: 14
├── Source Documents: 5
├── Related Events: 7
├── Relationships: 9
└── Evidence References: 12
```

The graph visualization can later show:

```text
Rose Mathew
PERSON

Connected to:
• Arjun Dev
• Ananya Joseph
• Meera Krishnan

Events:
• Meeting
• Last Phone Activity
• Discovery Event
```

---

# 12. Confidence Scores

Every extracted relationship should have a confidence score.

Example:

```text
Rose ── FRIEND_OF (0.96) ── Ananya
```

Initial confidence sources can be:

```text
LLM extraction confidence
+
NER confidence where available
+
Repeated mention count
```

For the MVP, a simple confidence score is sufficient.

Do not build a complex probabilistic reasoning engine.

---

# 13. Graph Storage (file-first, Neo4j next)

Current MVP is **file-based**; Neo4j is the next step and shares the same schema.

After extraction, `engine/graph_builder.py:save_graph_outputs()` writes:

```text
{project}/processed/relations.json   # raw relations (spec name)
{project}/processed/relationships.json # compat alias
{project}/processed/graph_data.json  # enriched mappings: relation_id : node{data} relation node{data}
{project}/processed/graph.json       # alias of graph_data.json
```

Example conceptual Neo4j structure (future):

```text
(:Person {name: "Rose Mathew", id: "person_rose_mathew"})
        │
        │ [:FRIEND_OF {confidence: 0.95}]
        │
        ▼
(:Person {name: "Ananya Joseph"})
```

Nodes should include:

```text
id            # person_rose_mathew (engine/llm.py:make_entity_id)
name
type
confidence
data          # JSON metadata (age, occupation, phone, address, ...)
project_id    # case folder name — isolates cases
mention_count # mentions across chunks
source_files  # ["fir.txt", ...]
chunk_ids
aliases
```

Relationships should include:

```text
relation_id   # rel_001
type / relation # FRIEND_OF
confidence
project_id
source_file   # provenance
chunk_id
evidence_text
```

Every graph record must belong to a specific project (`project` field in JSON, `project_id` in Neo4j). Do not mix data between cases. `engine/crud.py` is reserved for Neo4j CRUD (currently empty).

---

# 14. Graph Visualization

The graph visualization is one of the main demo features.

The user should be able to:

* View all major entities.
* Click nodes.
* See relationships.
* Inspect node metadata.
* Filter by entity type.
* View connected nodes.

For the hackathon MVP:

```text
Click Node
      ↓
Show:
Name
Type
Relationships
Source Documents
Evidence Mentions
```

Avoid trying to render thousands of nodes simultaneously.

---

# 15. Current Development Milestone

## First ~10 Hours

The priority is ONLY:

```text
CREATE CASE
     ↓
CREATE LOCAL FOLDER
     ↓
ADD TEXT FILES
     ↓
BUILD warehouse.txt
     ↓
READ warehouse.txt IN PYTHON
     ↓
CHUNK DATA
     ↓
EXTRACT ENTITIES
     ↓
EXTRACT RELATIONSHIPS
     ↓
BUILD GRAPH
     ↓
STORE IN FILE (graph_data.json) → Neo4j (next)
     ↓
VISUALIZE GRAPH
```

If this pipeline works end-to-end, the MVP foundation is successful.

---

# 16. Future Features — Only After Core Pipeline Works

Do NOT work on these until the core pipeline works.

Future features:

```text
Evidence system
Contradiction detection
Timeline reconstruction
Natural language graph querying
Image analysis
PDF support
Automatic findings
```

Priority order:

```text
Priority 1:
Core graph pipeline

Priority 2:
Graph visualization

Priority 3:
Timeline

Priority 4:
Evidence

Priority 5:
Contradictions

Priority 6:
Natural language querying
```

---

# Development Principles

## Keep it simple

This is a hackathon MVP.

Avoid:

* Microservices
* Kafka
* Kubernetes
* Complex authentication
* Complex distributed systems
* Multiple databases unless absolutely necessary
* Advanced probabilistic reasoning
* Overly complicated agent systems

The goal is a working demonstration.

## Every fact needs provenance

The most important design principle is:

```text
ENTITY
+
RELATIONSHIP
+
SOURCE FILE
+
SOURCE CHUNK
```

Every important graph relationship should be traceable to the original data.

## The pipeline should be inspectable

At every stage, developers should be able to inspect:

```text
Raw Data
↓
Chunks
↓
Extracted Entities
↓
Extracted Relationships
↓
Neo4j Graph
```

Save intermediate JSON outputs in:

```text
{project_name}/processed/
```

Example (as implemented):

```text
processed/
├── chunks.json          # 235 chunks with warehouse offsets
├── entities.json        # {id,name,type,confidence,source_files,chunk_ids,aliases,data,mentions}
├── relations.json       # raw relations (spec) + relationships.json alias
├── graph_data.json      # enriched: relation_id : node{data} relation node{data} (+ graph.json alias)
└── llm.json             # per-case config (if case folder, or llm.json.example at root)
```

This will make debugging dramatically easier.

---

# Definition of Success

For the first milestone, Sherlock is successful if:

1. User creates a case.
2. Sherlock creates the local case folder.
3. User adds multiple text files.
4. Files are stored in the project data folder.
5. `warehouse.txt` is generated with source boundaries.
6. Python processes the warehouse.
7. Entities are extracted.
8. Relationships are extracted.
9. Data is stored in file (`processed/graph_data.json` → Neo4j next).
10. A graph can be visualized.
11. Clicking a node shows its underlying extracted information.

That is the first working Sherlock MVP.

Everything else comes after this works.
