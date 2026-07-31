import concurrent.futures
import json
import os
import random
import tempfile
import time
from pathlib import Path
from typing import Callable

import pandas as pd
from dotenv import load_dotenv

from ..config.llm import openai_base_url
from ..paths import OUTPUT_ROOT, WORKSPACE_ENV_FILE


REQUIRED_COLUMNS = {"id", "cluster_id", "module_desc", "desc", "method_name"}
TRANSLATION_COLUMNS = {
    "module": ("module_desc", "module_desc_cn"),
    "feature": ("desc", "desc_cn"),
}

load_dotenv(WORKSPACE_ENV_FILE)
load_dotenv()


def _positive_int_env(name: str, default: int) -> int:
    raw = os.getenv(name)
    if raw is None or not raw.strip():
        return default
    try:
        value = int(raw)
    except ValueError:
        return default
    return value if value > 0 else default


def _positive_float_env(name: str, default: float) -> float:
    raw = os.getenv(name)
    if raw is None or not raw.strip():
        return default
    try:
        value = float(raw)
    except ValueError:
        return default
    return value if value > 0 else default


def _clean_json_text(text: str) -> str:
    value = str(text or "").strip().replace("```json", "```")
    if value.startswith("```") and value.endswith("```"):
        value = value[3:-3].strip()
    start = value.find("{")
    end = value.rfind("}")
    return value[start:end + 1] if start >= 0 and end >= start else value


def parse_translation_response(content: str) -> str:
    if not str(content or "").strip():
        raise ValueError("empty Chinese translation response")
    data = json.loads(_clean_json_text(content))
    translation = data.get("translation") if isinstance(data, dict) else None
    if not isinstance(translation, str) or not translation.strip():
        raise ValueError("Chinese translation response is missing 'translation'")
    return translation.strip()


def build_translation_prompt(kind: str, english_description: str) -> str:
    summary_type = "Epic（模块级功能）" if kind == "module" else "Feature（具体功能）"
    return f"""
你是一名专业的软件需求翻译与精炼助手。请将下面的英文 {summary_type} 描述翻译并改写为简体中文。

要求：
1. 准确保留原文的核心功能语义，不增加原文没有的信息。
2. 中文表述精简，只保留主谓宾结构。
3. 不再按照原有格式表述；去掉“As a..., I want..., so that...”等用户故事模板，以及背景、原因、示例和实现细节。
4. 不要解释翻译过程，不要输出标题、列表或 Markdown。
5. 只返回 JSON：{{"translation": "翻译后的中文描述"}}

英文描述：
{english_description}
""".strip()


def _is_retryable(error: Exception) -> bool:
    status_code = getattr(error, "status_code", None)
    if status_code in (408, 409, 425, 429) or (isinstance(status_code, int) and status_code >= 500):
        return True
    markers = (
        "timeout", "timed out", "connection", "rate limit", "429", "upstream",
        "temporarily", "service unavailable", "bad gateway", "gateway timeout",
        "server error", "empty chinese translation", "missing 'translation'", "json",
    )
    message = f"{error.__class__.__name__}: {error}".lower()
    return any(marker in message for marker in markers)


def _request_translation_once(kind: str, english_description: str) -> str:
    api_key = os.getenv("OPENAI_API_KEY")
    if not api_key:
        raise RuntimeError("OPENAI_API_KEY is not set; Chinese summary translation cannot run")
    try:
        from openai import OpenAI
    except ImportError as error:
        raise RuntimeError("The openai package is required for Chinese summary translation") from error

    model = os.getenv("OPENAI_API_MODEL") or os.getenv("LLM_API_MODEL") or "deepseek-v4-flash"
    timeout = _positive_float_env("REPOSUMMARY_LLM_TIMEOUT_SECONDS", 20.0)
    client = OpenAI(api_key=api_key, base_url=openai_base_url(), timeout=timeout)
    response = client.chat.completions.create(
        model=model,
        messages=[{"role": "user", "content": build_translation_prompt(kind, english_description)}],
        response_format={"type": "json_object"},
    )
    return parse_translation_response(response.choices[0].message.content or "")


