"""
engine/contradictions.py — Contradiction detection, truth comparison, and persistence for Sherlock.

Responsibilities:
  1. Load contextual case knowledge from processed/ (entities.json, relations.json, timeline.json, graph_data.json)
  2. Orchestrate contradiction detection via LLM (batched/single) using engine.llm
  3. Validate, score severity, assign sequential IDs, and deduplicate contradictory claims
  4. Persist structured results to processed/contradictions.json (with severity and type breakdowns)

Pipeline per user spec:
  Input data (warehouse.txt + chunks + processed/ context)
  → LLM compares statements and extracts conflicting truths / false alibis / timeline clashes
  → Normalized and deduplicated
  → Stored in processed/contradictions.json (and processed/contradiction_data.json)

Contradiction record schema:
  {
    "contradiction_id": "contra_001",
    "type": "ALIBI_VS_EVIDENCE | STATEMENT_VS_STATEMENT | TIMELINE_CONFLICT | RELATIONSHIP_DENIAL | FINANCIAL_OR_RECORD_MISMATCH | PHYSICAL_VS_TESTIMONIAL | FACTUAL_INCONSISTENCY",
    "summary": "Arjun Dev's Delhi travel claim vs Mumbai CCTV footage",
    "description": "Detailed explanation of why both statements cannot simultaneously be true.",
    "severity": "CRITICAL | HIGH | MEDIUM | LOW",
    "confidence": 0.96,
    "entities_involved": ["Arjun Dev", "Gateway Hotel", "CCTV Camera 4"],
    "conflicting_points": [
      {
        "claim": "Arjun claimed he flew to Delhi and stayed in his hotel room all evening.",
        "speaker_or_source": "Arjun Dev (Witness Statement)",
        "source_file": "02_witness_arjun.txt",
        "chunk_id": "02_witness_arjun_chunk_001",
        "quote": "I took the morning flight to Delhi and stayed in my hotel room all evening."
      },
      {
        "claim": "CCTV records Arjun Dev entering Gateway Hotel in Mumbai at 16:15.",
        "speaker_or_source": "CCTV Security Log",
        "source_file": "05_cctv_log.txt",
        "chunk_id": "05_cctv_log_chunk_003",
        "quote": "16:15:22 - Camera 4 captured Arjun Dev entering via South Lobby entrance."
      }
    ],
    "resolution_status": "POTENTIAL_LIE",
    "investigation_lead": "Confront Arjun Dev with the Gateway Hotel Mumbai CCTV timestamp and subpoena airline manifests."
  }
"""

from __future__ import annotations

import json
import logging
import sys
from collections import Counter
from pathlib import Path
from typing import Any, Dict, List, Optional

# Force UTF-8 on stdout/stderr
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
    extract_contradictions_auto,
    extract_contradictions_batched,
    extract_contradictions_single_call,
    get_token_stats,
    load_llm_config,
    resolve_llm_config_path,
)

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Load Processed Context Helper
# ---------------------------------------------------------------------------

