import unittest
from contextlib import contextmanager
from types import SimpleNamespace

from config import ParserConfig
from document_converter import (
    ConversionRequest,
    DocumentConversionError,
    MarkItDownDocumentConverter,
    _ObservedOcrService,
)
from llm_clients import VisionObservation


DISABLED_ENVIRONMENT = {
    "HARNESS_MODEL_CHAT_PROVIDER": "none",
}


class FakeMarkItDown:

    def __init__(self, result=None, **kwargs):
        self.result = result or SimpleNamespace(markdown="# Document", title="Doc")

    def convert_stream(self, stream, stream_info):
        return self.result


class CapturingMarkItDown(FakeMarkItDown):

    def __init__(self, captured, **kwargs):
        super().__init__(**kwargs)
        self.captured = captured

    def convert_stream(self, stream, stream_info):
        self.captured.update(stream_info)
        return super().convert_stream(stream, stream_info)


class FailedMarkItDown:

    def __init__(self, **kwargs):
        self.visionClient = kwargs["llm_client"]

    def convert_stream(self, stream, stream_info):
        try:
            self.visionClient.chat.completions.create(
                model="vision", messages=[])
        except RuntimeError:
            pass
        raise RuntimeError("direct image conversion failed")


class FakeMimeDetector:

    def detect(self, content):
        return "application/pdf"


class FailedObservedClient:

    @contextmanager
    def observe(self):
        yield VisionObservation(
            calls=1,
            failures=["RuntimeError: unavailable"],
        )


class DirectImageFailedClient:

    def __init__(self):
        from llm_clients import ObservedVisionClient

        delegate = SimpleNamespace(
            chat=SimpleNamespace(
                completions=SimpleNamespace(
                    create=lambda **kwargs: _raiseVisionFailure())))
        self._client = ObservedVisionClient(delegate)
        self.chat = self._client.chat

    def observe(self):
        return self._client.observe()

    def recordFailure(self, failure):
        self._client.recordFailure(failure)


def _raiseVisionFailure():
    raise RuntimeError("vision unavailable")


