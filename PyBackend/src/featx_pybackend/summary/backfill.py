import json
import os
import tempfile
from pathlib import Path
from typing import Any

import pandas as pd

from ..paths import OUTPUT_ROOT
from .translation import translate_targets


def _connect_database():
    import mysql.connector

    return mysql.connector.connect(
        host=os.getenv("DB_HOST"),
        port=int(os.getenv("DB_PORT", "3306")),
        user=os.getenv("DB_USER"),
        password=os.getenv("DB_PASSWORD"),
        database=os.getenv("DB_NAME"),
    )


def load_summary_records(connection, repo_id: str) -> tuple[str, list[dict[str, Any]], list[dict[str, Any]]]:
    cursor = connection.cursor(dictionary=True)
    try:
        cursor.execute("SELECT repo_name FROM project_info WHERE id=%s", (repo_id,))
        project = cursor.fetchone()
        if not project:
            raise RuntimeError(f"Repository {repo_id} does not exist")

        cursor.execute(
            """
            SELECT id, cluster_id, module_desc
            FROM modules
            WHERE repo=%s
            ORDER BY id
            """,
            (repo_id,),
        )
        modules = cursor.fetchall()
        cursor.execute(
            """
            SELECT f.id, f.feature_id, f.feature_desc
            FROM features f
            JOIN modules m ON m.id=f.module
            WHERE m.repo=%s
            ORDER BY f.id
            """,
            (repo_id,),
        )
        features = cursor.fetchall()
    finally:
        cursor.close()

    if not modules or not features:
        raise RuntimeError(f"Repository {repo_id} has no existing module/feature summaries")
    return str(project["repo_name"]), modules, features


def build_translation_rows(
    modules: list[dict[str, Any]],
    features: list[dict[str, Any]],
    translations: dict[tuple[str, str], str],
) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for module in modules:
        english = str(module["module_desc"] or "").strip()
        rows.append(
            {
                "kind": "module",
                "database_id": module["id"],
                "summary_id": module["cluster_id"],
                "english_description": english,
                "chinese_description": translations[("module", english)],
            }
        )
    for feature in features:
        english = str(feature["feature_desc"] or "").strip()
        rows.append(
            {
                "kind": "feature",
                "database_id": feature["id"],
                "summary_id": feature["feature_id"],
                "english_description": english,
                "chinese_description": translations[("feature", english)],
            }
        )
    return rows


def build_id_translation_rows(
    modules: list[dict[str, Any]],
    features: list[dict[str, Any]],
    module_translations: dict[int, str],
    feature_translations: dict[int, str],
) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for module in modules:
        database_id = int(module["id"])
        rows.append(
            {
                "kind": "module",
                "database_id": database_id,
                "summary_id": module["cluster_id"],
                "english_description": str(module["module_desc"] or "").strip(),
                "chinese_description": module_translations[database_id],
            }
        )
    for feature in features:
        database_id = int(feature["id"])
        rows.append(
            {
                "kind": "feature",
                "database_id": database_id,
                "summary_id": feature["feature_id"],
                "english_description": str(feature["feature_desc"] or "").strip(),
                "chinese_description": feature_translations[database_id],
            }
        )
    return rows


def _read_translation_map(path: Path) -> dict[int, str]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise RuntimeError(f"Translation file must contain a JSON object: {path}")
    result: dict[int, str] = {}
    for raw_id, raw_translation in data.items():
        database_id = int(raw_id)
        translation = str(raw_translation or "").strip()
        if not translation:
            raise RuntimeError(f"Translation file contains an empty value for id {database_id}: {path}")
        result[database_id] = translation
    return result


def load_translation_parts(parts_dir: Path) -> tuple[dict[int, str], dict[int, str]]:
    module_translations = _read_translation_map(parts_dir / "modules.json")
    feature_translations: dict[int, str] = {}
    feature_paths = sorted(parts_dir.glob("features_*.json"))
    if not feature_paths:
        raise RuntimeError(f"No feature translation files found under {parts_dir}")
    for path in feature_paths:
        for database_id, translation in _read_translation_map(path).items():
            if database_id in feature_translations:
                raise RuntimeError(f"Duplicate feature translation id {database_id} in {path}")
            feature_translations[database_id] = translation
    return module_translations, feature_translations


def write_translation_artifact(rows: list[dict[str, Any]], output_path: Path) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        dir=output_path.parent,
        prefix=f".{output_path.name}.",
        suffix=".tmp",
    )
    os.close(descriptor)
    temporary_path = Path(temporary_name)
    try:
        with temporary_path.open("w", encoding="utf-8", newline="") as handle:
            pd.DataFrame(rows).to_csv(handle, index=False)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary_path, output_path)
    finally:
        temporary_path.unlink(missing_ok=True)


