import ast
import csv
import json
import os
import shutil
import sys
import time
from collections import defaultdict
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd
from dotenv import load_dotenv

from ..analysis.python.enre.__main__ import main as enre_main
from ..paths import WORKSPACE_ENV_FILE
from . import clustering as summary_clustering
from .description_generation import generate_epic_descriptions, generate_feature_descriptions
from .embedding import load_summary_embedding_model
from .models import Feature, File, Function, method_Cluster


IGNORED_ANALYSIS_DIRECTORIES = {
    ".git", "node_modules", "target", "build", "dist", "__pycache__", ".venv", "venv", "env",
    "preprocess1", "delombok", "preprocess2"
}

load_dotenv(WORKSPACE_ENV_FILE)
load_dotenv()


def _truthy_env(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None or raw.strip() == "":
        return default
    return raw.strip().lower() in {"1", "true", "yes", "y", "on"}


def _positive_int_env(name: str, default: int) -> int:
    raw = os.getenv(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        value = int(raw)
    except ValueError:
        return default
    return value if value > 0 else default


def _normalize_rel_file(value: Any) -> str:
    path = "" if value is None else str(value).strip()
    path = path.replace("\\", "/")
    while path.startswith("./"):
        path = path[2:]
    return path


def _short_signature(signature: str) -> str:
    base = str(signature).split("(", 1)[0]
    return base.split(".")[-1] if base else str(signature)


def _format_duration(seconds: float | None) -> str:
    if seconds is None or seconds < 0:
        return "unknown"
    total_seconds = int(seconds)
    hours, remainder = divmod(total_seconds, 3600)
    minutes, secs = divmod(remainder, 60)
    if hours:
        return f"{hours}h{minutes:02d}m{secs:02d}s"
    if minutes:
        return f"{minutes}m{secs:02d}s"
    return f"{secs}s"


def _description_source_counts(results: list[dict[str, Any]]) -> dict[str, int]:
    counts: dict[str, int] = defaultdict(int)
    for row in results:
        source = str(row.get("source", "") or "unknown")
        if source.startswith("llm"):
            counts["llm"] += 1
        else:
            counts[source] += 1
    return dict(sorted(counts.items()))


def _progress_bar(completed: int, total: int, width: int = 24) -> str:
    if total <= 0:
        return "[" + "-" * width + "]"
    filled = int(width * completed / total)
    filled = max(0, min(width, filled))
    return "[" + "#" * filled + "-" * (width - filled) + "]"


def _log_description_progress(
    output_dir: Path,
    *,
    label: str,
    completed: int,
    total: int,
    started_at: float,
    results: list[dict[str, Any]],
    pending: int = 0,
    final: bool = False,
) -> None:
    elapsed = time.perf_counter() - started_at
    percent = (completed / total * 100.0) if total else 100.0
    avg = elapsed / completed if completed else 0.0
    eta = avg * (total - completed) if completed else None
    counts = _description_source_counts(results)
    counts_text = ", ".join(f"{key}={value}" for key, value in counts.items()) or "none"
    status = "done" if final else "running"
    message = (
        f"[python-reposummary] {label} {status} "
        f"{_progress_bar(completed, total)} {completed}/{total} ({percent:.1f}%) "
        f"elapsed={_format_duration(elapsed)} eta={_format_duration(eta)} "
        f"pending={pending} sources={counts_text}"
    )
    print(message, file=sys.stderr, flush=True)
    progress_payload = {
        "label": label,
        "status": status,
        "completed": completed,
        "total": total,
        "percent": percent,
        "elapsed_seconds": elapsed,
        "eta_seconds": eta,
        "pending": pending,
        "source_counts": counts,
        "updated_at_epoch": time.time(),
    }
    (output_dir / "feature_description_progress.json").write_text(
        json.dumps(progress_payload, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


def _adjust_square_matrix(matrix: np.ndarray, size: int) -> np.ndarray:
    adjusted = np.zeros((size, size), dtype=int)
    if matrix.size == 0 or size == 0:
        return adjusted
    rows = min(size, matrix.shape[0])
    cols = min(size, matrix.shape[1])
    adjusted[:rows, :cols] = matrix[:rows, :cols]
    return adjusted


def _read_numeric_matrix(path: Path, size: int) -> np.ndarray:
    if not path.exists() or path.stat().st_size == 0:
        return np.zeros((size, size), dtype=int)
    df = pd.read_csv(path, header=None)
    numeric = df.apply(pd.to_numeric, errors="coerce").fillna(0).astype(int).to_numpy()
    if numeric.shape == (size + 1, size + 1):
        numeric = numeric[1:, 1:]
    return _adjust_square_matrix(numeric, size)


def _normalize_project_file(value: Any, project_path: Path) -> str:
    path = _normalize_rel_file(value)
    root = _normalize_rel_file(project_path)
    if path.startswith(root + "/"):
        path = path[len(root) + 1:]
    return _strip_project_prefix(path, project_path.name, "/")


def _normalize_files_csv(output_dir: Path, project_path: Path) -> list[str]:
    files_path = output_dir / "files.csv"
    if not files_path.exists() or files_path.stat().st_size == 0:
        return []
    files_df = pd.read_csv(files_path, dtype=str, keep_default_na=False)
    if "file_path" not in files_df.columns:
        return []
    files_df["file_path"] = files_df["file_path"].map(lambda value: _normalize_project_file(value, project_path))
    files_df.to_csv(files_path, index=False)
    return [
        _normalize_rel_file(value)
        for value in files_df["file_path"].tolist()
        if _normalize_rel_file(value)
    ]


def _write_headered_file_matrix(output_dir: Path, project_path: Path, methods_df: pd.DataFrame) -> None:
    matrix_path = output_dir / "file_adj_matrix.csv"
    raw_path = output_dir / "file_adj_matrix.raw.csv"
    if matrix_path.exists():
        shutil.copyfile(matrix_path, raw_path)

    files = _normalize_files_csv(output_dir, project_path)
    if not files:
        files = sorted(
            {
                _normalize_rel_file(value)
                for value in methods_df.get("func_file", pd.Series(dtype=str)).fillna("").tolist()
                if _normalize_rel_file(value)
            }
        )
    matrix = _read_numeric_matrix(raw_path if raw_path.exists() else matrix_path, len(files))

    with matrix_path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow([""] + files)
        for index, src in enumerate(files):
            writer.writerow([src] + matrix[index].astype(int).tolist())


def _write_method_file_map(methods_df: pd.DataFrame, output_dir: Path) -> None:
    mapping = methods_df[["method_signature", "func_file"]].copy()
    mapping = mapping.rename(columns={"method_signature": "method_name"})
    mapping.to_csv(output_dir / "method_file_map.csv", index=False)


def _module_id_from_file(rel_file: Any) -> str:
    module = _normalize_rel_file(rel_file)
    if module.endswith(".py"):
        module = module[:-3]
    if module.endswith("/__init__"):
        module = module[: -len("/__init__")]
    module = module.replace("/", ".").strip(".")
    return module or _normalize_rel_file(rel_file)


def _signature_base(signature: Any) -> str:
    return str(signature or "").split("(", 1)[0].strip()


def _collect_ast_container_index(
    project_path: Path,
    rel_files: set[str],
) -> tuple[dict[str, dict[str, str]], dict[str, set[str]]]:
    container_by_function: dict[str, dict[str, str]] = {}
    class_names_by_file: dict[str, set[str]] = defaultdict(set)

    for rel_file in sorted(rel_files):
        clean_rel_file = _normalize_rel_file(rel_file)
        if not clean_rel_file:
            continue
        source_path = project_path / clean_rel_file
        if not source_path.exists() or not source_path.is_file():
            continue
        try:
            tree = ast.parse(source_path.read_text(encoding="utf-8", errors="replace"))
        except (OSError, SyntaxError, ValueError) as exc:
            print(
                f"[python-reposummary] Could not parse {clean_rel_file} for container map: {exc}",
                file=sys.stderr,
            )
            continue

        module_id = _module_id_from_file(clean_rel_file)

        def visit_body(
            body: list[ast.stmt],
            class_stack: list[str],
            function_stack: list[str],
        ) -> None:
            for node in body:
                if isinstance(node, ast.ClassDef):
                    next_class_stack = class_stack + [node.name]
                    class_id = ".".join([module_id] + next_class_stack)
                    class_names_by_file[clean_rel_file].add(class_id)
                    visit_body(node.body, next_class_stack, [])
                elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                    function_parts = class_stack + function_stack + [node.name]
                    function_qname = ".".join([module_id] + function_parts)
                    if class_stack:
                        container_id = ".".join([module_id] + class_stack)
                        container_type = "Class"
                    else:
                        container_id = module_id
                        container_type = "Module"
                    method_short_name = ".".join(function_stack + [node.name])
                    container_by_function[function_qname] = {
                        "container_id": container_id,
                        "container_type": container_type,
                        "container_file": clean_rel_file,
                        "method_short_name": method_short_name,
                    }
                    visit_body(node.body, class_stack, function_stack + [node.name])

        visit_body(list(tree.body), [], [])

    return container_by_function, class_names_by_file


def _infer_method_container(
    signature: Any,
    rel_file: Any,
    container_by_function: dict[str, dict[str, str]],
    class_names_by_file: dict[str, set[str]],
) -> dict[str, str]:
    clean_rel_file = _normalize_rel_file(rel_file) or "unknown.py"
    base = _signature_base(signature)
    direct = container_by_function.get(base)
    if direct:
        return direct

    module_id = _module_id_from_file(clean_rel_file)
    owner = base.rsplit(".", 1)[0] if "." in base else module_id
    class_candidates = class_names_by_file.get(clean_rel_file, set())
    matching_classes = [
        class_id
        for class_id in class_candidates
        if owner == class_id or owner.startswith(class_id + ".")
    ]
    if matching_classes:
        container_id = max(matching_classes, key=len)
        method_short_name = base[len(container_id) + 1:] if base.startswith(container_id + ".") else _short_signature(signature)
        return {
            "container_id": container_id,
            "container_type": "Class",
            "container_file": clean_rel_file,
            "method_short_name": method_short_name,
        }

    method_short_name = base[len(module_id) + 1:] if base.startswith(module_id + ".") else _short_signature(signature)
    return {
        "container_id": module_id,
        "container_type": "Module",
        "container_file": clean_rel_file,
        "method_short_name": method_short_name,
    }


def _write_method_container_map(methods_df: pd.DataFrame, output_dir: Path, project_path: Path) -> None:
    rel_files = {
        _normalize_rel_file(value)
        for value in methods_df.get("func_file", pd.Series(dtype=str)).fillna("").tolist()
        if _normalize_rel_file(value)
    }
    container_by_function, class_names_by_file = _collect_ast_container_index(project_path, rel_files)
    rows: list[dict[str, str]] = []
    for _, row in methods_df.fillna("").iterrows():
        method_name = str(row.get("method_signature", "")).strip()
        if not method_name:
            continue
        container = _infer_method_container(
            method_name,
            row.get("func_file", ""),
            container_by_function,
            class_names_by_file,
        )
        rows.append(
            {
                "method_name": method_name,
                "method_signature": method_name,
                "container_id": container["container_id"],
                "container_type": container["container_type"],
                "container_file": _normalize_rel_file(container["container_file"]),
                "method_short_name": container["method_short_name"],
            }
        )
    pd.DataFrame(
        rows,
        columns=[
            "method_name",
            "method_signature",
            "container_id",
            "container_type",
            "container_file",
            "method_short_name",
        ],
    ).to_csv(output_dir / "method_container_map.csv", index=False)


def _write_container_edges(methods_df: pd.DataFrame, output_dir: Path) -> None:
    map_path = output_dir / "method_container_map.csv"
    edge_path = output_dir / "container_edges.csv"
    columns = ["src_container", "dst_container", "edge_type", "weight", "evidence"]
    if not map_path.exists() or map_path.stat().st_size == 0:
        pd.DataFrame(columns=columns).to_csv(edge_path, index=False)
        return

    method_names = [
        str(value or "").strip()
        for value in methods_df.get("method_signature", pd.Series(dtype=str)).fillna("").tolist()
    ]
    if not method_names:
        pd.DataFrame(columns=columns).to_csv(edge_path, index=False)
        return

    container_df = pd.read_csv(map_path, dtype=str, keep_default_na=False)
    required = {"method_name", "container_id", "method_short_name"}
    if not required.issubset(set(container_df.columns)):
        pd.DataFrame(columns=columns).to_csv(edge_path, index=False)
        return

    containers_by_method = {
        str(row["method_name"]).strip(): {
            "container_id": str(row["container_id"]).strip(),
            "method_short_name": str(row.get("method_short_name", "")).strip(),
        }
        for _, row in container_df.iterrows()
        if str(row.get("method_name", "")).strip() and str(row.get("container_id", "")).strip()
    }
    matrix = _read_numeric_matrix(output_dir / "method_adj_matrix.csv", len(method_names))
    edge_accumulator: dict[tuple[str, str], dict[str, Any]] = {}
    src_indices, dst_indices = np.nonzero(matrix)
    for src_index, dst_index in zip(src_indices.tolist(), dst_indices.tolist()):
        src_method = method_names[src_index]
        dst_method = method_names[dst_index]
        src_info = containers_by_method.get(src_method)
        dst_info = containers_by_method.get(dst_method)
        if not src_info or not dst_info:
            continue
        src_container = src_info["container_id"]
        dst_container = dst_info["container_id"]
        if not src_container or not dst_container or src_container == dst_container:
            continue

        key = (src_container, dst_container)
        entry = edge_accumulator.setdefault(
            key,
            {
                "src_container": src_container,
                "dst_container": dst_container,
                "edge_type": "method_dependency",
                "weight": 0,
                "evidence": [],
            },
        )
        entry["weight"] += int(matrix[src_index][dst_index])
        if len(entry["evidence"]) < 10:
            src_short = src_info["method_short_name"] or _short_signature(src_method)
            dst_short = dst_info["method_short_name"] or _short_signature(dst_method)
            entry["evidence"].append(f"{src_short} -> {dst_short}")

    rows = []
    for entry in edge_accumulator.values():
        rows.append(
            {
                "src_container": entry["src_container"],
                "dst_container": entry["dst_container"],
                "edge_type": entry["edge_type"],
                "weight": entry["weight"],
                "evidence": "; ".join(entry["evidence"]),
            }
        )
    rows.sort(key=lambda row: (row["src_container"], row["dst_container"]))
    pd.DataFrame(rows, columns=columns).to_csv(edge_path, index=False)
    print(
        f"[python-reposummary] Wrote {len(rows)} container-level method dependency edges to {edge_path}",
        file=sys.stderr,
    )


def _write_method_compat_files(output_dir: Path) -> None:
    methods_csv = output_dir / "methods.csv"
    method_csv = output_dir / "method.csv"
    if methods_csv.exists():
        shutil.copyfile(methods_csv, method_csv)


def _strip_project_prefix(value: str, project_root_name: str, separator: str) -> str:
    prefix = project_root_name + separator
    if value.startswith(prefix):
        return value[len(prefix):]
    return value


def _normalize_methods_df(methods_df: pd.DataFrame, project_path: Path) -> pd.DataFrame:
    project_root_name = project_path.name
    df = methods_df.copy()
    df["func_file"] = df["func_file"].astype(str).map(
        lambda value: _normalize_project_file(value, project_path)
    )
    df["method_signature"] = df["method_signature"].astype(str).map(
        lambda value: _strip_project_prefix(value, project_root_name, ".")
    )
    return df


def _extract_python_docstring(code: str) -> str:
    if not isinstance(code, str) or not code.strip():
        return ""
    try:
        tree = ast.parse(code)
    except (SyntaxError, ValueError):
        return ""
    docs: list[str] = []
    for node in ast.walk(tree):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            doc = ast.get_docstring(node)
            if doc:
                docs.append(" ".join(doc.split()))
    return " ".join(docs[:2])


def _function_description(signature: str, file_path: str, code: str) -> str:
    doc = _extract_python_docstring(code)
    short_name = _short_signature(signature)
    if doc:
        return f"{short_name}: {doc}"
    return f"{file_path}.{short_name}"


def _load_codet5_model():
    model_path = (
        os.getenv("PYTHON_CODET5_MODEL")
        or os.getenv("CODET5_MODEL")
        or "/app/models/transformers/Salesforce/codet5-base-multi-sum"
    )
    try:
        import torch
        from transformers import RobertaTokenizer, T5ForConditionalGeneration

        tokenizer = RobertaTokenizer.from_pretrained(model_path, local_files_only=True)
        model = T5ForConditionalGeneration.from_pretrained(model_path, local_files_only=True)
        device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        model.to(device)
        model.eval()
        return tokenizer, model, device, model_path
    except Exception as exc:
        print(
            f"[python-reposummary] CodeT5 model unavailable ({model_path}): {exc}. "
            "Falling back to docstring/name function descriptions.",
            file=sys.stderr,
        )
        return None


def _apply_codet5_descriptions(functions: list[Function]) -> None:
    if not _truthy_env("REPOSUMMARY_PYTHON_USE_CODET5", True):
        return
    loaded = _load_codet5_model()
    if loaded is None:
        return

    tokenizer, model, device, model_path = loaded
    try:
        import torch
    except Exception as exc:
        print(f"[python-reposummary] PyTorch unavailable for CodeT5: {exc}", file=sys.stderr)
        return

    batch_size = _positive_int_env("REPOSUMMARY_CODET5_BATCH_SIZE", 16)
    max_input_tokens = _positive_int_env("REPOSUMMARY_CODET5_MAX_INPUT_TOKENS", 512)
    max_output_tokens = _positive_int_env("REPOSUMMARY_CODET5_MAX_OUTPUT_TOKENS", 40)
    texts: list[str] = []
    indices: list[int] = []
    for index, function in enumerate(functions):
        code = function.func_code if isinstance(function.func_code, str) else str(function.func_code or "")
        if not code.strip():
            code = function.func_name or function.func_fullName
        texts.append(code)
        indices.append(index)

    if not texts:
        return

    print(
        f"[python-reposummary] Generating {len(texts)} Python function descriptions with CodeT5: {model_path}",
        file=sys.stderr,
    )
    started_at = time.perf_counter()
    total_batches = max(1, (len(texts) + batch_size - 1) // batch_size)
    print(
        f"[python-reposummary] CodeT5 function descriptions {_progress_bar(0, len(texts))} "
        f"0/{len(texts)} (0.0%) batches=0/{total_batches}",
        file=sys.stderr,
        flush=True,
    )
    with torch.no_grad():
        for batch_number, start in enumerate(range(0, len(texts), batch_size), start=1):
            batch_texts = texts[start:start + batch_size]
            batch_indices = indices[start:start + batch_size]
            encoded = tokenizer(
                batch_texts,
                return_tensors="pt",
                truncation=True,
                padding=True,
                max_length=max_input_tokens,
            )
            input_ids = encoded["input_ids"].to(device)
            attention_mask = encoded.get("attention_mask")
            if attention_mask is not None:
                attention_mask = attention_mask.to(device)
            outputs = model.generate(
                input_ids=input_ids,
                attention_mask=attention_mask,
                max_length=max_output_tokens,
            )
            decoded = tokenizer.batch_decode(outputs, skip_special_tokens=True)
            for item_index, description in zip(batch_indices, decoded):
                description = str(description or "").strip()
                if description:
                    functions[item_index].func_desc = description
            completed = min(start + len(batch_texts), len(texts))
            percent = completed / len(texts) * 100.0 if texts else 100.0
            elapsed = time.perf_counter() - started_at
            avg_per_item = elapsed / completed if completed else 0.0
            eta = avg_per_item * (len(texts) - completed) if completed else None
            print(
                f"[python-reposummary] CodeT5 function descriptions {_progress_bar(completed, len(texts))} "
                f"{completed}/{len(texts)} ({percent:.1f}%) batches={batch_number}/{total_batches} "
                f"elapsed={_format_duration(elapsed)} eta={_format_duration(eta)}",
                file=sys.stderr,
                flush=True,
            )


def _load_python_functions(methods_df: pd.DataFrame) -> list[Function]:
    functions: list[Function] = []
    for index, row in methods_df.fillna("").iterrows():
        signature = str(row.get("method_signature", "")).strip()
        if not signature:
            continue
        file_path = _normalize_rel_file(row.get("func_file", "")) or "unknown.py"
        code = str(row.get("method_code", "") or "")
        function = Function(
            func_id=len(functions),
            func_name=_short_signature(signature),
            func_desc=_function_description(signature, file_path, code),
            func_file=file_path,
            func_flow="",
            func_notf="",
            func_code=code,
            func_fullName=signature,
            func_txt_vector=[],
        )
        functions.append(function)
    _apply_codet5_descriptions(functions)
    return functions


def _load_method_matrix(output_dir: Path, expected_size: int) -> np.ndarray:
    matrix = _read_numeric_matrix(output_dir / "method_adj_matrix.csv", expected_size)
    return matrix.astype(int)


def _python_file_name(rel_path: str) -> str:
    return Path(rel_path).stem or rel_path.replace("/", ".")


def _create_python_files(project_path: Path) -> list[File]:
    files: list[File] = []
    for path in sorted(project_path.rglob("*.py")):
        if any(part in IGNORED_ANALYSIS_DIRECTORIES for part in path.relative_to(project_path).parts):
            continue
        rel_path = _normalize_rel_file(path.relative_to(project_path))
        try:
            file_code = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            file_code = ""
        file_desc = rel_path[:-3].replace("/", ".") if rel_path.endswith(".py") else rel_path.replace("/", ".")
        files.append(
            File(
                file_id=len(files),
                file_name=_python_file_name(rel_path),
                file_path=rel_path,
                file_code=file_code,
                file_desc=file_desc,
                func_list=[],
                file_txt_vector=[],
                file_discode="",
            )
        )
    return files


def _attach_functions_to_files(files: list[File], functions: list[Function]) -> int:
    files_by_path = {_normalize_rel_file(file.file_path): file for file in files}
    attached = 0
    for function in functions:
        file = files_by_path.get(_normalize_rel_file(function.func_file))
        if file is None:
            continue
        file.func_list.append(function)
        attached += 1
    print(f"[python-reposummary] Attached {attached}/{len(functions)} Python functions to files", file=sys.stderr)
    return attached


def _load_sentence_model():
    return load_summary_embedding_model()


def _encode_to_lists(model: Any, texts: list[str]) -> list[list[float]]:
    if not texts:
        return []
    vectors = model.encode(texts)
    return [np.asarray(vector, dtype=float).tolist() for vector in vectors]


def _write_cluster_results(
    output_dir: Path,
    feature_list: list[Feature],
    summary: dict[str, Any],
) -> None:
    payload = {
        "summary": _json_safe(summary),
        "features": [
            {
                "feature_id": _json_safe(feature.feature_id),
                "cluster_id": _json_safe(feature.cluster_id),
                "feature_desc": feature.feature_desc,
                "methods": [function.func_fullName for function in feature.feature_func_list],
                "files": sorted({function.func_file for function in feature.feature_func_list}),
            }
            for feature in feature_list
        ],
    }
    (output_dir / "cluster_results.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


def _json_safe(value: Any) -> Any:
    if isinstance(value, dict):
        return {str(key): _json_safe(item) for key, item in value.items()}
    if isinstance(value, (list, tuple, set)):
        return [_json_safe(item) for item in value]
    if isinstance(value, np.ndarray):
        return _json_safe(value.tolist())
    if isinstance(value, np.integer):
        return int(value)
    if isinstance(value, np.floating):
        return float(value)
    if isinstance(value, np.bool_):
        return bool(value)
    return value


def _write_features_csv(
    output_dir: Path,
    feature_list: list[Feature],
    method_clusters: list[method_Cluster],
) -> int:
    cluster_desc_by_id = {
        method_cluster.cluster_id: method_cluster.cluster_desc
        for method_cluster in method_clusters
    }
    rows: list[dict[str, Any]] = []
    for feature in feature_list:
        module_desc = cluster_desc_by_id.get(feature.cluster_id) or f"Module {feature.cluster_id} feature group"
        for function in feature.feature_func_list:
            rows.append(
                {
                    "id": feature.feature_id,
                    "cluster_id": feature.cluster_id,
                    "module_desc": module_desc,
                    "desc": feature.feature_desc,
                    "method_name": function.func_fullName,
                    "func_file": function.func_file,
                    "flow": feature.feature_flow,
                    "notf": feature.feature_notf,
                }
            )
    pd.DataFrame(
        rows,
        columns=["id", "cluster_id", "module_desc", "desc", "method_name", "func_file", "flow", "notf"],
    ).to_csv(output_dir / "features.csv", index=False)
    return len(rows)


def _write_features(methods_df: pd.DataFrame, output_dir: Path, project_path: Path) -> dict[str, Any]:
    required = {"method_signature", "func_file", "method_code"}
    missing = required.difference(methods_df.columns)
    if missing:
        raise RuntimeError(f"methods.csv missing columns: {sorted(missing)}")

    functions = _load_python_functions(methods_df)
    if not functions:
        raise RuntimeError("No Python functions found in methods.csv")
    function_adj_matrix = _load_method_matrix(output_dir, len(functions))

    files = _create_python_files(project_path)
    attached_functions = _attach_functions_to_files(files, functions)
    if attached_functions == 0:
        raise RuntimeError("No parsed Python functions were attached to files.")
    files = [file for file in files if file.func_list]
    for index, file in enumerate(files):
        file.file_id = index

    model = _load_sentence_model()
    file_vectors = _encode_to_lists(model, [file.file_desc for file in files])
    for file, vector in zip(files, file_vectors):
        file.file_txt_vector = vector

    if len(files) == 1:
        method_clusters = [method_Cluster(0, "", files[0].func_list)]
    else:
        best_gamma, best_labels, _ = summary_clustering.find_best_resolution(
            files,
            a=0.5,
            n_points=25,
            gamma_min=0.01,
            gamma_max=0.4,
            seeds_per_gamma=8,
            use_knn=True,
            knn_k=20,
            use_threshold=False,
            threshold_tau=0.0,
            min_clusters=min(3, len(files)),
            max_clusters_ratio=0.15,
            min_cluster_size=min(3, len(files)),
            use_silhouette=False,
        )
        method_clusters = []
        for cluster in summary_clustering.save_to_file_cluster(files, best_labels):
            func_list: list[Function] = []
            for file in cluster.cluster_file_list:
                func_list.extend(file.func_list)
            method_clusters.append(method_Cluster(cluster.cluster_id, "", func_list))
        print(
            f"[python-reposummary] File clustering gamma={best_gamma}; modules={len(method_clusters)}",
            file=sys.stderr,
        )

    for method_cluster in method_clusters:
        func_vectors = _encode_to_lists(model, [function.func_desc for function in method_cluster.cluster_func_list])
        for function, vector in zip(method_cluster.cluster_func_list, func_vectors):
            function.func_txt_vector = vector

    feature_list, summary = summary_clustering.cluster_all_functions_to_features(
        method_clusters,
        function_adj_matrix=function_adj_matrix,
        weight_parameter=0.25,
        gamma_min=0.05,
        gamma_max=0.15,
        n_points=24,
        seeds_per_gamma=8,
        use_knn=True,
        knn_k=20,
        use_threshold=False,
        threshold_tau=0.0,
        min_clusters=2,
        max_clusters_ratio=0.15,
        use_silhouette=False,
        silhouette_sample_size=None,
        objective="CPM",
        consensus_tau=0.6,
        consensus_gamma=0.1,
        rng_seed=2025,
        target_total_features=None,
    )
    print(f"[python-reposummary] Total clustered features: {len(feature_list)}", file=sys.stderr)

    generate_feature_descriptions(feature_list, output_dir=output_dir)
    generate_epic_descriptions(feature_list, method_clusters, output_dir=output_dir)
    _write_cluster_results(output_dir, feature_list, summary)
    feature_rows = _write_features_csv(output_dir, feature_list, method_clusters)
    pd.DataFrame([function.__dict__ for function in functions]).to_csv(
        output_dir / "methods_with_desc.csv",
        index=False,
    )
    return {"total_features": len(feature_list), "feature_rows": feature_rows}


def repo_summary(project_root: str, output_dir: str) -> dict[str, Any]:
    project_path = Path(project_root).resolve()
    out_path = Path(output_dir).resolve()
    out_path.mkdir(parents=True, exist_ok=True)

    if not project_path.exists():
        raise RuntimeError(f"Python project root does not exist: {project_path}")

    print(f"[python-reposummary] Running ENRE for {project_path}", file=sys.stderr)
    enre_main([str(project_path), str(out_path)])

    methods_path = out_path / "methods.csv"
    if not methods_path.exists() or methods_path.stat().st_size == 0:
        raise RuntimeError(f"ENRE did not produce methods.csv at {methods_path}")

    methods_df = pd.read_csv(methods_path, dtype=str, keep_default_na=False)
    if methods_df.empty:
        raise RuntimeError(f"ENRE produced an empty methods.csv at {methods_path}")

    methods_df = _normalize_methods_df(methods_df, project_path)
    methods_df.to_csv(methods_path, index=False)
    _write_method_compat_files(out_path)
    _write_headered_file_matrix(out_path, project_path, methods_df)
    _write_method_file_map(methods_df, out_path)
    _write_method_container_map(methods_df, out_path, project_path)
    _write_container_edges(methods_df, out_path)
    feature_stats = _write_features(methods_df, out_path, project_path)

    result = {
        "project_root": str(project_path),
        "output_dir": str(out_path),
        "status": "success",
        "reason": "",
        "total_functions": int(len(methods_df)),
        **feature_stats,
    }
    print(json.dumps(result, ensure_ascii=False), file=sys.stderr)
    return result


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(
            "Usage: python -m featx_pybackend.summary.python_repository <project_root> <output_dir>",
            file=sys.stderr,
        )
        sys.exit(2)
    print(json.dumps(repo_summary(sys.argv[1], sys.argv[2]), ensure_ascii=False))
