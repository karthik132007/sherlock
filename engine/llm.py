"""
engine/llm.py — LLM call wrapper + token-window & batching logic for Sherlock.

Responsibilities:
  1. Token estimation & context-window decision (warehouse vs chunked)
  2. Batch creation (default 20 chunks per LLM call)
  3. Multi-provider LLM client (llm.json per case folder)
  4. Batched entity/relationship extraction with deduplication

LLM config source (priority order):
  1. <case_folder>/llm.json  — created by UI, contains provider/model/api_key
  2. Env vars — fallback for CLI/testing (LLM_API, OPENAI_API_KEY, etc.)
  3. Explicit overrides passed to functions

Supported providers (all OpenAI-compatible via `openai.OpenAI` SDK):
  openai, openrouter, deepseek, groq, together, mistral, ollama, openai-compatible (custom base_url)
  - Anthropic native is NOT OpenAI-compatible; if requested, we auto-map to OpenRouter-style
    OpenAI SDK with anthropic base_url if supplied, else raise helpful error.

llm.json schema (flexible keys):
  {
    "provider": "openai" | "openrouter" | "deepseek" | "groq" | "together" | "mistral" | "ollama" | "custom",
    "model": "gpt-4o-mini",
    "api_key": "sk-...",
    "base_url": "https://api.openai.com/v1",          // optional, overrides provider default
    "context_window": 128000,                          // optional, else default 500k / model default
    "temperature": 0.1
  }
  Also accepts camelCase / alternate keys: provider_name, model_name, apiKey, key, token, baseUrl, api_base

Usage:
  from engine.llm import load_llm_config, estimate_tokens, decide_strategy, extract_entities_auto
  cfg = load_llm_config("/path/to/case")
  ents, decision = extract_entities_auto(warehouse_text, chunks, llm_config=cfg)
"""

from __future__ import annotations

import json
import logging
import os
import re
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

# Load .env if present (fallback when llm.json not present)
try:
    from dotenv import load_dotenv  # type: ignore

    _env_path = Path(__file__).resolve().parent.parent / ".env"
    if _env_path.exists():
        load_dotenv(_env_path)
    else:
        load_dotenv()
except Exception:
    pass

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------

DEFAULT_CONTEXT_WINDOW_TOKENS: int = 500_000
DEFAULT_BATCH_SIZE: int = 20
DEFAULT_MODEL: str = os.getenv("LLM_MODEL", "gpt-4o-mini")
DEFAULT_TEMPERATURE: float = 0.1
DEFAULT_MAX_RETRIES: int = 3

CHARS_PER_TOKEN: float = 4.0
WORDS_TO_TOKENS: float = 1.33

# ---------------------------------------------------------------------------
# Provider registry — base URLs for known OpenAI-compatible providers
# ---------------------------------------------------------------------------

PROVIDER_REGISTRY: Dict[str, Dict[str, Any]] = {
    "openai": {
        "base_url": "https://api.openai.com/v1",
        "env_keys": ["OPENAI_API_KEY", "LLM_API", "LLM_API_KEY", "OPENAI_KEY"],
        "default_model": "gpt-4o-mini",
        "context_window": 128000,
    },
    "openrouter": {
        "base_url": "https://openrouter.ai/api/v1",
        "env_keys": ["OPENROUTER_API_KEY", "OPENROUTER_KEY", "LLM_API"],
        "default_model": "openai/gpt-4o-mini",
        "context_window": 200000,
        "extra_headers": {"HTTP-Referer": "https://sherlock.local", "X-Title": "Sherlock Investigation"},
    },
    "deepseek": {
        "base_url": "https://api.deepseek.com",
        "env_keys": ["DEEPSEEK_API_KEY", "DEEPSEEK_KEY", "LLM_API"],
        "default_model": "deepseek-chat",
        "context_window": 64000,
    },
    "groq": {
        "base_url": "https://api.groq.com/openai/v1",
        "env_keys": ["GROQ_API_KEY", "LLM_API"],
        "default_model": "llama-3.3-70b-versatile",
        "context_window": 128000,
    },
    "together": {
        "base_url": "https://api.together.xyz/v1",
        "env_keys": ["TOGETHER_API_KEY", "LLM_API"],
        "default_model": "meta-llama/Meta-Llama-3.1-70B-Instruct-Turbo",
        "context_window": 128000,
    },
    "mistral": {
        "base_url": "https://api.mistral.ai/v1",
        "env_keys": ["MISTRAL_API_KEY", "LLM_API"],
        "default_model": "mistral-large-latest",
        "context_window": 128000,
    },
    "ollama": {
        "base_url": "http://127.0.0.1:11434/v1",
        "env_keys": ["OLLAMA_API_KEY", "LLM_API"],
        "default_model": "llama3.1",
        "context_window": 128000,
        "api_key_optional": True,  # ollama local doesn't need key
    },
    "azure": {
        "base_url": None,  # must be supplied
        "env_keys": ["AZURE_OPENAI_API_KEY", "LLM_API"],
        "default_model": "gpt-4o-mini",
        "context_window": 128000,
    },
    "custom": {
        "base_url": None,
        "env_keys": ["LLM_API", "CUSTOM_API_KEY"],
        "default_model": "gpt-4o-mini",
        "context_window": 500000,
    },
    # Aliases
    "openai-compatible": {
        "base_url": None,
        "env_keys": ["LLM_API", "OPENAI_API_KEY"],
        "default_model": "gpt-4o-mini",
        "context_window": 500000,
    },
}

# Map common UI aliases to canonical provider keys
_PROVIDER_ALIASES: Dict[str, str] = {
    "chatgpt": "openai",
    "gpt": "openai",
    "openai-chatgpt": "openai",
    "oai": "openai",
    "open-router": "openrouter",
    "open_router": "openrouter",
    "deepseek-ai": "deepseek",
    "deepseek_ai": "deepseek",
    "together-ai": "together",
    "together_ai": "together",
    "local": "ollama",
    "ollama-local": "ollama",
}


def _normalize_provider(raw: str) -> str:
    """Normalize provider string to canonical registry key."""
    if not raw:
        return "openai"
    key = raw.strip().lower().replace(" ", "").replace("-", "").replace("_", "")
    # Direct hit
    for canonical in PROVIDER_REGISTRY:
        if key == canonical.replace("-", "").replace("_", ""):
            return canonical
    # Alias map (normalized)
    alias_norm = {k.replace("-", "").replace("_", "").lower(): v for k, v in _PROVIDER_ALIASES.items()}
    if key in alias_norm:
        return alias_norm[key]
    # Also try original alias dict without normalization
    low = raw.strip().lower()
    if low in _PROVIDER_ALIASES:
        return _PROVIDER_ALIASES[low]
    # Unknown — treat as custom (user supplied base_url)
    logger.warning("Unknown provider '%s' — treating as custom (requires base_url)", raw)
    return "custom"


# ---------------------------------------------------------------------------
# LLM config dataclass + loading
# ---------------------------------------------------------------------------

@dataclass
class LLMConfig:
    provider: str = "openai"
    model: str = DEFAULT_MODEL
    api_key: Optional[str] = None
    base_url: Optional[str] = None
    context_window: int = DEFAULT_CONTEXT_WINDOW_TOKENS
    temperature: float = DEFAULT_TEMPERATURE
    extra_headers: Dict[str, str] = field(default_factory=dict)
    extra_body: Dict[str, Any] = field(default_factory=dict)
    raw: Dict[str, Any] = field(default_factory=dict)  # original json for debugging

    def masked(self) -> Dict[str, Any]:
        """Return dict for logging with api_key masked."""
        d = {
            "provider": self.provider,
            "model": self.model,
            "base_url": self.base_url,
            "context_window": self.context_window,
            "temperature": self.temperature,
            "api_key": (self.api_key[:6] + "..." + self.api_key[-4:] if self.api_key and len(self.api_key) > 10 else "***") if self.api_key else None,
        }
        if self.extra_headers:
            d["extra_headers"] = list(self.extra_headers.keys())
        return d


def _get_nested(d: Dict[str, Any], *keys: str, default=None):
    """Fetch first matching key from dict (case-insensitive, camel/snake)."""
    if not isinstance(d, dict):
        return default
    # Build lower map
    lower_map = {k.lower(): v for k, v in d.items()}
    # Also map snake->camel variations: api_key vs apiKey
    for key in keys:
        # direct
        if key in d:
            return d[key]
        # lower
        if key.lower() in lower_map:
            return lower_map[key.lower()]
        # camel variations: api_key -> apiKey
        camel = "".join(part.capitalize() if i else part for i, part in enumerate(key.split("_")))
        # Actually need lowerCamel: apiKey
        if "_" in key:
            camel2 = key.split("_")[0].lower() + "".join(p.capitalize() for p in key.split("_")[1:])
            if camel2 in d:
                return d[camel2]
            if camel2.lower() in lower_map:
                return lower_map[camel2.lower()]
    return default