def request_translation(kind: str, english_description: str) -> str:
    attempts = _positive_int_env("REPOSUMMARY_LLM_RETRIES", 10)
    base_delay = _positive_float_env("REPOSUMMARY_LLM_RETRY_BASE_DELAY", 1.0)
    max_delay = _positive_float_env("REPOSUMMARY_LLM_RETRY_MAX_DELAY", 60.0)
    last_error: Exception | None = None
    for attempt in range(1, attempts + 1):
        try:
            return _request_translation_once(kind, english_description)
        except Exception as error:
            last_error = error
            if not _is_retryable(error) or attempt >= attempts:
                raise
            delay = min(max_delay, base_delay * (2 ** (attempt - 1))) * (1 + random.random() * 0.25)
            print(
                f"[reposummary-translation] LLM call failed; retrying {attempt}/{attempts} "
                f"after {delay:.1f}s: {error}",
                flush=True,
            )
            time.sleep(delay)
    raise last_error or RuntimeError("Chinese summary translation failed")


def _translation_targets(df: pd.DataFrame) -> list[tuple[str, str]]:
    targets: list[tuple[str, str]] = []
    seen: set[tuple[str, str]] = set()
    for kind, (source_column, _) in TRANSLATION_COLUMNS.items():
        for raw_value in df[source_column]:
            description = str(raw_value or "").strip()
            if not description:
                raise RuntimeError(f"features.csv contains an empty {source_column} value")
            target = (kind, description)
            if target not in seen:
                seen.add(target)
                targets.append(target)
    return targets


def translate_targets(
    targets: list[tuple[str, str]],
    translator: Callable[[str, str], str] | None = None,
    max_workers: int | None = None,
) -> dict[tuple[str, str], str]:
    if not targets:
        return {}
    translate = translator or request_translation
    worker_count = min(
        max_workers or _positive_int_env("REPOSUMMARY_TRANSLATION_MAX_WORKERS", 50),
        len(targets),
    )
    translations: dict[tuple[str, str], str] = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=worker_count) as executor:
        future_to_target = {
            executor.submit(translate, kind, description): (kind, description)
            for kind, description in targets
        }
        for completed, future in enumerate(concurrent.futures.as_completed(future_to_target), start=1):
            target = future_to_target[future]
            translated = str(future.result() or "").strip()
            if not translated:
                raise RuntimeError(f"LLM returned an empty Chinese translation for {target[0]} summary")
            translations[target] = translated
            print(
                f"[reposummary-translation] Chinese summaries {completed}/{len(targets)}",
                flush=True,
            )
    return translations


def translate_features_file(
    file_path: str | Path,
    translator: Callable[[str, str], str] | None = None,
    max_workers: int | None = None,
) -> int:
    path = Path(file_path)
    if not path.exists() or path.stat().st_size == 0:
        raise RuntimeError(f"Required RepoSummary output is empty or missing: {path}")

    df = pd.read_csv(path, dtype=str, keep_default_na=False)
    if df.empty:
        raise RuntimeError(f"Required RepoSummary output has no rows: {path}")
    missing = REQUIRED_COLUMNS.difference(df.columns)
    if missing:
        raise RuntimeError(f"features.csv is missing columns: {sorted(missing)}")

    targets = _translation_targets(df)
    translations = translate_targets(targets, translator=translator, max_workers=max_workers)

    for kind, (source_column, target_column) in TRANSLATION_COLUMNS.items():
        df[target_column] = [
            translations[(kind, str(value).strip())]
            for value in df[source_column]
        ]

    file_descriptor, temporary_name = tempfile.mkstemp(
        dir=path.parent,
        prefix=f".{path.name}.translation.",
        suffix=".tmp",
    )
    os.close(file_descriptor)
    temporary_path = Path(temporary_name)
    try:
        with temporary_path.open("w", encoding="utf-8", newline="") as handle:
            df.to_csv(handle, index=False)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary_path, path)
    finally:
        temporary_path.unlink(missing_ok=True)
    return len(targets)


def main(project_id: str) -> int:
    features_path = OUTPUT_ROOT / str(project_id) / "features.csv"
    return translate_features_file(features_path)


if __name__ == "__main__":
    import sys

    if len(sys.argv) != 2:
        raise SystemExit("Usage: python -m featx_pybackend.summary.translation <project_id>")
    main(sys.argv[1])
