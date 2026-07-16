import ast
import json
import sys
from pathlib import Path
from typing import Any


def _module_name(relative_file: str) -> str:
    module = relative_file.replace("\\", "/")
    if module.endswith(".py"):
        module = module[:-3]
    if module.endswith("/__init__"):
        module = module[: -len("/__init__")]
    return module.replace("/", ".").strip(".")


def _arg_names(args: ast.arguments) -> list[str]:
    names = [arg.arg for arg in args.posonlyargs]
    names.extend(arg.arg for arg in args.args)
    if args.vararg:
        names.append("*" + args.vararg.arg)
    names.extend(arg.arg for arg in args.kwonlyargs)
    if args.kwarg:
        names.append("**" + args.kwarg.arg)
    return names


def _signature(module: str, class_name: str | None, node: ast.FunctionDef | ast.AsyncFunctionDef) -> str:
    parts = [part for part in [module, class_name, node.name] if part]
    return f"{'.'.join(parts)}({', '.join(_arg_names(node.args))})"


def extract_file(src_root: Path, relative_file: str) -> list[dict[str, str]]:
    clean_relative = relative_file.replace("\\", "/").lstrip("./")
    path = (src_root / clean_relative).resolve()
    if not path.exists() or not path.is_file():
        return []
    if not path.is_relative_to(src_root.resolve()):
        raise RuntimeError(f"Invalid path outside source root: {relative_file}")

    source = path.read_text(encoding="utf-8")
    tree = ast.parse(source)
    module = _module_name(clean_relative)
    rows: list[dict[str, str]] = []

    for node in tree.body:
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            rows.append({"file": clean_relative, "signature": _signature(module, None, node)})
        elif isinstance(node, ast.ClassDef):
            for child in node.body:
                if isinstance(child, (ast.FunctionDef, ast.AsyncFunctionDef)):
                    rows.append({"file": clean_relative, "signature": _signature(module, node.name, child)})
    return rows


def main() -> int:
    request: dict[str, Any] = json.loads(sys.stdin.read())
    src_root = Path(request["srcRoot"]).resolve()
    files = request.get("files") or []
    result = []
    for file_name in files:
        result.extend(extract_file(src_root, str(file_name)))
    print(json.dumps({"methods": result}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
