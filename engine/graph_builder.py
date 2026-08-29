"""
engine/graph_builder.py — Graph construction: entities + relations -> node-relation-node mappings

Responsibilities:
  1. Ensure entity ids and data
  2. Build relation_id : node1 relation node2 mappings (deterministic or via LLM)
  3. Persist to processed/relations.json and processed/graph_data.json

Graph model per AGENTS.md §10-11:
  Node: {id, name, type, confidence, data, source_files, chunk_ids, aliases, mentions}
  Relation: {relation_id, source: Node, relation, target: Node, confidence, evidence_text, source_file, chunk_id}
"""

from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Any, Dict, List, Optional

from engine.llm import (
    LLMConfig,
    build_graph_mappings,
    create_graph_mappings_via_llm,
    ensure_entity_ids,
    load_llm_config,
    resolve_llm_config_path,
)

logger = logging.getLogger(__name__)


def build_graph(
    entities: List[Dict[str, Any]],
    relationships: List[Dict[str, Any]],
    use_llm: bool = False,
    llm_config: Optional[LLMConfig | Dict[str, Any]] = None,
    project_path: Optional[str | Path] = None,
    verbose: bool = True,
) -> List[Dict[str, Any]]:
    """
    Build graph mappings from entities+relationships.

    Args:
        entities: list from entities.json (with id, data)
        relationships: list from relations.json (source, relation, target)
        use_llm: if True, use LLM to generate mappings (sends full entities+relations); else deterministic
        llm_config: LLMConfig for LLM mode
        project_path: needed to resolve llm.json if use_llm True
        verbose: logging

    Returns:
        List of mappings: [{relation_id, source: Node, relation, target: Node, confidence, evidence_text, ...}]
    """
    # Ensure ids/data before mapping
    entities = ensure_entity_ids(entities)

    if use_llm:
        cfg = llm_config if isinstance(llm_config, LLMConfig) else load_llm_config(project_path, overrides=llm_config if isinstance(llm_config, dict) else None)
        return create_graph_mappings_via_llm(entities, relationships, llm_config=cfg, verbose=verbose)
    return build_graph_mappings(entities, relationships)


def save_graph_outputs(
    project_path: str | Path,
    entities: List[Dict[str, Any]],
    relationships: List[Dict[str, Any]],
    mappings: List[Dict[str, Any]],
    verbose: bool = True,
) -> Dict[str, Path]:
    """
    Save relations.json, graph_data.json, and also graph.json alias.
    Returns dict of paths written.
    """
    project_path = Path(project_path).expanduser().resolve()
    processed_dir = project_path / "processed"
    processed_dir.mkdir(parents=True, exist_ok=True)

    # Resolve llm config for header (best effort)
    try:
        cfg = load_llm_config(project_path)
        llm_info = {"provider": cfg.provider, "model": cfg.model, "base_url": cfg.base_url}
    except Exception:
        llm_info = {}

    # 1. relations.json (user spec) — also keep relationships.json for compat
    relations_payload = {
        "project": project_path.name,
        "project_path": str(project_path),
        "llm": llm_info,
        "total_relations": len(relationships),
        "total_mappings": len(mappings),
        "relationships": relationships,
    }
    # Also include enriched mappings in same file? Keep separate per spec — relations.json is raw relations
    # We'll store mappings in graph_data.json; relations.json keeps raw + also provide enriched version under "mappings"
    relations_path = processed_dir / "relations.json"
    with relations_path.open("w", encoding="utf-8") as f:
        json.dump(relations_payload, f, indent=2, ensure_ascii=False)
    # Compat symlink/copy
    compat_path = processed_dir / "relationships.json"
    with compat_path.open("w", encoding="utf-8") as f:
        json.dump(relations_payload, f, indent=2, ensure_ascii=False)

    # 2. graph_data.json — primary mapping output: relation_id : node1 relation node2
    graph_payload = {
        "project": project_path.name,
        "project_path": str(project_path),
        "llm": llm_info,
        "total_entities": len(entities),
        "total_relations": len(mappings),
        "entities": entities,
        "graph": mappings,  # list of {relation_id, source: Node, relation, target: Node, ...}
        # Also provide dict form relation_id -> mapping for easy lookup
        "graph_by_id": {m["relation_id"]: m for m in mappings},
    }
    graph_path = processed_dir / "graph_data.json"
    with graph_path.open("w", encoding="utf-8") as f:
        json.dump(graph_payload, f, indent=2, ensure_ascii=False)
    # Alias graph.json
    alias_path = processed_dir / "graph.json"
    with alias_path.open("w", encoding="utf-8") as f:
        json.dump(graph_payload, f, indent=2, ensure_ascii=False)

    if verbose:
        print(f"[Sherlock Graph] Saved {len(relationships)} relations → {relations_path} (compat {compat_path})")
        print(f"[Sherlock Graph] Saved {len(mappings)} mappings → {graph_path} (alias {alias_path})")

    # Ingest directly into Neo4j Community database if available
    try:
        from engine.crud import sync_graph_to_neo4j
        sync_graph_to_neo4j(project_path, entities, mappings, verbose=verbose)
    except Exception as e:
        if verbose:
            print(f"[Sherlock Neo4j] Sync skipped or failed: {e}")

    return {
        "relations_json": relations_path,
        "relationships_json": compat_path,
        "graph_data_json": graph_path,
        "graph_json": alias_path,
    }


