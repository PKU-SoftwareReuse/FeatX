import argparse
import ast
import json
import math
import os
import re
import sys
import threading
from contextlib import contextmanager
from collections import defaultdict
from pathlib import Path
from typing import Any, Callable, Iterator

import numpy as np
import pandas as pd
from dotenv import load_dotenv

from ..paths import OUTPUT_ROOT, WORKSPACE_ENV_FILE
from ..storage import embedding_cache


load_dotenv(WORKSPACE_ENV_FILE)
load_dotenv()

_PROGRESS_CONTEXT = threading.local()


def _log(message: str) -> None:
    print(f"[focusgraph] {message}", file=sys.stderr)


def _progress(stage: str, message: str, step: int, total: int = 8, **details: Any) -> None:
    payload = {
        "stage": stage,
        "message": message,
        "step": step,
        "total": total,
        **details,
    }
    callback = getattr(_PROGRESS_CONTEXT, "callback", None)
    if callback is not None:
        callback(payload)
    print(f"__FOCUSGRAPH_PROGRESS__{json.dumps(payload, ensure_ascii=False)}", file=sys.stderr, flush=True)


@contextmanager
def progress_callback(callback: Callable[[dict[str, Any]], None] | None) -> Iterator[None]:
    previous = getattr(_PROGRESS_CONTEXT, "callback", None)
    _PROGRESS_CONTEXT.callback = callback
    try:
        yield
    finally:
        _PROGRESS_CONTEXT.callback = previous


def _normalize_symbol(value: Any) -> str:
    text = "" if value is None else str(value).strip()
    text = text.split("(", 1)[0] if "(" in text else text
    return text.strip().lstrip(".")


def _without_first_segment(symbol: str) -> str:
    parts = symbol.split(".")
    if len(parts) <= 1:
        return symbol
    return ".".join(parts[1:])


def _tokens(text: Any) -> set[str]:
    return set(re.findall(r"[A-Za-z_][A-Za-z0-9_]*", "" if text is None else str(text).lower()))


def _score_texts_lexical(query: str, documents: list[str]) -> list[float]:
    if not documents:
        return []
    try:
        from sklearn.feature_extraction.text import TfidfVectorizer
        from sklearn.metrics.pairwise import cosine_similarity

        vectorizer = TfidfVectorizer(stop_words="english")
        matrix = vectorizer.fit_transform([query] + documents)
        sims = cosine_similarity(matrix[0:1], matrix[1:]).flatten()
        return [float(x) for x in sims]
    except Exception as exc:
        _log(f"TF-IDF similarity fallback: {exc}")
        query_tokens = _tokens(query)
        scores = []
        for document in documents:
            doc_tokens = _tokens(document)
            if not query_tokens or not doc_tokens:
                scores.append(0.0)
                continue
            scores.append(len(query_tokens & doc_tokens) / math.sqrt(len(query_tokens) * len(doc_tokens)))
        return scores


_SENTENCE_MODEL_CACHE: dict[str, Any] = {}
_MODEL_LOAD_LOCK = threading.RLock()
_SENTENCE_INFERENCE_LOCK = threading.RLock()
_BGE_INFERENCE_LOCK = threading.RLock()
BGE_CODE_INSTRUCTION = "Given a requirement description in text, retrieve code snippets that are relevant to the requirement."


def _default_sentence_model_path(model_name: str) -> str:
    for root in (Path("/app/models/sentence-transformers"), Path("/home/riverbag/phd0/featx_focusgraph/models/sentence-transformers")):
        candidate = root / model_name
        if candidate.exists():
            return str(candidate)
    return f"sentence-transformers/{model_name}"


def _load_sentence_model(env_name: str, default_model_name: str) -> Any | None:
    model_name_or_path = os.getenv(env_name) or _default_sentence_model_path(default_model_name)
    with _MODEL_LOAD_LOCK:
        if model_name_or_path in _SENTENCE_MODEL_CACHE:
            return _SENTENCE_MODEL_CACHE[model_name_or_path]
        try:
            from sentence_transformers import SentenceTransformer

            model = SentenceTransformer(model_name_or_path)
            model.eval()
            _SENTENCE_MODEL_CACHE[model_name_or_path] = model
            _log(f"{env_name} loaded: {model_name_or_path}")
            return model
        except Exception as exc:
            _log(f"{env_name} embedding unavailable ({model_name_or_path}): {exc}")
            return None


def _load_bge_code_model() -> Any | None:
    model_name_or_path = os.getenv("FOCUSGRAPH_GRAPH_EMBEDDING_MODEL") or _default_sentence_model_path("BAAI/bge-code-v1")
    cache_key = f"bge-code::{model_name_or_path}"
    with _MODEL_LOAD_LOCK:
        if cache_key in _SENTENCE_MODEL_CACHE:
            return _SENTENCE_MODEL_CACHE[cache_key]
        try:
            from sentence_transformers import SentenceTransformer

            try:
                model = SentenceTransformer(model_name_or_path, trust_remote_code=True)
            except TypeError:
                model = SentenceTransformer(model_name_or_path)
            max_seq_length = int(os.getenv("FOCUSGRAPH_BGE_MAX_SEQ_LENGTH", "4096"))
            model.max_seq_length = max_seq_length
            model.eval()
            _SENTENCE_MODEL_CACHE[cache_key] = model
            _log(f"FOCUSGRAPH_GRAPH_EMBEDDING_MODEL loaded as bge-code: {model_name_or_path}")
            return model
        except Exception as exc:
            _log(f"bge-code graph embedding unavailable ({model_name_or_path}): {exc}")
            return None


def _truncate_for_bge_code(text: str, max_seq_length: int) -> str:
    if not text:
        return ""
    max_chars = max(1, max_seq_length - 2) * 3
    return text[:max_chars] if len(text) > max_chars else text


