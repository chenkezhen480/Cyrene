package com.harness.tool.web;

import com.harness.core.exception.ToolExecutionException;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Validates outbound URLs and blocks server-side requests to private networks.
 */
public final class UrlSafetyPolicy {

    private final boolean allowPrivateNetworks;
    private final List<String> blockedDomains;

    public UrlSafetyPolicy(boolean allowPrivateNetworks, List<String> blockedDomains) {
        this.allowPrivateNetworks = allowPrivateNetworks;
        this.blockedDomains = blockedDomains == null
                ? List.of()
                : blockedDomains.stream()
                        .filter(Objects::nonNull)
                        .map(value -> value.trim().toLowerCase(Locale.ROOT))
                        .filter(value -> !value.isBlank())
                        .toList();
    }

    public URI validate(String value, String toolName) {
        try {
            URI uri = URI.create(value).normalize();
            String scheme = uri.getScheme();
            if (scheme == null
                    || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                throw new ToolExecutionException(
                        toolName, "Only http and https URLs are allowed");
            }
            if (uri.getUserInfo() != null) {
                throw new ToolExecutionException(
                        toolName, "URLs containing embedded credentials are not allowed");
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                throw new ToolExecutionException(toolName, "URL host is required");
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            if (isBlockedDomain(normalizedHost)) {
                throw new ToolExecutionException(
                        toolName, "URL host is blocked by policy: " + normalizedHost);
            }
            if (!allowPrivateNetworks) {
                for (InetAddress address : InetAddress.getAllByName(host)) {
                    if (isPrivate(address)) {
                        throw new ToolExecutionException(
                                toolName, "Private or local network URLs are not allowed");
                    }
                }
            }
            return uri;
        } catch (ToolExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutionException(
                    toolName, "Invalid or unresolvable URL: " + e.getMessage(), e);
        }
    }

    private boolean isBlockedDomain(String host) {
        return blockedDomains.stream()
                .anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
    }

    private boolean isPrivate(InetAddress address) {
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress();
    }
}
