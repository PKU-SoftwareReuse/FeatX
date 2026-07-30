from __future__ import annotations

import ast
import csv
import io
import json
import os
import sys
import threading
import time
import traceback
import uuid
from contextlib import redirect_stderr, redirect_stdout
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Callable
from urllib.parse import parse_qs, urlparse

from ..analysis.python import delete_feature, method_extractor
from ..graph import focus, java_rank
from ..storage import database, embedding_cache
from ..summary import python_repository, repository, translation


SERVICE_VERSION = 1
_MODEL_STATUS: dict[str, Any] = {}
_JOBS: dict[str, dict[str, Any]] = {}
_JOBS_LOCK = threading.RLock()
_SUMMARY_LOCK = threading.Lock()
_INDEX_LOCK = threading.Lock()


class _JobStream(io.TextIOBase):
    def __init__(self, job_id: str, original: io.TextIOBase):
        self.job_id = job_id
        self.original = original
        self.buffer = ""

    def write(self, value: str) -> int:
        text = str(value)
        self.original.write(text)
        self.original.flush()
        self.buffer += text
        while "\n" in self.buffer:
            line, self.buffer = self.buffer.split("\n", 1)
            _append_job_log(self.job_id, line)
        return len(text)

    def flush(self) -> None:
        self.original.flush()
        if self.buffer:
            _append_job_log(self.job_id, self.buffer)
            self.buffer = ""


