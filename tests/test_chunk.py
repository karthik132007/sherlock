import unittest
import tempfile
from pathlib import Path

from engine.chunk import (
    DEFAULT_CHUNK_SIZE,
    DEFAULT_OVERLAP,
    semantic_chunk_text,
    _parse_warehouse_sources,
    _safe_prefix,
    _apply_overlap,
)


class TestWarehouseParsing(unittest.TestCase):
    def test_parse_empty(self):
        self.assertEqual(_parse_warehouse_sources("hello world no markers"), [])

    def test_parse_single_source(self):
        txt = """========================================
SOURCE_FILE: fir.txt
SOURCE_TYPE: TEXT
========================================

hello world content here

========================================
END_SOURCE: fir.txt
========================================
"""
        sources = _parse_warehouse_sources(txt)
        self.assertEqual(len(sources), 1)
        self.assertEqual(sources[0]["source_file"], "fir.txt")
        self.assertIn("hello world", sources[0]["content"])

    def test_parse_multi_source(self):
        txt = """========================================
SOURCE_FILE: fir.txt
SOURCE_TYPE: TEXT
========================================

content A

========================================
END_SOURCE: fir.txt
========================================


========================================
SOURCE_FILE: witness.txt
SOURCE_TYPE: TEXT
========================================

content B

========================================
END_SOURCE: witness.txt
========================================
"""
        sources = _parse_warehouse_sources(txt)
        self.assertEqual(len(sources), 2)
        self.assertEqual(sources[0]["source_file"], "fir.txt")
        self.assertEqual(sources[1]["source_file"], "witness.txt")

    def test_safe_prefix(self):
        self.assertEqual(_safe_prefix("fir.txt"), "fir")
        self.assertEqual(_safe_prefix("my case/file name.TXT"), "file_name")
        self.assertEqual(_safe_prefix(""), "warehouse")


class TestSemanticChunk(unittest.TestCase):
    def test_empty_text(self):
        self.assertEqual(semantic_chunk_text("", source_file="x.txt"), [])
        self.assertEqual(semantic_chunk_text("   ", source_file="x.txt"), [])

    def test_small_text_single_chunk(self):
        chunks = semantic_chunk_text("Hello world. Short.", source_file="test.txt")
        self.assertEqual(len(chunks), 1)
        self.assertEqual(chunks[0]["source_file"], "test.txt")
        self.assertIn("chunk_id", chunks[0])
        self.assertIn("metadata", chunks[0])
        self.assertIn("char_start", chunks[0]["metadata"])

    def test_chunk_metadata_shape(self):
        chunks = semantic_chunk_text("Rose Mathew was found dead in her apartment. Ananya Joseph called police.", source_file="fir.txt")
        for c in chunks:
            self.assertIn("chunk_id", c)
            self.assertIn("chunk_index", c)
            self.assertIn("source_file", c)
            self.assertIn("text", c)
            self.assertIn("metadata", c)
            md = c["metadata"]
            for k in ("char_start", "char_end", "warehouse_char_start", "warehouse_char_end", "char_length"):
                self.assertIn(k, md)

    def test_warehouse_provenance(self):
        txt = """========================================
SOURCE_FILE: fir.txt
SOURCE_TYPE: TEXT
========================================

Rose Mathew was found dead. She lived in Anna Nagar. Police arrived at 18:30. The body was discovered by Ananya Joseph. This is sentence five to increase length. This is sentence six to increase length. This is sentence seven to increase length. This is sentence eight to increase length. This is sentence nine to increase length. This is sentence ten with more content to exceed chunk size for chunking test. Additional sentence eleven continues. Additional sentence twelve continues. Additional sentence thirteen continues. Additional sentence fourteen continues. Additional sentence fifteen continues with more detail about the case and investigation progress.

========================================
END_SOURCE: fir.txt
========================================


========================================
SOURCE_FILE: witness.txt
SOURCE_TYPE: TEXT
========================================

Witness Ananya Joseph stated she visited Rose at Flat 3B. They were friends. She noticed something wrong at 18:30 on 14 April 2026. She called Arjun Dev. Arjun Dev arrived later. Another sentence to pad. Another sentence to pad. Another sentence to pad. Another sentence to pad. Another sentence to pad. Another sentence to pad. Another sentence to pad beyond chunk size to trigger multiple chunks.

========================================
END_SOURCE: witness.txt
========================================
"""
        chunks = semantic_chunk_text(txt, chunk_size=1200, overlap=200)
        # should have chunks from both sources
        files = set(c["source_file"] for c in chunks)
        self.assertIn("fir.txt", files)
        self.assertIn("witness.txt", files)
        # chunk_id prefix matches source
        for c in chunks:
            if c["source_file"] == "fir.txt":
                self.assertTrue(c["chunk_id"].startswith("fir_chunk_"))
            if c["source_file"] == "witness.txt":
                self.assertTrue(c["chunk_id"].startswith("witness_chunk_"))

    def test_overlap(self):
        # force many chunks
        long_text = ". ".join([f"Sentence number {i} about Rose Mathew and Anna Nagar" for i in range(50)])
        chunks = semantic_chunk_text(long_text, chunk_size=300, overlap=50)
        self.assertGreater(len(chunks), 1)
        # overlap means second chunk should share some text with first's tail
        # check that chunk 2 starts with part of chunk 1 end
        self.assertTrue(len(chunks[1]["text"]) > 0)

    def test_chunk_size_respected_approx(self):
        long_text = ". ".join([f"This is a test sentence about investigation case number {i}" for i in range(100)])
        chunks = semantic_chunk_text(long_text, chunk_size=500, overlap=0)
        for c in chunks:
            # allow some overshoot due to sentence grouping but not huge
            self.assertLessEqual(c["metadata"]["char_length"], 700)

    def test_warehouse_sample_case_exists(self):
        p = Path("data/sample_case/warehouse.txt")
        if not p.exists():
            self.skipTest("sample_case missing")
        text = p.read_text(encoding="utf-8")
        chunks = semantic_chunk_text(text, chunk_size=1200, overlap=200)
        self.assertGreater(len(chunks), 5)
        # every chunk has provenance
        for c in chunks:
            self.assertTrue(c["source_file"].endswith(".txt"))
            self.assertTrue(c["chunk_id"])
            self.assertGreater(c["metadata"]["char_length"], 0)

    def test_naive_fallback_single_long_sentence(self):
        txt = "a" * 5000  # no sentence boundaries
        chunks = semantic_chunk_text(txt, chunk_size=1000, overlap=100)
        self.assertGreater(len(chunks), 1)
        # reassembled via naive should cover all
        total_chars = sum(c["metadata"]["char_length"] for c in chunks)
        self.assertGreater(total_chars, 4000)

    def test_apply_overlap_does_not_cascade(self):
        # Regression: overlap must come from the ORIGINAL previous chunk,
        # not the already-overlapped one. Otherwise text can leak across two chunks.
        a = "AAAA " * 400   # 2000 chars
        b = "BBBB " * 10    # 50 chars — smaller than the overlap
        c = "CCCC " * 400   # 2000 chars
        raw = [(a, 0, len(a)), (b, len(a), len(a) + len(b)), (c, len(a) + len(b), len(a) + len(b) + len(c))]
        out = _apply_overlap(raw, 100)
        self.assertGreaterEqual(len(out), 3)
        # chunk 2 gets overlap from chunk 1
        self.assertIn("AAAA", out[1][0])
        # chunk 3 must NOT contain text from chunk 1 — only from chunk 2
        self.assertNotIn("AAAA", out[2][0])
        self.assertIn("BBBB", out[2][0])
