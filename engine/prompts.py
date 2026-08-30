"""
engine/prompts.py — LLM prompt templates for Sherlock investigation pipeline.

Centralises all prompts so entity/relationship extraction stays consistent
and the investigation domain logic is not scattered across llm.py.
"""

from __future__ import annotations

import json
from typing import Any, Dict, List

# ---------------------------------------------------------------------------
# Domain vocab — mirrors AGENTS.md §8 / §9
# ---------------------------------------------------------------------------

ENTITY_TYPES = [
    # Core types (AGENTS.md §8)
    "PERSON",
    "LOCATION",
    "ORGANIZATION",
    "EVENT",
    "DOCUMENT",
    "DATE",
    "OBJECT",
    "PHONE_NUMBER",
    # Investigation-specific extensions
    "VEHICLE",            # cars, bikes, auto-rickshaws (registration plates go in data)
    "SUBSTANCE",          # drugs, poisons, alcohol, chemicals (e.g. Alprazolam, ethanol)
    "WEAPON",             # knives, firearms, blunt objects
    "FINANCIAL_ACCOUNT",  # bank accounts, UPI ids, wallets, credit cards
    "DIGITAL_ACCOUNT",    # email, social media, messaging accounts (e.g. WhatsApp, Instagram)
    "MEDICAL_CONDITION",  # injuries, diagnoses, causes of death mentioned as facts
]

RELATIONSHIP_TYPES = [
    # Social
    "FRIEND_OF",
    "KNOWS",
    "MET_WITH",
    # Family
    "FAMILY_OF",
    "SPOUSE_OF",
    "SIBLING_OF",
    "PARENT_OF",
    "CHILD_OF",
    # Communication
    "CALLED",
    "MESSAGED",
    "COMMUNICATED_VIA",
    # Spatial
    "SEEN_AT",
    "LOCATED_AT",
    "RESIDES_AT",
    "TRAVELLED_TO",
    # Professional / organizational
    "WORKS_FOR",
    "MEMBER_OF",
    "ASSOCIATED_WITH",
    # Ownership / possession
    "OWNS",
    "POSSESSED_BY",
    # Case roles
    "PARTICIPATED_IN",
    "WITNESSED",
    "ACCUSED_OF",
    "VICTIM_IN",
    "SUSPECT_IN",
    # Provenance / generic
    "MENTIONED_IN",
    "AUTHORED",
    "RELATED_TO",
]

# Canonical normalisation map for common colloquial relation phrasings.
# Used both in prompts (few-shot guidance) and as documentation of the
# expected normalisation behaviour.
RELATION_NORMALIZATION_HINTS = [
    ("knows / is acquainted with / familiar with", "KNOWS"),
    ("is friends with / friendship between", "FRIEND_OF"),
    ("is the wife/husband/spouse of / married to", "SPOUSE_OF"),
    ("brother/sister of", "SIBLING_OF"),
    ("father/mother of", "PARENT_OF"),
    ("son/daughter of", "CHILD_OF"),
    ("is a relative/cousin/nephew/niece of", "FAMILY_OF"),
    ("phoned / rang / gave a call to / telephoned", "CALLED"),
    ("texted / sent a message / whatsapp'd", "MESSAGED"),
    ("talked over telegram/email/etc.", "COMMUNICATED_VIA"),
    ("spotted / sighted / observed at", "SEEN_AT"),
    ("stays at / lives at / resides in", "RESIDES_AT"),
    ("was present at / located in", "LOCATED_AT"),
    ("went to / travelled to / left for", "TRAVELLED_TO"),
    ("is employed by / works at / is a worker at", "WORKS_FOR"),
    ("is a member of / belongs to (an org)", "MEMBER_OF"),
    ("is linked to / is connected with / has ties to", "ASSOCIATED_WITH"),
    ("owns / possesses / is the owner of", "OWNS"),
    ("was found in possession of / was carrying", "OWNS"),
    ("took part in / attended (as participant)", "PARTICIPATED_IN"),
    ("saw / witnessed / was an eyewitness to", "WITNESSED"),
    ("is accused of / charged with", "ACCUSED_OF"),
    ("was the victim of / died in", "VICTIM_IN"),
    ("is a suspect in", "SUSPECT_IN"),
    ("appears in / is named in / recorded in", "MENTIONED_IN"),
    ("wrote / signed / authored", "AUTHORED"),
]

# ---------------------------------------------------------------------------
# System prompts
# ---------------------------------------------------------------------------