def _score_texts_with_bge_code(
    query: str,
    documents: list[str],
    *,
    repo_id: str | int | None = None,
    entity_ids: list[str] | None = None,
    source_paths: list[str] | None = None,
    entity_kind: str = "graph-node",
) -> list[float]:
    if not documents:
        return []
    model = _load_bge_code_model()
    if model is None:
        return _score_texts_with_embedding(
            query,
            documents,
            env_name="FOCUSGRAPH_GRAPH_SENTENCE_FALLBACK_MODEL",
            default_model_name="all-MiniLM-L6-v2",
        )
    try:
        batch_size = int(os.getenv("FOCUSGRAPH_GRAPH_EMBEDDING_BATCH_SIZE", "16"))
        max_seq_length = int(getattr(model, "max_seq_length", 4096) or 4096)
        query_text = f"<instruct>{BGE_CODE_INSTRUCTION}\n<query>{query}"
        code_texts = [_truncate_for_bge_code(str(document or ""), max_seq_length) for document in documents]
        model_name_or_path = os.getenv("FOCUSGRAPH_GRAPH_EMBEDDING_MODEL") or _default_sentence_model_path("BAAI/bge-code-v1")
        cache_key = embedding_cache.model_key(
            "bge-code",
            model_name_or_path,
            max_seq_length=max_seq_length,
            instruction=BGE_CODE_INSTRUCTION,
        )
        ids = entity_ids if entity_ids is not None and len(entity_ids) == len(code_texts) else [str(index) for index in range(len(code_texts))]
        with _BGE_INFERENCE_LOCK:
            query_embedding = model.encode(
                [query_text],
                batch_size=1,
                convert_to_numpy=True,
                normalize_embeddings=True,
            )
            code_embeddings, stats = embedding_cache.load_or_encode(
                repo_id=repo_id,
                model_cache_key=cache_key,
                entity_kind=entity_kind,
                entity_ids=ids,
                texts=code_texts,
                source_paths=source_paths,
                encode=lambda missing: model.encode(
                    missing,
                    batch_size=batch_size,
                    convert_to_numpy=True,
                    normalize_embeddings=True,
                ),
            )
        _log(f"bge-code cache hits={stats['hits']} misses={stats['misses']} repo={repo_id}")
        similarities = np.matmul(code_embeddings, query_embedding[0])
        return [float(x) for x in similarities]
    except Exception as exc:
        _log(f"bge-code graph embedding scoring fallback: {exc}")
        return _score_texts_with_embedding(
            query,
            documents,
            env_name="FOCUSGRAPH_GRAPH_SENTENCE_FALLBACK_MODEL",
            default_model_name="all-MiniLM-L6-v2",
        )


def sync_bge_code_cache(request: dict[str, Any]) -> dict[str, Any]:
    repo_id = request.get("repoId")
    if repo_id is None:
        raise RuntimeError("repoId is required for embedding cache synchronization")
    nodes = list(request.get("nodes") or [])
    full_sync = bool(request.get("fullSync"))
    changed_paths = [str(path or "").replace("\\", "/") for path in request.get("changedPaths") or []]
    entity_kind = str(request.get("entityKind") or "graph-node")
    model = _load_bge_code_model()
    if model is None:
        raise RuntimeError("BGE-code model is unavailable")

    batch_size = int(os.getenv("FOCUSGRAPH_GRAPH_EMBEDDING_BATCH_SIZE", "16"))
    max_seq_length = int(getattr(model, "max_seq_length", 4096) or 4096)
    model_name_or_path = os.getenv("FOCUSGRAPH_GRAPH_EMBEDDING_MODEL") or _default_sentence_model_path("BAAI/bge-code-v1")
    cache_key = embedding_cache.model_key(
        "bge-code",
        model_name_or_path,
        max_seq_length=max_seq_length,
        instruction=BGE_CODE_INSTRUCTION,
    )
    entity_ids = [str(node.get("id") or "") for node in nodes]
    source_paths = [str(node.get("sourcePath") or "").replace("\\", "/") for node in nodes]
    documents = [_truncate_for_bge_code(str(node.get("text") or ""), max_seq_length) for node in nodes]

    if documents:
        with _BGE_INFERENCE_LOCK:
            _, stats = embedding_cache.load_or_encode(
                repo_id=repo_id,
                model_cache_key=cache_key,
                entity_kind=entity_kind,
                entity_ids=entity_ids,
                texts=documents,
                source_paths=source_paths,
                encode=lambda missing: model.encode(
                    missing,
                    batch_size=batch_size,
                    convert_to_numpy=True,
                    normalize_embeddings=True,
                ),
            )
    else:
        stats = {"hits": 0, "misses": 0}

    if full_sync:
        deleted = embedding_cache.prune_missing_entities(
            repo_id=repo_id,
            model_cache_key=cache_key,
            entity_kind=entity_kind,
            active_entity_ids=entity_ids,
        )
    else:
        deleted = embedding_cache.prune_changed_paths(
            repo_id=repo_id,
            model_cache_key=cache_key,
            entity_kind=entity_kind,
            changed_paths=changed_paths,
            active_entity_ids=entity_ids,
        )
    return {
        "repoId": str(repo_id),
        "entityKind": entity_kind,
        "fullSync": full_sync,
        "nodeCount": len(nodes),
        "cacheHits": stats["hits"],
        "cacheMisses": stats["misses"],
        "deletedEntries": deleted,
        "cache": embedding_cache.cache_stats(repo_id),
    }


def sync_feature_embedding_cache(request: dict[str, Any]) -> dict[str, Any]:
    repo_id = request.get("repoId")
    if repo_id is None:
        raise RuntimeError("repoId is required for feature embedding cache synchronization")
    features = list(request.get("features") or [])
    model = _load_sentence_model("FOCUSGRAPH_FEATURE_EMBEDDING_MODEL", "all-MiniLM-L6-v2")
    if model is None:
        raise RuntimeError("Feature embedding model is unavailable")

    model_name_or_path = os.getenv("FOCUSGRAPH_FEATURE_EMBEDDING_MODEL") or _default_sentence_model_path("all-MiniLM-L6-v2")
    cache_key = embedding_cache.model_key("sentence-transformer", model_name_or_path)
    entity_ids = [str(feature.get("featureId") or "") for feature in features]
    documents = [
        f"{feature.get('moduleDesc') or ''}\n{feature.get('description') or ''}"
        for feature in features
    ]
    if documents:
        with _SENTENCE_INFERENCE_LOCK:
            _, stats = embedding_cache.load_or_encode(
                repo_id=repo_id,
                model_cache_key=cache_key,
                entity_kind="feature",
                entity_ids=entity_ids,
                texts=documents,
                source_paths=None,
                encode=lambda missing: model.encode(
                    missing,
                    convert_to_numpy=True,
                    normalize_embeddings=True,
                ),
            )
    else:
        stats = {"hits": 0, "misses": 0}
    deleted = embedding_cache.prune_missing_entities(
        repo_id=repo_id,
        model_cache_key=cache_key,
        entity_kind="feature",
        active_entity_ids=entity_ids,
    )
    return {
        "repoId": str(repo_id),
        "featureCount": len(features),
        "cacheHits": stats["hits"],
        "cacheMisses": stats["misses"],
        "deletedEntries": deleted,
    }


def _score_graph_code_contexts(
    query: str,
    documents: list[str],
    *,
    repo_id: str | int | None = None,
    entity_ids: list[str] | None = None,
    source_paths: list[str] | None = None,
) -> list[float]:
    backend = os.getenv("FOCUSGRAPH_GRAPH_EMBEDDING_BACKEND", "bge-code").strip().lower()
    if backend in {"bge", "bge-code", "bge_code"}:
        return _score_texts_with_bge_code(
            query,
            documents,
            repo_id=repo_id,
            entity_ids=entity_ids,
            source_paths=source_paths,
        )
    if backend in {"sentence-transformer", "sentence_transformer", "sentence", "st"}:
        return _score_texts_with_embedding(
            query,
            documents,
            env_name="FOCUSGRAPH_GRAPH_EMBEDDING_MODEL",
            default_model_name="all-MiniLM-L6-v2",
            repo_id=repo_id,
            entity_ids=entity_ids,
            source_paths=source_paths,
            entity_kind="graph-node",
        )
    _log(f"Unknown FOCUSGRAPH_GRAPH_EMBEDDING_BACKEND={backend}; falling back to bge-code.")
    return _score_texts_with_bge_code(
        query,
        documents,
        repo_id=repo_id,
        entity_ids=entity_ids,
        source_paths=source_paths,
    )


