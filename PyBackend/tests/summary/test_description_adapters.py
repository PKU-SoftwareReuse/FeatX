import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from featx_pybackend.summary import (
    description_generation,
    java_repository,
    python_repository,
    repository,
)


class DescriptionAdapterContractTest(unittest.TestCase):
    def test_java_and_python_import_the_same_description_entrypoints(self):
        self.assertIs(
            description_generation.generate_feature_descriptions,
            java_repository.generate_feature_descriptions,
        )
        self.assertIs(
            description_generation.generate_feature_descriptions,
            python_repository.generate_feature_descriptions,
        )
        self.assertIs(
            description_generation.generate_epic_descriptions,
            java_repository.generate_epic_descriptions,
        )
        self.assertIs(
            description_generation.generate_epic_descriptions,
            python_repository.generate_epic_descriptions,
        )

    def test_dispatcher_routes_java_and_python_as_peer_adapters(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            java_repo = root / "1"
            python_repo = root / "2"
            java_repo.mkdir()
            python_repo.mkdir()
            (java_repo / "Demo.java").write_text("class Demo {}", encoding="utf-8")
            (python_repo / "demo.py").write_text("def demo():\n    return 1\n", encoding="utf-8")

            with patch.dict(os.environ, {"LOTM_REPO_PATH": str(root)}):
                with patch.object(repository, "OUTPUT_ROOT", root / "output"):
                    with patch.object(java_repository, "repo_summary", return_value={"language": "java"}) as java:
                        self.assertEqual({"language": "java"}, repository.main("1"))
                    with patch.object(python_repository, "repo_summary", return_value={"language": "python"}) as python:
                        self.assertEqual({"language": "python"}, repository.main("2"))

            java.assert_called_once_with(
                project_root=str(java_repo),
                output_dir=str(root / "output" / "1"),
                repo_id="1",
            )
            python.assert_called_once_with(
                project_root=str(python_repo),
                output_dir=str(root / "output" / "2"),
            )


if __name__ == "__main__":
    unittest.main()