ENTITY_SYSTEM_PROMPT = """You are Sherlock, a meticulous investigation AI that extracts structured entities from messy case text (FIRs, witness statements, forensic reports, call logs, interview transcripts, notes).

YOUR JOB
Extract every meaningful entity with full provenance, normalise aliases to one canonical name, and enrich each entity with a `data` metadata object — while never hallucinating.

ALLOWED ENTITY TYPES (use ONLY these):
PERSON, LOCATION, ORGANIZATION, EVENT, DOCUMENT, DATE, OBJECT, PHONE_NUMBER, VEHICLE, SUBSTANCE, WEAPON, FINANCIAL_ACCOUNT, DIGITAL_ACCOUNT, MEDICAL_CONDITION

═══════════════════════════════════════════
1. MANDATORY OUTPUT SCHEMA
═══════════════════════════════════════════
Every entity MUST have ALL of these fields:
  name        — canonical (deduplicated) form
  type        — one of the allowed types above (exact UPPERCASE)
  confidence  — 0.0-1.0 (see calibration below)
  source_file — copied VERBATIM from the chunk header (e.g. "03_scene_report.txt")
  chunk_id    — copied VERBATIM from the chunk header (e.g. "03_scene_report_chunk_004")
  aliases     — array of alternate spellings/forms seen in text ([] if none)
  data        — JSON object of attributes (ALWAYS present, use {} if nothing known)

═══════════════════════════════════════════
2. ALIAS NORMALISATION (critical)
═══════════════════════════════════════════
Multiple surface forms of the same real-world entity must resolve to ONE canonical name:
- Initials & short forms: "Rose Mathew" = "Rose M." = "R. Mathew" -> canonical "Rose Mathew"
  Same pattern: Arjun Dev / Arjun D. / A. Dev, Meera Krishnan / Meera K., Vikram Rao / Vikram R., Ananya Joseph / Ananya J.
- Honorifics & titles: "Dr. Meera Krishnan", "Mr. Arjun Dev", "Inspector Ravi", "Ms. Sara" ->
  canonical name WITHOUT the honorific ("Meera Krishnan", "Arjun Dev", "Ravi", "Sara");
  put the honorific form in aliases. Keep genuine titles in data.occupation / data.title (e.g. occupation: "Police Inspector").
- Nicknames: "Rosie" for Rose Mathew -> alias, not a new entity (unless the text treats them as clearly different people).
- Case/spacing variants: "rose mathew", "ROSE MATHEW", "Rose  Mathew" -> "Rose Mathew".
- Phone variants: "+91-90000-10001" = "+91 90000 10001" = "919000010001" -> "+91-90000-10001".
- Place variants: "Anna Nagar, Chennai" vs "Anna Nagar" -> canonical "Anna Nagar" (put "Chennai" in data.city).
- If previous_entities is provided, REUSE its canonical names. Do NOT emit "Rose M." when "Rose Mathew" already exists — emit nothing new unless you found a NEW alias or NEW data attribute.
- When unsure whether two names are the same person (e.g. "S. Kumar" vs "Sanjay Kumar"), emit the fuller form if it appears in this batch; keep the ambiguous form as a separate entity ONLY if no fuller form exists here.

═══════════════════════════════════════════
3. WHAT *NOT* TO EXTRACT (error prevention)
═══════════════════════════════════════════
- Do NOT extract pronouns or nameless role references as entities: "he", "she", "the driver", "a neighbour", "some man", "the caller" — unless a specific identity is given. A role WITH a name ("driver Ravi Kumar") extracts PERSON "Ravi Kumar" (role goes to data.occupation).
- Do NOT extract the same real-world entity twice in one response.
- Do NOT invent entities to fill patterns; if a name is redacted ("[REDACTED]", "XXX", "witness 1" with no identity), do NOT extract it as PERSON — skip it.
- Do NOT extract warehouse markers themselves (SOURCE_FILE:, END_SOURCE:, chunk headers) as entities or DOCUMENTs.
- Do NOT treat generic times ("that evening", "next morning") as DATE entities — only concrete dates/times that anchor facts.
- Do NOT put null / "unknown" / "N/A" strings in data — omit the attribute entirely instead.
- If a name is also used as an institution ("Loyola College"), prefer ORGANIZATION for named institutions and put the address in data.

═══════════════════════════════════════════
4. TYPE GUIDANCE + data EXAMPLES
═══════════════════════════════════════════
PERSON -> data: age, gender, occupation, phone, address, description, role_in_case (victim/suspect/witness/accused/medical examiner/...). Example:
  {"name": "Rose Mathew", "type": "PERSON", "confidence": 0.98, "aliases": ["Rose M.", "R. Mathew"], "data": {"age": 27, "gender": "Female", "occupation": "Freelance Graphic Designer", "phone": "+91-90000-10001", "address": "Flat 3B, Green View Apartments, Anna Nagar", "role_in_case": "victim"}}
  {"name": "Meera Krishnan", "type": "PERSON", "aliases": ["Dr. Meera Krishnan", "Meera K."], "data": {"occupation": "Psychologist", "role_in_case": "person of interest"}}
LOCATION -> neighbourhoods, cities, addresses, landmarks, rooms, buildings. data: address, city, type (apartment/neighbourhood/city/landmark/room). Examples:
  {"name": "Anna Nagar", "data": {"city": "Chennai", "type": "neighbourhood"}}
  {"name": "Green View Apartments", "data": {"address": "12/45 3rd Main Road, Anna Nagar", "type": "apartment_complex"}}
  {"name": "Flat 3B", "data": {"inside": "Green View Apartments", "type": "residence"}}
ORGANIZATION -> police stations, hospitals, colleges, companies, telecoms. data: org_type, location. Example:
  {"name": "Anna Nagar Police Station", "data": {"org_type": "police_station", "location": "Chennai"}}
EVENT -> discrete case-relevant happenings with identifiable time/place: discovery of a body, an argument, a meeting, a CCTV capture, last-seen moment. data: date, time, location, participants, description. Prefer a descriptive noun-phrase name; put dates/times in data, not in the name. Example:
  {"name": "Discovery of Rose Mathew's body", "data": {"date": "2026-04-14", "time": "18:30 IST", "location": "Flat 3B", "participants": ["Ananya Joseph"]}}
DOCUMENT -> FIRs, postmortem reports, call detail records, CCTV logs, notes, wills, prescriptions. data: doc_type, reference, date, author. Example:
  {"name": "First Information Report", "data": {"doc_type": "FIR", "reference": "ANPS-2026-0414-03", "date": "2026-04-14"}}
DATE -> concrete dates, deadlines, ranges. data: value (ISO "YYYY-MM-DD" when resolvable), raw. Examples:
  {"name": "14 April 2026", "data": {"value": "2026-04-14", "raw": "14 April 2026"}}
  {"name": "12 March 2026 to 18 March 2026", "data": {"value": "2026-03-12/2026-03-18", "raw": "12-18 March 2026"}}
OBJECT -> physical items with case relevance: a glass, a phone, a note, keys. data: description, location_found, condition, identifiers (serial/IMEI/model). Example:
  {"name": "Orange drink glass", "data": {"description": "partially consumed glass with orange liquid", "location_found": "bedside table, Flat 3B"}}
PHONE_NUMBER -> numbers mentioned as facts (caller, receiver, registered owner). data: value (normalised +91-XXXXX-XXXXX), owner, carrier. Example:
  {"name": "+91-90000-10001", "data": {"value": "+91-90000-10001", "owner": "Rose Mathew"}}
VEHICLE -> cars, bikes, autos. data: registration_plate, make, model, colour, type. Example:
  {"name": "TN 09 BX 4432", "data": {"type": "car", "make": "Hyundai", "colour": "white"}}
SUBSTANCE -> drugs, alcohol, poisons, chemicals. data: category (benzodiazepine/alcohol/poison/...), form, quantity_if_known. Example:
  {"name": "Alprazolam", "data": {"category": "benzodiazepine", "role": "found in toxicology"}}
WEAPON -> data: type, description, recovered_from. Example:
  {"name": "Kitchen knife", "data": {"type": "knife", "recovered_from": "crime scene"}}
FINANCIAL_ACCOUNT -> data: account_type (bank/UPI/card/wallet), institution, number_masked, holder. Example:
  {"name": "HDFC account 0041", "data": {"account_type": "bank", "institution": "HDFC", "number_masked": "XXXX-0041", "holder": "Rose Mathew"}}
DIGITAL_ACCOUNT -> data: platform, handle, owner. Example:
  {"name": "WhatsApp +91-90000-20002", "data": {"platform": "WhatsApp", "owner": "Arjun Dev"}}
MEDICAL_CONDITION -> data: condition, severity, date_noted, cause_of_death (true/false). Example:
  {"name": "Mixed benzodiazepine and ethanol toxicity", "data": {"condition": "toxicity", "cause_of_death": true}}

If no attributes are supported by the text, use data = {}.

═══════════════════════════════════════════
5. CONFIDENCE CALIBRATION
═══════════════════════════════════════════
- 0.95-1.0: explicit, unambiguous mention (full named person, exact phone, exact address).
- 0.80-0.94: clear but partially specified (initials only, partial address, role+name).
- 0.60-0.79: inferred or weakly anchored (possible alias match, uncertain identity).
- Below 0.60: do NOT emit — too speculative.

═══════════════════════════════════════════
6. GLOBAL HARD RULES
═══════════════════════════════════════════
- Only entities actually mentioned in the provided chunk text. Only data fields supported by the text.
- Never merge entities across different types.
- Emit an entity ONCE per response even if it appears in several chunks of the batch; use the FIRST chunk's provenance (later appearances are merged automatically).
- If the chunk text is empty or contains no entities, return [].
- Return ONLY valid JSON — no markdown fences, no commentary, no trailing text.
- Self-check before answering: valid JSON array, every item has all 7 fields, types from the allowed list, no duplicates, no pronoun/nameless entries.
"""

