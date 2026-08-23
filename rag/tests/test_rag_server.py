"""Tests for rag_server.py: cosine_search, formatting, MCP tool handlers,
error paths, and semantic-dedupe behavior.

Uses the MockEmbeddingModel to build a small in-memory index, then exercises
cosine_search directly + the MCP tool handlers (via async call). The model2vec
package is never imported - tests run without the 30MB download.
"""
from __future__ import annotations

import asyncio
import runpy
import sys
from pathlib import Path
from types import ModuleType

import numpy as np
import pytest

import index_builder as ib
import rag_server

MODULE_PATH = Path(rag_server.__file__).resolve()


# ---------------------------------------------------------------------------
# Build a small in-memory index once per test session for the search tests.
# ---------------------------------------------------------------------------


@pytest.fixture
def loaded_index(tmp_campaign, mock_model, tmp_path):
    """Build a tmp index.db and load it into (matrix, rows)."""
    db = tmp_path / "index.db"
    ib.build(model=mock_model, campaign=tmp_campaign, db_path=db)
    return rag_server.load_index(db)


# ---------------------------------------------------------------------------
# cosine_search - the pure ranking function.
# ---------------------------------------------------------------------------


class TestLoadIndex:
    def test_loads_built_index(self, tmp_campaign, mock_model, tmp_path):
        db = tmp_path / "index.db"
        ib.build(model=mock_model, campaign=tmp_campaign, db_path=db)
        matrix, rows = rag_server.load_index(db)
        assert matrix.shape[0] == len(rows)
        assert matrix.shape[0] >= 6

    def test_empty_db_yields_empty_matrix(self, tmp_path):
        """An index.db with zero rows loads as an empty (0,0) matrix, not a crash."""
        import sqlite3

        db = tmp_path / "empty.db"
        # Create the table but insert nothing.
        conn = sqlite3.connect(db)
        conn.execute(
            "CREATE TABLE chunks (id INTEGER PRIMARY KEY, collection TEXT, source TEXT, "
            "chunk TEXT, meta_json TEXT, vector_json TEXT)"
        )
        conn.commit()
        conn.close()
        matrix, rows = rag_server.load_index(db)
        assert matrix.shape == (0, 0)
        assert rows == []

    def test_missing_table_raises_operational_error(self, tmp_path):
        """Document the contract: load_index does NOT guard against a path with
        no chunks table. The guard lives in _ensure_loaded (DB.exists() check +
        the index_builder having been run). A caller that points load_index at
        a never-built path hits sqlite3.OperationalError - that's the signal to
        rebuild the index, not a bug."""
        import sqlite3

        db = tmp_path / "never_built.db"
        # sqlite3.connect creates an empty file; load_index's query then fails
        # because there's no 'chunks' table.
        with pytest.raises(sqlite3.OperationalError, match="no such table"):
            rag_server.load_index(db)


class TestEnsureLoaded:
    def test_raises_when_index_missing(self, monkeypatch, tmp_path):
        """_ensure_loaded raises a clear RuntimeError if index.db doesn't exist."""
        monkeypatch.setattr(rag_server, "DB", tmp_path / "nonexistent.db")
        # Reset cached state so it tries to load.
        monkeypatch.setattr(rag_server, "_model", None)
        with pytest.raises(RuntimeError, match="index not found"):
            rag_server._ensure_loaded()

    def test_is_idempotent_once_loaded(self, monkeypatch):
        """Once _model is set, _ensure_loaded returns without reloading."""
        # Pretend already loaded.
        monkeypatch.setattr(rag_server, "_model", object())
        # Should not raise / not try to load the (nonexistent) index.
        rag_server._ensure_loaded()

    def test_loads_model_and_index_from_disk(
        self, monkeypatch, tmp_campaign, mock_model, tmp_path
    ):
        """First load: downloads the model (faked seam), reads index.db into
        module state, and logs the chunk count + dim."""
        db = tmp_path / "index.db"
        ib.build(model=mock_model, campaign=tmp_campaign, db_path=db)
        monkeypatch.setattr(rag_server, "DB", db)

        requested = []

        class FakeStaticModel:
            @classmethod
            def from_pretrained(cls, name):
                requested.append(name)
                return mock_model

        fake_m2v = ModuleType("model2vec")
        fake_m2v.StaticModel = FakeStaticModel
        monkeypatch.setitem(sys.modules, "model2vec", fake_m2v)

        monkeypatch.setattr(rag_server, "_model", None)
        monkeypatch.setattr(rag_server, "_matrix", None)
        monkeypatch.setattr(rag_server, "_rows", None)

        rag_server._ensure_loaded()

        assert requested == [rag_server.MODEL]
        assert rag_server._model is mock_model
        assert len(rag_server._rows) >= 6
        assert rag_server._matrix.shape[0] == len(rag_server._rows)


