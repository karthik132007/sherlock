"""
engine/opinion.py — Sherlock's Opinion & Deep Forensic Theory Deduction.

Responsibilities:
  1. Load all contextual case knowledge (warehouse.txt, entities.json, relations.json, timeline.json, contradictions.json).
  2. Orchestrate deep brainstorming / dot-connecting with LLM thinking mode enabled (DeepSeek reasoner, OpenRouter, OpenAI, Ollama).
  3. Validate and normalize the primary hypothesis, supporting evidence (with verbatim quotes & citations), flaws/counter-evidence, alternative hypotheses, and actionable leads.
  4. Persist structured results to processed/opinion.json and processed/opinion_data.json.
"""

from __future__ import annotations

import json
import logging
import sys
from datetime import datetime, timezone
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
    LLMConfig,
    extract_opinion_auto,
    load_llm_config,
)

logger = logging.getLogger(__name__)


def load_processed_context(project_path: str | Path) -> Dict[str, Any]:
    """
    Load previously extracted processed files from <project>/processed/.
    Returns a dictionary with 'entities', 'relations', 'timeline', 'contradictions', and 'graph'.
    """
    project_path = Path(project_path).expanduser().resolve()
    processed_dir = project_path / "processed"

    context: Dict[str, Any] = {
        "entities": [],
        "relations": [],
        "timeline": [],
        "contradictions": [],
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
            logger.warning(f"Could not read entities.json for opinion context: {e}")

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
            logger.warning(f"Could not read relations.json for opinion context: {e}")

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
            logger.warning(f"Could not read timeline.json for opinion context: {e}")

    # 4. Load contradictions
    contradictions_path = processed_dir / "contradictions.json"
    if not contradictions_path.exists():
        contradictions_path = processed_dir / "contradiction_data.json"
    if contradictions_path.exists():
        try:
            with contradictions_path.open("r", encoding="utf-8") as f:
                data = json.load(f)
                if isinstance(data, dict):
                    context["contradictions"] = data.get("contradictions") or data.get("items") or []
                elif isinstance(data, list):
                    context["contradictions"] = data
        except Exception as e:
            logger.warning(f"Could not read contradictions.json for opinion context: {e}")

    return context


def normalize_evidence_point(item: Any) -> Dict[str, Any]:
    """Normalize a supporting evidence point."""
    if isinstance(item, str):
        return {
            "claim": item,
            "source_file": "",
            "chunk_id": "",
            "quote": "",
            "relevance": "",
            "entities_involved": [],
        }
    if not isinstance(item, dict):
        return {
            "claim": str(item),
            "source_file": "",
            "chunk_id": "",
            "quote": "",
            "relevance": "",
            "entities_involved": [],
        }
    return {
        "claim": str(item.get("claim", "")).strip(),
        "source_file": str(item.get("source_file", "")).strip(),
        "chunk_id": str(item.get("chunk_id", "")).strip(),
        "quote": str(item.get("quote", "")).strip(),
        "relevance": str(item.get("relevance", "")).strip(),
        "entities_involved": list(item.get("entities_involved", [])) if isinstance(item.get("entities_involved"), list) else [],
    }


def normalize_flaw_point(item: Any) -> Dict[str, Any]:
    """Normalize a flaw or counter-evidence point."""
    if isinstance(item, str):
        return {
            "point": item,
            "type": "MISSING_EVIDENCE",
            "source_file": "",
            "chunk_id": "",
            "quote": "",
            "impact": "",
            "entities_involved": [],
        }
    if not isinstance(item, dict):
        return {
            "point": str(item),
            "type": "MISSING_EVIDENCE",
            "source_file": "",
            "chunk_id": "",
            "quote": "",
            "impact": "",
            "entities_involved": [],
        }
    return {
        "point": str(item.get("point", item.get("claim", ""))).strip(),
        "type": str(item.get("type", "MISSING_EVIDENCE")).upper().strip(),
        "source_file": str(item.get("source_file", "")).strip(),
        "chunk_id": str(item.get("chunk_id", "")).strip(),
        "quote": str(item.get("quote", "")).strip(),
        "impact": str(item.get("impact", item.get("explanation", ""))).strip(),
        "entities_involved": list(item.get("entities_involved", [])) if isinstance(item.get("entities_involved"), list) else [],
    }


def normalize_possible_cause(item: Any) -> Dict[str, Any]:
    """Normalize a possible root cause / catalyst point."""
    if isinstance(item, str):
        return {
            "cause": item,
            "category": "MOTIVE",
            "evidence_indicators": [],
            "significance": "",
        }
    if not isinstance(item, dict):
        return {
            "cause": str(item),
            "category": "MOTIVE",
            "evidence_indicators": [],
            "significance": "",
        }
    return {
        "cause": str(item.get("cause", item.get("title", ""))).strip(),
        "category": str(item.get("category", "MOTIVE")).upper().strip(),
        "evidence_indicators": list(item.get("evidence_indicators", item.get("indicators", []))) if isinstance(item.get("evidence_indicators", item.get("indicators", [])), list) else [],
        "significance": str(item.get("significance", item.get("description", ""))).strip(),
    }


def normalize_alternative_hypothesis(item: Any) -> Dict[str, Any]:
    """Normalize an alternative hypothesis."""
    if isinstance(item, str):
        return {
            "title": "Alternative Theory",
            "description": item,
            "supporting_points": [],
            "counter_points": [],
        }
    if not isinstance(item, dict):
        return {
            "title": "Alternative Theory",
            "description": str(item),
            "supporting_points": [],
            "counter_points": [],
        }
    return {
        "title": str(item.get("title", item.get("name", "Alternative Theory"))).strip(),
        "description": str(item.get("description", item.get("summary", ""))).strip(),
        "supporting_points": list(item.get("supporting_points", [])) if isinstance(item.get("supporting_points"), list) else [],
        "counter_points": list(item.get("counter_points", [])) if isinstance(item.get("counter_points"), list) else [],
    }


def normalize_investigative_lead(item: Any) -> Dict[str, Any]:
    """Normalize an investigative lead item."""
    if isinstance(item, str):
        return {
            "lead": item,
            "priority": "HIGH",
            "action": item,
            "rationale": "",
        }
    if not isinstance(item, dict):
        return {
            "lead": str(item),
            "priority": "HIGH",
            "action": str(item),
            "rationale": "",
        }
    return {
        "lead": str(item.get("lead", item.get("title", ""))).strip(),
        "priority": str(item.get("priority", "HIGH")).upper().strip(),
        "action": str(item.get("action", item.get("lead", ""))).strip(),
        "rationale": str(item.get("rationale", item.get("reason", ""))).strip(),
    }


def generate_opinion(
    project_path: str | Path,
    model: Optional[str] = None,
    llm_config: Optional[LLMConfig | Dict[str, Any]] = None,
    save_outputs: bool = True,
    enable_thinking: bool = True,
    verbose: bool = True,
) -> Dict[str, Any]:
    """
    Generate Sherlock's Opinion for a project directory.
    Loads warehouse.txt and existing processed artifacts, invokes LLM with thinking mode,
    normalizes the output, and optionally saves to processed/opinion.json.
    """
    project_path = Path(project_path).expanduser().resolve()
    warehouse_path = project_path / "warehouse.txt"

    if not warehouse_path.exists():
        raise FileNotFoundError(f"warehouse.txt not found at {warehouse_path}")

    warehouse_text = warehouse_path.read_text(encoding="utf-8", errors="replace")
    context = load_processed_context(project_path)

    # Chunks if available
    chunks = []
    chunks_path = project_path / "processed" / "chunks.json"
    if chunks_path.exists():
        try:
            with chunks_path.open("r", encoding="utf-8") as f:
                c_data = json.load(f)
                chunks = c_data if isinstance(c_data, list) else c_data.get("chunks", [])
        except Exception:
            pass

    raw_result = extract_opinion_auto(
        warehouse_text=warehouse_text,
        chunks=chunks,
        known_entities=context["entities"],
        known_relations=context["relations"],
        known_timeline=context["timeline"],
        known_contradictions=context["contradictions"],
        model=model,
        llm_config=llm_config,
        project_path=project_path,
        enable_thinking=enable_thinking,
        verbose=verbose,
    )

    case_name = project_path.name
    case_id = raw_result.get("case_id")
    if not case_id or case_id == "UNKNOWN_CASE":
        case_id = case_name

    supporting_ev = [normalize_evidence_point(p) for p in raw_result.get("supporting_evidence", [])]
    flaws = [normalize_flaw_point(f) for f in raw_result.get("flaws_and_counter_evidence", [])]
    alt_hypotheses = [normalize_alternative_hypothesis(h) for h in raw_result.get("alternative_hypotheses", [])]
    leads = [normalize_investigative_lead(l) for l in raw_result.get("investigative_leads", [])]
    causes = [normalize_possible_cause(c) for c in raw_result.get("possible_causes", [])]

    final_opinion: Dict[str, Any] = {
        "case_id": case_id,
        "preliminary_analysis": str(raw_result.get("preliminary_analysis", "")).strip(),
        "possible_causes": causes,
        "self_debate_summary": str(raw_result.get("self_debate_summary", "")).strip(),
        "executive_summary": str(raw_result.get("executive_summary", "")).strip(),
        "primary_hypothesis": str(raw_result.get("primary_hypothesis", "")).strip(),
        "confidence": float(raw_result.get("confidence", 0.85)),
        "confidence_explanation": str(raw_result.get("confidence_explanation", "")).strip(),
        "supporting_evidence": supporting_ev,
        "flaws_and_counter_evidence": flaws,
        "alternative_hypotheses": alt_hypotheses,
        "investigative_leads": leads,
        "reasoning_trace": str(raw_result.get("reasoning_trace", "")).strip(),
        "generated_at": datetime.now(timezone.utc).isoformat(),
    }

    if save_outputs:
        save_opinion(project_path, final_opinion, verbose=verbose)

    return final_opinion


def save_opinion(
    project_path: str | Path,
    opinion_data: Dict[str, Any],
    verbose: bool = True,
) -> Path:
    """Save opinion data to <project>/processed/opinion.json and opinion_data.json."""
    project_path = Path(project_path).expanduser().resolve()
    processed_dir = project_path / "processed"
    processed_dir.mkdir(parents=True, exist_ok=True)

    out_file = processed_dir / "opinion.json"
    alt_file = processed_dir / "opinion_data.json"

    content = json.dumps(opinion_data, indent=2, ensure_ascii=False)
    out_file.write_text(content, encoding="utf-8")
    alt_file.write_text(content, encoding="utf-8")

    if verbose:
        print(f"[Sherlock Opinion] Saved Sherlock's Opinion to {out_file} (and {alt_file})")

    return out_file