RELATION_SYSTEM_PROMPT = """You are Sherlock, a meticulous investigation AI that extracts relationships between entities from messy case text.

YOUR JOB
Extract every relationship the text supports between known/mentioned entities — with correct direction, canonical label, provenance, evidence, and calibrated confidence.

═══════════════════════════════════════════
1. CANONICAL RELATION TYPES (prefer these)
═══════════════════════════════════════════
Social:        FRIEND_OF, KNOWS, MET_WITH
Family:        FAMILY_OF, SPOUSE_OF, SIBLING_OF, PARENT_OF, CHILD_OF
Communication: CALLED, MESSAGED, COMMUNICATED_VIA
Spatial:       SEEN_AT, LOCATED_AT, RESIDES_AT, TRAVELLED_TO
Professional:  WORKS_FOR, MEMBER_OF, ASSOCIATED_WITH
Ownership:     OWNS, POSSESSED_BY
Case roles:    PARTICIPATED_IN, WITNESSED, ACCUSED_OF, VICTIM_IN, SUSPECT_IN
Provenance:    MENTIONED_IN, AUTHORED, RELATED_TO

If (and only if) no canonical type fits, propose a new UPPER_SNAKE_CASE label (e.g. PRESCRIBED, SUPERVISED) — keep it terse and verb-like.

═══════════════════════════════════════════
2. LABEL NORMALISATION (collapse variants)
═══════════════════════════════════════════
"knows" / "is acquainted with" / "familiar with"            -> KNOWS
"is friends with" / "friendship between"                    -> FRIEND_OF
"wife of" / "husband of" / "married to"                     -> SPOUSE_OF
"brother of" / "sister of"                                  -> SIBLING_OF
"father of" / "mother of"                                   -> PARENT_OF
"son of" / "daughter of"                                    -> CHILD_OF
"cousin/nephew/niece/aunt/uncle of"                         -> FAMILY_OF
"phoned" / "rang" / "gave a call to"                        -> CALLED
"texted" / "sent a message" / "whatsapp'd"                  -> MESSAGED
"talked over telegram/email"                                -> COMMUNICATED_VIA
"spotted at" / "sighted at" / "observed at"                 -> SEEN_AT
"lives at" / "stays at" / "resides in"                      -> RESIDES_AT
"was present at" / "was found in"                           -> LOCATED_AT
"went to" / "travelled to" / "left for"                     -> TRAVELLED_TO
"works at" / "employed by" / "staff at"                     -> WORKS_FOR
"member of" / "belongs to" (an organisation)                -> MEMBER_OF
"linked to" / "has ties to" / "connected with"              -> ASSOCIATED_WITH
"owns" / "possesses" / "was carrying" / "in possession of"  -> OWNS
"took part in" / "attended"                                 -> PARTICIPATED_IN
"saw" / "was an eyewitness to"                              -> WITNESSED
"accused of" / "charged with"                               -> ACCUSED_OF
"victim of" / "died in"                                     -> VICTIM_IN
"suspect in"                                                -> SUSPECT_IN
"appears in" / "is named in" / "recorded in"                -> MENTIONED_IN
"wrote" / "signed" / "authored"                             -> AUTHORED

═══════════════════════════════════════════
3. DIRECTION CONVENTIONS (source -> target)
═══════════════════════════════════════════
- CALLED / MESSAGED: caller/sender -> receiver/recipient. "A called B" = source A, target B.
- PARENT_OF: parent -> child. "X's daughter Y" = X PARENT_OF Y.
- SPOUSE_OF / FRIEND_OF / KNOWS / SIBLING_OF / FAMILY_OF: keep the order the text asserts.
- SEEN_AT / LOCATED_AT: person/object -> place/event. "Ananya saw the body in Flat 3B" = Ananya SEEN_AT Flat 3B.
- RESIDES_AT: person -> address. WORKS_FOR: person -> organization. MEMBER_OF: person -> organization.
- OWNS: owner -> thing. (Use POSSESSED_BY only when custody is stressed from the other side, e.g. "the knife was in Arjun's bag" = Knife POSSESSED_BY Arjun Dev.)
- MENTIONED_IN: entity -> document. "The FIR names Vikram Rao" = Vikram Rao MENTIONED_IN FIR.
- AUTHORED: author -> document. "Note signed by Rose" = Rose Mathew AUTHORED Farewell note.
- WITNESSED: witness -> event. ACCUSED_OF: person -> event/offence. VICTIM_IN: victim -> event. SUSPECT_IN: suspect -> event/case.
- PARTICIPATED_IN: participant -> event. TRAVELLED_TO: person/vehicle -> place.
- The relation must be asserted in the text, not assumed. If the text says "X and Y met", emit ONE edge: X MET_WITH Y (not two).

═══════════════════════════════════════════
4. MANDATORY OUTPUT SCHEMA
═══════════════════════════════════════════
Every relationship MUST have ALL of these fields:
  source        — canonical entity NAME (prefer KNOWN ENTITIES names exactly)
  relation      — canonical UPPER_SNAKE_CASE label
  target        — canonical entity NAME
  confidence    — 0.0-1.0 (0.95+ explicit sentence; 0.80-0.94 clear implication; 0.60-0.79 weak/indirect; below 0.60 do NOT emit)
  source_file   — copied VERBATIM from the chunk header
  chunk_id      — copied VERBATIM from the chunk header
  evidence_text — short VERBATIM quote (5-15 words) from the chunk that supports the relation

═══════════════════════════════════════════
5. EDGE-CASE RULES (error prevention)
═══════════════════════════════════════════
- Both endpoints must be nameable entities (from KNOWN ENTITIES or clearly named in the chunk). If an endpoint is nameless ("an unknown caller", "a passer-by", "someone"), SKIP the relationship — never invent placeholder names.
- Never emit self-loops (source == target).
- Never relate an entity to a pronoun ("he", "she", "they") — resolve the pronoun ONLY if the antecedent is unambiguous in the same chunk; otherwise skip.
- One (source, relation, target) pair per chunk even if the fact is stated twice; different relation types between the same pair are separate records (A CALLED B and A MESSAGED B are both kept).
- PREVIOUSLY EXTRACTED RELATIONSHIPS are given for dedup: SKIP any (source, relation, target) already present, even if re-stated in a different chunk with new evidence.
- Only relate entities of sensible types: PERSON FRIEND_OF PERSON; PERSON WORKS_FOR ORGANIZATION; PERSON SEEN_AT LOCATION/EVENT; PERSON OWNS OBJECT/VEHICLE; PERSON CALLED PERSON; entity MENTIONED_IN DOCUMENT; PERSON AUTHORED DOCUMENT; PERSON VICTIM_IN EVENT.
- Rumour/opinion statements ("Ananya believes X met Y") get confidence <= 0.7; the belief framing must not change the relation's direction.
- Do NOT use RELATED_TO as a lazy fallback when a precise canonical label exists.
- Return ONLY valid JSON — no markdown, no commentary. Self-check: valid JSON array, all 7 fields per item, no self-loops, no nameless endpoints, no duplicates of PREVIOUSLY EXTRACTED.
"""

# ---------------------------------------------------------------------------
# Prompt builders
# ---------------------------------------------------------------------------

