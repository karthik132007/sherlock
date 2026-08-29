"""
engine/crud.py — Neo4j CRUD & Ingestion for Sherlock Knowledge Graph.

Supports both:
1. Direct Bolt connection if `neo4j` Python driver is installed.
2. Standard HTTP Cypher Transactional API (http://localhost:7474/db/neo4j/tx/commit)
   using Python's built-in urllib.request (zero extra dependencies required).

Schema (per AGENTS.md §13):
  Nodes: (:Label {id, name, type, confidence, mentions, data, aliases, source_files, chunk_ids, project_id})
  Relationships: (:[RELATION_TYPE] {relation_id, confidence, evidence_text, source_file, chunk_id, project_id})
"""

from __future__ import annotations

import base64
import json
import logging
import os
import re
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

logger = logging.getLogger(__name__)

# Defaults matching docker-compose.yml and application.properties
DEFAULT_NEO4J_URI = os.getenv("NEO4J_URI", "bolt://localhost:7687")
DEFAULT_NEO4J_HTTP_URL = os.getenv("NEO4J_HTTP_URL", "http://localhost:7474")
DEFAULT_NEO4J_USER = os.getenv("NEO4J_USER", os.getenv("NEO4J_USERNAME", "neo4j"))
DEFAULT_NEO4J_PASSWORD = os.getenv("NEO4J_PASSWORD", "password")
DEFAULT_NEO4J_DATABASE = os.getenv("NEO4J_DATABASE", "neo4j")


def sanitize_label(label: Optional[str]) -> str:
    """Sanitize entity type to a valid Cypher label."""
    if not label or not label.strip():
        return "Entity"
    clean = re.sub(r"[^a-zA-Z0-9_]", "", label.strip())
    if not clean:
        return "Entity"
    if clean[0].isdigit():
        clean = "L_" + clean
    return clean[0].upper() + clean[1:].lower()


def sanitize_relation(rel: Optional[str]) -> str:
    """Sanitize relationship type to a valid uppercase Cypher relationship identifier."""
    if not rel or not rel.strip():
        return "RELATED_TO"
    clean = re.sub(r"[^A-Za-z0-9_]", "_", rel.strip()).upper()
    clean = re.sub(r"_+", "_", clean).strip("_")
    if not clean:
        return "RELATED_TO"
    if clean[0].isdigit():
        clean = "R_" + clean
    return clean


def _http_cypher_request(
    statements: List[Dict[str, Any]],
    http_url: str = DEFAULT_NEO4J_HTTP_URL,
    user: str = DEFAULT_NEO4J_USER,
    password: str = DEFAULT_NEO4J_PASSWORD,
    database: str = DEFAULT_NEO4J_DATABASE,
    timeout: float = 10.0,
) -> Tuple[bool, Optional[Dict[str, Any]], Optional[str]]:
    """
    Execute Cypher transactional statements via HTTP API.
    Endpoint: {http_url}/db/{database}/tx/commit
    """
    endpoint = f"{http_url.rstrip('/')}/db/{database}/tx/commit"
    payload = json.dumps({"statements": statements}).encode("utf-8")

    auth_str = f"{user}:{password}"
    auth_header = "Basic " + base64.b64encode(auth_str.encode("utf-8")).decode("utf-8")

    req = urllib.request.Request(
        endpoint,
        data=payload,
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json;charset=UTF-8",
            "Authorization": auth_header,
        },
        method="POST",
    )

    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8")
            data = json.loads(body)
            errors = data.get("errors", [])
            if errors:
                err_msg = "; ".join(e.get("message", "Unknown error") for e in errors)
                return False, None, err_msg
            return True, data, None
    except urllib.error.HTTPError as e:
        err_body = ""
        try:
            err_body = e.read().decode("utf-8")
        except Exception:
            pass
        return False, None, f"HTTP Error {e.code}: {e.reason} ({err_body})"
    except urllib.error.URLError as e:
        return False, None, f"Connection failed: {e.reason}"
    except Exception as e:
        return False, None, str(e)


