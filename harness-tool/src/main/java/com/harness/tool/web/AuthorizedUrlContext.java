package com.harness.tool.web;

import com.harness.core.exception.ToolExecutionException;

import java.net.URI;
import java.util.Collection;
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
        CURRENT.set(Set.copyOf(extractFromUserText(text)));
    }

    /**
     * 纯提取：只把文本里的 URL 规范化解出来，不改动当前作用域。
     * 供 resume 等需要从其它来源（会话历史）重建作用域的调用方使用。
     */
    public static Set<String> extractFromUserText(String text) {
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
        return urls;
    }

    /**
     * 追加本轮运行内可信来源（如 web_search 结果）发现的 URL，与用户显式给出的 URL 合并。
     * 无运行作用域时不授权，保持 fail-closed。
     */
    public static void authorizeAll(Collection<String> urls) {
        Set<String> current = CURRENT.get();
        if (current == null || urls == null || urls.isEmpty()) {
            return;
        }
        Set<String> merged = new LinkedHashSet<>(current);
        for (String url : urls) {
            if (url == null || url.isBlank()) {
                continue;
            }
            try {
                merged.add(normalize(url));
            } catch (Exception ignored) {
            }
        }
        CURRENT.set(Set.copyOf(merged));
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
        // 两种失败原因必须分开报：作用域没建立（run 上下文缺失）和 URL 不在作用域内，
        // 症状相似但根因完全不同，合并成一句话会让人查错方向。
        if (authorized == null) {
            throw new ToolExecutionException(
                    toolName,
                    "URL authorization scope is not initialized for this run: " + url);
        }
        if (!authorized.contains(normalized)) {
            throw new ToolExecutionException(
                    toolName,
                    "URL is outside the scope authorized by the user in this request: " + url);
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
