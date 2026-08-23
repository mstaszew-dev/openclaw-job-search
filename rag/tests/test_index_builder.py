"""Tests for index_builder.py: corpus extraction, doc chunking, and index build.

Uses the MockEmbeddingModel (no model2vec download) and a tmp campaign dir so
the tests are deterministic and isolated from the real tracker.json.
"""
from __future__ import annotations

import json
import runpy
import sqlite3
import sys
from pathlib import Path
from types import ModuleType

import index_builder as ib
import pytest


class TestAppText:
    def test_basic_company_role(self):
        a = {"company": "Acme", "roleTitle": "Senior Java Engineer"}
        text = ib.app_text(a)
        assert "Senior Java Engineer" in text
        assert "Acme" in text

    def test_upweights_stack_by_repetition(self):
        """Stack tokens appear multiple times so they dominate the embedding."""
        a = {"company": "X", "roleTitle": "Dev", "stack": ["java", "spring"]}
        text = ib.app_text(a)
        # "java" / "spring" should appear more than once (the upweight).
        assert text.count("java") >= 2
        assert text.count("spring") >= 2

    def test_includes_source_and_currency_once(self):
        a = {
            "company": "X",
            "roleTitle": "Dev",
            "source": "nofluffjobs",
            "salarySeen": {"min": 100, "max": 200, "currency": "PLN"},
        }
        text = ib.app_text(a)
        assert text.count("nofluffjobs") == 1  # low-signal, not upweighted
        assert text.count("PLN") == 1

    def test_empty_app_yields_empty(self):
        assert ib.app_text({}) == ""

    def test_handles_missing_fields_gracefully(self):
        # No crash on partial records (some tracker entries lack stack/salary).
        text = ib.app_text({"roleTitle": "Dev"})
        assert "Dev" in text

    def test_scalar_stack_appended_once(self):
        """A non-list stack (legacy tracker rows) is included but NOT upweighted."""
        a = {"company": "X", "roleTitle": "Dev", "stack": "java"}
        text = ib.app_text(a)
        assert text.count("java") == 1


class TestChunkDoc:
    def test_splits_by_h2_headers(self):
        md = "# Title\n\nIntro.\n\n## Section A\nContent A.\n\n## Section B\nContent B.\n"
        chunks = ib.chunk_doc(md)
        titles = [t for t, _ in chunks]
        assert "(intro)" in titles
        assert "Section A" in titles
        assert "Section B" in titles

    def test_chunk_includes_its_header(self):
        md = "## IL portals\nAllJobs, Drushim.\n"
        chunks = ib.chunk_doc(md)
        assert len(chunks) == 1
        title, body = chunks[0]
        assert title == "IL portals"
        assert "IL portals" in body
        assert "AllJobs" in body

    def test_drops_empty_chunks(self):
        md = "## A\n\n## B\nContent.\n"
        chunks = ib.chunk_doc(md)
        # Section A had no body -> dropped.
        titles = [t for t, _ in chunks]
        assert "A" not in titles
        assert "B" in titles

    def test_no_headers_yields_single_intro_chunk(self):
        md = "Just a plain paragraph.\nNo headers here.\n"
        chunks = ib.chunk_doc(md)
        assert len(chunks) == 1
        assert chunks[0][0] == "(intro)"

    def test_drops_blank_intro_chunk(self):
        """Whitespace-only preamble yields an intro chunk with an empty body;
        it must be dropped, not indexed as a junk chunk."""
        md = "\n\n\n## Real\nContent.\n"
        chunks = ib.chunk_doc(md)
        titles = [t for t, _ in chunks]
        assert "(intro)" not in titles
        assert "Real" in titles


class TestCollectCorpus:
    def test_reads_applications_and_docs(self, tmp_campaign: Path):
        rows = ib.collect_corpus(tmp_campaign)
        apps = [r for r in rows if r["collection"] == "apps"]
        docs = [r for r in rows if r["collection"] == "docs"]
        assert len(apps) == 4  # sample_tracker has 4 apps
        assert len(docs) >= 2  # PORTALS.md has 2 ## sections (+ intro)

    def test_app_row_has_meta_and_text(self, tmp_campaign: Path):
        rows = [r for r in ib.collect_corpus(tmp_campaign) if r["collection"] == "apps"]
        r = rows[0]
        assert r["source"] == "tracker.json"
        assert r["meta"]["id"]
        assert r["meta"]["company"]
        assert r["chunk"]

    def test_missing_docs_are_skipped_gracefully(self, tmp_campaign: Path):
        # tmp_campaign only has PORTALS.md; the other 8 DOCS are absent.
        rows = ib.collect_corpus(tmp_campaign)
        doc_sources = {r["source"] for r in rows if r["collection"] == "docs"}
        assert doc_sources == {"PORTALS.md"}  # no crash, just the one present

    def test_apps_with_no_text_are_skipped(self, tmp_path: Path):
        """An application with no company/roleTitle produces no retrievable
        text and is dropped instead of indexing an empty chunk."""
        tracker = {
            "applications": [
                {"id": "ghost"},  # nothing indexable
                {"id": "real", "company": "Acme", "roleTitle": "Dev"},
            ]
        }
        (tmp_path / "tracker.json").write_text(json.dumps(tracker))
        (tmp_path / "PORTALS.md").write_text("## S\nBody.\n")
        rows = ib.collect_corpus(tmp_path)
        apps = [r for r in rows if r["collection"] == "apps"]
        assert len(apps) == 1
        assert apps[0]["meta"]["id"] == "real"


