import io
import mimetypes
import os
import time
from dataclasses import dataclass

from config import ParserConfig
from llm_clients import VisionObservation, createVisionClient


MARKITDOWN_VERSION = "0.1.6"
DEFAULT_ANTHROPIC_MAX_TOKENS = 4096


class DocumentConversionError(RuntimeError):

    def __init__(self, status: int, code: str, message: str, details=None):
        super().__init__(message)
        self.status = status
        self.code = code
        self.details = details


@dataclass(frozen=True)
class ConversionRequest:
    content: bytes
    fileName: str
    mimeType: str


class MarkItDownDocumentConverter:

    def __init__(
            self,
            config: ParserConfig,
            markitdownFactory=None,
            visionClientFactory=createVisionClient,
            mimeDetector=None,
            streamInfoFactory=None,
            ocrRegistrar=None):
        self._config = config
        self._mimeDetector = mimeDetector or _MagikaMimeDetector()
        if streamInfoFactory is None:
            from markitdown import StreamInfo

            streamInfoFactory = StreamInfo
        self._streamInfoFactory = streamInfoFactory
        self._visionClient = visionClientFactory(
            config.vision,
            config.visionTimeoutSeconds,
            DEFAULT_ANTHROPIC_MAX_TOKENS,
        )
        self._markitdown = self._createMarkItDown(
            markitdownFactory, ocrRegistrar)

    def convert(self, request: ConversionRequest):
        startedAt = time.monotonic()
        detectedMimeType = self._mimeDetector.detect(request.content)
        streamInfo = self._streamInfo(request, detectedMimeType)
        observation = VisionObservation()
        try:
            if self._visionClient is None:
                result = self._markitdown.convert_stream(
                    io.BytesIO(request.content), stream_info=streamInfo)
            else:
                with self._visionClient.observe() as observation:
                    result = self._markitdown.convert_stream(
                        io.BytesIO(request.content), stream_info=streamInfo)
        except DocumentConversionError:
            raise
        except Exception as error:
            if observation.failures:
                raise self._visionFailure(observation) from error
            status, code = _conversionFailure(error)
            raise DocumentConversionError(
                status,
                code,
                "MarkItDown could not convert the uploaded document",
                {"cause": type(error).__name__},
            ) from error

        if observation.failures:
            raise self._visionFailure(observation)

        markdown = result.markdown
        if not markdown.strip():
            raise DocumentConversionError(
                422,
                "EMPTY_DOCUMENT_CONTENT",
                "MarkItDown returned empty Markdown for the uploaded document",
                {"detectedMimeType": detectedMimeType},
            )
        warnings = []
        if not self._config.vision.enabled:
            warnings.append("VISION_DISABLED")
        elapsedMs = round((time.monotonic() - startedAt) * 1000)
        return {
            "markdown": markdown,
            "title": result.title,
            "detectedMimeType": detectedMimeType,
            "diagnostics": {
                "converter": "markitdown",
                "model": (
                    self._config.vision.model
                    if self._config.vision.enabled else None
                ),
                "ocrEnabled": self._config.vision.enabled,
                "warnings": warnings,
                "visionSource": self._config.vision.source,
                "visionCalls": observation.calls,
                "elapsedMs": elapsedMs,
                "inputBytes": len(request.content),
            },
        }

    def _visionFailure(self, observation):
        return DocumentConversionError(
            502,
            "VISION_REQUEST_FAILED",
            "One or more document vision requests failed",
            {
                "model": self._config.vision.model,
                "visionCalls": observation.calls,
                "failures": observation.failures,
            },
        )

    def _createMarkItDown(self, factory, registerOcrConverters):
        if factory is None:
            from markitdown import MarkItDown

            factory = MarkItDown
            registerOcrConverters = _registerObservedOcrConverters

        arguments = {}
        if self._visionClient is not None:
            arguments = {
                "llm_client": self._visionClient,
                "llm_model": self._config.vision.model,
            }
        markitdown = factory(enable_plugins=False, **arguments)
        if registerOcrConverters is not None:
            registerOcrConverters(
                markitdown, self._visionClient, self._config.vision.model)
        return markitdown

    def _streamInfo(self, request, detectedMimeType):
        extension = os.path.splitext(request.fileName)[1].lower() or None
        return self._streamInfoFactory(
            filename=request.fileName,
            extension=extension,
            mimetype=detectedMimeType,
        )


class _MagikaMimeDetector:

    def __init__(self):
        from magika import Magika

        self._magika = Magika()

    def detect(self, content: bytes) -> str:
        result = self._magika.identify_stream(io.BytesIO(content))
        if result.status != "ok" or result.prediction.output.label == "unknown":
            raise DocumentConversionError(
                415,
                "UNSUPPORTED_MEDIA_TYPE",
                "Could not detect the uploaded document type",
            )
        return result.prediction.output.mime_type


def normalizedMimeType(value: str, fileName: str) -> str:
    mimeType = value.split(";", 1)[0].strip().lower()
    if mimeType:
        return mimeType
    guessedType, _ = mimetypes.guess_type(fileName, strict=False)
    return guessedType or "application/octet-stream"


def _conversionFailure(error: Exception):
    errorType = type(error).__name__
    if errorType == "UnsupportedFormatException":
        return 415, "UNSUPPORTED_MEDIA_TYPE"
    if errorType == "FileConversionException":
        return 422, "DOCUMENT_CONVERSION_FAILED"
    return 500, "DOCUMENT_PARSER_ERROR"


class _ObservedOcrService:

    def __init__(self, delegate, visionClient):
        self._delegate = delegate
        self._visionClient = visionClient

    def extract_text(self, *args, **kwargs):
        result = self._delegate.extract_text(*args, **kwargs)
        if result.error:
            self._visionClient.recordFailure("OCRResultError")
        return result


def _registerObservedOcrConverters(markitdown, visionClient, model):
    from markitdown_ocr import (
        DocxConverterWithOCR,
        LLMVisionOCRService,
        PdfConverterWithOCR,
        PptxConverterWithOCR,
        XlsxConverterWithOCR,
    )

    ocrService = None
    if visionClient is not None:
        delegate = LLMVisionOCRService(client=visionClient, model=model)
        ocrService = _ObservedOcrService(delegate, visionClient)
    converterClasses = (
        PdfConverterWithOCR,
        DocxConverterWithOCR,
        PptxConverterWithOCR,
        XlsxConverterWithOCR,
    )
    for converterClass in converterClasses:
        markitdown.register_converter(
            converterClass(ocr_service=ocrService), priority=-1.0)