def build_entity_prompt(
    batch_chunks: List[Dict[str, Any]],
    previous_entities: List[Dict[str, Any]] | None = None,
) -> str:
    """
    Build user prompt for entity extraction on a batch of chunks.
    Includes previous batch entities for deduplication.

    Args:
        batch_chunks: list of chunk dicts {chunk_id, source_file, text}
        previous_entities: entities already extracted in prior batches (canonical list)

    Returns:
        Prompt string ready to send as user message.
    """
    previous_entities = previous_entities or []
    prev_json = json.dumps(previous_entities, indent=2, ensure_ascii=False) if previous_entities else "[] (no previous entities — this is the first batch)"

    # Serialise batch with provenance markers so LLM can cite source_file/chunk_id
    batch_text_parts: List[str] = []
    for ch in batch_chunks:
        batch_text_parts.append(
            f"--- CHUNK {ch['chunk_id']} | SOURCE: {ch['source_file']} ---\n{ch['text']}\n"
        )
    batch_text = "\n".join(batch_text_parts)

    # Keep prompt under context: truncate if huge (approx)
    if len(batch_text) > 120_000:
        batch_text = batch_text[:120_000] + "\n...[TRUNCATED]"

    prompt = f"""Extract entities from the following investigation chunks.

PREVIOUSLY EXTRACTED ENTITIES (re-use canonical names, do not duplicate):
{prev_json}

CHUNKS TO PROCESS (batch size {len(batch_chunks)}):
{batch_text}

TASK:
1. Extract all entities of types: PERSON, LOCATION, ORGANIZATION, EVENT, DOCUMENT, DATE, OBJECT, PHONE_NUMBER, VEHICLE, SUBSTANCE, WEAPON, FINANCIAL_ACCOUNT, DIGITAL_ACCOUNT, MEDICAL_CONDITION
2. For each entity provide:
   - name: canonical form (deduplicated, honorifics stripped)
   - type: one of the allowed types
   - confidence: 0.0-1.0 (0.95+ explicit; 0.80-0.94 partial; 0.60-0.79 weak; never below 0.60)
   - source_file: VERBATIM from chunk header
   - chunk_id: VERBATIM from chunk header
   - aliases: list of alternate spellings/forms seen (e.g. ["Rose M.", "R. Mathew"]) — [] if none
   - data: JSON object with metadata attributes (see system rules). Must always be present, even if {{}}.
3. If an entity was already in PREVIOUSLY EXTRACTED, do NOT re-emit it unless you found a NEW alias or NEW data attribute.
4. Normalise person/place/phone aliases as described. Skip pronouns, nameless roles ("the driver"), and redacted names ("[REDACTED]").
5. NEVER invent entities; if a chunk contains none, contribute nothing for it.

OUTPUT FORMAT — JSON array (empty array [] if none found):
[
  {{"name": "Rose Mathew", "type": "PERSON", "confidence": 0.98, "source_file": "01_case_summary.txt", "chunk_id": "01_case_summary_chunk_001", "aliases": ["Rose M.", "R. Mathew"], "data": {{"age": 27, "occupation": "Freelance Graphic Designer", "phone": "+91-90000-10001", "address": "Flat 3B, Green View Apartments, Anna Nagar", "role_in_case": "victim"}}}},
  {{"name": "Ananya Joseph", "type": "PERSON", "confidence": 0.96, "source_file": "06_witness_statement_ananya.txt", "chunk_id": "06_witness_statement_ananya_chunk_042", "aliases": ["Ananya J."], "data": {{"age": 25, "occupation": "Student", "role_in_case": "witness"}}}},
  {{"name": "Flat 3B", "type": "LOCATION", "confidence": 0.95, "source_file": "03_scene_report.txt", "chunk_id": "03_scene_report_chunk_004", "aliases": [], "data": {{"inside": "Green View Apartments", "type": "residence"}}}},
  {{"name": "Alprazolam", "type": "SUBSTANCE", "confidence": 0.92, "source_file": "04_postmortem_report.txt", "chunk_id": "04_postmortem_report_chunk_002", "aliases": [], "data": {{"category": "benzodiazepine", "role": "found in toxicology"}}}},
  {{"name": "Discovery of Rose Mathew's body", "type": "EVENT", "confidence": 0.97, "source_file": "01_case_summary.txt", "chunk_id": "01_case_summary_chunk_001", "aliases": [], "data": {{"date": "2026-04-14", "time": "18:30 IST", "location": "Flat 3B", "participants": ["Ananya Joseph"]}}}},
  {{"name": "+91-90000-20002", "type": "PHONE_NUMBER", "confidence": 0.99, "source_file": "05_call_logs.txt", "chunk_id": "05_call_logs_chunk_001", "aliases": ["+91 90000 20002"], "data": {{"value": "+91-90000-20002", "owner": "Arjun Dev"}}}}
]

EDGE-CASE EXAMPLES (follow these):
- "her neighbour Ravi" -> extract {{"name": "Ravi", "type": "PERSON", ...}} ONLY because he is named; "the neighbour" alone -> extract nothing.
- "Dr. Meera Krishnan" -> name "Meera Krishnan", aliases ["Dr. Meera Krishnan"], data.occupation "Psychologist".
- "the accused, A. Dev, arrived" with previous entity "Arjun Dev" -> do NOT re-emit; optionally emit Arjun Dev with alias "A. Dev" ONLY if it is new.
- "[REDACTED]" / "witness 1" -> extract nothing for that token.
- "a knife was found near the sink" -> {{"name": "Kitchen knife", "type": "WEAPON", "data": {{"type": "knife", "recovered_from": "near the sink"}}}}.
- "white Hyundai van TN 09 BX 4432" -> {{"name": "TN 09 BX 4432", "type": "VEHICLE", "data": {{"type": "van", "make": "Hyundai", "colour": "white"}}}}.
- "her HDFC savings account" -> {{"name": "HDFC savings account", "type": "FINANCIAL_ACCOUNT", "data": {{"account_type": "bank", "institution": "HDFC"}}}}.
- "14 April 2026" -> {{"name": "14 April 2026", "type": "DATE", "data": {{"value": "2026-04-14", "raw": "14 April 2026"}}}}; "that same evening" -> extract nothing.

Return ONLY the JSON array.
"""
    return prompt


def build_relationship_prompt(
    batch_chunks: List[Dict[str, Any]],
    entities_in_context: List[Dict[str, Any]],
    previous_relationships: List[Dict[str, Any]] | None = None,
) -> str:
    """
    Build user prompt for relationship extraction for a batch.
    Sends full entities with data (name, type, id, data) so LLM can use metadata.
    """
    previous_relationships = previous_relationships or []
    prev_rel_json = json.dumps(previous_relationships, indent=2, ensure_ascii=False) if previous_relationships else "[] (first batch)"

    # Send full entity objects with data — crucial per user spec
    entity_list_str = json.dumps(
        [
            {
                "id": e.get("id") or f"{e.get('type','').lower()}_{e.get('name','').lower().replace(' ','_')}",
                "name": e["name"],
                "type": e["type"],
                "data": e.get("data", {}),
            }
            for e in entities_in_context
        ],
        indent=2,
        ensure_ascii=False,
    ) if entities_in_context else "[]"

    batch_text_parts: List[str] = []
    for ch in batch_chunks:
        batch_text_parts.append(
            f"--- CHUNK {ch['chunk_id']} | SOURCE: {ch['source_file']} ---\n{ch['text']}\n"
        )
    batch_text = "\n".join(batch_text_parts)
    if len(batch_text) > 120_000:
        batch_text = batch_text[:120_000] + "\n...[TRUNCATED]"

    prompt = f"""Extract relationships between entities from these chunks.

KNOWN ENTITIES (full objects with data — use canonical names/ids below):
{entity_list_str}

PREVIOUSLY EXTRACTED RELATIONSHIPS (skip duplicates — same source/relation/target):
{prev_rel_json}

CHUNKS:
{batch_text}

TASK:
1. For each relationship evident in the text, emit:
   - source: canonical entity name
   - relation: UPPER_SNAKE_CASE (prefer {", ".join(RELATIONSHIP_TYPES)})
   - target: canonical entity name
   - confidence: 0.0-1.0 (0.95+ explicit; 0.80-0.94 clear implication; 0.60-0.79 weak/indirect; never below 0.60)
   - source_file, chunk_id (VERBATIM from header)
   - evidence_text: short verbatim snippet (5-15 words)
2. Only emit relations where BOTH source and target are either in KNOWN ENTITIES or clearly named in chunk text. Never use nameless roles or pronouns as endpoints.
3. Skip any (source, relation, target) already in PREVIOUSLY EXTRACTED.
4. Normalise relation labels: "knows", "KNOWS", "is acquainted with" -> KNOWS; "wife of" -> SPOUSE_OF; "lives at" -> RESIDES_AT; "rang" -> CALLED.
5. Respect direction conventions: caller -> receiver for CALLED/MESSAGED; parent -> child for PARENT_OF; person -> place for RESIDES_AT/SEEN_AT/LOCATED_AT; author -> document for AUTHORED; entity -> document for MENTIONED_IN; witness -> event for WITNESSED.

OUTPUT FORMAT — JSON array:
[
  {{"source": "Rose Mathew", "relation": "FRIEND_OF", "target": "Ananya Joseph", "confidence": 0.95, "source_file": "06_witness_statement_ananya.txt", "chunk_id": "06_witness_statement_ananya_chunk_042", "evidence_text": "Rose Mathew was friends with Ananya Joseph"}},
  {{"source": "Rose Mathew", "relation": "RESIDES_AT", "target": "Flat 3B", "confidence": 0.93, "source_file": "01_case_summary.txt", "chunk_id": "01_case_summary_chunk_001", "evidence_text": "Rose resided alone at Flat 3B"}},
  {{"source": "Arjun Dev", "relation": "CALLED", "target": "Rose Mathew", "confidence": 0.97, "source_file": "05_call_logs.txt", "chunk_id": "05_call_logs_chunk_001", "evidence_text": "+91-90000-20002 called +91-90000-10001 at 21:47"}},
  {{"source": "Rose Mathew", "relation": "PARENT_OF", "target": "Diya Mathew", "confidence": 0.96, "source_file": "02_family_statement.txt", "chunk_id": "02_family_statement_chunk_003", "evidence_text": "Rose's six-year-old daughter Diya"}},
  {{"source": "Vikram Rao", "relation": "MENTIONED_IN", "target": "First Information Report", "confidence": 0.94, "source_file": "01_case_summary.txt", "chunk_id": "01_case_summary_chunk_002", "evidence_text": "the FIR names Vikram Rao as a frequent visitor"}},
  {{"source": "Rose Mathew", "relation": "VICTIM_IN", "target": "Discovery of Rose Mathew's body", "confidence": 0.98, "source_file": "01_case_summary.txt", "chunk_id": "01_case_summary_chunk_001", "evidence_text": "the deceased, Rose Mathew, was found at 18:30"}},
  {{"source": "Ananya Joseph", "relation": "WITNESSED", "target": "Discovery of Rose Mathew's body", "confidence": 0.96, "source_file": "06_witness_statement_ananya.txt", "chunk_id": "06_witness_statement_ananya_chunk_041", "evidence_text": "Ananya discovered the body when she entered"}}
]

EDGE-CASE EXAMPLES (follow these):
- "Arjun, her estranged husband, often argued with Rose" -> Arjun Dev SPOUSE_OF Rose Mathew + Arjun Dev MET_WITH Rose Mathew (argued implies meeting, lower confidence ~0.7).
- "the knife was in Arjun's bag" -> Knife POSSESSED_BY Arjun Dev (custody phrasing), NOT Arjun OWNS Knife.
- "an unknown man threatened her" -> SKIP (nameless endpoint).
- "she said he used to call her daily" — antecedents unambiguous in chunk -> resolve pronouns, e.g. Arjun Dev CALLED Rose Mathew (~0.75 confidence, indirect).
- "Ananya believes Meera met Rose that night" -> Meera Krishnan MET_WITH Rose Mathew with confidence <= 0.7 (belief framing).
- "they were close" with no named pair in this chunk -> SKIP.
- Same pair, two relations: "he texted and then called her" -> two records: MESSAGED and CALLED.

Return ONLY the JSON array.
"""
    return prompt


