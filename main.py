#!/usr/bin/env python3
"""
Sherlock Processing Pipeline — warehouse → chunking → LLM extraction
Usage:
    python main.py --project "/path/to/project"
    python main.py --project "/path/to/project" --extract
    python main.py --project "/path/to/project" --extract --batch-size 20

Flow:
  1. Read warehouse.txt
  2. Check token count vs LLM context window (default 500k) — decides single-call vs batched
  3. Semantic chunking → chunks.json
  4. (optional --extract) Entity + relationship extraction via batched LLM with dedup context

Expected project structure:
    project/
    ├── data/
    ├── processed/
    └── warehouse.txt
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
from pathlib import Path

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

# Ensure engine is importable when running as script
sys.path.insert(0, str(Path(__file__).resolve().parent))

from engine.chunk import DEFAULT_CHUNK_SIZE, DEFAULT_OVERLAP, semantic_chunk_text

logging.basicConfig(level=logging.INFO, format="%(levelname)s: %(message)s")
logger = logging.getLogger(__name__)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Sherlock — Pipeline: warehouse → token-window check → chunking → (optional) LLM extraction"
    )
    parser.add_argument(
        "--project",
        required=True,
        help="Path to Sherlock project directory containing warehouse.txt",
    )
    parser.add_argument(
        "--chunk-size",
        type=int,
        default=DEFAULT_CHUNK_SIZE,
        help=f"Chunk size in characters (default: {DEFAULT_CHUNK_SIZE})",
    )
    parser.add_argument(
        "--overlap",
        type=int,
        default=DEFAULT_OVERLAP,
        help=f"Overlap in characters (default: {DEFAULT_OVERLAP})",
    )
    # LLM / extraction args
    parser.add_argument(
        "--extract",
        action="store_true",
        help="After chunking, run LLM entity & relationship extraction (batched, with dedup)",
    )
    parser.add_argument(
        "--batch-size",
        type=int,
        default=20,
        help="Chunks per LLM batch (default 20) — each call also gets previous results for dedup",
    )
    parser.add_argument(
        "--context-window",
        type=int,
        default=None,
        help="LLM context window in tokens (default: from llm.json/provider, fallback 500000) — used for warehouse vs chunk decision",
    )
    parser.add_argument(
        "--model",
        type=str,
        default=None,
        help="LLM model name (default from env LLM_MODEL or gpt-4o-mini)",
    )
    parser.add_argument(
        "--single-call",
        action="store_true",
        help="When warehouse fits in window, use single LLM call instead of batched (batched is default per spec to avoid alias dupes)",
    )
    parser.add_argument(
        "--no-relationships",
        action="store_true",
        help="Skip relationship extraction (entities only)",
    )
    parser.add_argument(
        "--timeline",
        action="store_true",
        help="Also run timeline extraction (chunks->LLM unsorted timestamp/event -> sorted -> timeline.json)",
    )
    parser.add_argument(
        "--timeline-only",
        action="store_true",
        help="Run ONLY timeline extraction (skip entity/relationship extraction, requires chunks.json/warehouse.txt)",
    )
    parser.add_argument(
        "--timeline-prefer-batched",
        action="store_true",
        help="For timeline, force batched mode even when warehouse fits (default: single_call when fits)",
    )
    return parser.parse_args()


def validate_project(project_path: Path) -> Path:
    """Validate project directory and warehouse.txt, return warehouse path or exit."""
    print(f"[Sherlock] Starting processing pipeline")
    print(f"[Sherlock] Project: {project_path.name}")

    # 1. Project directory exists
    if not project_path.exists():
        print(f"[Sherlock] ERROR: Project directory does not exist: {project_path}", file=sys.stderr)
        sys.exit(1)
    if not project_path.is_dir():
        print(f"[Sherlock] ERROR: Project path is not a directory: {project_path}", file=sys.stderr)
        sys.exit(1)

    warehouse_path = project_path / "warehouse.txt"

    # 2. warehouse.txt exists
    if not warehouse_path.exists():
        print(f"[Sherlock] ERROR: warehouse.txt not found at {warehouse_path}", file=sys.stderr)
        print(f"[Sherlock]        Expected: {warehouse_path}", file=sys.stderr)
        sys.exit(1)

    # 3. warehouse.txt is readable (and is file)
    if not warehouse_path.is_file():
        print(f"[Sherlock] ERROR: warehouse.txt is not a file: {warehouse_path}", file=sys.stderr)
        sys.exit(1)

    # 4. File is not empty
    try:
        if warehouse_path.stat().st_size == 0:
            print(f"[Sherlock] ERROR: warehouse.txt is empty: {warehouse_path}", file=sys.stderr)
            sys.exit(1)
    except OSError as e:
        print(f"[Sherlock] ERROR: Cannot stat warehouse.txt: {e}", file=sys.stderr)
        sys.exit(1)

    return warehouse_path


def main() -> None:
    args = parse_args()
    project_path = Path(args.project).expanduser().resolve()
    warehouse_path = validate_project(project_path)

    # Resolve LLM config early (llm.json in case folder > env vars)
    # This also gives provider/model/context_window for token-window decision
    try:
        from engine.llm import load_llm_config, resolve_llm_config_path

        _overrides: dict = {}
        if args.model is not None:
            _overrides["model"] = args.model
        if args.context_window is not None:
            _overrides["context_window"] = args.context_window
        llm_cfg = load_llm_config(project_path, overrides=_overrides or None)
        llm_src = resolve_llm_config_path(project_path)
        print(f"[Sherlock] LLM config: provider={llm_cfg.provider} model={llm_cfg.model} base_url={llm_cfg.base_url} (source: {llm_src or 'env vars'})")
        effective_window = args.context_window if args.context_window is not None else llm_cfg.context_window
        effective_model = args.model or llm_cfg.model
    except Exception as e:
        print(f"[Sherlock] WARNING: failed to load LLM config: {e}", file=sys.stderr)
        logger.warning("LLM config load failed", exc_info=True)
        from engine.llm import DEFAULT_CONTEXT_WINDOW_TOKENS, DEFAULT_MODEL

        llm_cfg = None  # type: ignore
        effective_window = args.context_window if args.context_window is not None else DEFAULT_CONTEXT_WINDOW_TOKENS
        effective_model = args.model or DEFAULT_MODEL

    # Read warehouse.txt
    print(f"[Sherlock] Reading warehouse.txt")
    try:
        text = warehouse_path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        # Try with fallback encoding
        try:
            text = warehouse_path.read_text(encoding="utf-8", errors="replace")
            print(f"[Sherlock] WARNING: warehouse.txt had encoding issues, used replacement")
        except Exception as e:
            print(f"[Sherlock] ERROR: Failed to read warehouse.txt: {e}", file=sys.stderr)
            sys.exit(1)
    except OSError as e:
        print(f"[Sherlock] ERROR: Failed to read warehouse.txt: {e}", file=sys.stderr)
        sys.exit(1)
    except Exception as e:
        print(f"[Sherlock] ERROR: Unexpected error reading warehouse.txt: {e}", file=sys.stderr)
        sys.exit(1)

    if not text.strip():
        print(f"[Sherlock] ERROR: warehouse.txt is empty or whitespace only", file=sys.stderr)
        sys.exit(1)

    print(f"[Sherlock] Warehouse loaded successfully ({len(text)} chars, {len(text.splitlines())} lines)")

    # ------------------------------------------------------------------
    # Token-window check: decide warehouse vs chunked LLM strategy
    # Spec: "after we got warehouse.txt we will check how many words are there
    #        if is it is under the context window of llm (say 500k tokens)
    #        we will chunk it else give whole warehouse to llm"
    # Implementation: heuristic token estimate + decision logging.
    # Default behaviour (prefer_batched=True) matches spec: under -> batched (20/batch with dedup)
    # Use --single-call to instead do whole-warehouse single call when it fits.
    # ------------------------------------------------------------------
    try:
        from engine.llm import decide_strategy, get_token_stats

        stats = get_token_stats(text)
        pre_decision = decide_strategy(
            text, chunks=None, context_window=effective_window, prefer_batched_when_fits=not args.single_call
        )
        print(f"[Sherlock] Token check: {stats['words']} words, ~{stats['estimated_tokens']} tokens (window {effective_window})")
        print(f"[Sherlock] Fits in context: {pre_decision['fits_in_context']} | Planned strategy: {pre_decision['strategy']}")
        print(f"[Sherlock] Reason: {pre_decision['reason']}")
        if stats["estimated_tokens"] > effective_window:
            print(f"[Sherlock] WARNING: warehouse exceeds LLM context window — MUST use batched chunking (batch_size={args.batch_size})")
        else:
            if args.single_call:
                print(f"[Sherlock] Will use SINGLE-CALL mode (whole warehouse to LLM) since it fits")
            else:
                print(f"[Sherlock] Will use BATCHED mode ({args.batch_size} chunks/batch) with previous-batch dedup context")
    except Exception as e:
        print(f"[Sherlock] WARNING: token check failed: {e}", file=sys.stderr)
        logger.warning("Token check exception", exc_info=True)

    # Semantic chunking
    print("[Sherlock] STAGE: Chunking - Starting semantic chunking...")
    try:
        chunks = semantic_chunk_text(
            text=text,
            source_file="warehouse.txt",
            chunk_size=args.chunk_size,
            overlap=args.overlap,
        )
    except Exception as e:
        print(f"[Sherlock] ERROR: Chunking failed: {e}", file=sys.stderr)
        logger.exception("Chunking exception")
        sys.exit(1)

    if not chunks:
        print(f"[Sherlock] ERROR: No chunks were produced (empty or invalid input)", file=sys.stderr)
        sys.exit(1)

    print(f"[Sherlock] Created {len(chunks)} chunks")

    # Save chunks.json
    processed_dir = project_path / "processed"
    try:
        processed_dir.mkdir(parents=True, exist_ok=True)
    except OSError as e:
        print(f"[Sherlock] ERROR: Failed to create processed directory {processed_dir}: {e}", file=sys.stderr)
        sys.exit(1)

    output_path = processed_dir / "chunks.json"
    print(f"[Sherlock] Saving chunks.json")

    output_data = {
        "project": project_path.name,
        "project_path": str(project_path),
        "source_file": "warehouse.txt",
        "warehouse_chars": len(text),
        "warehouse_lines": len(text.splitlines()),
        "chunk_config": {
            "chunk_size": args.chunk_size,
            "overlap": args.overlap,
            "unit": "characters",
        },
        "total_chunks": len(chunks),
        "chunks": chunks,
    }

    try:
        with output_path.open("w", encoding="utf-8") as f:
            json.dump(output_data, f, indent=2, ensure_ascii=False)
    except OSError as e:
        print(f"[Sherlock] ERROR: Failed to write chunks.json: {e}", file=sys.stderr)
        sys.exit(1)

    print(f"[Sherlock] Saved to {output_path}")

    # Re-log decision with actual chunk count for accurate batches_needed
    try:
        from engine.llm import decide_strategy as _decide2

        final_decision = _decide2(
            text, chunks=chunks, context_window=effective_window, prefer_batched_when_fits=not args.single_call, batch_size=args.batch_size
        )
        print(f"[Sherlock] Final decision after chunking: strategy={final_decision['strategy']} | batches_needed={final_decision['batches_needed']} | total_chunks={len(chunks)}")
    except Exception:
        pass

    # Optional: LLM entity/relationship extraction
    if args.extract or args.timeline_only:
        # If --timeline-only, skip entities/relationships but run timeline
        if args.timeline_only:
            print(f"[Sherlock] --timeline-only enabled — starting ONLY timeline extraction (batch_size={args.batch_size}, model={effective_model}, window={effective_window})")
            try:
                from engine.timeline import run_timeline_pipeline

                t_result = run_timeline_pipeline(
                    project_path=project_path,
                    batch_size=args.batch_size,
                    context_window=effective_window,
                    model=effective_model,
                    llm_config=llm_cfg,
                    prefer_batched_when_fits=args.timeline_prefer_batched,
                    verbose=True,
                )
                print(f"[Sherlock] Timeline done: {len(t_result['timeline'])} events sorted chronologically → {t_result['paths']['timeline_json']}")
            except Exception as e:
                print(f"[Sherlock] ERROR: Timeline extraction failed: {e}", file=sys.stderr)
                logger.exception("Timeline extraction failed")
                print(f"[Sherlock] Chunking succeeded; timeline error is non-fatal. Check processed/ for partial results.", file=sys.stderr)
        else:
            print(f"[Sherlock] --extract enabled — starting LLM extraction (batch_size={args.batch_size}, model={effective_model}, window={effective_window})")
            try:
                from engine.entity_extraction import run_extraction_pipeline

                result = run_extraction_pipeline(
                    project_path=project_path,
                    batch_size=args.batch_size,
                    context_window=effective_window,
                    model=effective_model,
                    llm_config=llm_cfg,
                    prefer_batched_when_fits=not args.single_call,
                    extract_relationships=not args.no_relationships,
                    extract_timeline=args.timeline,
                    timeline_prefer_batched=args.timeline_prefer_batched,
                    verbose=True,
                )
                print(f"[Sherlock] Extraction done: {len(result['entities'])} entities, {len(result['relationships'])} relationships")
                if args.timeline:
                    tlen = len(result.get("timeline", []))
                    print(f"[Sherlock] Timeline done: {tlen} events sorted chronologically")
                    if tlen and result.get("timeline_paths"):
                        print(f"[Sherlock] Timeline → {result['timeline_paths'].get('timeline_json')}")
            except Exception as e:
                print(f"[Sherlock] ERROR: LLM extraction failed: {e}", file=sys.stderr)
                logger.exception("Extraction failed")
                # Don't exit 1 for chunking success — extraction is stage 2
                print(f"[Sherlock] Chunking succeeded; extraction error is non-fatal. Check processed/ for partial results.", file=sys.stderr)
    elif args.timeline:
        # --timeline without --extract: run just timeline after chunking
        print(f"[Sherlock] --timeline enabled — starting timeline extraction (batch_size={args.batch_size}, model={effective_model}, window={effective_window})")
        try:
            from engine.timeline import run_timeline_pipeline

            t_result = run_timeline_pipeline(
                project_path=project_path,
                batch_size=args.batch_size,
                context_window=effective_window,
                model=effective_model,
                llm_config=llm_cfg,
                prefer_batched_when_fits=args.timeline_prefer_batched,
                verbose=True,
            )
            print(f"[Sherlock] Timeline done: {len(t_result['timeline'])} events sorted chronologically → {t_result['paths']['timeline_json']}")
        except Exception as e:
            print(f"[Sherlock] ERROR: Timeline extraction failed: {e}", file=sys.stderr)
            logger.exception("Timeline extraction failed")
            print(f"[Sherlock] Chunking succeeded; timeline error is non-fatal.", file=sys.stderr)

    print(f"[Sherlock] Processing completed successfully")


if __name__ == "__main__":
    main()
