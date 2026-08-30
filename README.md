<div align="center">

<img src="app/resorces/sherlock_logo_dark.png" alt="Sherlock" width="180"/>

# 🕵️ Sherlock — AI Investigation Knowledge Graph

### From messy case files to an interactive, explainable investigation knowledge graph — automatically.

**JavaFX Desktop App · Spring Boot API · Python LLM Engine · Neo4j Graph · Force-Directed Visualization**

</div>

---

## ✨ The Problem

Investigators work with fragmented evidence across FIRs, witness statements, reports, call logs,
financial records, CCTV summaries, and forensic notes. Connecting the dots manually is slow,
error-prone, and difficult to defend in a real investigation.

Sherlock converts raw case material into a living, queryable graph:

```text
Raw case text → Entities + Relationships + Timeline → Interactive Graph → Investigation Questions
```

Every fact is traceable to its source file and chunk so the graph remains explainable and auditable.

---

## 🏗️ Architecture

```text
┌───────────────────────────────┐         ┌──────────────────────────────┐
│        JavaFX Desktop UI      │         │     Spring Boot Backend      │
│  Graph / Timeline / Chat view │◄───────►│  Case mgmt · Warehouse ·     │
│  Node details · Filters       │  REST   │  Pipeline orchestration ·    │
└───────────────────────────────┘         │  Chat · Neo4j sync           │
                                          └──────────────┬───────────────┘
                                                         │
                                                         ▼
┌───────────────────────────────────────────────────────────────────────┐
│                         Python LLM Engine                              │
│  warehouse.txt → token-window check → semantic chunking              │
│  → entity extraction → relationship extraction → graph construction    │
│  → timeline extraction → contradiction detection → processed JSON      │
└───────────────────────────────────────────────────────────────────────┘
                                                         │
                                                         ▼
┌───────────────────────────────────────────────────────────────────────┐
│         File-first outputs + Optional Neo4j persistence               │
│  processed/{chunks,entities,relations,graph_data,timeline}.json       │
└───────────────────────────────────────────────────────────────────────┘
```

**Key principle:** every graph record keeps provenance — source file, chunk, and evidence text.

---

## 🚀 Quickstart

### 1. Python environment

```bash
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

### 2. Start Neo4j (optional)

```bash
docker compose up -d
```

The Neo4j instance runs on `http://localhost:7474` with default credentials `neo4j/password`.

### 3. Run the backend

```bash
cd app/sherlock-spring-backend
./mvnw spring-boot:run
```

### 4. Run the desktop app

```bash
cd app/UI
mvn javafx:run
```

### 5. Run the Python engine directly

```bash
python main.py --project data/sample_case --extract
python main.py --project data/sample_case --timeline
python main.py --project data/sample_case --contradictions
python main.py --project data/sample_case --query "Who is connected to Rose Mathew?"
```

---

## 🔬 Current Pipeline

### 1. Warehouse construction
Sherlock combines uploaded files into a source-boundaried `warehouse.txt` that keeps original file boundaries:

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

This is essential for traceability.

### 2. Token-window check and chunking
The engine estimates token load and decides how to process a case. It then runs semantic chunking with provenance metadata so evidence can be linked back to its origin.

### 3. Entity extraction
Sherlock extracts entities such as people, locations, organizations, events, documents, dates, and digital artifacts. It also merges aliases and enriches entity data across mentions.

### 4. Relationship extraction
The relationship stage is grounded on the extracted entity set, and each relationship retains evidence details, source file, chunk id, and confidence.

### 5. Timeline extraction
A dedicated stage extracts temporal events and normalizes them into a chronologically sorted timeline.

### 6. Contradiction detection
The engine compares inconsistent statements, timeline mismatches, and cross-source evidence to surface contradictions.

### 7. Graph outputs
Processed outputs are written under each case folder in `processed/`:

```text
processed/
├── chunks.json
├── entities.json
├── relations.json
├── relationships.json
├── graph_data.json
├── graph.json
├── timeline.json
├── contradictions.json
├── contradiction_data.json
└── ...
```

---

## 🤖 LLM Configuration

Sherlock supports multiple OpenAI-compatible providers through per-case configuration in `llm.json`.

Resolution order:

1. case-level `llm.json`
2. environment variables
3. CLI overrides

Example:

```json
{
  "provider": "openai",
  "model": "gpt-4o-mini",
  "api_key": "sk-...",
  "base_url": "https://api.openai.com/v1",
  "context_window": 128000,
  "temperature": 0.1
}
```

Supported provider families include OpenAI, OpenRouter, DeepSeek, Groq, Together, Mistral, Ollama, and custom OpenAI-compatible endpoints.

---

## 📦 Sample Data

The repository includes:

- a full fictional case: `data/case_rose_001/`
- a ready-to-run sample case: `data/sample_case/`
- devops dataset examples under `data/devops/`

This makes it easy to validate the pipeline beyond criminal case analysis and into incident-response style evidence sets.

---

## 🧪 Quality

```bash
# Python tests
python -m unittest discover -s tests -v

# Java backend tests
cd app/sherlock-spring-backend
./mvnw test
```

---

## 🗂️ Repository Layout

```text
├── main.py
├── engine/
│   ├── chunk.py
│   ├── llm.py
│   ├── prompts.py
│   ├── entity_extraction.py
│   ├── graph_builder.py
│   ├── timeline.py
│   ├── contradictions.py
│   ├── query_agent.py
│   └── crud.py
├── app/
│   ├── UI/
│   └── sherlock-spring-backend/
├── data/
│   ├── sample_case/
│   ├── case_rose_001/
│   └── devops/
├── tests/
├── docker-compose.yml
├── llm.json.example
├── requirements.txt
├── README.md
├── AGENTS.md
└── .venv/
```

---

## 🏆 Why it matters

1. It is a working end-to-end investigation workflow, not just a mockup.
2. Provenance is built into the data model, not bolted on later.
3. The engine is modular and inspectable: chunks, entities, relations, timeline, contradictions, graph outputs are all visible.
4. Neo4j is optional and acts as a persistence/query layer rather than the only source of truth.
5. The project supports both crime-investigation workflows and other evidence-heavy domains.

---

<div align="center">

**Sherlock — every fact has a source.** 🕵️

</div>