def _score_texts_with_embedding(
    query: str,
    documents: list[str],
    *,
    env_name: str,
    default_model_name: str = "all-MiniLM-L6-v2",
    repo_id: str | int | None = None,
    entity_ids: list[str] | None = None,
    source_paths: list[str] | None = None,
    entity_kind: str = "text",
) -> list[float]:
    if not documents:
        return []
    model = _load_sentence_model(env_name, default_model_name)
    if model is None:
        return _score_texts_lexical(query, documents)
    try:
        model_name_or_path = os.getenv(env_name) or _default_sentence_model_path(default_model_name)
        cache_key = embedding_cache.model_key("sentence-transformer", model_name_or_path)
        ids = entity_ids if entity_ids is not None and len(entity_ids) == len(documents) else [str(index) for index in range(len(documents))]
        with _SENTENCE_INFERENCE_LOCK:
            query_embedding = model.encode([query], convert_to_numpy=True, normalize_embeddings=True)
            doc_embeddings, stats = embedding_cache.load_or_encode(
                repo_id=repo_id,
                model_cache_key=cache_key,
                entity_kind=entity_kind,
                entity_ids=ids,
                texts=documents,
                source_paths=source_paths,
                encode=lambda missing: model.encode(
                    missing,
                    convert_to_numpy=True,
                    normalize_embeddings=True,
                ),
            )
        _log(f"{env_name} cache hits={stats['hits']} misses={stats['misses']} repo={repo_id}")
        similarities = np.matmul(doc_embeddings, query_embedding[0])
        return [float(x) for x in similarities]
    except Exception as exc:
        _log(f"{env_name} embedding scoring fallback: {exc}")
        return _score_texts_lexical(query, documents)


def _read_request() -> dict[str, Any]:
    raw = sys.stdin.read()
    if not raw.strip():
        raise RuntimeError("Missing JSON request on stdin")
    return json.loads(raw)


def _repo_paths(request: dict[str, Any]) -> tuple[Path, Path]:
    repo_id = str(request["repoId"])
    output_dir = Path(request.get("repoSummaryOutputDir") or OUTPUT_ROOT / repo_id).resolve()
    repo_path = request.get("repoPath")
    if repo_path:
        project_root = Path(repo_path).resolve()
    else:
        lotm_repo_path = Path(os.getenv("LOTM_REPO_PATH", "/workspace/repos"))
        project_root = (lotm_repo_path / repo_id / "src" / "main" / "python").resolve()
    return project_root, output_dir


def _load_features(output_dir: Path) -> pd.DataFrame:
    path = output_dir / "features.csv"
    if not path.exists():
        raise RuntimeError(f"features.csv not found: {path}")
    df = pd.read_csv(path, dtype=str, keep_default_na=False)
    required = {"id", "cluster_id", "module_desc", "desc", "method_name"}
    missing = required.difference(df.columns)
    if missing:
        raise RuntimeError(f"features.csv missing columns: {sorted(missing)}")
    return df


def _load_methods(output_dir: Path) -> pd.DataFrame:
    path = output_dir / "methods.csv"
    if not path.exists():
        raise RuntimeError(f"methods.csv not found: {path}")
    df = pd.read_csv(path, dtype=str, keep_default_na=False).fillna("")
    required = {"method_signature", "func_file", "method_code"}
    missing = required.difference(df.columns)
    if missing:
        raise RuntimeError(f"methods.csv missing columns: {sorted(missing)}")
    return df


def _load_enre(output_dir: Path) -> tuple[dict[int, dict[str, Any]], dict[str, int], dict[int, str], dict[int, list[tuple[int, str]]], dict[int, list[tuple[int, str]]]]:
    path = output_dir / "report-enre.json"
    if not path.exists():
        _log(f"ENRE report not found: {path}")
        return {}, {}, {}, defaultdict(list), defaultdict(list)
    data = json.loads(path.read_text(encoding="utf-8"))
    valid_nodes: dict[int, dict[str, Any]] = {}
    qname_to_id: dict[str, int] = {}
    id_to_qname: dict[int, str] = {}
    for node in data.get("variables", []):
        category = str(node.get("category", ""))
        if category.startswith("Un"):
            continue
        try:
            node_id = int(node["id"])
        except Exception:
            continue
        qname = str(node.get("qualifiedName", ""))
        if not qname:
            continue
        valid_nodes[node_id] = node
        id_to_qname[node_id] = qname
        normalized_qname = _normalize_symbol(qname)
        qname_to_id[normalized_qname] = node_id
        qname_to_id.setdefault(_without_first_segment(normalized_qname), node_id)

    adj: dict[int, list[tuple[int, str]]] = defaultdict(list)
    reverse_adj: dict[int, list[tuple[int, str]]] = defaultdict(list)
    seen = set()
    for edge in data.get("cells", []):
        try:
            src = int(edge.get("src"))
            dest = int(edge.get("dest"))
        except Exception:
            continue
        kind = str(edge.get("values", {}).get("kind", ""))
        if src not in valid_nodes or dest not in valid_nodes:
            continue
        key = (src, dest, kind)
        if key in seen:
            continue
        seen.add(key)
        adj[src].append((dest, kind))
        reverse_adj[dest].append((src, kind))
    return valid_nodes, qname_to_id, id_to_qname, adj, reverse_adj


def _feature_index(features_df: pd.DataFrame) -> list[dict[str, Any]]:
    records = []
    for feature_id, group in features_df.groupby("id", sort=False):
        first = group.iloc[0]
        records.append(
            {
                "featureId": str(feature_id),
                "clusterId": str(first.get("cluster_id", "")),
                "moduleDesc": str(first.get("module_desc", "")),
                "description": str(first.get("desc", "")),
                "methods": [str(x) for x in group["method_name"].tolist() if str(x).strip()],
            }
        )
    return records


