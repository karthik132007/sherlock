"""
engine/chunk.py — Semantic chunking for Sherlock.

Responsibility: ONLY chunking. No entity extraction, no graph building.

Implements a lightweight semantic chunking strategy:
  1. Split text into sentences (regex, no heavy NLP dependency).
  2. Compute TF-IDF embeddings per sentence (scikit-learn).
  3. Compute cosine similarity between adjacent sentences.
  4. Determine semantic breakpoints via percentile threshold.
  5. Group sentences into chunks respecting:
     - semantic breakpoints
     - configurable chunk size (characters)
     - configurable overlap (characters)
     - configurable breakpoint percentile & min chunk size

Fallbacks:
  - If scikit-learn is unavailable → falls back to sentence-grouping by size
    (still preserves sentence boundaries, not naive N-char split).
  - If sentence-transformers is available, optionally uses it (higher quality).

Config defaults are documented and match task spec:
  DEFAULT_CHUNK_SIZE = 1200 chars
  DEFAULT_OVERLAP    = 200 chars
"""

from __future__ import annotations

import logging
import re
from typing import Any, Dict, List, Optional, Tuple

# ---------------------------------------------------------------------------
# Defaults (character-based, documented)
# ---------------------------------------------------------------------------

DEFAULT_CHUNK_SIZE: int = 1200  # characters per chunk (approx 300 tokens)
DEFAULT_OVERLAP: int = 200  # characters of overlap between consecutive chunks
DEFAULT_BREAKPOINT_PERCENTILE: int = 65  # percentile for semantic breakpoint threshold
DEFAULT_MIN_CHUNK_SIZE: int = 300  # minimum chars before allowing a semantic split

# Warehouse marker regex (AGENTS.md format)
_WAREHOUSE_SOURCE_RE = re.compile(
    r"=+\s*\nSOURCE_FILE:\s*(?P<file>.+?)\s*\nSOURCE_TYPE:.*?\n=+\s*\n(?P<content>.*?)\n=+\s*\nEND_SOURCE:.*?\n=+\s*",
    re.DOTALL,
)

# Sentence split regex — splits on .!? followed by whitespace + capital/number
_SENTENCE_SPLIT_RE = re.compile(r"(?<=[.!?])\s+(?=[A-Z0-9\"'\(])")

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

def semantic_chunk_text(
    text: str,
    source_file: str = "warehouse.txt",
    chunk_size: int = DEFAULT_CHUNK_SIZE,
    overlap: int = DEFAULT_OVERLAP,
    breakpoint_percentile: int = DEFAULT_BREAKPOINT_PERCENTILE,
    min_chunk_size: int = DEFAULT_MIN_CHUNK_SIZE,
) -> List[Dict[str, Any]]:
    """
    Semantic chunking entry point called by main.py.

    Args:
        text: Full warehouse text (or any source text).
        source_file: Logical source name (e.g. "warehouse.txt").
                     If text contains AGENTS.md warehouse markers,
                     per-source provenance is extracted and overrides this.
        chunk_size: Maximum chunk size in characters.
        overlap: Overlap in characters between consecutive chunks.
        breakpoint_percentile: Percentile (0-100) for similarity threshold.
        min_chunk_size: Minimum chunk size before a semantic breakpoint is honoured.

    Returns:
        List of chunk dicts with keys:
          chunk_id, chunk_index, source_file, text, metadata{char_start, char_end}
    """
    if not text or not text.strip():
        logger.warning("semantic_chunk_text received empty text")
        return []

    # Detect warehouse format — if found, chunk per-source to preserve provenance
    sources = _parse_warehouse_sources(text)
    if sources:
        logger.info("Detected warehouse format with %d sources", len(sources))
        return _chunk_warehouse_sources(
            sources=sources,
            chunk_size=chunk_size,
            overlap=overlap,
            breakpoint_percentile=breakpoint_percentile,
            min_chunk_size=min_chunk_size,
        )

    # Single source path
    chunks = _semantic_chunk_single_text(
        text=text,
        chunk_size=chunk_size,
        overlap=overlap,
        breakpoint_percentile=breakpoint_percentile,
        min_chunk_size=min_chunk_size,
    )
    return _build_chunk_objects(
        chunk_texts_with_offsets=chunks,
        source_file=source_file,
        start_index=1,
        id_prefix=_safe_prefix(source_file),
    )