def run_graph_build_pipeline(
    project_path: str | Path,
    entities: Optional[List[Dict[str, Any]]] = None,
    relationships: Optional[List[Dict[str, Any]]] = None,
    use_llm: bool = False,
    llm_config: Optional[LLMConfig | Dict[str, Any]] = None,
    verbose: bool = True,
) -> Dict[str, Any]:
    """
    Full graph build: load entities/relationships from files if not supplied,
    build mappings, save outputs.
    """
    project_path = Path(project_path).expanduser().resolve()
    processed_dir = project_path / "processed"

    # Load entities if not supplied
    if entities is None:
        ent_path = processed_dir / "entities.json"
        if not ent_path.exists():
            raise FileNotFoundError(f"entities.json not found at {ent_path} — run entity extraction first")
        with ent_path.open("r", encoding="utf-8") as f:
            data = json.load(f)
        entities = data.get("entities", [])

    if relationships is None:
        rel_path = processed_dir / "relations.json"
        if not rel_path.exists():
            rel_path = processed_dir / "relationships.json"
        if not rel_path.exists():
            raise FileNotFoundError(f"relations.json not found — run relationship extraction first")
        with rel_path.open("r", encoding="utf-8") as f:
            data = json.load(f)
        relationships = data.get("relationships", data.get("relations", []))

    mappings = build_graph(entities, relationships, use_llm=use_llm, llm_config=llm_config, project_path=project_path, verbose=verbose)
    paths = save_graph_outputs(project_path, entities, relationships, mappings, verbose=verbose)

    return {
        "entities": entities,
        "relationships": relationships,
        "mappings": mappings,
        "paths": paths,
    }


if __name__ == "__main__":
    import argparse
    import sys

    parser = argparse.ArgumentParser(description="Sherlock — Graph Builder (entities+relations -> node-relation-node)")
    parser.add_argument("--project", required=True, help="Path to Sherlock project directory")
    parser.add_argument("--use-llm", action="store_true", help="Use LLM for graph mapping (else deterministic)")
    args = parser.parse_args()

    try:
        result = run_graph_build_pipeline(Path(args.project), use_llm=args.use_llm, verbose=True)
        print(f"[Sherlock Graph] Done — {len(result['entities'])} entities, {len(result['mappings'])} mappings")
    except Exception as e:
        print(f"[Sherlock Graph] ERROR: {e}", file=sys.stderr)
        import traceback

        traceback.print_exc()
        sys.exit(1)
