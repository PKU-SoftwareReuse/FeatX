import json
import os
import sys
import threading
from contextlib import contextmanager
from collections import defaultdict
from typing import Any, Callable, Iterator

try:
    from .focusgraph_cli import _score_graph_code_contexts, _score_texts_with_embedding
except ImportError:
    from focusgraph_cli import _score_graph_code_contexts, _score_texts_with_embedding


PROGRESS_PREFIX = "__FOCUSGRAPH_PROGRESS__"
_PROGRESS_CONTEXT = threading.local()
METHOD_CATEGORIES = {"Method", "Constructor", "Initializer"}
CLASS_CATEGORIES = {"Class", "Interface"}
TYPE_CATEGORIES = {"Enum", "Annotation"}
FIELD_CATEGORIES = {"Field", "AnnotationMember"}


def _progress(stage: str, message: str, step: int, **details: Any) -> None:
    payload = {
        "stage": stage,
        "message": message,
        "step": step,
        "total": 8,
        **details,
    }
    callback = getattr(_PROGRESS_CONTEXT, "callback", None)
    if callback is not None:
        callback(payload)
    print(f"{PROGRESS_PREFIX}{json.dumps(payload, ensure_ascii=False)}", file=sys.stderr, flush=True)


@contextmanager
def progress_callback(callback: Callable[[dict[str, Any]], None] | None) -> Iterator[None]:
    previous = getattr(_PROGRESS_CONTEXT, "callback", None)
    _PROGRESS_CONTEXT.callback = callback
    try:
        yield
    finally:
        _PROGRESS_CONTEXT.callback = previous


def _read_request() -> dict[str, Any]:
    raw = sys.stdin.read()
    if not raw.strip():
        raise RuntimeError("Missing JSON request on stdin")
    return json.loads(raw)


def _feature_documents(features: list[dict[str, Any]]) -> list[str]:
    return [f"{feature.get('moduleDesc', '')}\n{feature.get('description', '')}" for feature in features]


def retrieve_features(
    request: dict[str, Any],
    score_fn: Callable[[str, list[str]], list[float]] | None = None,
) -> dict[str, Any]:
    query = str(request.get("query") or "").strip()
    features = list(request.get("features") or [])
    operation = str(request.get("operation") or "").strip().lower()
    current_feature_id = str(request.get("featureId") or "").strip()
    top_k = max(1, int(request.get("topKFeatures") or os.getenv("FOCUSGRAPH_TOP_K_FEATURES", "3")))

    _progress(
        "feature-retrieval",
        "Retrieving top-k Java features with all-MiniLM-L6-v2 embedding search.",
        3,
        featureCount=len(features),
        queryLength=len(query),
    )
    if score_fn is None:
        score_fn = lambda text, documents: _score_texts_with_embedding(
            text,
            documents,
            env_name="FOCUSGRAPH_FEATURE_EMBEDDING_MODEL",
            default_model_name="all-MiniLM-L6-v2",
            repo_id=request.get("repoId"),
            entity_ids=[str(feature.get("featureId") or "") for feature in features],
            entity_kind="feature",
        )
    scores = score_fn(query, _feature_documents(features)) if features else []
    ranked = sorted(
        [
            {
                "feature": feature,
                "score": float(score),
            }
            for feature, score in zip(features, scores)
        ],
        key=lambda entry: (-entry["score"], str(entry["feature"].get("featureId", ""))),
    )
    for retrieval_rank, entry in enumerate(ranked, start=1):
        entry["retrievalRank"] = retrieval_rank

    selected_entries: list[dict[str, Any]] = []
    if operation == "modify" and current_feature_id:
        current = next(
            (entry for entry in ranked if str(entry["feature"].get("featureId")) == current_feature_id),
            None,
        )
        if current is not None:
            selected_entries.append({**current, "reason": "forced_current_feature"})

    seen_ids = {str(entry["feature"].get("featureId")) for entry in selected_entries}
    for entry in ranked:
        if len(selected_entries) >= top_k:
            break
        feature_id = str(entry["feature"].get("featureId"))
        if feature_id in seen_ids:
            continue
        selected_entries.append({**entry, "reason": "retrieved_by_query"})
        seen_ids.add(feature_id)

    selected_features = []
    seed_methods = []
    seen_methods = set()
    for rank, entry in enumerate(selected_entries[:top_k], start=1):
        feature = entry["feature"]
        selected_features.append(
            {
                "rank": rank,
                "featureId": str(feature.get("featureId", "")),
                "clusterId": ",".join(str(value) for value in feature.get("clusterIds") or []),
                "moduleDesc": str(feature.get("moduleDesc") or ""),
                "description": str(feature.get("description") or ""),
                "reason": entry["reason"],
                "score": entry["score"],
                "retrievalRank": entry.get("retrievalRank"),
            }
        )
        for method in feature.get("methods") or []:
            method = str(method).strip()
            if method and method not in seen_methods:
                seed_methods.append(method)
                seen_methods.add(method)

    _progress(
        "seed-methods",
        "Collecting Java seed methods from the selected features.",
        4,
        selectedFeatureCount=len(selected_features),
        seedMethodCount=len(seed_methods),
    )
    return {"selectedFeatures": selected_features, "seedMethods": seed_methods}