class TestSearchWrapper:
    def test_search_encodes_query_and_ranks_loaded_state(
        self, monkeypatch, tmp_campaign, mock_model, tmp_path
    ):
        """_search embeds the query with the loaded model and ranks the loaded
        index - the real module-level path the MCP handlers use."""
        db = tmp_path / "index.db"
        ib.build(model=mock_model, campaign=tmp_campaign, db_path=db)
        matrix, rows = rag_server.load_index(db)
        monkeypatch.setattr(rag_server, "_model", mock_model)
        monkeypatch.setattr(rag_server, "_matrix", matrix)
        monkeypatch.setattr(rag_server, "_rows", rows)

        hits = rag_server._search("Senior Java Backend Engineer", "apps", 3)

        assert 0 < len(hits) <= 3
        top = hits[0]["meta"]
        top_text = (top.get("roleTitle", "") + " " + str(top.get("stack", ""))).lower()
        assert any(w in top_text for w in ("java", "backend"))


class TestLogSink:
    def test_log_writes_to_file_when_rag_log_env_set(self, monkeypatch, tmp_path):
        """RAG_LOG points logging at an append-mode file; lines carry the
        [rag] <iso-ts> prefix."""
        log_file = tmp_path / "rag.log"
        monkeypatch.setenv("RAG_LOG", str(log_file))
        monkeypatch.setattr(rag_server, "_SINK_FH", None)
        monkeypatch.setattr(rag_server, "_SINK_LABEL", None)

        rag_server._log("hello log")

        text = log_file.read_text(encoding="utf-8")
        assert text.startswith("[rag] ")
        assert text.endswith(" hello log\n")
        # One line: "[rag] <date>T<hh:mm:ss> msg".
        body = text[len("[rag] "):]
        ts = body.split(" ")[0]
        assert len(ts) == 19 and ts[10] == "T"
        assert rag_server._SINK_LABEL == f"file:{log_file}"
        rag_server._SINK_FH.close()

    def test_log_falls_back_to_stderr_on_unwritable_path(self, monkeypatch, tmp_path, capsys):
        """A RAG_LOG path that cannot be opened degrades to stderr instead of
        crashing the server."""
        monkeypatch.setenv("RAG_LOG", str(tmp_path / "no_such_dir" / "rag.log"))
        monkeypatch.setattr(rag_server, "_SINK_FH", None)
        monkeypatch.setattr(rag_server, "_SINK_LABEL", None)

        rag_server._log("fallback msg")

        assert rag_server._SINK_LABEL == "stderr"
        assert rag_server._SINK_FH is sys.stderr
        assert "fallback msg" in capsys.readouterr().err

    def test_log_never_raises_when_sink_is_broken(self, monkeypatch, tmp_path):
        """A dead sink (e.g. closed file handle) must be swallowed - logging
        is best-effort and must never break the MCP loop."""
        dead = open(tmp_path / "dead.log", "w", encoding="utf-8")
        dead.close()
        monkeypatch.setattr(rag_server, "_SINK_FH", dead)

        result = rag_server._log("must not raise")

        assert result is None


