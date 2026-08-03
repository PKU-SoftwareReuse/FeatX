import concurrent.futures
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from featx_pybackend.summary.description_generation import (
    DescriptionGenerator,
    DescriptionKind,
    DescriptionTask,
    build_feature_task,
    generate_epic_descriptions,
    generate_feature_descriptions,
    parse_description_response,
    _token_count,
)
from featx_pybackend.summary.models import Feature, Function, MethodCluster


def _function(index: int, code: str | None = None) -> Function:
    return Function(
        func_id=index,
        func_name=f"method{index}",
        func_desc=f"method {index} description",
        func_file=f"src/module{index}.py",
        func_flow="",
        func_notf="",
        func_code=code or f"def method{index}():\n    return {index}",
        func_fullName=f"module{index}.method{index}()",
    )


def _feature(index: int, cluster_id: int | None = None) -> Feature:
    return Feature(
        cluster_id=index if cluster_id is None else cluster_id,
        feature_id=index,
        feature_desc="",
        feature_func_list=[_function(index)],
    )


class DescriptionGenerationTest(unittest.TestCase):
    def test_parser_normalizes_fenced_feature_json(self):
        description, flow, notf = parse_description_response(
            DescriptionKind.FEATURE,
            '```json\n{"description":"Manage jobs","flow":["Start", "Finish"],'
            '"notf":{"Reliability":"Retry failures"}}\n```',
        )
        self.assertEqual("Manage jobs", description)
        self.assertEqual("- Start\n- Finish", flow)
        self.assertEqual("- Reliability: Retry failures", notf)

    def test_retry_budget_is_ten_total_calls(self):
        calls = []

        def invalid_json(_task):
            calls.append(1)
            return "not-json"

        generator = DescriptionGenerator(
            call_llm=invalid_json,
            max_attempts=10,
            sleep=lambda _delay: None,
            random_value=lambda: 0.0,
            emit=lambda _line: None,
        )
        result = generator.generate(
            DescriptionTask(
                kind=DescriptionKind.FEATURE,
                subject_id="1",
                prompt="prompt",
                fallback="fallback",
            )
        )

        self.assertEqual(10, len(calls))
        self.assertEqual(10, result.attempts)
        self.assertEqual("fallback", result.description)
        self.assertEqual("fallback:generation_failed", result.source)

    def test_non_retryable_error_stops_after_first_call(self):
        calls = []

        def configuration_error(_task):
            calls.append(1)
            raise RuntimeError("unsupported model configuration")

        result = DescriptionGenerator(
            call_llm=configuration_error,
            max_attempts=10,
            sleep=lambda _delay: None,
            emit=lambda _line: None,
        ).generate(
            DescriptionTask(
                kind=DescriptionKind.EPIC,
                subject_id="epic-1",
                prompt="prompt",
                fallback="fallback epic",
            )
        )

        self.assertEqual(1, len(calls))
        self.assertEqual(1, result.attempts)
        self.assertEqual("fallback epic", result.description)

    def test_retry_delay_is_capped_after_jitter(self):
        delays = []

        with patch.dict(
            os.environ,
            {
                "REPOSUMMARY_LLM_RETRY_BASE_DELAY": "100",
                "REPOSUMMARY_LLM_RETRY_MAX_DELAY": "60",
            },
        ):
            DescriptionGenerator(
                call_llm=lambda _task: "not-json",
                max_attempts=2,
                sleep=delays.append,
                random_value=lambda: 1.0,
                emit=lambda _line: None,
            ).generate(
                DescriptionTask(
                    kind=DescriptionKind.FEATURE,
                    subject_id="1",
                    prompt="prompt",
                    fallback="fallback",
                )
            )

        self.assertEqual([60.0], delays)

    def test_feature_prompt_includes_signature_description_and_source(self):
        source = "def save_job(job):\n    database.save(job)"
        feature = Feature(1, 7, "", [_function(7, source)])
        with patch.dict(os.environ, {"REPOSUMMARY_LLM_MAX_INPUT_TOKENS": "1000"}):
            task = build_feature_task(feature)

        self.assertIn("module7.method7()", task.prompt)
        self.assertIn("method 7 description", task.prompt)
        self.assertIn(source, task.prompt)
        self.assertLessEqual(_token_count(task.prompt), 1000)

    def test_feature_generation_uses_50_workers(self):
        real_executor = concurrent.futures.ThreadPoolExecutor
        worker_counts = []

        def recording_executor(*args, **kwargs):
            worker_counts.append(kwargs.get("max_workers", args[0] if args else None))
            return real_executor(*args, **kwargs)

        generator = DescriptionGenerator(
            call_llm=lambda task: json.dumps({
                "description": f"Feature {task.subject_id}",
                "flow": "",
                "notf": "",
            }),
            emit=lambda _line: None,
        )
        features = [_feature(index) for index in range(1, 52)]
        with patch.dict(os.environ, {"REPOSUMMARY_FEATURE_LLM_MAX_WORKERS": "50"}):
            with patch(
                "featx_pybackend.summary.description_generation.concurrent.futures.ThreadPoolExecutor",
                side_effect=recording_executor,
            ):
                results = generate_feature_descriptions(
                    features,
                    generator=generator,
                    emit=lambda _line: None,
                )

        self.assertEqual([50], worker_counts)
        self.assertEqual(51, len(results))
        self.assertEqual("Feature 1", features[0].feature_desc)

    def test_epic_generation_is_ordered_and_receives_previous_epics(self):
        prompts = []

        def describe(task):
            prompts.append(task.prompt)
            return json.dumps({"description": f"Epic {task.subject_id}"})

        features = [_feature(1, cluster_id=10), _feature(2, cluster_id=20)]
        features[0].feature_desc = "First feature"
        features[1].feature_desc = "Second feature"
        clusters = [
            MethodCluster(10, "", features[0].feature_func_list),
            MethodCluster(20, "", features[1].feature_func_list),
        ]
        with tempfile.TemporaryDirectory() as directory:
            results = generate_epic_descriptions(
                features,
                clusters,
                output_dir=Path(directory),
                generator=DescriptionGenerator(call_llm=describe, emit=lambda _line: None),
                emit=lambda _line: None,
            )
            self.assertTrue((Path(directory) / "module_description_timing.csv").exists())

        self.assertEqual(["Epic 10", "Epic 20"], [result.description for result in results])
        self.assertNotIn("Epic 10", prompts[0])
        self.assertIn("Epic 10", prompts[1])
        self.assertEqual("Epic 10", clusters[0].cluster_desc)
        self.assertEqual("Epic 20", clusters[1].cluster_desc)


if __name__ == "__main__":
    unittest.main()
