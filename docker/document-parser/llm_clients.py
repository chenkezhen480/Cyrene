import base64
import re
import threading
from contextlib import contextmanager
from dataclasses import dataclass, field
from types import SimpleNamespace

from config import ConfigurationError, VisionConfig


@dataclass
class VisionObservation:
    calls: int = 0
    failures: list[str] = field(default_factory=list)


class ObservedVisionClient:

    def __init__(self, delegate):
        self._delegate = delegate
        self._local = threading.local()
        self.chat = SimpleNamespace(
            completions=SimpleNamespace(create=self._create))

    @contextmanager
    def observe(self):
        if getattr(self._local, "observation", None) is not None:
            raise RuntimeError("Nested vision observation is not supported")
        observation = VisionObservation()
        self._local.observation = observation
        try:
            yield observation
        finally:
            self._local.observation = None

    def _create(self, **kwargs):
        observation = getattr(self._local, "observation", None)
        if observation is not None:
            observation.calls += 1
        try:
            response = self._delegate.chat.completions.create(**kwargs)
            content = response.choices[0].message.content
            if not isinstance(content, str) or not content.strip():
                raise RuntimeError("Vision model returned empty content")
            return response
        except Exception as error:
            self.recordFailure(_safeFailure(error))
            raise

    def recordFailure(self, failure: str):
        observation = getattr(self._local, "observation", None)
        if observation is not None and failure not in observation.failures:
            observation.failures.append(failure)


class AnthropicOpenAiAdapter:

    def __init__(self, client, maxTokens: int):
        self._client = client
        self._maxTokens = maxTokens
        self.chat = SimpleNamespace(
            completions=SimpleNamespace(create=self._create))

    def _create(self, *, model, messages, **kwargs):
        anthropicMessages = [
            {
                "role": message["role"],
                "content": _anthropicContent(message.get("content")),
            }
            for message in messages
            if message.get("role") != "system"
        ]
        systemParts = [
            str(message.get("content", ""))
            for message in messages
            if message.get("role") == "system"
        ]
        request = {
            "model": model,
            "max_tokens": self._maxTokens,
            "messages": anthropicMessages,
        }
        if systemParts:
            request["system"] = "\n\n".join(systemParts)
        response = self._client.messages.create(**request)
        text = "\n".join(
            block.text for block in response.content
            if getattr(block, "type", None) == "text"
        )
        return SimpleNamespace(
            choices=[SimpleNamespace(message=SimpleNamespace(content=text))])


def createVisionClient(
        vision: VisionConfig, visionTimeoutSeconds: int, maxTokens: int):
    if not vision.enabled:
        return None
    if vision.provider in {"openai", "dashscope"}:
        from openai import OpenAI

        delegate = OpenAI(
            api_key=vision.apiKey,
            base_url=vision.baseUrl,
            timeout=float(visionTimeoutSeconds),
        )
    elif vision.provider == "anthropic":
        from anthropic import Anthropic

        anthropicClient = Anthropic(
            api_key=vision.apiKey,
            base_url=vision.baseUrl,
            timeout=float(visionTimeoutSeconds),
        )
        delegate = AnthropicOpenAiAdapter(anthropicClient, maxTokens)
    else:
        raise ConfigurationError(
            f"Unsupported document parser vision provider: {vision.provider}")
    return ObservedVisionClient(delegate)


def _anthropicContent(content):
    if isinstance(content, str):
        return [{"type": "text", "text": content}]
    if not isinstance(content, list):
        raise ValueError("Unsupported OpenAI message content")

    converted = []
    for block in content:
        blockType = block.get("type")
        if blockType == "text":
            converted.append({"type": "text", "text": block.get("text", "")})
        elif blockType == "image_url":
            imageUrl = block.get("image_url", {}).get("url", "")
            mediaType, data = _parseDataUri(imageUrl)
            converted.append({
                "type": "image",
                "source": {
                    "type": "base64",
                    "media_type": mediaType,
                    "data": data,
                },
            })
        else:
            raise ValueError(f"Unsupported OpenAI content block: {blockType}")
    return converted


def _parseDataUri(value: str):
    match = re.fullmatch(
        r"data:([a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+);base64,(.+)",
        value,
        flags=re.DOTALL,
    )
    if match is None:
        raise ValueError("Anthropic adapter only accepts base64 image data URIs")
    try:
        base64.b64decode(match.group(2), validate=True)
    except ValueError as error:
        raise ValueError("Invalid base64 image data URI") from error
    return match.group(1), match.group(2)


def _safeFailure(error: Exception) -> str:
    statusCode = getattr(error, "status_code", None)
    if isinstance(statusCode, int):
        return f"{type(error).__name__}: HTTP {statusCode}"
    return type(error).__name__
