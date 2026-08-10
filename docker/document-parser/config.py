import os
from dataclasses import dataclass
from typing import Mapping


class ConfigurationError(RuntimeError):
    pass


SUPPORTED_PROVIDERS = {"openai", "dashscope", "anthropic"}


@dataclass(frozen=True)
class VisionConfig:
    provider: str
    apiKey: str
    baseUrl: str
    model: str
    source: str
    enabled: bool


@dataclass(frozen=True)
class ParserConfig:
    host: str
    port: int
    token: str
    maxFileBytes: int
    timeoutSeconds: int
    visionTimeoutSeconds: int
    maxConcurrent: int
    vision: VisionConfig

    @classmethod
    def fromEnvironment(cls, environment: Mapping[str, str] | None = None):
        env = os.environ if environment is None else environment
        maxFileSizeMb = _positiveInteger(
            env, "HARNESS_DOCUMENT_PARSER_MAX_FILE_SIZE_MB", 50)
        vision = _visionConfig(env)
        return cls(
            host=env.get("HARNESS_DOCUMENT_PARSER_HOST", "0.0.0.0").strip()
            or "0.0.0.0",
            port=_positiveInteger(env, "HARNESS_DOCUMENT_PARSER_PORT", 8082),
            token=env.get("HARNESS_DOCUMENT_PARSER_TOKEN", "").strip(),
            maxFileBytes=maxFileSizeMb * 1024 * 1024,
            timeoutSeconds=_positiveInteger(
                env, "HARNESS_DOCUMENT_PARSER_TIMEOUT_SECONDS", 300),
            visionTimeoutSeconds=_positiveInteger(
                env, "HARNESS_DOCUMENT_PARSER_VISION_TIMEOUT_SECONDS", 60),
            maxConcurrent=_positiveInteger(
                env, "HARNESS_DOCUMENT_PARSER_MAX_CONCURRENT", 2),
            vision=vision,
        )


def _visionConfig(environment: Mapping[str, str]) -> VisionConfig:
    visionProvider = environment.get("HARNESS_MODEL_VISION_PROVIDER", "").strip()
    if visionProvider:
        source = "vision"
        prefix = "HARNESS_MODEL_VISION"
    else:
        source = "chat"
        prefix = "HARNESS_MODEL_CHAT"

    provider = environment.get(f"{prefix}_PROVIDER", "").strip().lower()
    if provider == "claude":
        provider = "anthropic"
    if not provider:
        raise ConfigurationError(
            f"{prefix}_PROVIDER is required for document parser vision configuration")
    if provider == "none":
        return VisionConfig(
            provider=provider,
            apiKey="",
            baseUrl="",
            model="",
            source="none",
            enabled=False,
        )
    if provider == "ollama":
        raise ConfigurationError(
            "Ollama is not supported by the MarkItDown vision integration; "
            "configure an OpenAI-compatible openai/dashscope endpoint or anthropic")
    if provider not in SUPPORTED_PROVIDERS:
        raise ConfigurationError(
            f"Unsupported document parser vision provider: {provider}")

    values = {
        "apiKey": environment.get(f"{prefix}_API_KEY", "").strip(),
        "baseUrl": environment.get(f"{prefix}_BASE_URL", "").strip(),
        "model": environment.get(f"{prefix}_MODEL", "").strip(),
    }
    missing = [name for name, value in values.items() if not value]
    if missing:
        missingKeys = ", ".join(
            f"{prefix}_{_environmentSuffix(name)}" for name in missing)
        raise ConfigurationError(
            f"Incomplete {source} vision configuration; missing: {missingKeys}")
    return VisionConfig(
        provider=provider,
        apiKey=values["apiKey"],
        baseUrl=values["baseUrl"],
        model=values["model"],
        source=source,
        enabled=True,
    )


def _environmentSuffix(fieldName: str) -> str:
    return {
        "apiKey": "API_KEY",
        "baseUrl": "BASE_URL",
        "model": "MODEL",
    }[fieldName]


def _positiveInteger(
        environment: Mapping[str, str], name: str, defaultValue: int) -> int:
    rawValue = environment.get(name, str(defaultValue)).strip()
    try:
        value = int(rawValue)
    except ValueError as error:
        raise ConfigurationError(f"{name} must be an integer") from error
    if value <= 0:
        raise ConfigurationError(f"{name} must be positive")
    return value
