from __future__ import annotations

import ast
import csv
import json
import sys
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class Definition:
    file_path: str
    base_name: str
    params: tuple[str, ...]
    start_line: int
    end_line: int
    class_base: str | None


def _normalize_path(value: Any) -> str:
    path = str(value or "").strip().replace("\\", "/")
    while path.startswith("./"):
        path = path[2:]
    return path


def _module_name(relative_file: str) -> str:
    module = _normalize_path(relative_file)
    if module.endswith(".py"):
        module = module[:-3]
    if module.endswith("/__init__"):
        module = module[: -len("/__init__")]
    return module.replace("/", ".").strip(".")


def _signature_base(signature: Any) -> str:
    return str(signature or "").strip().split("(", 1)[0].strip()


def _signature_params(signature: Any) -> tuple[str, ...]:
    text = str(signature or "")
    if "(" not in text or ")" not in text:
        return ()
    args = text.split("(", 1)[1].rsplit(")", 1)[0].strip()
    if not args:
        return ()
    return tuple(part.strip() for part in args.split(",") if part.strip())


def _arg_names(args: ast.arguments) -> tuple[str, ...]:
    names: list[str] = [arg.arg for arg in args.posonlyargs]
    names.extend(arg.arg for arg in args.args)
    if args.vararg:
        names.append("*" + args.vararg.arg)
    names.extend(arg.arg for arg in args.kwonlyargs)
    if args.kwarg:
        names.append("**" + args.kwarg.arg)
    return tuple(names)


def _node_start_line(node: ast.AST) -> int:
    lines = [getattr(node, "lineno", 1)]
    lines.extend(getattr(item, "lineno", lines[0]) for item in getattr(node, "decorator_list", []))
    return min(lines)


def _read_methods_csv(methods_csv: Path) -> tuple[dict[str, str], dict[str, list[str]]]:
    exact: dict[str, str] = {}
    by_base: dict[str, list[str]] = defaultdict(list)
    with methods_csv.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        for row in reader:
            signature = str(row.get("method_signature") or "").strip()
            file_path = _normalize_path(row.get("func_file"))
            if not signature or not file_path:
                continue
            exact[signature] = file_path
            by_base[_signature_base(signature)].append(signature)
    return exact, by_base


def _resolve_signature(
    requested: str,
    exact: dict[str, str],
    by_base: dict[str, list[str]],
) -> str | None:
    if requested in exact:
        return requested
    base = _signature_base(requested)
    candidates = by_base.get(base, [])
    if len(candidates) == 1:
        return candidates[0]
    requested_params = _signature_params(requested)
    if requested_params:
        matches = [candidate for candidate in candidates if _signature_params(candidate) == requested_params]
        if len(matches) == 1:
            return matches[0]
    return None


def _safe_source_path(src_root: Path, relative_file: str) -> Path:
    normalized = _normalize_path(relative_file)
    if normalized.startswith("/") or "../" in normalized or not normalized.endswith(".py"):
        raise ValueError(f"Invalid Python file path: {relative_file}")
    root = src_root.resolve()
    path = (root / normalized).resolve()
    if not path.is_relative_to(root):
        raise ValueError(f"Invalid Python file path outside source root: {relative_file}")
    return path


def _collect_definitions(src_root: Path, files: set[str]) -> dict[str, list[Definition]]:
    definitions: dict[str, list[Definition]] = defaultdict(list)

    def visit_class(
        node: ast.ClassDef,
        module: str,
        file_path: str,
        class_stack: list[str],
        class_bases: dict[ast.ClassDef, str],
    ) -> None:
        class_base = ".".join([module, *class_stack, node.name])
        class_bases[node] = class_base
        for child in node.body:
            if isinstance(child, (ast.FunctionDef, ast.AsyncFunctionDef)):
                base = ".".join([module, *class_stack, node.name, child.name])
                definitions[base].append(
                    Definition(
                        file_path=file_path,
                        base_name=base,
                        params=_arg_names(child.args),
                        start_line=_node_start_line(child),
                        end_line=getattr(child, "end_lineno", getattr(child, "lineno", 1)),
                        class_base=class_base,
                    )
                )
            elif isinstance(child, ast.ClassDef):
                visit_class(child, module, file_path, [*class_stack, node.name], class_bases)

    for file_path in sorted(files):
        source_path = _safe_source_path(src_root, file_path)
        if not source_path.exists():
            continue
        source = source_path.read_text(encoding="utf-8")
        tree = ast.parse(source, filename=file_path)
        module = _module_name(file_path)
        class_bases: dict[ast.ClassDef, str] = {}
        for node in tree.body:
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                base = ".".join([module, node.name])
                definitions[base].append(
                    Definition(
                        file_path=file_path,
                        base_name=base,
                        params=_arg_names(node.args),
                        start_line=_node_start_line(node),
                        end_line=getattr(node, "end_lineno", getattr(node, "lineno", 1)),
                        class_base=None,
                    )
                )
            elif isinstance(node, ast.ClassDef):
                visit_class(node, module, file_path, [], class_bases)

    return definitions