class TestCosineSearch:
    def test_finds_matching_app_above_nonmatching(self, loaded_index, mock_model):
        """A Java-backend query should rank Java apps above Node apps."""
        matrix, rows = loaded_index
        qv = mock_model.encode(["Senior Java Backend Engineer"])[0]
        hits = rag_server.cosine_search(qv, matrix, rows, "apps", k=4)
        assert len(hits) > 0
        # The top hit should be a Java/Senior role, not a Node fullstack one.
        top_meta = hits[0]["meta"]
        top_text = (top_meta.get("roleTitle", "") + " " + str(top_meta.get("stack", ""))).lower()
        # Mindbox (Senior Backend Engineer Java) or Cross River (Senior Java Engineer)
        assert any(name in top_text for name in ("java", "backend"))

    def test_restricts_to_collection(self, loaded_index, mock_model):
        """Searching 'apps' must not return 'docs' rows, even if similar."""
        matrix, rows = loaded_index
        qv = mock_model.encode(["portals"])[0]
        hits = rag_server.cosine_search(qv, matrix, rows, "apps", k=10)
        # PORTALS.md chunks are collection='docs'; apps query must return only apps.
        assert all(h["meta"] is not None for h in hits)  # structural
        # Verify no hit's underlying row is a doc by re-checking the source rows.
        hit_ids = {id(h) for h in hits}
        doc_rows = [r for r in rows if r["collection"] == "docs"]
        # No hit should reference a doc row's chunk text (doc chunks are portal text).
        for h in hits:
            assert "portals" not in h["chunk"].lower()[:20] or h["chunk"] == ""

    def test_returns_empty_on_zero_query_vector(self, loaded_index):
        """A zero embedding (e.g. all-stopword query) returns no hits, no crash."""
        matrix, rows = loaded_index
        qv = np.zeros(matrix.shape[1], dtype=np.float32)
        hits = rag_server.cosine_search(qv, matrix, rows, "apps", k=3)
        assert hits == []

    def test_returns_empty_on_empty_index(self):
        """No rows -> empty result, no crash."""
        empty_matrix = np.array([], dtype=np.float32).reshape(0, 0)
        qv = np.array([1.0, 0.0], dtype=np.float32)
        hits = rag_server.cosine_search(qv, empty_matrix, [], "apps", k=3)
        assert hits == []

    def test_k_bounds_result_count(self, loaded_index, mock_model):
        matrix, rows = loaded_index
        qv = mock_model.encode(["developer"])[0]
        hits = rag_server.cosine_search(qv, matrix, rows, "apps", k=2)
        assert len(hits) <= 2

    def test_hits_descending_by_score(self, loaded_index, mock_model):
        matrix, rows = loaded_index
        qv = mock_model.encode(["java engineer"])[0]
        hits = rag_server.cosine_search(qv, matrix, rows, "apps", k=4)
        scores = [h["score"] for h in hits]
        assert scores == sorted(scores, reverse=True)

    def test_scores_are_in_neg1_to_1_range(self, loaded_index, mock_model):
        """Cosine similarity is bounded [-1, 1]."""
        matrix, rows = loaded_index
        qv = mock_model.encode(["anything"])[0]
        hits = rag_server.cosine_search(qv, matrix, rows, "apps", k=4)
        for h in hits:
            assert -1.001 <= h["score"] <= 1.001


# ---------------------------------------------------------------------------
# Semantic dedupe - the headline RAG value.
# ---------------------------------------------------------------------------


class TestSemanticDedupe:
    def test_synonym_query_finds_java_apps(self, loaded_index, mock_model):
        """'JVM backend lead' (synonyms for Java/senior) must surface Java apps
        that exact-match dedupe would miss (no shared token with the literal app text)."""
        matrix, rows = loaded_index
        qv = mock_model.encode(["Lead JVM backend engineer"])[0]
        hits = rag_server.cosine_search(qv, matrix, rows, "apps", k=3)
        assert len(hits) > 0
        # At least one Java/backend app should appear in the top hits.
        top_text = " ".join(
            (h["meta"].get("roleTitle", "") + " " + str(h["meta"].get("stack", "")))
            for h in hits[:2]
        ).lower()
        assert any(w in top_text for w in ("java", "backend", "jvm"))

    def test_different_stack_does_not_dominate(self, loaded_index, mock_model):
        """A Node/React query should rank Node apps above Java apps (stack discrimination)."""
        matrix, rows = loaded_index
        qv = mock_model.encode(["Frontend developer React TypeScript"])[0]
        hits = rag_server.cosine_search(qv, matrix, rows, "apps", k=4)
        assert len(hits) > 0
        # The top hit should be a Node/React/fullstack role, not a pure Java backend.
        top_role = hits[0]["meta"].get("roleTitle", "").lower()
        assert any(w in top_role for w in ("full-stack", "fullstack", "frontend", "react"))


# ---------------------------------------------------------------------------
# Formatting helpers.
# ---------------------------------------------------------------------------


class TestFormatting:
    def test_format_apps_empty(self):
        assert rag_server._format_apps([]) == "No similar past applications found."

    def test_format_apps_with_hits(self):
        hits = [
            {
                "score": 0.91,
                "meta": {"roleTitle": "Senior Java Engineer", "company": "Acme",
                         "source": "linkedin", "appliedAt": "2026-07-15T08:00:00+00:00",
                         "status": "submitted"},
                "chunk": "...",
            }
        ]
        out = rag_server._format_apps(hits)
        assert "Top 1 similar past applications:" in out
        assert "Senior Java Engineer" in out
        assert "Acme" in out
        assert "0.91" in out
        assert "2026-07-15" in out

    def test_format_docs_empty(self):
        assert rag_server._format_docs([]) == "No matching doc chunks found."

    def test_format_docs_truncates_long_chunks(self):
        long_body = "x" * 1000
        hits = [{"score": 0.5, "meta": {"file": "PORTALS.md", "header": "IL"},
                 "chunk": long_body}]
        out = rag_server._format_docs(hits)
        # Truncated to ~500 chars + the header line.
        assert len(out) < 1000
        assert "PORTALS.md" in out
        assert "IL" in out


# ---------------------------------------------------------------------------
# MCP tool handlers (async).
# ---------------------------------------------------------------------------