def _select_features(query: str, features: list[dict[str, Any]], request: dict[str, Any]) -> tuple[list[dict[str, Any]], list[str]]:
    operation = str(request.get("operation", "")).lower()
    current_methods = [str(x) for x in request.get("currentCodeMap", []) if str(x).strip()]
    current_feature_id = str(request.get("featureId") or "").strip()

    if operation == "delete":
        _progress(
            "delete-seeds",
            "Using the current feature CodeMap as Delete Feature seed methods.",
            3,
            featureId=current_feature_id,
            seedMethodCount=len(current_methods),
        )
        current_feature = next((item for item in features if item["featureId"] == current_feature_id), None)
        selected = [
            {
                "rank": 1,
                "featureId": current_feature_id,
                "clusterId": (current_feature or {}).get("clusterId", ""),
                "reason": "forced_current_feature_delete_seed",
                "score": 1.0,
                "retrievalRank": None,
                "description": (current_feature or {}).get(
                    "description",
                    str(request.get("oldFeatureDescription") or request.get("featureDescription") or ""),
                ),
                "moduleDesc": (current_feature or {}).get("moduleDesc", ""),
            }
        ]
        return selected, _dedupe_methods(current_methods)

    _progress(
        "feature-retrieval",
        "Retrieving top-k similar features with embedding search.",
        3,
        queryLength=len(query),
        featureCount=len(features),
    )
    top_k = int(request.get("topKFeatures") or os.getenv("FOCUSGRAPH_TOP_K_FEATURES", "3"))
    docs = [f"{item['moduleDesc']}\n{item['description']}" for item in features]
    scores = _score_texts_with_embedding(
        query,
        docs,
        env_name="FOCUSGRAPH_FEATURE_EMBEDDING_MODEL",
        default_model_name="all-MiniLM-L6-v2",
        repo_id=request.get("repoId"),
        entity_ids=[str(item["featureId"]) for item in features],
        entity_kind="feature",
    )
    ranked = sorted(
        [
            {
                "feature": feature,
                "score": float(score),
                "retrievalRank": index + 1,
            }
            for index, (feature, score) in enumerate(sorted(zip(features, scores), key=lambda item: item[1], reverse=True))
        ],
        key=lambda item: item["retrievalRank"],
    )

    selected_ranked: list[dict[str, Any]] = []
    if operation == "modify" and current_feature_id:
        current_entry = next((item for item in ranked if item["feature"]["featureId"] == current_feature_id), None)
        if current_entry is None:
            current_entry = {
                "feature": {
                    "featureId": current_feature_id,
                    "clusterId": "",
                    "moduleDesc": "",
                    "description": str(request.get("oldFeatureDescription") or request.get("featureDescription") or ""),
                    "methods": current_methods,
                },
                "score": 1.0,
                "retrievalRank": None,
            }
        selected_ranked.append({**current_entry, "reason": "forced_current_feature"})

    seen_feature_ids = {item["feature"]["featureId"] for item in selected_ranked}
    for entry in ranked:
        if len(selected_ranked) >= top_k:
            break
        feature = entry["feature"]
        if feature["featureId"] in seen_feature_ids:
            continue
        selected_ranked.append({**entry, "reason": "retrieved_by_query"})
        seen_feature_ids.add(feature["featureId"])

    selected: list[dict[str, Any]] = []
    seed_methods: list[str] = []
    for rank, entry in enumerate(selected_ranked[:top_k], start=1):
        feature = entry["feature"]
        selected.append(
            {
                "rank": rank,
                "featureId": feature["featureId"],
                "clusterId": feature.get("clusterId", ""),
                "reason": entry["reason"],
                "score": float(entry["score"]),
                "retrievalRank": entry.get("retrievalRank"),
                "description": feature.get("description", ""),
                "moduleDesc": feature.get("moduleDesc", ""),
            }
        )
        seed_methods.extend(feature.get("methods") or [])

    deduped_methods = _dedupe_methods(seed_methods)
    _progress(
        "seed-methods",
        "Collecting seed methods from selected features.",
        4,
        selectedFeatureCount=len(selected),
        seedMethodCount=len(deduped_methods),
    )
    return selected, deduped_methods


def _dedupe_methods(seed_methods: list[str]) -> list[str]:
    deduped_methods = []
    seen_methods = set()
    for method in seed_methods:
        norm = _normalize_symbol(method)
        if not norm or norm in seen_methods:
            continue
        deduped_methods.append(method)
        seen_methods.add(norm)
    return deduped_methods


def _method_maps(methods_df: pd.DataFrame) -> tuple[dict[str, dict[str, Any]], dict[str, str]]:
    info_by_norm: dict[str, dict[str, Any]] = {}
    sig_by_norm: dict[str, str] = {}
    for row in methods_df.to_dict("records"):
        signature = str(row.get("method_signature", ""))
        norm = _normalize_symbol(signature)
        if not norm:
            continue
        info_by_norm[norm] = row
        sig_by_norm[norm] = signature
    return info_by_norm, sig_by_norm


