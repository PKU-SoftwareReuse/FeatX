"""Shared RepoSummary text-embedding model loader."""

import os
from functools import lru_cache

from sentence_transformers import SentenceTransformer

from ..paths import MODELS_ROOT


@lru_cache(maxsize=1)
def _load_summary_embedding_model(model_path: str) -> SentenceTransformer:
    return SentenceTransformer(model_path)


def load_summary_embedding_model() -> SentenceTransformer:
    default_model_path = MODELS_ROOT / "sentence-transformers" / "all-mpnet-base-v2"
    model_path = os.getenv("SENTENCE_TRANSFORMER_MODEL")
    if not model_path:
        model_path = (
            str(default_model_path)
            if default_model_path.exists()
            else "sentence-transformers/all-mpnet-base-v2"
        )
    return _load_summary_embedding_model(model_path)