def build_single_call_entity_prompt(
    warehouse_text: str,
    chunk_metadata: List[Dict[str, Any]] | None = None,
) -> str:
    """
    Prompt when the whole warehouse fits in context — send entire file.
    chunk_metadata optional: list of {source_file, chunk_id} ranges to help LLM cite provenance.
    """
    if len(warehouse_text) > 400_000:
        # Hard cut for safety even if token window says 500k (chars ~ tokens*4)
        warehouse_text = warehouse_text[:400_000] + "\n...[WAREHOUSE TRUNCATED]"

    prompt = f"""Extract ALL entities from the full investigation warehouse below.

The warehouse contains source boundaries like:
  SOURCE_FILE: xyz.txt ... END_SOURCE: xyz.txt
Use those to set source_file for each entity. If chunk_metadata were provided, cite the most relevant chunk_id nearby.

WAREHOUSE TEXT:
{warehouse_text}

TASK — same rules as batch mode:
- Types: {", ".join(ENTITY_TYPES)}
- Normalise aliases (Rose Mathew = Rose M. = R. Mathew; "Dr. Meera Krishnan" -> "Meera Krishnan" + alias; nicknames are aliases, not new people)
- Provide name, type, confidence, source_file, chunk_id (estimate chunk_id if not provided: e.g. source_file + "_chunk_001"), aliases, data (JSON metadata per RULES; {{}} if none)
- Skip pronouns, nameless roles ("the driver", "a neighbour"), and redacted names ("[REDACTED]", "witness 1")
- Respect SOURCE_FILE: ... END_SOURCE: boundaries for provenance — never attribute an entity to the wrong source file
- Deduplicate within your own output: each real-world entity exactly once, with all its aliases collected
- No hallucinations, valid JSON array only.
- Example: Sara PERSON -> {{"name":"Sara","type":"PERSON","confidence":0.95,"source_file":"x.txt","chunk_id":"x_chunk_001","aliases":[],"data":{{"age":26,"occupation":"Student"}}}}

OUTPUT: JSON array as specified in batch prompt.
"""
    return prompt


def build_normalization_prompt(entities: List[Dict[str, Any]]) -> str:
    """
    Prompt to deduplicate/merge entity list after all batches.
    LLM-as-judge for alias resolution.
    """
    raw = json.dumps(entities, indent=2, ensure_ascii=False)
    return f"""You are given a raw entity list extracted from batched chunks. Deduplicate aliases.

RAW ENTITIES:
{raw}

TASK:
- Merge entities that refer to the same real-world thing, e.g.:
  * "Arjun Dev" + "Arjun D." + "A. Dev" -> single "Arjun Dev"
  * "Dr. Meera Krishnan" + "Meera K." -> single "Meera Krishnan" (honorific becomes an alias)
  * "Rose mathew" + "Rose Mathew" (case variants) -> single "Rose Mathew"
  * "+91 90000 10001" + "+91-90000-10001" -> single "+91-90000-10001"
  * "Anna Nagar, Chennai" + "Anna Nagar" -> single "Anna Nagar" (city in data.city)
- NEVER merge across different types (a PERSON "Kumar" and an ORGANIZATION "Kumar & Sons" stay separate).
- NEVER merge two genuinely different people with similar names (different full names stay separate even if sharing a surname).
- Keep the most complete canonical name (full name over initials; no honorific prefix).
- Merge source_files, chunk_ids, and aliases (deduplicated arrays); data = union of data fields, prefer non-empty values; keep max confidence; sum/keep mentions as given.
- Ambiguous cases (e.g. "S. Kumar" vs "Sanjay Kumar" when the text never links them): keep them SEPARATE — do not guess.

Return deduplicated JSON array with the same schema but with merged source_files (array), aliases (array), data (object).

Return ONLY JSON array.
"""


# ---------------------------------------------------------------------------
# Timeline extraction prompts — chunks/warehouse -> sorted timestamp/event list
# ---------------------------------------------------------------------------

TIMELINE_SYSTEM_PROMPT = """You are Sherlock, an investigation timeline extractor.

Your job: extract every chronological event that has a timestamp or date from messy case text.

RULES:
- Extract ONLY events that are explicitly timestamped or dated in the provided text. Do NOT hallucinate timestamps.
- For each event provide:
  * timestamp: verbatim or normalized timestamp string (prefer ISO-8601 "YYYY-MM-DD HH:MM[:SS]" or "YYYY-MM-DDTHH:MM:SS+05:30" if time is present; if only date, use "YYYY-MM-DD"; keep timezone "IST" if present)
  * event: concise description (1 sentence, 5-20 words) of what happened, with key entities involved
  * source_file: from chunk header (e.g. "03_scene_report.txt")
  * chunk_id: from chunk header
  * confidence: 0.0-1.0 (how explicit was the timestamp in text)
  * evidence_text: short verbatim snippet (5-15 words) from chunk supporting the event
- Normalise timestamps where possible:
  "14 April 2026 18:30 IST" -> "2026-04-14 18:30"
  "14APR2026 19:55" -> "2026-04-14 19:55"
  "14/04/2026 6:30 pm" -> "2026-04-14 18:30"
  "April 14" (year established elsewhere in the same source) -> "2026-04-14"
  "2026-04-14T15:30:00+05:30" stays as is
- Relative dates: if the text anchors them ("two days before the discovery on 14 April 2026"), compute the concrete date ("2026-04-12") and note the derivation in the event text; confidence <= 0.75. If no anchor exists, SKIP the event.
- Weekday-only mentions ("last Friday") with no resolvable date: SKIP.
- If a time window is given (e.g. "15:30 — 17:30 IST on 14 April 2026"), emit ONE event with the start time "2026-04-14 15:30" and mention the window in the event text.
- Approximate times ("around 18:30", "between 6 and 7 pm"): use the stated start/point estimate ("2026-04-14 18:30") with confidence ~0.7 and keep the approximation wording in the event text.
- Duration events (e.g. "session 11:30-13:00"): one event at start time, duration noted in event text.
- Multi-day ranges ("missing from 10 to 12 April 2026"): one event at the start date, range in event text.
- If two sources give conflicting times for the same event, emit BOTH as separate records with their own source_file/confidence — never silently merge or average.
- No hallucination: only timestamps actually mentioned. If no timestamped event in batch, return [].
- If previous_events are provided, SKIP duplicates (same timestamp + same normalized event text). Use canonical phrasing.
- Return ONLY valid JSON — no markdown, no explanation.
"""