# ---------------------------------------------------------------------------
# Warehouse parsing
# ---------------------------------------------------------------------------

def _parse_warehouse_sources(text: str) -> List[Dict[str, Any]]:
    """
    Parse warehouse.txt source boundaries per AGENTS.md.

    Returns list of dicts: {source_file, content, warehouse_char_start, warehouse_char_end}
    If no markers found, returns empty list.
    """
    sources: List[Dict[str, Any]] = []
    for m in _WAREHOUSE_SOURCE_RE.finditer(text):
        fname = m.group("file").strip()
        content = m.group("content")
        # content offsets within the overall warehouse text
        # group "content" start/end inside warehouse
        c_start = m.start("content")
        c_end = m.end("content")
        sources.append(
            {
                "source_file": fname,
                "content": content,
                "warehouse_char_start": c_start,
                "warehouse_char_end": c_end,
            }
        )
    return sources


def _chunk_warehouse_sources(
    sources: List[Dict[str, Any]],
    chunk_size: int,
    overlap: int,
    breakpoint_percentile: int,
    min_chunk_size: int,
) -> List[Dict[str, Any]]:
    all_chunks: List[Dict[str, Any]] = []
    global_index = 1
    for src in sources:
        content = src["content"]
        # Skip empty sources
        if not content or not content.strip():
            logger.warning("Skipping empty source %s", src["source_file"])
            continue
        chunk_texts = _semantic_chunk_single_text(
            text=content,
            chunk_size=chunk_size,
            overlap=overlap,
            breakpoint_percentile=breakpoint_percentile,
            min_chunk_size=min_chunk_size,
        )
        objs = _build_chunk_objects(
            chunk_texts_with_offsets=chunk_texts,
            source_file=src["source_file"],
            start_index=global_index,
            id_prefix=_safe_prefix(src["source_file"]),
            warehouse_offset=src["warehouse_char_start"],
        )
        all_chunks.extend(objs)
        global_index += len(objs)

    # If no chunks produced but sources existed (all empty), fallback
    if not all_chunks:
        logger.warning("Warehouse had sources but no chunks produced")

    return all_chunks


# ---------------------------------------------------------------------------
# Core semantic chunking for a single text block
# ---------------------------------------------------------------------------

def _semantic_chunk_single_text(
    text: str,
    chunk_size: int,
    overlap: int,
    breakpoint_percentile: int,
    min_chunk_size: int,
) -> List[Tuple[str, int, int]]:
    """
    Returns list of (chunk_text, char_start, char_end) where char_start/end
    are offsets within the input text (before overlap injection).
    Overlap is applied afterwards but offsets are adjusted to be within original text.
    """
    text = text.strip()
    if not text:
        return []

    # Fast path: short text fits in one chunk
    if len(text) <= chunk_size:
        # Still attempt semantic check: if very short, single chunk is fine
        sentences = _split_into_sentences(text)
        if len(sentences) <= 3:
            return [(text, 0, len(text))]

    sentences = _split_into_sentences(text)
    if not sentences:
        # Fallback to naive if sentence split fails
        return _naive_char_chunks(text, chunk_size, overlap)

    # If only one sentence but longer than chunk_size, naive split
    if len(sentences) == 1 and len(text) > chunk_size:
        return _naive_char_chunks(text, chunk_size, overlap)

    # Compute semantic similarities if possible
    similarities = _compute_similarities(sentences)

    # Determine threshold
    threshold: Optional[float] = None
    if similarities:
        try:
            import numpy as np  # already a dependency

            threshold = float(np.percentile(similarities, breakpoint_percentile))
            logger.debug(
                "Similarity threshold p%d = %.4f (n=%d)",
                breakpoint_percentile,
                threshold,
                len(similarities),
            )
        except Exception as e:
            logger.warning("Failed to compute percentile threshold: %s", e)
            threshold = None

    # Group sentences into chunks respecting semantics and size
    raw_chunks = _group_sentences(
        sentences=sentences,
        similarities=similarities,
        threshold=threshold,
        chunk_size=chunk_size,
        min_chunk_size=min_chunk_size,
        original_text=text,
    )

    # raw_chunks is list of (chunk_text, start, end) without overlap
    if not raw_chunks:
        return [(text, 0, len(text))]

    # Apply overlap
    if overlap > 0 and len(raw_chunks) > 1:
        raw_chunks = _apply_overlap(raw_chunks, overlap)

    return raw_chunks