def _edge_weight(edge_type: str) -> float:
    normalized = edge_type.lower()
    if "call" in normalized:
        return 1.0
    if "member" in normalized:
        return 0.8
    if "fielduse" in normalized or "fielddef" in normalized:
        return 0.6
    if "type" in normalized:
        return 0.5
    if "extends" in normalized or "implements" in normalized or "inner" in normalized:
        return 0.4
    if "annotation" in normalized:
        return 0.3
    return 0.5


def _normalize_nonnegative(values: dict[str, float], fallback_ids: list[str]) -> dict[str, float]:
    total = sum(max(value, 0.0) for value in values.values())
    if total > 0:
        return {node_id: max(value, 0.0) / total for node_id, value in values.items()}
    fallback_set = [node_id for node_id in fallback_ids if node_id in values]
    if fallback_set:
        value = 1.0 / len(fallback_set)
        return {node_id: value if node_id in fallback_set else 0.0 for node_id in values}
    value = 1.0 / max(len(values), 1)
    return {node_id: value for node_id in values}


def _pagerank(
    node_ids: list[str],
    edges: list[dict[str, Any]],
    personalization: dict[str, float],
    alpha: float = 0.85,
    iterations: int = 100,
    tolerance: float = 1.0e-9,
) -> dict[str, float]:
    if not node_ids:
        return {}

    outgoing: dict[str, dict[str, float]] = defaultdict(dict)
    for edge in edges:
        source = str(edge.get("from") or "")
        target = str(edge.get("to") or "")
        if source not in personalization or target not in personalization:
            continue
        weight = _edge_weight(str(edge.get("type") or ""))
        outgoing[source][target] = outgoing[source].get(target, 0.0) + weight
        reverse_weight = weight * 0.25
        outgoing[target][source] = outgoing[target].get(source, 0.0) + reverse_weight

    rank = dict(personalization)
    for _ in range(iterations):
        dangling = sum(rank[node_id] for node_id in node_ids if not outgoing.get(node_id))
        next_rank = {
            node_id: (1.0 - alpha) * personalization[node_id] + alpha * dangling * personalization[node_id]
            for node_id in node_ids
        }
        for source, targets in outgoing.items():
            total_weight = sum(targets.values())
            if total_weight <= 0:
                continue
            for target, weight in targets.items():
                next_rank[target] += alpha * rank[source] * weight / total_weight
        error = sum(abs(next_rank[node_id] - rank[node_id]) for node_id in node_ids)
        rank = next_rank
        if error < tolerance:
            break
    return rank


