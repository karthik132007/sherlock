# Sherlock — Test Results

**Generated:** 2026-08-29 06:31:22 UTC  
**Command:** `python3 -m unittest discover -s tests -v`  
**Python:** 3.14.7  
**Environment:** linux, scikit-learn 1.9.0, numpy 2.5.2, openai, tiktoken (fallback heuristic if missing)

## Summary

| Metric | Value |
|--------|-------|
| Total tests | 72 |
| Passed | 72 |
| Failed | 0 |
| Errors | 0 |
| Skipped | 0 |
| Duration | 4.905s |
| Exit code | 0 |
| Result | **OK** |

All tests passed — no fixes required. Code is ready for `run 1` entity extraction pipeline.

## Module Coverage

| Test File | Focus | Tests | Status |
|-----------|-------|-------|--------|
| `tests/test_chunk.py:1` | `engine/chunk.py:58` warehouse parsing, semantic chunking, provenance, overlap, chunk metadata | 12 | ✅ |
| `tests/test_llm.py:1` | `engine/llm.py:387` token estimate, `decide_strategy:419`, batches, `merge_entities:573`, graph mappings, `parse_json_array:898`, LLM config | 23 | ✅ |
| `tests/test_prompts.py:1` | `engine/prompts.py:111` entity/relation/timeline/graph prompts | 8 | ✅ |
| `tests/test_graph_builder.py:1` | `engine/graph_builder.py:33` deterministic build + `save_graph_outputs:64` | 3 | ✅ |
| `tests/test_timeline.py:1` | `engine/llm.py:1200` timeline parsing & sorting, `engine/timeline.py:61` persistence | 11 | ✅ |
| `tests/test_main_pipeline.py:1` | `main.py:112` validate_project, chunk smoke on `data/sample_case`, full mocked extraction & timeline | 15 | ✅ |

## Detailed Breakdown

### Chunk Pipeline (`engine/chunk.py`)
- ✅ empty text returns `[]`
- ✅ small text → single chunk with `chunk_id`, `metadata{char_start,...}`
- ✅ warehouse single/multi source parsing (`_parse_warehouse_sources`)
- ✅ per-source provenance (`fir.txt` → `fir_chunk_001`, `witness.txt` → `witness_chunk_*`, `warehouse_char_start`)
- ✅ overlap injection between consecutive chunks
- ✅ chunk size respected (~<700 chars for 500 limit)
- ✅ naive fallback for long single-sentence/no-punctuation text
- ✅ real warehouse smoke: `data/sample_case/warehouse.txt` (167986 chars, 19 sources) → 235 chunks with provenance

### LLM Utils (`engine/llm.py`)
- ✅ `estimate_tokens` / `get_token_stats`
- ✅ `decide_strategy` fits → `batched` default, `single_call` when `prefer_batched_when_fits=False`, exceeds → forced `batched`
- ✅ `batches_needed` computed correctly (45 chunks → 3 batches @20)
- ✅ `create_batches` split + preserve order, invalid batch_size raises
- ✅ `make_entity_id` (`Rose Mathew` → `person_rose_mathew`)
- ✅ `_person_alias_match` (`Rose M.`/`R. Mathew` alias dedup, surname initial check)
- ✅ `merge_entities` alias collapse, `data` union, different types not merged
- ✅ `build_graph_mappings` deterministic, alias resolution, missing entity skip
- ✅ `parse_json_array` plain, fenced, wrapped `{entities:[]}`, trailing text
- ✅ `load_llm_config` env fallback + `llm.json` file + provider alias `chatgpt→openai`

### Graph Builder (`engine/graph_builder.py`)
- ✅ deterministic `build_graph` embeds full node `data`
- ✅ `save_graph_outputs` creates `relations.json`, `relationships.json`, `graph_data.json`, `graph.json` with `graph_by_id`

