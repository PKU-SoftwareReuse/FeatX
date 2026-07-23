import tempfile
import unittest
from pathlib import Path

import pandas as pd

from featx_pybackend.summary.translation import (
    build_translation_prompt,
    parse_translation_response,
    translate_features_file,
)


class TranslateSummaryTest(unittest.TestCase):
    def test_parses_json_response_inside_markdown_fence(self):
        self.assertEqual(
            "管理员审核评论",
            parse_translation_response('```json\n{"translation":"管理员审核评论"}\n```'),
        )

    def test_prompt_requests_concise_subject_verb_object_chinese(self):
        prompt = build_translation_prompt("feature", "As an admin, I want to review comments.")
        self.assertIn("中文表述精简，只保留主谓宾结构", prompt)
        self.assertIn("不再按照原有格式表述", prompt)
        self.assertIn("准确保留原文的核心功能语义", prompt)
        self.assertNotIn("30", prompt)

    def test_parser_only_validates_the_response_shape(self):
        translation = "作为管理员，我想审核评论，以便维护社区秩序"
        self.assertEqual(
            translation,
            parse_translation_response(f'{{"translation":"{translation}"}}'),
        )

    def test_translates_unique_summaries_and_preserves_rows(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "features.csv"
            pd.DataFrame(
                [
                    {
                        "id": "1", "cluster_id": "10", "module_desc": "Comment management",
                        "desc": "Administrators review comments", "method_name": "a.A.review()",
                    },
                    {
                        "id": "1", "cluster_id": "10", "module_desc": "Comment management",
                        "desc": "Administrators review comments", "method_name": "a.A.save()",
                    },
                ]
            ).to_csv(path, index=False)
            calls = []

            def translator(kind, description):
                calls.append((kind, description))
                return "系统管理评论" if kind == "module" else "管理员审核评论"

            translated_count = translate_features_file(path, translator=translator, max_workers=1)
            result = pd.read_csv(path, dtype=str, keep_default_na=False)

            self.assertEqual(2, translated_count)
            self.assertEqual(
                [("module", "Comment management"), ("feature", "Administrators review comments")],
                calls,
            )
            self.assertEqual(["系统管理评论", "系统管理评论"], result["module_desc_cn"].tolist())
            self.assertEqual(["管理员审核评论", "管理员审核评论"], result["desc_cn"].tolist())
            self.assertEqual(["a.A.review()", "a.A.save()"], result["method_name"].tolist())

    def test_translation_failure_does_not_modify_csv(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "features.csv"
            original = (
                "id,cluster_id,module_desc,desc,method_name\n"
                "1,10,Comment management,Administrators review comments,a.A.review()\n"
            )
            path.write_text(original, encoding="utf-8")

            def translator(kind, description):
                if kind == "feature":
                    raise RuntimeError("translation failed")
                return "系统管理评论"

            with self.assertRaisesRegex(RuntimeError, "translation failed"):
                translate_features_file(path, translator=translator, max_workers=1)

            self.assertEqual(original, path.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