def update_cn_fields(connection, repo_id: str, rows: list[dict[str, Any]]) -> None:
    cursor = connection.cursor(dictionary=True)
    try:
        for row in rows:
            if row["kind"] == "module":
                cursor.execute(
                    "UPDATE modules SET module_desc_cn=%s WHERE id=%s AND repo=%s",
                    (row["chinese_description"], row["database_id"], repo_id),
                )
            else:
                cursor.execute(
                    """
                    UPDATE features f
                    JOIN modules m ON m.id=f.module
                    SET f.feature_desc_cn=%s
                    WHERE f.id=%s AND m.repo=%s
                    """,
                    (row["chinese_description"], row["database_id"], repo_id),
                )

        cursor.execute(
            "SELECT COUNT(*) AS missing_count FROM modules WHERE repo=%s AND (module_desc_cn IS NULL OR module_desc_cn='')",
            (repo_id,),
        )
        missing_modules = int(cursor.fetchone()["missing_count"])
        cursor.execute(
            """
            SELECT COUNT(*) AS missing_count
            FROM features f
            JOIN modules m ON m.id=f.module
            WHERE m.repo=%s AND (f.feature_desc_cn IS NULL OR f.feature_desc_cn='')
            """,
            (repo_id,),
        )
        missing_features = int(cursor.fetchone()["missing_count"])
        if missing_modules or missing_features:
            raise RuntimeError(
                f"Chinese summary backfill is incomplete: "
                f"{missing_modules} modules and {missing_features} features are still empty"
            )
        connection.commit()
    except Exception:
        connection.rollback()
        raise
    finally:
        cursor.close()


def main(repo_id: str) -> dict[str, Any]:
    read_connection = _connect_database()
    try:
        repo_name, modules, features = load_summary_records(read_connection, repo_id)
    finally:
        read_connection.close()

    targets = list(dict.fromkeys(
        [("module", str(row["module_desc"] or "").strip()) for row in modules]
        + [("feature", str(row["feature_desc"] or "").strip()) for row in features]
    ))
    if any(not description for _, description in targets):
        raise RuntimeError(f"Repository {repo_id} contains an empty English summary")

    print(
        f"[reposummary-cn-backfill] Translating {len(modules)} modules and {len(features)} features "
        f"for {repo_name} ({repo_id})",
        flush=True,
    )
    translations = translate_targets(targets)
    rows = build_translation_rows(modules, features, translations)
    artifact_path = OUTPUT_ROOT / str(repo_id) / "summary_translations.csv"
    write_translation_artifact(rows, artifact_path)

    write_connection = _connect_database()
    try:
        update_cn_fields(write_connection, repo_id, rows)
    finally:
        write_connection.close()

    result = {
        "repo_id": str(repo_id),
        "repo_name": repo_name,
        "module_count": len(modules),
        "feature_count": len(features),
        "artifact": str(artifact_path),
    }
    print(f"[reposummary-cn-backfill] Complete: {result}", flush=True)
    return result


def apply_translation_parts(repo_id: str) -> dict[str, Any]:
    read_connection = _connect_database()
    try:
        repo_name, modules, features = load_summary_records(read_connection, repo_id)
    finally:
        read_connection.close()

    parts_dir = OUTPUT_ROOT / str(repo_id) / "translation_parts"
    module_translations, feature_translations = load_translation_parts(parts_dir)
    expected_module_ids = {int(row["id"]) for row in modules}
    expected_feature_ids = {int(row["id"]) for row in features}
    if set(module_translations) != expected_module_ids:
        missing = sorted(expected_module_ids.difference(module_translations))
        extra = sorted(set(module_translations).difference(expected_module_ids))
        raise RuntimeError(f"Module translation IDs do not match the database; missing={missing}, extra={extra}")
    if set(feature_translations) != expected_feature_ids:
        missing = sorted(expected_feature_ids.difference(feature_translations))
        extra = sorted(set(feature_translations).difference(expected_feature_ids))
        raise RuntimeError(f"Feature translation IDs do not match the database; missing={missing}, extra={extra}")

    rows = build_id_translation_rows(modules, features, module_translations, feature_translations)
    artifact_path = OUTPUT_ROOT / str(repo_id) / "summary_translations.csv"
    write_translation_artifact(rows, artifact_path)

    write_connection = _connect_database()
    try:
        update_cn_fields(write_connection, repo_id, rows)
    finally:
        write_connection.close()

    result = {
        "repo_id": str(repo_id),
        "repo_name": repo_name,
        "module_count": len(modules),
        "feature_count": len(features),
        "artifact": str(artifact_path),
        "source": "translation_parts",
    }
    print(f"[reposummary-cn-backfill] Imported reviewed translations: {result}", flush=True)
    return result


if __name__ == "__main__":
    import sys

    if len(sys.argv) == 3 and sys.argv[1] == "--from-parts":
        apply_translation_parts(sys.argv[2])
    elif len(sys.argv) == 2:
        main(sys.argv[1])
    else:
        raise SystemExit(
            "Usage: python -m featx_pybackend.summary.backfill <repo_id> | "
            "python -m featx_pybackend.summary.backfill --from-parts <repo_id>"
        )