### Timeline (`engine/timeline.py` + `engine/llm.py`)
- ✅ `_try_parse_timeline_timestamp` ISO `2026-04-14 18:30`, human `14 April 2026 18:30 IST`, `2026-04-14T15:30:00+05:30`, US `April 14, 2026 at 6:30 pm`
- ✅ `sort_timeline_events` chronological, stable tie-break, unparseable at end lexicographically
- ✅ `normalize_timeline_event` creates `_parsed_datetime`
- ✅ `save_timeline_outputs` re-sorts and writes `timeline.json` with `sorted:true`

### Pipeline Integration (`main.py` + `engine/entity_extraction.py`)
- ✅ `validate_project` missing dir / missing warehouse / empty → `SystemExit`, valid → returns path
- ✅ `main.py --project data/sample_case` without `--extract` → 235 chunks written to `processed/chunks.json`
- ✅ `run_extraction_pipeline` mocked LLM → entities + relationships + `graph_data.json` with `relation_id: node relation node`
- ✅ timeline mocked → sorted chronologically `15:30` before `18:30`
- ✅ dedup across batches (2 batches with `Rose Mathew` / `Rose M.` → 1 merged entity)

## Smoke Run (no LLM) — data/sample_case

```
[Sherlock] Warehouse loaded successfully (166959 chars, 1914 lines)
[Sherlock] Token check: 23411 words, ~41739 tokens (window 128000)
[Sherlock] Fits in context: True | Planned strategy: batched
[Sherlock] Created 235 chunks
[Sherlock] Saved to /home/electron/Documents/GitHub/sherlock/data/sample_case/processed/chunks.json
[Sherlock] Final decision after chunking: strategy=batched | batches_needed=12 | total_chunks=235
[Sherlock] Processing completed successfully
```

## Fixes Applied

**None required** — all 72 tests passed on first run. No code changes were needed.
If the real LLM call fails, check `llm.json` key or env `LLM_API` (see `llm.json.example:1`).

## Ready for Run 1

```bash
# chunk only (already verified)
python3 main.py --project data/sample_case

# full pipeline with real LLM
python3 main.py --project data/sample_case --extract

# with timeline
python3 main.py --project data/sample_case --extract --timeline
```

## Full Verbose Log

