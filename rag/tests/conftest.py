"""Shared test fixtures for the RAG server tests.

The MockEmbeddingModel simulates semantic similarity deterministically: text
that shares tokens (or known synonyms) embeds close together. This lets the
tests assert real ranking behavior (Java query -> Java apps ranked above Node
apps) WITHOUT downloading the 30MB model2vec model or needing the network.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pytest

# Make the rag/ dir importable as a package root.
RAG_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(RAG_ROOT))


# Synonym groups: tokens that should embed near each other even though they
# differ as strings. Lets the mock model simulate "real" semantic similarity
# (e.g. "JVM" ~ "Java") for the dedupe tests.
SYNONYMS = [
    {"java", "jvm", "kotlin", "spring"},
    {"node", "nodejs", "node.js", "javascript", "typescript"},
    {"backend", "server", "back-end"},
    {"frontend", "front-end", "ui", "react"},
    {"senior", "lead", "sr", "principal"},
    {"engineer", "developer", "dev", "programmer"},
    {"remote", "remote-only"},
    {"poland", "pl", "polish"},
    {"israel", "il", "israeli"},
]


def _tokenize(text: str) -> set[str]:
    """Lowercase, split on non-alphanumeric, drop empties + stopwords."""
    stop = {"at", "the", "a", "an", "for", "with", "and", "to", "of", "in", "on"}
    raw = set(t for t in "".join(c if c.isalnum() else " " for c in text.lower()).split() if t and t not in stop)
    # Expand each token to its synonym group so 'jvm' counts as 'java'.
    expanded = set(raw)
    for tok in list(raw):
        for group in SYNONYMS:
            if tok in group:
                expanded |= group
    return expanded


class MockEmbeddingModel:
    """Deterministic mock of model2vec.StaticModel.

    Encodes text into a 64-dim bag-of-concept vector: each dimension = presence
    of one synonym group / high-frequency token. Cosine similarity then
    approximates semantic overlap (shared concepts), exactly what the real
    model does at a coarser grain. Identical text -> sim 1.0; disjoint text ->
    sim 0.0; synonym text (Java/JVM) -> high sim.

    Implements the only method the production code uses: encode(list[str]).
    """

    DIM = 64

    def __init__(self) -> None:
        # Fixed vocabulary of "concept" buckets so vectors are stable per text.
        # Build a deterministic token->dim map on first use.
        self._token_dim: dict[str, int] = {}
        self._next_dim = 0

    def _dim_for(self, tok: str) -> int:
        if tok not in self._token_dim:
            self._token_dim[tok] = self._next_dim % self.DIM
            self._next_dim += 1
        return self._token_dim[tok]

    def encode(self, texts):
        out = np.zeros((len(texts), self.DIM), dtype=np.float32)
        for i, t in enumerate(texts):
            toks = _tokenize(t)
            for tok in toks:
                out[i][self._dim_for(tok)] += 1.0
        return out


@pytest.fixture
def mock_model() -> MockEmbeddingModel:
    """A fresh mock model per test (clean vocab state)."""
    return MockEmbeddingModel()


@pytest.fixture
def sample_tracker() -> dict:
    """A small tracker fixture spanning the stacks the tests discriminate on."""
    return {
        "targetApplications": 100,
        "stats": {"submitted": 4},
        "applications": [
            {
                "id": "mindbox-senior-backend-java",
                "source": "nofluffjobs",
                "company": "Mindbox Sp. z o.o.",
                "companyKey": "mindbox",
                "roleTitle": "Senior Backend Engineer (Java)",
                "roleKey": "senior-backend-engineer-java",
                "salarySeen": {"min": 29400, "max": 33600, "currency": "PLN", "basis": "net_b2b"},
                "appliedAt": "2026-06-17T10:10:42+00:00",
                "status": "submitted",
            },
            {
                "id": "crossriver-senior-java",
                "source": "linkedin",
                "company": "Cross River",
                "companyKey": "cross-river",
                "roleTitle": "Senior Java Engineer",
                "roleKey": "senior-java-engineer",
                "appliedAt": "2026-07-15T08:00:00+00:00",
                "status": "submitted",
            },
            {
                "id": "gness-fullstack-node",
                "source": "jobmaster",
                "company": "G-Ness",
                "companyKey": "g-ness",
                "roleTitle": "Full-stack Developer",
                "roleKey": "full-stack-developer",
                "stack": ["node", "react", "typescript"],
                "appliedAt": "2026-06-21T12:00:00+00:00",
                "status": "submitted",
            },
            {
                "id": "workana-fullstack-js",
                "source": "workable",
                "company": "Workana",
                "companyKey": "workana",
                "roleTitle": "Senior Full-Stack Software Engineer (JavaScript/TypeScript)",
                "roleKey": "senior-full-stack-software-engineer",
                "stack": ["javascript", "typescript"],
                "appliedAt": "2026-06-26T09:00:00+00:00",
                "status": "submitted",
            },
        ],
    }


@pytest.fixture
def tmp_campaign(tmp_path, sample_tracker) -> Path:
    """A temp campaign dir with tracker.json + one doc, for build() tests."""
    (tmp_path / "tracker.json").write_text(json.dumps(sample_tracker))
    # Minimal PORTALS.md with two ## sections to exercise chunk_doc.
    (tmp_path / "PORTALS.md").write_text(
        "# Portals\n\nIntro line.\n\n"
        "## IL portals\nAllJobs, Drushim, JobMaster (Israel, remote/hybrid).\n\n"
        "## EU portals\nNo Fluff Jobs, Just Join IT (Poland, full remote).\n"
    )
    # A doc we do NOT expect to find (to test missing-doc skip).
    # (other DOCS entries are absent; collect_corpus should skip them gracefully)
    return tmp_path
