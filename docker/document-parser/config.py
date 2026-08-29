import os
from dataclasses import dataclass
from pathlib import Path
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
    modelConfigPath: str

    @classmethod
    def fromEnvironment(
            cls,
            environment: Mapping[str, str] | None = None,
            modelConfiguration: Mapping[str, str] | None = None):
        env = os.environ if environment is None else environment
        modelConfigPath = env.get(
            "HARNESS_CONFIG_MODEL_FILE", "./data/model.conf").strip()
        modelValues = (
            _readModelConfiguration(modelConfigPath)
            if modelConfiguration is None
            else dict(modelConfiguration)
        )
        maxFileSizeMb = _positiveInteger(
            env, "HARNESS_DOCUMENT_PARSER_MAX_FILE_SIZE_MB", 50)
        vision = _visionConfig(modelValues)
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
            modelConfigPath=modelConfigPath,
        )


def _readModelConfiguration(pathValue: str) -> dict[str, str]:
    path = Path(pathValue).expanduser()
    if not path.is_file():
        # First-run setup is completed by the main Web console. Start without
        # vision so the parser can become healthy before that file is created.
        return {}
    values: dict[str, str] = {}
    for lineNumber, rawLine in enumerate(
            path.read_text(encoding="utf-8").splitlines(), start=1):
        line = rawLine.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise ConfigurationError(
                f"Invalid model.conf assignment at line {lineNumber}")
        key, rawValue = line.split("=", 1)
        key = key.strip()
        if key in values:
            raise ConfigurationError(f"Duplicate model.conf key: {key}")
        values[key] = _decodeValue(rawValue)
    return values


def _decodeValue(rawValue: str) -> str:
    value = rawValue.strip()
    if len(value) >= 2 and value.startswith('"') and value.endswith('"'):
        return value[1:-1].replace('\\"', '"').replace('\\\\', '\\')
    commentIndex = value.find(" #")
    return value[:commentIndex].strip() if commentIndex >= 0 else value


def _visionConfig(modelConfiguration: Mapping[str, str]) -> VisionConfig:
    visionProvider = modelConfiguration.get("vision.provider", "").strip()
    if visionProvider:
        source = "vision"
        prefix = "vision"
    else:
        source = "chat"
        prefix = "chat"

    provider = modelConfiguration.get(f"{prefix}.provider", "").strip().lower()
    if provider == "claude":
        provider = "anthropic"
    if not provider:
        return VisionConfig(
            provider="none",
            apiKey="",
            baseUrl="",
            model="",
            source="none",
            enabled=False,
        )
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
        "apiKey": modelConfiguration.get(f"{prefix}.apiKey", "").strip(),
        "baseUrl": modelConfiguration.get(f"{prefix}.baseUrl", "").strip(),
        "model": modelConfiguration.get(f"{prefix}.model", "").strip(),
    }
    missing = [name for name, value in values.items() if not value]
    if missing:
        missingKeys = ", ".join(
            f"{prefix}.{name}" for name in missing)
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
