from __future__ import annotations

import numpy as np

from featx_pybackend.graph import focus
from featx_pybackend.storage import embedding_cache


class _FakeModel:
    max_seq_length = 32

    def encode(self, texts, **_kwargs):
        return np.asarray(
            [[float(len(text)), float(sum(map(ord, text)) % 997)] for text in texts],
            dtype=np.float32,
        )


def test_full_bge_sync_prunes_nodes_missing_from_complete_graph(tmp_path, monkeypatch):
    monkeypatch.setattr(focus, "_load_bge_code_model", lambda: _FakeModel())
    monkeypatch.setattr(embedding_cache, "OUTPUT_ROOT", tmp_path)
    monkeypatch.setenv("FOCUSGRAPH_GRAPH_EMBEDDING_MODEL", "fake-bge-code")

    first = focus.sync_bge_code_cache(
        {
            "repoId": 12,
            "fullSync": True,
            "nodes": [
                {"id": "a", "sourcePath": "src/A.java", "text": "class A {}"},
                {"id": "b", "sourcePath": "src/B.java", "text": "class B {}"},
            ],
        }
    )
    second = focus.sync_bge_code_cache(
        {
            "repoId": 12,
            "fullSync": True,
            "nodes": [
                {"id": "b", "sourcePath": "src/B.java", "text": "class B {}"},
            ],
        }
    )

    assert first["cacheMisses"] == 2
    assert second["cacheHits"] == 1
    assert second["deletedEntries"] == 1
    assert second["fullSync"] is True
    assert embedding_cache.cache_stats(12, tmp_path)["entries"][0]["count"] == 1
