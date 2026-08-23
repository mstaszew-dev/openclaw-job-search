"""Tests for seed_memory — chunking, embedding, and Pinecone upsert payload."""
import json
import os
import runpy
import sys
from unittest.mock import MagicMock, patch

import pytest

from campaign_agent.seed_memory import (
    chunk_markdown,
    collect_seed_files,
    build_vectors,
    build_upsert_payload,
    upsert_to_pinecone,
    _embed_fn,
    main,
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


class TestChunkTruncation:
    def test_truncates_oversized_chunks(self):
        long_text = "## A\n" + "x" * 5000
        chunks = chunk_markdown(long_text, "f.md", "rules", max_chars=100)
        assert len(chunks) == 1
        assert chunks[0]["text"].endswith("...[truncated]")
        assert len(chunks[0]["text"]) < 200

    def test_no_truncation_under_limit(self):
        chunks = chunk_markdown("## A\nshort body", "f.md", "rules", max_chars=4000)
        assert "[truncated]" not in chunks[0]["text"]

    def test_truncates_final_chunk_without_trailing_heading(self):
        """The trailing chunk (no following '## ') must also truncate."""
        text = "## A\n" + "y" * 5000  # no trailing heading
        chunks = chunk_markdown(text, "f.md", "rules", max_chars=50)
        assert chunks[0]["text"].endswith("...[truncated]")

    def test_truncates_an_early_chunk_before_next_heading(self):
        """A chunk followed by another '## ' heading must truncate too."""
        text = "## A\n" + "x" * 5000 + "\n## B\nshort tail"
        chunks = chunk_markdown(text, "f.md", "rules", max_chars=100)
        assert len(chunks) == 2
        assert chunks[0]["text"].endswith("...[truncated]")
        assert chunks[1]["text"] == "## B\nshort tail"


class TestEmbedFn:
    def test_returns_256_dim_float_list(self, monkeypatch):
        """_embed_fn lazily imports model2vec (not in the campaign venv), so
        inject a fake module - the lazy import must pick it up at call time."""
        import sys
        from types import ModuleType

        fake = ModuleType("model2vec")
        fake_static = MagicMock()
        fake_static.from_pretrained.return_value.encode.return_value = [0.25] * 256
        fake.StaticModel = fake_static
        monkeypatch.setitem(sys.modules, "model2vec", fake)

        fn = _embed_fn()
        out = fn("hello world")
        assert len(out) == 256
        assert all(isinstance(x, float) for x in out)
        assert out[0] == 0.25
        fake_static.from_pretrained.assert_called_once()


class TestMain:
    def test_no_seed_files_returns_1(self, tmp_path):
        assert main(["idx", str(tmp_path)]) == 1

    def test_seeds_and_upserts_success(self, tmp_path):
        (tmp_path / "KEYWORDS.md").write_text("## K\nkeyword content")
        (tmp_path / "memories").mkdir()
        (tmp_path / "memories" / "MEMORY.md").write_text("## M\nmemory content")
        (tmp_path / "rules").mkdir()
        (tmp_path / "rules" / "global").mkdir()
        (tmp_path / "rules" / "global" / "10-search.md").write_text("## R\nrule content")

        with patch(
            "campaign_agent.seed_memory._embed_fn", return_value=lambda t: [0.1] * 256
        ), patch("campaign_agent.seed_memory.upsert_to_pinecone") as mock_up:
            mock_up.return_value = MagicMock(returncode=0)
            rc = main(["agent-memory", str(tmp_path)])

        assert rc == 0
        assert mock_up.call_count == 1
        index, vectors_path = mock_up.call_args[0]
        assert index == "agent-memory"
        # Vectors file was written and is valid JSON with all chunks.
        with open(vectors_path) as f:
            data = json.load(f)
        assert len(data) == 3  # one chunk per seed file

    def test_kind_classification_by_source(self, tmp_path):
        """KEYWORDS.md -> keyword, memories/* -> memory, rules/global/* -> rules."""
        (tmp_path / "KEYWORDS.md").write_text("## K\ncontent")
        (tmp_path / "memories").mkdir()
        (tmp_path / "memories" / "MEMORY.md").write_text("## M\ncontent")
        (tmp_path / "rules").mkdir()
        (tmp_path / "rules" / "global").mkdir()
        (tmp_path / "rules" / "global" / "r.md").write_text("## R\ncontent")

        with patch(
            "campaign_agent.seed_memory._embed_fn", return_value=lambda t: [0.1] * 256
        ), patch("campaign_agent.seed_memory.upsert_to_pinecone") as mock_up:
            mock_up.return_value = MagicMock(returncode=0)
            main(["idx", str(tmp_path)])

        _, vectors_path = mock_up.call_args[0]
        with open(vectors_path) as f:
            data = json.load(f)
        kinds = {v["metadata"]["kind"] for v in data}
        assert kinds == {"keyword", "memory", "rules"}

    def test_returns_1_on_upsert_failure(self, tmp_path):
        (tmp_path / "KEYWORDS.md").write_text("## K\ncontent")
        with patch(
            "campaign_agent.seed_memory._embed_fn", return_value=lambda t: [0.1] * 256
        ), patch("campaign_agent.seed_memory.upsert_to_pinecone") as mock_up:
            mock_up.return_value = MagicMock(returncode=1, stderr="pc: boom")
            rc = main(["idx", str(tmp_path)])
        assert rc == 1


class TestScriptEntryPoint:
    def test_dunder_main_seeds_and_upserts_end_to_end(self, monkeypatch, tmp_path):
        """Executing the module as a script (python -m campaign_agent.seed_memory)
        must parse argv, bootstrap logging, seed the context files, and exit 0
        after the upsert. Real file collection + chunking + payload write run
        end-to-end; only the two genuine external seams are faked: the
        model2vec embedder (not installed in this venv) and the pc CLI binary
        (resolved on PATH)."""
        from types import ModuleType

        context = tmp_path / "context"
        (context / "memories").mkdir(parents=True)
        (context / "KEYWORDS.md").write_text("## K\nkeyword content")
        (context / "memories" / "MEMORY.md").write_text("## M\nmemory content")

        # Genuine seam 1: lazy model2vec import resolves to an injected fake.
        fake_mod = ModuleType("model2vec")
        fake_static = MagicMock()
        fake_static.from_pretrained.return_value.encode.return_value = [0.25] * 256
        fake_mod.StaticModel = fake_static
        monkeypatch.setitem(sys.modules, "model2vec", fake_mod)

        # Genuine seam 2: a fake `pc` on PATH captures the --file payload.
        bin_dir = tmp_path / "bin"
        bin_dir.mkdir()
        captured = tmp_path / "captured-vectors.json"
        pc = bin_dir / "pc"
        pc.write_text(
            "#!/bin/sh\n"
            'prev=""\n'
            'for arg in "$@"; do\n'
            '  if [ "$prev" = "--file" ]; then cp "$arg" "$PC_CAPTURE"; fi\n'
            '  prev="$arg"\n'
            "done\n"
            "exit 0\n"
        )
        pc.chmod(0o755)
        monkeypatch.setenv("PATH", str(bin_dir) + os.pathsep + os.environ.get("PATH", ""))
        monkeypatch.setenv("PC_CAPTURE", str(captured))

        monkeypatch.setattr(
            sys, "argv", ["seed_memory.py", "agent-memory", str(context)]
        )

        import warnings

        with pytest.raises(SystemExit) as excinfo, warnings.catch_warnings():
            # runpy warns that the module is already imported; re-execution
            # under __main__ is exactly the entry-point behavior under test.
            warnings.simplefilter("ignore", RuntimeWarning)
            runpy.run_module("campaign_agent.seed_memory", run_name="__main__")
        assert excinfo.value.code == 0

        data = json.loads(captured.read_text())
        assert {v["id"] for v in data} == {
            f"{context / 'KEYWORDS.md'}::0",
            f"{context / 'memories' / 'MEMORY.md'}::0",
        }
        kinds = {v["metadata"]["kind"] for v in data}
        assert kinds == {"keyword", "memory"}
        for v in data:
            assert len(v["values"]) == 256
            assert all(isinstance(x, float) for x in v["values"])
        fake_static.from_pretrained.assert_called_once()