def _split_into_sentences(text: str) -> List[str]:
    """
    Lightweight sentence splitter. Handles paragraphs and sentence boundaries.
    Keeps sentence text trimmed but non-empty.
    """
    # Normalize whitespace but preserve paragraph breaks for now
    # First split into paragraphs (double newline) to avoid merging unrelated sections
    paragraphs = re.split(r"\n\s*\n", text.strip())
    sentences: List[str] = []
    for para in paragraphs:
        # Collapse internal newlines to spaces for sentence detection, but keep them? Use space.
        para_norm = re.sub(r"\s+", " ", para.strip())
        if not para_norm:
            continue
        # If paragraph is very short or looks like a header/list item, keep as one sentence unit
        if len(para_norm) < 60 or para_norm.count(".") == 0:
            sentences.append(para_norm)
            continue
        parts = _SENTENCE_SPLIT_RE.split(para_norm)
        for p in parts:
            p = p.strip()
            if p:
                sentences.append(p)
    # Post-filter: merge very short fragments that are likely not sentences
    merged: List[str] = []
    for s in sentences:
        if merged and len(s) < 30 and not s.endswith((".", "!", "?", '"', "'")):
            merged[-1] = merged[-1] + " " + s
        else:
            merged.append(s)
    return merged


def _compute_similarities(sentences: List[str]) -> List[float]:
    """
    Compute cosine similarity between adjacent sentence TF-IDF vectors.
    Returns list length len(sentences)-1.
    Falls back to empty list if sklearn unavailable.
    """
    if len(sentences) < 2:
        return []
    try:
        from sklearn.feature_extraction.text import TfidfVectorizer
        from sklearn.metrics.pairwise import cosine_similarity

        # Use TF-IDF with English stop words, handle very short sentences
        vectorizer = TfidfVectorizer(
            stop_words="english",
            ngram_range=(1, 2),
            min_df=1,
            max_df=0.95,
        )
        # Filter out sentences that are too short to vectorize meaningfully?
        # Keep all; Tfidf will handle.
        tfidf = vectorizer.fit_transform(sentences)
        # If vocabulary empty (e.g., all stop words), fallback
        if tfidf.shape[1] == 0:
            logger.warning("TF-IDF vocabulary empty, skipping semantic similarity")
            return []
        sims: List[float] = []
        for i in range(len(sentences) - 1):
            vec_a = tfidf[i]
            vec_b = tfidf[i + 1]
            # cosine_similarity expects 2D
            sim = float(cosine_similarity(vec_a, vec_b)[0][0])
            # Clamp - just in case
            sim = max(0.0, min(1.0, sim))
            sims.append(sim)
        return sims
    except ImportError as e:
        logger.warning("scikit-learn not available, using non-semantic grouping: %s", e)
        return []
    except Exception as e:
        logger.warning("Failed to compute similarities: %s", e)
        return []


def _group_sentences(
    sentences: List[str],
    similarities: List[float],
    threshold: Optional[float],
    chunk_size: int,
    min_chunk_size: int,
    original_text: str,
) -> List[Tuple[str, int, int]]:
    """
    Group sentences greedily, respecting chunk_size and semantic breakpoints.

    Returns list of (chunk_text, char_start, char_end) where start/end are
    offsets approximated in original_text (normalized). Offsets are
    monotonic and consistent with char_length, not strict byte offsets.
    """
    chunks: List[Tuple[str, int, int]] = []
    current: List[str] = []
    current_len = 0
    # Track approximate normalized offset for correct char_start/end
    # This is the offset in the normalized " ".join(sentences) space
    normalized_offset = 0
    total_normalized_len = sum(len(s) for s in sentences) + max(0, len(sentences) - 1)

    def flush_current():
        nonlocal current, current_len, normalized_offset
        if not current:
            return
        chunk_text = " ".join(current)
        start = normalized_offset
        end = start + len(chunk_text)
        # Clamp to original_text length for sanity (original may have newlines)
        # Use min to avoid exceeding original
        # normalized text is approximately same length as original stripped version
        end = min(end, len(original_text))
        chunks.append((chunk_text, start, end))
        normalized_offset = end
        current = []
        current_len = 0

    for idx, sent in enumerate(sentences):
        sent_len = len(sent) + (1 if current else 0)  # +1 for space

        # Check if adding this sentence would exceed chunk_size
        would_exceed = current_len + sent_len > chunk_size

        if would_exceed and current:
            # Force split before adding current sentence
            flush_current()

        current.append(sent)
        current_len += sent_len

        # Check semantic breakpoint after this sentence (between idx and idx+1)
        if idx < len(similarities) and threshold is not None:
            sim = similarities[idx]
            is_break = sim < threshold
            # Only honour breakpoint if current chunk is sufficiently large
            # and next sentence would not make tiny chunk
            if is_break and current_len >= min_chunk_size:
                # Peek next sentence length: if remaining text would be too small, avoid split?
                # Simple: split now, next chunk starts fresh
                flush_current()
            elif current_len >= chunk_size:
                # Hit size limit
                flush_current()
        elif current_len >= chunk_size:
            flush_current()

    if current:
        flush_current()

    return chunks