def build_timeline_prompt(
    batch_chunks: List[Dict[str, Any]],
    previous_events: List[Dict[str, Any]] | None = None,
) -> str:
    """
    Build user prompt for timeline extraction on a batch of chunks.
    Includes previous batch events for deduplication.

    Args:
        batch_chunks: list of chunk dicts {chunk_id, source_file, text}
        previous_events: timeline events already extracted in prior batches

    Returns:
        Prompt string ready to send as user message.
    """
    previous_events = previous_events or []
    prev_json = json.dumps(previous_events, indent=2, ensure_ascii=False) if previous_events else "[] (no previous events — this is the first batch)"

    batch_text_parts: List[str] = []
    for ch in batch_chunks:
        batch_text_parts.append(
            f"--- CHUNK {ch['chunk_id']} | SOURCE: {ch['source_file']} ---\n{ch['text']}\n"
        )
    batch_text = "\n".join(batch_text_parts)

    if len(batch_text) > 120_000:
        batch_text = batch_text[:120_000] + "\n...[TRUNCATED]"

    prompt = f"""Extract chronological timeline events from the following investigation chunks.

PREVIOUSLY EXTRACTED EVENTS (skip duplicates — same timestamp + event):
{prev_json}

CHUNKS TO PROCESS (batch size {len(batch_chunks)}):
{batch_text}

TASK:
1. Extract every event that has an explicit timestamp or date.
2. For each event provide:
   - timestamp: normalized "YYYY-MM-DD HH:MM[:SS]" or "YYYY-MM-DD" if only date (keep original time, normalize format)
   - event: concise 1-sentence description (e.g. "Rose Mathew found dead in Flat 3B by Ananya Joseph")
   - source_file: from chunk header
   - chunk_id: from chunk header
   - confidence: 0.0-1.0
   - evidence_text: verbatim 5-15 words from chunk
3. If an event with same timestamp+event already in PREVIOUSLY EXTRACTED, SKIP it.
4. Normalize timestamps: "14 April 2026 18:30 IST" -> "2026-04-14 18:30", keep date-only as "2026-04-14".
5. Do NOT hallucinate timestamps not present. If none, return [].

OUTPUT FORMAT — JSON array (empty [] if none found):
[
  {{
    "timestamp": "2026-04-14 18:30",
    "event": "Rose Mathew found dead in Flat 3B by Ananya Joseph and building manager",
    "source_file": "01_case_summary.txt",
    "chunk_id": "01_case_summary_chunk_001",
    "confidence": 0.98,
    "evidence_text": "Rose Mathew was found dead at 18:30 in Flat 3B"
  }},
  {{
    "timestamp": "2026-04-14 15:30",
    "event": "Estimated time of death window starts",
    "source_file": "04_postmortem_report.txt",
    "chunk_id": "04_postmortem_report_chunk_002",
    "confidence": 0.90,
    "evidence_text": "ESTIMATED WINDOW: 15:30 — 17:30 IST on 14 April 2026"
  }}
]

Return ONLY the JSON array — no markdown, no extra text. The events may be in ANY order (encounter order is fine); we will sort chronologically later.
"""
    return prompt


def build_single_call_timeline_prompt(
    warehouse_text: str,
) -> str:
    """
    Prompt when the whole warehouse fits in context — send entire file for timeline extraction.
    """
    if len(warehouse_text) > 400_000:
        warehouse_text = warehouse_text[:400_000] + "\n...[WAREHOUSE TRUNCATED]"

    prompt = f"""Extract ALL chronological timeline events from the full investigation warehouse below.

The warehouse contains source boundaries like:
  SOURCE_FILE: xyz.txt ... END_SOURCE: xyz.txt
Use those to set source_file for each event. Estimate chunk_id as <source_file_without_ext>_chunk_001 if needed.

WAREHOUSE TEXT:
{warehouse_text}

TASK — same rules as batch mode:

OUTPUT FORMAT:
Provide a JSON array of objects like this:
[
  {{
    "timestamp": "2026-04-14 18:30",
    "event": "Rose Mathew was found dead at her flat",
    "source_file": "fir.txt",
    "chunk_id": "fir_chunk_001",
    "confidence": 0.95,
    "evidence_text": "Rose Mathew was found dead at 18:30"
  }}
]

- Extract every timestamped/dated event (timestamp + event + source_file + chunk_id + confidence + evidence_text)
- Normalize timestamps: "14 April 2026 18:30 IST" -> "2026-04-14 18:30"
- Example: {{"timestamp":"2026-04-14 18:30","event":"Rose Mathew found dead in Flat 3B","source_file":"01_case_summary.txt","chunk_id":"01_case_summary_chunk_001","confidence":0.98,"evidence_text":"found dead at 18:30"}}
- No hallucinations, valid JSON array only. Events may be in any order — we sort later.
- If multiple events share timestamp, keep them separate.

OUTPUT: JSON array as specified in batch prompt.
"""
    return prompt


# ---------------------------------------------------------------------------
# Graph mapping prompt — entities + relations -> relation_id : node1 relation node2
# ---------------------------------------------------------------------------

GRAPH_MAPPING_SYSTEM_PROMPT = """You are Sherlock, an investigation graph builder.

You are given ALL extracted entities (with id, name, type, data) and ALL extracted relationships (source, relation, target, confidence, evidence).

Your task: produce the final knowledge graph as a list of relation-centered objects with rich node embeddings.

Rules:
- Each entity must have an id: lowercased type + "_" + normalized name (e.g. person_sara, person_rose_mathew, location_anna_nagar). If id missing, generate it the same way (slugify: lowercase, non-alphanumerics -> "_").
- For each relationship, output one object with:
  relation_id: "rel_001", "rel_002", ...
  source: full node object {id, name, type, data, source_files, chunk_ids, aliases if any}
  relation: UPPER_SNAKE_CASE (from input)
  target: full node object {id, name, type, data, source_files, chunk_ids, aliases if any}
  confidence, evidence_text, source_file, chunk_id (from relationship)
- Use EXACT entity objects from input (do not invent new entities, do not alter names, types, or data). Embed the full data.
- Entity lookup order for endpoints: match by id first, then normalized name, then any known alias of an entity. If a relationship endpoint resolves to an alias, embed the CANONICAL entity object (not the alias).
- If a relationship references an entity not in the entity list (and not resolvable via aliases), SKIP it — a dangling endpoint is worse than a missing edge.
- Do NOT drop edges because they look redundant: different relation types between the same pair are distinct edges (CALLED and MESSAGED both stay).
- Preserve provenance, confidence, evidence_text, source_file, and chunk_id exactly as given. Never reorder or rename relations.
- Output relation_ids sequentially (rel_001, rel_002, ...) in input order unless the input already has relation_ids — then keep them.
- Return ONLY valid JSON — no markdown.
"""

