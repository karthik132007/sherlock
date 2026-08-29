"""
engine/entity_extraction.py — Orchestrates warehouse → entities → relationships.

Uses engine.llm for token-window decision + batched LLM calls with
previous-batch dedup context.

Pipeline:
  warehouse.txt (text) + chunks.json (with provenance)
        ↓ decide_strategy (token count vs 500k window)
        ↓ if fits & prefer_single -> single LLM call
          else -> batched (20 chunks/batch, each call gets previous entities)
        ↓ merge/dedup aliases
        ↓ relationship extraction (batched, grounded on entities)
        ↓ save to processed/entities.json, relationships.json

Provenance: every entity/relationship retains source_file + chunk_id.
"""

from __future__ import annotations

import json
import logging
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

# Force UTF-8 on stdout/stderr to avoid Windows cp1252/cp437 UnicodeEncodeErrors
if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
if hasattr(sys.stderr, "reconfigure"):
    try:
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

from engine.llm import (
    DEFAULT_BATCH_SIZE,
    DEFAULT_CONTEXT_WINDOW_TOKENS,
    DEFAULT_MODEL,
    LLMConfig,
    decide_strategy,
    extract_entities_auto,
    extract_relationships_batched,
    get_token_stats,
    load_llm_config,
    resolve_llm_config_path,
)

logger = logging.getLogger(__name__)