def _resolve_definition(signature: str, definitions: dict[str, list[Definition]]) -> Definition | None:
    base = _signature_base(signature)
    candidates = definitions.get(base, [])
    if len(candidates) == 1:
        return candidates[0]
    params = _signature_params(signature)
    if params:
        matches = [candidate for candidate in candidates if candidate.params == params]
        if len(matches) == 1:
            return matches[0]
    return None


def _class_pass_insertions(src_root: Path, file_path: str, deleted: list[Definition]) -> dict[int, list[str]]:
    deleted_ranges = {(item.start_line, item.end_line) for item in deleted}
    source_path = _safe_source_path(src_root, file_path)
    source = source_path.read_text(encoding="utf-8")
    tree = ast.parse(source, filename=file_path)
    insertions: dict[int, list[str]] = defaultdict(list)

    def is_docstring(stmt: ast.stmt) -> bool:
        return (
            isinstance(stmt, ast.Expr)
            and isinstance(getattr(stmt, "value", None), ast.Constant)
            and isinstance(stmt.value.value, str)
        )

    def visit_class(node: ast.ClassDef) -> None:
        meaningful: list[ast.stmt] = [stmt for stmt in node.body if not is_docstring(stmt)]
        if meaningful and all(
            isinstance(stmt, (ast.FunctionDef, ast.AsyncFunctionDef))
            and (_node_start_line(stmt), getattr(stmt, "end_lineno", getattr(stmt, "lineno", 1))) in deleted_ranges
            for stmt in meaningful
        ):
            first = min(_node_start_line(stmt) for stmt in meaningful)
            first_stmt = meaningful[0]
            indent = " " * getattr(first_stmt, "col_offset", getattr(node, "col_offset", 0) + 4)
            insertions[first].append(indent + "pass\n")
        for stmt in node.body:
            if isinstance(stmt, ast.ClassDef):
                visit_class(stmt)

    for top in tree.body:
        if isinstance(top, ast.ClassDef):
            visit_class(top)
    return insertions


def _apply_file_deletions(src_root: Path, file_path: str, deleted: list[Definition]) -> str:
    source_path = _safe_source_path(src_root, file_path)
    source = source_path.read_text(encoding="utf-8")
    lines = source.splitlines(keepends=True)
    ranges = sorted((item.start_line, item.end_line) for item in deleted)
    insertions = _class_pass_insertions(src_root, file_path, deleted)

    result: list[str] = []
    for line_no, line in enumerate(lines, start=1):
        result.extend(insertions.get(line_no, []))
        if any(start <= line_no <= end for start, end in ranges):
            continue
        result.append(line)
    new_source = "".join(result)
    ast.parse(new_source or "\n", filename=file_path)
    return new_source


def plan_delete(request: dict[str, Any]) -> dict[str, Any]:
    src_root = Path(str(request["srcRoot"])).resolve()
    methods_csv = Path(str(request["methodsCsv"])).resolve()
    feature_methods = [str(item).strip() for item in request.get("featureMethods") or [] if str(item).strip()]
    shared_methods = {str(item).strip() for item in request.get("sharedMethods") or [] if str(item).strip()}

    exact, by_base = _read_methods_csv(methods_csv)
    warnings: list[str] = []
    skipped: list[dict[str, str]] = []
    resolved_methods: list[str] = []
    method_files: dict[str, str] = {}

    for method in dict.fromkeys(feature_methods):
        if method in shared_methods:
            skipped.append({"method": method, "reason": "shared_by_other_features"})
            continue
        resolved = _resolve_signature(method, exact, by_base)
        if resolved is None:
            skipped.append({"method": method, "reason": "not_found_in_methods_csv"})
            continue
        resolved_methods.append(resolved)
        method_files[resolved] = exact[resolved]

    files = set(method_files.values())
    definitions = _collect_definitions(src_root, files)
    deleted_by_file: dict[str, list[Definition]] = defaultdict(list)
    deleted_methods: list[str] = []

    for method in resolved_methods:
        definition = _resolve_definition(method, definitions)
        if definition is None:
            skipped.append({"method": method, "reason": "definition_not_found_or_ambiguous"})
            continue
        deleted_by_file[definition.file_path].append(definition)
        deleted_methods.append(method)

    modification_map: dict[str, str] = {}
    affected_files: list[str] = []
    for file_path, definitions_for_file in sorted(deleted_by_file.items()):
        unique = list({(item.start_line, item.end_line): item for item in definitions_for_file}.values())
        if not unique:
            continue
        new_source = _apply_file_deletions(src_root, file_path, unique)
        original = _safe_source_path(src_root, file_path).read_text(encoding="utf-8")
        if new_source != original:
            modification_map[file_path] = new_source
            affected_files.append(file_path)
        else:
            warnings.append(f"No textual change produced for {file_path}")

    return {
        "affectedFiles": affected_files,
        "deletedMethods": deleted_methods,
        "skippedMethods": skipped,
        "modificationMap": modification_map,
        "warnings": warnings,
    }


def main() -> int:
    request = json.loads(sys.stdin.read())
    result = plan_delete(request)
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