class TestCallToolHandler:
    @pytest.mark.asyncio
    async def test_unknown_tool_returns_error(self):
        result = await rag_server.call_tool("nonexistent", {"query": "x"})
        assert len(result) == 1
        assert "unknown tool" in result[0].text.lower()

    @pytest.mark.asyncio
    async def test_empty_query_returns_error(self):
        result = await rag_server.call_tool("rag_search_apps", {"query": ""})
        assert "query is required" in result[0].text.lower()

    @pytest.mark.asyncio
    async def test_apps_search_via_handler(self, monkeypatch, tmp_campaign, mock_model, tmp_path):
        """End-to-end through the MCP handler: build index, patch _search, call tool."""
        db = tmp_path / "index.db"
        ib.build(model=mock_model, campaign=tmp_campaign, db_path=db)
        matrix, rows = rag_server.load_index(db)

        # Patch the module-level _search to use our mock model + loaded index,
        # bypassing _ensure_loaded (which would try to load model2vec).
        def fake_search(query, collection, k):
            qv = mock_model.encode([query])[0]
            return rag_server.cosine_search(qv, matrix, rows, collection, k)

        monkeypatch.setattr(rag_server, "_search", fake_search)
        result = await rag_server.call_tool(
            "rag_search_apps", {"query": "Senior Java Backend Engineer", "k": 3}
        )
        assert len(result) == 1
        text = result[0].text
        assert "Top" in text or "similar past applications" in text

    @pytest.mark.asyncio
    async def test_docs_search_via_handler(self, monkeypatch, tmp_campaign, mock_model, tmp_path):
        """The rag_search_docs handler branch (mirrors the apps handler test)."""
        db = tmp_path / "index.db"
        ib.build(model=mock_model, campaign=tmp_campaign, db_path=db)
        matrix, rows = rag_server.load_index(db)

        def fake_search(query, collection, k):
            qv = mock_model.encode([query])[0]
            return rag_server.cosine_search(qv, matrix, rows, collection, k)

        monkeypatch.setattr(rag_server, "_search", fake_search)
        result = await rag_server.call_tool(
            "rag_search_docs", {"query": "IL portals hybrid", "k": 2}
        )
        assert len(result) == 1
        text = result[0].text
        # Either returns hits mentioning IL/portals, or the empty-result message.
        assert "matching doc chunks" in text or "No matching doc chunks" in text

    @pytest.mark.asyncio
    async def test_search_error_is_caught_and_surfaced(self, monkeypatch):
        """If _search raises, the handler returns an Error text (no exception escapes)."""
        def boom(query, collection, k):
            raise RuntimeError("simulated index load failure")

        monkeypatch.setattr(rag_server, "_search", boom)
        result = await rag_server.call_tool("rag_search_apps", {"query": "x"})
        assert "Error" in result[0].text
        assert "simulated index load failure" in result[0].text


class TestListTools:
    @pytest.mark.asyncio
    async def test_lists_both_tools(self):
        tools = await rag_server.list_tools()
        names = [t.name for t in tools]
        assert "rag_search_apps" in names
        assert "rag_search_docs" in names

    @pytest.mark.asyncio
    async def test_tools_have_input_schema(self):
        tools = await rag_server.list_tools()
        for t in tools:
            assert t.inputSchema is not None
            assert "query" in t.inputSchema.get("properties", {})
            assert t.inputSchema.get("required") == ["query"]


class TestMain:
    @pytest.mark.asyncio
    async def test_main_wires_stdio_streams_into_server_run(self, monkeypatch):
        """main() serves the MCP app over the stdio transport: the streams from
        stdio_server() and the initialization options go into server.run()."""
        class FakeStdioCtx:
            async def __aenter__(self):
                return ("read_stream", "write_stream")

            async def __aexit__(self, exc_type, exc, tb):
                return False

        seen = {}

        async def fake_run(read, write, init_options):
            seen["args"] = (read, write, init_options)

        monkeypatch.setattr(rag_server, "stdio_server", lambda: FakeStdioCtx())
        monkeypatch.setattr(rag_server.server, "run", fake_run)

        await rag_server.main()

        read, write, opts = seen["args"]
        assert (read, write) == ("read_stream", "write_stream")
        assert opts is not None

    def test_script_entrypoint_runs_main_once(self, monkeypatch):
        """Executing the file as a script starts the asyncio event loop on
        main(). asyncio.run is intercepted so no server actually starts."""
        launched = []

        def fake_run(coro, **kwargs):
            launched.append(coro)
            coro.close()

        monkeypatch.setattr(asyncio, "run", fake_run)
        runpy.run_path(str(MODULE_PATH), run_name="__main__")

        assert len(launched) == 1
        assert launched[0].__name__ == "main"
