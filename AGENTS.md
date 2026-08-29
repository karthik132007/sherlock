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
│          Neo4j            │
│                           │
│ Knowledge Graph Database  │
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
        ├── data/
        ├── processed/
        ├── graph/
        └── warehouse.txt
```

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
Document Parsing
       ↓
Chunking
       ↓
Entity Extraction
       ↓
Relationship Extraction
       ↓
Graph Construction
       ↓
Neo4j
```

The Python engine should be modular.

Recommended structure:

```text
python_engine/
├── main.py
├── config.py
│
├── ingestion/
│   └── warehouse_reader.py
│
├── processing/
│   ├── chunker.py
│   └── cleaner.py
│
├── extraction/
│   ├── entity_extractor.py
│   ├── relationship_extractor.py
│   └── llm_extractor.py
│
├── graph/
│   ├── graph_builder.py
│   └── neo4j_client.py
│
└── models/
    └── schemas.py
```

Do not combine everything into one massive Python file.

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

Example chunk:

```json
{
  "chunk_id": "fir_001_chunk_03",
  "source_file": "fir.txt",
  "chunk_index": 3,
  "text": "..."
}
```

## Small data strategy

If the case data is below a configurable threshold:

```text
CASE_TEXT_SIZE < SMALL_CASE_THRESHOLD
```

Use a lightweight extraction approach.

Example:

* spaCy NER
* Rule-based extraction
* Limited LLM calls only when necessary

The goal is to reduce unnecessary LLM usage.

## Large data strategy

If the case is larger:

```text
CASE_TEXT_SIZE >= SMALL_CASE_THRESHOLD
```

Use chunking with overlap.

Example initial configuration:

```text
Chunk Size: 1000–1500 tokens
Overlap: 150–250 tokens
```

Exact values should remain configurable.

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

Input:

```text
Rose Mathew was found dead in her apartment in Anna Nagar.
```

Extract:

```json
[
  {
    "name": "Rose Mathew",
    "type": "PERSON",
    "confidence": 0.98
  },
  {
    "name": "Anna Nagar",
    "type": "LOCATION",
    "confidence": 0.99
  }
]
```

Each extracted entity must retain provenance.

Example:

```json
{
  "name": "Rose Mathew",
  "type": "PERSON",
  "confidence": 0.98,
  "source_file": "fir.txt",
  "chunk_id": "fir_001_chunk_03"
}
```

---

# 9. Relationship Extraction

Relationships should be extracted separately.

Example input:

```text
Rose Mathew was friends with Ananya Joseph.
```

Output:

```json
{
  "source": "Rose Mathew",
  "relation": "FRIEND_OF",
  "target": "Ananya Joseph",
  "confidence": 0.95,
  "source_file": "witness_statement.txt",
  "chunk_id": "witness_001_chunk_02"
}
```

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

Example node:

```json
{
  "id": "person_rose_mathew",
  "name": "Rose Mathew",
  "type": "PERSON",
  "confidence": 0.98,
  "source_files": [
    "fir.txt",
    "postmortem_report.txt"
  ],
  "mentions": 14
}
```

Example relationship:

```json
{
  "source": "person_rose_mathew",
  "target": "person_ananya_joseph",
  "type": "FRIEND_OF",
  "confidence": 0.95,
  "evidence": [
    {
      "source_file": "witness_statement.txt",
      "chunk_id": "witness_001_chunk_02",
      "text": "..."
    }
  ]
}
```

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

# 13. Neo4j Storage

After extraction, store the graph in Neo4j.

Example conceptual structure:

```text
(:Person {name: "Rose Mathew"})
        │
        │ [:FRIEND_OF]
        │
        ▼
(:Person {name: "Ananya Joseph"})
```

Nodes should include:

```text
id
name
type
confidence
project_id
mention_count
source_count
```

Relationships should include:

```text
type
confidence
project_id
source_file
chunk_id
```

Every graph record must belong to a specific project.

Do not mix data between cases.

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
STORE IN NEO4J
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

Example:

```text
processed/
├── chunks.json
├── entities.json
├── relationships.json
└── graph_data.json
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
9. Data is stored in Neo4j.
10. A graph can be visualized.
11. Clicking a node shows its underlying extracted information.

That is the first working Sherlock MVP.

Everything else comes after this works.