def _build_reasoning_graph(
    *,
    query: str,
    seed_methods: list[str],
    current_code_map: list[Any],
    methods_df: pd.DataFrame,
    enre_data: tuple[dict[int, dict[str, Any]], dict[str, int], dict[int, str], dict[int, list[tuple[int, str]]], dict[int, list[tuple[int, str]]]],
    top_k_nodes: int,
    project_root: Path,
    repo_id: str | int | None = None,
) -> tuple[dict[str, Any], list[str], list[str], list[dict[str, Any]]]:
    _progress(
        "graph-expansion",
        "Building the initial graph and running one-hop FocusGraph expansion.",
        5,
        seedMethodCount=len(seed_methods),
    )
    valid_nodes, qname_to_id, id_to_qname, adj, reverse_adj = enre_data
    info_by_norm, sig_by_norm = _method_maps(methods_df)
    current_norms = {_normalize_symbol(method) for method in current_code_map or [] if _normalize_symbol(method)}
    seed_norms = {_normalize_symbol(method) for method in seed_methods or [] if _normalize_symbol(method)}
    similar_norms = seed_norms - current_norms if current_norms else set(seed_norms)
    current_files = {
        str(info.get("func_file", "")).strip()
        for method in current_code_map or []
        for info in [_lookup_method_info(method, info_by_norm)]
        if info and str(info.get("func_file", "")).strip()
    }

    nodes: dict[str, dict[str, Any]] = {}
    edges: list[dict[str, str]] = []
    edge_keys: set[tuple[str, str, str]] = set()
    function_enre_ids: set[int] = set()

    def resolve_enre_qname(node_id: int) -> str:
        qname = _normalize_symbol(id_to_qname.get(node_id, ""))
        if qname not in info_by_norm:
            stripped_qname = _without_first_segment(qname)
            if stripped_qname in info_by_norm:
                return stripped_qname
        return qname

    def node_key_for_enre_id(node_id: int) -> str:
        category = str(valid_nodes.get(node_id, {}).get("category", ""))
        if category == "Function":
            return resolve_enre_qname(node_id)
        return _normalize_symbol(id_to_qname.get(node_id, ""))

    def add_function_node(key: str, enre_id: int | None, src_type: str) -> str | None:
        key = _normalize_symbol(key)
        if not key:
            return None
        info = info_by_norm.get(key, {})
        signature = sig_by_norm.get(key) or key
        if key not in nodes:
            func_file = str(info.get("func_file", ""))
            nodes[key] = {
                "id": key,
                "label": key,
                "category": "Function",
                "srcType": src_type,
                "methodSignature": signature,
                "funcFile": func_file,
                "methodCode": str(info.get("method_code", "")),
                "boostSameFeature": key in current_norms,
                "boostSameFile": bool(func_file and func_file in current_files),
                "boostSimilarMethod": key in similar_norms,
            }
        elif nodes[key].get("srcType") != "original" and src_type == "original":
            nodes[key]["srcType"] = "original"
        if enre_id is not None:
            function_enre_ids.add(enre_id)
        return key

    def add_function_from_method(method: str, src_type: str) -> tuple[str | None, int | None]:
        norm = _normalize_symbol(method)
        enre_id = qname_to_id.get(norm)
        if enre_id is None:
            stripped = _without_first_segment(norm)
            enre_id = qname_to_id.get(stripped)
        key = resolve_enre_qname(enre_id) if enre_id is not None else norm
        return add_function_node(key, enre_id, src_type), enre_id

    def add_function_from_enre(node_id: int, src_type: str) -> str | None:
        if node_id not in valid_nodes or valid_nodes[node_id].get("category") != "Function":
            return None
        return add_function_node(resolve_enre_qname(node_id), node_id, src_type)

    def add_class_from_enre(node_id: int) -> str | None:
        if node_id not in valid_nodes or valid_nodes[node_id].get("category") != "Class":
            return None
        key = node_key_for_enre_id(node_id)
        if not key:
            return None
        node = valid_nodes.get(node_id, {})
        if key not in nodes:
            nodes[key] = {
                "id": key,
                "label": key,
                "category": "Class",
                "srcType": "expand",
                "methodSignature": key,
                "funcFile": str(node.get("File", "")),
                "methodCode": "",
                "boostSameFeature": False,
                "boostSameFile": False,
                "boostSimilarMethod": False,
            }
        return key

    def add_edge_by_keys(src_key: str | None, dest_key: str | None, kind: str) -> None:
        if not src_key or not dest_key or src_key not in nodes or dest_key not in nodes:
            return
        edge_key = (src_key, dest_key, kind)
        if edge_key in edge_keys:
            return
        edge_keys.add(edge_key)
        edges.append({"from": src_key, "to": dest_key, "type": kind})

    def add_edge_by_ids(src_id: int, dest_id: int, kind: str) -> None:
        add_edge_by_keys(node_key_for_enre_id(src_id), node_key_for_enre_id(dest_id), kind)

    def public_graph(stage_id: str, label: str, description: str, node_subset: set[str] | None = None) -> dict[str, Any]:
        included_nodes = list(nodes.keys()) if node_subset is None else [node_id for node_id in nodes.keys() if node_id in node_subset]
        included_set = set(included_nodes)
        graph_nodes = []
        for node_id in included_nodes:
            node = dict(nodes[node_id])
            node.pop("methodCode", None)
            node.pop("boostSameFeature", None)
            node.pop("boostSameFile", None)
            node.pop("boostSimilarMethod", None)
            graph_nodes.append(node)

        seen_keys: set[tuple[str, str, str]] = set()
        graph_edges = []
        for edge in edges:
            if edge["from"] not in included_set or edge["to"] not in included_set:
                continue
            key = (edge["from"], edge["to"], edge.get("type", ""))
            if key in seen_keys:
                continue
            seen_keys.add(key)
            graph_edges.append(dict(edge))
        return {
            "id": stage_id,
            "label": label,
            "description": description,
            "nodes": graph_nodes,
            "edges": graph_edges,
        }

    original_function_enre_ids: list[int] = []
    for method in seed_methods:
        _, enre_id = add_function_from_method(method, "original")
        if enre_id is not None:
            original_function_enre_ids.append(enre_id)

    original_function_enre_ids = list(dict.fromkeys(original_function_enre_ids))
    original_id_set = set(original_function_enre_ids)

    # Initial graph: selected feature methods plus their existing direct ENRE relations.
    for node_id in original_function_enre_ids:
        for dest_id, kind in adj.get(node_id, []):
            if dest_id in original_id_set:
                add_edge_by_ids(node_id, dest_id, kind)

    initial_graph = public_graph(
        "initial",
        "Initial Graph",
        "Seed methods collected from retrieved features before one-hop expansion.",
    )

    # FocusGraph one-hop expansion: Function --(Call/Use/Contain)--> Function,
    # plus reverse Call callers into the original Function seeds.
    for node_id in original_function_enre_ids:
        for dest_id, kind in adj.get(node_id, []):
            if kind not in {"Call", "Use", "Contain"}:
                continue
            if dest_id not in valid_nodes or valid_nodes[dest_id].get("category") != "Function":
                continue
            add_function_from_enre(dest_id, "expand")
            add_edge_by_ids(node_id, dest_id, kind)
        for caller_id, kind in reverse_adj.get(node_id, []):
            if kind != "Call":
                continue
            if caller_id not in valid_nodes or valid_nodes[caller_id].get("category") != "Function":
                continue
            add_function_from_enre(caller_id, "expand")
            add_edge_by_ids(caller_id, node_id, kind)

    # FocusGraph second pass: add defining Class nodes for all graph Function nodes.
    for func_id in list(function_enre_ids):
        for class_id, kind in reverse_adj.get(func_id, []):
            if kind != "Define":
                continue
            if class_id not in valid_nodes or valid_nodes[class_id].get("category") != "Class":
                continue
            add_class_from_enre(class_id)
            add_edge_by_ids(class_id, func_id, kind)

    expanded_graph = public_graph(
        "expanded",
        "Expanded Graph",
        "Initial graph after one-hop FocusGraph expansion and class-definition nodes.",
    )

    node_ids = list(nodes.keys())
    documents = []
    for node_id in node_ids:
        node = nodes[node_id]
        code = str(node.get("methodCode", ""))
        if node.get("category") == "Class":
            code = _class_skeleton(project_root, node.get("funcFile", ""), node.get("methodSignature") or node_id)
        documents.append(f"{node.get('label', '')}\n{code}")
    _progress(
        "graph-ranking",
        "Ranking expanded graph nodes with query-code similarity and personalized PageRank.",
        6,
        expandedNodeCount=len(node_ids),
        expandedEdgeCount=len(edges),
    )
    sim_scores = dict(
        zip(
            node_ids,
            _score_graph_code_contexts(
                query,
                documents,
                repo_id=repo_id,
                entity_ids=node_ids,
                source_paths=[str(nodes[node_id].get("funcFile") or "") for node_id in node_ids],
            ),
        )
    )
    base_scores = {node_id: max(0.0, float(sim_scores.get(node_id, 0.0))) for node_id in node_ids}
    original_node_ids = {node_id for node_id, node in nodes.items() if node.get("srcType") == "original"}
    boosted_nodes = 0
    boosted_total_delta = 0.0
    for edge in edges:
        source = edge.get("from")
        dest = edge.get("to")
        for original_id, neighbor_id in ((source, dest), (dest, source)):
            if original_id not in original_node_ids or neighbor_id not in nodes or neighbor_id in original_node_ids:
                continue
            original = nodes.get(original_id, {})
            inc = 0.0
            if original.get("boostSameFeature"):
                inc += 0.1
            if original.get("boostSameFile"):
                inc += 0.1
            if original.get("boostSimilarMethod"):
                inc += 0.1
            if inc <= 0:
                continue
            base_scores[neighbor_id] = float(base_scores.get(neighbor_id, 0.0)) + inc
            boosted_nodes += 1
            boosted_total_delta += inc
    if boosted_nodes:
        _log(f"Personalization boosted: expanded_neighbors={boosted_nodes} total_delta={boosted_total_delta:.4f}")

    try:
        import networkx as nx

        graph = nx.DiGraph()
        graph.add_nodes_from(node_ids)
        graph.add_edges_from([(edge["from"], edge["to"]) for edge in edges if edge["from"] in nodes and edge["to"] in nodes])
        total = sum(max(score, 0.0) for score in base_scores.values())
        if total <= 0:
            personalization = {node_id: 1.0 / max(len(node_ids), 1) for node_id in node_ids}
        else:
            personalization = {node_id: max(score, 0.0) / total for node_id, score in base_scores.items()}
        rank_scores = nx.pagerank(graph, alpha=0.85, personalization=personalization) if node_ids else {}
    except Exception as exc:
        _log(f"PageRank fallback: {exc}")
        rank_scores = base_scores

    selected_ids = [node_id for node_id, _ in sorted(rank_scores.items(), key=lambda item: item[1], reverse=True)[:top_k_nodes]]
    selected_set = set(selected_ids)
    graph_nodes = []
    for node_id in selected_ids:
        node = dict(nodes[node_id])
        node["score"] = float(rank_scores.get(node_id, base_scores.get(node_id, 0.0)))
        node.pop("methodCode", None)
        node.pop("boostSameFeature", None)
        node.pop("boostSameFile", None)
        node.pop("boostSimilarMethod", None)
        graph_nodes.append(node)
    seen_edge_keys = set()
    graph_edges = []
    for edge in edges:
        if edge["from"] not in selected_set or edge["to"] not in selected_set:
            continue
        key = (edge["from"], edge["to"], edge.get("type", ""))
        if key in seen_edge_keys:
            continue
        seen_edge_keys.add(key)
        graph_edges.append(edge)
    affected_files = sorted({
        str(nodes[node_id].get("funcFile", ""))
        for node_id in selected_ids
        if str(nodes[node_id].get("funcFile", "")).strip()
    })
    localized_methods = [nodes[node_id].get("methodSignature", node_id) for node_id in selected_ids if nodes[node_id].get("category") == "Function"]
    _progress(
        "top-k-subgraph",
        "Selecting the top-k induced reasoning subgraph.",
        6,
        selectedNodeCount=len(graph_nodes),
        selectedEdgeCount=len(graph_edges),
    )
    reasoning_graph = {"nodes": graph_nodes, "edges": graph_edges}
    graph_stages = [
        initial_graph,
        expanded_graph,
        {
            "id": "reasoning",
            "label": "Reasoning Graph",
            "description": "Top-k induced subgraph after query-code ranking and personalized PageRank.",
            "nodes": graph_nodes,
            "edges": graph_edges,
        },
    ]
    return reasoning_graph, localized_methods, affected_files, graph_stages


