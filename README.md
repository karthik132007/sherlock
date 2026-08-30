<div align="center">

<img src="app/resorces/sherlock_logo_dark.png" alt="Sherlock" width="180"/>

# 🕵️ Sherlock — AI Investigation Knowledge Graph

### From messy case files to an interactive, explainable investigation knowledge graph — automatically.

**JavaFX Desktop App · Spring Boot API · Python LLM Engine · Neo4j Graph · Force-Directed Visualization**

</div>

<p align="center">
  <img src="Sherlock_system_design.png" alt="Sherlock System Design" width="900"/>
</p>

---

## 📖 Table of Contents

- [What is Sherlock?](#-what-is-sherlock)
- [The Problem](#-the-problem)
- [Key Features](#-key-features)
- [Architecture](#-architecture)
- [Tech Stack](#-tech-stack)
- [Pipeline Deep Dive](#-pipeline-deep-dive)
- [Provenance & Explainability](#-provenance--explainability)
- [Getting Started](#-getting-started)
- [Using the Desktop App](#-using-the-desktop-app)
- [LLM Configuration](#-llm-configuration)
- [Sample Datasets](#-sample-datasets)
- [REST API Reference](#-rest-api-reference)
- [Testing](#-testing)
- [Repository Layout](#-repository-layout)
- [Why It Stands Out](#-why-it-stands-out)
- [Roadmap](#-roadmap)

---

## 🔍 What is Sherlock?

Sherlock is a **full-stack, LLM-powered investigation platform** that ingests raw, unstructured case
evidence and automatically produces an **interactive, source-traceable knowledge graph** — entities,
relationships, a chronological timeline, contradictions, and even a reasoned investigation opinion.

It is not a mockup. It is a working end-to-end investigation workflow:

```text
Raw case text → Entities + Relationships + Timeline → Interactive Graph → Investigation Questions
```

Built as a three-layer system — a **JavaFX desktop client**, a **Spring Boot REST API**, and a
**Python LLM engine** — Sherlock is designed to be practical, auditable, and demonstrable. Every fact
in the graph can be traced back to the exact source file and chunk of evidence it came from.

---

## 🎯 The Problem

Investigators work with fragmented, heterogeneous evidence: FIRs, witness statements, scene and
post-mortem reports, call logs, phone messages, financial records, CCTV summaries, digital-forensics
notes, and more. Manually connecting the dots across dozens of files is:

- **Slow** — hours of reading and cross-referencing.
- **Error-prone** — critical links and contradictions are easy to miss.
- **Hard to defend** — conclusions need to be justified with a source, not a hunch.

Existing tools either force investigators into rigid templates or produce "black-box" AI answers with
no way to verify where a claim came from. **Sherlock solves this by making provenance a first-class
citizen of the data model** — the graph is not just smart, it is *explainable*.

---

## ✨ Key Features

### 🧠 Analysis Engine (Python)
- **Source-boundaried warehouse** — evidence is combined into `warehouse.txt` while preserving exact
  file boundaries for traceability.
- **Token-window aware processing** — the engine estimates token load and automatically decides
  between a single LLM call or a batched, deduplicated strategy.
- **Semantic chunking with provenance** — each chunk carries `chunk_id`, `source_file`,
  `char_start`, and overlap metadata.
- **Entity extraction & alias merging** — people, locations, organizations, events, documents,
  phone numbers, and digital artifacts; aliases (e.g. "Rose M." vs "R. Mathew") are merged.
- **Relationship extraction** — every relationship is grounded on the extracted entity set and keeps
  evidence text, source file, chunk id, and confidence.
- **Timeline extraction** — temporal events are normalized and sorted into a chronological timeline.
- **Contradiction detection** — surfaces inconsistent statements, timeline mismatches, and
  cross-source lies (e.g. false alibis).
- **Sherlock's Opinion** — a deep, chain-of-thought theory deduction with supporting evidence
  (verbatim quotes), flaws/counter-evidence, alternative hypotheses, and actionable leads.
- **Bounded natural-language query agent** — ask questions in plain English; a read-only Cypher agent
  answers with evidence citations and highlights the supporting graph nodes.

### 🖥️ Investigation Desktop App (JavaFX)
- **Force-directed, Neo4j-inspired graph canvas** — colored node types, relationship labels, search,
  filtering, and fit-to-view.
- **Node & relationship details panel** — inspect evidence text, source file, chunk, confidence,
  aliases, and metadata for any graph element.
- **Timeline view** — chronologically sorted events with filtering.
- **Contradictions view** — every contradiction with its supporting/contradicting sources.
- **Opinion panel** — Sherlock's reasoned verdict with citations.
- **Case chat** — per-case, session-based chat backed by the bounded query agent.
- **Documents panel** — browse uploaded evidence files.
- **Per-case LLM settings** — configure provider, model, and keys from the UI.

### 🛠️ Backend & Persistence (Spring Boot + Neo4j)
- Full **case lifecycle** REST API (create, upload, process, query).
- **Pipeline orchestration** — triggers extraction / timeline / contradictions / opinion / opinion jobs
  and tracks processing status.
- **Optional Neo4j persistence** — sync the file-first graph into Neo4j and run scoped Cypher queries.
- **Chat history persistence** — transcripts are stored per case and per session.

---

## 🏗️ Architecture

### Component overview

```text
┌───────────────────────────────┐         ┌──────────────────────────────┐
│        JavaFX Desktop UI      │         │     Spring Boot Backend      │
│  Graph · Timeline · Chat      │◄───────►│  Case mgmt · Warehouse ·     │
│  Contradictions · Opinion     │  REST   │  Pipeline orchestration ·    │
│  Documents · LLM settings     │         │  Chat · Neo4j sync/query     │
└───────────────────────────────┘         └──────────────┬───────────────┘
                                                         │  subprocess
                                                         ▼
┌───────────────────────────────────────────────────────────────────────┐
│                         Python LLM Engine                              │
│  warehouse.txt → token-window check → semantic chunking               │
│  → entity extraction → relationship extraction → graph construction    │
│  → timeline extraction → contradiction detection → opinion deduction   │
│  → bounded natural-language query agent                                │
└───────────────────────────────────────────────────────────────────────┘
                                                         │
                                                         ▼
┌───────────────────────────────────────────────────────────────────────┐
│    File-first outputs (source of truth) + Optional Neo4j persistence  │
│    processed/{chunks,entities,relations,graph_data,timeline,          │
│              contradictions,opinion}.json  ·  warehouse.txt           │
└───────────────────────────────────────────────────────────────────────┘
```

**Key principle:** the file-first outputs under `processed/` are the primary local artifact and source
of truth. Neo4j is an optional persistence/query layer on top — never the only source of truth.

---

## 🧰 Tech Stack

| Layer            | Technology                                                     | Role                                          |
|------------------|----------------------------------------------------------------|-----------------------------------------------|
| **Desktop UI**   | Java 17, JavaFX 21, custom SVG icon set, CSS theming            | Graph exploration, timeline, chat, settings   |
| **Backend API**  | Spring Boot 3, Maven (`mvnw`), REST, multipart uploads          | Case lifecycle, pipeline orchestration, chat  |
| **LLM Engine**   | Python 3.10+, OpenAI SDK, scikit-learn, tiktoken, python-dotenv | Extraction, graph, timeline, contradictions    |
| **Graph DB**     | Neo4j 5 (Docker Compose, APOC plugin)                          | Optional persistence + Cypher query agent     |
| **LLM Providers**| OpenAI, OpenRouter, DeepSeek, Groq, Together, Mistral, Ollama  | Any OpenAI-compatible endpoint                |

---

## 🔬 Pipeline Deep Dive

### 1. Warehouse construction
Uploaded files are combined into a source-boundaried `warehouse.txt` that keeps original file
boundaries — essential for traceability.

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

### 2. Token-window check & chunking
The engine estimates the token load and decides how to process the case (single-call vs. batched).
It then runs **semantic chunking** with overlap and provenance metadata so every piece of evidence
can be linked back to its origin.

### 3. Entity extraction
Entities (people, locations, organizations, events, documents, dates, digital artifacts, phone
numbers) are extracted from each chunk. Alias mentions are detected and **merged into canonical
entities**, with unioned metadata and per-source provenance.

### 4. Relationship extraction
Relationships are extracted **grounded on the entity set** (no hallucinated nodes). Each relationship
retains `evidence_text`, `source_file`, `chunk_id`, and `confidence`.

### 5. Graph construction
A deterministic graph builder produces the node/edge structures rendered by the UI and synced to
Neo4j, embedding full node data and alias resolution.

### 6. Timeline extraction
Temporal events are extracted, parsed, and **normalized into a chronologically sorted timeline**.

### 7. Contradiction detection
Cross-source truth comparison and timeline-mismatch analysis surface contradictions — including
false alibis — each backed by the sources that conflict.

### 8. Opinion deduction
Deep reasoning mode (thinking enabled) connects the dots across entities, relations, timeline, and
contradictions to produce a **primary hypothesis with verbatim supporting quotes, flaws, alternative
hypotheses, and actionable leads**.

### 9. Natural-language querying
The bounded query agent answers investigator questions with **read-only, case-scoped Cypher** tool
calls (max 5 calls, 100 records), returns evidence-grounded answers, and identifies the exact graph
node/relation ids to highlight in the UI.

### Outputs
All processed artifacts are written to each case folder:

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
├── opinion.json
└── opinion_data.json
```

---

## 🔎 Provenance & Explainability

Sherlock's differentiator is that **every graph fact has a source**:

- Every node/relationship records `source_file` and `chunk_id`.
- Every relationship records verbatim `evidence_text`.
- Case isolation is enforced — no case data leaks into another (`project_id` scoping throughout).
- The UI surfaces the evidence for any node or edge on demand.

> **"If a fact is in the graph, there should be a reason for it and a source for it."**

---

## 🚀 Getting Started

### Prerequisites
- **Python 3.10+**
- **JDK 17+** and **Maven** (or use the bundled `mvnw` / `mvn` wrappers)
- **JavaFX 21** (resolved via Maven)
- **Docker** (only if you want Neo4j persistence)
- An **LLM API key** (or local Ollama) for extraction stages

### 1. Set up the Python environment

```bash
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

### 2. (Optional) Start Neo4j

```bash
docker compose up -d
```

The Neo4j instance runs on `http://localhost:7474` (Bolt `7687`) with default credentials
`neo4j/password`.

### 3. Run the Spring Boot backend

```bash
cd app/sherlock-spring-backend
./mvnw spring-boot:run
```

The API is served at `http://localhost:8080/api`.

### 4. Run the JavaFX desktop app

```bash
cd app/UI
mvn javafx:run
```

### 5. Run the Python engine directly (standalone)

```bash
# Full extraction (chunking → entities → relationships → graph)
python main.py --project data/sample_case --extract

# Timeline extraction
python main.py --project data/sample_case --timeline

# Contradiction detection
python main.py --project data/sample_case --contradictions

# Sherlock's Opinion
python main.py --project data/sample_case --opinion

# Natural-language graph query (JSON-Lines Cypher protocol)
python main.py --project data/sample_case --query "Who is connected to Rose Mathew?"
```

---

## 🖱️ Using the Desktop App

1. **Create a case** from the initial view and upload evidence files (TXT and more).
2. Click **Process** to run the extraction pipeline; follow progress in the processing logs dialog.
3. Explore the **graph** — search nodes, filter by type (Person, Phone, Doc, Location, Org, Event),
   and click any node/edge to see its evidence, source file, chunk, and confidence.
4. Switch to **Timeline**, **Contradictions**, or **Opinion** panels for the analytical views.
5. Ask questions in **Chat** — answers come back with graph highlights and source citations.
6. Configure the **LLM provider/model** per case in the settings view.
7. Optionally **sync to Neo4j** and run scoped Cypher queries for persistence/query-layer usage.

---

## 🤖 LLM Configuration

Sherlock resolves LLM config in this order:

```text
1. <case_folder>/llm.json   (created by the UI)
2. Environment variables    (OPENAI_API_KEY, DEEPSEEK_API_KEY, GROQ_API_KEY, LLM_API, ...)
3. CLI overrides            (--model, --context-window)
```

Example `llm.json`:

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

**Supported providers:** OpenAI, OpenRouter, DeepSeek, Groq, Together, Mistral, Ollama (local), and
any custom OpenAI-compatible endpoint. See [`llm.json.example`](llm.json.example) for the full
reference, including flexible key aliases (`model_name`, `apiKey`, `baseUrl`, ...).

---

## 📦 Sample Datasets

| Dataset | Path | Purpose |
|---------|------|---------|
| **Rose case** | `data/case_rose_001/` | Full fictional investigation: FIR, scene report, post-mortem, witness statements, interviews, phone messages, call logs, location timeline, CCTV summary, digital forensics, financial records, relationship map, suspect matrix, and a ground-truth file (`20_ground_truth.json`) for evaluation. |
| **Sample case** | `data/sample_case/` | Ready-to-run pipeline demo with a pre-built warehouse. |
| **DevOps / incident response** | `data/devops/dataset/` | A synthetic cybersecurity dataset (`server_crash_logs.jsonl`, 195 chronological events) showing the pipeline applied to incident-response evidence — from reconnaissance → brute force → compromise → resource exhaustion → crash. |

This breadth shows Sherlock generalizes **beyond criminal investigation** into any evidence-heavy
domain (cybersecurity incident response, compliance, journalism, etc.).

---

## 📡 REST API Reference

All endpoints are under `/api`.

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/cases` | Create a case |
| `GET` | `/cases/next-id` | Get the next available case id |
| `GET` | `/cases` | List cases |
| `PUT` | `/cases/{caseId}/llm` | Update per-case LLM config |
| `GET` | `/cases/{caseId}` | Get case details |
| `POST` | `/cases/{caseId}/files` | Upload evidence file (multipart) |
| `GET` | `/cases/{caseId}/files/{fileName}` | Fetch a file's contents |
| `GET` | `/cases/{caseId}/chunks` | Fetch processed chunks |
| `GET` | `/cases/{caseId}/warehouse` | Fetch the warehouse text |
| `POST` | `/cases/{caseId}/process` | Trigger extraction pipeline |
| `GET` | `/cases/{caseId}/status` | Processing status |
| `GET` | `/cases/{caseId}/graph` | Get graph data |
| `POST` | `/cases/{caseId}/timeline/process` | Trigger timeline extraction |
| `GET` | `/cases/{caseId}/timeline/status` | Timeline status |
| `GET` | `/cases/{caseId}/timeline` | Get timeline data |
| `POST` | `/cases/{caseId}/contradictions/process` | Trigger contradiction detection |
| `GET` | `/cases/{caseId}/contradictions/status` | Contradictions status |
| `GET` | `/cases/{caseId}/contradictions` | Get contradictions data |
| `POST` | `/cases/{caseId}/opinion/process` | Trigger opinion deduction |
| `GET` | `/cases/{caseId}/opinion/status` | Opinion status |
| `GET` | `/cases/{caseId}/opinion` | Get opinion data |
| `GET` | `/neo4j/status` | Neo4j connectivity status |
| `POST` | `/cases/{caseId}/neo4j/sync` | Sync graph to Neo4j |
| `POST` | `/cases/{caseId}/neo4j/query` | Run scoped Cypher query |
| `POST` | `/cases/{caseId}/chat` | Send a chat message (query agent) |
| `GET` | `/cases/{caseId}/chat` | Get chat history |
| `GET` | `/cases/{caseId}/chat/sessions` | List chat sessions |
| `GET` | `/cases/{caseId}/chat/sessions/{sessionId}` | Get a session's transcript |
| `POST` | `/cases/{caseId}/chat/sessions` | Create a chat session |
| `DELETE` | `/cases/{caseId}/chat/sessions/{sessionId}` | Delete a chat session |

---

## 🧪 Testing

```bash
# Python engine tests (72 tests)
python -m unittest discover -s tests -v

# Java backend tests
cd app/sherlock-spring-backend
./mvnw test
```

The Python suite covers chunking/provenance, LLM utilities (token estimation, strategy decisions,
batching, alias merging, graph mappings), prompt templates, deterministic graph building, timeline
parsing & sorting, contradiction detection, opinion deduction, and end-to-end pipeline smoke tests.
See [`TEST_RESULTS.md`](TEST_RESULTS.md) for the latest full run (72/72 passing).

---

## 🗂️ Repository Layout

```text
├── main.py                        # CLI entry point for the Python engine
├── engine/
│   ├── chunk.py                   # Warehouse parsing + semantic chunking
│   ├── llm.py                     # Multi-provider LLM client, batching, dedup, helpers
│   ├── prompts.py                 # Prompt templates for every stage
│   ├── entity_extraction.py       # Entity + relationship extraction orchestration
│   ├── graph_builder.py           # Deterministic graph construction + outputs
│   ├── timeline.py                # Timeline extraction, sorting, persistence
│   ├── contradictions.py          # Contradiction detection
│   ├── opinion.py                 # Deep theory/opinion deduction
│   ├── query_agent.py             # Bounded natural-language graph query agent
│   └── crud.py                    # Neo4j CRUD / graph persistence helpers
├── app/
│   ├── UI/                        # JavaFX desktop client
│   └── sherlock-spring-backend/   # Spring Boot REST API
├── data/
│   ├── sample_case/               # Ready-to-run pipeline demo
│   ├── case_rose_001/             # Full fictional case + ground truth
│   └── devops/                    # Incident-response / cybersecurity dataset
├── tests/                         # Python unit + integration tests
├── docker-compose.yml             # Neo4j service
├── llm.json.example               # LLM config reference
├── requirements.txt
├── TEST_RESULTS.md
├── AGENTS.md
└── README.md
```

---

## 🏆 Why It Stands Out

1. **It works end-to-end** — a real investigation workflow (upload → process → graph → chat), not a
   demo mockup.
2. **Provenance by design** — every graph fact traces to a source file, chunk, and evidence excerpt;
   explainability is built into the data model, not bolted on.
3. **Fully inspectable** — chunks, entities, relations, timeline, contradictions, opinion, and graph
   data are all reviewable JSON artifacts under `processed/`.
4. **Multi-provider, cost-aware** — token-window decisions and batching keep LLM costs predictable,
   and it runs on anything from local Ollama to cloud APIs.
5. **Graph-first but Neo4j-optional** — file-first outputs are the source of truth; Neo4j is an
   optional persistence/query layer on top.
6. **Domain-general** — proven on both crime investigations and cybersecurity incident response.
7. **Deep reasoning** — beyond extraction, Sherlock produces a cited opinion, surfaces contradictions,
   and answers questions with evidence-grounded, source-cited answers.

---



<div align="center">

**Sherlock — every fact has a source.** 🕵️

</div>