def load_llm_config(
    project_path: Optional[str | Path] = None,
    overrides: Optional[Dict[str, Any]] = None,
) -> LLMConfig:
    """
    Load LLM config from <case_folder>/llm.json with fallback to env vars.
    If project_path is None, only env vars are used.

    Args:
        project_path: case folder containing llm.json
        overrides: dict of explicit overrides (e.g. CLI --model)

    Returns:
        LLMConfig

    Raises:
        FileNotFoundError is NOT raised if llm.json missing — falls back to env.
        ValueError if provider requires api_key but none found.
    """
    raw: Dict[str, Any] = {}
    llm_json_path: Optional[Path] = None

    # 1. Try to locate llm.json under project_path
    if project_path is not None:
        project_path = Path(project_path).expanduser().resolve()
        candidates = [
            project_path / "llm.json",
            project_path / "processed" / "llm.json",  # alt
            project_path.parent / "llm.json",  # case may be subfolder
        ]
        for cand in candidates:
            if cand.exists() and cand.is_file():
                llm_json_path = cand
                try:
                    raw = json.loads(cand.read_text(encoding="utf-8"))
                    if not isinstance(raw, dict):
                        logger.warning("llm.json at %s is not a dict, ignoring", cand)
                        raw = {}
                    else:
                        logger.info("Loaded LLM config from %s", cand)
                    break
                except Exception as e:
                    logger.warning("Failed to parse llm.json at %s: %s", cand, e)
                    raw = {}
        if llm_json_path is None:
            logger.info("No llm.json found under %s — falling back to env vars", project_path)

    # 2. Apply overrides (CLI --model etc. take precedence)
    if overrides:
        # Merge overrides into raw (overrides win)
        for k, v in overrides.items():
            if v is not None:
                raw[k] = v

    # 3. Normalize fields
    provider_raw = _get_nested(raw, "provider", "provider_name", "llm_provider", "api_provider", default="openai")
    provider = _normalize_provider(str(provider_raw)) if provider_raw else "openai"

    registry = PROVIDER_REGISTRY.get(provider, PROVIDER_REGISTRY["custom"])

    model = _get_nested(raw, "model", "model_name", "llm_model", "deployment", "model_id", default=None)
    if not model:
        model = registry.get("default_model", DEFAULT_MODEL)
    else:
        model = str(model).strip()
        # Auto-map deepseek model aliases
        if provider == "deepseek":
            if model.lower() in ("deepseek-v4-flash", "deepseek-v4-pro", "deepseek-v4", "deepseek-v3", "deepseek-flash", "deepseek-pro", "deepseek-chat-v3"):
                model = "deepseek-chat"
            elif model.lower() in ("deepseek-r1", "r1", "reasoner"):
                model = "deepseek-reasoner"

    api_key = _get_nested(raw, "api_key", "apiKey", "apikey", "key", "token", "secret", default=None)
    if not api_key:
        # Try provider-specific env keys, then generic
        for env_key in registry.get("env_keys", []) + ["LLM_API", "LLM_API_KEY", "OPENAI_API_KEY"]:
            val = os.getenv(env_key)
            if val:
                api_key = val
                break

    base_url = _get_nested(raw, "base_url", "baseUrl", "api_base", "baseURL", "endpoint", "api_url", default=None)
    if not base_url:
        base_url = registry.get("base_url")
    elif provider == "ollama" and not base_url.endswith("/v1"):
        base_url = base_url.rstrip("/") + "/v1"


    context_window = _get_nested(raw, "context_window", "contextWindow", "max_tokens", "maxTokens", "window", default=None)
    if context_window is None:
        context_window = registry.get("context_window", DEFAULT_CONTEXT_WINDOW_TOKENS)
    try:
        context_window = int(context_window)
    except Exception:
        context_window = DEFAULT_CONTEXT_WINDOW_TOKENS

    temperature = _get_nested(raw, "temperature", "temp", default=DEFAULT_TEMPERATURE)
    try:
        temperature = float(temperature)
    except Exception:
        temperature = DEFAULT_TEMPERATURE

    extra_headers = _get_nested(raw, "extra_headers", "extraHeaders", "headers", default=None) or {}
    # Merge registry extra_headers (e.g. OpenRouter) with user supplied
    reg_headers = registry.get("extra_headers", {})
    if reg_headers:
        merged_headers = dict(reg_headers)
        merged_headers.update(extra_headers)
        extra_headers = merged_headers

    extra_body = _get_nested(raw, "extra_body", "extraBody", default=None) or {}

    # Validate
    api_key_optional = registry.get("api_key_optional", False)
    if not api_key and not api_key_optional:
        # For ollama local, allow missing; for others warn
        if provider == "ollama":
            api_key = "ollama"  # dummy
        else:
            logger.warning(
                "No API key found for provider '%s' (model %s). "
                "Checked llm.json (%s) and env %s. Calls will fail unless mocked.",
                provider,
                model,
                llm_json_path or "not found",
                registry.get("env_keys"),
            )
            api_key = api_key or "sk-missing-key-for-testing"

    cfg = LLMConfig(
        provider=provider,
        model=str(model),
        api_key=api_key,
        base_url=base_url,
        context_window=context_window,
        temperature=temperature,
        extra_headers=extra_headers,
        extra_body=extra_body,
        raw=raw,
    )
    logger.debug("LLMConfig resolved: %s", cfg.masked())
    return cfg


def resolve_llm_config_path(project_path: str | Path) -> Optional[Path]:
    """Return path to llm.json if found under case folder, else None."""
    project_path = Path(project_path).expanduser().resolve()
    for cand in [project_path / "llm.json", project_path / "processed" / "llm.json"]:
        if cand.exists() and cand.is_file():
            return cand
    return None


# ---------------------------------------------------------------------------
# Token estimation (unchanged)
# ---------------------------------------------------------------------------

def estimate_tokens(text: str) -> int:
    """
    Estimate token count for `text`.
    Tries tiktoken (cl100k_base) if available, otherwise heuristic.
    Heuristic = max(chars/4, words*1.33) — conservative upper bound.
    """
    if not text:
        return 0
    try:
        import tiktoken  # type: ignore

        enc = tiktoken.get_encoding("cl100k_base")
        return len(enc.encode(text))
    except Exception:
        pass
    chars = len(text)
    words = len(text.split())
    est_by_chars = int(chars / CHARS_PER_TOKEN)
    est_by_words = int(words * WORDS_TO_TOKENS)
    return max(est_by_chars, est_by_words, 1)


def get_token_stats(text: str) -> Dict[str, Any]:
    """Return chars/words/tokens stats for logging."""
    return {
        "chars": len(text),
        "words": len(text.split()),
        "lines": len(text.splitlines()),
        "estimated_tokens": estimate_tokens(text),
    }


def decide_strategy(
    warehouse_text: str,
    chunks: Optional[List[Dict[str, Any]]] = None,
    context_window: int = DEFAULT_CONTEXT_WINDOW_TOKENS,
    prefer_batched_when_fits: bool = True,
    batch_size: int = DEFAULT_BATCH_SIZE,
) -> Dict[str, Any]:
    """
    Decide whether to send whole warehouse or chunked batches to LLM.

    Args:
        warehouse_text: full warehouse text
        chunks: list of chunks (used to compute batches_needed)
        context_window: LLM token window
        prefer_batched_when_fits: if True, use batched even when the warehouse fits
        batch_size: chunks per batch used to compute batches_needed
    """
    stats = get_token_stats(warehouse_text)
    est = stats["estimated_tokens"]
    fits = est <= context_window

    if fits:
        if prefer_batched_when_fits:
            strategy = "batched"
            reason = f"Fits in window ({est} <= {context_window}), using batched mode ({batch_size} chunks/batch) with dedup context to avoid alias duplicates."
        else:
            strategy = "single_call"
            reason = f"Fits in window ({est} <= {context_window}), sending whole warehouse in one LLM call (no chunking needed)."
    else:
        strategy = "batched"
        reason = f"Exceeds window ({est} > {context_window}), MUST use batched chunking (single call would overflow). Total chunks={len(chunks) if chunks else 'unknown'}."

    batches_needed = None
    if chunks is not None:
        import math

        batches_needed = math.ceil(len(chunks) / batch_size) if strategy == "batched" else 1

    return {
        **stats,
        "context_window": context_window,
        "fits_in_context": fits,
        "strategy": strategy,
        "batches_needed": batches_needed,
        "reason": reason,
    }


def check_warehouse_file(warehouse_path: str | Path, context_window: int = DEFAULT_CONTEXT_WINDOW_TOKENS) -> Dict[str, Any]:
    """Convenience: read file and return decide_strategy result + path."""
    p = Path(warehouse_path)
    text = p.read_text(encoding="utf-8", errors="replace")
    result = decide_strategy(text, context_window=context_window)
    result["warehouse_path"] = str(p.resolve())
    return result


# ---------------------------------------------------------------------------
# Batching helpers
# ---------------------------------------------------------------------------

def create_batches(chunks: List[Dict[str, Any]], batch_size: int = DEFAULT_BATCH_SIZE) -> List[List[Dict[str, Any]]]:
    """Split chunks into batches of `batch_size` (preserves order)."""
    if batch_size <= 0:
        raise ValueError("batch_size must be > 0")
    batches: List[List[Dict[str, Any]]] = []
    for i in range(0, len(chunks), batch_size):
        batches.append(chunks[i : i + batch_size])
    return batches


def _normalize_name(name: str) -> str:
    """Lowercase + strip punctuation for alias dedup key."""
    return re.sub(r"[^a-z0-9]+", " ", name.lower()).strip()


def _person_tokens(name: str) -> tuple[str, str]:
    """Return (first_name, surname_initial) for alias matching. e.g. Rose Mathew -> (rose, m), Rose M. -> (rose, m), R. Mathew -> (r, m)"""
    norm = _normalize_name(name)
    parts = norm.split()
    if not parts:
        return ("", "")
    if len(parts) == 1:
        return (parts[0], "")
    first = parts[0]
    last = parts[-1]
    surname_initial = last[0] if last else ""
    return (first, surname_initial)


def _person_alias_match(name_a: str, name_b: str) -> bool:
    """
    Check if two person names are aliases of same individual.
    Heuristic: first name matches (full or initial) AND surname initial matches.
    """
    a_first, a_s_init = _person_tokens(name_a)
    b_first, b_s_init = _person_tokens(name_b)
    if not a_first or not b_first:
        return False
    if a_s_init and b_s_init and a_s_init != b_s_init:
        return False
    if a_first == b_first:
        return True
    if len(a_first) == 1 and b_first.startswith(a_first):
        return True
    if len(b_first) == 1 and a_first.startswith(b_first):
        return True
    return False


def _entity_dedup_key(ent: Dict[str, Any]) -> str:
    """Key for merging: for PERSON use alias-aware via search, otherwise normalized."""
    name = ent.get("name", "")
    etype = ent.get("type", "")
    if etype == "PERSON":
        first, s_init = _person_tokens(name)
        return f"person:{first[0] if first else ''}_{s_init}"
    return f"{etype.lower()}:{_normalize_name(name)}"


def _find_person_match(merged: Dict[str, Dict[str, Any]], incoming_name: str) -> str | None:
    """Search merged PERSON entries for alias match; return matching key if found."""
    for key, existing in merged.items():
        if not key.startswith("person:"):
            continue
        if _person_alias_match(existing["name"], incoming_name):
            return key
    return None


def make_entity_id(name: str, etype: str) -> str:
    """Generate deterministic entity id: e.g. PERSON Sara -> person_sara, PERSON Rose Mathew -> person_rose_mathew"""
    norm = _normalize_name(name)  # "rose mathew"
    slug = re.sub(r"[^a-z0-9]+", "_", norm).strip("_")
    if not slug:
        slug = "unknown"
    return f"{etype.lower()}_{slug}"


def _merge_data(existing_data: Dict[str, Any], incoming_data: Dict[str, Any]) -> Dict[str, Any]:
    """Merge two data dicts: union, prefer non-empty, keep longer string on conflict."""
    if not isinstance(existing_data, dict):
        existing_data = {}
    if not isinstance(incoming_data, dict):
        return existing_data
    merged = dict(existing_data)
    for k, v in incoming_data.items():
        if k not in merged or merged[k] is None or merged[k] == "":
            merged[k] = v
        else:
            # Both have value — keep more informative (longer string, or non-empty dict)
            if isinstance(v, str) and isinstance(merged[k], str):
                if len(v) > len(str(merged[k])):
                    # Avoid overwriting with empty-ish
                    if v.strip():
                        merged[k] = v
            elif isinstance(v, dict) and isinstance(merged[k], dict):
                merged[k] = _merge_data(merged[k], v)
            # otherwise keep existing
    return merged


