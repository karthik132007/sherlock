<div align="center">

<img src="app/resorces/sherlock_logo_dark.png" alt="Sherlock" width="180"/>

# 🕵️ Sherlock — AI Investigation Knowledge Graph

### From messy case files to an interactive, explainable investigation knowledge graph — automatically.

**JavaFX Desktop App · Spring Boot API · Python LLM Engine · Neo4j Graph · Force-Directed Visualization**

Built for a **48-hour hackathon** as a complete, working MVP.

</div>

---

## ✨ The Problem

Investigators drown in **unstructured text** — FIRs, witness statements, postmortem reports, call logs,
financial records, CCTV summaries. Connecting the dots across hundreds of pages is slow, error-prone,
and impossible to explain to a courtroom.

**Sherlock turns messy case text into a living knowledge graph** in minutes:

```
Raw case text  →  Entities + Relationships + Timeline  →  Interactive Graph  →  Ask questions
```

Every fact is **traceable to its source file and chunk** — because an investigation tool that can't
show *why* it linked two people is useless.

---

## 🎬 Demo Story (30 seconds)

1. **Create a case** — "Operation Rose" 🗂️
2. **Drop in evidence** — FIR, witness statements, call logs, postmortem report 📄
3. **Hit "🚀 Start Investigation"** — Sherlock builds the warehouse, chunks, and extracts
4. **Watch the graph materialize** — Rose Mathew at the center, connected to friends, suspects,
   locations, phones, and a drug substance — all with confidence scores
5. **Click a node** — see every relationship, source document, and evidence mention
6. **Switch to Timeline view** — a chronological reconstruction of the incident
7. **Ask Sherlock** — "Who was with Rose Mathew the day she died?" → an answer grounded in the graph

---

## 🏗️ Architecture

```
┌───────────────────────────────┐         ┌──────────────────────────────┐
│        JavaFX Desktop UI      │         │     Spring Boot Backend      │
│  Graph / Timeline / Chat view │◄───────►│  Case mgmt · Warehouse ·     │
│  Node details · Filters       │  REST   │  Pipeline orchestration ·    │
└───────────────────────────────┘         │  Chat · Neo4j sync           │
                                          └──────────────┬───────────────┘
                                                         │  --project <case>
                                                         ▼
┌───────────────────────────────────────────────────────────────────────┐
│                         Python LLM Engine                              │
│  warehouse.txt ─► token-window check ─► semantic chunking              │
│  ─► entity extraction (batched, dedup) ─► relationship extraction      │
│  ─► timeline extraction ─► graph construction                          │
│  ─► processed/{chunks,entities,relations,graph_data,timeline}.json     │
└───────────────────────────────────────────────────────────────────────┘
                                                         │
                                                         ▼
┌───────────────────────────────────────────────────────────────────────┐
│   Neo4j (docker-compose)  ◄── sync ──  File-based graph (processed/)   │
│   Cypher Q&A · persistent case graphs · future expandability           │
└───────────────────────────────────────────────────────────────────────┘
```

**Key principle:** *Every graph record carries provenance* — `source_file` + `chunk_id` + `evidence_text`
for every entity, relationship, and timeline event. Nothing enters the graph untraceably.

---

## 🚀 Quickstart

### 0. Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Java | 17+ | JDK for Spring Boot + JavaFX |
| Maven | 3.8+ | `mvnw` included per module |
| Python | 3.10+ | Engine runtime (tested on 3.14) |
| Neo4j | 5 (optional) | `docker compose up -d` |

### 1. Python engine

```bash
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt        # openai, scikit-learn, numpy, scipy, neo4j, tiktoken, dotenv
```

### 2. Start Neo4j (optional — graphs also work file-first)

```bash
docker compose up -d          # Neo4j 5 with APOC at http://localhost:7474 (neo4j/password)
```

### 3. Run the backend (Spring Boot)

```bash
cd app/sherlock-spring-backend
./mvnw spring-boot:run        # API on http://localhost:8080
```

### 4. Run the desktop app (JavaFX)

```bash
cd app/UI
mvn javafx:run                # full Sherlock desktop experience
```

> **Prefer to see the engine work standalone?** It runs end-to-end without any UI:

```bash
python main.py --project data/sample_case --extract
```

---

## 🔬 How the Pipeline Works

