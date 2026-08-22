"""Shared Feature and Epic description generation for Java and Python RepoSummary."""

import concurrent.futures
import csv
import json
import os
import random
import time
from dataclasses import dataclass, field
from enum import Enum
from functools import lru_cache
from pathlib import Path
from typing import Any, Callable

import tiktoken
from openai import OpenAI

from ..config.llm import openai_base_url
from .description_prompts import (
    fallback_epic_description,
    fallback_feature_description,
    render_epic_prompt,
    render_feature_prompt,
)
from .models import Feature, MethodCluster


DEFAULT_FEATURE_WORKERS = 50
DEFAULT_MAX_ATTEMPTS = 10
DEFAULT_TIMEOUT_SECONDS = 120.0
DEFAULT_MAX_INPUT_TOKENS = 32_000
DEFAULT_RETRY_BASE_DELAY = 1.0
DEFAULT_RETRY_MAX_DELAY = 60.0


class DescriptionKind(str, Enum):
    FEATURE = "feature"
    EPIC = "module"


@dataclass(frozen=True)
class DescriptionTask:
    kind: DescriptionKind
    subject_id: str
    prompt: str
    fallback: str
    metadata: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class DescriptionResult:
    kind: DescriptionKind
    subject_id: str
    description: str
    flow: str
    notf: str
    source: str
    attempts: int
    elapsed_seconds: float
    finish_reason: str = ""
    error: str = ""
    metadata: dict[str, Any] = field(default_factory=dict)


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


