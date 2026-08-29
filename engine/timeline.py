"""
engine/timeline.py — Timeline extraction + sorting + persistence for Sherlock.

Responsibilities:
  1. Orchestrate timeline extraction via LLM (batched/single) using engine.llm
  2. Sort events chronologically regardless of LLM encounter order
  3. Persist to processed/timeline.json (primary) with sorted timeline

Pipeline per user spec:
  Input data (warehouse.txt + chunks) → LLM returns JSON array [{timestamp, event}, ...] in ANY order
  → we sort by timestamp → store in processed/timeline.json

Timeline event schema (canonical, as returned after normalization + sorting):
  {
    "timestamp": "2026-04-14 18:30",              # original/normalized string from LLM (kept as-is or lightly normalized)
    "timestamp_normalized": "2026-04-14 18:30",   # optional, parsed normalized form if different
    "_parsed_datetime": "2026-04-14T18:30:00",    # iso for debugging/sorting
    "event": "Rose Mathew found dead in Flat 3B by Ananya Joseph",
    "source_file": "01_case_summary.txt",
    "chunk_id": "01_case_summary_chunk_001",
    "confidence": 0.98,
    "evidence_text": "Rose Mathew was found dead at 18:30"
  }

Sorting: robust timestamp parsing via engine.llm._try_parse_timeline_timestamp + sort_timeline_events.
Storage: processed/timeline.json + processed/graph_data.json symmetry; also graph.json alias unaffected.

Usage:
  from engine.timeline import run_timeline_pipeline, save_timeline_outputs
  events = run_timeline_pipeline(project_path, batch_size=20, verbose=True)
"""

from __future__ import annotations

import json
import logging
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional

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
    LLMConfig,
    decide_strategy,
    extract_timeline_auto,
    extract_timeline_batched,
    extract_timeline_single_call,
    get_token_stats,
    load_llm_config,
    resolve_llm_config_path,
    sort_timeline_events,
)

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Persistence
# ---------------------------------------------------------------------------

def save_timeline_outputs(
    project_path: str | Path,
    timeline_events: List[Dict[str, Any]],
    token_decision: Optional[Dict[str, Any]] = None,
    verbose: bool = True,
) -> Dict[str, Path]:
    """
    Save sorted timeline to processed/timeline.json.

    The LLM may return events in any order (encounter order). This function
    ensures the stored JSON is sorted chronologically.

    Args:
        project_path: case folder containing processed/
        timeline_events: list of timeline events (ideally already sorted; will re-sort to be safe)
        token_decision: optional decision dict from decide_strategy (for provenance)
        verbose: print progress

    Returns:
        dict of paths written: {"timeline_json": Path}
    """
    project_path = Path(project_path).expanduser().resolve()
    processed_dir = project_path / "processed"
    processed_dir.mkdir(parents=True, exist_ok=True)

    # Ensure chronological order regardless of input order — per user spec
    sorted_events = sort_timeline_events(timeline_events)

    # Resolve LLM info for header (best effort)
    try:
        cfg = load_llm_config(project_path)
        llm_info = {"provider": cfg.provider, "model": cfg.model, "base_url": cfg.base_url}
    except Exception:
        llm_info = {}

    # Also compute stats about timeline span
    timeline_start = sorted_events[0].get("timestamp") if sorted_events else None
    timeline_end = sorted_events[-1].get("timestamp") if sorted_events else None

    payload: Dict[str, Any] = {
        "project": project_path.name,
        "project_path": str(project_path),
        "llm": llm_info,
        "total_events": len(sorted_events),
        "timeline_start": timeline_start,
        "timeline_end": timeline_end,
        "sorted": True,
        "sort_note": "Timeline is sorted chronologically by parsed timestamp regardless of LLM encounter order. Unparseable timestamps are at the end, sorted lexicographically.",
        "timeline": sorted_events,
        # Alias for compatibility
        "events": sorted_events,
    }
    if token_decision is not None:
        payload["token_decision"] = token_decision

    # Primary output
    timeline_path = processed_dir / "timeline.json"
    with timeline_path.open("w", encoding="utf-8") as f:
        json.dump(payload, f, indent=2, ensure_ascii=False)

    if verbose:
        print(f"[Sherlock Timeline] Saved {len(sorted_events)} events → {timeline_path} (sorted chronologically)")

    return {"timeline_json": timeline_path}


