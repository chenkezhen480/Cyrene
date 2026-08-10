import os
import sys
from collections.abc import Mapping


PARSER_ENVIRONMENT_KEYS = {
    "HARNESS_DOCUMENT_PARSER_HOST",
    "HARNESS_DOCUMENT_PARSER_PORT",
    "HARNESS_DOCUMENT_PARSER_TOKEN",
    "HARNESS_DOCUMENT_PARSER_MAX_FILE_SIZE_MB",
    "HARNESS_DOCUMENT_PARSER_TIMEOUT_SECONDS",
    "HARNESS_DOCUMENT_PARSER_VISION_TIMEOUT_SECONDS",
    "HARNESS_DOCUMENT_PARSER_MAX_CONCURRENT",
}
MODEL_ENVIRONMENT_KEYS = {
    f"HARNESS_MODEL_{capability}_{field}"
    for capability in ("CHAT", "VISION")
    for field in ("PROVIDER", "API_KEY", "BASE_URL", "MODEL")
}
SYSTEM_ENVIRONMENT_KEYS = {
    "PATH",
    "HOME",
    "USER",
    "LOGNAME",
    "SHELL",
    "HOSTNAME",
    "TZ",
    "LANG",
    "LANGUAGE",
    "LC_ALL",
    "LC_CTYPE",
    "LC_NUMERIC",
    "LC_TIME",
    "LC_COLLATE",
    "LC_MONETARY",
    "LC_MESSAGES",
    "LC_PAPER",
    "LC_NAME",
    "LC_ADDRESS",
    "LC_TELEPHONE",
    "LC_MEASUREMENT",
    "LC_IDENTIFICATION",
    "SSL_CERT_FILE",
    "SSL_CERT_DIR",
    "REQUESTS_CA_BUNDLE",
    "CURL_CA_BUNDLE",
    "HTTP_PROXY",
    "HTTPS_PROXY",
    "NO_PROXY",
    "ALL_PROXY",
    "http_proxy",
    "https_proxy",
    "no_proxy",
    "all_proxy",
    "TMPDIR",
    "TEMP",
    "TMP",
    "PYTHONUNBUFFERED",
    "PYTHONIOENCODING",
    "PYTHONUTF8",
}


def sanitizedEnvironment(environment: Mapping[str, str]) -> dict[str, str]:
    allowedKeys = (
        PARSER_ENVIRONMENT_KEYS
        | MODEL_ENVIRONMENT_KEYS
        | SYSTEM_ENVIRONMENT_KEYS
    )
    return {
        key: value
        for key, value in environment.items()
        if key in allowedKeys
    }


def main():
    appPath = os.path.join(os.path.dirname(os.path.abspath(__file__)), "app.py")
    environment = sanitizedEnvironment(os.environ)
    os.execve(sys.executable, [sys.executable, appPath], environment)


if __name__ == "__main__":
    main()