def load_processed_context(project_path: str | Path) -> Dict[str, Any]:
    """
    Load previously extracted processed files from <project>/processed/.
    Returns a dictionary with 'entities', 'relations', 'timeline', and 'graph'.
    """
    project_path = Path(project_path).expanduser().resolve()
    processed_dir = project_path / "processed"

    context: Dict[str, Any] = {
        "entities": [],
        "relations": [],
        "timeline": [],
        "graph": [],
    }

    if not processed_dir.exists():
        return context

    # 1. Load entities
    entities_path = processed_dir / "entities.json"
    if entities_path.exists():
        try:
            with entities_path.open("r", encoding="utf-8") as f:
                data = json.load(f)
                if isinstance(data, dict) and "entities" in data:
                    context["entities"] = data["entities"]
                elif isinstance(data, list):
                    context["entities"] = data
        except Exception as e:
            logger.warning(f"Could not read entities.json for contradiction context: {e}")

    # 2. Load relations
    relations_path = processed_dir / "relations.json"
    if not relations_path.exists():
        relations_path = processed_dir / "relationships.json"
    if relations_path.exists():
        try:
            with relations_path.open("r", encoding="utf-8") as f:
                data = json.load(f)
                if isinstance(data, dict):
                    context["relations"] = data.get("relations") or data.get("relationships") or []
                elif isinstance(data, list):
                    context["relations"] = data
        except Exception as e:
            logger.warning(f"Could not read relations.json for contradiction context: {e}")

    # 3. Load timeline
    timeline_path = processed_dir / "timeline.json"
    if timeline_path.exists():
        try:
            with timeline_path.open("r", encoding="utf-8") as f:
                data = json.load(f)
                if isinstance(data, dict):
                    context["timeline"] = data.get("timeline") or data.get("events") or []
                elif isinstance(data, list):
                    context["timeline"] = data
        except Exception as e:
            logger.warning(f"Could not read timeline.json for contradiction context: {e}")

    # 4. Load graph_data
    graph_path = processed_dir / "graph_data.json"
    if not graph_path.exists():
        graph_path = processed_dir / "graph.json"
    if graph_path.exists():
        try:
            with graph_path.open("r", encoding="utf-8") as f:
                data = json.load(f)
                if isinstance(data, dict):
                    context["graph"] = data.get("graph") or data.get("nodes") or []
                elif isinstance(data, list):
                    context["graph"] = data
        except Exception as e:
            logger.warning(f"Could not read graph_data.json for contradiction context: {e}")

    return context


# ---------------------------------------------------------------------------
# Persistence
# ---------------------------------------------------------------------------

def save_contradiction_outputs(
    project_path: str | Path,
    contradictions: List[Dict[str, Any]],
    token_decision: Optional[Dict[str, Any]] = None,
    verbose: bool = True,
) -> Dict[str, Path]:
    """
    Save extracted contradictions with full metadata & breakdown to processed/contradictions.json.
    """
    project_path = Path(project_path).expanduser().resolve()
    processed_dir = project_path / "processed"
    processed_dir.mkdir(parents=True, exist_ok=True)

    # Compute breakdown statistics
    severity_counts = Counter(c.get("severity", "HIGH") for c in contradictions)
    type_counts = Counter(c.get("type", "FACTUAL_INCONSISTENCY") for c in contradictions)
    status_counts = Counter(c.get("resolution_status", "UNRESOLVED") for c in contradictions)

    severity_breakdown = {
        "CRITICAL": severity_counts.get("CRITICAL", 0),
        "HIGH": severity_counts.get("HIGH", 0),
        "MEDIUM": severity_counts.get("MEDIUM", 0),
        "LOW": severity_counts.get("LOW", 0),
    }

    # Resolve LLM info for header
    try:
        cfg = load_llm_config(project_path)
        llm_info = {"provider": cfg.provider, "model": cfg.model, "base_url": cfg.base_url}
    except Exception:
        llm_info = {}

    summary_text = (
        f"Detected {len(contradictions)} contradiction(s) "
        f"({severity_breakdown['CRITICAL']} critical, {severity_breakdown['HIGH']} high, "
        f"{severity_breakdown['MEDIUM']} medium, {severity_breakdown['LOW']} low)."
        if contradictions
        else "No contradictions detected across evidence."
    )

    payload: Dict[str, Any] = {
        "project": project_path.name,
        "project_path": str(project_path),
        "llm": llm_info,
        "total_contradictions": len(contradictions),
        "summary": summary_text,
        "severity_breakdown": severity_breakdown,
        "types_breakdown": dict(type_counts),
        "status_breakdown": dict(status_counts),
        "contradictions": contradictions,
        # Alias for backward compatibility
        "items": contradictions,
    }
    if token_decision is not None:
        payload["token_decision"] = token_decision

    contradictions_path = processed_dir / "contradictions.json"
    with contradictions_path.open("w", encoding="utf-8") as f:
        json.dump(payload, f, indent=2, ensure_ascii=False)

    compat_path = processed_dir / "contradiction_data.json"
    with compat_path.open("w", encoding="utf-8") as f:
        json.dump(payload, f, indent=2, ensure_ascii=False)

    if verbose:
        print(f"[Sherlock Contradictions] Saved {len(contradictions)} contradictions → {contradictions_path}")

    return {
        "contradictions_json": contradictions_path,
        "contradiction_data_json": compat_path,
    }


