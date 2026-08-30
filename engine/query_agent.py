"""Natural-language investigation query agent.

The agent deliberately does not have database credentials.  It talks to Spring
over JSON Lines: it emits a ``tool_call`` for read-only Cypher, Spring executes
the query, and it writes a ``tool_result`` back to this process.  This keeps the
LLM separated from Neo4j and makes the five-query budget enforceable.
"""

from __future__ import annotations

import json
import logging
import sys
from pathlib import Path
from typing import Any, Dict, List

from engine.llm import call_llm, load_llm_config

logger = logging.getLogger(__name__)

PROTOCOL = "sherlock-query-v1"
MAX_CYPHER_CALLS = 5
MAX_RESULT_RECORDS = 100
MAX_PREVIOUS_MESSAGES = 12

SHERLOCK_INVESTIGATION_CONTEXT = """
You are Sherlock's investigation query agent. Sherlock converts source-boundaried
case evidence into an explainable knowledge graph. Every node and relationship
belongs to exactly one case via project_id. Every relationship can be traced back
to source_file, chunk_id, and evidence_text. Treat the graph as investigation
evidence, not as proof: report uncertainty and cite available source files or
evidence text in your answer. Do not invent facts not returned by the tool.

Story and goal: An investigator asks a question in the desktop app. You inspect
only that case's knowledge graph, use Cypher when needed, analyse the returned
records, then give a concise evidence-grounded answer. You must also identify
the exact graph node ids and relation ids that support the answer so the desktop
app can highlight them for the investigator.

Neo4j schema:
* Nodes have a type label such as :Person, :Location, :Organization, :Event,
  :Document or :Entity. Their properties are id, name, type, project_id,
  confidence, mentions, data (a JSON string), aliases, source_files, chunk_ids.
* Relationships are directed and have a dynamic type (for example FRIEND_OF,
  CALLED, LOCATED_AT). Their properties are relation_id, project_id, confidence,
  evidence_text, source_file, and chunk_id.
* Queries must scope every match to the supplied case with e.g.
  n.project_id = $caseId or r.project_id = $caseId. The executor supplies
  $caseId; never put a literal case id in Cypher.
* Only read queries are allowed. Never attempt writes, schema changes,
  procedures, external loads, or more than 100 records.

Node data is inside the node, not a separate node:
* Each node's ``data`` property is a JSON *string* that holds the entity's facts:
  phone numbers, addresses, ages, occupations, roles, dates, amounts, and other
  metadata. Example: Suresh's node has data = '""{\"phone\":\"+91-90000-10007\",\"occupation\":\"Building Manager, Green View Apartments\"}""'.
* Facts such as a person's phone number are stored inside the person node's data
  string. They are almost never modelled as a separate phone-number node reached
  through a CALLED relationship.
* When the tool returns a node it is serialised as
  {"id", "name", "type", "labels", "properties"} where properties.data is the
  JSON string. Parse that string to read the fact.
""".strip()

AGENT_INSTRUCTIONS = """
Respond with exactly one JSON object and no Markdown fences.

To inspect the graph, return:
{"type":"tool_call","tool":"run_cypher","arguments":{"cypher":"MATCH ... WHERE n.project_id = $caseId RETURN ... LIMIT 25"}}

To answer, return:
{"type":"final","answer":"Evidence-grounded Markdown answer","highlight_node_ids":["node_id"],"highlight_relation_ids":["rel_001"]}

Use the tool only when it improves the answer. You have at most five calls.
After each tool result, decide whether another read query is necessary; otherwise
return a final answer. Highlight IDs must be IDs actually present in tool
results. If the tool reports an error or no records, explain that honestly.

Always check inside node data before concluding an attribute is missing.
If the question asks for an attribute of an entity (phone, address, age,
occupation, role, income, ...), locate the node by id, name, or alias and RETURN
the whole node (or n.data) so you can read the properties.data JSON string.
Do NOT assume the attribute is a separate node or relationship. Example:

MATCH (n) WHERE n.project_id = $caseId
  AND (toLower(n.name) = 'suresh' OR toLower(n.id) CONTAINS 'suresh'
       OR any(a IN coalesce(n.aliases, []) WHERE toLower(a) = 'suresh'))
RETURN n LIMIT 25

If the first lookup misses, retry a broader search that also matches the data
string, e.g. WHERE n.project_id = $caseId AND toString(n.data) CONTAINS 'phone'.
Only after checking the node's own data may you conclude the fact is absent.
""".strip()


def _emit(payload: Dict[str, Any]) -> None:
    payload["protocol"] = PROTOCOL
    print(json.dumps(payload, ensure_ascii=False), flush=True)


def _read_tool_result(call_id: str) -> Dict[str, Any]:
    line = sys.stdin.readline()
    if not line:
        return {"error": "The Spring query bridge closed before returning a result.", "records": []}
    try:
        payload = json.loads(line)
    except json.JSONDecodeError:
        return {"error": "The Spring query bridge returned invalid JSON.", "records": []}
    if payload.get("protocol") != PROTOCOL or payload.get("type") != "tool_result" or payload.get("call_id") != call_id:
        return {"error": "The Spring query bridge returned an unexpected tool result.", "records": []}
    records = payload.get("records", [])
    if not isinstance(records, list):
        records = []
    return {"records": records[:MAX_RESULT_RECORDS], "error": payload.get("error")}