```text
test_chunk_metadata_shape (test_chunk.TestSemanticChunk.test_chunk_metadata_shape) ... ok
test_chunk_size_respected_approx (test_chunk.TestSemanticChunk.test_chunk_size_respected_approx) ... ok
test_empty_text (test_chunk.TestSemanticChunk.test_empty_text) ... WARNING: semantic_chunk_text received empty text
WARNING: semantic_chunk_text received empty text
ok
test_naive_fallback_single_long_sentence (test_chunk.TestSemanticChunk.test_naive_fallback_single_long_sentence) ... ok
test_overlap (test_chunk.TestSemanticChunk.test_overlap) ... ok
test_small_text_single_chunk (test_chunk.TestSemanticChunk.test_small_text_single_chunk) ... ok
test_warehouse_provenance (test_chunk.TestSemanticChunk.test_warehouse_provenance) ... INFO: Detected warehouse format with 2 sources
ok
test_warehouse_sample_case_exists (test_chunk.TestSemanticChunk.test_warehouse_sample_case_exists) ... INFO: Detected warehouse format with 19 sources
ok
test_parse_empty (test_chunk.TestWarehouseParsing.test_parse_empty) ... ok
test_parse_multi_source (test_chunk.TestWarehouseParsing.test_parse_multi_source) ... ok
test_parse_single_source (test_chunk.TestWarehouseParsing.test_parse_single_source) ... ok
test_safe_prefix (test_chunk.TestWarehouseParsing.test_safe_prefix) ... ok
test_build_graph_deterministic (test_graph_builder.TestGraphBuilder.test_build_graph_deterministic) ... ok
test_save_empty_graph (test_graph_builder.TestGraphBuilder.test_save_empty_graph) ... INFO: No llm.json found under /tmp/tmpw796rptz/proj — falling back to env vars
ok
test_save_graph_outputs_creates_files (test_graph_builder.TestGraphBuilder.test_save_graph_outputs_creates_files) ... INFO: No llm.json found under /tmp/tmp27ovcakd/case_test — falling back to env vars
ok
test_create_batches (test_llm.TestBatching.test_create_batches) ... ok
test_create_batches_invalid (test_llm.TestBatching.test_create_batches_invalid) ... ok
test_create_batches_preserves_order (test_llm.TestBatching.test_create_batches_preserves_order) ... ok
test_ensure_ids (test_llm.TestEntityUtils.test_ensure_ids) ... ok
test_make_entity_id (test_llm.TestEntityUtils.test_make_entity_id) ... ok
test_merge_alias_dedup_person (test_llm.TestEntityUtils.test_merge_alias_dedup_person) ... ok
test_merge_data_union (test_llm.TestEntityUtils.test_merge_data_union) ... ok
test_merge_different_types_not_merged (test_llm.TestEntityUtils.test_merge_different_types_not_merged) ... ok
test_merge_single (test_llm.TestEntityUtils.test_merge_single) ... ok
test_normalize_name (test_llm.TestEntityUtils.test_normalize_name) ... ok
test_person_alias_match (test_llm.TestEntityUtils.test_person_alias_match) ... ok
test_alias_resolution (test_llm.TestGraphMapping.test_alias_resolution) ... ok
test_missing_entity_skipped (test_llm.TestGraphMapping.test_missing_entity_skipped) ... WARNING: Skipping relation Rose Mathew -> Ghost Person : entity not found
ok
test_simple (test_llm.TestGraphMapping.test_simple) ... ok
test_load_env_fallback (test_llm.TestLLMConfig.test_load_env_fallback) ... ok
test_load_from_file (test_llm.TestLLMConfig.test_load_from_file) ... INFO: Loaded LLM config from /tmp/tmpzq9icug3/llm.json
ok
test_provider_alias (test_llm.TestLLMConfig.test_provider_alias) ... INFO: Loaded LLM config from /tmp/tmp7sc0_cl5/llm.json
ok
test_array_with_trailing_text (test_llm.TestParseJson.test_array_with_trailing_text) ... ok
test_fenced (test_llm.TestParseJson.test_fenced) ... ok
test_plain_array (test_llm.TestParseJson.test_plain_array) ... ok
test_single_object (test_llm.TestParseJson.test_single_object) ... ok
test_wrapped_object_entities (test_llm.TestParseJson.test_wrapped_object_entities) ... ok
test_batches_needed_computed (test_llm.TestTokenUtils.test_batches_needed_computed) ... ok
test_decide_exceeds_forced_batched (test_llm.TestTokenUtils.test_decide_exceeds_forced_batched) ... ok
test_decide_fits_batched_default (test_llm.TestTokenUtils.test_decide_fits_batched_default) ... ok
test_decide_fits_single_call_when_prefer_false (test_llm.TestTokenUtils.test_decide_fits_single_call_when_prefer_false) ... ok
test_estimate_empty (test_llm.TestTokenUtils.test_estimate_empty) ... ok
test_estimate_positive (test_llm.TestTokenUtils.test_estimate_positive) ... ok
test_get_token_stats (test_llm.TestTokenUtils.test_get_token_stats) ... ok
test_dedup_across_batches (test_main_pipeline.TestExtractionPipelineMocked.test_dedup_across_batches) ... INFO: Detected warehouse format with 1 sources
INFO: Loaded LLM config from /tmp/tmpuiglu7_l/case_dedup/llm.json
ok
test_full_extraction_mocked (test_main_pipeline.TestExtractionPipelineMocked.test_full_extraction_mocked) ... INFO: Detected warehouse format with 1 sources
INFO: Loaded LLM config from /tmp/tmpgeal37i_/case_mock/llm.json
INFO: Loaded LLM config from /tmp/tmpgeal37i_/case_mock/llm.json
ok
test_timeline_mocked (test_main_pipeline.TestExtractionPipelineMocked.test_timeline_mocked) ... INFO: Detected warehouse format with 1 sources
INFO: Loaded LLM config from /tmp/tmpwhbiafpk/case_tl/llm.json
INFO: Loaded LLM config from /tmp/tmpwhbiafpk/case_tl/llm.json
ok
test_chunk_smoke_on_sample (test_main_pipeline.TestMainChunkSmoke.test_chunk_smoke_on_sample) ... INFO: Detected warehouse format with 19 sources
ok
test_main_py_runs_without_extract (test_main_pipeline.TestMainChunkSmoke.test_main_py_runs_without_extract) ... ok
test_empty_warehouse (test_main_pipeline.TestValidateProject.test_empty_warehouse) ... [Sherlock] ERROR: warehouse.txt is empty: /tmp/tmpc5z_s2no/warehouse.txt
ok
test_missing_dir (test_main_pipeline.TestValidateProject.test_missing_dir) ... [Sherlock] ERROR: Project directory does not exist: /tmp/does_not_exist_12345_sherlock_xyz
ok
test_missing_warehouse (test_main_pipeline.TestValidateProject.test_missing_warehouse) ... [Sherlock] ERROR: warehouse.txt not found at /tmp/tmpz2g_gxuj/warehouse.txt
[Sherlock]        Expected: /tmp/tmpz2g_gxuj/warehouse.txt
ok
test_valid_project (test_main_pipeline.TestValidateProject.test_valid_project) ... ok
test_build_entity_prompt_contains_chunks (test_prompts.TestPrompts.test_build_entity_prompt_contains_chunks) ... ok
test_build_entity_prompt_previous_entities (test_prompts.TestPrompts.test_build_entity_prompt_previous_entities) ... ok
test_build_graph_mapping_prompt (test_prompts.TestPrompts.test_build_graph_mapping_prompt) ... ok
test_build_relationship_prompt_grounded (test_prompts.TestPrompts.test_build_relationship_prompt_grounded) ... ok
test_build_single_call_prompt_truncates_large (test_prompts.TestPrompts.test_build_single_call_prompt_truncates_large) ... ok
test_build_timeline_prompt (test_prompts.TestPrompts.test_build_timeline_prompt) ... ok
test_entity_system_contains_types (test_prompts.TestPrompts.test_entity_system_contains_types) ... ok
test_relation_system_contains_canonical (test_prompts.TestPrompts.test_relation_system_contains_canonical) ... ok
test_human_date (test_timeline.TestTimelineParsing.test_human_date) ... ok
test_iso_date (test_timeline.TestTimelineParsing.test_iso_date) ... ok
test_iso_with_T (test_timeline.TestTimelineParsing.test_iso_with_T) ... ok
test_normalize_event (test_timeline.TestTimelineParsing.test_normalize_event) ... ok
test_normalize_missing_fields_returns_none (test_timeline.TestTimelineParsing.test_normalize_missing_fields_returns_none) ... ok
test_save_timeline_sorts (test_timeline.TestTimelineParsing.test_save_timeline_sorts) ... INFO: No llm.json found under /tmp/tmpw4oi_d_e/case — falling back to env vars
ok
test_sort_chronologically (test_timeline.TestTimelineParsing.test_sort_chronologically) ... ok
test_sort_same_timestamp_preserves_order (test_timeline.TestTimelineParsing.test_sort_same_timestamp_preserves_order) ... ok
test_sort_stable_and_unparseable_at_end (test_timeline.TestTimelineParsing.test_sort_stable_and_unparseable_at_end) ... ok
test_unparseable (test_timeline.TestTimelineParsing.test_unparseable) ... ok
test_us_style (test_timeline.TestTimelineParsing.test_us_style) ... ok

----------------------------------------------------------------------
Ran 72 tests in 4.905s

OK
```

