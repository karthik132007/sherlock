import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

from engine.query_agent import PROTOCOL, _load_previous_conversation, run_query_agent


def _project(tmp_path: Path) -> Path:
    (tmp_path / "processed").mkdir()
    (tmp_path / "processed" / "graph_data.json").write_text("[]", encoding="utf-8")
    return tmp_path


class TestQueryAgent(unittest.TestCase):
    def test_loads_recent_persisted_conversation(self):
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            (project / "agent_responses.json").write_text(json.dumps({
                "messages": [
                    {"role": "user", "content": "Who is Rose?"},
                    {"role": "assistant", "content": "Rose is a person in the case."},
                    {"role": "tool", "content": "not prompt context"},
                ]
            }), encoding="utf-8")
            self.assertEqual(_load_previous_conversation(project), [
                {"role": "user", "content": "Who is Rose?"},
                {"role": "assistant", "content": "Rose is a person in the case."},
            ])

    def test_returns_structured_final_without_tool(self):
        with tempfile.TemporaryDirectory() as directory, patch("engine.query_agent.load_llm_config", return_value=object()), patch(
            "engine.query_agent.call_llm",
            return_value=json.dumps({
                "type": "final",
                "answer": "### Finding\n\nRose is connected to Arjun.",
                "highlight_node_ids": ["person_rose", "person_arjun"],
                "highlight_relation_ids": ["rel_001"],
            }),
        ):
            result = run_query_agent(_project(Path(directory)), "Who is connected to Rose?")

        self.assertEqual(result["type"], "final")
        self.assertEqual(result["highlight_node_ids"], ["person_rose", "person_arjun"])
        self.assertEqual(result["highlight_relation_ids"], ["rel_001"])
        self.assertEqual(result["tool_calls_used"], 0)

    def test_hard_stops_after_five_cypher_calls(self):
        tool_action = json.dumps({
            "type": "tool_call",
            "tool": "run_cypher",
            "arguments": {"cypher": "MATCH (n) WHERE n.project_id = $caseId RETURN n"},
        })
        bridge_lines = "".join(
            json.dumps({"protocol": PROTOCOL, "type": "tool_result", "call_id": f"cypher_{i}", "records": []}) + "\n"
            for i in range(1, 6)
        )
        original_stdin = sys.stdin
        try:
            sys.stdin = io.StringIO(bridge_lines)
            with tempfile.TemporaryDirectory() as directory, redirect_stdout(io.StringIO()), patch(
                "engine.query_agent.load_llm_config", return_value=object()
            ), patch("engine.query_agent.call_llm", return_value=tool_action):
                result = run_query_agent(_project(Path(directory)), "Find all evidence")
        finally:
            sys.stdin = original_stdin

        self.assertEqual(result["type"], "final")
        self.assertEqual(result["tool_calls_used"], 5)
        self.assertEqual(len(result["cypher_queries"]), 5)
        self.assertIn("five-query", result["answer"])