def _apply_overlap(
    chunks: List[Tuple[str, int, int]], overlap: int
) -> List[Tuple[str, int, int]]:
    """
    Inject overlap characters from previous chunk's tail into next chunk's head.
    Adjusts char_start for overlapped chunks (start moves back by overlap chars).
    """
    if overlap <= 0 or len(chunks) <= 1:
        return chunks
    overlapped: List[Tuple[str, int, int]] = [chunks[0]]
    for i in range(1, len(chunks)):
        # Always take the tail of the ORIGINAL previous chunk. Using the already-
        # overlapped chunk would cascade the overlap (text leaking across two chunks).
        prev_text, _, _ = chunks[i - 1]
        # Take tail of previous chunk up to overlap chars, try to break on word boundary
        if len(prev_text) <= overlap:
            overlap_text = prev_text
        else:
            tail = prev_text[-overlap:]
            # Avoid cutting mid-word: find first space
            space_idx = tail.find(" ")
            if space_idx != -1 and space_idx < len(tail) * 0.5:
                overlap_text = tail[space_idx + 1 :]
            else:
                overlap_text = tail

        curr_text, curr_start, curr_end = chunks[i]
        # Prepend overlap
        new_text = overlap_text.strip() + " " + curr_text
        # Adjust start backwards (best-effort)
        new_start = max(0, curr_start - len(overlap_text) - 1)
        overlapped.append((new_text, new_start, curr_end))
    return overlapped


def _naive_char_chunks(text: str, chunk_size: int, overlap: int) -> List[Tuple[str, int, int]]:
    """Fallback when sentence logic fails: split on character windows with overlap."""
    chunks: List[Tuple[str, int, int]] = []
    start = 0
    n = len(text)
    while start < n:
        end = min(start + chunk_size, n)
        chunk_text = text[start:end]
        chunks.append((chunk_text, start, end))
        if end >= n:
            break
        start = end - overlap if overlap < chunk_size else end
        if start < 0:
            start = 0
    return chunks


def _build_chunk_objects(
    chunk_texts_with_offsets: List[Tuple[str, int, int]],
    source_file: str,
    start_index: int = 1,
    id_prefix: str = "warehouse",
    warehouse_offset: int = 0,
) -> List[Dict[str, Any]]:
    result: List[Dict[str, Any]] = []
    for idx, (c_text, c_start, c_end) in enumerate(chunk_texts_with_offsets):
        chunk_index = start_index + idx
        chunk_id = f"{id_prefix}_chunk_{chunk_index:03d}"
        # For warehouse sources, optionally include absolute warehouse offsets
        abs_start = warehouse_offset + c_start if warehouse_offset else c_start
        abs_end = warehouse_offset + c_end if warehouse_offset else c_end
        result.append(
            {
                "chunk_id": chunk_id,
                "chunk_index": chunk_index,
                "source_file": source_file,
                "text": c_text,
                "metadata": {
                    "char_start": c_start,
                    "char_end": c_end,
                    "warehouse_char_start": abs_start,
                    "warehouse_char_end": abs_end,
                    "char_length": len(c_text),
                },
            }
        )
    return result


def _safe_prefix(source_file: str) -> str:
    """Create a safe id prefix from source file name."""
    name = source_file.rsplit("/", 1)[-1]
    name = name.rsplit("\\", 1)[-1]
    if "." in name:
        name = name.rsplit(".", 1)[0]
    name = re.sub(r"[^a-zA-Z0-9]+", "_", name).strip("_").lower()
    return name or "warehouse"
