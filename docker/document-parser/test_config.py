import tempfile
import unittest
from pathlib import Path

from config import ConfigurationError, ParserConfig


CHAT_MODEL = {
    "chat.provider": "openai",
    "chat.apiKey": "chat-key",
    "chat.baseUrl": "https://chat.example/v1",
    "chat.model": "chat-vision",
}


class ParserConfigTest(unittest.TestCase):

    def config(self, environment=None, model=None):
        return ParserConfig.fromEnvironment(environment or {}, model or CHAT_MODEL)

    def test_inherits_complete_chat_group_when_vision_provider_is_blank(self):
        config = self.config()

        self.assertEqual("chat", config.vision.source)
        self.assertEqual("openai", config.vision.provider)
        self.assertEqual("chat-key", config.vision.apiKey)
        self.assertEqual("https://chat.example/v1", config.vision.baseUrl)
        self.assertEqual("chat-vision", config.vision.model)
        self.assertEqual(60, config.visionTimeoutSeconds)

    def test_uses_independent_vision_request_timeout(self):
        config = self.config({
            "HARNESS_DOCUMENT_PARSER_TIMEOUT_SECONDS": "300",
            "HARNESS_DOCUMENT_PARSER_VISION_TIMEOUT_SECONDS": "45",
        })
        self.assertEqual(300, config.timeoutSeconds)
        self.assertEqual(45, config.visionTimeoutSeconds)

    def test_rejects_non_positive_vision_request_timeout(self):
        with self.assertRaisesRegex(ConfigurationError, "must be positive"):
            self.config({"HARNESS_DOCUMENT_PARSER_VISION_TIMEOUT_SECONDS": "0"})

    def test_uses_complete_vision_group_when_provider_is_present(self):
        model = dict(CHAT_MODEL)
        model.update({
            "vision.provider": "dashscope",
            "vision.apiKey": "vision-key",
            "vision.baseUrl": "https://dashscope.example/v1",
            "vision.model": "qwen-vl",
        })
        config = self.config(model=model)
        self.assertEqual("vision", config.vision.source)
        self.assertEqual("dashscope", config.vision.provider)
        self.assertEqual("vision-key", config.vision.apiKey)

    def test_normalizes_claude_provider_to_anthropic(self):
        model = dict(CHAT_MODEL)
        model["chat.provider"] = "claude"
        self.assertEqual("anthropic", self.config(model=model).vision.provider)

    def test_does_not_fill_missing_vision_fields_from_chat_group(self):
        model = dict(CHAT_MODEL)
        model["vision.provider"] = "openai"
        with self.assertRaisesRegex(ConfigurationError, "vision.apiKey"):
            self.config(model=model)

    def test_provider_none_explicitly_disables_vision(self):
        model = dict(CHAT_MODEL)
        model["vision.provider"] = "none"
        config = self.config(model=model)
        self.assertFalse(config.vision.enabled)
        self.assertEqual("none", config.vision.source)

    def test_rejects_ollama_instead_of_silently_falling_back(self):
        model = dict(CHAT_MODEL)
        model["chat.provider"] = "ollama"
        with self.assertRaisesRegex(ConfigurationError, "Ollama"):
            self.config(model=model)

    def test_rejects_non_positive_limits(self):
        with self.assertRaisesRegex(ConfigurationError, "must be positive"):
            self.config({"HARNESS_DOCUMENT_PARSER_MAX_CONCURRENT": "0"})

    def test_reads_standalone_model_conf_instead_of_model_environment(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "model.conf"
            path.write_text(
                "chat.provider=openai\nchat.apiKey=file-key\n"
                "chat.baseUrl=https://file.example/v1\nchat.model=file-model\n",
                encoding="utf-8")
            config = ParserConfig.fromEnvironment({
                "HARNESS_CONFIG_MODEL_FILE": str(path),
                "HARNESS_MODEL_CHAT_API_KEY": "ignored-env-key",
            })
            self.assertEqual("file-key", config.vision.apiKey)
            self.assertEqual("file-model", config.vision.model)

    def test_missing_model_conf_starts_without_vision_for_web_first_setup(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "model.conf"
            config = ParserConfig.fromEnvironment({
                "HARNESS_CONFIG_MODEL_FILE": str(path),
            })

            self.assertFalse(config.vision.enabled)
            self.assertEqual("none", config.vision.provider)
            self.assertEqual("none", config.vision.source)

    def test_empty_model_configuration_disables_vision(self):
        config = ParserConfig.fromEnvironment({}, {})

        self.assertFalse(config.vision.enabled)
        self.assertEqual("none", config.vision.provider)


if __name__ == "__main__":
    unittest.main()
