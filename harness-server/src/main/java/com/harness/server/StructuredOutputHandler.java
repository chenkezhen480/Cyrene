package com.harness.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.agent.AgentOrchestrator;
import com.harness.core.exception.StructuredOutputException;
import com.harness.core.model.AgentContext;
import com.harness.core.model.AgentResult;
import com.harness.core.model.CancellationToken;
import com.harness.core.model.FinalOutputContract;
import com.harness.core.structured.StructuredOutputValueValidator;
import com.harness.input.multimodal.MultimodalParser;
import com.harness.provider.impl.CancellableHttpClient;
import com.harness.server.api.ApiErrorCode;
import com.harness.server.api.ApiResponses;
import com.harness.tool.HttpApiTool;
import io.javalin.http.Context;
import io.javalin.http.BadRequestResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Non-streaming endpoint that returns only locally validated structured data. */
public final class StructuredOutputHandler {

    private static final Logger log = LoggerFactory.getLogger(StructuredOutputHandler.class);

    private final AgentOrchestrator agent;
    private final ConcurrentHashMap<String, CancellationToken> activeRequests;
    private final ApiRequestAuthenticator authenticator;
    private final StructuredOutputSchemaValidator schemaValidator;
    private final StructuredOutputValueValidator valueValidator;

    public StructuredOutputHandler(
            AgentOrchestrator agent,
            ConcurrentHashMap<String, CancellationToken> activeRequests
    ) {
        this(agent, activeRequests, new ApiRequestAuthenticator(), new ObjectMapper());
    }

    StructuredOutputHandler(
            AgentOrchestrator agent,
            ConcurrentHashMap<String, CancellationToken> activeRequests,
            ApiRequestAuthenticator authenticator,
            ObjectMapper objectMapper
    ) {
        this.agent = agent;
        this.activeRequests = activeRequests;
        this.authenticator = authenticator;
        this.schemaValidator = new StructuredOutputSchemaValidator(objectMapper);
        this.valueValidator = new StructuredOutputValueValidator(objectMapper);
    }

    public void handle(Context context) {
        String requestId = null;
        String resolvedSessionId = null;
        CancellationToken cancellationToken = null;
        try {
            String rawToken = authenticator.authenticate(context);
            StructuredOutputRequest request = context.bodyAsClass(StructuredOutputRequest.class);
            validateRequest(request);

            FinalOutputContract.JsonSchema outputContract = schemaValidator.validate(
                    request.outputSchema().name(),
                    request.outputSchema().schema(),
                    request.outputSchema().strict());
            AgentContext agentContext = AgentContext.of(
                    AgentContextRequestMapper.sanitize(request.context()));

            String requestedSessionId = context.header("X-Session-Id");
            requestId = requestedSessionId != null
                    ? requestedSessionId
                    : UUID.randomUUID().toString();
            cancellationToken = new CancellationToken();
            cancellationToken.onCancel(CancellableHttpClient::cancelAll);
            activeRequests.put(requestId, cancellationToken);
            HttpApiTool.setCurrentCredentials(agentContext.credentials());

            AgentResult result = agent.runStructured(
                    rawToken,
                    request.input(),
                    request.attachments() != null ? request.attachments() : List.of(),
                    requestedSessionId,
                    null,
                    cancellationToken,
                    agentContext.enableThinking(),
                    agentContext.userId(),
                    agentContext,
                    outputContract);
            JsonNode data = valueValidator.parseAndValidate(
                    result.output(), outputContract.schema());
            resolvedSessionId = result.trace().sessionId();
            if (resolvedSessionId == null || resolvedSessionId.isBlank()) {
                throw new IllegalStateException("Agent trace is missing sessionId");
            }
            if (!resolvedSessionId.equals(requestId)) {
                activeRequests.put(resolvedSessionId, cancellationToken);
            }
            context.json(new StructuredOutputResponse(
                    data,
                    new ResponseMeta(resolvedSessionId, result.trace().traceId())));
        } catch (ApiRequestAuthenticator.RequestAuthenticationException e) {
            ApiResponses.error(context, 401, ApiErrorCode.UNAUTHORIZED, e.getMessage());
        } catch (StructuredOutputException e) {
            ApiResponses.error(
                    context,
                    statusFor(e.code()),
                    ApiErrorCode.valueOf(e.code().name()),
                    e.getMessage(),
                    e.details());
        } catch (BadRequestResponse e) {
            ApiResponses.error(
                    context, 400, ApiErrorCode.INVALID_REQUEST, e.getMessage());
        } catch (IllegalArgumentException e) {
            ApiResponses.error(
                    context, 400, ApiErrorCode.INVALID_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("[Server] Structured output request failed: {}", e.getMessage(), e);
            ApiResponses.error(
                    context, 500, ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        } finally {
            HttpApiTool.clearCurrentCredentials();
            if (requestId != null) {
                activeRequests.remove(requestId);
            }
            if (resolvedSessionId != null && !resolvedSessionId.isBlank()) {
                activeRequests.remove(resolvedSessionId);
            }
        }
    }

    private static void validateRequest(StructuredOutputRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        if (request.input() == null || request.input().isBlank()) {
            throw new IllegalArgumentException("input is required");
        }
        if (request.outputSchema() == null) {
            throw new IllegalArgumentException("outputSchema is required");
        }
    }

    private static int statusFor(StructuredOutputException.Code code) {
        return switch (code) {
            case STRUCTURED_OUTPUT_SCHEMA_INVALID -> 400;
            case STRUCTURED_OUTPUT_UNSUPPORTED, STRUCTURED_OUTPUT_REFUSED -> 422;
            case STRUCTURED_OUTPUT_TRUNCATED, STRUCTURED_OUTPUT_EMPTY,
                    STRUCTURED_OUTPUT_INVALID_JSON,
                    STRUCTURED_OUTPUT_SCHEMA_MISMATCH -> 502;
        };
    }

    public record StructuredOutputRequest(
            String input,
            List<MultimodalParser.RawAttachment> attachments,
            Map<String, Object> context,
            OutputSchemaRequest outputSchema
    ) {
    }

    public record OutputSchemaRequest(String name, boolean strict, JsonNode schema) {
    }

    public record StructuredOutputResponse(JsonNode data, ResponseMeta meta) {
    }

    public record ResponseMeta(String sessionId, String traceId) {
    }
}
