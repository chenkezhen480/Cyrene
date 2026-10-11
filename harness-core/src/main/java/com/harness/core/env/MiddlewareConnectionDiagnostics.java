package com.harness.core.env;

import java.net.URI;
import java.sql.SQLException;
import redis.clients.jedis.exceptions.JedisDataException;

/** Shared startup diagnostics without credentials from connection URLs or driver messages. */
public final class MiddlewareConnectionDiagnostics {
    public enum Service {
        MYSQL("MySQL", "mysql", EnvKey.AUDIT_DB_URL, EnvKey.AUDIT_DB_USER, EnvKey.AUDIT_DB_PASS),
        REDIS("Redis", "redis", EnvKey.MEMORY_REDIS_URL, EnvKey.MEMORY_REDIS_PASSWORD, EnvKey.MEMORY_REDIS_DB),
        MILVUS("Milvus", "milvus", EnvKey.RAG_URL, EnvKey.RAG_API_KEY, EnvKey.RAG_DATABASE),
        NEO4J("Neo4j", "neo4j", EnvKey.GRAPH_NEO4J_URI, EnvKey.GRAPH_NEO4J_USER, EnvKey.GRAPH_NEO4J_PASSWORD);

        private final String name;
        private final String dockerService;
        private final String configKeys;

        Service(String name, String dockerService, String... configKeys) {
            this.name = name;
            this.dockerService = dockerService;
            this.configKeys = String.join(", ", configKeys);
        }
    }

    private MiddlewareConnectionDiagnostics() {}

    public static String failureMessage(Service service, String url, Throwable failure) {
        return service.name + " 启动连接检查失败（" + endpoint(url) + "）：" + reason(failure)
                + "。请检查 " + service.configKeys
                + "。推荐通过项目 Docker 配置启动服务：docker compose --env-file .env"
                + " -f docker/docker-compose.yml up -d " + service.dockerService;
    }

    public static String endpoint(String url) {
        try {
            URI uri = URI.create(url.startsWith("jdbc:") ? url.substring(5) : url);
            if (uri.getHost() != null) {
                return uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
            }
        } catch (IllegalArgumentException ignored) {
            // Invalid URLs can contain secrets; never print the original value.
        }
        return "连接地址无效";
    }

    private static String reason(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                if (sql.getSQLState() != null && sql.getSQLState().startsWith("28")) return "认证失败，请核对账号和密码";
                if (sql.getErrorCode() == 1044) return "数据库访问被拒绝，请检查账号权限";
                if (sql.getErrorCode() == 1049) return "目标数据库不存在，请先执行统一 MySQL 初始化脚本";
            }
            String type = cause.getClass().getSimpleName();
            String message = cause.getMessage();
            if ("AuthenticationException".equals(type)
                    || (cause instanceof JedisDataException && message != null
                    && (message.startsWith("NOAUTH") || message.startsWith("WRONGPASS") || message.startsWith("ERR invalid password")))
                    || (("StatusRuntimeException".equals(type) || "MilvusClientException".equals(type)) && message != null
                    && (message.contains("UNAUTHENTICATED") || message.contains("PERMISSION_DENIED")))) {
                return "认证或授权失败，请核对凭据和服务权限";
            }
            if (cause instanceof IllegalArgumentException) return "连接配置无效";
        }
        return "服务可能未启动或不可达，请确认服务状态、连接地址和网络";
    }
}
