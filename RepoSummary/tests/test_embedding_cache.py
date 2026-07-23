from __future__ import annotations

import sqlite3
from pathlib import Path

import numpy as np

from src import embedding_cache


def _encoder(calls: list[list[str]]):
    def encode(texts: list[str]) -> np.ndarray:
        calls.append(list(texts))
        return np.asarray(
            [[float(len(text)), float(sum(map(ord, text)) % 997)] for text in texts],
            dtype=np.float32,
        )

    return encode


def test_load_or_encode_reuses_unchanged_text_and_recomputes_only_changed_text(tmp_path: Path):
    calls: list[list[str]] = []
    arguments = {
        "repo_id": 7,
        "model_cache_key": "test-model",
        "entity_kind": "graph-node",
        "entity_ids": ["a", "b"],
        "source_paths": ["src/a.py", "src/b.py"],
        "encode": _encoder(calls),
        "output_root": tmp_path,
    }

    first, first_stats = embedding_cache.load_or_encode(texts=["alpha", "beta"], **arguments)
    second, second_stats = embedding_cache.load_or_encode(texts=["alpha", "beta"], **arguments)
    changed, changed_stats = embedding_cache.load_or_encode(texts=["alpha", "beta-v2"], **arguments)

    assert first_stats == {"hits": 0, "misses": 2}
    assert second_stats == {"hits": 2, "misses": 0}
    assert changed_stats == {"hits": 1, "misses": 1}
    assert calls == [["alpha", "beta"], ["beta-v2"]]
    np.testing.assert_array_equal(first, second)
    np.testing.assert_array_equal(first[0], changed[0])
    assert not np.array_equal(first[1], changed[1])


def test_prune_changed_paths_removes_deleted_nodes_only_in_confirmed_paths(tmp_path: Path):
    calls: list[list[str]] = []
    embedding_cache.load_or_encode(
        repo_id="repo",
        model_cache_key="test-model",
        entity_kind="graph-node",
        entity_ids=["a", "b", "c"],
        texts=["alpha", "beta", "gamma"],
        source_paths=["src/changed.py", "src/changed.py", "src/untouched.py"],
        encode=_encoder(calls),
        output_root=tmp_path,
    )

    deleted = embedding_cache.prune_changed_paths(
        repo_id="repo",
        model_cache_key="test-model",
        entity_kind="graph-node",
        changed_paths=["src/changed.py"],
        active_entity_ids=["a"],
        output_root=tmp_path,
    )

    assert deleted == 1
    path = embedding_cache.cache_path("repo", tmp_path)
    with sqlite3.connect(path) as connection:
        ids = [row[0] for row in connection.execute("SELECT entity_id FROM embedding_entry ORDER BY entity_id")]
    assert ids == ["a", "c"]


def test_cache_path_accepts_output_root_or_repository_output_directory(tmp_path: Path):
    output_root = tmp_path / "output"
    expected = output_root / "12" / "embedding-cache.sqlite"

    assert embedding_cache.cache_path(12, output_root) == expected
    assert embedding_cache.cache_path(12, output_root / "12") == expected


def test_prune_missing_entities_cleans_removed_features(tmp_path: Path):
    embedding_cache.load_or_encode(
        repo_id=3,
        model_cache_key="feature-model",
        entity_kind="feature",
        entity_ids=["1", "2"],
        texts=["one", "two"],
        source_paths=None,
        encode=_encoder([]),
        output_root=tmp_path,
    )

    deleted = embedding_cache.prune_missing_entities(
        repo_id=3,
        model_cache_key="feature-model",
        entity_kind="feature",
        active_entity_ids=["2"],
        output_root=tmp_path,
    )

    assert deleted == 1
    assert embedding_cache.cache_stats(3, tmp_path)["entries"][0]["count"] == 1
