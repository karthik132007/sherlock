import os

path = "engine/prompts.py"
with open(path, "r") as f:
    content = f.read()

timeline_schema = """
OUTPUT FORMAT:
Provide a JSON array of objects like this:
[
  {
    "timestamp": "2026-04-14 18:30",
    "event": "Rose Mathew was found dead at her flat",
    "source_file": "fir.txt",
    "chunk_id": "fir_chunk_001",
    "confidence": 0.95,
    "evidence_text": "Rose Mathew was found dead at 18:30"
  }
]
"""

content = content.replace(
    "- Extract every timestamped/dated event",
    timeline_schema + "\n- Extract every timestamped/dated event"
)

content = content.replace(
    "Extract ALL timestamped/dated events from this batch.",
    timeline_schema + "\nExtract ALL timestamped/dated events from this batch."
)

with open(path, "w") as f:
    f.write(content)

print("Patched successfully")
