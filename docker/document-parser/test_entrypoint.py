import unittest
from unittest.mock import patch

from entrypoint import main, sanitizedEnvironment


class EntrypointEnvironmentTest(unittest.TestCase):

    def test_removes_unrelated_application_secrets(self):
        environment = {
            "HARNESS_DOCUMENT_PARSER_TOKEN": "parser-token",
            "HARNESS_CONFIG_MODEL_FILE": "/app/data/model.conf",
            "HARNESS_MODEL_CHAT_API_KEY": "chat-key",
            "HARNESS_MODEL_VISION_API_KEY": "vision-key",
            "HARNESS_DB_PASS": "database-secret",
            "HARNESS_AUDIT_DB_PASS": "audit-secret",
            "HARNESS_MEMORY_REDIS_URL": "redis://secret",
            "HARNESS_GRAPH_NEO4J_PASSWORD": "neo4j-secret",
            "HARNESS_MODEL_EMBEDDING_API_KEY": "embedding-key",
        }

        result = sanitizedEnvironment(environment)

        self.assertEqual("parser-token", result["HARNESS_DOCUMENT_PARSER_TOKEN"])
        self.assertEqual(
            "/app/data/model.conf", result["HARNESS_CONFIG_MODEL_FILE"])
        self.assertNotIn("HARNESS_MODEL_CHAT_API_KEY", result)
        self.assertNotIn("HARNESS_MODEL_VISION_API_KEY", result)
        self.assertNotIn("HARNESS_DB_PASS", result)
        self.assertNotIn("HARNESS_AUDIT_DB_PASS", result)
        self.assertNotIn("HARNESS_MEMORY_REDIS_URL", result)
        self.assertNotIn("HARNESS_GRAPH_NEO4J_PASSWORD", result)
        self.assertNotIn("HARNESS_MODEL_EMBEDDING_API_KEY", result)

    def test_keeps_parser_model_proxy_ca_and_locale_configuration(self):
        environment = {
            "PATH": "/usr/local/bin:/usr/bin",
            "HOME": "/home/parser",
            "LANG": "zh_CN.UTF-8",
            "LC_TIME": "zh_CN.UTF-8",
            "HTTPS_PROXY": "http://proxy.internal:8080",
            "NO_PROXY": "document-parser,localhost",
            "REQUESTS_CA_BUNDLE": "/certs/internal.pem",
            "HARNESS_DOCUMENT_PARSER_VISION_TIMEOUT_SECONDS": "60",
            "HARNESS_CONFIG_MODEL_FILE": "/app/data/model.conf",
            "HARNESS_MODEL_CHAT_PROVIDER": "openai",
            "HARNESS_MODEL_CHAT_BASE_URL": "https://llm.internal/v1",
            "PYTHONPATH": "/untrusted/modules",
        }

        result = sanitizedEnvironment(environment)

        self.assertEqual(environment["PATH"], result["PATH"])
        self.assertEqual(environment["LANG"], result["LANG"])
        self.assertEqual(environment["LC_TIME"], result["LC_TIME"])
        self.assertEqual(environment["HTTPS_PROXY"], result["HTTPS_PROXY"])
        self.assertEqual(
            environment["REQUESTS_CA_BUNDLE"], result["REQUESTS_CA_BUNDLE"])
        self.assertEqual(
            "60", result["HARNESS_DOCUMENT_PARSER_VISION_TIMEOUT_SECONDS"])
        self.assertEqual(
            "/app/data/model.conf", result["HARNESS_CONFIG_MODEL_FILE"])
        self.assertNotIn("HARNESS_MODEL_CHAT_PROVIDER", result)
        self.assertNotIn("PYTHONPATH", result)

    def test_executes_app_with_only_sanitized_environment(self):
        environment = {
            "PATH": "/usr/local/bin:/usr/bin",
            "HARNESS_DOCUMENT_PARSER_PORT": "8082",
            "HARNESS_CONFIG_MODEL_FILE": "/app/data/model.conf",
            "HARNESS_DB_PASS": "database-secret",
        }

        with patch("entrypoint.os.environ", environment), patch(
                "entrypoint.os.execve") as execve:
            main()

        executable, arguments, childEnvironment = execve.call_args.args
        self.assertEqual(executable, arguments[0])
        self.assertTrue(arguments[1].endswith("app.py"))
        self.assertEqual(
            "/app/data/model.conf", childEnvironment["HARNESS_CONFIG_MODEL_FILE"])
        self.assertNotIn("HARNESS_MODEL_CHAT_API_KEY", childEnvironment)
        self.assertNotIn("HARNESS_DB_PASS", childEnvironment)


if __name__ == "__main__":
    unittest.main()
