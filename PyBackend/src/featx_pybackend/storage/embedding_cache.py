from __future__ import annotations

import hashlib
import json
import sqlite3
import threading
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Iterable, Sequence

import numpy as np

from ..paths import OUTPUT_ROOT

_LOCKS_GUARD = threading.Lock()
_LOCKS: dict[Path, threading.RLock] = {}


def cache_path(repo_id: str | int, output_root: Path | str | None = None) -> Path:
    root = Path(output_root).resolve() if output_root is not None else OUTPUT_ROOT
    output_dir = root if root.name == str(repo_id) else root / str(repo_id)
    output_dir.mkdir(parents=True, exist_ok=True)
    return output_dir / "embedding-cache.sqlite"


def model_key(
    role: str,
    model_name_or_path: str,
    *,
    max_seq_length: int | None = None,
    instruction: str = "",
) -> str:
    payload = {
        "schema": 1,
        "role": role,
        "model": str(model_name_or_path),
        "maxSeqLength": max_seq_length,
        "instructionSha256": _sha256_text(instruction) if instruction else "",
        "normalized": True,
        "dtype": "float32",
    }
    return json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def load_or_encode(
    *,
    repo_id: str | int | None,
    model_cache_key: str,
    entity_kind: str,
    entity_ids: Sequence[str],
    texts: Sequence[str],
    source_paths: Sequence[str] | None,
    encode: Callable[[list[str]], Any],
    output_root: Path | str | None = None,
) -> tuple[np.ndarray, dict[str, int]]:
    if len(entity_ids) != len(texts):
        raise ValueError("entity_ids and texts must have the same length")
    if source_paths is not None and len(source_paths) != len(texts):
        raise ValueError("source_paths and texts must have the same length")
    if not texts:
        return np.zeros((0, 0), dtype=np.float32), {"hits": 0, "misses": 0}

    normalized_ids = [str(value) for value in entity_ids]
    normalized_texts = [str(value or "") for value in texts]
    normalized_paths = (
        [str(value or "").replace("\\", "/") for value in source_paths]
        if source_paths is not None
        else [""] * len(normalized_texts)
    )
    hashes = [_sha256_text(text) for text in normalized_texts]

    if repo_id is None:
        vectors = _as_matrix(encode(normalized_texts))
        return vectors, {"hits": 0, "misses": len(normalized_texts)}

    db_path = cache_path(repo_id, output_root)
    lock = _lock_for(db_path)
    with lock:
        with _connect(db_path) as connection:
            cached = _read_cached(
                connection,
                model_cache_key=model_cache_key,
                entity_kind=entity_kind,
                entity_ids=normalized_ids,
            )

        vectors_by_index: dict[int, np.ndarray] = {}
        missing_indices: list[int] = []
        for index, (entity_id, content_hash) in enumerate(zip(normalized_ids, hashes)):
            entry = cached.get(entity_id)
            if entry is None or entry[0] != content_hash:
                missing_indices.append(index)
                continue
            vector = entry[1]
            if vector.size == 0 or not np.all(np.isfinite(vector)):
                missing_indices.append(index)
                continue
            vectors_by_index[index] = vector

        if missing_indices:
            encoded = _as_matrix(encode([normalized_texts[index] for index in missing_indices]))
            if encoded.shape[0] != len(missing_indices):
                raise RuntimeError("Embedding model returned an unexpected row count")
            now = datetime.now(timezone.utc).isoformat()
            rows = []
            for offset, index in enumerate(missing_indices):
                vector = np.ascontiguousarray(encoded[offset], dtype=np.float32)
                vectors_by_index[index] = vector
                rows.append(
                    (
                        model_cache_key,
                        entity_kind,
                        normalized_ids[index],
                        normalized_paths[index],
                        hashes[index],
                        int(vector.size),
                        vector.tobytes(order="C"),
                        now,
                    )
                )
            with _connect(db_path) as connection:
                connection.executemany(
                    """
                    INSERT INTO embedding_entry (
                        model_key, entity_kind, entity_id, source_path,
                        content_hash, dimension, vector, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(model_key, entity_kind, entity_id) DO UPDATE SET
                        source_path = excluded.source_path,
                        content_hash = excluded.content_hash,
                        dimension = excluded.dimension,
                        vector = excluded.vector,
                        updated_at = excluded.updated_at
                    """,
                    rows,
                )
                connection.commit()

        matrix = np.vstack([vectors_by_index[index] for index in range(len(normalized_texts))])
        return matrix, {
            "hits": len(normalized_texts) - len(missing_indices),
            "misses": len(missing_indices),
        }


def prune_changed_paths(
    *,
    repo_id: str | int,
    model_cache_key: str,
    entity_kind: str,
    changed_paths: Iterable[str],
    active_entity_ids: Iterable[str],
    output_root: Path | str | None = None,
) -> int:
    paths = sorted({str(path or "").replace("\\", "/") for path in changed_paths if str(path or "")})
    if not paths:
        return 0
    active_ids = {str(value) for value in active_entity_ids}
    db_path = cache_path(repo_id, output_root)
    lock = _lock_for(db_path)
    deleted = 0
    with lock, _connect(db_path) as connection:
        placeholders = ",".join("?" for _ in paths)
        rows = connection.execute(
            f"""
            SELECT entity_id
            FROM embedding_entry
            WHERE model_key = ? AND entity_kind = ? AND source_path IN ({placeholders})
            """,
            [model_cache_key, entity_kind, *paths],
        ).fetchall()
        stale_ids = [str(row[0]) for row in rows if str(row[0]) not in active_ids]
        if stale_ids:
            for chunk in _chunks(stale_ids, 500):
                id_placeholders = ",".join("?" for _ in chunk)
                cursor = connection.execute(
                    f"""
                    DELETE FROM embedding_entry
                    WHERE model_key = ? AND entity_kind = ? AND entity_id IN ({id_placeholders})
                    """,
                    [model_cache_key, entity_kind, *chunk],
                )
                deleted += int(cursor.rowcount or 0)
        connection.commit()
    return deleted