def _parse_action(raw: str) -> Dict[str, Any]:
    try:
        # Strip markdown fences if present
        cleaned = raw.strip()
        if cleaned.startswith("```"):
            cleaned = __import__("re").sub(r"^```(json)?", "", cleaned)
            cleaned = __import__("re").sub(r"```$", "", cleaned).strip()
        
        parsed = json.loads(cleaned)
        return parsed if isinstance(parsed, dict) else {}
    except json.JSONDecodeError:
        logger.warning("Query LLM returned invalid JSON: %s", raw[:300])
        return {}


def _load_previous_conversation(project: Path) -> List[Dict[str, str]]:
    """Load the most recent persisted chat turns for the next LLM prompt."""
    history_path = project / "agent_responses.json"
    if not history_path.exists():
        return []
    try:
        root = json.loads(history_path.read_text(encoding="utf-8"))
        messages = root.get("messages", []) if isinstance(root, dict) else []
        if not isinstance(messages, list):
            return []
        conversation: List[Dict[str, str]] = []
        for message in messages[-MAX_PREVIOUS_MESSAGES:]:
            if not isinstance(message, dict):
                continue
            role = str(message.get("role", "")).strip().lower()
            content = message.get("content")
            if role in {"user", "assistant"} and isinstance(content, str) and content.strip():
                conversation.append({"role": role, "content": content.strip()})
        return conversation
    except (OSError, json.JSONDecodeError) as exc:
        logger.warning("Could not read persisted chat history from %s: %s", history_path, exc)
        return []


def _final(answer: str, node_ids: List[str] | None = None, relation_ids: List[str] | None = None,
           cypher_queries: List[str] | None = None, calls_used: int = 0) -> Dict[str, Any]:
    return {
        "type": "final",
        "answer": answer,
        "highlight_node_ids": node_ids or [],
        "highlight_relation_ids": relation_ids or [],
        "cypher_queries": cypher_queries or [],
        "tool_calls_used": calls_used,
    }


def run_query_agent(project_path: str | Path, question: str) -> Dict[str, Any]:
    """Run the bounded tool loop and return the final payload for Spring."""
    project = Path(project_path).expanduser().resolve()
    question = (question or "").strip()
    if not question:
        return _final("Please enter an investigation question.")
    if not (project / "processed" / "graph_data.json").exists() and not (project / "processed" / "relations.json").exists():
        return _final("No processed knowledge graph is available for this case yet. Run extraction before asking a graph question.")

    config = load_llm_config(project)
    transcript: List[Dict[str, Any]] = _load_previous_conversation(project)
    transcript.append({"role": "user", "content": question})
    cypher_queries: List[str] = []

    for call_number in range(MAX_CYPHER_CALLS + 1):
        prompt = json.dumps(
            {
                "case_folder": project.name,
                "question": question,
                "tool_calls_used": call_number,
                "tool_calls_remaining": MAX_CYPHER_CALLS - call_number,
                "conversation": transcript,
            },
            ensure_ascii=False,
        )
        try:
            action = _parse_action(call_llm(prompt, system_prompt=f"{SHERLOCK_INVESTIGATION_CONTEXT}\n\n{AGENT_INSTRUCTIONS}", llm_config=config))
        except Exception as exc:
            logger.exception("Investigation query LLM call failed")
            return _final(f"I could not query the investigation model: {exc}", cypher_queries=cypher_queries, calls_used=call_number)

        if action.get("type") == "final":
            answer = action.get("answer")
            if not isinstance(answer, str) or not answer.strip():
                answer = "I could not derive a grounded answer from the available graph evidence."
            node_ids = action.get("highlight_node_ids", [])
            relation_ids = action.get("highlight_relation_ids", [])
            return _final(
                answer.strip(),
                [str(value) for value in node_ids if value],
                [str(value) for value in relation_ids if value],
                cypher_queries,
                call_number,
            )

        arguments = action.get("arguments", {})
        cypher = arguments.get("cypher") if isinstance(arguments, dict) else None
        if action.get("type") != "tool_call" or action.get("tool") != "run_cypher" or not isinstance(cypher, str) or not cypher.strip():
            return _final(
                "I could not form a safe graph query for that question. Please rephrase it with an entity, relationship, place, or time.",
                cypher_queries=cypher_queries,
                calls_used=call_number,
            )
        if call_number >= MAX_CYPHER_CALLS:
            return _final(
                "I reached the five-query investigation limit. Based on the evidence gathered so far, I cannot make an additional database lookup.",
                cypher_queries=cypher_queries,
                calls_used=call_number,
            )

        call_id = f"cypher_{call_number + 1}"
        clean_cypher = cypher.strip()
        cypher_queries.append(clean_cypher)
        _emit({"type": "tool_call", "call_id": call_id, "tool": "run_cypher", "arguments": {"cypher": clean_cypher}})
        tool_result = _read_tool_result(call_id)
        transcript.append({"kind": "tool_result", "cypher": clean_cypher, **tool_result})

    return _final(
        "I reached the five-query investigation limit. This answer uses the evidence returned before the limit was reached.",
        cypher_queries=cypher_queries,
        calls_used=MAX_CYPHER_CALLS,
    )


def main() -> None:
    import argparse

    parser = argparse.ArgumentParser(description="Sherlock bounded Neo4j query agent")
    parser.add_argument("--project", required=True)
    parser.add_argument("--question", required=True)
    args = parser.parse_args()
    _emit(run_query_agent(args.project, args.question))


if __name__ == "__main__":
    main()
