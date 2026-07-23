import unittest

from featx_pybackend.config.llm import normalize_openai_base_url


class LlmConfigTest(unittest.TestCase):
    def test_adds_v1_to_unversioned_base_url(self):
        self.assertEqual(
            "https://api.example.com/v1",
            normalize_openai_base_url("https://api.example.com"),
        )

    def test_does_not_duplicate_existing_v1(self):
        self.assertEqual(
            "https://api.example.com/v1",
            normalize_openai_base_url("https://api.example.com/v1/"),
        )

    def test_rejects_chat_completion_endpoint(self):
        with self.assertRaises(ValueError):
            normalize_openai_base_url("https://api.example.com/v1/chat/completions")


if __name__ == "__main__":
    unittest.main()
