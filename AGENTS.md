# Sherlock Agent Guide

## Mission

Sherlock turns raw investigation evidence into a structured, explainable graph. The system is meant to be practical and auditable: every fact should be traceable to a specific source file, chunk, and evidence excerpt.

The project is split across three layers:

- JavaFX desktop client for user experience and visualization
- Spring Boot API for case orchestration and external integrations
- Python engine for warehouse processing, extraction, timelines, contradictions, and query logic

This repo is not a toy pipeline. It is a working investigation workflow with real case data, model-backed extraction, and graph outputs.

---

## Architecture map

```text
JavaFX UI
  ├─ create/list cases
  ├─ upload evidence files
  ├─ show graph / details / timeline / contradictions
  ├─ chat panel and LLM settings
  └─ optional Neo4j actions
        │
        ▼
Spring Boot backend
  ├─ REST APIs for case lifecycle
  ├─ file upload handling
  ├─ pipeline triggering for Python jobs
  ├─ case-specific chat and history
  ├─ Neo4j sync / query execution
  └─ processing status tracking
        │
        ▼
Python engine
  ├─ warehouse creation and validation
  ├─ token-window and batching strategy
  ├─ semantic chunking with provenance
  ├─ entity extraction
  ├─ relationship extraction
  ├─ timeline extraction
  ├─ contradictions detection
  ├─ graph output generation
  └─ bounded natural-language query agent
        │
        ▼
Processed outputs
  ├─ warehouse.txt
  ├─ processed/chunks.json
  ├─ processed/entities.json
  ├─ processed/relations.json
  ├─ processed/graph_data.json
  ├─ processed/timeline.json
  ├─ processed/contradictions.json
  └─ agent_responses.json
```

---

## What belongs where

### JavaFX UI
Keep the UI responsible for:

- case creation and listing
- evidence file uploads
- graph exploration and node/edge details
- timeline and contradiction views
- chat interactions and per-case settings

Do not place extraction or graph reasoning inside the desktop code.

### Spring Boot backend
Keep the backend responsible for:

- REST endpoints for cases, files, and processing
- running Python jobs for extraction / timeline / contradictions
- serving processed graph and timeline data to the UI
- managing Neo4j connectivity and case-scoped Cypher execution
- persisting chat transcripts and case state

### Python engine
The Python side is the actual analysis layer. It handles:

- warehouse generation and validation
- token-window decisions
- chunking
- entity extraction and alias merging
- relationship extraction
- graph materialization
- timeline normalization
- contradiction detection
- graph-question answering via the bounded query agent

---

## Core project conventions

### 1. Provenance is mandatory
Every graph fact should be traceable back to a source file and a chunk. Do not silently mix evidence across cases or across sources.

### 2. Case isolation is required
Each case should remain self-contained. No case data should be merged into another case by accident.

### 3. Processed files are inspection artifacts
Files such as `entities.json`, `relations.json`, `timeline.json`, and `graph_data.json` are not disposable scratch outputs. They are the explicit evidence layer for debugging, review, and UI rendering.

### 4. Neo4j is optional, not primary
The file-first graph is the primary local artifact. Neo4j is a persistence/query layer on top of it.

### 5. Keep the system explainable
If a fact is in the graph, there should be a reason for it and a source for it.

---

## Expected project layout

```text
<case_name>/
├── data/
│   ├── evidence_01.txt
│   ├── evidence_02.txt
│   └── ...
├── processed/
│   ├── chunks.json
│   ├── entities.json
│   ├── relations.json
│   ├── relationships.json
│   ├── graph_data.json
│   ├── graph.json
│   ├── timeline.json
│   ├── contradictions.json
│   └── contradiction_data.json
├── warehouse.txt
├── llm.json
├── agent_responses.json
└── ...
```

---

## Warehouse format

The warehouse must preserve source boundaries. The format is intentionally source-marked:

```text
========================================
SOURCE_FILE: fir.txt
SOURCE_TYPE: TEXT
========================================

[file contents]

========================================
END_SOURCE: fir.txt
========================================
```

Do not build a flat concatenated text file without source markers. This breaks provenance and makes the graph misleading.

---

## Pipeline flow

Current implementation follows this flow:

```text
warehouse.txt
  ↓
token-window check
  ↓
semantic chunking
  ↓
entity extraction
  ↓
relationship extraction
  ↓
graph construction
  ↓
processed JSON outputs
```

Additional supported stages:

```text
timeline extraction
contradiction detection
bounded natural-language querying
```

---

## Main modules

```text
main.py
  CLI entry point for warehouse processing and optional extraction stages.

engine/chunk.py
  Semantic chunking and warehouse parsing logic.

engine/llm.py
  Multi-provider LLM client, token estimation, batching, config resolution,
  merge/dedup helpers, timeline parsing, and graph helper functions.

engine/prompts.py
  Prompt templates for entity extraction, relationship extraction,
  graph mapping, timeline generation, and contradiction discovery.

engine/entity_extraction.py
  Orchestrates extraction and writes entities/relationships to processed JSON.

engine/graph_builder.py
  Produces graph mappings and saves graph data outputs.

engine/timeline.py
  Timeline extraction, sorting, normalization, and persistence.

engine/contradictions.py
  Contradiction detection using processed context and LLM comparison.

engine/query_agent.py
  Bounded natural-language graph Q&A using read-only Cypher tool calls.

engine/crud.py
  Reserved for Neo4j CRUD and future graph persistence helpers.
```

---

## LLM configuration

Sherlock resolves LLM config in this order:

```text
case llm.json > environment variables > CLI overrides
```

Provider families are OpenAI-compatible and include common services such as:

- OpenAI
- OpenRouter
- DeepSeek
- Groq
- Together
- Mistral
- Ollama
- custom OpenAI-compatible endpoints

Use [llm.json.example](llm.json.example) as the reference template.

---

## How to run the project

### Python dependencies

```bash
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

### Neo4j

```bash
docker compose up -d
```

### Spring Boot backend

```bash
cd app/sherlock-spring-backend
./mvnw spring-boot:run
```

### JavaFX desktop app

```bash
cd app/UI
mvn javafx:run
```

### Standalone engine operations

```bash
python main.py --project data/sample_case --extract
python main.py --project data/sample_case --timeline
python main.py --project data/sample_case --contradictions
python main.py --project data/sample_case --query "Who is connected to Rose Mathew?"
```

---

## Agent working rules

When making changes in this repo, follow these rules:

1. Keep extraction logic separate from UI logic.
2. Preserve source-bound provenance in warehouses and processed outputs.
3. Do not mix case data across projects.
4. Prefer file-first outputs and optional Neo4j sync over assuming Neo4j is the sole source of truth.
5. Keep the Python pipeline inspectable: chunks, entities, relations, timeline, contradictions, and graph data should all be reviewable.
6. If you touch the graph or evidence layer, maintain the provenance metadata.
7. If you change the API contract, update both backend and UI behavior together.
8. Keep the repo simple and explainable; do not over-engineer abstractions.

---

## Testing

Python tests:

```bash
python -m unittest discover -s tests -v
```

Java backend tests:

```bash
cd app/sherlock-spring-backend
./mvnw test
```

---

## Short operational summary

If a new feature is needed, the default path is:

1. add or update the data model
2. update the Python processing flow
3. write or update processed JSON artifacts
4. expose the result via the Spring API
5. render it in the JavaFX UI
6. keep provenance and case isolation intact

This is the intended engineering pattern for the repo.