def run_extraction_pipeline(
    project_path: str | Path,
    batch_size: int = DEFAULT_BATCH_SIZE,
    context_window: Optional[int] = None,
    model: Optional[str] = None,
    llm_config: Optional[LLMConfig | Dict[str, Any]] = None,
    prefer_batched_when_fits: bool = True,
    extract_relationships: bool = True,
    extract_timeline: bool = False,
    timeline_prefer_batched: bool = False,
    verbose: bool = True,
) -> Dict[str, Any]:
    """
    Run full extraction for a Sherlock project.

    Args:
        project_path: path to case folder containing warehouse.txt, processed/chunks.json, llm.json
        batch_size: chunks per LLM batch (default 20)
        context_window: LLM token window — if None, uses llm.json / provider default (500k fallback)
        model: LLM model name — if None, uses llm.json model
        llm_config: explicit LLMConfig or dict (provider/model/api_key); if None, loads <project>/llm.json
        prefer_batched_when_fits: if True, even when warehouse fits, use batched+dedup (user spec).
                                  if False, use single-call when it fits.
        extract_relationships: also run relationship extraction after entities
        extract_timeline: also run timeline extraction (batched LLM, sorted chronologically → timeline.json)
        timeline_prefer_batched: for timeline, if True force batched even when fits; default False (single_call more coherent)
        verbose: print progress

    Returns:
        dict with keys: entities, relationships, timeline, token_decision, stats, llm_config

    Side-effects:
        Writes processed/entities.json, processed/relationships.json, processed/timeline.json (if enabled)
    """
    project_path = Path(project_path).expanduser().resolve()
    warehouse_path = project_path / "warehouse.txt"
    chunks_path = project_path / "processed" / "chunks.json"
    processed_dir = project_path / "processed"
    processed_dir.mkdir(parents=True, exist_ok=True)

    if not warehouse_path.exists():
        raise FileNotFoundError(f"warehouse.txt not found at {warehouse_path}")

    warehouse_text = warehouse_path.read_text(encoding="utf-8", errors="replace")
    if not warehouse_text.strip():
        raise ValueError("warehouse.txt is empty")

    # Load chunks (required for batched mode; also needed for provenance in single-call)
    if chunks_path.exists():
        with chunks_path.open("r", encoding="utf-8") as f:
            chunks_data = json.load(f)
        chunks: List[Dict[str, Any]] = chunks_data.get("chunks", [])
        if not chunks:
            raise ValueError("chunks.json exists but contains no chunks")
    else:
        # Fallback: generate chunks on the fly if missing
        if verbose:
            print(f"[Sherlock] chunks.json not found, generating chunks inline...")
        from engine.chunk import semantic_chunk_text

        chunks = semantic_chunk_text(warehouse_text, source_file="warehouse.txt")
        # Persist for inspection
        with (processed_dir / "chunks.json").open("w", encoding="utf-8") as f:
            json.dump(
                {
                    "project": project_path.name,
                    "project_path": str(project_path),
                    "source_file": "warehouse.txt",
                    "warehouse_chars": len(warehouse_text),
                    "total_chunks": len(chunks),
                    "chunks": chunks,
                },
                f,
                indent=2,
                ensure_ascii=False,
            )

    # Resolve LLM config (llm.json in case folder > env > overrides)
    # Build overrides from explicit args
    overrides: Dict[str, Any] = {}
    if model is not None:
        overrides["model"] = model
    if context_window is not None:
        overrides["context_window"] = context_window
    # Merge with supplied llm_config dict if any
    if isinstance(llm_config, dict):
        overrides.update(llm_config)
        llm_config = None  # will re-load with merged overrides
    cfg: LLMConfig = llm_config if isinstance(llm_config, LLMConfig) else load_llm_config(project_path, overrides=overrides or None)
    effective_window = context_window if context_window is not None else cfg.context_window
    if verbose:
        llm_src = resolve_llm_config_path(project_path)
        src_str = str(llm_src) if llm_src else "env vars"
        print(f"[Sherlock] LLM config: {cfg.masked()} (source: {src_str})")

    # Token-window decision (logging)
    decision = decide_strategy(
        warehouse_text, chunks, context_window=effective_window, prefer_batched_when_fits=prefer_batched_when_fits
    )
    if verbose:
        print(f"[Sherlock] Warehouse stats: {get_token_stats(warehouse_text)}")
        print(f"[Sherlock] Decision: strategy={decision['strategy']} | {decision['reason']}")
        print(f"[Sherlock] Chunks: {len(chunks)} | Batches (size {batch_size}): {decision['batches_needed']}")

    # Entity extraction (auto chooses single vs batched)
    print("[Sherlock] STAGE: Entity Extraction - Starting entity extraction...")
    entities, _ = extract_entities_auto(
        warehouse_text=warehouse_text,
        chunks=chunks,
        context_window=effective_window,
        batch_size=batch_size,
        llm_config=cfg,
        prefer_batched_when_fits=prefer_batched_when_fits,
        verbose=verbose,
    )

    # Save entities.json with provenance
    entities_output = {
        "project": project_path.name,
        "project_path": str(project_path),
        "extraction_config": {
            "provider": cfg.provider,
            "model": cfg.model,
            "base_url": cfg.base_url,
            "context_window": effective_window,
            "batch_size": batch_size,
            "strategy": decision["strategy"],
            "prefer_batched_when_fits": prefer_batched_when_fits,
            "token_decision": decision,
            "llm_config_source": str(resolve_llm_config_path(project_path) or "env"),
        },
        "total_entities": len(entities),
        "entities": entities,
    }
    entities_path = processed_dir / "entities.json"
    with entities_path.open("w", encoding="utf-8") as f:
        json.dump(entities_output, f, indent=2, ensure_ascii=False)
    if verbose:
        print(f"[Sherlock] Saved {len(entities)} entities → {entities_path}")

    relationships: List[Dict[str, Any]] = []
    if extract_relationships and entities:
        if verbose:
            print(f"[Sherlock] STAGE: Relationship Extraction - Starting for {len(entities)} entities...")
        relationships = extract_relationships_batched(
            chunks=chunks,
            entities=entities,  # full entities with id,data now sent to LLM per prompts.py
            batch_size=batch_size,
            llm_config=cfg,
            verbose=verbose,
        )
        # Save relations.json (user spec) + relationships.json alias
        rel_output = {
            "project": project_path.name,
            "project_path": str(project_path),
            "extraction_config": {
                "provider": cfg.provider,
                "model": cfg.model,
                "base_url": cfg.base_url,
                "batch_size": batch_size,
            },
            "total_relationships": len(relationships),
            "total_relations": len(relationships),
            "relationships": relationships,
            "relations": relationships,  # alias per spec name
        }
        rel_path = processed_dir / "relations.json"
        with rel_path.open("w", encoding="utf-8") as f:
            json.dump(rel_output, f, indent=2, ensure_ascii=False)
        compat_path = processed_dir / "relationships.json"
        with compat_path.open("w", encoding="utf-8") as f:
            json.dump(rel_output, f, indent=2, ensure_ascii=False)
        if verbose:
            print(f"[Sherlock] Saved {len(relationships)} relations → {rel_path} (compat {compat_path})")
    elif extract_relationships and not entities:
        if verbose:
            print("[Sherlock] Skipping relationship extraction — no entities found")

    # ------------------------------------------------------------------
    # Graph mapping: entities (with data) + relations -> relation_id : node1 relation node2
    # Deterministic by default; LLM-based if needed via graph_builder
    # ------------------------------------------------------------------
    graph_mappings: List[Dict[str, Any]] = []
    graph_paths: Dict[str, Any] = {}
    if entities and relationships:
        if verbose:
            print(f"[Sherlock] STAGE: Relation Mapping - Building graph mappings...")
        try:
            from engine.graph_builder import build_graph, save_graph_outputs

            # Deterministic mapping (no extra LLM call) — embeds full node data
            graph_mappings = build_graph(entities, relationships, use_llm=False, verbose=verbose)
            graph_paths = save_graph_outputs(project_path, entities, relationships, graph_mappings, verbose=verbose)
        except Exception as e:
            print(f"[Sherlock] WARNING: graph building failed: {e}", file=sys.stderr)
            logger.warning("Graph build failed", exc_info=True)
    elif verbose:
        print(f"[Sherlock] Skipping graph mapping — need both entities and relationships (got {len(entities)}, {len(relationships) if 'relationships' in locals() else 0})")

    # ------------------------------------------------------------------
    # Timeline extraction: chunks/warehouse -> LLM returns unsorted timestamp/event -> we sort -> timeline.json
    # Per user spec: LLM can return in any order (encounter order), we sort chronologically and store.
    # ------------------------------------------------------------------
    timeline_events: List[Dict[str, Any]] = []
    timeline_paths: Dict[str, Any] = {}
    timeline_decision: Optional[Dict[str, Any]] = None
    if extract_timeline:
        if verbose:
            print(f"[Sherlock] Starting timeline extraction (batch_size={batch_size}, prefer_batched_when_fits={timeline_prefer_batched})")
        try:
            from engine.timeline import save_timeline_outputs
            from engine.llm import extract_timeline_auto as _extract_timeline_auto

            # Re-use same chunks + warehouse_text + cfg; timeline auto-decides strategy
            # Default for timeline is prefer_batched=False (single call when fits is more coherent)
            timeline_events, timeline_decision = _extract_timeline_auto(
                warehouse_text=warehouse_text,
                chunks=chunks,
                context_window=effective_window,
                batch_size=batch_size,
                llm_config=cfg,
                prefer_batched_when_fits=timeline_prefer_batched,
                verbose=verbose,
                sort_after=True,
            )
            # Persist sorted timeline
            timeline_paths = save_timeline_outputs(
                project_path, timeline_events, token_decision=timeline_decision, verbose=verbose
            )
            if verbose:
                print(f"[Sherlock] Timeline done: {len(timeline_events)} events sorted → {timeline_paths.get('timeline_json')}")
        except Exception as e:
            print(f"[Sherlock] WARNING: timeline extraction failed: {e}", file=sys.stderr)
            logger.warning("Timeline extraction failed", exc_info=True)
            # Non-fatal — keep empty timeline
    elif verbose:
        print(f"[Sherlock] Skipping timeline extraction — use --timeline to enable")

    return {
        "entities": entities,
        "relationships": relationships,
        "relations": relationships,  # alias
        "graph_mappings": graph_mappings,
        "graph_paths": graph_paths,
        "timeline": timeline_events,
        "timeline_events": timeline_events,  # alias
        "timeline_paths": timeline_paths,
        "timeline_decision": timeline_decision,
        "token_decision": decision,
        "stats": get_token_stats(warehouse_text),
        "llm_config": cfg,
    }