def _lookup_method_info(method: Any, info_by_norm: dict[str, dict[str, Any]]) -> dict[str, Any] | None:
    norm = _normalize_symbol(method)
    if not norm:
        return None
    info = info_by_norm.get(norm)
    if info is not None:
        return info
    return info_by_norm.get(_without_first_segment(norm))


def _resolve_project_file(project_root: Path, file_path: Any) -> Path | None:
    text = str(file_path or "").strip()
    if not text:
        return None
    candidate = Path(text)
    if candidate.is_absolute() and candidate.exists():
        return candidate
    candidate = project_root / text.lstrip("/")
    if candidate.exists():
        return candidate
    return None


def _read_python_source(project_root: Path, file_path: Any) -> tuple[str, ast.Module] | None:
    resolved = _resolve_project_file(project_root, file_path)
    if resolved is None:
        return None
    try:
        source = resolved.read_text(encoding="utf-8", errors="replace")
        return source, ast.parse(source)
    except Exception as exc:
        _log(f"Failed to parse Python file for FocusGraph context ({file_path}): {exc}")
        return None


def _source_lines(source: str, start_line: int, end_line: int) -> str:
    lines = source.splitlines(keepends=True)
    start = max(1, start_line)
    end = min(len(lines), end_line)
    if start > end:
        return ""
    return "".join(lines[start - 1:end])


def _node_start_line(node: ast.AST) -> int:
    decorator_lines = [getattr(item, "lineno", getattr(node, "lineno", 1)) for item in getattr(node, "decorator_list", [])]
    return min([getattr(node, "lineno", 1), *decorator_lines])


def _class_skeleton(project_root: Path, file_path: Any, class_qname: Any) -> str:
    parsed = _read_python_source(project_root, file_path)
    if parsed is None:
        return ""
    source, tree = parsed
    short_name = str(class_qname or "").split(".")[-1]
    target: ast.ClassDef | None = None
    for node in ast.walk(tree):
        if isinstance(node, ast.ClassDef) and node.name == short_name:
            target = node
            break
    if target is None or not hasattr(target, "end_lineno"):
        return ""

    body = list(target.body)
    if not body:
        return _source_lines(source, target.lineno, target.end_lineno)

    parts: list[str] = []
    header_end = max(target.lineno, min(getattr(child, "lineno", target.lineno) for child in body) - 1)
    parts.append(_source_lines(source, _node_start_line(target), header_end))

    lines = source.splitlines(keepends=True)
    fallback_body_indent = " " * (len(lines[target.lineno - 1]) - len(lines[target.lineno - 1].lstrip()) + 4)
    for child in body:
        child_start = _node_start_line(child)
        child_end = getattr(child, "end_lineno", getattr(child, "lineno", child_start))
        if isinstance(child, (ast.FunctionDef, ast.AsyncFunctionDef)):
            if child.name == "__init__":
                parts.append(_source_lines(source, child_start, child_end))
                continue
            body_start = getattr(child.body[0], "lineno", getattr(child, "lineno", child_start)) if child.body else child_end
            signature_end = max(child_start, body_start - 1)
            parts.append(_source_lines(source, child_start, signature_end))
            indent = " " * getattr(child, "col_offset", len(fallback_body_indent)) + "    "
            parts.append(f"{indent}...\n")
        else:
            parts.append(_source_lines(source, child_start, child_end))
    return "".join(parts).strip()


def _module_level_variable_and_class_names(tree: ast.Module) -> set[str]:
    names: set[str] = set()

    def add_target(target: ast.AST) -> None:
        if isinstance(target, ast.Name):
            names.add(target.id)
        elif isinstance(target, (ast.Tuple, ast.List)):
            for item in target.elts:
                add_target(item)

    for node in tree.body:
        if isinstance(node, ast.ClassDef):
            names.add(node.name)
        elif isinstance(node, ast.Assign):
            for target in node.targets:
                add_target(target)
        elif isinstance(node, ast.AnnAssign):
            add_target(node.target)
    return names


def _definition_map(tree: ast.Module) -> dict[tuple[str, str], list[ast.AST]]:
    result: dict[tuple[str, str], list[ast.AST]] = defaultdict(list)
    for node in ast.walk(tree):
        if isinstance(node, ast.ClassDef):
            result[("Class", node.name)].append(node)
        elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            result[("Function", node.name)].append(node)
    return result


