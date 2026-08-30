import re
with open("engine/query_agent.py", "r") as f:
    code = f.read()
old = """def _parse_action(raw: str) -> Dict[str, Any]:
    try:
        parsed = json.loads(raw)
        return parsed if isinstance(parsed, dict) else {}
    except json.JSONDecodeError:"""
new = """def _parse_action(raw: str) -> Dict[str, Any]:
    try:
        # Strip markdown fences if present
        cleaned = raw.strip()
        if cleaned.startswith("```"):
            cleaned = __import__("re").sub(r"^```(json)?", "", cleaned)
            cleaned = __import__("re").sub(r"```$", "", cleaned).strip()
        
        parsed = json.loads(cleaned)
        return parsed if isinstance(parsed, dict) else {}
    except json.JSONDecodeError:"""
code = code.replace(old, new)
with open("engine/query_agent.py", "w") as f:
    f.write(code)
print("query_agent.py patched")