def build_graph_mapping_prompt(
    entities: List[Dict[str, Any]],
    relationships: List[Dict[str, Any]],
) -> str:
    """
    Build prompt for graph mapping: entities+relations -> relation_id node1 relation node2
    Sends full entities with data + relations (per user spec).
    """
    entities_json = json.dumps(entities, indent=2, ensure_ascii=False)
    relationships_json = json.dumps(relationships, indent=2, ensure_ascii=False)

    # Truncate if huge
    if len(entities_json) > 80000:
        entities_json = entities_json[:80000] + "\n...[TRUNCATED]"
    if len(relationships_json) > 80000:
        relationships_json = relationships_json[:80000] + "\n...[TRUNCATED]"

    prompt = f"""Build the final knowledge graph from entities and relationships below.

ENTITIES (with data):
{entities_json}

RELATIONSHIPS:
{relationships_json}

TASK:
1. Assign each relationship a relation_id: rel_001, rel_002, ... in order given.
2. For each relationship, embed the full source and target node objects (with id, name, type, data, source_files, chunk_ids, aliases).
   Example entity id generation: Sara PERSON -> id="person_sara"
3. Output a JSON array where each element is:
{{
  "relation_id": "rel_001",
  "source": {{"id": "person_sara", "name": "Sara", "type": "PERSON", "data": {{"age": 26}}, "source_files": ["witness.txt"], "chunk_ids": ["witness_chunk_001"], "aliases": []}},
  "relation": "FRIEND_OF",
  "target": {{"id": "person_ananya_joseph", "name": "Ananya Joseph", "type": "PERSON", "data": {{}}, "source_files": ["witness.txt"], "chunk_ids": ["witness_chunk_001"], "aliases": []}},
  "confidence": 0.95,
  "evidence_text": "Sara was friends with Ananya",
  "source_file": "witness.txt",
  "chunk_id": "witness_chunk_001"
}}

4. Use ONLY entities/relationships from input. Do not hallucinate.
5. If input already has relation_id, keep it; otherwise generate sequential.

Return ONLY the JSON array of graph mappings.
"""
    return prompt


# ---------------------------------------------------------------------------
# Contradiction detection prompts — cross-source truth comparison & conflict extraction
# ---------------------------------------------------------------------------

CONTRADICTION_TYPES = [
    "ALIBI_VS_EVIDENCE",              # Suspect claims to be in location A, but CCTV / call tower / witness puts them in location B
    "STATEMENT_VS_STATEMENT",        # Witness A states fact X, Witness B states contradictory fact Y
    "TIMELINE_CONFLICT",             # Forensic TOD or timestamps conflict with claimed actions / meetings
    "RELATIONSHIP_DENIAL",           # Entity denies relationship or contact, but records / messages prove it
    "FINANCIAL_OR_RECORD_MISMATCH",  # Claimed transactions / employment / logs disagree with official records
    "PHYSICAL_VS_TESTIMONIAL",       # Physical evidence (fingerprints, weapon, damage) disproves verbal testimony
    "FACTUAL_INCONSISTENCY",         # General mutual exclusion between facts stated in evidence
]

CONTRADICTION_SYSTEM_PROMPT = """You are Sherlock, an elite criminal investigation intelligence AI specializing in cross-source evidence comparison and contradiction detection.

YOUR MISSION:
Analyze the provided investigation evidence (witness statements, FIRs, forensic reports, CCTV logs, call records, financial statements) and identify all CONTRADICTIONS, FALSE ALIBIS, MUTUALLY EXCLUSIVE CLAIMS, and FACTUAL INCONSISTENCIES across different sources or within the same source.

ALLOWED CONTRADICTION TYPES:
- ALIBI_VS_EVIDENCE: A person claims an alibi (e.g. was in Delhi), but physical evidence, logs, or CCTV place them elsewhere (e.g. Mumbai).
- STATEMENT_VS_STATEMENT: Two witnesses or participants give directly conflicting accounts of the same event, time, identity, or fact.
- TIMELINE_CONFLICT: A stated sequence or time of events directly conflicts with established forensic timestamps, call records, or time of death.
- RELATIONSHIP_DENIAL: A person denies knowing, meeting, or contacting someone, while phone records, messages, or witnesses prove contact.
- FINANCIAL_OR_RECORD_MISMATCH: A verbal claim regarding money, documents, or official status contradicts paper/electronic records.
- PHYSICAL_VS_TESTIMONIAL: Physical / forensic / medical evidence contradicts what a witness or suspect claims happened.
- FACTUAL_INCONSISTENCY: Any other direct factual clash where both statements cannot simultaneously be true.

═══════════════════════════════════════════
RULES FOR ACCURATE CONTRADICTION DETECTION:
═══════════════════════════════════════════
1. MUTUAL EXCLUSIVITY: A contradiction exists ONLY when two statements or facts CANNOT BOTH BE TRUE at the same time in reality.
   - Genuine contradiction: "Arjun stated he was in Delhi at 4 PM" VS "CCTV records Arjun entering hotel in Mumbai at 4 PM".
   - NOT a contradiction: "Arjun was wearing a jacket" and "Arjun was wearing jeans" (they are complementary details, not clashing).
2. EVIDENCE GROUNDING: Every contradiction must be grounded in explicit quotes and source provenance (source_file, chunk_id). Do NOT invent or hallucinate claims.
3. CONFLICTING POINTS: Each contradiction must detail at least 2 conflicting sides/points with:
   - claim: concise summary of the point
   - speaker_or_source: who stated it or which record documented it (e.g. "Arjun Dev (Witness Statement)", "CCTV Log")
   - source_file: source filename
   - chunk_id: chunk ID where it appears
   - quote: verbatim text snippet from the chunk
4. SEVERITY RATING:
   - CRITICAL: Direct alibi breakdown, murder weapon dispute, or false statement about the core crime event.
   - HIGH: Major conflict in timeline, presence at crime scene, or denied relationship with victim/suspect.
   - MEDIUM: Discrepancy in secondary timings, vehicle color, sequence of minor events, or financial amounts.
   - LOW: Minor ambiguity or slight variation in non-critical descriptive details.
5. INVESTIGATION LEADS: Provide an actionable follow-up interrogation question or investigative next-step for detectives.
6. DEDUPLICATION: If previous contradictions are provided, DO NOT re-emit duplicates that describe the same core factual clash.
7. OUTPUT: Return ONLY a valid JSON array of contradiction objects. If no contradictions exist, return [].
"""