def _positive_float_env(name: str, default: float) -> float:
    raw = os.getenv(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        value = float(raw)
    except ValueError:
        return default
    return value if value > 0 else default


def _optional_positive_int_env(name: str) -> int | None:
    raw = os.getenv(name)
    if raw is None or raw.strip() == "":
        return None
    try:
        value = int(raw)
    except ValueError:
        return None
    return value if value > 0 else None


@lru_cache(maxsize=1)
def _token_encoding():
    return tiktoken.get_encoding("cl100k_base")


def _token_count(text: str) -> int:
    return len(_token_encoding().encode(str(text or "")))


def _truncate_tokens(text: str, max_tokens: int) -> str:
    if max_tokens <= 0:
        return ""
    tokens = _token_encoding().encode(str(text or ""))
    if len(tokens) <= max_tokens:
        return str(text or "")
    return _token_encoding().decode(tokens[:max_tokens]).rstrip() + "\n...[truncated]"


def _normalize_to_string(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value.strip()
    if isinstance(value, dict):
        lines: list[str] = []
        for key, item in value.items():
            key_text = str(key).strip()
            item_text = _normalize_to_string(item)
            if key_text and item_text:
                lines.append(f"- {key_text}: {item_text}")
            elif key_text:
                lines.append(f"- {key_text}")
            elif item_text:
                lines.append(f"- {item_text}")
        return "\n".join(lines)
    if isinstance(value, list):
        lines: list[str] = []
        for item in value:
            item_text = _normalize_to_string(item)
            for line in item_text.splitlines():
                clean_line = line.strip()
                if clean_line:
                    lines.append(clean_line if clean_line.startswith("- ") else f"- {clean_line}")
        return "\n".join(lines)
    return str(value).strip()


def clean_json_text(content: str) -> str:
    text = str(content or "").strip().replace("```json", "```")
    if text.startswith("```") and text.endswith("```"):
        text = text[3:-3].strip()
    start = text.find("{")
    if start < 0:
        return text
    depth = 0
    in_string = False
    escaped = False
    for index, character in enumerate(text[start:], start=start):
        if in_string:
            if escaped:
                escaped = False
            elif character == "\\":
                escaped = True
            elif character == '"':
                in_string = False
            continue
        if character == '"':
            in_string = True
        elif character == "{":
            depth += 1
        elif character == "}":
            depth -= 1
            if depth == 0:
                return text[start:index + 1].strip()
    return text[start:].strip()


def parse_description_response(kind: DescriptionKind, content: str) -> tuple[str, str, str]:
    if not str(content or "").strip():
        raise ValueError("empty response from LLM")
    try:
        payload = json.loads(clean_json_text(content))
    except json.JSONDecodeError as exc:
        raise ValueError(f"invalid JSON response from LLM: {exc}") from exc
    if not isinstance(payload, dict):
        raise ValueError("LLM response must be a JSON object")

    if kind is DescriptionKind.EPIC and "description" not in payload and payload:
        payload = {"description": next(iter(payload.values()))}
    description = _normalize_to_string(payload.get("description"))
    if not description or description.lower() in {"null", "none", "n/a"}:
        raise ValueError("missing description in LLM response")
    if kind is DescriptionKind.EPIC:
        return description, "", ""
    return (
        description,
        _normalize_to_string(payload.get("flow")),
        _normalize_to_string(payload.get("notf")),
    )


def is_retryable_llm_error(error: Exception) -> bool:
    status_code = getattr(error, "status_code", None)
    if status_code in {408, 409, 425, 429} or (isinstance(status_code, int) and status_code >= 500):
        return True
    error_name = error.__class__.__name__.lower()
    if any(token in error_name for token in ("timeout", "connection", "rate", "api", "server")):
        return True
    message = str(error).lower()
    return any(
        marker in message
        for marker in (
            "429", "rate limit", "ratelimit", "timeout", "timed out", "connection",
            "upstream", "temporarily", "service unavailable", "bad gateway",
            "gateway timeout", "internal server error", "server error", "empty response",
            "invalid json", "missing description",
        )
    )


@lru_cache(maxsize=1)
def _openai_client(api_key: str, base_url: str, timeout_seconds: float) -> OpenAI:
    return OpenAI(api_key=api_key, base_url=base_url, timeout=timeout_seconds)


class DescriptionGenerator:
    def __init__(
        self,
        call_llm: Callable[[DescriptionTask], str | tuple[str, str]] | None = None,
        *,
        max_attempts: int | None = None,
        sleep: Callable[[float], None] = time.sleep,
        random_value: Callable[[], float] = random.random,
        emit: Callable[[str], None] | None = None,
    ) -> None:
        self._call_llm = call_llm
        self.max_attempts = max_attempts or _positive_int_env(
            "REPOSUMMARY_LLM_MAX_ATTEMPTS",
            DEFAULT_MAX_ATTEMPTS,
        )
        self._sleep = sleep
        self._random_value = random_value
        self._emit = emit or (lambda line: print(line, flush=True))

    def _default_call(self, task: DescriptionTask) -> tuple[str, str]:
        api_key = os.getenv("OPENAI_API_KEY", "").strip()
        if not api_key:
            raise RuntimeError("OPENAI_API_KEY is not set")
        timeout_seconds = _positive_float_env(
            "REPOSUMMARY_LLM_TIMEOUT_SECONDS",
            DEFAULT_TIMEOUT_SECONDS,
        )
        client = _openai_client(api_key, openai_base_url(), timeout_seconds)
        model = os.getenv("OPENAI_API_MODEL") or os.getenv("LLM_API_MODEL") or "deepseek-v4-flash"
        request: dict[str, Any] = {
            "model": model,
            "messages": [{"role": "user", "content": task.prompt}],
            "response_format": {"type": "json_object"},
            "temperature": 0.3,
            "top_p": 0.95,
            "frequency_penalty": 0.5,
            "presence_penalty": 0.2,
        }
        max_tokens = _optional_positive_int_env("REPOSUMMARY_LLM_MAX_TOKENS")
        if max_tokens is not None:
            request["max_tokens"] = max_tokens
        response = client.chat.completions.create(**request)
        choice = response.choices[0]
        return choice.message.content or "", str(getattr(choice, "finish_reason", "") or "")

    def generate(self, task: DescriptionTask) -> DescriptionResult:
        started_at = time.perf_counter()
        if self._call_llm is None and not _truthy_env("REPOSUMMARY_GENERATE_DESCRIPTION", True):
            return self._fallback(task, 0, started_at, "description_disabled")
        if self._call_llm is None and not os.getenv("OPENAI_API_KEY", "").strip():
            return self._fallback(task, 0, started_at, "missing_openai_api_key")

        base_delay = _positive_float_env("REPOSUMMARY_LLM_RETRY_BASE_DELAY", DEFAULT_RETRY_BASE_DELAY)
        max_delay = _positive_float_env("REPOSUMMARY_LLM_RETRY_MAX_DELAY", DEFAULT_RETRY_MAX_DELAY)
        last_error: Exception | None = None
        finish_reason = ""
        for attempt in range(1, self.max_attempts + 1):
            try:
                raw_result = self._call_llm(task) if self._call_llm is not None else self._default_call(task)
                if isinstance(raw_result, tuple):
                    content, finish_reason = raw_result
                else:
                    content, finish_reason = raw_result, ""
                description, flow, notf = parse_description_response(task.kind, content)
                return DescriptionResult(
                    kind=task.kind,
                    subject_id=task.subject_id,
                    description=description,
                    flow=flow,
                    notf=notf,
                    source="llm" if attempt == 1 else f"llm:attempt_{attempt}",
                    attempts=attempt,
                    elapsed_seconds=time.perf_counter() - started_at,
                    finish_reason=finish_reason,
                    metadata=dict(task.metadata),
                )
            except Exception as exc:
                last_error = exc
                if attempt >= self.max_attempts or not is_retryable_llm_error(exc):
                    break
                delay = min(
                    max_delay,
                    base_delay * (2 ** (attempt - 1)) * (1 + self._random_value() * 0.25),
                )
                self._emit(
                    f"LLM call failed; retrying {attempt}/{self.max_attempts} "
                    f"for {task.kind.value} {task.subject_id} after {delay:.1f}s: {exc}"
                )
                self._sleep(delay)
        return self._fallback(
            task,
            self.max_attempts if last_error is not None and is_retryable_llm_error(last_error) else max(1, attempt),
            started_at,
            "generation_failed",
            last_error,
            finish_reason,
        )

    @staticmethod
    def _fallback(
        task: DescriptionTask,
        attempts: int,
        started_at: float,
        reason: str,
        error: Exception | None = None,
        finish_reason: str = "",
    ) -> DescriptionResult:
        return DescriptionResult(
            kind=task.kind,
            subject_id=task.subject_id,
            description=task.fallback,
            flow="",
            notf="",
            source=f"fallback:{reason}",
            attempts=attempts,
            elapsed_seconds=time.perf_counter() - started_at,
            finish_reason=finish_reason,
            error="" if error is None else str(error),
            metadata=dict(task.metadata),
        )


def _method_context(feature: Feature, max_input_tokens: int) -> str:
    functions = sorted(
        feature.feature_func_list,
        key=lambda function: (
            str(function.func_file or ""),
            str(function.func_fullName or ""),
            int(function.func_id or 0),
        ),
    )
    empty_prompt_tokens = _token_count(render_feature_prompt(""))
    remaining = max(1, max_input_tokens - empty_prompt_tokens)
    entries: list[str] = []
    for function in functions:
        entry = (
            f"File: {function.func_file}\n"
            f"Function: {function.func_fullName}\n"
            f"Existing description: {function.func_desc}\n"
            f"Existing flow: {function.func_flow}\n"
            f"Existing non-functional requirements: {function.func_notf}\n"
            f"Source code:\n{function.func_code}\n"
        )
        entry_tokens = _token_count(entry)
        if entry_tokens <= remaining:
            entries.append(entry)
            remaining -= entry_tokens
            continue
        if remaining > 16:
            entries.append(_truncate_tokens(entry, remaining))
        break
    if not entries:
        signatures = "\n".join(str(function.func_fullName) for function in functions)
        return _truncate_tokens(signatures or f"Feature {feature.feature_id}", remaining)
    return "\n---\n".join(entries)


def build_feature_task(feature: Feature) -> DescriptionTask:
    max_input_tokens = _positive_int_env(
        "REPOSUMMARY_LLM_MAX_INPUT_TOKENS",
        DEFAULT_MAX_INPUT_TOKENS,
    )
    files = sorted({str(function.func_file or "") for function in feature.feature_func_list if function.func_file})
    method_context = _method_context(feature, max_input_tokens)
    prompt = render_feature_prompt(method_context)
    while method_context and _token_count(prompt) > max_input_tokens:
        excess = _token_count(prompt) - max_input_tokens
        method_context = _truncate_tokens(
            method_context,
            max(1, _token_count(method_context) - excess - 4),
        )
        prompt = render_feature_prompt(method_context)
    return DescriptionTask(
        kind=DescriptionKind.FEATURE,
        subject_id=str(feature.feature_id),
        prompt=prompt,
        fallback=fallback_feature_description(feature),
        metadata={
            "feature_id": feature.feature_id,
            "cluster_id": feature.cluster_id,
            "file_path": ";".join(files),
            "method_count": len(feature.feature_func_list),
        },
    )


def build_epic_task(
    method_cluster: MethodCluster,
    related_features: list[Feature],
    previous_epics: list[str],
) -> DescriptionTask:
    return DescriptionTask(
        kind=DescriptionKind.EPIC,
        subject_id=str(method_cluster.cluster_id),
        prompt=render_epic_prompt(
            [feature.feature_desc for feature in related_features],
            previous_epics,
        ),
        fallback=fallback_epic_description(method_cluster, related_features),
        metadata={
            "cluster_id": method_cluster.cluster_id,
            "feature_count": len(related_features),
            "method_count": len(method_cluster.cluster_func_list),
        },
    )


def _progress_bar(completed: int, total: int, width: int = 24) -> str:
    filled = width if total <= 0 else min(width, int(width * completed / total))
    return "[" + "#" * filled + "-" * (width - filled) + "]"


def generate_feature_descriptions(
    features: list[Feature],
    *,
    output_dir: str | Path | None = None,
    generator: DescriptionGenerator | None = None,
    emit: Callable[[str], None] | None = None,
) -> list[DescriptionResult]:
    if not features:
        return []
    emit_line = emit or (lambda line: print(line, flush=True))
    active_generator = generator or DescriptionGenerator(emit=emit_line)
    worker_count = min(
        _positive_int_env("REPOSUMMARY_FEATURE_LLM_MAX_WORKERS", DEFAULT_FEATURE_WORKERS),
        len(features),
    )
    tasks = {feature.feature_id: build_feature_task(feature) for feature in features}
    features_by_id = {feature.feature_id: feature for feature in features}
    results: list[DescriptionResult] = []
    wall_started = time.perf_counter()
    emit_line(
        f"[reposummary-description] Generating descriptions for {len(features)} clustered features "
        f"with {worker_count} workers"
    )
    with concurrent.futures.ThreadPoolExecutor(max_workers=worker_count) as executor:
        future_to_id = {
            executor.submit(active_generator.generate, task): feature_id
            for feature_id, task in tasks.items()
        }
        for future in concurrent.futures.as_completed(future_to_id):
            feature_id = future_to_id[future]
            result = future.result()
            feature = features_by_id[feature_id]
            feature.feature_desc = result.description
            feature.feature_flow = result.flow
            feature.feature_notf = result.notf
            results.append(result)
            completed = len(results)
            percent = completed / len(features) * 100.0
            emit_line(
                f"[reposummary-description] Feature descriptions running "
                f"{_progress_bar(completed, len(features))} {completed}/{len(features)} "
                f"({percent:.1f}%) pending={len(features) - completed}"
            )
            emit_line(
                f"Feature ID: {feature.feature_id}, Description: {feature.feature_desc}, "
                f"Flow: {feature.feature_flow}, Non-functional requirements: {feature.feature_notf}"
            )
    results.sort(key=lambda result: int(result.metadata.get("feature_id", 0) or 0))
    if output_dir is not None:
        _write_feature_timing(
            Path(output_dir),
            results,
            wall_seconds=time.perf_counter() - wall_started,
            max_workers=worker_count,
            max_attempts=active_generator.max_attempts,
        )
    return results


def generate_epic_descriptions(
    features: list[Feature],
    method_clusters: list[MethodCluster],
    *,
    output_dir: str | Path | None = None,
    generator: DescriptionGenerator | None = None,
    emit: Callable[[str], None] | None = None,
) -> list[DescriptionResult]:
    emit_line = emit or (lambda line: print(line, flush=True))
    active_generator = generator or DescriptionGenerator(emit=emit_line)
    previous_epics: list[str] = []
    results: list[DescriptionResult] = []
    total = len(method_clusters)
    for index, method_cluster in enumerate(method_clusters, start=1):
        related_features = [feature for feature in features if feature.cluster_id == method_cluster.cluster_id]
        task = build_epic_task(method_cluster, related_features, previous_epics)
        emit_line(
            f"[reposummary-description] Module descriptions running "
            f"{_progress_bar(index - 1, total)} {index - 1}/{total} "
            f"cluster_id={method_cluster.cluster_id} features={len(related_features)}"
        )
        if related_features:
            result = active_generator.generate(task)
        else:
            result = DescriptionResult(
                kind=task.kind,
                subject_id=task.subject_id,
                description=task.fallback,
                flow="",
                notf="",
                source="fallback:no_related_features",
                attempts=0,
                elapsed_seconds=0.0,
                metadata=dict(task.metadata),
            )
        method_cluster.cluster_desc = result.description
        previous_epics.append(result.description)
        results.append(result)
        emit_line(f"Module ID: {method_cluster.cluster_id}, Description: {method_cluster.cluster_desc}")
        emit_line(
            f"[reposummary-description] Module descriptions running "
            f"{_progress_bar(index, total)} {index}/{total} "
            f"cluster_id={method_cluster.cluster_id} source={result.source}"
        )
    if output_dir is not None:
        _write_epic_timing(Path(output_dir), results)
    return results


def _write_feature_timing(
    output_dir: Path,
    results: list[DescriptionResult],
    *,
    wall_seconds: float,
    max_workers: int,
    max_attempts: int,
) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    path = output_dir / "feature_description_timing.csv"
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=[
                "feature_index", "feature_id", "cluster_id", "file_path", "method_count",
                "source", "attempts", "elapsed_seconds", "finish_reason", "error", "description",
            ],
        )
        writer.writeheader()
        for index, result in enumerate(results, start=1):
            writer.writerow({
                "feature_index": index,
                "feature_id": result.metadata.get("feature_id", result.subject_id),
                "cluster_id": result.metadata.get("cluster_id", ""),
                "file_path": result.metadata.get("file_path", ""),
                "method_count": result.metadata.get("method_count", 0),
                "source": result.source,
                "attempts": result.attempts,
                "elapsed_seconds": f"{result.elapsed_seconds:.6f}",
                "finish_reason": result.finish_reason,
                "error": result.error,
                "description": result.description,
            })
    elapsed = [result.elapsed_seconds for result in results]
    sorted_elapsed = sorted(elapsed)
    summary = {
        "feature_count": len(results),
        "max_workers": max_workers,
        "max_attempts": max_attempts,
        "wall_seconds": wall_seconds,
        "average_seconds_per_feature": sum(elapsed) / len(elapsed) if elapsed else 0.0,
        "p50_seconds_per_feature": sorted_elapsed[len(sorted_elapsed) // 2] if sorted_elapsed else 0.0,
        "max_seconds_per_feature": max(elapsed) if elapsed else 0.0,
        "llm_count": sum(1 for result in results if result.source.startswith("llm")),
        "fallback_count": sum(1 for result in results if not result.source.startswith("llm")),
        "retried_feature_count": sum(1 for result in results if result.attempts > 1),
    }
    (output_dir / "feature_description_timing_summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


def _write_epic_timing(output_dir: Path, results: list[DescriptionResult]) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    path = output_dir / "module_description_timing.csv"
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=[
                "module_index", "cluster_id", "feature_count", "method_count", "source",
                "attempts", "elapsed_seconds", "finish_reason", "error", "description",
            ],
        )
        writer.writeheader()
        for index, result in enumerate(results, start=1):
            writer.writerow({
                "module_index": index,
                "cluster_id": result.metadata.get("cluster_id", result.subject_id),
                "feature_count": result.metadata.get("feature_count", 0),
                "method_count": result.metadata.get("method_count", 0),
                "source": result.source,
                "attempts": result.attempts,
                "elapsed_seconds": f"{result.elapsed_seconds:.6f}",
                "finish_reason": result.finish_reason,
                "error": result.error,
                "description": result.description,
            })
