import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock

import pandas as pd

from featx_pybackend.summary.backfill import (
    build_id_translation_rows,
    build_translation_rows,
    load_translation_parts,
    update_cn_fields,
    write_translation_artifact,
)


class BackfillSummaryCnTest(unittest.TestCase):
    def test_builds_module_and_feature_update_rows(self):
        modules = [{"id": 3, "cluster_id": 0, "module_desc": "Comment management"}]
        features = [{"id": 39, "feature_id": 1, "feature_desc": "Administrators review comments"}]
        translations = {
            ("module", "Comment management"): "系统管理评论",
            ("feature", "Administrators review comments"): "管理员审核评论",
        }

        rows = build_translation_rows(modules, features, translations)

        self.assertEqual("系统管理评论", rows[0]["chinese_description"])
        self.assertEqual("管理员审核评论", rows[1]["chinese_description"])

    def test_updates_only_cn_fields_and_commits(self):
        connection = Mock()
        cursor = connection.cursor.return_value
        cursor.fetchone.side_effect = [{"missing_count": 0}, {"missing_count": 0}]
        rows = [
            {"kind": "module", "database_id": 3, "chinese_description": "系统管理评论"},
            {"kind": "feature", "database_id": 39, "chinese_description": "管理员审核评论"},
        ]

        update_cn_fields(connection, "12", rows)

        update_sql = "\n".join(call.args[0] for call in cursor.execute.call_args_list[:2])
        self.assertIn("UPDATE modules SET module_desc_cn", update_sql)
        self.assertIn("SET f.feature_desc_cn", update_sql)
        self.assertNotIn("module_desc=", update_sql)
        self.assertNotIn("feature_desc=", update_sql)
        connection.commit.assert_called_once_with()
        connection.rollback.assert_not_called()
        cursor.close.assert_called_once_with()

    def test_writes_translation_audit_csv(self):
        rows = [{
            "kind": "module",
            "database_id": 3,
            "summary_id": 0,
            "english_description": "Comment management",
            "chinese_description": "系统管理评论",
        }]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "summary_translations.csv"
            write_translation_artifact(rows, path)
            result = pd.read_csv(path, dtype=str, keep_default_na=False)

        self.assertEqual("系统管理评论", result.loc[0, "chinese_description"])

    def test_loads_translation_parts_and_builds_rows_by_database_id(self):
        with tempfile.TemporaryDirectory() as directory:
            parts_dir = Path(directory)
            (parts_dir / "modules.json").write_text('{"3":"系统管理评论"}', encoding="utf-8")
            (parts_dir / "features_39_39.json").write_text('{"39":"管理员审核评论"}', encoding="utf-8")
            modules, features = load_translation_parts(parts_dir)

        rows = build_id_translation_rows(
            [{"id": 3, "cluster_id": 0, "module_desc": "Comment management"}],
            [{"id": 39, "feature_id": 1, "feature_desc": "Administrators review comments"}],
            modules,
            features,
        )
        self.assertEqual([3, 39], [row["database_id"] for row in rows])
        self.assertEqual(["系统管理评论", "管理员审核评论"], [row["chinese_description"] for row in rows])


if __name__ == "__main__":
    unittest.main()