def _params_from_signature(method_signature: Any) -> list[str] | None:
    text = str(method_signature or "")
    match = re.search(r"\(([^)]*)\)", text)
    if not match:
        return None
    params = []
    for part in match.group(1).split(","):
        item = part.strip()
        if not item:
            continue
        item = item.split("=", 1)[0].split(":", 1)[0].strip().lstrip("*")
        if item:
            params.append(item)
    return params


def _params_from_ast_node(node: ast.AST) -> list[str]:
    if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
        return []
    args = []
    all_args = [*node.args.posonlyargs, *node.args.args, *node.args.kwonlyargs]
    if node.args.vararg is not None:
        all_args.append(node.args.vararg)
    if node.args.kwarg is not None:
        all_args.append(node.args.kwarg)
    for arg in all_args:
        args.append(arg.arg)
    return args


def _resolve_ast_definition(
    definition_map: dict[tuple[str, str], list[ast.AST]],
    category: str,
    short_name: str,
    method_signature: Any,
) -> ast.AST | None:
    candidates = definition_map.get((category, short_name), [])
    if not candidates:
        return None
    if len(candidates) == 1 or category != "Function":
        return candidates[0]
    expected_params = _params_from_signature(method_signature)
    if expected_params is None:
        return candidates[0]
    for candidate in candidates:
        if _params_from_ast_node(candidate) == expected_params:
            return candidate
    return candidates[0]


def _target_names(target: ast.AST) -> set[str]:
    if isinstance(target, ast.Name):
        return {target.id}
    if isinstance(target, (ast.Tuple, ast.List)):
        names: set[str] = set()
        for item in target.elts:
            names.update(_target_names(item))
        return names
    if isinstance(target, ast.Starred):
        return _target_names(target.value)
    return set()