# ---------------------------------------------------------------------------
# High-level pipeline
# ---------------------------------------------------------------------------

def run_timeline_pipeline(
    project_path: str | Path,
    batch_size: int = DEFAULT_BATCH_SIZE,
    context_window: Optional[int] = None,
    model: Optional[str] = None,
    llm_config: Optional[LLMConfig | Dict[str, Any]] = None,
    prefer_batched_when_fits: bool = False,
    verbose: bool = True,
    sort_after: bool = True,
) -> Dict[str, Any]:
    """
    Run full timeline extraction for a Sherlock project.

    Steps:
      1. Load warehouse.txt + chunks.json (generate if missing)
      2. Resolve LLM config (llm.json > env > overrides)
      3. Auto-decide batched vs single_call based on token window
      4. Call LLM to extract timeline events (any order from LLM)
      5. Sort chronologically + save to processed/timeline.json

    Args:
        project_path: path to case folder containing warehouse.txt, processed/chunks.json, llm.json
        batch_size: chunks per LLM batch (default 20)
        context_window: LLM token window — if None, uses llm.json / provider default
        model: LLM model name — if None, uses llm.json model
        llm_config: explicit LLMConfig or dict (provider/model/api_key); if None, loads <project>/llm.json
        prefer_batched_when_fits: if True, force batched even when warehouse fits; default False (single_call is more coherent for timeline)
        verbose: print progress
        sort_after: if True (default), sort returned events chronologically

    Returns:
        dict with keys: timeline (sorted), token_decision, stats, llm_config, paths

    Side-effects:
        Writes processed/timeline.json
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

    # Load chunks (required for batched mode; also helpful for provenance in single-call)
    if chunks_path.exists():
        with chunks_path.open("r", encoding="utf-8") as f:
            chunks_data = json.load(f)
        chunks: List[Dict[str, Any]] = chunks_data.get("chunks", [])
        if not chunks:
            raise ValueError("chunks.json exists but contains no chunks")
    else:
        # Fallback: generate chunks on the fly if missing
        if verbose:
            print(f"[Sherlock Timeline] chunks.json not found, generating chunks inline...")
        from engine.chunk import semantic_chunk_text

        chunks = semantic_chunk_text(warehouse_text, source_file="warehouse.txt")
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

    # Resolve LLM config
    overrides: Dict[str, Any] = {}
    if model is not None:
        overrides["model"] = model
    if context_window is not None:
        overrides["context_window"] = context_window
    if isinstance(llm_config, dict):
        overrides.update(llm_config)
        llm_config = None  # will re-load with merged overrides
    cfg: LLMConfig = llm_config if isinstance(llm_config, LLMConfig) else load_llm_config(project_path, overrides=overrides or None)
    effective_window = context_window if context_window is not None else cfg.context_window
    if verbose:
        llm_src = resolve_llm_config_path(project_path)
        src_str = str(llm_src) if llm_src else "env vars"
        print(f"[Sherlock Timeline] LLM config: {cfg.masked()} (source: {src_str})")

    # Token-window decision (logging)
    decision = decide_strategy(
        warehouse_text, chunks, context_window=effective_window, prefer_batched_when_fits=prefer_batched_when_fits, batch_size=batch_size
    )
    if verbose:
        print(f"[Sherlock Timeline] Warehouse stats: {get_token_stats(warehouse_text)}")
        print(f"[Sherlock Timeline] Decision: strategy={decision['strategy']} | {decision['reason']}")
        print(f"[Sherlock Timeline] Chunks: {len(chunks)} | Batches (size {batch_size}): {decision['batches_needed']}")

    # Timeline extraction (auto chooses single vs batched)
    # Note: LLM may return events in any order — we sort afterwards regardless
    timeline_events, _ = extract_timeline_auto(
        warehouse_text=warehouse_text,
        chunks=chunks,
        context_window=effective_window,
        batch_size=batch_size,
        llm_config=cfg,
        prefer_batched_when_fits=prefer_batched_when_fits,
        verbose=verbose,
        sort_after=False,  # get encounter order first, then sort in save step to demonstrate sorting
    )

    # Sort explicitly (even though extract_timeline_auto could sort, we want to show sorting step per spec)
    # Per spec: "it can just give it any order like in order it encountered the things we will sort it"
    sorted_events = sort_timeline_events(timeline_events) if sort_after else timeline_events

    if verbose and sort_after:
        # Show that we sorted
        if timeline_events and sorted_events and timeline_events[0].get("timestamp") != sorted_events[0].get("timestamp"):
            print(f"[Sherlock Timeline] Sorted {len(sorted_events)} events chronologically (LLM returned encounter order; we sorted).")
        else:
            print(f"[Sherlock Timeline] Timeline already in chronological order ({len(sorted_events)} events).")

    # Save (save_timeline_outputs also re-sorts to be safe)
    paths = save_timeline_outputs(project_path, sorted_events, token_decision=decision, verbose=verbose)

    return {
        "timeline": sorted_events,
        "events": sorted_events,  # alias
        "total_events": len(sorted_events),
        "token_decision": decision,
        "stats": get_token_stats(warehouse_text),
        "llm_config": cfg,
        "paths": paths,
    }


# ---------------------------------------------------------------------------
# Legacy helper: sort existing timeline.json in-place (useful for re-sort)
# ---------------------------------------------------------------------------

def sort_existing_timeline_file(project_path: str | Path, verbose: bool = True) -> Dict[str, Any]:
    """
    Load existing processed/timeline.json, sort its timeline chronologically, and overwrite.
    Useful if file was created before sorting was enforced.

    Returns dict with sorted events and path.
    """
    project_path = Path(project_path).expanduser().resolve()
    timeline_path = project_path / "processed" / "timeline.json"
    if not timeline_path.exists():
        raise FileNotFoundError(f"timeline.json not found at {timeline_path}")

    with timeline_path.open("r", encoding="utf-8") as f:
        data = json.load(f)

    # Support both "timeline" and "events" keys
    raw_events: List[Dict[str, Any]] = data.get("timeline") or data.get("events") or []
    if not raw_events:
        raise ValueError("timeline.json contains no events")

    sorted_events = sort_timeline_events(raw_events)
    # Update file
    data["timeline"] = sorted_events
    data["events"] = sorted_events
    data["sorted"] = True
    data["total_events"] = len(sorted_events)
    if sorted_events:
        data["timeline_start"] = sorted_events[0].get("timestamp")
        data["timeline_end"] = sorted_events[-1].get("timestamp")

    with timeline_path.open("w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)

    if verbose:
        print(f"[Sherlock Timeline] Re-sorted {len(sorted_events)} events → {timeline_path}")

    return {"timeline": sorted_events, "path": timeline_path}


# ---------------------------------------------------------------------------
# CLI for standalone testing
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    import argparse
    import sys

    parser = argparse.ArgumentParser(description="Sherlock — Timeline Extraction (batched LLM, sorted chronologically)")
    parser.add_argument("--project", required=True, help="Path to Sherlock project directory (must contain warehouse.txt and llm.json)")
    parser.add_argument("--batch-size", type=int, default=DEFAULT_BATCH_SIZE, help="Chunks per LLM batch (default 20)")
    parser.add_argument("--context-window", type=int, default=None, help="LLM context window tokens (default: from llm.json/provider)")
    parser.add_argument("--model", type=str, default=None, help="LLM model (default: from llm.json)")
    parser.add_argument("--prefer-batched", action="store_true", help="Force batched mode even when warehouse fits (default: single_call when fits)")
    parser.add_argument("--re-sort", action="store_true", help="Just re-sort existing timeline.json chronologically (no LLM call)")
    args = parser.parse_args()

    try:
        if args.re_sort:
            result = sort_existing_timeline_file(args.project, verbose=True)
            print(f"[Sherlock Timeline] Done — re-sorted {len(result['timeline'])} events")
        else:
            result = run_timeline_pipeline(
                project_path=args.project,
                batch_size=args.batch_size,
                context_window=args.context_window,
                model=args.model,
                prefer_batched_when_fits=args.prefer_batched,
                verbose=True,
            )
            print(f"[Sherlock Timeline] Done — {len(result['timeline'])} events sorted chronologically → {result['paths']['timeline_json']}")
    except Exception as e:
        print(f"[Sherlock Timeline] ERROR: {e}", file=sys.stderr)
        logger.exception("Timeline pipeline failed")
        sys.exit(1)