### 1. Warehouse — source-boundaried text
All uploaded files are combined into `warehouse.txt` with explicit boundaries so the engine always
knows **which file** a fact came from:

```
========================================
SOURCE_FILE: fir.txt
SOURCE_TYPE: TEXT
========================================
[file contents]
========================================
END_SOURCE: fir.txt
========================================
```

### 2. Token-window check
The engine estimates tokens (`tiktoken`, or a chars/4 fallback) and compares them to the LLM's
context window from `llm.json`. Small cases run single-call; large cases are **forced into 20-chunk
batches** with cross-batch dedup context.

### 3. Semantic chunking
Not naive fixed splits — sentences are scored by TF-IDF cosine similarity and split at natural
semantic breakpoints (default 1200 chars, 200 overlap). Every chunk keeps `source_file`, `chunk_index`,
and warehouse char offsets for full provenance.

### 4. Entity extraction (batched LLM)
14 entity types, each with rich `data` payloads:

```
PERSON · LOCATION · ORGANIZATION · EVENT · DOCUMENT · DATE · OBJECT · PHONE_NUMBER
VEHICLE · SUBSTANCE · WEAPON · FINANCIAL_ACCOUNT · DIGITAL_ACCOUNT · MEDICAL_CONDITION
```

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

Aliases are **auto-merged** (`R. Mathew` → `Rose Mathew`) via first-name + surname-initial matching,
and `data` fields are unioned across mentions.

### 5. Relationship extraction (grounded on entities *with data*)
The second batched stage is grounded on already-extracted entities so relations reference canonical
names. Each relation carries confidence, evidence text, and source provenance:

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

### 6. Timeline extraction ⏳
A dedicated stage asks the LLM for `{timestamp, event}` pairs (in *any* order), then **robustly
parses and chronologically sorts** them — supporting ISO, human-date, and US-style formats —
and persists a sorted, deduplicated `timeline.json`.

### 7. Graph construction
Deterministic mapping produces `relation_id : node{data} → relation → node{data}` records
(LLM mapping available as a fallback). Outputs land in `processed/`:

```
processed/
├── chunks.json        # semantic chunks with warehouse offsets
├── entities.json      # {id, name, type, confidence, data, aliases, mentions, ...}
├── relations.json     # raw relations (+ relationships.json compat alias)
├── graph_data.json    # enriched node→relation→node mappings (+ graph.json alias)
└── timeline.json      # chronologically sorted timeline events
```

### 8. Neo4j sync and investigation queries (optional)
One click syncs the file graph into Neo4j with `project_id` isolation between cases. The chat panel then
uses the case's configured Python LLM engine to answer natural-language investigation questions.

```
Investigator question in JavaFX
        ↓ REST
Spring starts Python query agent with the case llm.json
        ↓ JSON tool-call protocol
Python asks for a read-only, project-scoped Cypher query
        ↓
Spring validates it, injects $caseId, and executes it in Neo4j
        ↓ JSON tool result
Python analyses the evidence and returns Markdown + node IDs + relation IDs
        ↓
Chat renders Markdown; graph highlights the supporting evidence
```

The agent receives Sherlock's evidence/provenance story and the Neo4j schema in every prompt. It can call
the `run_cypher` tool at most **five times** per question. Spring is the only component that talks to Neo4j:
it rejects writes, procedures, multiple statements, unscoped queries, and result limits over 100. This keeps
an LLM-generated query read-only and confined to the active case. If the budget is reached, the agent must
answer from the evidence it has already received.

The `/api/cases/{caseId}/chat` response includes `answer`, `highlightNodeIds`, `highlightRelationIds`,
`cypherQueries`, and `toolCallsUsed`. The JavaFX client uses the highlight IDs directly instead of trying to
infer evidence from the answer text.

Each case stores its complete user/assistant transcript in `<case>/agent_responses.json`. The desktop chat
restores this file when reopening a case, while the Python agent supplies the most recent 12 turns to the LLM
alongside the current question. The graph schema and Sherlock evidence/provenance story are still included on
every LLM turn.

---

## 🖥️ The Desktop Experience

### 🔭 Graph view
- **Force-directed physics** (repulsion + spring forces) with play/pause — plus a *top-down layered*
  and *centrality-hub* layout (Rose Mathew naturally lands at the center)
- **Zoom / pan / drag** nodes, click nodes **and** edges for details
- **Search + type filter overlay**, minimap, and a compact legend
- Node size reflects **connection degree**; edge labels show relation + confidence

