import os
import unittest
from unittest.mock import call, patch

from featx_pybackend.summary import embedding, python_repository


class SummaryEmbeddingModelTest(unittest.TestCase):
    def test_java_and_python_use_the_shared_sentence_transformer_model(self):
        shared_model = object()
        with patch.dict(
            os.environ,
            {
                "SENTENCE_TRANSFORMER_MODEL": "/models/shared-java-model",
                "PYTHON_SENTENCE_TRANSFORMER_MODEL": "/models/legacy-python-model",
            },
        ):
            with patch.object(
                embedding,
                "SentenceTransformer",
                return_value=shared_model,
            ) as sentence_transformer:
                embedding._load_summary_embedding_model.cache_clear()
                try:
                    java_model = embedding.load_summary_embedding_model()
                    python_model = python_repository._load_sentence_model()
                finally:
                    embedding._load_summary_embedding_model.cache_clear()

        self.assertIs(shared_model, java_model)
        self.assertIs(shared_model, python_model)
        self.assertEqual([call("/models/shared-java-model")], sentence_transformer.call_args_list)


if __name__ == "__main__":
    unittest.main()