# ---------------------------------------------------------------------------
# CLI for standalone testing
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    import argparse
    import sys

    parser = argparse.ArgumentParser(description="Sherlock — Entity & Relationship & Timeline Extraction (batched LLM, via llm.json)")
    parser.add_argument("--project", required=True, help="Path to Sherlock project directory (must contain llm.json)")
    parser.add_argument("--batch-size", type=int, default=DEFAULT_BATCH_SIZE, help="Chunks per LLM batch (default 20)")
    parser.add_argument("--context-window", type=int, default=None, help="LLM context window tokens (default: from llm.json/provider)")
    parser.add_argument("--model", type=str, default=None, help="LLM model (default: from llm.json)")
    parser.add_argument("--single-call", action="store_true", help="Prefer single-call when warehouse fits (instead of batched)")
    parser.add_argument("--no-relationships", action="store_true", help="Skip relationship extraction")
    parser.add_argument("--timeline", action="store_true", help="Also run timeline extraction (chunks->LLM unsorted -> sort -> timeline.json)")
    parser.add_argument("--timeline-prefer-batched", action="store_true", help="For timeline, force batched even when warehouse fits (default: single_call when fits)")
    args = parser.parse_args()

    try:
        result = run_extraction_pipeline(
            project_path=args.project,
            batch_size=args.batch_size,
            context_window=args.context_window,
            model=args.model,
            prefer_batched_when_fits=not args.single_call,
            extract_relationships=not args.no_relationships,
            extract_timeline=args.timeline,
            timeline_prefer_batched=args.timeline_prefer_batched,
            verbose=True,
        )
        print(f"[Sherlock] Done — {len(result['entities'])} entities, {len(result['relationships'])} relationships, {len(result.get('timeline', []))} timeline events")
        if args.timeline and result.get("timeline_paths"):
            print(f"[Sherlock] Timeline → {result['timeline_paths'].get('timeline_json')}")
    except Exception as e:
        print(f"[Sherlock] ERROR: {e}", file=sys.stderr)
        logger.exception("Extraction pipeline failed")
        sys.exit(1)
