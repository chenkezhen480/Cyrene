package com.harness.tool.web;

import com.harness.core.exception.ToolExecutionException;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Request-scoped capability set containing URLs explicitly written by the user.
 */
public final class AuthorizedUrlContext {

    private static final Pattern HTTP_URL =
            Pattern.compile("https?://[^\\s<>\"']+", Pattern.CASE_INSENSITIVE);
    private static final ThreadLocal<Set<String>> CURRENT = new ThreadLocal<>();

    private AuthorizedUrlContext() {
    }

    public static void setFromUserText(String text) {
        Set<String> urls = new LinkedHashSet<>();
        if (text != null) {
            Matcher matcher = HTTP_URL.matcher(text);
            while (matcher.find()) {
                String candidate = trimTrailingPunctuation(matcher.group());
                try {
                    urls.add(normalize(candidate));
                } catch (Exception ignored) {
                }
            }
        }
        CURRENT.set(Set.copyOf(urls));
    }

    public static Set<String> snapshot() {
        Set<String> urls = CURRENT.get();
        return urls != null ? Set.copyOf(urls) : Set.of();
    }

    public static void set(Set<String> urls) {
        CURRENT.set(urls == null ? Set.of() : Set.copyOf(urls));
    }

    public static void requireAuthorized(String url, String toolName) {
        String normalized;
        try {
            normalized = normalize(url);
        } catch (Exception e) {
            throw new ToolExecutionException(toolName, "Invalid URL: " + e.getMessage(), e);
        }
        Set<String> authorized = CURRENT.get();
        if (authorized == null || !authorized.contains(normalized)) {
            throw new ToolExecutionException(
                    toolName,
                    "URL was not explicitly provided by the user in this request: " + url);
        }
    }

    public static void clear() {
        CURRENT.remove();
    }

    private static String normalize(String value) {
        URI uri = URI.create(value).normalize();
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        int normalizedPort = port == -1
                ? ("https".equals(scheme) ? 443 : 80)
                : port;
        String path = uri.getRawPath();
        if (path == null || path.isBlank()) {
            path = "/";
        }
        String query = uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "";
        return scheme + "://" + host + ":" + normalizedPort + path + query;
    }

    private static String trimTrailingPunctuation(String value) {
        int end = value.length();
        while (end > 0) {
            char character = value.charAt(end - 1);
            if (".,;!?)]}，。；！？）】》".indexOf(character) < 0) {
                break;
            }
            end--;
        }
        return value.substring(0, end);
    }
}