class RepoSummaryHandler(BaseHTTPRequestHandler):
    server_version = "FeatXRepoSummary/1"
    protocol_version = "HTTP/1.1"

    def do_GET(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        if parsed.path == "/health":
            self._json_response(
                HTTPStatus.OK,
                {
                    "status": "ready",
                    "version": SERVICE_VERSION,
                    "models": _MODEL_STATUS,
                },
            )
            return
        if parsed.path.startswith("/v1/reposummary/jobs/"):
            job_id = parsed.path.rsplit("/", 1)[-1]
            cursor = int((parse_qs(parsed.query).get("cursor") or ["0"])[0])
            self._json_response(HTTPStatus.OK, _job_snapshot(job_id, cursor))
            return
        if parsed.path.startswith("/v1/cache/stats/"):
            repo_id = parsed.path.rsplit("/", 1)[-1]
            self._json_response(HTTPStatus.OK, embedding_cache.cache_stats(repo_id))
            return
        self._json_response(HTTPStatus.NOT_FOUND, {"error": f"Unknown path: {parsed.path}"})

    def do_POST(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        try:
            request = self._read_json()
            if parsed.path == "/v1/focusgraph/context":
                self._stream_result(lambda progress: _focusgraph_context(request, progress))
                return
            if parsed.path == "/v1/java-graph/retrieve":
                self._stream_result(lambda progress: _java_retrieve(request, progress))
                return
            if parsed.path == "/v1/java-graph/rank":
                self._stream_result(lambda progress: _java_rank(request, progress))
                return
            if parsed.path == "/v1/cache/sync":
                self._json_response(HTTPStatus.OK, focus.sync_bge_code_cache(request))
                return
            if parsed.path == "/v1/cache/sync-python":
                self._json_response(HTTPStatus.OK, _sync_python_cache(request))
                return
            if parsed.path == "/v1/cache/warm-python":
                self._json_response(HTTPStatus.OK, _warm_python_cache(request))
                return
            if parsed.path == "/v1/index/sync-features":
                self._json_response(HTTPStatus.OK, _sync_feature_index(request))
                return
            if parsed.path == "/v1/python/delete-feature":
                self._json_response(HTTPStatus.OK, delete_feature.plan_delete(request))
                return
            if parsed.path == "/v1/python/extract-methods":
                self._json_response(HTTPStatus.OK, _extract_python_methods(request))
                return
            if parsed.path == "/v1/python/validate":
                self._json_response(HTTPStatus.OK, _validate_python(request))
                return
            if parsed.path == "/v1/reposummary/jobs":
                self._json_response(HTTPStatus.ACCEPTED, _start_summary_job(request))
                return
            self._json_response(HTTPStatus.NOT_FOUND, {"error": f"Unknown path: {parsed.path}"})
        except Exception as exc:
            traceback.print_exc()
            self._json_response(
                HTTPStatus.INTERNAL_SERVER_ERROR,
                {"error": str(exc), "type": type(exc).__name__},
            )

    def log_message(self, format_string: str, *args: Any) -> None:
        print(f"[reposummary-http] {self.address_string()} {format_string % args}", file=sys.stderr)

    def _read_json(self) -> dict[str, Any]:
        content_length = int(self.headers.get("Content-Length") or "0")
        raw = self.rfile.read(content_length)
        if not raw.strip():
            return {}
        value = json.loads(raw.decode("utf-8"))
        if not isinstance(value, dict):
            raise ValueError("JSON request must be an object")
        return value

    def _json_response(self, status: HTTPStatus, payload: Any) -> None:
        raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        try:
            self.send_response(int(status))
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _stream_result(self, operation: Callable[[Callable[[dict[str, Any]], None]], Any]) -> None:
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "application/x-ndjson; charset=utf-8")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True

        def emit_progress(payload: dict[str, Any]) -> None:
            self._stream_line({"type": "progress", "progress": payload})

        try:
            result = operation(emit_progress)
            self._stream_line({"type": "result", "result": result})
        except Exception as exc:
            traceback.print_exc()
            self._stream_line({"type": "error", "error": str(exc), "errorType": type(exc).__name__})

    def _stream_line(self, payload: Any) -> None:
        try:
            self.wfile.write((json.dumps(payload, ensure_ascii=False) + "\n").encode("utf-8"))
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            raise ConnectionAbortedError("HTTP client disconnected")


def _focusgraph_context(request: dict[str, Any], progress: Callable[[dict[str, Any]], None]) -> Any:
    with focus.progress_callback(progress):
        return focus.build_context(request)


def _java_retrieve(request: dict[str, Any], progress: Callable[[dict[str, Any]], None]) -> Any:
    with java_rank.progress_callback(progress):
        return java_rank.retrieve_features(request)


def _java_rank(request: dict[str, Any], progress: Callable[[dict[str, Any]], None]) -> Any:
    with java_rank.progress_callback(progress):
        return java_rank.rank_graph(request)


def _extract_python_methods(request: dict[str, Any]) -> dict[str, Any]:
    src_root = Path(str(request["srcRoot"])).resolve()
    methods: list[dict[str, str]] = []
    for file_name in request.get("files") or []:
        methods.extend(method_extractor.extract_file(src_root, str(file_name)))
    return {"methods": methods}


def _validate_python(request: dict[str, Any]) -> dict[str, Any]:
    filename = str(request.get("filename") or "generated.py")
    content = str(request.get("content") or "")
    ast.parse(content, filename=filename)
    return {"valid": True}


def _sync_python_cache(request: dict[str, Any]) -> dict[str, Any]:
    repo_id = request.get("repoId")
    if repo_id is None:
        raise RuntimeError("repoId is required")
    source_root = Path(str(request["sourceRoot"])).resolve()
    project_root = Path(str(request.get("projectRoot") or source_root)).resolve()
    changed_project_paths = [str(path or "").replace("\\", "/") for path in request.get("changedPaths") or []]
    source_relative_paths: list[str] = []
    for path_value in changed_project_paths:
        absolute = (project_root / path_value).resolve()
        try:
            relative = absolute.relative_to(source_root).as_posix()
        except ValueError:
            continue
        if relative.endswith(".py"):
            source_relative_paths.append(relative)

    output_dir = embedding_cache.cache_path(repo_id).parent
    with _INDEX_LOCK:
        methods_df = _refresh_python_structure_index(source_root, output_dir)

    changed_set = set(source_relative_paths)
    nodes: list[dict[str, str]] = []
    for row in methods_df.to_dict("records"):
        source_path = str(row.get("func_file") or "").replace("\\", "/")
        if source_path not in changed_set:
            continue
        signature = str(row.get("method_signature") or "")
        entity_id = focus._normalize_symbol(signature)
        nodes.append(
            {
                "id": entity_id,
                "sourcePath": source_path,
                "text": f"{entity_id}\n{row.get('method_code') or ''}",
            }
        )
    result = focus.sync_bge_code_cache(
        {
            "repoId": repo_id,
            "entityKind": "graph-node",
            "changedPaths": source_relative_paths,
            "nodes": nodes,
        }
    )
    result["index"] = {
        "methodCount": int(len(methods_df)),
        "changedPathCount": len(source_relative_paths),
        "outputDir": str(output_dir),
    }
    return result


def _warm_python_cache(request: dict[str, Any]) -> dict[str, Any]:
    repo_id = request.get("repoId")
    if repo_id is None:
        raise RuntimeError("repoId is required")
    source_root = Path(str(request["sourceRoot"])).resolve()
    output_dir = embedding_cache.cache_path(repo_id).parent
    methods_path = output_dir / "methods.csv"
    if not methods_path.exists():
        raise RuntimeError(f"RepoSummary methods.csv not found: {methods_path}")

    methods_df = python_repository.pd.read_csv(methods_path, dtype=str, keep_default_na=False)
    methods_df = python_repository._normalize_methods_df(methods_df, source_root)
    info_by_norm, _ = focus._method_maps(methods_df)
    nodes_by_id: dict[str, dict[str, str]] = {}
    for row in methods_df.to_dict("records"):
        signature = str(row.get("method_signature") or "")
        entity_id = focus._normalize_symbol(signature)
        if not entity_id:
            continue
        nodes_by_id[entity_id] = {
            "id": entity_id,
            "sourcePath": str(row.get("func_file") or "").replace("\\", "/"),
            "text": f"{entity_id}\n{row.get('method_code') or ''}",
        }

    valid_nodes, _, id_to_qname, _, _ = focus._load_enre(output_dir)
    for node_id, node in valid_nodes.items():
        category = str(node.get("category") or "")
        qualified_name = focus._normalize_symbol(id_to_qname.get(node_id, ""))
        if not qualified_name or category not in {"Function", "Class"}:
            continue
        if category == "Function":
            entity_id = qualified_name
            if entity_id not in info_by_norm:
                stripped = focus._without_first_segment(entity_id)
                if stripped in info_by_norm:
                    entity_id = stripped
            info = info_by_norm.get(entity_id, {})
            source_path = str(info.get("func_file") or "").replace("\\", "/")
            code = str(info.get("method_code") or "")
        else:
            entity_id = qualified_name
            source_path = str(node.get("File") or "").replace("\\", "/")
            code = focus._class_skeleton(source_root, source_path, entity_id)
        nodes_by_id[entity_id] = {
            "id": entity_id,
            "sourcePath": source_path,
            "text": f"{entity_id}\n{code}",
        }

    nodes = list(nodes_by_id.values())
    result = focus.sync_bge_code_cache(
        {
            "repoId": repo_id,
            "entityKind": "graph-node",
            "fullSync": True,
            "nodes": nodes,
        }
    )
    result["index"] = {
        "methodCount": int(len(methods_df)),
        "outputDir": str(output_dir),
    }
    return result


def _refresh_python_structure_index(source_root: Path, output_dir: Path):
    output_dir.mkdir(parents=True, exist_ok=True)
    python_repository.enre_main([str(source_root), str(output_dir)])
    methods_path = output_dir / "methods.csv"
    if not methods_path.exists():
        raise RuntimeError(f"ENRE did not produce methods.csv at {methods_path}")
    methods_df = python_repository.pd.read_csv(methods_path, dtype=str, keep_default_na=False)
    methods_df = python_repository._normalize_methods_df(methods_df, source_root)
    methods_df.to_csv(methods_path, index=False)
    python_repository._write_method_compat_files(output_dir)
    python_repository._write_headered_file_matrix(output_dir, source_root, methods_df)
    python_repository._write_method_file_map(methods_df, output_dir)
    python_repository._write_method_container_map(methods_df, output_dir, source_root)
    python_repository._write_container_edges(methods_df, output_dir)
    return methods_df


def _sync_feature_index(request: dict[str, Any]) -> dict[str, Any]:
    repo_id = request.get("repoId")
    if repo_id is None:
        raise RuntimeError("repoId is required")
    features = list(request.get("features") or [])
    output_dir = embedding_cache.cache_path(repo_id).parent
    output_dir.mkdir(parents=True, exist_ok=True)
    rows: list[dict[str, str]] = []
    for feature in features:
        methods = [str(value) for value in feature.get("methods") or [] if str(value).strip()]
        if not methods:
            methods = [""]
        for method in methods:
            rows.append(
                {
                    "id": str(feature.get("featureId") or ""),
                    "cluster_id": str(feature.get("clusterId") or ""),
                    "module_desc": str(feature.get("moduleDesc") or ""),
                    "desc": str(feature.get("description") or ""),
                    "method_name": method,
                    "func_file": "",
                    "flow": "",
                    "notf": "",
                }
            )
    target = output_dir / "features.csv"
    temporary = output_dir / f".features-{uuid.uuid4().hex}.tmp"
    fieldnames = ["id", "cluster_id", "module_desc", "desc", "method_name", "func_file", "flow", "notf"]
    with _INDEX_LOCK:
        with temporary.open("w", encoding="utf-8", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=fieldnames)
            writer.writeheader()
            writer.writerows(rows)
        temporary.replace(target)
        cache_result = focus.sync_feature_embedding_cache(
            {"repoId": repo_id, "features": features}
        )
    return {
        "repoId": str(repo_id),
        "featureCount": len(features),
        "rowCount": len(rows),
        "path": str(target),
        "cache": cache_result,
    }


def _start_summary_job(request: dict[str, Any]) -> dict[str, Any]:
    repo_id = str(request.get("repoId") or "").strip()
    if not repo_id:
        raise ValueError("repoId is required")
    with _JOBS_LOCK:
        for job_id, state in _JOBS.items():
            if state["repoId"] == repo_id and state["status"] in {"queued", "running"}:
                return {"jobId": job_id, "repoId": repo_id, "status": state["status"], "reused": True}
        job_id = str(uuid.uuid4())
        _JOBS[job_id] = {
            "jobId": job_id,
            "repoId": repo_id,
            "status": "queued",
            "error": None,
            "logs": [],
            "startedAt": None,
            "finishedAt": None,
        }
    thread = threading.Thread(target=_run_summary_job, args=(job_id, repo_id), daemon=True)
    thread.start()
    return {"jobId": job_id, "repoId": repo_id, "status": "queued", "reused": False}


def _run_summary_job(job_id: str, repo_id: str) -> None:
    with _SUMMARY_LOCK:
        _update_job(job_id, status="running", startedAt=time.time())
        stdout = _JobStream(job_id, sys.__stdout__)
        stderr = _JobStream(job_id, sys.__stderr__)
        try:
            with redirect_stdout(stdout), redirect_stderr(stderr):
                repository.main(repo_id)
                print("[reposummary-main] Translating English summaries to Chinese", flush=True)
                translated_count = translation.main(repo_id)
                print(
                    f"[reposummary-main] Chinese translation complete ({translated_count} unique summaries)",
                    flush=True,
                )
                print("[reposummary-main] Writing summary to database", flush=True)
                database.main(repo_id)
                print("[reposummary-main] Database write complete", flush=True)
            stdout.flush()
            stderr.flush()
            _update_job(job_id, status="complete", finishedAt=time.time())
        except Exception as exc:
            traceback.print_exc(file=stderr)
            stderr.flush()
            _update_job(job_id, status="failed", error=str(exc), finishedAt=time.time())


def _append_job_log(job_id: str, line: str) -> None:
    with _JOBS_LOCK:
        state = _JOBS.get(job_id)
        if state is not None:
            state["logs"].append(str(line))


def _update_job(job_id: str, **values: Any) -> None:
    with _JOBS_LOCK:
        state = _JOBS.get(job_id)
        if state is not None:
            state.update(values)


def _job_snapshot(job_id: str, cursor: int) -> dict[str, Any]:
    with _JOBS_LOCK:
        state = _JOBS.get(job_id)
        if state is None:
            raise KeyError(f"Unknown RepoSummary job: {job_id}")
        logs = list(state["logs"])
        safe_cursor = max(0, min(cursor, len(logs)))
        return {
            "jobId": job_id,
            "repoId": state["repoId"],
            "status": state["status"],
            "error": state["error"],
            "logs": logs[safe_cursor:],
            "nextCursor": len(logs),
            "startedAt": state["startedAt"],
            "finishedAt": state["finishedAt"],
        }


def _preload_models() -> None:
    started = time.perf_counter()
    feature = focus._load_sentence_model("FOCUSGRAPH_FEATURE_EMBEDDING_MODEL", "all-MiniLM-L6-v2")
    _MODEL_STATUS["feature"] = {
        "loaded": feature is not None,
        "model": os.getenv("FOCUSGRAPH_FEATURE_EMBEDDING_MODEL") or "all-MiniLM-L6-v2",
    }
    backend = os.getenv("FOCUSGRAPH_GRAPH_EMBEDDING_BACKEND", "bge-code").strip().lower()
    if backend in {"bge", "bge-code", "bge_code"}:
        graph = focus._load_bge_code_model()
    else:
        graph = focus._load_sentence_model("FOCUSGRAPH_GRAPH_EMBEDDING_MODEL", "all-MiniLM-L6-v2")
    _MODEL_STATUS["graph"] = {
        "loaded": graph is not None,
        "backend": backend,
        "model": os.getenv("FOCUSGRAPH_GRAPH_EMBEDDING_MODEL") or "BAAI/bge-code-v1",
    }
    _MODEL_STATUS["preloadSeconds"] = round(time.perf_counter() - started, 3)


def main() -> int:
    _preload_models()
    host = os.getenv("REPOSUMMARY_HTTP_HOST", "0.0.0.0")
    port = int(os.getenv("REPOSUMMARY_HTTP_PORT", "8091"))
    server = ThreadingHTTPServer((host, port), RepoSummaryHandler)
    print(f"[reposummary-http] listening on http://{host}:{port}", file=sys.stderr, flush=True)
    try:
        server.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