### 📄 Details panel
Click any node to inspect: type, mentions, aliases, every connected relationship, source documents,
evidence references, and the full extracted `data` payload.

### 🗂️ Case management
Create cases, upload evidence (`.txt`, `.pdf`, `.png`, `.doc/.docx`, `.json`, `.csv` — up to 200 MB),
manage per-case LLM settings, and revisit case history.

### ⏳ Timeline view
A chronological reconstruction of the case — scroll the incident from discovery to arrest.

### 💬 Chat with the case
Multi-provider chat (Ollama, OpenAI, DeepSeek, OpenRouter, Groq, Mistral, Custom) that searches
entities, relations, and timeline events — and when Neo4j is connected, generates and runs **live
Cypher queries** against the case graph.

### 🤖 Bring your own LLM
Fully provider-agnostic via `llm.json`:

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

Resolution priority: **case `llm.json` → env vars → CLI flags**. Run fully offline with Ollama.

---

## 📦 Sample Data

Sherlock ships with a complete fictional case — **Operation Rose** — 20 evidence files covering an
FIR, postmortem, scene report, witness statements, interviews, call logs, CCTV summary, digital
forensics, financial records, and a ground-truth answer key. It also includes a DevOps-flavored
`server_crash_logs.jsonl` dataset (195 events with expected entities) to prove the engine generalizes
beyond criminal cases — from **evidence to incident response**.

---

## 🧪 Quality

| Suite | Tests | Status |
|-------|-------|--------|
| Python engine (chunk, llm, prompts, graph, timeline, pipeline) | 72 | ✅ All passing |
| Java backend (controller + service) | 5 | ✅ All passing |

```bash
# Python
python -m unittest discover -s tests -v

# Java
cd app/sherlock-spring-backend && ./mvnw test
```

---

## 🗂️ Repository Layout

```
├── main.py                    # Engine entry: warehouse → chunk → extract → graph
├── engine/
│   ├── chunk.py               # Semantic chunking + warehouse source parsing
│   ├── llm.py                 # Multi-provider client, batching, dedup, mappings, timeline
│   ├── prompts.py             # All LLM prompt templates
│   ├── entity_extraction.py   # Extraction orchestration → processed/*.json
│   ├── graph_builder.py       # Deterministic + LLM graph mappings
│   ├── timeline.py            # Timeline extraction + sorting + persistence
│   └── crud.py                # Neo4j CRUD store
├── app/
│   ├── UI/                    # JavaFX desktop application
│   └── sherlock-spring-backend/ # Spring Boot REST API + orchestration
├── data/
│   ├── case_rose_001/         # Full fictional case + ground truth
│   ├── sample_case/           # Ready-to-run sample
│   └── devops/dataset/        # Server-crash logs + expected entities
├── tests/                     # 72 Python unit tests
└── docker-compose.yml         # Neo4j 5 + APOC
```

---

## 🏆 Why This Wins

1. **It's a working end-to-end demo** — not a mockup. Text in → interactive graph out, in one click.
2. **Provenance is baked into the data model** — every entity, relation, and timeline event is
   traceable to a source file and chunk. That's the differentiator for a *real* investigation tool.
3. **Massive scope in 48 hours** — Python LLM engine + Spring Boot API + JavaFX desktop app +
   force-directed visualization + timeline + natural-language Cypher Q&A + Neo4j persistence.
4. **100% test-backed** — 77 passing tests across the engine and backend make the demo robust on stage.
5. **Multi-provider and offline-ready** — works with any OpenAI-compatible API or local Ollama.
6. **Reusable beyond crime** — the same engine turns server-crash logs into incident-response graphs.

---

## 🛣️ Roadmap

- [x] Case creation + local folder structure + warehouse
- [x] Semantic chunking with provenance
- [x] Batched entity + relationship extraction with alias dedup
- [x] Interactive force-directed graph + details + filters
- [x] Timeline reconstruction
- [x] Natural-language chat + Cypher Q&A on Neo4j
- [ ] Contradiction detection across sources
- [ ] Evidence / exhibit tracking system
- [ ] PDF & image evidence support
- [ ] Automated investigation findings

---

<div align="center">

**Sherlock — every fact has a source.** 🕵️

Built with ❤️ by the Sherlock team for a 48-hour hackathon.

</div>