def merge_entities(
    all_entities: List[Dict[str, Any]]
) -> List[Dict[str, Any]]:
    """
    Merge entities by canonical alias-aware key, collapsing variants.
    Keeps max confidence, merges source_files/chunk_ids, aliases, and data (JSON metadata).
    Also ensures each entity gets an `id` and `data` field.
    """
    merged: Dict[str, Dict[str, Any]] = {}
    for ent in all_entities:
        etype = ent.get("type", "")
        name = ent.get("name", "")
        if not name:
            continue
        # Ensure data is dict
        ent_data = ent.get("data") if isinstance(ent.get("data"), dict) else {}
        # Ensure id exists (will finalize after merge)
        key: str | None = None
        if etype == "PERSON":
            key = _find_person_match(merged, name)
            if key is None:
                key = _entity_dedup_key(ent)
                if key in merged and not _person_alias_match(merged[key]["name"], name):
                    key = f"person:{_normalize_name(name)}"
        else:
            key = _entity_dedup_key(ent)
        if not key:
            continue
        if key not in merged:
            merged[key] = {
                "name": ent.get("name"),
                "type": ent.get("type"),
                "confidence": ent.get("confidence", 0.0),
                "source_files": set([ent.get("source_file")] if ent.get("source_file") else []),
                "chunk_ids": set([ent.get("chunk_id")] if ent.get("chunk_id") else []),
                "aliases": set(ent.get("aliases", []) if isinstance(ent.get("aliases"), list) else []),
                "data": dict(ent_data),
                "mentions": 1,
            }
        else:
            existing = merged[key]
            incoming_name = ent.get("name", "")
            if len(incoming_name) > len(existing["name"]):
                if _normalize_name(existing["name"]) != _normalize_name(incoming_name):
                    existing["aliases"].add(existing["name"])
                existing["name"] = incoming_name
            elif incoming_name != existing["name"] and _normalize_name(incoming_name) != _normalize_name(existing["name"]):
                existing["aliases"].add(incoming_name)
            existing["confidence"] = max(existing["confidence"], ent.get("confidence", 0.0))
            if ent.get("source_file"):
                existing["source_files"].add(ent["source_file"])
            if ent.get("chunk_id"):
                existing["chunk_ids"].add(ent["chunk_id"])
            for alias in ent.get("aliases", []) if isinstance(ent.get("aliases"), list) else []:
                if alias != existing["name"]:
                    existing["aliases"].add(alias)
            # Merge data
            existing["data"] = _merge_data(existing["data"], ent_data)
            existing["mentions"] += 1

    result: List[Dict[str, Any]] = []
    for v in merged.values():
        eid = make_entity_id(v["name"], v["type"] or "UNKNOWN")
        result.append(
            {
                "id": eid,
                "name": v["name"],
                "type": v["type"],
                "confidence": v["confidence"],
                "source_files": sorted(v["source_files"]),
                "chunk_ids": sorted(v["chunk_ids"]),
                "aliases": sorted(v["aliases"]),
                "data": v["data"],
                "mentions": v["mentions"],
            }
        )
    result.sort(key=lambda x: (-x["mentions"], x["name"]))
    return result