class TestWriteIndex:
    def test_writes_rows_and_vectors(self, tmp_path: Path):
        rows = [
            {"collection": "apps", "source": "t", "chunk": "java engineer", "meta": {"id": "1"}},
            {"collection": "docs", "source": "PORTALS.md", "chunk": "IL portals", "meta": {"header": "IL"}},
        ]
        import numpy as np

        vectors = np.array([[1.0, 0.0], [0.0, 1.0]], dtype=np.float32)
        db = tmp_path / "test.db"
        ib.write_index(rows, vectors, db)
        assert db.exists()
        conn = sqlite3.connect(db)
        n = conn.execute("SELECT COUNT(*) FROM chunks").fetchone()[0]
        assert n == 2
        cols = conn.execute(
            "SELECT collection, source, chunk, meta_json, vector_json FROM chunks ORDER BY id"
        ).fetchall()
        assert cols[0][0] == "apps"
        assert json.loads(cols[0][3])["id"] == "1"
        assert json.loads(cols[0][4]) == [1.0, 0.0]
        conn.close()

    def test_idempotent_rebuild(self, tmp_path: Path):
        import numpy as np

        db = tmp_path / "test.db"
        rows = [{"collection": "apps", "source": "t", "chunk": "a", "meta": {}}]
        ib.write_index(rows, np.array([[1.0]], dtype=np.float32), db)
        # Second write replaces, doesn't append.
        ib.write_index(rows, np.array([[1.0]], dtype=np.float32), db)
        conn = sqlite3.connect(db)
        n = conn.execute("SELECT COUNT(*) FROM chunks").fetchone()[0]
        conn.close()
        assert n == 1


class TestBuild:
    def test_build_with_mock_model(self, tmp_campaign: Path, mock_model, tmp_path: Path):
        db = tmp_path / "index.db"
        ib.build(model=mock_model, campaign=tmp_campaign, db_path=db)
        assert db.exists()
        conn = sqlite3.connect(db)
        n = conn.execute("SELECT COUNT(*) FROM chunks").fetchone()[0]
        conn.close()
        # 4 apps + >=2 doc chunks from PORTALS.md
        assert n >= 6

    def test_check_only_does_not_write(self, tmp_campaign: Path, mock_model, tmp_path: Path):
        db = tmp_path / "index.db"
        ib.build(check_only=True, model=mock_model, campaign=tmp_campaign, db_path=db)
        assert not db.exists()

    def test_build_then_load_round_trip(self, tmp_campaign: Path, mock_model, tmp_path: Path):
        """Build writes vectors that load_index can read back."""
        import rag_server

        db = tmp_path / "index.db"
        ib.build(model=mock_model, campaign=tmp_campaign, db_path=db)
        matrix, rows = rag_server.load_index(db)
        assert matrix.shape[0] == len(rows)
        assert matrix.shape[0] >= 6
        assert matrix.shape[1] > 0  # non-zero dim

    def test_build_loads_default_model_when_none_given(
        self, monkeypatch, tmp_campaign: Path, mock_model, tmp_path: Path
    ):
        """model=None triggers StaticModel.from_pretrained(MODEL) - the
        production entry path. The model download seam is faked."""
        requested = []

        class FakeStaticModel:
            @classmethod
            def from_pretrained(cls, name):
                requested.append(name)
                return mock_model

        monkeypatch.setattr(ib, "StaticModel", FakeStaticModel)
        db = tmp_path / "index.db"
        ib.build(model=None, campaign=tmp_campaign, db_path=db)
        assert requested == [ib.MODEL]
        assert db.exists()


class TestMainGuard:
    def test_dash_check_reports_counts_without_writing(self, monkeypatch, capsys, mock_model):
        """Running the file as a script with --check prints corpus counts and
        exits without building. The model2vec import seam is faked so no
        download happens; --check is read-only over the real campaign corpus."""
        requested = []

        class FakeStaticModel:
            @classmethod
            def from_pretrained(cls, name):
                requested.append(name)
                return mock_model

        fake_m2v = ModuleType("model2vec")
        fake_m2v.StaticModel = FakeStaticModel
        monkeypatch.setitem(sys.modules, "model2vec", fake_m2v)
        monkeypatch.setattr(sys, "argv", ["index_builder.py", "--check"])
        module_path = Path(ib.__file__).resolve()

        runpy.run_path(str(module_path), run_name="__main__")

        out = capsys.readouterr().out
        assert "Loading embedding model:" in out
        assert "Corpus:" in out
        assert requested == [ib.MODEL]


def test_build_stamps_meta_with_built_at_and_app_count(tmp_campaign, mock_model, tmp_path):
    """Every build records built_at (UTC ISO) + n_apps in a _meta table so the
    server can detect staleness against the live tracker."""
    import sqlite3

    db = tmp_path / "stamped.db"
    ib.build(model=mock_model, campaign=tmp_campaign, db_path=db)

    conn = sqlite3.connect(db)
    try:
        rows = dict(conn.execute("SELECT key, value FROM _meta").fetchall())
    finally:
        conn.close()
    assert "built_at" in rows and "T" in rows["built_at"]  # ISO timestamp
    assert int(rows["n_apps"]) == 4  # tmp_campaign fixture has 4 applications
