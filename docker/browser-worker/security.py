import ipaddress
import socket
from urllib.parse import urlsplit


class UrlPolicyError(ValueError):
    pass


def validate_http_url(url, allow_private_networks=False, blocked_domains=()):
    try:
        parsed = urlsplit(url)
    except ValueError as error:
        raise UrlPolicyError(f"Invalid URL: {error}") from error
    if parsed.scheme.lower() not in {"http", "https"}:
        raise UrlPolicyError("Only http and https URLs are allowed")
    if parsed.username is not None or parsed.password is not None:
        raise UrlPolicyError("URLs containing embedded credentials are not allowed")
    if not parsed.hostname:
        raise UrlPolicyError("URL host is required")

    host = parsed.hostname.lower().rstrip(".")
    for blocked_domain in blocked_domains:
        normalized = blocked_domain.strip().lower().rstrip(".")
        if normalized and (host == normalized or host.endswith("." + normalized)):
            raise UrlPolicyError(f"URL host is blocked by policy: {host}")

    if not allow_private_networks:
        try:
            addresses = {
                item[4][0]
                for item in socket.getaddrinfo(
                    parsed.hostname, parsed.port or _default_port(parsed.scheme))
            }
        except OSError as error:
            raise UrlPolicyError(f"URL host cannot be resolved: {error}") from error
        if not addresses:
            raise UrlPolicyError("URL host did not resolve to an address")
        for address in addresses:
            if not ipaddress.ip_address(address).is_global:
                raise UrlPolicyError("Private or local network URLs are not allowed")
    return parsed


def is_safe_resource_url(url, allow_private_networks=False, blocked_domains=()):
    parsed = urlsplit(url)
    if parsed.scheme.lower() in {"about", "blob", "data"}:
        return True
    try:
        validate_http_url(url, allow_private_networks, blocked_domains)
        return True
    except UrlPolicyError:
        return False


def origin_of(url):
    parsed = validate_http_url(url, allow_private_networks=True)
    port = parsed.port or _default_port(parsed.scheme)
    return f"{parsed.scheme.lower()}://{parsed.hostname.lower()}:{port}"


def _default_port(scheme):
    return 443 if scheme.lower() == "https" else 80