def check_neo4j_connection(
    http_url: str = DEFAULT_NEO4J_HTTP_URL,
    user: str = DEFAULT_NEO4J_USER,
    password: str = DEFAULT_NEO4J_PASSWORD,
    database: str = DEFAULT_NEO4J_DATABASE,
) -> bool:
    """Check whether Neo4j instance is reachable."""
    success, _, _ = _http_cypher_request(
        statements=[{"statement": "RETURN 1 AS ping"}],
        http_url=http_url,
        user=user,
        password=password,
        database=database,
        timeout=3.0,
    )
    return success


def sync_graph_to_neo4j(
    project_path_or_id: str | Path,
    entities: List[Dict[str, Any]],
    mappings_or_relations: List[Dict[str, Any]],
    http_url: str = DEFAULT_NEO4J_HTTP_URL,
    user: str = DEFAULT_NEO4J_USER,
    password: str = DEFAULT_NEO4J_PASSWORD,
    database: str = DEFAULT_NEO4J_DATABASE,
    verbose: bool = True,
) -> bool:
    """
    Ingest extracted entities and relationships into Neo4j Community database.
    """
    if isinstance(project_path_or_id, Path):
        case_id = project_path_or_id.name
    else:
        case_id = Path(str(project_path_or_id)).name

    if not entities and not mappings_or_relations:
        if verbose:
            print("[Sherlock Neo4j] Nothing to sync (empty graph)")
        return False

    # 1. Create constraint / index if not exists (in separate DDL transaction)
    _http_cypher_request(
        statements=[{
            "statement": "CREATE CONSTRAINT IF NOT EXISTS FOR (n:Entity) REQUIRE (n.id, n.project_id) IS UNIQUE"
        }],
        http_url=http_url,
        user=user,
        password=password,
        database=database,
    )

    # 2. Prepare node merge statements
    statements: List[Dict[str, Any]] = []
    for entity in entities:
        ent_id = entity.get("id") or entity.get("name", "unknown")
        name = entity.get("name") or ent_id
        ent_type = entity.get("type", "ENTITY")
        label = sanitize_label(ent_type)
        confidence = float(entity.get("confidence", 1.0) or 1.0)
        mentions = int(entity.get("mentions", 1) or 1)
        data_map = entity.get("data", {})
        data_json = json.dumps(data_map, ensure_ascii=False) if isinstance(data_map, dict) else "{}"
        aliases = entity.get("aliases", [])
        source_files = entity.get("source_files", [])
        chunk_ids = entity.get("chunk_ids", [])

        cypher_node = (
            f"MERGE (n:{label}:Entity {{id: $id, project_id: $caseId}}) "
            "SET n.name = $name, "
            "    n.type = $type, "
            "    n.confidence = $confidence, "
            "    n.mentions = $mentions, "
            "    n.data = $data, "
            "    n.aliases = $aliases, "
            "    n.source_files = $source_files, "
            "    n.chunk_ids = $chunk_ids"
        )
        statements.append({
            "statement": cypher_node,
            "parameters": {
                "id": str(ent_id),
                "caseId": str(case_id),
                "name": str(name),
                "type": str(ent_type),
                "confidence": confidence,
                "mentions": mentions,
                "data": data_json,
                "aliases": aliases,
                "source_files": source_files,
                "chunk_ids": chunk_ids,
            },
        })

    # 3. Prepare relationship merge statements
    for idx, item in enumerate(mappings_or_relations):
        # Support both enriched mapping (source is dict) and raw relation (source is string)
        src_raw = item.get("source")
        tgt_raw = item.get("target")

        src_id = (src_raw.get("id") if isinstance(src_raw, dict) else None) or (src_raw.get("name") if isinstance(src_raw, dict) else str(src_raw or ""))
        tgt_id = (tgt_raw.get("id") if isinstance(tgt_raw, dict) else None) or (tgt_raw.get("name") if isinstance(tgt_raw, dict) else str(tgt_raw or ""))
        src_name = (src_raw.get("name") if isinstance(src_raw, dict) else str(src_raw or ""))
        tgt_name = (tgt_raw.get("name") if isinstance(tgt_raw, dict) else str(tgt_raw or ""))

        rel_type = sanitize_relation(item.get("relation", "RELATED_TO"))
        rel_id = item.get("relation_id") or f"rel_{idx+1}"
        confidence = float(item.get("confidence", 1.0) or 1.0)
        evidence = item.get("evidence_text") or ""
        source_file = item.get("source_file") or ""
        chunk_id = item.get("chunk_id") or ""

        cypher_rel = (
            "MATCH (s {project_id: $caseId}) WHERE s.id = $srcId OR s.name = $srcName OR s.id = $srcName "
            "MATCH (t {project_id: $caseId}) WHERE t.id = $tgtId OR t.name = $tgtName OR t.id = $tgtName "
            f"MERGE (s)-[r:{rel_type} {{relation_id: $relId, project_id: $caseId}}]->(t) "
            "SET r.confidence = $confidence, "
            "    r.evidence_text = $evidence, "
            "    r.source_file = $sourceFile, "
            "    r.chunk_id = $chunkId"
        )
        statements.append({
            "statement": cypher_rel,
            "parameters": {
                "caseId": str(case_id),
                "srcId": str(src_id),
                "srcName": str(src_name),
                "tgtId": str(tgt_id),
                "tgtName": str(tgt_name),
                "relId": str(rel_id),
                "confidence": confidence,
                "evidence": str(evidence),
                "sourceFile": str(source_file),
                "chunkId": str(chunk_id),
            },
        })

    # Execute in batches against Neo4j
    batch_size = 100
    all_success = True
    last_err = None
    for i in range(0, len(statements), batch_size):
        chunk_stmts = statements[i : i + batch_size]
        success, _, err_msg = _http_cypher_request(
            statements=chunk_stmts,
            http_url=http_url,
            user=user,
            password=password,
            database=database,
        )
        if not success:
            all_success = False
            last_err = err_msg

    if all_success:
        if verbose:
            print(f"[Sherlock Neo4j] Successfully ingested {len(entities)} nodes and {len(mappings_or_relations)} edges into Neo4j for case: {case_id}")
        return True
    else:
        if verbose:
            print(f"[Sherlock Neo4j] Ingestion failed: {last_err}")
        return False