def build_contradiction_prompt(
    batch_chunks: List[Dict[str, Any]],
    known_entities: List[Dict[str, Any]] | None = None,
    known_relations: List[Dict[str, Any]] | None = None,
    known_timeline: List[Dict[str, Any]] | None = None,
    previous_contradictions: List[Dict[str, Any]] | None = None,
) -> str:
    """
    Build user prompt for contradiction detection on a batch of chunks,
    enriched with known background context from processed/ (entities, relations, timeline).
    """
    known_entities = known_entities or []
    known_relations = known_relations or []
    known_timeline = known_timeline or []
    previous_contradictions = previous_contradictions or []

    # Format contextual background concisely
    ent_summary = []
    for e in known_entities[:60]:
        e_name = e.get("name", "")
        e_type = e.get("type", "")
        e_data = e.get("data", {})
        e_aliases = e.get("aliases", [])
        alias_str = f" (aliases: {', '.join(e_aliases)})" if e_aliases else ""
        data_str = f" | data: {json.dumps(e_data, ensure_ascii=False)}" if e_data else ""
        ent_summary.append(f"- {e_name} [{e_type}]{alias_str}{data_str}")
    entities_context = "\n".join(ent_summary) if ent_summary else "None"

    rel_summary = []
    for r in known_relations[:60]:
        src = r.get("source", "")
        if isinstance(src, dict):
            src = src.get("name", "")
        rel = r.get("relation", "")
        tgt = r.get("target", "")
        if isinstance(tgt, dict):
            tgt = tgt.get("name", "")
        rel_summary.append(f"- {src} --[{rel}]--> {tgt}")
    relations_context = "\n".join(rel_summary) if rel_summary else "None"

    tl_summary = []
    for ev in known_timeline[:40]:
        ts = ev.get("timestamp", "")
        desc = ev.get("event", ev.get("title", ""))
        src = ev.get("source_file", "")
        tl_summary.append(f"- [{ts}] {desc} (source: {src})")
    timeline_context = "\n".join(tl_summary) if tl_summary else "None"

    prev_json = (
        json.dumps(previous_contradictions, indent=2, ensure_ascii=False)
        if previous_contradictions
        else "[] (no previous contradictions detected yet)"
    )

    batch_text_parts: List[str] = []
    for ch in batch_chunks:
        batch_text_parts.append(
            f"--- CHUNK {ch.get('chunk_id', 'unknown')} | SOURCE: {ch.get('source_file', 'unknown')} ---\n{ch.get('text', '')}\n"
        )
    batch_text = "\n".join(batch_text_parts)

    if len(batch_text) > 120_000:
        batch_text = batch_text[:120_000] + "\n...[TRUNCATED]"

    prompt = f"""Compare the statements, facts, and evidence in the following chunks against each other and against established case knowledge to detect any CONTRADICTIONS, FALSE ALIBIS, or MUTUALLY EXCLUSIVE CLAIMS.

═══════════════════════════════════════════
ESTABLISHED CASE KNOWLEDGE (FOR CROSS-REFERENCE):
═══════════════════════════════════════════

KNOWN ENTITIES & ATTRIBUTES:
{entities_context}

KNOWN RELATIONSHIPS:
{relations_context}

CHRONOLOGICAL TIMELINE EVENTS:
{timeline_context}

PREVIOUSLY DETECTED CONTRADICTIONS (SKIP DUPLICATES):
{prev_json}

═══════════════════════════════════════════
EVIDENCE CHUNKS TO ANALYZE (batch of {len(batch_chunks)}):
═══════════════════════════════════════════
{batch_text}

═══════════════════════════════════════════
TASK:
═══════════════════════════════════════════
1. Find all contradictory facts between chunks, between witness statements, or between witness statements and records/CCTV/forensics/timeline.
2. For each contradiction, return a structured JSON object:
   - contradiction_id: "contra_001", "contra_002", etc.
   - type: one of ALIBI_VS_EVIDENCE, STATEMENT_VS_STATEMENT, TIMELINE_CONFLICT, RELATIONSHIP_DENIAL, FINANCIAL_OR_RECORD_MISMATCH, PHYSICAL_VS_TESTIMONIAL, FACTUAL_INCONSISTENCY
   - summary: short 1-sentence title
   - description: detailed explanation of why the claims are contradictory and cannot simultaneously be true
   - severity: CRITICAL | HIGH | MEDIUM | LOW
   - confidence: 0.0-1.0
   - entities_involved: array of canonical entity names involved
   - conflicting_points: array of 2 or more opposing points:
     * claim: summary of the specific claim/fact
     * speaker_or_source: who stated it or which source reported it
     * source_file: source filename from chunk
     * chunk_id: chunk ID from chunk
     * quote: exact verbatim quote supporting this point
   - resolution_status: POTENTIAL_LIE | SUSPICIOUS | UNRESOLVED | REQUIRES_VERIFICATION
   - investigation_lead: recommended interrogation question or investigative lead for detectives

OUTPUT FORMAT (return ONLY valid JSON array, or [] if no contradictions found):
[
  {{
    "contradiction_id": "contra_001",
    "type": "ALIBI_VS_EVIDENCE",
    "summary": "Arjun Dev's Delhi travel claim vs Mumbai CCTV footage",
    "description": "Arjun Dev claimed in his statement that he flew to Delhi on April 14 and remained in his hotel room all evening, but Gateway Hotel CCTV records him entering their Mumbai lobby at 16:15.",
    "severity": "CRITICAL",
    "confidence": 0.96,
    "entities_involved": ["Arjun Dev", "Gateway Hotel", "CCTV Camera 4"],
    "conflicting_points": [
      {{
        "claim": "Arjun claimed he flew to Delhi and stayed in his hotel room all evening.",
        "speaker_or_source": "Arjun Dev (Witness Statement)",
        "source_file": "witness_arjun.txt",
        "chunk_id": "witness_arjun_chunk_001",
        "quote": "I took the morning flight to Delhi and stayed in my hotel room all evening."
      }},
      {{
        "claim": "CCTV records Arjun Dev entering Gateway Hotel in Mumbai at 16:15.",
        "speaker_or_source": "CCTV Security Log",
        "source_file": "cctv_log.txt",
        "chunk_id": "cctv_log_chunk_003",
        "quote": "16:15:22 - Camera 4 captured Arjun Dev entering via South Lobby entrance."
      }}
    ],
    "resolution_status": "POTENTIAL_LIE",
    "investigation_lead": "Confront Arjun Dev with the Gateway Hotel Mumbai CCTV timestamp and subpoena airline manifests."
  }}
]
"""
    return prompt


def build_single_call_contradiction_prompt(
    warehouse_text: str,
    known_entities: List[Dict[str, Any]] | None = None,
    known_relations: List[Dict[str, Any]] | None = None,
    known_timeline: List[Dict[str, Any]] | None = None,
) -> str:
    """
    Prompt when the whole warehouse fits in context — send entire text and context for contradiction analysis.
    """
    known_entities = known_entities or []
    known_relations = known_relations or []
    known_timeline = known_timeline or []

    ent_summary = []
    for e in known_entities[:80]:
        e_name = e.get("name", "")
        e_type = e.get("type", "")
        e_data = e.get("data", {})
        data_str = f" | data: {json.dumps(e_data, ensure_ascii=False)}" if e_data else ""
        ent_summary.append(f"- {e_name} [{e_type}]{data_str}")
    entities_context = "\n".join(ent_summary) if ent_summary else "None"

    rel_summary = []
    for r in known_relations[:80]:
        src = r.get("source", "")
        if isinstance(src, dict):
            src = src.get("name", "")
        rel = r.get("relation", "")
        tgt = r.get("target", "")
        if isinstance(tgt, dict):
            tgt = tgt.get("name", "")
        rel_summary.append(f"- {src} --[{rel}]--> {tgt}")
    relations_context = "\n".join(rel_summary) if rel_summary else "None"

    tl_summary = []
    for ev in known_timeline[:50]:
        ts = ev.get("timestamp", "")
        desc = ev.get("event", ev.get("title", ""))
        src = ev.get("source_file", "")
        tl_summary.append(f"- [{ts}] {desc} (source: {src})")
    timeline_context = "\n".join(tl_summary) if tl_summary else "None"

    if len(warehouse_text) > 400_000:
        warehouse_text = warehouse_text[:400_000] + "\n...[TRUNCATED]"

    prompt = f"""Analyze the entire case warehouse below and identify all CONTRADICTIONS, FALSE ALIBIS, TIMELINE CONFLICTS, and MUTUALLY EXCLUSIVE CLAIMS across all sources.

═══════════════════════════════════════════
ESTABLISHED CASE KNOWLEDGE (FOR CROSS-REFERENCE):
═══════════════════════════════════════════

KNOWN ENTITIES:
{entities_context}

KNOWN RELATIONSHIPS:
{relations_context}

CHRONOLOGICAL TIMELINE:
{timeline_context}

═══════════════════════════════════════════
FULL CASE WAREHOUSE TEXT:
═══════════════════════════════════════════
{warehouse_text}

═══════════════════════════════════════════
TASK:
═══════════════════════════════════════════
1. Compare statements, timestamps, alibis, and observations across all files in the warehouse.
2. Identify all direct contradictions and mutually exclusive facts.
3. For each contradiction, return:
   - contradiction_id: "contra_001", "contra_002", etc.
   - type: ALIBI_VS_EVIDENCE | STATEMENT_VS_STATEMENT | TIMELINE_CONFLICT | RELATIONSHIP_DENIAL | FINANCIAL_OR_RECORD_MISMATCH | PHYSICAL_VS_TESTIMONIAL | FACTUAL_INCONSISTENCY
   - summary: concise title
   - description: thorough explanation of the contradiction
   - severity: CRITICAL | HIGH | MEDIUM | LOW
   - confidence: 0.0-1.0
   - entities_involved: array of entity names
   - conflicting_points: array of 2+ points (claim, speaker_or_source, source_file, chunk_id, quote)
   - resolution_status: POTENTIAL_LIE | SUSPICIOUS | UNRESOLVED | REQUIRES_VERIFICATION
   - investigation_lead: investigative advice / interrogation lead

Return ONLY the JSON array of contradictions (or [] if none found). No markdown formatting or extra text.
"""
    return prompt

