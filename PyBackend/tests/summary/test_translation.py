import concurrent.futures
import os
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

import pandas as pd

from featx_pybackend.summary.translation import (
    _request_translation_once,
    build_translation_prompt,
    parse_translation_response,
    request_translation,
    translate_targets,
    translate_features_file,
)


class TranslateSummaryTest(unittest.TestCase):
    def test_parses_json_response_inside_markdown_fence(self):
        self.assertEqual(
            "管理员审核评论",
            parse_translation_response('```json\n{"translation":"管理员审核评论"}\n```'),
        )

    def test_feature_prompt_uses_five_generic_subject_verb_object_examples(self):
        prompt = build_translation_prompt("feature", "As an admin, I want to review comments.")
        self.assertIn("主体 + 动作 + 对象 + 必要限定", prompt)
        self.assertIn("管理员按ID停用账户", prompt)
        self.assertIn("API网关校验JWT并拒绝过期凭证", prompt)
        self.assertIn("结尾不添加句号", prompt)
        self.assertEqual(5, prompt.count("示例 "))
        self.assertNotIn("NBlog", prompt)

    def test_module_prompt_uses_five_generic_noun_phrase_examples(self):
        prompt = build_translation_prompt("module", "Comment management and moderation")
        self.assertIn("功能对象 + 核心能力", prompt)
        self.assertIn("用户认证与会话管理", prompt)
        self.assertIn("搜索索引与结果排序", prompt)
        self.assertEqual(5, prompt.count("示例 "))
        self.assertNotIn("NBlog", prompt)

    def test_prompt_rejects_unknown_summary_kind(self):
        with self.assertRaisesRegex(ValueError, "Unsupported translation kind"):
            build_translation_prompt("unknown", "Description")

    def test_translation_request_disables_sampling(self):
        create = MagicMock(
            return_value=SimpleNamespace(
                choices=[SimpleNamespace(message=SimpleNamespace(content='{"translation":"管理员停用账户"}'))]
            )
        )
        client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=create)))
        openai_module = SimpleNamespace(OpenAI=MagicMock(return_value=client))

        with patch.dict(
            os.environ,
            {
                "OPENAI_API_KEY": "test-key",
                "OPENAI_API_MODEL": "test-model",
                "OPENAI_BASE_URL": "https://example.invalid",
            },
        ):
            with patch.dict(sys.modules, {"openai": openai_module}):
                result = _request_translation_once("feature", "Administrators deactivate accounts")

        self.assertEqual("管理员停用账户", result)
        self.assertEqual(0, create.call_args.kwargs["temperature"])

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

    def test_defaults_to_50_translation_workers(self):
        real_executor = concurrent.futures.ThreadPoolExecutor
        worker_counts = []

        def recording_executor(*args, **kwargs):
            worker_counts.append(kwargs.get("max_workers", args[0] if args else None))
            return real_executor(*args, **kwargs)

        targets = [("feature", f"Description {index}") for index in range(51)]
        with patch.dict(os.environ, {"REPOSUMMARY_TRANSLATION_MAX_WORKERS": ""}):
            with patch(
                "featx_pybackend.summary.translation.concurrent.futures.ThreadPoolExecutor",
                side_effect=recording_executor,
            ):
                translations = translate_targets(
                    targets,
                    translator=lambda _kind, description: f"CN {description}",
                )

        self.assertEqual([50], worker_counts)
        self.assertEqual(51, len(translations))

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

    def test_translation_uses_ten_total_calls(self):
        calls = []

        def timeout(_kind, _description):
            calls.append(1)
            raise TimeoutError("provider timed out")

        with patch.dict(os.environ, {"REPOSUMMARY_LLM_MAX_ATTEMPTS": "10"}):
            with patch(
                "featx_pybackend.summary.translation._request_translation_once",
                side_effect=timeout,
            ):
                with patch("featx_pybackend.summary.translation.time.sleep"):
                    with self.assertRaises(TimeoutError):
                        request_translation("feature", "Manage jobs")

        self.assertEqual(10, len(calls))

    def test_translation_retry_delay_is_capped_after_jitter(self):
        delays = []
        with patch.dict(
            os.environ,
            {
                "REPOSUMMARY_LLM_MAX_ATTEMPTS": "2",
                "REPOSUMMARY_LLM_RETRY_BASE_DELAY": "100",
                "REPOSUMMARY_LLM_RETRY_MAX_DELAY": "60",
            },
        ):
            with patch(
                "featx_pybackend.summary.translation._request_translation_once",
                side_effect=TimeoutError("provider timed out"),
            ):
                with patch("featx_pybackend.summary.translation.random.random", return_value=1.0):
                    with patch("featx_pybackend.summary.translation.time.sleep", side_effect=delays.append):
                        with self.assertRaises(TimeoutError):
                            request_translation("feature", "Manage jobs")

        self.assertEqual([60.0], delays)


if __name__ == "__main__":
    unittest.main()
