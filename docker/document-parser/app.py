import hmac
import json
from concurrent.futures import ThreadPoolExecutor, TimeoutError
from email.parser import BytesParser
from email.policy import default
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from config import ConfigurationError, ParserConfig
from document_converter import (
    ConversionRequest,
    DocumentConversionError,
    MARKITDOWN_VERSION,
    MarkItDownDocumentConverter,
    normalizedMimeType,
)


MULTIPART_OVERHEAD_BYTES = 1024 * 1024


class DocumentParserRuntime:

    def __init__(self, config: ParserConfig, converter=None):
        self.config = config
        self.converter = converter or MarkItDownDocumentConverter(config)
        self._executor = ThreadPoolExecutor(
            max_workers=config.maxConcurrent,
            thread_name_prefix="document-parser",
        )
        import threading
        self._capacity = threading.BoundedSemaphore(config.maxConcurrent)

    def convert(self, request: ConversionRequest):
        if not self._capacity.acquire(blocking=False):
            raise DocumentConversionError(
                429,
                "DOCUMENT_PARSER_BUSY",
                "Document parser concurrency limit reached",
            )
        future = self._executor.submit(self._convertAndRelease, request)
        try:
            return future.result(timeout=self.config.timeoutSeconds)
        except TimeoutError as error:
            future.cancel()
            raise DocumentConversionError(
                504,
                "DOCUMENT_CONVERSION_TIMEOUT",
                "Document conversion exceeded the configured timeout",
                {"timeoutSeconds": self.config.timeoutSeconds},
            ) from error

    def health(self):
        return {
            "status": "ok",
            "converter": "markitdown",
            "version": MARKITDOWN_VERSION,
            "ocrEnabled": self.config.vision.enabled,
            "model": self.config.vision.model if self.config.vision.enabled else None,
        }

    def shutdown(self):
        self._executor.shutdown(wait=True, cancel_futures=True)

    def _convertAndRelease(self, request):
        try:
            return self.converter.convert(request)
        finally:
            self._capacity.release()