# ---------------------------------------------------------------------------
# High-Level Pipeline
# ---------------------------------------------------------------------------

def run_contradictions_pipeline(
    project_path: str | Path,
    batch_size: int = DEFAULT_BATCH_SIZE,
    context_window: Optional[int] = None,
    model: Optional[str] = None,
    llm_config: Optional[LLMConfig | Dict[str, Any]] = None,
    prefer_batched_when_fits: bool = False,
    verbose: bool = True,
) -> Dict[str, Any]:
    """
    Standalone runner for contradiction detection on a Sherlock project.
    """
    project_path = Path(project_path).expanduser().resolve()
    warehouse_path = project_path / "warehouse.txt"
    chunks_path = project_path / "processed" / "chunks.json"

    if not warehouse_path.exists():
        raise FileNotFoundError(f"warehouse.txt not found at {warehouse_path}")

    warehouse_text = warehouse_path.read_text(encoding="utf-8", errors="replace")
    if not warehouse_text.strip():
        raise ValueError("warehouse.txt is empty")

    # Load chunks
    chunks: List[Dict[str, Any]] = []
    if chunks_path.exists():
        try:
            with chunks_path.open("r", encoding="utf-8") as f:
                c_data = json.load(f)
                chunks = c_data.get("chunks", [])
        except Exception as e:
            logger.warning(f"Could not read chunks.json: {e}")

    if not chunks:
        from engine.chunk import semantic_chunk_text

        if verbose:
            print("[Sherlock Contradictions] Generating chunks inline...")
        chunks = semantic_chunk_text(warehouse_text, source_file="warehouse.txt")

    # Load processed context (entities, relations, timeline)
    context = load_processed_context(project_path)
    if verbose:
        print(
            f"[Sherlock Contradictions] Loaded case context: "
            f"{len(context['entities'])} entities, {len(context['relations'])} relations, {len(context['timeline'])} timeline events"
        )

    # Resolve LLM config
    overrides: Dict[str, Any] = {}
    if model is not None:
        overrides["model"] = model
    if context_window is not None:
        overrides["context_window"] = context_window
    if isinstance(llm_config, dict):
        overrides.update(llm_config)
        llm_config = None
    cfg: LLMConfig = (
        llm_config if isinstance(llm_config, LLMConfig) else load_llm_config(project_path, overrides=overrides or None)
    )
    effective_window = context_window if context_window is not None else cfg.context_window

    if verbose:
        llm_src = resolve_llm_config_path(project_path)
        print(f"[Sherlock Contradictions] LLM: {cfg.masked()} (source: {llm_src or 'env'})")

    contradictions, decision = extract_contradictions_auto(
        warehouse_text=warehouse_text,
        chunks=chunks,
        context_window=effective_window,
        batch_size=batch_size,
        llm_config=cfg,
        prefer_batched_when_fits=prefer_batched_when_fits,
        known_entities=context["entities"],
        known_relations=context["relations"],
        known_timeline=context["timeline"],
        verbose=verbose,
    )

    paths = save_contradiction_outputs(
        project_path=project_path,
        contradictions=contradictions,
        token_decision=decision,
        verbose=verbose,
    )

    return {
        "contradictions": contradictions,
        "paths": paths,
        "token_decision": decision,
        "stats": get_token_stats(warehouse_text),
        "llm_config": cfg,
    }


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="Sherlock — Contradiction Detection Pipeline")
    parser.add_argument("--project", required=True, help="Path to Sherlock project directory")
    parser.add_argument("--batch-size", type=int, default=DEFAULT_BATCH_SIZE, help="Batch size")
    parser.add_argument("--context-window", type=int, default=None, help="LLM context window")
    parser.add_argument("--model", type=str, default=None, help="LLM model")
    parser.add_argument("--prefer-batched", action="store_true", help="Force batched mode even when warehouse fits")
    args = parser.parse_args()

    res = run_contradictions_pipeline(
        project_path=args.project,
        batch_size=args.batch_size,
        context_window=args.context_window,
        model=args.model,
        prefer_batched_when_fits=args.prefer_batched,
        verbose=True,
    )
    print(f"Done. Detected {len(res['contradictions'])} contradictions.")
