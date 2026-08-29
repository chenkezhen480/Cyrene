import time
import unittest
from dataclasses import replace

from app import (
    DocumentParserRuntime,
    _isAuthorized,
    parseMultipart,
    sanitizeFileName,
)
from config import ParserConfig
from document_converter import ConversionRequest, DocumentConversionError


DISABLED_ENVIRONMENT = {
    "HARNESS_DOCUMENT_PARSER_TIMEOUT_SECONDS": "1",
    "HARNESS_DOCUMENT_PARSER_MAX_CONCURRENT": "1",
}
DISABLED_MODEL = {"chat.provider": "none"}


class SlowConverter:

    def __init__(self, delay):
        self.delay = delay

    def convert(self, request):
        time.sleep(self.delay)
        return {"markdown": "done"}


class MultipartTest(unittest.TestCase):

    def test_parses_file_and_metadata_fields(self):
        boundary = "cyrene-boundary"
        body = (
            f"--{boundary}\r\n"
            "Content-Disposition: form-data; name=\"file\"; "
            "filename=\"original.pdf\"\r\n"
            "Content-Type: application/pdf\r\n\r\n"
        ).encode("ascii") + b"pdf-content" + (
            f"\r\n--{boundary}\r\n"
            "Content-Disposition: form-data; name=\"fileName\"\r\n\r\n"
            "renamed.pdf\r\n"
            f"--{boundary}--\r\n"
        ).encode("ascii")

        fields = parseMultipart(
            f"multipart/form-data; boundary={boundary}", body)

        self.assertEqual(b"pdf-content", fields["file"]["content"])
        self.assertEqual("original.pdf", fields["file"]["fileName"])
        self.assertEqual(b"renamed.pdf", fields["fileName"]["content"])

    def test_strips_client_path_from_file_name(self):
        self.assertEqual("report.pdf", sanitizeFileName("C:\\fake\\report.pdf"))

    def test_bearer_token_is_optional_but_exact_when_configured(self):
        self.assertTrue(_isAuthorized(None, ""))
        self.assertTrue(_isAuthorized("Bearer secret", "secret"))
        self.assertFalse(_isAuthorized("Bearer wrong", "secret"))


class DocumentParserRuntimeTest(unittest.TestCase):

    def test_rejects_work_above_concurrency_limit(self):
        config = ParserConfig.fromEnvironment(DISABLED_ENVIRONMENT, DISABLED_MODEL)
        runtime = DocumentParserRuntime(config, SlowConverter(0.1))
        request = ConversionRequest(b"x", "x.txt", "text/plain")
        runtime._capacity.acquire()
        try:
            with self.assertRaises(DocumentConversionError) as raised:
                runtime.convert(request)
            self.assertEqual(429, raised.exception.status)
        finally:
            runtime._capacity.release()
            runtime.shutdown()

    def test_returns_timeout_without_releasing_running_capacity(self):
        config = replace(
            ParserConfig.fromEnvironment(DISABLED_ENVIRONMENT, DISABLED_MODEL),
            timeoutSeconds=0.01,
        )
        runtime = DocumentParserRuntime(config, SlowConverter(0.05))
        request = ConversionRequest(b"x", "x.txt", "text/plain")
        try:
            with self.assertRaises(DocumentConversionError) as raised:
                runtime.convert(request)
            self.assertEqual(504, raised.exception.status)
            with self.assertRaises(DocumentConversionError) as busy:
                runtime.convert(request)
            self.assertEqual(429, busy.exception.status)
        finally:
            runtime.shutdown()


if __name__ == "__main__":
    unittest.main()
