import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

from engine.llm import (
    call_llm_with_thinking,
    extract_opinion_auto,
)
from engine.opinion import (
    generate_opinion,
    load_processed_context,
    normalize_alternative_hypothesis,
    normalize_evidence_point,
    normalize_flaw_point,
    normalize_investigative_lead,
    normalize_possible_cause,
    save_opinion,
)
from engine.prompts import (
    OPINION_SYSTEM_PROMPT,
    build_opinion_prompt,
)


class TestOpinion(unittest.TestCase):
    def test_opinion_system_prompt_contains_sherlock_guidelines(self):
        self.assertIn("Sherlock Holmes", OPINION_SYSTEM_PROMPT)
        self.assertIn("EXHAUSTIVE SCENARIO EXPLORATION", OPINION_SYSTEM_PROMPT)
        self.assertIn("ADVERSARIAL SELF-DEBATE", OPINION_SYSTEM_PROMPT)
        self.assertIn("ROOT CAUSE & CATALYST ANALYSIS", OPINION_SYSTEM_PROMPT)

    def test_build_opinion_prompt_incorporates_all_context(self):
        warehouse = "========================================\nSOURCE_FILE: test.txt\n========================================\nJohn was at the dock at midnight."
        entities = [{"name": "John Doe", "type": "PERSON", "aliases": ["JD"]}]
        relations = [{"source": "John Doe", "relation": "SEEN_AT", "target": "The Dock", "evidence_text": "saw him at dock"}]
        timeline = [{"timestamp_normalized": "2026-05-01T00:00:00Z", "event": "John sighted", "source_file": "test.txt"}]
        contradictions = [{"contradiction_id": "contra_001", "type": "ALIBI_VS_EVIDENCE", "summary": "John alibi conflict", "description": "claimed he was asleep"}]

        prompt = build_opinion_prompt(
            warehouse_text=warehouse,
            entities=entities,
            relations=relations,
            timeline=timeline,
            contradictions=contradictions,
        )

        self.assertIn("John Doe", prompt)
        self.assertIn("JD", prompt)
        self.assertIn("The Dock", prompt)
        self.assertIn("John sighted", prompt)
        self.assertIn("contra_001", prompt)
        self.assertIn("John alibi conflict", prompt)
        self.assertIn("OUTPUT REQUIREMENTS", prompt)

    def test_normalizers(self):
        ev = normalize_evidence_point({
            "claim": "Weapon found in trunk",
            "source_file": "forensics.txt",
            "chunk_id": "c1",
            "quote": "A 9mm pistol was recovered from the trunk.",
            "relevance": "Direct physical link",
            "entities_involved": ["John Doe", "9mm Pistol"],
        })
        self.assertEqual(ev["claim"], "Weapon found in trunk")
        self.assertEqual(ev["quote"], "A 9mm pistol was recovered from the trunk.")
        self.assertEqual(len(ev["entities_involved"]), 2)

        flaw = normalize_flaw_point({
            "point": "No fingerprints on the gun",
            "type": "MISSING_EVIDENCE",
            "source_file": "forensics.txt",
            "impact": "Leaves doubt on whether John fired it",
        })
        self.assertEqual(flaw["type"], "MISSING_EVIDENCE")
        self.assertIn("fingerprints", flaw["point"])

        alt = normalize_alternative_hypothesis({
            "title": "Framed by Associate",
            "description": "Associate planted the weapon.",
            "supporting_points": ["Associate had access"],
            "counter_points": ["No key found with associate"],
        })
        self.assertEqual(alt["title"], "Framed by Associate")

        lead = normalize_investigative_lead({
            "lead": "Test trunk for DNA",
            "priority": "CRITICAL",
            "action": "Swab steering wheel and trunk handle",
            "rationale": "Confirm physical contact",
        })
        self.assertEqual(lead["priority"], "CRITICAL")

        cause = normalize_possible_cause({
            "cause": "Financial blackmail",
            "category": "FINANCIAL",
            "evidence_indicators": ["Offshore wire transfers"],
            "significance": "Compelled suspect to commit breach",
        })
        self.assertEqual(cause["cause"], "Financial blackmail")
        self.assertEqual(cause["category"], "FINANCIAL")
        self.assertEqual(len(cause["evidence_indicators"]), 1)

    @patch("engine.llm.get_client")
    def test_call_llm_with_thinking_extracts_reasoning_and_think_tags(self, mock_get_client):
        # Case 1: Model with reasoning_content attribute (DeepSeek Reasoner)
        mock_client = MagicMock()
        mock_resp = MagicMock()
        mock_choice = MagicMock()
        mock_choice.message.content = '{"executive_summary": "Solved"}'
        mock_choice.message.reasoning_content = "1. Let's analyze John's timeline... 2. Connect the gun to the trunk."
        mock_resp.choices = [mock_choice]
        mock_client.chat.completions.create.return_value = mock_resp
        mock_get_client.return_value = mock_client

        content, reasoning = call_llm_with_thinking("test prompt", model="deepseek-reasoner")
        self.assertIn("Solved", content)
        self.assertIn("Let's analyze John's timeline", reasoning)

        # Case 2: Model outputting <think>...</think> tags in content (Ollama / Local R1)
        mock_choice.message.content = "<think>Analyzing all clues carefully...</think>\n{\"executive_summary\": \"Theory verified\"}"
        mock_choice.message.reasoning_content = None

        content2, reasoning2 = call_llm_with_thinking("test prompt", model="deepseek-r1")
        self.assertNotIn("<think>", content2)
        self.assertIn("Theory verified", content2)
        self.assertEqual(reasoning2, "Analyzing all clues carefully...")

    @patch("engine.llm.call_llm_with_thinking")
    def test_generate_opinion_end_to_end(self, mock_llm_call):
        mock_llm_call.return_value = (
            json.dumps({
                "case_id": "CASE_TEST_001",
                "preliminary_analysis": "Initial inspection shows compromised electronic access locks at midnight.",
                "possible_causes": [
                    {
                        "cause": "Insider debt pressure",
                        "category": "FINANCIAL",
                        "evidence_indicators": ["Loan default notice in desk"],
                        "significance": "Provided urgent financial pressure to execute theft.",
                    }
                ],
                "self_debate_summary": "Debated whether John acted alone vs as an accomplice. CCTV timing confirms solo entry.",
                "executive_summary": "The suspect committed the act between 22:00 and 23:00.",
                "primary_hypothesis": "John planned the theft using insider access.",
                "confidence": 0.92,
                "confidence_explanation": "Direct CCTV and witness consistency.",
                "supporting_evidence": [
                    {
                        "claim": "Seen near vault at 22:15",
                        "source_file": "cctv.txt",
                        "quote": "John entered corridor 4",
                        "relevance": "Places suspect at scene",
                        "entities_involved": ["John", "Vault"],
                    }
                ],
                "flaws_and_counter_evidence": [
                    {
                        "point": "Vault log timestamp is 5 minutes off",
                        "type": "TIMELINE_GAP",
                        "impact": "Minor discrepancy in electronic clock sync",
                    }
                ],
                "alternative_hypotheses": [
                    {
                        "title": "External Heist",
                        "description": "Third party entered through skylight",
                        "supporting_points": ["Skylight was unlocked"],
                        "counter_points": ["Alarm sensors did not trigger"],
                    }
                ],
                "investigative_leads": [
                    {
                        "lead": "Calibrate security server clock",
                        "priority": "HIGH",
                        "action": "Request NTP audit from IT",
                    }
                ],
                "reasoning_trace": "Brainstorming steps: 1. Reviewed vault logs. 2. Cross-referenced CCTV.",
            }),
            "DeepSeek R1 Thinking trace: Evaluated all 4 suspects and eliminated 3."
        )

        with tempfile.TemporaryDirectory() as tmpdir:
            case_dir = Path(tmpdir) / "case_test"
            case_dir.mkdir()
            (case_dir / "warehouse.txt").write_text("========================================\nSOURCE_FILE: cctv.txt\n========================================\nJohn entered corridor 4\n========================================\nEND_SOURCE: cctv.txt", encoding="utf-8")

            res = generate_opinion(case_dir, save_outputs=True)

            self.assertEqual(res["case_id"], "CASE_TEST_001")
            self.assertEqual(res["confidence"], 0.92)
            self.assertIn("Initial inspection", res["preliminary_analysis"])
            self.assertEqual(len(res["possible_causes"]), 1)
            self.assertIn("Insider debt pressure", res["possible_causes"][0]["cause"])
            self.assertIn("Debated whether John acted alone", res["self_debate_summary"])
            self.assertEqual(len(res["supporting_evidence"]), 1)
            self.assertEqual(len(res["flaws_and_counter_evidence"]), 1)
            self.assertEqual(len(res["alternative_hypotheses"]), 1)
            self.assertEqual(len(res["investigative_leads"]), 1)
            self.assertIn("Evaluated all 4 suspects", res["reasoning_trace"])

            # Verify saved file
            saved_file = case_dir / "processed" / "opinion.json"
            self.assertTrue(saved_file.exists())
            loaded = json.loads(saved_file.read_text(encoding="utf-8"))
            self.assertEqual(loaded["confidence"], 0.92)
            self.assertEqual(len(loaded["possible_causes"]), 1)


if __name__ == "__main__":
    unittest.main()
