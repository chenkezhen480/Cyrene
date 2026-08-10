import unittest

from config import ConfigurationError, ParserConfig


CHAT_ENVIRONMENT = {
    "HARNESS_MODEL_CHAT_PROVIDER": "openai",
    "HARNESS_MODEL_CHAT_API_KEY": "chat-key",
    "HARNESS_MODEL_CHAT_BASE_URL": "https://chat.example/v1",
    "HARNESS_MODEL_CHAT_MODEL": "chat-vision",
}


class ParserConfigTest(unittest.TestCase):

    def test_inherits_complete_chat_group_when_vision_provider_is_blank(self):
        config = ParserConfig.fromEnvironment(CHAT_ENVIRONMENT)

        self.assertEqual("chat", config.vision.source)
        self.assertEqual("openai", config.vision.provider)
        self.assertEqual("chat-key", config.vision.apiKey)
        self.assertEqual("https://chat.example/v1", config.vision.baseUrl)
        self.assertEqual("chat-vision", config.vision.model)
        self.assertEqual(60, config.visionTimeoutSeconds)

    def test_uses_independent_vision_request_timeout(self):
        environment = dict(CHAT_ENVIRONMENT)
        environment.update({
            "HARNESS_DOCUMENT_PARSER_TIMEOUT_SECONDS": "300",
            "HARNESS_DOCUMENT_PARSER_VISION_TIMEOUT_SECONDS": "45",
        })

        config = ParserConfig.fromEnvironment(environment)

        self.assertEqual(300, config.timeoutSeconds)
        self.assertEqual(45, config.visionTimeoutSeconds)

    def test_rejects_non_positive_vision_request_timeout(self):
        environment = dict(CHAT_ENVIRONMENT)
        environment["HARNESS_DOCUMENT_PARSER_VISION_TIMEOUT_SECONDS"] = "0"

        with self.assertRaisesRegex(ConfigurationError, "must be positive"):
            ParserConfig.fromEnvironment(environment)

    def test_uses_complete_vision_group_when_provider_is_present(self):
        environment = dict(CHAT_ENVIRONMENT)
        environment.update({
            "HARNESS_MODEL_VISION_PROVIDER": "dashscope",
            "HARNESS_MODEL_VISION_API_KEY": "vision-key",
            "HARNESS_MODEL_VISION_BASE_URL": "https://dashscope.example/v1",
            "HARNESS_MODEL_VISION_MODEL": "qwen-vl",
        })

        config = ParserConfig.fromEnvironment(environment)

        self.assertEqual("vision", config.vision.source)
        self.assertEqual("dashscope", config.vision.provider)
        self.assertEqual("vision-key", config.vision.apiKey)

    def test_normalizes_claude_provider_to_anthropic(self):
        environment = dict(CHAT_ENVIRONMENT)
        environment["HARNESS_MODEL_CHAT_PROVIDER"] = "claude"

        config = ParserConfig.fromEnvironment(environment)

        self.assertEqual("anthropic", config.vision.provider)

    def test_does_not_fill_missing_vision_fields_from_chat_group(self):
        environment = dict(CHAT_ENVIRONMENT)
        environment["HARNESS_MODEL_VISION_PROVIDER"] = "openai"

        with self.assertRaisesRegex(
                ConfigurationError, "HARNESS_MODEL_VISION_API_KEY"):
            ParserConfig.fromEnvironment(environment)

    def test_provider_none_explicitly_disables_vision(self):
        environment = dict(CHAT_ENVIRONMENT)
        environment["HARNESS_MODEL_VISION_PROVIDER"] = "none"

        config = ParserConfig.fromEnvironment(environment)

        self.assertFalse(config.vision.enabled)
        self.assertEqual("none", config.vision.source)

    def test_rejects_ollama_instead_of_silently_falling_back(self):
        environment = dict(CHAT_ENVIRONMENT)
        environment["HARNESS_MODEL_CHAT_PROVIDER"] = "ollama"

        with self.assertRaisesRegex(ConfigurationError, "Ollama"):
            ParserConfig.fromEnvironment(environment)

    def test_rejects_non_positive_limits(self):
        environment = dict(CHAT_ENVIRONMENT)
        environment["HARNESS_DOCUMENT_PARSER_MAX_CONCURRENT"] = "0"

        with self.assertRaisesRegex(ConfigurationError, "must be positive"):
            ParserConfig.fromEnvironment(environment)


if __name__ == "__main__":
    unittest.main()