def rank_graph(
    request: dict[str, Any],
    score_fn: Callable[[str, list[str]], list[float]] | None = None,
) -> dict[str, Any]:
    query = str(request.get("query") or "").strip()
    nodes = list(request.get("nodes") or [])
    edges = list(request.get("edges") or [])
    top_k = max(1, int(request.get("topKNodes") or os.getenv("FOCUSGRAPH_TOP_K_NODES", "15")))
    node_by_id = {str(node["id"]): node for node in nodes}
    node_ids = list(node_by_id)

    _progress(
        "graph-ranking",
        "Ranking Java maxGraph nodes with BGE-code similarity and personalized PageRank.",
        6,
        expandedNodeCount=len(nodes),
        expandedEdgeCount=len(edges),
    )
    if score_fn is None:
        similarities = _score_graph_code_contexts(
            query,
            [str(node_by_id[node_id].get("text") or "") for node_id in node_ids],
            repo_id=request.get("repoId"),
            entity_ids=node_ids,
            source_paths=[str(node_by_id[node_id].get("funcFile") or "") for node_id in node_ids],
        )
    else:
        similarities = score_fn(query, [str(node_by_id[node_id].get("text") or "") for node_id in node_ids])
    similarity_by_id = {
        node_id: max(0.0, float(score))
        for node_id, score in zip(node_ids, similarities)
    }

    method_scores: dict[str, float] = {}
    for node_id, node in node_by_id.items():
        if str(node.get("category")) not in METHOD_CATEGORIES:
            continue
        method_scores[node_id] = (
            0.75 * similarity_by_id.get(node_id, 0.0)
            + 0.15 * max(0.0, float(node.get("selectedFeatureScore") or 0.0))
            + 0.10 * (1.0 if node.get("seed") else 0.0)
            + 0.10 * (1.0 if node.get("currentFeature") else 0.0)
        )

    member_method_scores: dict[str, list[float]] = defaultdict(list)
    connected_method_scores: dict[str, list[float]] = defaultdict(list)
    for edge in edges:
        source = str(edge.get("from") or "")
        target = str(edge.get("to") or "")
        edge_type = str(edge.get("type") or "")
        if "Member" in edge_type and target in method_scores:
            member_method_scores[source].append(method_scores[target])
        if source in method_scores:
            connected_method_scores[target].append(method_scores[source])
        if target in method_scores:
            connected_method_scores[source].append(method_scores[target])

    base_scores: dict[str, float] = {}
    for node_id, node in node_by_id.items():
        category = str(node.get("category") or "")
        similarity = similarity_by_id.get(node_id, 0.0)
        if category in METHOD_CATEGORIES:
            base_scores[node_id] = method_scores[node_id]
        elif category in CLASS_CATEGORIES:
            member_scores = sorted(member_method_scores.get(node_id, []), reverse=True)
            max_member = member_scores[0] if member_scores else 0.0
            top_three_average = sum(member_scores[:3]) / min(len(member_scores), 3) if member_scores else 0.0
            base_scores[node_id] = 0.50 * similarity + 0.30 * max_member + 0.20 * top_three_average
        elif category in FIELD_CATEGORIES:
            connected = connected_method_scores.get(node_id, [])
            base_scores[node_id] = 0.70 * similarity + 0.30 * (max(connected) if connected else 0.0)
        elif category in TYPE_CATEGORIES:
            connected = connected_method_scores.get(node_id, [])
            base_scores[node_id] = 0.60 * similarity + 0.40 * (max(connected) if connected else 0.0)
        else:
            base_scores[node_id] = similarity

    seed_ids = [node_id for node_id, node in node_by_id.items() if node.get("seed")]
    personalization = _normalize_nonnegative(base_scores, seed_ids)
    rank_scores = _pagerank(node_ids, edges, personalization)
    selected_ids = [
        node_id
        for node_id, _ in sorted(
            rank_scores.items(),
            key=lambda item: (-item[1], item[0]),
        )[:top_k]
    ]
    _progress(
        "top-k-subgraph",
        "Selecting the top-k induced Java reasoning subgraph.",
        6,
        selectedNodeCount=len(selected_ids),
    )
    return {
        "similarityScores": similarity_by_id,
        "baseScores": base_scores,
        "rankScores": rank_scores,
        "selectedNodeIds": selected_ids,
    }


def main() -> int:
    try:
        request = _read_request()
        mode = str(request.get("mode") or "").strip().lower()
        if mode == "retrieve":
            result = retrieve_features(request)
        elif mode == "rank":
            result = rank_graph(request)
        else:
            raise RuntimeError(f"Unsupported mode: {mode}")
        print(json.dumps(result, ensure_ascii=False))
        return 0
    except Exception as exc:
        print(f"[java-graph-rank] {exc}", file=sys.stderr)
        print(json.dumps({"error": str(exc)}, ensure_ascii=False))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
