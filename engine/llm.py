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
        "base_url": "https://api.deepseek.com/v1",
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
        "base_url": "http://localhost:11434/v1",
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
) -> Dict[str, Any]:
    """
    Decide whether to send whole warehouse or chunked batches to LLM.
    """
    stats = get_token_stats(warehouse_text)
    est = stats["estimated_tokens"]
    fits = est <= context_window

    if fits:
        if prefer_batched_when_fits:
            strategy = "batched"
            reason = f"Fits in window ({est} <= {context_window}), using batched mode (20 chunks/batch) with dedup context to avoid alias duplicates."
        else:
            strategy = "single_call"
            reason = f"Fits in window ({est} <= {context_window}), sending whole warehouse in one LLM call (no chunking needed)."
    else:
        strategy = "batched"
        reason = f"Exceeds window ({est} > {context_window}), MUST use batched chunking (single call would overflow). Total chunks={len(chunks) if chunks else 'unknown'}."

    batches_needed = None
    if chunks is not None:
        import math

        batches_needed = math.ceil(len(chunks) / DEFAULT_BATCH_SIZE) if strategy == "batched" else 1

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

        kwargs: Dict[str, Any] = {"api_key": cfg.api_key or "sk-missing-key-for-testing"}
        if cfg.base_url:
            kwargs["base_url"] = cfg.base_url
        if cfg.extra_headers:
            kwargs["default_headers"] = cfg.extra_headers
        # timeout handled per-call, not client
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
    timeout: int = 60,
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


def parse_json_array(text: str) -> List[Any]:
    """
    Robustly parse JSON array from LLM output.
    Handles markdown fences, leading/trailing text, object-wrapping.
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