class MarkItDownDocumentConverterTest(unittest.TestCase):

    def test_passes_independent_vision_timeout_to_client_factory(self):
        environment = {
            "HARNESS_MODEL_CHAT_PROVIDER": "openai",
            "HARNESS_MODEL_CHAT_API_KEY": "key",
            "HARNESS_MODEL_CHAT_BASE_URL": "https://example.test/v1",
            "HARNESS_MODEL_CHAT_MODEL": "vision",
            "HARNESS_DOCUMENT_PARSER_TIMEOUT_SECONDS": "300",
            "HARNESS_DOCUMENT_PARSER_VISION_TIMEOUT_SECONDS": "45",
        }
        config = ParserConfig.fromEnvironment(environment)
        captured = {}

        def clientFactory(vision, timeoutSeconds, maxTokens):
            captured["timeoutSeconds"] = timeoutSeconds
            return FailedObservedClient()

        MarkItDownDocumentConverter(
            config,
            markitdownFactory=FakeMarkItDown,
            visionClientFactory=clientFactory,
            mimeDetector=FakeMimeDetector(),
            streamInfoFactory=lambda **kwargs: kwargs,
        )

        self.assertEqual(45, captured["timeoutSeconds"])

    def test_returns_stable_diagnostics_when_vision_is_disabled(self):
        config = ParserConfig.fromEnvironment(DISABLED_ENVIRONMENT)
        converter = MarkItDownDocumentConverter(
            config,
            markitdownFactory=FakeMarkItDown,
            visionClientFactory=lambda *args: None,
            mimeDetector=FakeMimeDetector(),
            streamInfoFactory=lambda **kwargs: kwargs,
        )

        result = converter.convert(ConversionRequest(
            content=b"pdf",
            fileName="document.pdf",
            mimeType="application/pdf",
        ))

        self.assertEqual("# Document", result["markdown"])
        self.assertEqual("application/pdf", result["detectedMimeType"])
        self.assertFalse(result["diagnostics"]["ocrEnabled"])
        self.assertIn("VISION_DISABLED", result["diagnostics"]["warnings"])
        self.assertEqual(3, result["diagnostics"]["inputBytes"])

    def test_detected_mime_type_overrides_generic_client_hint(self):
        config = ParserConfig.fromEnvironment(DISABLED_ENVIRONMENT)
        captured = {}
        converter = MarkItDownDocumentConverter(
            config,
            markitdownFactory=lambda **kwargs: CapturingMarkItDown(
                captured, **kwargs),
            visionClientFactory=lambda *args: None,
            mimeDetector=FakeMimeDetector(),
            streamInfoFactory=lambda **kwargs: kwargs,
        )

        converter.convert(ConversionRequest(
            content=b"pdf",
            fileName="document.pdf",
            mimeType="application/octet-stream",
        ))

        self.assertEqual("application/pdf", captured["mimetype"])

    def test_surfaces_vision_failure_even_if_converter_returns_partial_text(self):
        environment = {
            "HARNESS_MODEL_CHAT_PROVIDER": "openai",
            "HARNESS_MODEL_CHAT_API_KEY": "key",
            "HARNESS_MODEL_CHAT_BASE_URL": "https://example.test/v1",
            "HARNESS_MODEL_CHAT_MODEL": "vision",
        }
        config = ParserConfig.fromEnvironment(environment)
        converter = MarkItDownDocumentConverter(
            config,
            markitdownFactory=FakeMarkItDown,
            visionClientFactory=lambda *args: FailedObservedClient(),
            mimeDetector=FakeMimeDetector(),
            streamInfoFactory=lambda **kwargs: kwargs,
        )

        with self.assertRaises(DocumentConversionError) as raised:
            converter.convert(ConversionRequest(
                content=b"pdf",
                fileName="document.pdf",
                mimeType="application/pdf",
            ))

        self.assertEqual(502, raised.exception.status)
        self.assertEqual("VISION_REQUEST_FAILED", raised.exception.code)

    def test_empty_markdown_is_a_worker_error(self):
        config = ParserConfig.fromEnvironment(DISABLED_ENVIRONMENT)
        emptyFactory = lambda **kwargs: FakeMarkItDown(SimpleNamespace(
            markdown=" \n", title=None))
        converter = MarkItDownDocumentConverter(
            config,
            markitdownFactory=emptyFactory,
            visionClientFactory=lambda *args: None,
            mimeDetector=FakeMimeDetector(),
            streamInfoFactory=lambda **kwargs: kwargs,
        )

        with self.assertRaises(DocumentConversionError) as raised:
            converter.convert(ConversionRequest(
                content=b"pdf",
                fileName="empty.pdf",
                mimeType="application/pdf",
            ))

        self.assertEqual(422, raised.exception.status)
        self.assertEqual("EMPTY_DOCUMENT_CONTENT", raised.exception.code)

    def test_direct_image_vision_failure_keeps_vision_error_contract(self):
        environment = {
            "HARNESS_MODEL_CHAT_PROVIDER": "openai",
            "HARNESS_MODEL_CHAT_API_KEY": "key",
            "HARNESS_MODEL_CHAT_BASE_URL": "https://example.test/v1",
            "HARNESS_MODEL_CHAT_MODEL": "vision",
        }
        config = ParserConfig.fromEnvironment(environment)
        converter = MarkItDownDocumentConverter(
            config,
            markitdownFactory=FailedMarkItDown,
            visionClientFactory=lambda *args: DirectImageFailedClient(),
            mimeDetector=FakeMimeDetector(),
            streamInfoFactory=lambda **kwargs: kwargs,
        )

        with self.assertRaises(DocumentConversionError) as raised:
            converter.convert(ConversionRequest(
                content=b"image",
                fileName="image.png",
                mimeType="image/png",
            ))

        self.assertEqual(502, raised.exception.status)
        self.assertEqual("VISION_REQUEST_FAILED", raised.exception.code)

    def test_records_error_returned_inside_ocr_plugin(self):
        failures = []
        visionClient = SimpleNamespace(recordFailure=failures.append)
        delegate = SimpleNamespace(extract_text=lambda *args, **kwargs: SimpleNamespace(
            text="", error="image decode failed"))
        service = _ObservedOcrService(delegate, visionClient)

        result = service.extract_text(object())

        self.assertEqual("image decode failed", result.error)
        self.assertEqual(["OCRResultError"], failures)


if __name__ == "__main__":
    unittest.main()