def _local_definitions(node: ast.AST) -> set[str]:
    defined: set[str] = set()
    if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
        defined.update(name for name in _params_from_ast_node(node) if name != "self")
    for child in ast.walk(node):
        if isinstance(child, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            if child is not node:
                defined.add(child.name)
        elif isinstance(child, (ast.Assign, ast.AnnAssign, ast.AugAssign)):
            targets = getattr(child, "targets", None)
            if targets is None:
                targets = [getattr(child, "target", None)]
            for target in targets:
                if target is not None:
                    defined.update(_target_names(target))
        elif isinstance(child, (ast.For, ast.AsyncFor)):
            defined.update(_target_names(child.target))
        elif isinstance(child, ast.With):
            for item in child.items:
                if item.optional_vars is not None:
                    defined.update(_target_names(item.optional_vars))
        elif isinstance(child, ast.Import):
            for alias in child.names:
                defined.add((alias.asname or alias.name).split(".")[0])
        elif isinstance(child, ast.ImportFrom):
            for alias in child.names:
                defined.add(alias.asname or alias.name)
    return defined


def _used_names(node: ast.AST, local_defs: set[str]) -> set[str]:
    used: set[str] = set()
    for child in ast.walk(node):
        if isinstance(child, ast.Name) and isinstance(child.ctx, ast.Load) and child.id not in local_defs:
            used.add(child.id)
    return used


def _get_used_globals_in_file(project_root: Path, file_path: Any, node_keys: list[tuple[str, str, str]]) -> set[str]:
    parsed = _read_python_source(project_root, file_path)
    if parsed is None:
        return set()
    _, tree = parsed
    module_names = _module_level_variable_and_class_names(tree)
    definitions = _definition_map(tree)
    used_globals: set[str] = set()
    for category, short_name, method_signature in node_keys:
        ast_node = _resolve_ast_definition(definitions, category, short_name, method_signature)
        if ast_node is None:
            continue
        used_globals.update(_used_names(ast_node, _local_definitions(ast_node)) & module_names)
    return used_globals


def _current_feature_context(current_code_map: list[Any], methods_df: pd.DataFrame, operation: str = "") -> str:
    info_by_norm, _ = _method_maps(methods_df)
    file_to_methods: dict[str, list[dict[str, str]]] = defaultdict(list)
    missing_methods: list[str] = []
    seen_methods: set[str] = set()

    for method in current_code_map or []:
        norm = _normalize_symbol(method)
        if not norm or norm in seen_methods:
            continue
        seen_methods.add(norm)
        info = _lookup_method_info(method, info_by_norm)
        if not info:
            missing_methods.append(str(method))
            continue
        file_path = str(info.get("func_file") or "unknown.py")
        file_to_methods[file_path].append(
            {
                "signature": str(info.get("method_signature") or method),
                "code": str(info.get("method_code") or ""),
            }
        )

    if not file_to_methods and not missing_methods:
        return ""

    sections = [
        "## Current Feature Complete CodeMap",
        "",
        "The following functions/methods are forced into the Agent context because they belong to the currently selected feature.",
    ]
    for file_path in sorted(file_to_methods.keys()):
        sections.extend(["", f"--- File: {file_path} ---", ""])
        for item in file_to_methods[file_path]:
            sections.append(f"# CURRENT_FEATURE {item['signature']}\n{item['code']}")
    if missing_methods:
        sections.extend(["", "Current feature methods not found in methods.csv:"])
        sections.extend(f"- {method}" for method in missing_methods)
    return "\n".join(sections)


def _operation_context_header(
    request: dict[str, Any],
    selected_features: list[dict[str, Any]],
    seed_methods: list[str],
) -> str:
    operation = str(request.get("operation", "")).strip().lower()
    if operation == "modify":
        lines = [
            "## Modify Feature Request",
            "",
            "Original feature description:",
            str(request.get("oldFeatureDescription") or request.get("featureDescription") or "").strip(),
            "",
            "New feature description:",
            str(request.get("newFeatureDescription") or "").strip(),
            "",
            "Delta query used for FocusGraph retrieval:",
            str(request.get("query") or request.get("newFeatureDescription") or "").strip(),
            "",
            "## Retrieved Similar Features",
        ]
        if selected_features:
            for feature in selected_features:
                lines.append(
                    f"- rank={feature.get('rank')} featureId={feature.get('featureId')} "
                    f"reason={feature.get('reason')} score={feature.get('score')}: {feature.get('description')}"
                )
        else:
            lines.append("- None")
        lines.extend(["", "## Seed Methods"])
        if seed_methods:
            lines.extend(f"- {method}" for method in seed_methods)
        else:
            lines.append("- None")
        return "\n".join(lines)
    if operation == "add":
        lines = [
            "## Add Feature Request",
            "",
            str(request.get("newFeatureDescription") or request.get("featureDescription") or request.get("query") or "").strip(),
            "",
            "## Retrieved Similar Features",
        ]
        if selected_features:
            for feature in selected_features:
                lines.append(
                    f"- rank={feature.get('rank')} featureId={feature.get('featureId')} "
                    f"score={feature.get('score')}: {feature.get('description')}"
                )
        else:
            lines.append("- None")
        lines.extend(["", "## Seed Methods"])
        if seed_methods:
            lines.extend(f"- {method}" for method in seed_methods)
        else:
            lines.append("- None")
        return "\n".join(lines)
    if operation == "delete":
        lines = [
            "## Delete Feature Request",
            "",
            "Remove the selected feature and clean up direct callers/usages when required, while preserving unrelated behavior.",
            "",
            "Selected feature description:",
            str(request.get("oldFeatureDescription") or request.get("featureDescription") or "").strip(),
            "",
            "## Delete Seed Methods",
        ]
        if seed_methods:
            lines.extend(f"- {method}" for method in seed_methods)
        else:
            lines.append("- None")
        return "\n".join(lines)
    return ""


def _node_short_name(node: dict[str, Any]) -> str:
    value = _normalize_symbol(node.get("methodSignature") or node.get("id") or node.get("label") or "")
    return value.split(".")[-1] if value else ""


def _context_prompt(
    reasoning_graph: dict[str, Any],
    methods_df: pd.DataFrame,
    project_root: Path,
    current_code_map: list[Any] | None = None,
    operation: str = "",
    request: dict[str, Any] | None = None,
    selected_features: list[dict[str, Any]] | None = None,
    seed_methods: list[str] | None = None,
) -> str:
    _progress(
        "context-prompt",
        "Building the file-organized context prompt for the Python Agent.",
        7,
        reasoningNodeCount=len(reasoning_graph.get("nodes", [])),
        reasoningEdgeCount=len(reasoning_graph.get("edges", [])),
    )
    info_by_norm, _ = _method_maps(methods_df)
    file_to_nodes: dict[str, list[dict[str, str]]] = defaultdict(list)
    file_to_node_keys: dict[str, list[tuple[str, str, str]]] = defaultdict(list)
    for node in reasoning_graph.get("nodes", []):
        category = str(node.get("category") or "Function")
        signature = str(node.get("methodSignature") or node.get("id") or node.get("label") or "")
        norm = _normalize_symbol(signature)
        info = (_lookup_method_info(norm, info_by_norm) or {}) if category == "Function" else {}
        file_path = str(info.get("func_file") or node.get("funcFile") or "unknown.py")
        if category == "Function":
            code = str(info.get("method_code") or "")
        elif category == "Class":
            code = _class_skeleton(project_root, file_path, signature) or f"# Class: {signature}"
        else:
            code = str(node.get("methodCode") or "")
        file_to_nodes[file_path].append(
            {
                "category": category,
                "signature": str(info.get("method_signature") or signature),
                "code": code,
            }
        )
        short_name = _node_short_name(node)
        if short_name:
            file_to_node_keys[file_path].append((category, short_name, signature))

    relation_by_file: dict[str, list[str]] = defaultdict(list)
    node_file = {node.get("id"): node.get("funcFile") for node in reasoning_graph.get("nodes", [])}
    for edge in reasoning_graph.get("edges", []):
        rel = f"{edge.get('from')} {edge.get('type', 'relates_to')} {edge.get('to')}"
        src_file = node_file.get(edge.get("from"))
        dest_file = node_file.get(edge.get("to"))
        if src_file:
            relation_by_file[str(src_file)].append(rel)
        if dest_file and dest_file != src_file:
            relation_by_file[str(dest_file)].append(rel)

    sections = []
    for file_path in sorted(file_to_nodes.keys()):
        lines = [f"--- File: {file_path} ---"]
        used_globals = _get_used_globals_in_file(project_root, file_path, file_to_node_keys.get(file_path, []))
        if used_globals:
            lines.extend([
                "",
                "**Important module-level variable and class names** used here:",
                ", ".join(sorted(used_globals)),
            ])
        lines.extend(["", "**Important Relations** between code entities:"])
        relations = list(dict.fromkeys(relation_by_file.get(file_path) or []))
        if relations:
            lines.extend(f"  {rel}" for rel in relations)
        else:
            lines.append("  None")
        lines.extend(["", "**Important Code Context**:"])
        for item in file_to_nodes[file_path]:
            prefix = "Class skeleton" if item["category"] == "Class" else item["category"]
            lines.append(f"\n# {prefix}: {item['signature']}\n{item['code']}")
        sections.append("\n".join(lines))
    reasoning_context = "\n\n".join(sections) if sections else "No FocusGraph context was found."
    prefix_sections = []
    operation_header = _operation_context_header(request or {}, selected_features or [], seed_methods or [])
    if operation_header:
        prefix_sections.append(operation_header)
    current_context = _current_feature_context(current_code_map or [], methods_df, operation)
    if current_context:
        prefix_sections.append(current_context)
    prefix = "\n\n".join(prefix_sections)
    return f"{prefix}\n\n## FocusGraph Reasoning Context\n\n{reasoning_context}" if prefix else reasoning_context


def build_context(request: dict[str, Any]) -> dict[str, Any]:
    project_root, output_dir = _repo_paths(request)
    _progress(
        "load-summary",
        "Loading RepoSummary features, methods, and ENRE graph files.",
        2,
        repoId=str(request.get("repoId", "")),
    )
    query = str(request.get("query") or request.get("newFeatureDescription") or "").strip()
    if not query:
        query = str(request.get("featureDescription") or "").strip()
    if not query:
        raise RuntimeError("Missing query/newFeatureDescription in request")

    top_k_nodes = int(request.get("topKGraphNodes") or os.getenv("FOCUSGRAPH_TOP_K_NODES", "15"))
    operation = str(request.get("operation", "")).strip().lower()
    features_df = _load_features(output_dir)
    methods_df = _load_methods(output_dir)
    features = _feature_index(features_df)
    selected_features, seed_methods = _select_features(query, features, request)
    _progress(
        "load-enre",
        "Loading ENRE dependency graph for graph expansion.",
        4,
        methodCount=len(methods_df),
    )
    enre_data = _load_enre(output_dir)
    reasoning_graph, localized_methods, affected_files, graph_stages = _build_reasoning_graph(
        query=query,
        seed_methods=seed_methods,
        current_code_map=request.get("currentCodeMap", []),
        methods_df=methods_df,
        enre_data=enre_data,
        top_k_nodes=top_k_nodes,
        project_root=project_root,
        repo_id=request.get("repoId"),
    )
    context_prompt = _context_prompt(
        reasoning_graph,
        methods_df,
        project_root,
        request.get("currentCodeMap", []),
        operation,
        request,
        selected_features,
        seed_methods,
    )

    return {
        "query": query,
        "selectedFeatures": selected_features,
        "seedMethods": seed_methods,
        "localizedMethods": localized_methods,
        "affectedFiles": affected_files,
        "reasoningGraph": reasoning_graph,
        "graphStages": graph_stages,
        "contextPrompt": context_prompt,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pretty", action="store_true")
    parser.parse_args()
    try:
        request = _read_request()
        result = build_context(request)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except Exception as exc:
        _log(str(exc))
        print(json.dumps({"error": str(exc)}, ensure_ascii=False))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
