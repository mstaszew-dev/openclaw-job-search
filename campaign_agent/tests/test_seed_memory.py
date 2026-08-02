"""Tests for seed_memory — chunking, embedding, and Pinecone upsert payload."""
import json
from unittest.mock import MagicMock, patch

from campaign_agent.seed_memory import (
    chunk_markdown,
    collect_seed_files,
    build_vectors,
    build_upsert_payload,
    upsert_to_pinecone,
)

SAMPLE_MD = """# Header

intro text

## Section One

alpha content

## Section Two

beta content
"""


class TestChunkMarkdown:
    def test_splits_by_h2_headings(self):
        chunks = chunk_markdown(SAMPLE_MD, "test.md", "rules")
        # leading content (before first ##) is kept as its own chunk
        assert len(chunks) == 3
        assert "intro text" in chunks[0]["text"]
        assert chunks[1]["text"].startswith("## Section One")
        assert "alpha content" in chunks[1]["text"]
        assert chunks[2]["text"].startswith("## Section Two")

    def test_metadata_carries_source_and_kind(self):
        chunks = chunk_markdown(SAMPLE_MD, "KEYWORDS.md", "keyword")
        for c in chunks:
            assert c["metadata"]["source"] == "KEYWORDS.md"
            assert c["metadata"]["kind"] == "keyword"

    def test_ids_are_unique_and_stable(self):
        chunks = chunk_markdown(SAMPLE_MD, "f.md", "rules")
        ids = [c["id"] for c in chunks]
        assert len(ids) == len(set(ids))
        assert ids[0] == "f.md::0"

    def test_empty_input_yields_no_chunks(self):
        assert chunk_markdown("", "f.md", "rules") == []


class TestCollectSeedFiles:
    def test_finds_seed_globs(self, tmp_path):
        (tmp_path / "KEYWORDS.md").write_text("k")
        (tmp_path / "memories").mkdir()
        (tmp_path / "memories" / "MEMORY.md").write_text("m")
        (tmp_path / "rules").mkdir()
        (tmp_path / "rules" / "global").mkdir()
        (tmp_path / "rules" / "global" / "94-tested-code-by-default.md").write_text("r")
        (tmp_path / "scratch.md").write_text("ignore me")

        files = collect_seed_files(str(tmp_path))
        names = [f.name for f in files]
        assert "KEYWORDS.md" in names
        assert "MEMORY.md" in names
        assert "94-tested-code-by-default.md" in names
        assert "scratch.md" not in names


class TestBuildVectors:
    def test_embeds_each_chunk_with_256_dim(self):
        chunks = chunk_markdown(SAMPLE_MD, "f.md", "rules")
        embed_fn = MagicMock(return_value=[0.1] * 256)
        vectors = build_vectors(chunks, embed_fn)
        assert len(vectors) == len(chunks)
        assert len(vectors[0]["values"]) == 256
        assert vectors[0]["id"].startswith("f.md::")
        assert vectors[0]["metadata"]["kind"] == "rules"

    def test_payload_shape_for_pc_cli(self):
        chunks = chunk_markdown(SAMPLE_MD, "f.md", "rules")
        embed_fn = MagicMock(return_value=[0.5] * 256)
        vectors = build_vectors(chunks, embed_fn)
        payload = build_upsert_payload(vectors)
        data = json.loads(payload)
        assert isinstance(data, list)
        assert {"id", "values", "metadata"} <= set(data[0].keys())


class TestUpsertToPinecone:
    def test_invokes_pc_cli_with_index_and_file(self):
        with patch("campaign_agent.seed_memory.subprocess.run") as mock_run:
            mock_run.return_value = MagicMock(returncode=0)
            upsert_to_pinecone("agent-memory", "/tmp/vectors.json")
        call = mock_run.call_args
        assert "pc" in call.args[0]
        assert "upsert" in call.args[0]
        assert "--index-name" in call.args[0] or "-n" in call.args[0]