def ensure_entity_ids(entities: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Ensure each entity has id and data fields (for already-merged entities)."""
    for e in entities:
        if "id" not in e or not e["id"]:
            e["id"] = make_entity_id(e.get("name", "unknown"), e.get("type", "UNKNOWN"))
        if "data" not in e or not isinstance(e["data"], dict):
            e["data"] = {}
    return entities


def build_graph_mappings(
    entities: List[Dict[str, Any]],
    relationships: List[Dict[str, Any]],
) -> List[Dict[str, Any]]:
    """
    Deterministic mapping: entities (with id,data) + relationships -> relation_id : node1 relation node2
    Returns list of objects:
      {relation_id, source: {id,name,type,data,...}, relation, target: {id,name,type,data,...}, confidence, evidence_text, source_file, chunk_id}
    """
    # Index entities by name and by id for lookup
    by_name: Dict[str, Dict[str, Any]] = {}
    by_id: Dict[str, Dict[str, Any]] = {}
    for e in entities:
        # Ensure id/data
        if "id" not in e:
            e["id"] = make_entity_id(e.get("name", ""), e.get("type", ""))
        if "data" not in e:
            e["data"] = {}
        # Index by normalized name and by id
        by_name[_normalize_name(e["name"])] = e
        by_name[e["name"].lower()] = e
        by_id[e["id"]] = e
        # Also index person aliases via _person_alias_match search fallback
    # Helper to find entity for a relation endpoint
    def find_entity(name: str) -> Optional[Dict[str, Any]]:
        if not name:
            return None
        # Direct normalized lookup
        norm = _normalize_name(name)
        if norm in by_name:
            return by_name[norm]
        if name.lower() in by_name:
            return by_name[name.lower()]
        # Try id lookup
        if name in by_id:
            return by_id[name]
        # Person alias search
        for ent in entities:
            if ent.get("type") == "PERSON" and _person_alias_match(ent.get("name", ""), name):
                return ent
        # Fallback: case-insensitive contains?
        for ent in entities:
            if ent.get("name", "").lower() == name.lower():
                return ent
        return None

    mappings: List[Dict[str, Any]] = []
    for idx, rel in enumerate(relationships, start=1):
        src_name = rel.get("source", "")
        tgt_name = rel.get("target", "")
        src_ent = find_entity(src_name)
        tgt_ent = find_entity(tgt_name)
        if src_ent is None or tgt_ent is None:
            logger.warning("Skipping relation %s -> %s : entity not found", src_name, tgt_name)
            continue
        rel_id = rel.get("relation_id") or rel.get("id") or f"rel_{idx:03d}"
        mappings.append(
            {
                "relation_id": rel_id,
                "source": {
                    "id": src_ent["id"],
                    "name": src_ent["name"],
                    "type": src_ent["type"],
                    "data": src_ent.get("data", {}),
                    "source_files": src_ent.get("source_files", []),
                    "chunk_ids": src_ent.get("chunk_ids", []),
                    "aliases": src_ent.get("aliases", []),
                    "confidence": src_ent.get("confidence", 0.0),
                },
                "relation": rel.get("relation", "RELATED_TO"),
                "target": {
                    "id": tgt_ent["id"],
                    "name": tgt_ent["name"],
                    "type": tgt_ent["type"],
                    "data": tgt_ent.get("data", {}),
                    "source_files": tgt_ent.get("source_files", []),
                    "chunk_ids": tgt_ent.get("chunk_ids", []),
                    "aliases": tgt_ent.get("aliases", []),
                    "confidence": tgt_ent.get("confidence", 0.0),
                },
                "confidence": rel.get("confidence", 0.0),
                "evidence_text": rel.get("evidence_text", ""),
                "source_file": rel.get("source_file", ""),
                "chunk_id": rel.get("chunk_id", ""),
            }
        )
    return mappings


# ---------------------------------------------------------------------------
# Multi-provider OpenAI client
# ---------------------------------------------------------------------------

# Cache per (provider, base_url, api_key) -> client
_client_cache: Dict[str, Any] = {}


def _client_cache_key(cfg: LLMConfig) -> str:
    return f"{cfg.provider}|{cfg.base_url}|{cfg.api_key[:8] if cfg.api_key else ''}"


def get_client(cfg: Optional[LLMConfig] = None) -> Any:
    """
    Get (or create) OpenAI client for given config.
    If cfg is None, loads from env / default project-agnostic config.
    """
    if cfg is None:
        cfg = load_llm_config()

    key = _client_cache_key(cfg)
    if key in _client_cache:
        return _client_cache[key]

    try:
        from openai import OpenAI  # type: ignore

        kwargs: Dict[str, Any] = {
            "api_key": cfg.api_key or "sk-missing-key-for-testing",
            "timeout": 300.0,
            "max_retries": 2,
        }
        if cfg.base_url:
            kwargs["base_url"] = cfg.base_url
        if cfg.extra_headers:
            kwargs["default_headers"] = cfg.extra_headers
        client = OpenAI(**kwargs)
        _client_cache[key] = client
        logger.info("Created LLM client for provider=%s model=%s base_url=%s", cfg.provider, cfg.model, cfg.base_url)
        return client
    except Exception as e:
        logger.error("Failed to init OpenAI client for provider %s: %s", cfg.provider, e)
        raise


# Backcompat alias
def _get_client():
    return get_client()


def call_llm(
    user_prompt: str,
    system_prompt: str = "You are a helpful assistant. Return only valid JSON.",
    model: Optional[str] = None,
    llm_config: Optional[LLMConfig | Dict[str, Any]] = None,
    project_path: Optional[str | Path] = None,
    temperature: Optional[float] = None,
    max_retries: int = DEFAULT_MAX_RETRIES,
    timeout: int = 300,
) -> str:
    """
    Call LLM and return raw content string.
    Resolves config via priority: llm_config param > project_path/llm.json > env vars.
    Uses JSON mode when possible, with retries + backoff.
    """
    # Resolve config
    cfg: LLMConfig
    if isinstance(llm_config, LLMConfig):
        cfg = llm_config
    elif isinstance(llm_config, dict):
        # dict overrides -> merge into loaded config
        base = load_llm_config(project_path)
        # Apply dict as overrides
        cfg = load_llm_config(project_path, overrides=llm_config)
    elif project_path is not None:
        cfg = load_llm_config(project_path)
    elif llm_config is None:
        cfg = load_llm_config()
    else:
        cfg = load_llm_config()

    # CLI --model overrides config model
    effective_model = model or cfg.model
    effective_temp = temperature if temperature is not None else cfg.temperature

    client = get_client(cfg)

    last_err: Optional[Exception] = None
    for attempt in range(1, max_retries + 1):
        try:
            create_kwargs: Dict[str, Any] = {
                "model": effective_model,
                "messages": [
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": user_prompt},
                ],
                "temperature": effective_temp,
                "timeout": timeout,  # type: ignore
            }
            # Most OpenAI-compatible providers support response_format json_object
            # Some (ollama) may not — we try with, fallback without
            if cfg.provider not in ("ollama",):
                create_kwargs["response_format"] = {"type": "json_object"}  # type: ignore

            if cfg.extra_body:
                create_kwargs["extra_body"] = cfg.extra_body  # type: ignore

            resp = client.chat.completions.create(**create_kwargs)  # type: ignore
            content = resp.choices[0].message.content
            if content is None:
                raise ValueError("LLM returned None content")
            return content.strip()
        except Exception as e:
            last_err = e
            # If response_format not supported, retry without it
            if "response_format" in str(e).lower() or "response format" in str(e).lower():
                logger.warning("Provider %s may not support response_format, retrying without", cfg.provider)
                try:
                    resp = client.chat.completions.create(  # type: ignore
                        model=effective_model,
                        messages=[
                            {"role": "system", "content": system_prompt},
                            {"role": "user", "content": user_prompt},
                        ],
                        temperature=effective_temp,
                        timeout=timeout,  # type: ignore
                    )
                    content = resp.choices[0].message.content
                    if content is None:
                        raise ValueError("LLM returned None content")
                    return content.strip()
                except Exception as e2:
                    last_err = e2
            logger.warning("LLM call attempt %d/%d failed (provider=%s model=%s): %s", attempt, max_retries, cfg.provider, effective_model, e)
            if attempt < max_retries:
                time.sleep(2 ** attempt)
            else:
                logger.error("LLM call failed after %d attempts", max_retries)
                raise
    raise RuntimeError(f"LLM call failed: {last_err}")


def call_llm_with_thinking(
    user_prompt: str,
    system_prompt: str = "You are a master detective. Return valid JSON.",
    model: Optional[str] = None,
    llm_config: Optional[LLMConfig | Dict[str, Any]] = None,
    project_path: Optional[str | Path] = None,
    temperature: Optional[float] = None,
    max_retries: int = DEFAULT_MAX_RETRIES,
    timeout: int = 600,
    enable_thinking: bool = True,
) -> Tuple[str, str]:
    """
    Call LLM with thinking/reasoning mode enabled and return (content, reasoning_trace).
    Handles provider-specific reasoning flags and extracts reasoning content across:
      - DeepSeek reasoner (message.reasoning_content)
      - OpenRouter (include_reasoning / reasoning effort)
      - OpenAI o-series (reasoning_effort)
      - Local/Ollama models with <think>...</think> tags in content
    """
    # Resolve config
    cfg: LLMConfig
    if isinstance(llm_config, LLMConfig):
        cfg = llm_config
    elif isinstance(llm_config, dict):
        cfg = load_llm_config(project_path, overrides=llm_config)
    elif project_path is not None:
        cfg = load_llm_config(project_path)
    elif llm_config is None:
        cfg = load_llm_config()
    else:
        cfg = load_llm_config()

    # If provider is deepseek and model is default or chat, switch to deepseek-reasoner when thinking is requested
    if enable_thinking and (cfg.provider == "deepseek" or "deepseek.com" in str(cfg.base_url or "")) and (not model or model in ("deepseek-chat", "default")):
        effective_model = "deepseek-reasoner"
    else:
        effective_model = model or cfg.model

    effective_temp = temperature if temperature is not None else cfg.temperature

    is_deepseek_reasoner = (
        "deepseek-reasoner" in effective_model.lower()
        or "deepseek-r1" in effective_model.lower()
        or "reasoner" in effective_model.lower()
    )
    is_openai_reasoning = any(x in effective_model.lower() for x in ["o1", "o3", "o4", "gpt-5"])

    client = get_client(cfg)

    last_err: Optional[Exception] = None
    for attempt in range(1, max_retries + 1):
        try:
            # For OpenAI o-series, use developer message or standard system message
            system_role = "developer" if (is_openai_reasoning and cfg.provider == "openai") else "system"
            messages = [
                {"role": system_role, "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ]

            create_kwargs: Dict[str, Any] = {
                "model": effective_model,
                "messages": messages,
                "timeout": timeout,
            }

            # Only set temperature if not a strict reasoning model that rejects it
            if not is_deepseek_reasoner and not is_openai_reasoning:
                create_kwargs["temperature"] = effective_temp

            extra_body: Dict[str, Any] = dict(cfg.extra_body) if cfg.extra_body else {}

            if enable_thinking:
                if cfg.provider == "openrouter":
                    extra_body["reasoning"] = {"effort": "high"}
                    extra_body["include_reasoning"] = True
                elif is_openai_reasoning:
                    create_kwargs["reasoning_effort"] = "high"
                elif cfg.provider == "ollama":
                    extra_body["think"] = True

            # response_format json_object if supported and not reasoner
            if cfg.provider not in ("ollama",) and not is_deepseek_reasoner and not is_openai_reasoning:
                create_kwargs["response_format"] = {"type": "json_object"}

            if extra_body:
                create_kwargs["extra_body"] = extra_body

            resp = client.chat.completions.create(**create_kwargs)
            choice = resp.choices[0]
            msg = choice.message

            content = getattr(msg, "content", "") or ""
            reasoning_trace = ""

            # Check for native reasoning content
            rc = getattr(msg, "reasoning_content", None)
            r_alt = getattr(msg, "reasoning", None)
            if rc and isinstance(rc, str) and rc.strip():
                reasoning_trace = rc.strip()
            elif r_alt and isinstance(r_alt, str) and r_alt.strip():
                reasoning_trace = r_alt.strip()
            elif isinstance(msg, dict):
                reasoning_trace = str(msg.get("reasoning_content") or msg.get("reasoning") or "").strip()

            # Check for <think>...</think> in content (common in Ollama / R1 distill)
            think_match = re.search(r"<think>(.*?)</think>", content, re.DOTALL)
            if think_match:
                extracted_think = think_match.group(1).strip()
                if not reasoning_trace:
                    reasoning_trace = extracted_think
                content = re.sub(r"<think>.*?</think>", "", content, flags=re.DOTALL).strip()

            content = content.strip()
            return content, reasoning_trace

        except Exception as e:
            last_err = e
            # Fallback if provider fails on specific parameters (e.g. response_format or temperature)
            logger.warning("LLM thinking attempt %d/%d failed (provider=%s model=%s): %s", attempt, max_retries, cfg.provider, effective_model, e)
            try:
                # Retry with minimalist parameters
                resp = client.chat.completions.create(
                    model=effective_model,
                    messages=[
                        {"role": "user", "content": f"{system_prompt}\n\n{user_prompt}"},
                    ],
                    timeout=timeout,
                )
                choice = resp.choices[0]
                msg = choice.message
                content = getattr(msg, "content", "") or ""
                reasoning_trace = ""
                rc = getattr(msg, "reasoning_content", None)
                r_alt = getattr(msg, "reasoning", None)
                if rc and isinstance(rc, str) and rc.strip():
                    reasoning_trace = rc.strip()
                elif r_alt and isinstance(r_alt, str) and r_alt.strip():
                    reasoning_trace = r_alt.strip()

                think_match = re.search(r"<think>(.*?)</think>", content, re.DOTALL)
                if think_match:
                    if not reasoning_trace:
                        reasoning_trace = think_match.group(1).strip()
                    content = re.sub(r"<think>.*?</think>", "", content, flags=re.DOTALL).strip()

                return content.strip(), str(reasoning_trace).strip()
            except Exception as e2:
                last_err = e2

            if attempt < max_retries:
                time.sleep(2 ** attempt)
            else:
                logger.error("LLM thinking call failed after %d attempts", max_retries)
                raise
    raise RuntimeError(f"LLM thinking call failed: {last_err}")


def parse_json_array(text: str) -> List[Any]:

    """
    Robustly parse JSON array from LLM output.
    Handles markdown fences, leading/trailing text, object-wrapping, and truncated outputs.
    """
    if not text or not text.strip():
        return []

    fence_match = re.search(r"```(?:json)?\s*(.*?)\s*```", text, re.DOTALL)
    if fence_match:
        text = fence_match.group(1).strip()

    try:
        data = json.loads(text)
        if isinstance(data, list):
            return data
        if isinstance(data, dict):
            for key in ("entities", "relationships", "data", "result", "items"):
                if key in data and isinstance(data[key], list):
                    return data[key]
            if "name" in data or "source" in data:
                return [data]
            return []
    except json.JSONDecodeError:
        pass

    arr_match = re.search(r"\[.*\]", text, re.DOTALL)
    if arr_match:
        try:
            return json.loads(arr_match.group(0))
        except json.JSONDecodeError:
            pass

    obj_match = re.search(r"\{.*\}", text, re.DOTALL)
    if obj_match:
        try:
            data = json.loads(obj_match.group(0))
            if isinstance(data, list):
                return data
            if isinstance(data, dict):
                for key in ("entities", "relationships", "data", "result"):
                    if key in data and isinstance(data[key], list):
                        return data[key]
        except json.JSONDecodeError:
            pass

    # Try recovering objects up to the last complete '}' in truncated JSON array
    last_brace = text.rfind("}")
    if last_brace != -1:
        first_bracket = text.find("[")
        if first_bracket != -1 and first_bracket < last_brace:
            candidate = text[first_bracket:last_brace + 1] + "\n]"
            try:
                candidate_data = json.loads(candidate)
                if isinstance(candidate_data, list) and candidate_data:
                    return candidate_data
            except json.JSONDecodeError:
                pass

    # Fallback: scan for all complete top-level JSON objects {...}
    recovered: List[Dict[str, Any]] = []
    stack = 0
    start = -1
    in_string = False
    escape = False
    for i, ch in enumerate(text):
        if escape:
            escape = False
            continue
        if ch == "\\":
            escape = True
            continue
        if ch == '"':
            in_string = not in_string
            continue
        if in_string:
            continue
        if ch == "{":
            if stack == 0:
                start = i
            stack += 1
        elif ch == "}":
            stack -= 1
            if stack == 0 and start != -1:
                try:
                    obj = json.loads(text[start:i + 1])
                    if isinstance(obj, dict):
                        recovered.append(obj)
                except Exception:
                    pass
                start = -1

    if recovered:
        return recovered

    logger.warning("Failed to parse JSON array from LLM output (first 500 chars): %s", text[:500])
    return []


# ---------------------------------------------------------------------------
# High-level extraction (batched vs single)
# ---------------------------------------------------------------------------

def extract_entities_batched(
    chunks: List[Dict[str, Any]],
    batch_size: int = DEFAULT_BATCH_SIZE,
    model: Optional[str] = None,
    llm_config: Optional[LLMConfig | Dict[str, Any]] = None,
    project_path: Optional[str | Path] = None,
    verbose: bool = True,
) -> List[Dict[str, Any]]:
    """
    Batched entity extraction:
      - Splits chunks into batches of `batch_size` (default 20)
      - Each LLM call receives current batch + previous entities (for dedup)
      - Merges all results and runs local merge_entities to collapse aliases.
    """
    from engine.prompts import ENTITY_SYSTEM_PROMPT, build_entity_prompt

    batches = create_batches(chunks, batch_size)
    # Resolve config once
    cfg = llm_config if isinstance(llm_config, LLMConfig) else load_llm_config(project_path, overrides=llm_config if isinstance(llm_config, dict) else None)
    # Allow model override
    if model:
        cfg.model = model
    if verbose:
        print(f"[Sherlock LLM] Batched entity extraction: {len(chunks)} chunks → {len(batches)} batches (size {batch_size}) | provider={cfg.provider} model={cfg.model}")

    all_raw: List[Dict[str, Any]] = []
    previous_entities: List[Dict[str, Any]] = []

    for idx, batch in enumerate(batches, start=1):
        if verbose:
            print(f"[Sherlock LLM]  Batch {idx}/{len(batches)}: {len(batch)} chunks | prev_entities={len(previous_entities)}")

        user_prompt = build_entity_prompt(batch, previous_entities)
        raw_response = call_llm(user_prompt, system_prompt=ENTITY_SYSTEM_PROMPT, llm_config=cfg)
        entities = parse_json_array(raw_response)

        for e in entities:
            if not isinstance(e, dict):
                continue
            if "name" not in e or "type" not in e:
                continue
            all_raw.append(e)

        previous_entities = merge_entities(all_raw)
        if verbose:
            print(f"[Sherlock LLM]   → got {len(entities)} new, total unique so far: {len(previous_entities)}")

    deduped = merge_entities(all_raw)
    if verbose:
        print(f"[Sherlock LLM] Entity extraction complete: {len(all_raw)} raw → {len(deduped)} deduplicated")
    return deduped


def extract_entities_single_call(
    warehouse_text: str,
    model: Optional[str] = None,
    llm_config: Optional[LLMConfig | Dict[str, Any]] = None,
    project_path: Optional[str | Path] = None,
    verbose: bool = True,
) -> List[Dict[str, Any]]:
    """Single LLM call with whole warehouse text (when it fits)."""
    from engine.prompts import ENTITY_SYSTEM_PROMPT, build_single_call_entity_prompt

    cfg = llm_config if isinstance(llm_config, LLMConfig) else load_llm_config(project_path, overrides=llm_config if isinstance(llm_config, dict) else None)
    if model:
        cfg.model = model
    if verbose:
        stats = get_token_stats(warehouse_text)
        print(f"[Sherlock LLM] Single-call entity extraction: {stats['estimated_tokens']} tokens, {stats['words']} words | provider={cfg.provider} model={cfg.model}")

    user_prompt = build_single_call_entity_prompt(warehouse_text)
    raw_response = call_llm(user_prompt, system_prompt=ENTITY_SYSTEM_PROMPT, llm_config=cfg)
    entities = parse_json_array(raw_response)
    deduped = merge_entities([e for e in entities if isinstance(e, dict) and "name" in e])
    if verbose:
        print(f"[Sherlock LLM] Single-call done: {len(entities)} raw → {len(deduped)} deduped")
    return deduped


def extract_entities_auto(
    warehouse_text: str,
    chunks: List[Dict[str, Any]],
    context_window: Optional[int] = None,
    batch_size: int = DEFAULT_BATCH_SIZE,
    model: Optional[str] = None,
    llm_config: Optional[LLMConfig | Dict[str, Any]] = None,
    project_path: Optional[str | Path] = None,
    prefer_batched_when_fits: bool = True,
    verbose: bool = True,
) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
    """
    Auto-decide strategy based on token window, then extract.
    Resolves context_window from llm_config if not supplied.
    Returns (entities, decision_info)
    """
    cfg = llm_config if isinstance(llm_config, LLMConfig) else load_llm_config(project_path, overrides=llm_config if isinstance(llm_config, dict) else None)
    if model:
        cfg.model = model
    effective_window = context_window if context_window is not None else cfg.context_window
    decision = decide_strategy(
        warehouse_text, chunks, context_window=effective_window, prefer_batched_when_fits=prefer_batched_when_fits
    )
    if verbose:
        print(f"[Sherlock LLM] Token check: {decision['estimated_tokens']} tokens (window {effective_window}) | fits={decision['fits_in_context']} | strategy={decision['strategy']} | provider={cfg.provider} model={cfg.model}")
        print(f"[Sherlock LLM] Reason: {decision['reason']}")

    if decision["strategy"] == "single_call":
        entities = extract_entities_single_call(warehouse_text, llm_config=cfg, verbose=verbose)
    else:
        entities = extract_entities_batched(chunks, batch_size=batch_size, llm_config=cfg, verbose=verbose)

    return entities, decision


def extract_relationships_batched(
    chunks: List[Dict[str, Any]],
    entities: List[Dict[str, Any]],
    batch_size: int = DEFAULT_BATCH_SIZE,
    model: Optional[str] = None,
    llm_config: Optional[LLMConfig | Dict[str, Any]] = None,
    project_path: Optional[str | Path] = None,
    verbose: bool = True,
) -> List[Dict[str, Any]]:
    """
    Batched relationship extraction (always batched — needs entity grounding).
    Each batch receives known entities + previous relationships for dedup.
    """
    from engine.prompts import RELATION_SYSTEM_PROMPT, build_relationship_prompt

    batches = create_batches(chunks, batch_size)
    cfg = llm_config if isinstance(llm_config, LLMConfig) else load_llm_config(project_path, overrides=llm_config if isinstance(llm_config, dict) else None)
    if model:
        cfg.model = model
    if verbose:
        print(f"[Sherlock LLM] Batched relationship extraction: {len(chunks)} chunks, {len(entities)} entities → {len(batches)} batches | provider={cfg.provider} model={cfg.model}")

    all_rels: List[Dict[str, Any]] = []
    previous_rels: List[Dict[str, Any]] = []
    seen_keys: set = set()

    for idx, batch in enumerate(batches, start=1):
        if verbose:
            print(f"[Sherlock LLM]  Batch {idx}/{len(batches)} | prev_rels={len(previous_rels)}")

        user_prompt = build_relationship_prompt(batch, entities, previous_rels)
        raw_response = call_llm(user_prompt, system_prompt=RELATION_SYSTEM_PROMPT, llm_config=cfg)
        rels = parse_json_array(raw_response)

        new_count = 0
        for r in rels:
            if not isinstance(r, dict) or "source" not in r or "relation" not in r or "target" not in r:
                continue
            r["relation"] = re.sub(r"\s+", "_", r["relation"].strip().upper())
            key = (_normalize_name(r["source"]), r["relation"], _normalize_name(r["target"]))
            if key in seen_keys:
                continue
            seen_keys.add(key)
            all_rels.append(r)
            new_count += 1

        previous_rels = all_rels
        if verbose:
            print(f"[Sherlock LLM]   → got {len(rels)} raw, {new_count} new unique, total: {len(all_rels)}")

    if verbose:
        print(f"[Sherlock LLM] Relationship extraction complete: {len(all_rels)} unique relationships")
    return all_rels


# ---------------------------------------------------------------------------
# Graph mapping: entities + relations -> relation_id : node1 relation node2
# ---------------------------------------------------------------------------

def create_graph_mappings_via_llm(
    entities: List[Dict[str, Any]],
    relationships: List[Dict[str, Any]],
    llm_config: Optional[LLMConfig | Dict[str, Any]] = None,
    project_path: Optional[str | Path] = None,
    verbose: bool = True,
) -> List[Dict[str, Any]]:
    """
    LLM-based graph mapping: sends all entities (with data) + all relations to LLM,
    expects {relation_id, source:{id,name,type,data,...}, relation, target:{...}}.
    Falls back to deterministic build_graph_mappings() if LLM fails or returns empty.
    """
    from engine.prompts import GRAPH_MAPPING_SYSTEM_PROMPT, build_graph_mapping_prompt

    if not relationships:
        if verbose:
            print("[Sherlock LLM] No relationships to map — skipping graph mapping")
        return []
    cfg = llm_config if isinstance(llm_config, LLMConfig) else load_llm_config(project_path, overrides=llm_config if isinstance(llm_config, dict) else None)
    # Ensure ids
    entities = ensure_entity_ids(entities)

    user_prompt = build_graph_mapping_prompt(entities, relationships)
    if verbose:
        print(f"[Sherlock LLM] Graph mapping via LLM: {len(entities)} entities, {len(relationships)} relations | provider={cfg.provider} model={cfg.model}")
    try:
        raw = call_llm(user_prompt, system_prompt=GRAPH_MAPPING_SYSTEM_PROMPT, llm_config=cfg)
        mappings = parse_json_array(raw)
        # Validate mappings have relation_id + source/target
        valid = [m for m in mappings if isinstance(m, dict) and "relation_id" in m and "source" in m and "target" in m]
        if valid:
            if verbose:
                print(f"[Sherlock LLM] Graph mapping LLM returned {len(valid)} mappings")
            return valid
        logger.warning("LLM graph mapping returned %d valid out of %d raw, falling back to deterministic", len(valid), len(mappings))
    except Exception as e:
        logger.warning("LLM graph mapping failed: %s — falling back to deterministic", e)
    # Fallback deterministic
    if verbose:
        print("[Sherlock LLM] Falling back to deterministic graph mapping")
    return build_graph_mappings(entities, relationships)


# ---------------------------------------------------------------------------
# Timeline extraction — timestamp/event, unsorted from LLM, then sorted
# ---------------------------------------------------------------------------

import datetime as _dt

_TIMELINE_DATETIME_FORMATS: List[str] = [
    "%Y-%m-%d %H:%M:%S",
    "%Y-%m-%d %H:%M",
    "%Y-%m-%d",
    "%Y-%m-%dT%H:%M:%S%z",
    "%Y-%m-%dT%H:%M:%S",
    "%Y-%m-%dT%H:%M",
    "%d %B %Y %H:%M:%S",
    "%d %B %Y %H:%M",
    "%d %B %Y",
    "%d %b %Y %H:%M:%S",
    "%d %b %Y %H:%M",
    "%d %b %Y",
    "%d-%m-%Y %H:%M",
    "%d/%m/%Y %H:%M",
    "%Y/%m/%d %H:%M",
    "%m/%d/%Y %H:%M",
]


def _strip_timezone_suffix(ts: str) -> str:
    """Remove trailing timezone tokens like IST, UTC, +05:30 for parsing attempts."""
    if not ts:
        return ts
    # Remove trailing IST/UTC/GMT etc (case insensitive)
    ts = re.sub(r"\s+(IST|UTC|GMT|UTC\+.*|GMT\+.*)$", "", ts.strip(), flags=re.IGNORECASE)
    # Remove trailing Z
    ts = re.sub(r"\s*Z\s*$", "", ts)
    # Normalise multiple spaces / T separator
    ts = ts.strip()
    return ts


def _try_parse_timeline_timestamp(raw: str) -> Optional[_dt.datetime]:
    """
    Try to parse a timestamp string into a datetime for sorting.
    Supports ISO-8601, 'YYYY-MM-DD HH:MM', '14 April 2026 18:30', etc.
    Returns naive datetime (or aware if tz present) or None if unparseable.
    """
    if not raw or not isinstance(raw, str):
        return None
    s = raw.strip()
    if not s:
        return None

    # Fast: try dateutil if available (most robust)
    try:
        from dateutil import parser as _parser  # type: ignore

        # dateutil handles many formats + IST (needs tzinfos)
        # Provide tzinfos for IST
        tzinfos = {"IST": 19800}
        try:
            dt = _parser.parse(s, tzinfos=tzinfos, fuzzy=True, dayfirst=False)
            return dt
        except Exception:
            pass
        # fuzzy fallback without tzinfos
        try:
            dt = _parser.parse(s, fuzzy=True)
            return dt
        except Exception:
            pass
    except ImportError:
        pass

    # Try stripping tz suffix and try known formats
    stripped = _strip_timezone_suffix(s)
    # Replace 'T' with space for some formats
    for fmt in _TIMELINE_DATETIME_FORMATS:
        try:
            dt = _dt.datetime.strptime(stripped, fmt)
            return dt
        except Exception:
            continue
        # Try with T replaced?
    # Try also stripping T variant
    stripped_t = stripped.replace("T", " ")
    if stripped_t != stripped:
        for fmt in _TIMELINE_DATETIME_FORMATS:
            try:
                dt = _dt.datetime.strptime(stripped_t, fmt)
                return dt
            except Exception:
                continue

    # Regex fallback: extract YYYY-MM-DD and optional HH:MM:SS (allow " at " separator)
    # Look for 2026-04-14 pattern
    m = re.search(r"(\d{4})[-/](\d{1,2})[-/](\d{1,2})(?:\s*(?:at)?\s*(\d{1,2}):(\d{2})(?::(\d{2}))?)?", s)
    if m:
        try:
            y, mo, d = int(m.group(1)), int(m.group(2)), int(m.group(3))
            h = int(m.group(4)) if m.group(4) else 0
            mi = int(m.group(5)) if m.group(5) else 0
            se = int(m.group(6)) if m.group(6) else 0
            return _dt.datetime(y, mo, d, h, mi, se)
        except Exception:
            pass

    # Regex for "14 April 2026" or "14 Apr 2026" (day month year)
    month_map = {
        "january": 1, "february": 2, "march": 3, "april": 4, "may": 5, "june": 6,
        "july": 7, "august": 8, "september": 9, "october": 10, "november": 11, "december": 12,
        "jan": 1, "feb": 2, "mar": 3, "apr": 4, "jun": 6, "jul": 7, "aug": 8, "sep": 9, "sept": 9, "oct": 10, "nov": 11, "dec": 12,
    }
    m2 = re.search(r"(\d{1,2})\s+([A-Za-z]+)\s+(\d{4})(?:\s*(?:at)?\s*(\d{1,2}):(\d{2})(?::(\d{2}))?)?", s)
    if m2:
        try:
            d = int(m2.group(1))
            mon_str = m2.group(2).lower()
            y = int(m2.group(3))
            mon = month_map.get(mon_str)
            if mon:
                h = int(m2.group(4)) if m2.group(4) else 0
                mi = int(m2.group(5)) if m2.group(5) else 0
                se = int(m2.group(6)) if m2.group(6) else 0
                return _dt.datetime(y, mon, d, h, mi, se)
        except Exception:
            pass

    # Regex for "April 14, 2026" or "Apr 14, 2026" with optional time (US style) — allow "at" between date and time
    m3 = re.search(r"([A-Za-z]+)\s+(\d{1,2}),?\s+(\d{4})(?:\s*(?:at)?\s*(\d{1,2}):(\d{2})(?::(\d{2}))?(?:\s*(AM|PM|am|pm))?)?", s)
    if m3:
        try:
            mon_str = m3.group(1).lower()
            d = int(m3.group(2))
            y = int(m3.group(3))
            mon = month_map.get(mon_str)
            if mon:
                h = int(m3.group(4)) if m3.group(4) else 0
                mi = int(m3.group(5)) if m3.group(5) else 0
                se = int(m3.group(6)) if m3.group(6) else 0
                ampm = m3.group(7)
                if ampm:
                    ampm = ampm.lower()
                    if ampm == "pm" and h < 12:
                        h += 12
                    elif ampm == "am" and h == 12:
                        h = 0
                return _dt.datetime(y, mon, d, h, mi, se)
        except Exception:
            pass

    # Regex for generic date with time like "14-04-2026 18:30" or "14/04/2026"
    m4 = re.search(r"(\d{1,2})[-/](\d{1,2})[-/](\d{4})(?:[ T](\d{1,2}):(\d{2})(?::(\d{2}))?)?", s)
    if m4:
        try:
            # Try to disambiguate d/m vs m/d: if first >12 assume d/m
            a, b, y = int(m4.group(1)), int(m4.group(2)), int(m4.group(3))
            h = int(m4.group(4)) if m4.group(4) else 0
            mi = int(m4.group(5)) if m4.group(5) else 0
            se = int(m4.group(6)) if m4.group(6) else 0
            if a > 12:
                d, mo = a, b
            elif b > 12:
                d, mo = a, b  # a is month? ambiguous, default d,m
                # but keep as d= a, mo=b if both <=12 default dd/mm
                d, mo = a, b
            else:
                # both <=12, treat as dd/mm (common in India)
                d, mo = a, b
            return _dt.datetime(y, mo, d, h, mi, se)
        except Exception:
            pass

    return None


def sort_timeline_events(events: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """
    Sort timeline events chronologically by timestamp.
    Events are returned in parsed order; original order preserved for unparseable timestamps
    (those go to the end, sorted lexicographically as fallback).

    Each event should have 'timestamp' (string). Stable sort is used so encounter order is
    tie-breaker for equal timestamps.
    """
    def sort_key(ev: Dict[str, Any]):
        raw = ev.get("timestamp", "")
        dt = _try_parse_timeline_timestamp(str(raw))
        if dt is not None:
            # Use aware->naive for comparison by timestamp()
            try:
                if dt.tzinfo is not None:
                    dt = dt.replace(tzinfo=None)
                # For sorting, use tuple (0, dt); None second for parseable
                # Put parseable first
                return (0, dt, "")
            except Exception:
                return (0, dt, "")
        # Unparseable -> push to end, sort by raw string to keep deterministic
        return (1, _dt.datetime.max, str(raw))

    # Attach original index for stable tie-break
    indexed = list(enumerate(events))
    # Python sort is stable; we want parseable events first sorted by dt, then unparseable lexicographically
    # Use sort_key + index
    sorted_indexed = sorted(indexed, key=lambda ix_ev: (sort_key(ix_ev[1]), ix_ev[0]))
    return [ev for _, ev in sorted_indexed]


def _timeline_dedup_key(ev: Dict[str, Any]) -> tuple:
    """Normalized key for timeline dedup: (normalized_timestamp, normalized_event)."""
    ts = str(ev.get("timestamp", "")).strip().lower()
    # Normalize timestamp by removing extra spaces, IST etc and lowercasing
    ts_norm = re.sub(r"\s+", " ", _strip_timezone_suffix(ts)).strip()
    # For event text, normalize similarly to entity dedup
    event_norm = _normalize_name(str(ev.get("event", "")))
    return (ts_norm, event_norm[:120])


def normalize_timeline_event(raw: Dict[str, Any], default_source: str = "", default_chunk: str = "") -> Optional[Dict[str, Any]]:
    """
    Normalize a single raw timeline event from LLM into canonical schema.
    Returns None if missing required fields.
    """
    if not isinstance(raw, dict):
        return None
    ts = raw.get("timestamp") or raw.get("time") or raw.get("date") or raw.get("datetime")
    event_text = raw.get("event") or raw.get("description") or raw.get("title") or raw.get("text")
    if not ts or not event_text:
        return None
    ts = str(ts).strip()
    event_text = str(event_text).strip()
    if not ts or not event_text:
        return None

    source_file = str(raw.get("source_file", "") or raw.get("source", "") or default_source).strip()
    chunk_id = str(raw.get("chunk_id", "") or raw.get("chunkId", "") or default_chunk).strip()
    confidence = raw.get("confidence", 0.8)
    try:
        confidence = float(confidence)
        confidence = max(0.0, min(1.0, confidence))
    except Exception:
        confidence = 0.8
    evidence_text = str(raw.get("evidence_text", "") or raw.get("evidence", "") or event_text[:120]).strip()

    # Attempt to generate ISO-ish normalized timestamp for sorting but keep original
    # Store original as 'timestamp' and also 'timestamp_normalized' if parseable
    dt = _try_parse_timeline_timestamp(ts)
    timestamp_normalized = None
    if dt is not None:
        try:
            # Format as ISO with space separator, keep seconds if non-zero
            if dt.second != 0:
                timestamp_normalized = dt.strftime("%Y-%m-%d %H:%M:%S")
            elif dt.hour != 0 or dt.minute != 0:
                timestamp_normalized = dt.strftime("%Y-%m-%d %H:%M")
            else:
                # Only date or midnight — check if original had time
                has_time = bool(re.search(r"\d{1,2}:\d{2}", ts))
                if has_time:
                    timestamp_normalized = dt.strftime("%Y-%m-%d %H:%M")
                else:
                    timestamp_normalized = dt.strftime("%Y-%m-%d")
        except Exception:
            timestamp_normalized = None

    out: Dict[str, Any] = {
        "timestamp": ts,
        "event": event_text,
        "title": event_text,
        "description": event_text,
        "source_file": source_file,
        "chunk_id": chunk_id,
        "confidence": confidence,
        "evidence_text": evidence_text,
    }
    if timestamp_normalized and timestamp_normalized != ts:
        out["timestamp_normalized"] = timestamp_normalized
        # Also store sortable iso
        out["_parsed_datetime"] = dt.isoformat() if dt else None
    else:
        out["_parsed_datetime"] = dt.isoformat() if dt else None

    # Keep any extra fields like event_type if supplied
    if "event_type" in raw:
        out["event_type"] = raw["event_type"]
    if "entities" in raw:
        out["entities"] = raw["entities"]

    return out


def extract_timeline_batched(
    chunks: List[Dict[str, Any]],
    batch_size: int = DEFAULT_BATCH_SIZE,
    model: Optional[str] = None,
    llm_config: Optional[LLMConfig | Dict[str, Any]] = None,
    project_path: Optional[str | Path] = None,
    verbose: bool = True,
    sort_after: bool = True,
) -> List[Dict[str, Any]]:
    """
    Batched timeline extraction:
      - Splits chunks into batches of `batch_size` (default 20)
      - Each LLM call receives current batch + previous events (for dedup)
      - Merges all results, dedups by (timestamp, event), then sorts chronologically.

    Args:
        chunks: list of chunk dicts {chunk_id, source_file, text}
        batch_size: chunks per LLM call
        llm_config: LLMConfig or dict
        project_path: for resolving llm.json
        verbose: print progress
        sort_after: if True, sort returned list chronologically; if False, return encounter order.

    Returns:
        List of timeline events sorted chronologically (if sort_after True).
    """
    from engine.prompts import TIMELINE_SYSTEM_PROMPT, build_timeline_prompt

    batches = create_batches(chunks, batch_size)
    cfg = llm_config if isinstance(llm_config, LLMConfig) else load_llm_config(project_path, overrides=llm_config if isinstance(llm_config, dict) else None)
    if model:
        cfg.model = model
    if verbose:
        print(f"[Sherlock LLM] Batched timeline extraction: {len(chunks)} chunks → {len(batches)} batches (size {batch_size}) | provider={cfg.provider} model={cfg.model}")

    all_events: List[Dict[str, Any]] = []
    previous_events: List[Dict[str, Any]] = []
    seen_keys: set = set()

    for idx, batch in enumerate(batches, start=1):
        if verbose:
            print(f"[Sherlock LLM]  Batch {idx}/{len(batches)}: {len(batch)} chunks | prev_events={len(previous_events)}")

        user_prompt = build_timeline_prompt(batch, previous_events)
        raw_response = call_llm(user_prompt, system_prompt=TIMELINE_SYSTEM_PROMPT, llm_config=cfg)
        raw_events = parse_json_array(raw_response)

        new_count = 0
        default_src = batch[0].get("source_file", "") if batch else ""
        default_chunk = batch[0].get("chunk_id", "") if batch else ""
        for raw_ev in raw_events:
            norm = normalize_timeline_event(raw_ev, default_source=default_src, default_chunk=default_chunk)
            if norm is None:
                continue
            # Ensure source/chunk fallback to batch if not supplied
            if not norm.get("source_file"):
                norm["source_file"] = default_src
            if not norm.get("chunk_id"):
                norm["chunk_id"] = default_chunk
            key = _timeline_dedup_key(norm)
            if key in seen_keys:
                continue
            # Also skip empty event text
            if not norm["event"].strip():
                continue
            seen_keys.add(key)
            all_events.append(norm)
            new_count += 1

        # Update previous for next batch — send deduplicated sorted snapshot? Send current all so far sorted for context
        # To keep LLM context small, send last 30 events if many
        if len(all_events) > 30:
            previous_events = all_events[-30:]
        else:
            previous_events = list(all_events)

        if verbose:
            print(f"[Sherlock LLM]   → got {len(raw_events)} raw, {new_count} new unique, total: {len(all_events)}")

    if sort_after:
        sorted_events = sort_timeline_events(all_events)
        if verbose:
            print(f"[Sherlock LLM] Timeline extraction complete: {len(all_events)} unique → sorted chronologically ({len(sorted_events)} events)")
        return sorted_events
    else:
        if verbose:
            print(f"[Sherlock LLM] Timeline extraction complete: {len(all_events)} unique (unsorted, encounter order)")
        return all_events


def extract_timeline_single_call(
    warehouse_text: str,
    chunks: Optional[List[Dict[str, Any]]] = None,
    model: Optional[str] = None,
    llm_config: Optional[LLMConfig | Dict[str, Any]] = None,
    project_path: Optional[str | Path] = None,
    verbose: bool = True,
    sort_after: bool = True,
) -> List[Dict[str, Any]]:
    """Single LLM call with whole warehouse text for timeline (when it fits in context)."""
    from engine.prompts import TIMELINE_SYSTEM_PROMPT, build_single_call_timeline_prompt

    cfg = llm_config if isinstance(llm_config, LLMConfig) else load_llm_config(project_path, overrides=llm_config if isinstance(llm_config, dict) else None)
    if model:
        cfg.model = model
    if verbose:
        stats = get_token_stats(warehouse_text)
        print(f"[Sherlock LLM] Single-call timeline extraction: {stats['estimated_tokens']} tokens, {stats['words']} words | provider={cfg.provider} model={cfg.model}")

    user_prompt = build_single_call_timeline_prompt(warehouse_text)
    raw_response = call_llm(user_prompt, system_prompt=TIMELINE_SYSTEM_PROMPT, llm_config=cfg)
    raw_events = parse_json_array(raw_response)

    all_events: List[Dict[str, Any]] = []
    seen_keys: set = set()
    default_src = "warehouse.txt"
    default_chunk = chunks[0].get("chunk_id", "") if chunks else "warehouse_chunk_001"
    for raw_ev in raw_events:
        norm = normalize_timeline_event(raw_ev, default_source=default_src, default_chunk=default_chunk)
        if norm is None:
            continue
        key = _timeline_dedup_key(norm)
        if key in seen_keys:
            continue
        seen_keys.add(key)
        all_events.append(norm)

    # Deduplicate again via sort helper
    if sort_after:
        sorted_events = sort_timeline_events(all_events)
        if verbose:
            print(f"[Sherlock LLM] Single-call timeline done: {len(raw_events)} raw → {len(sorted_events)} sorted unique")
        return sorted_events
    else:
        if verbose:
            print(f"[Sherlock LLM] Single-call timeline done: {len(raw_events)} raw → {len(all_events)} unique (unsorted)")
        return all_events


def extract_timeline_auto(
    warehouse_text: str,
    chunks: List[Dict[str, Any]],
    context_window: Optional[int] = None,
    batch_size: int = DEFAULT_BATCH_SIZE,
    model: Optional[str] = None,
    llm_config: Optional[LLMConfig | Dict[str, Any]] = None,
    project_path: Optional[str | Path] = None,
    prefer_batched_when_fits: bool = False,
    verbose: bool = True,
    sort_after: bool = True,
) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
    """
    Auto-decide strategy based on token window, then extract timeline.
    If warehouse fits and prefer_batched_when_fits=False (default for timeline), uses single call;
    otherwise batched.

    Timeline is less alias-sensitive than entities, so single-call when fits is often more coherent.
    Set prefer_batched_when_fits=True to force batched even when fits.

    Returns (timeline_events_sorted, decision_info)
    """
    cfg = llm_config if isinstance(llm_config, LLMConfig) else load_llm_config(project_path, overrides=llm_config if isinstance(llm_config, dict) else None)
    if model:
        cfg.model = model
    effective_window = context_window if context_window is not None else cfg.context_window
    decision = decide_strategy(
        warehouse_text, chunks, context_window=effective_window, prefer_batched_when_fits=prefer_batched_when_fits
    )
    if verbose:
        print(f"[Sherlock LLM] Timeline token check: {decision['estimated_tokens']} tokens (window {effective_window}) | fits={decision['fits_in_context']} | strategy={decision['strategy']} | provider={cfg.provider} model={cfg.model}")
        print(f"[Sherlock LLM] Timeline reason: {decision['reason']}")

    if decision["strategy"] == "single_call":
        events = extract_timeline_single_call(warehouse_text, chunks=chunks, llm_config=cfg, verbose=verbose, sort_after=sort_after)
    else:
        events = extract_timeline_batched(chunks, batch_size=batch_size, llm_config=cfg, verbose=verbose, sort_after=sort_after)

    return events, decision


# ---------------------------------------------------------------------------
# Contradiction Extraction Routines
# ---------------------------------------------------------------------------

def normalize_contradiction(
    raw: Dict[str, Any],
    default_source: str = "",
    default_chunk: str = "",
    index: int = 1,
) -> Optional[Dict[str, Any]]:
    """
    Validate and normalize a raw contradiction record from LLM.
    Returns normalized dict or None if invalid.
    """
    if not isinstance(raw, dict):
        return None

    # Summary / description extraction
    summary = raw.get("summary") or raw.get("title") or raw.get("name") or ""
    description = raw.get("description") or raw.get("explanation") or raw.get("details") or summary or ""
    if not summary and not description:
        return None
    if not summary:
        summary = description[:100] + ("..." if len(description) > 100 else "")

    # Contradiction ID
    contra_id = str(raw.get("contradiction_id") or raw.get("id") or f"contra_{index:03d}")
    if not contra_id.startswith("contra_"):
        contra_id = f"contra_{index:03d}"

    # Type normalization
    valid_types = {
        "ALIBI_VS_EVIDENCE",
        "STATEMENT_VS_STATEMENT",
        "TIMELINE_CONFLICT",
        "RELATIONSHIP_DENIAL",
        "FINANCIAL_OR_RECORD_MISMATCH",
        "PHYSICAL_VS_TESTIMONIAL",
        "FACTUAL_INCONSISTENCY",
    }
    raw_type = str(raw.get("type", "FACTUAL_INCONSISTENCY")).strip().upper().replace(" ", "_").replace("-", "_")
    if raw_type not in valid_types:
        if "ALIBI" in raw_type or "CCTV" in raw_type or "LOCATION" in raw_type:
            raw_type = "ALIBI_VS_EVIDENCE"
        elif "STATEMENT" in raw_type or "TESTIMONY" in raw_type or "WITNESS" in raw_type:
            raw_type = "STATEMENT_VS_STATEMENT"
        elif "TIME" in raw_type or "DATE" in raw_type or "CHRONO" in raw_type:
            raw_type = "TIMELINE_CONFLICT"
        elif "RELATION" in raw_type or "DENIAL" in raw_type:
            raw_type = "RELATIONSHIP_DENIAL"
        elif "FINANC" in raw_type or "MONEY" in raw_type or "ACCOUNT" in raw_type or "RECORD" in raw_type:
            raw_type = "FINANCIAL_OR_RECORD_MISMATCH"
        elif "PHYSICAL" in raw_type or "FORENSIC" in raw_type or "MEDICAL" in raw_type:
            raw_type = "PHYSICAL_VS_TESTIMONIAL"
        else:
            raw_type = "FACTUAL_INCONSISTENCY"

    # Severity normalization
    valid_severities = {"CRITICAL", "HIGH", "MEDIUM", "LOW"}
    raw_sev = str(raw.get("severity", "HIGH")).strip().upper()
    if raw_sev not in valid_severities:
        if raw_sev in {"SEVERE", "FATAL", "URGENT", "BLOCKER"}:
            raw_sev = "CRITICAL"
        elif raw_sev in {"MAJOR", "IMPORTANT"}:
            raw_sev = "HIGH"
        elif raw_sev in {"MODERATE", "NORMAL"}:
            raw_sev = "MEDIUM"
        elif raw_sev in {"MINOR", "INFO"}:
            raw_sev = "LOW"
        else:
            raw_sev = "HIGH"

    # Confidence normalization
    try:
        conf = float(raw.get("confidence", 0.9))
        conf = max(0.0, min(1.0, conf))
    except (ValueError, TypeError):
        conf = 0.85

    # Entities involved
    raw_ents = raw.get("entities_involved") or raw.get("entities") or []
    entities_involved: List[str] = []
    if isinstance(raw_ents, list):
        for e in raw_ents:
            if isinstance(e, str) and e.strip():
                entities_involved.append(e.strip())
            elif isinstance(e, dict) and "name" in e:
                entities_involved.append(str(e["name"]).strip())
    elif isinstance(raw_ents, str) and raw_ents.strip():
        entities_involved = [raw_ents.strip()]

    # Conflicting points
    raw_points = raw.get("conflicting_points") or raw.get("points") or raw.get("claims") or raw.get("evidence") or []
    conflicting_points: List[Dict[str, Any]] = []
    if isinstance(raw_points, list):
        for pt in raw_points:
            if isinstance(pt, dict):
                p_claim = pt.get("claim") or pt.get("statement") or pt.get("text") or ""
                p_source_file = pt.get("source_file") or default_source or ""
                p_chunk_id = pt.get("chunk_id") or default_chunk or ""
                p_speaker = pt.get("speaker_or_source") or pt.get("speaker") or pt.get("source") or p_source_file or ""
                p_quote = pt.get("quote") or pt.get("evidence_text") or pt.get("excerpt") or p_claim
                if p_claim or p_quote:
                    conflicting_points.append({
                        "claim": p_claim or p_quote,
                        "speaker_or_source": p_speaker,
                        "source_file": p_source_file,
                        "chunk_id": p_chunk_id,
                        "quote": p_quote,
                    })
            elif isinstance(pt, str) and pt.strip():
                conflicting_points.append({
                    "claim": pt.strip(),
                    "speaker_or_source": default_source or "Evidence",
                    "source_file": default_source,
                    "chunk_id": default_chunk,
                    "quote": pt.strip(),
                })

    # If conflicting_points is empty, provide a fallback from summary
    if not conflicting_points:
        conflicting_points = [
            {
                "claim": summary,
                "speaker_or_source": raw.get("source_file") or default_source or "Source",
                "source_file": raw.get("source_file") or default_source,
                "chunk_id": raw.get("chunk_id") or default_chunk,
                "quote": raw.get("quote") or raw.get("evidence_text") or summary,
            }
        ]

    # Resolution status
    valid_statuses = {"POTENTIAL_LIE", "SUSPICIOUS", "UNRESOLVED", "REQUIRES_VERIFICATION"}
    raw_status = str(raw.get("resolution_status", "UNRESOLVED")).strip().upper().replace(" ", "_")
    if raw_status not in valid_statuses:
        if "LIE" in raw_status or "FALSE" in raw_status:
            raw_status = "POTENTIAL_LIE"
        elif "SUSPECT" in raw_status or "SUSPICIOUS" in raw_status:
            raw_status = "SUSPICIOUS"
        elif "VERIF" in raw_status or "CHECK" in raw_status:
            raw_status = "REQUIRES_VERIFICATION"
        else:
            raw_status = "UNRESOLVED"

    # Investigation lead
    investigation_lead = (
        raw.get("investigation_lead")
        or raw.get("lead")
        or raw.get("recommended_action")
        or raw.get("interrogation_lead")
        or ""
    )

    return {
        "contradiction_id": contra_id,
        "type": raw_type,
        "summary": summary,
        "description": description,
        "severity": raw_sev,
        "confidence": conf,
        "entities_involved": entities_involved,
        "conflicting_points": conflicting_points,
        "resolution_status": raw_status,
        "investigation_lead": investigation_lead,
    }


def _contradiction_dedup_key(contra: Dict[str, Any]) -> str:
    """Generate normalized dedup key for contradiction based on type + entities + key words."""
    ctype = contra.get("type", "")
    ents = sorted([e.lower().strip() for e in contra.get("entities_involved", [])])
    summary_words = sorted(list(set(re.findall(r"\w+", (contra.get("summary", "")).lower()))))
    key_words = "_".join(summary_words[:6])
    return f"{ctype}:{','.join(ents)}:{key_words}"


def deduplicate_contradictions(contradictions: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Deduplicate contradictions and re-assign sequential IDs."""
    seen = set()
    unique: List[Dict[str, Any]] = []
    for c in contradictions:
        k = _contradiction_dedup_key(c)
        if k in seen:
            continue
        seen.add(k)
        unique.append(c)

    for i, c in enumerate(unique, 1):
        c["contradiction_id"] = f"contra_{i:03d}"

    return unique


def extract_contradictions_batched(
    chunks: List[Dict[str, Any]],
    batch_size: int = DEFAULT_BATCH_SIZE,
    llm_config: Optional[LLMConfig | Dict[str, Any]] = None,
    known_entities: Optional[List[Dict[str, Any]]] = None,
    known_relations: Optional[List[Dict[str, Any]]] = None,
    known_timeline: Optional[List[Dict[str, Any]]] = None,
    verbose: bool = True,
) -> List[Dict[str, Any]]:
    """
    Extract contradictions across chunks in batches, using known case context.
    """
    from engine.prompts import CONTRADICTION_SYSTEM_PROMPT, build_contradiction_prompt

    cfg = llm_config if isinstance(llm_config, LLMConfig) else load_llm_config(overrides=llm_config if isinstance(llm_config, dict) else None)
    batches = create_batches(chunks, batch_size=batch_size)
    if verbose:
        print(f"[Sherlock LLM] Extracting contradictions from {len(chunks)} chunks in {len(batches)} batches (batch_size={batch_size})")

    all_contradictions: List[Dict[str, Any]] = []

    for i, batch in enumerate(batches, 1):
        if verbose:
            print(f"[Sherlock LLM] Contradictions Batch {i}/{len(batches)} ({len(batch)} chunks)...")

        prompt = build_contradiction_prompt(
            batch_chunks=batch,
            known_entities=known_entities,
            known_relations=known_relations,
            known_timeline=known_timeline,
            previous_contradictions=all_contradictions,
        )

        try:
            response_text = call_llm(
                user_prompt=prompt,
                system_prompt=CONTRADICTION_SYSTEM_PROMPT,
                llm_config=cfg,
            )
            raw_list = parse_json_array(response_text)
        except Exception as e:
            logger.warning(f"Contradictions batch {i} failed: {e}")
            if verbose:
                print(f"[Sherlock LLM] WARNING: Contradictions batch {i} failed: {e}")
            raw_list = []

        batch_source = batch[0].get("source_file", "") if batch else ""
        batch_chunk = batch[0].get("chunk_id", "") if batch else ""

        for item in raw_list:
            norm = normalize_contradiction(item, default_source=batch_source, default_chunk=batch_chunk, index=len(all_contradictions) + 1)
            if norm:
                all_contradictions.append(norm)

    unique_contradictions = deduplicate_contradictions(all_contradictions)
    if verbose:
        print(f"[Sherlock LLM] Contradiction extraction completed: {len(all_contradictions)} raw -> {len(unique_contradictions)} unique contradictions")

    return unique_contradictions


def extract_contradictions_single_call(
    warehouse_text: str,
    chunks: Optional[List[Dict[str, Any]]] = None,
    llm_config: Optional[LLMConfig | Dict[str, Any]] = None,
    known_entities: Optional[List[Dict[str, Any]]] = None,
    known_relations: Optional[List[Dict[str, Any]]] = None,
    known_timeline: Optional[List[Dict[str, Any]]] = None,
    verbose: bool = True,
) -> List[Dict[str, Any]]:
    """
    Extract contradictions from whole warehouse text in a single LLM call.
    """
    from engine.prompts import CONTRADICTION_SYSTEM_PROMPT, build_single_call_contradiction_prompt

    cfg = llm_config if isinstance(llm_config, LLMConfig) else load_llm_config(overrides=llm_config if isinstance(llm_config, dict) else None)
    if verbose:
        print(f"[Sherlock LLM] Single-call contradiction extraction ({len(warehouse_text)} chars)...")

    prompt = build_single_call_contradiction_prompt(
        warehouse_text=warehouse_text,
        known_entities=known_entities,
        known_relations=known_relations,
        known_timeline=known_timeline,
    )

    try:
        response_text = call_llm(
            user_prompt=prompt,
            system_prompt=CONTRADICTION_SYSTEM_PROMPT,
            llm_config=cfg,
        )
        raw_list = parse_json_array(response_text)
    except Exception as e:
        logger.warning(f"Single-call contradiction extraction failed: {e}")
        if verbose:
            print(f"[Sherlock LLM] WARNING: Single-call contradictions failed: {e}")
        raw_list = []

    contradictions = []
    for i, item in enumerate(raw_list, 1):
        norm = normalize_contradiction(item, default_source="warehouse.txt", default_chunk="", index=i)
        if norm:
            contradictions.append(norm)

    unique_contradictions = deduplicate_contradictions(contradictions)
    if verbose:
        print(f"[Sherlock LLM] Single-call contradictions done: {len(raw_list)} raw -> {len(unique_contradictions)} unique contradictions")
    return unique_contradictions


def extract_contradictions_auto(
    warehouse_text: str,
    chunks: List[Dict[str, Any]],
    context_window: Optional[int] = None,
    batch_size: int = DEFAULT_BATCH_SIZE,
    model: Optional[str] = None,
    llm_config: Optional[LLMConfig | Dict[str, Any]] = None,
    project_path: Optional[str | Path] = None,
    prefer_batched_when_fits: bool = False,
    known_entities: Optional[List[Dict[str, Any]]] = None,
    known_relations: Optional[List[Dict[str, Any]]] = None,
    known_timeline: Optional[List[Dict[str, Any]]] = None,
    verbose: bool = True,
) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
    """
    Auto-decide strategy based on token window, then extract contradictions.
    Contradictions benefit from global cross-referencing, so single-call when fits is preferred (prefer_batched_when_fits=False).
    """
    cfg = llm_config if isinstance(llm_config, LLMConfig) else load_llm_config(project_path, overrides=llm_config if isinstance(llm_config, dict) else None)
    if model:
        cfg.model = model
    effective_window = context_window if context_window is not None else cfg.context_window
    decision = decide_strategy(
        warehouse_text, chunks, context_window=effective_window, prefer_batched_when_fits=prefer_batched_when_fits
    )
    if verbose:
        print(f"[Sherlock LLM] Contradictions token check: {decision['estimated_tokens']} tokens (window {effective_window}) | fits={decision['fits_in_context']} | strategy={decision['strategy']} | provider={cfg.provider} model={cfg.model}")

    if decision["strategy"] == "single_call":
        contradictions = extract_contradictions_single_call(
            warehouse_text,
            chunks=chunks,
            llm_config=cfg,
            known_entities=known_entities,
            known_relations=known_relations,
            known_timeline=known_timeline,
            verbose=verbose,
        )
    else:
        contradictions = extract_contradictions_batched(
            chunks,
            batch_size=batch_size,
            llm_config=cfg,
            known_entities=known_entities,
            known_relations=known_relations,
            known_timeline=known_timeline,
            verbose=verbose,
        )

    return contradictions, decision


def extract_opinion_auto(
    warehouse_text: str,
    chunks: Optional[List[Dict[str, Any]]] = None,
    known_entities: Optional[List[Dict[str, Any]]] = None,
    known_relations: Optional[List[Dict[str, Any]]] = None,
    known_timeline: Optional[List[Dict[str, Any]]] = None,
    known_contradictions: Optional[List[Dict[str, Any]]] = None,
    model: Optional[str] = None,
    llm_config: Optional[LLMConfig | Dict[str, Any]] = None,
    project_path: Optional[str | Path] = None,
    enable_thinking: bool = True,
    verbose: bool = True,
) -> Dict[str, Any]:
    """
    Synthesize all case context with thinking mode enabled and return Sherlock's Opinion.
    """
    from engine.prompts import OPINION_SYSTEM_PROMPT, build_opinion_prompt

    cfg = llm_config if isinstance(llm_config, LLMConfig) else load_llm_config(project_path, overrides=llm_config if isinstance(llm_config, dict) else None)
    if model:
        cfg.model = model

    if verbose:
        print(f"[Sherlock LLM] Formulating Sherlock's Opinion (thinking_mode={enable_thinking}) | provider={cfg.provider} model={cfg.model}")

    prompt = build_opinion_prompt(
        warehouse_text=warehouse_text,
        entities=known_entities,
        relations=known_relations,
        timeline=known_timeline,
        contradictions=known_contradictions,
    )

    content, reasoning_trace = call_llm_with_thinking(
        user_prompt=prompt,
        system_prompt=OPINION_SYSTEM_PROMPT,
        model=cfg.model,
        llm_config=cfg,
        project_path=project_path,
        enable_thinking=enable_thinking,
    )

    # Parse JSON from content
    parsed: Dict[str, Any] = {}
    fence_match = re.search(r"```(?:json)?\s*(.*?)\s*```", content, re.DOTALL)
    text_to_parse = fence_match.group(1).strip() if fence_match else content.strip()

    try:
        data = json.loads(text_to_parse)
        if isinstance(data, dict):
            parsed = data
    except json.JSONDecodeError:
        obj_match = re.search(r"\{.*\}", text_to_parse, re.DOTALL)
        if obj_match:
            try:
                data = json.loads(obj_match.group(0))
                if isinstance(data, dict):
                    parsed = data
            except json.JSONDecodeError:
                pass

    # If reasoning trace was returned natively or parsed from <think>, prioritize it, else use field from JSON
    final_reasoning = reasoning_trace.strip() if reasoning_trace else str(parsed.get("reasoning_trace", "")).strip()

    result = {
        "case_id": parsed.get("case_id") or "UNKNOWN_CASE",
        "preliminary_analysis": parsed.get("preliminary_analysis") or "",
        "possible_causes": parsed.get("possible_causes") or parsed.get("causes") or [],
        "self_debate_summary": parsed.get("self_debate_summary") or parsed.get("debate") or "",
        "executive_summary": parsed.get("executive_summary") or parsed.get("summary") or "Investigation synthesis completed.",
        "primary_hypothesis": parsed.get("primary_hypothesis") or parsed.get("hypothesis") or "Primary theory formulated based on evidence.",
        "confidence": float(parsed.get("confidence", 0.85)) if parsed.get("confidence") is not None else 0.85,
        "confidence_explanation": parsed.get("confidence_explanation") or "",
        "supporting_evidence": parsed.get("supporting_evidence") or [],
        "flaws_and_counter_evidence": parsed.get("flaws_and_counter_evidence") or parsed.get("counter_evidence") or parsed.get("flaws") or [],
        "alternative_hypotheses": parsed.get("alternative_hypotheses") or [],
        "investigative_leads": parsed.get("investigative_leads") or parsed.get("leads") or [],
        "reasoning_trace": final_reasoning,
    }

    if verbose:
        print(f"[Sherlock LLM] Opinion synthesized: {len(result['supporting_evidence'])} supporting evidence points, {len(result['flaws_and_counter_evidence'])} flaws/counter-points, confidence={result['confidence']}")

    return result