class DocumentParserHandler(BaseHTTPRequestHandler):

    runtime = None

    def do_GET(self):
        if self.path == "/health":
            self._writeJson(200, self.runtime.health())
            return
        self._writeError(404, "NOT_FOUND", "Not found")

    def do_POST(self):
        if self.path != "/convert":
            self._writeError(404, "NOT_FOUND", "Not found")
            return
        if not _isAuthorized(
                self.headers.get("Authorization"), self.runtime.config.token):
            self._writeError(401, "UNAUTHORIZED", "Unauthorized")
            return
        try:
            self.connection.settimeout(self.runtime.config.timeoutSeconds)
            request = self._readConversionRequest()
            self._writeJson(200, self.runtime.convert(request))
        except DocumentConversionError as error:
            self._writeError(
                error.status, error.code, str(error), error.details)
        except TimeoutError:
            self._writeError(
                408,
                "DOCUMENT_UPLOAD_TIMEOUT",
                "Timed out while receiving the uploaded document",
            )
        except Exception as error:
            self._writeError(
                500,
                "DOCUMENT_PARSER_ERROR",
                "Unexpected document parser error",
                {"cause": type(error).__name__},
            )

    def log_message(self, format, *args):
        return

    def _readConversionRequest(self):
        contentLength = self.headers.get("Content-Length")
        if contentLength is None:
            raise DocumentConversionError(
                411, "CONTENT_LENGTH_REQUIRED", "Content-Length is required")
        try:
            bodyLength = int(contentLength)
        except ValueError as error:
            raise DocumentConversionError(
                400, "INVALID_CONTENT_LENGTH", "Invalid Content-Length") from error
        maxRequestBytes = (
            self.runtime.config.maxFileBytes + MULTIPART_OVERHEAD_BYTES)
        if bodyLength <= 0:
            raise DocumentConversionError(
                400, "EMPTY_REQUEST", "Request body is empty")
        if bodyLength > maxRequestBytes:
            raise DocumentConversionError(
                413,
                "DOCUMENT_TOO_LARGE",
                "Multipart request exceeds the configured size limit",
                {"maxFileBytes": self.runtime.config.maxFileBytes},
            )
        contentType = self.headers.get("Content-Type", "")
        body = self.rfile.read(bodyLength)
        if len(body) != bodyLength:
            raise DocumentConversionError(
                400,
                "INCOMPLETE_REQUEST_BODY",
                "Request body ended before Content-Length bytes were received",
            )
        fields = parseMultipart(contentType, body)
        filePart = fields.get("file")
        if filePart is None or not filePart["content"]:
            raise DocumentConversionError(
                400, "FILE_REQUIRED", "Multipart field 'file' is required")
        content = filePart["content"]
        if len(content) > self.runtime.config.maxFileBytes:
            raise DocumentConversionError(
                413,
                "DOCUMENT_TOO_LARGE",
                "Uploaded document exceeds the configured size limit",
                {"maxFileBytes": self.runtime.config.maxFileBytes},
            )
        requestedFileName = _textField(fields, "fileName")
        fileName = sanitizeFileName(requestedFileName or filePart["fileName"])
        requestedMimeType = _textField(fields, "mimeType")
        mimeType = normalizedMimeType(
            requestedMimeType or filePart["mimeType"], fileName)
        return ConversionRequest(content, fileName, mimeType)

    def _writeError(self, status, code, message, details=None):
        error = {"code": code, "message": message}
        if details is not None:
            error["details"] = details
        self._writeJson(status, {"error": error})

    def _writeJson(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def parseMultipart(contentType: str, body: bytes):
    if not contentType.lower().startswith("multipart/form-data"):
        raise DocumentConversionError(
            415,
            "MULTIPART_REQUIRED",
            "Content-Type must be multipart/form-data",
        )
    message = BytesParser(policy=default).parsebytes(
        b"Content-Type: " + contentType.encode("latin-1")
        + b"\r\nMIME-Version: 1.0\r\n\r\n" + body)
    if not message.is_multipart():
        raise DocumentConversionError(
            400, "INVALID_MULTIPART", "Invalid multipart request body")
    fields = {}
    for part in message.iter_parts():
        fieldName = part.get_param("name", header="content-disposition")
        if not fieldName:
            continue
        if fieldName in fields:
            raise DocumentConversionError(
                400,
                "DUPLICATE_MULTIPART_FIELD",
                f"Multipart field '{fieldName}' must appear once",
            )
        fields[fieldName] = {
            "content": part.get_payload(decode=True) or b"",
            "fileName": part.get_filename() or "",
            "mimeType": part.get_content_type(),
            "charset": part.get_content_charset() or "utf-8",
        }
    return fields


def sanitizeFileName(value: str) -> str:
    fileName = value.replace("\\", "/").rsplit("/", 1)[-1].strip()
    if not fileName or fileName in {".", ".."}:
        raise DocumentConversionError(
            400, "INVALID_FILE_NAME", "A valid file name is required")
    if len(fileName) > 255 or "\x00" in fileName:
        raise DocumentConversionError(
            400, "INVALID_FILE_NAME", "File name is invalid")
    return fileName


def _textField(fields, name):
    field = fields.get(name)
    if field is None:
        return ""
    try:
        return field["content"].decode(field["charset"]).strip()
    except (LookupError, UnicodeDecodeError) as error:
        raise DocumentConversionError(
            400,
            "INVALID_MULTIPART_FIELD",
            f"Multipart field '{name}' is not valid text",
        ) from error


def _isAuthorized(authorization: str | None, token: str) -> bool:
    if not token:
        return True
    expected = f"Bearer {token}"
    return authorization is not None and hmac.compare_digest(
        authorization, expected)


def main():
    try:
        config = ParserConfig.fromEnvironment()
        runtime = DocumentParserRuntime(config)
    except ConfigurationError as error:
        raise SystemExit(f"Document parser configuration error: {error}") from error
    DocumentParserHandler.runtime = runtime
    server = ThreadingHTTPServer((config.host, config.port), DocumentParserHandler)
    try:
        server.serve_forever()
    finally:
        server.server_close()
        runtime.shutdown()


if __name__ == "__main__":
    main()