def prune_missing_entities(
    *,
    repo_id: str | int,
    model_cache_key: str,
    entity_kind: str,
    active_entity_ids: Iterable[str],
    output_root: Path | str | None = None,
) -> int:
    active_ids = {str(value) for value in active_entity_ids}
    db_path = cache_path(repo_id, output_root)
    lock = _lock_for(db_path)
    deleted = 0
    with lock, _connect(db_path) as connection:
        rows = connection.execute(
            """
            SELECT entity_id
            FROM embedding_entry
            WHERE model_key = ? AND entity_kind = ?
            """,
            [model_cache_key, entity_kind],
        ).fetchall()
        stale_ids = [str(row[0]) for row in rows if str(row[0]) not in active_ids]
        for chunk in _chunks(stale_ids, 500):
            placeholders = ",".join("?" for _ in chunk)
            cursor = connection.execute(
                f"""
                DELETE FROM embedding_entry
                WHERE model_key = ? AND entity_kind = ? AND entity_id IN ({placeholders})
                """,
                [model_cache_key, entity_kind, *chunk],
            )
            deleted += int(cursor.rowcount or 0)
        connection.commit()
    return deleted


def cache_stats(repo_id: str | int, output_root: Path | str | None = None) -> dict[str, Any]:
    db_path = cache_path(repo_id, output_root)
    with _lock_for(db_path), _connect(db_path) as connection:
        rows = connection.execute(
            """
            SELECT entity_kind, COUNT(*), COALESCE(SUM(LENGTH(vector)), 0)
            FROM embedding_entry
            GROUP BY entity_kind
            ORDER BY entity_kind
            """
        ).fetchall()
    return {
        "path": str(db_path),
        "entries": [
            {"entityKind": str(kind), "count": int(count), "bytes": int(size)}
            for kind, count, size in rows
        ],
    }


def _read_cached(
    connection: sqlite3.Connection,
    *,
    model_cache_key: str,
    entity_kind: str,
    entity_ids: Sequence[str],
) -> dict[str, tuple[str, np.ndarray]]:
    result: dict[str, tuple[str, np.ndarray]] = {}
    for chunk in _chunks(entity_ids, 500):
        placeholders = ",".join("?" for _ in chunk)
        rows = connection.execute(
            f"""
            SELECT entity_id, content_hash, dimension, vector
            FROM embedding_entry
            WHERE model_key = ? AND entity_kind = ? AND entity_id IN ({placeholders})
            """,
            [model_cache_key, entity_kind, *chunk],
        ).fetchall()
        for entity_id, content_hash, dimension, blob in rows:
            vector = np.frombuffer(blob, dtype=np.float32, count=int(dimension)).copy()
            result[str(entity_id)] = (str(content_hash), vector)
    return result


def _connect(path: Path) -> sqlite3.Connection:
    connection = sqlite3.connect(path, timeout=30.0)
    connection.execute("PRAGMA journal_mode=WAL")
    connection.execute("PRAGMA synchronous=NORMAL")
    connection.execute("PRAGMA busy_timeout=30000")
    connection.execute(
        """
        CREATE TABLE IF NOT EXISTS embedding_entry (
            model_key TEXT NOT NULL,
            entity_kind TEXT NOT NULL,
            entity_id TEXT NOT NULL,
            source_path TEXT NOT NULL DEFAULT '',
            content_hash TEXT NOT NULL,
            dimension INTEGER NOT NULL,
            vector BLOB NOT NULL,
            updated_at TEXT NOT NULL,
            PRIMARY KEY (model_key, entity_kind, entity_id)
        )
        """
    )
    connection.execute(
        """
        CREATE INDEX IF NOT EXISTS idx_embedding_source
        ON embedding_entry(model_key, entity_kind, source_path)
        """
    )
    connection.execute(
        """
        CREATE TABLE IF NOT EXISTS cache_manifest (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL,
            updated_at TEXT NOT NULL
        )
        """
    )
    return connection


def _as_matrix(value: Any) -> np.ndarray:
    matrix = np.asarray(value, dtype=np.float32)
    if matrix.ndim == 1:
        matrix = matrix.reshape(1, -1)
    if matrix.ndim != 2:
        raise RuntimeError(f"Embedding model returned an invalid shape: {matrix.shape}")
    return np.ascontiguousarray(matrix, dtype=np.float32)


def _lock_for(path: Path) -> threading.RLock:
    normalized = path.resolve()
    with _LOCKS_GUARD:
        return _LOCKS.setdefault(normalized, threading.RLock())


def _sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8", errors="replace")).hexdigest()


def _chunks(values: Sequence[str], size: int) -> Iterable[list[str]]:
    for start in range(0, len(values), size):
        yield list(values[start:start + size])