if __name__ == "__main__":
    import argparse
    import sys

    parser = argparse.ArgumentParser(description="Sherlock — Sync case graph to Neo4j database")
    parser.add_argument("--project", required=True, help="Path to Sherlock case directory (e.g. ~/Documents/Sherlock/rose)")
    parser.add_argument("--http-url", default=DEFAULT_NEO4J_HTTP_URL, help="Neo4j HTTP URL (default: http://localhost:7474)")
    parser.add_argument("--user", default=DEFAULT_NEO4J_USER, help="Neo4j username (default: neo4j)")
    parser.add_argument("--password", default=DEFAULT_NEO4J_PASSWORD, help="Neo4j password (default: password)")
    args = parser.parse_args()

    case_path = Path(args.project).expanduser().resolve()
    processed = case_path / "processed"
    ent_path = processed / "entities.json"
    graph_path = processed / "graph_data.json"
    if not graph_path.exists():
        graph_path = processed / "graph.json"

    if not ent_path.exists():
        print(f"Error: {ent_path} does not exist", file=sys.stderr)
        sys.exit(1)

    with ent_path.open("r", encoding="utf-8") as f:
        ent_data = json.load(f)
    entities = ent_data.get("entities", [])

    mappings = []
    if graph_path.exists():
        with graph_path.open("r", encoding="utf-8") as f:
            graph_d = json.load(f)
        mappings = graph_d.get("graph", graph_d.get("relationships", []))
    else:
        rel_path = processed / "relations.json"
        if rel_path.exists():
            with rel_path.open("r", encoding="utf-8") as f:
                rel_d = json.load(f)
            mappings = rel_d.get("relationships", rel_d.get("relations", []))

    print(f"Syncing case '{case_path.name}' to Neo4j ({len(entities)} nodes, {len(mappings)} edges)...")
    ok = sync_graph_to_neo4j(
        project_path_or_id=case_path,
        entities=entities,
        mappings_or_relations=mappings,
        http_url=args.http_url,
        user=args.user,
        password=args.password,
        verbose=True,
    )
    if ok:
        print("Migration to Neo4j completed successfully!")
    else:
        print("Migration failed!")
        sys.exit(1)
