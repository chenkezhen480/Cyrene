package com.harness.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.agent.AgentOrchestrator;
import com.harness.core.model.AgentResult;
import com.harness.core.model.AgentTrace;
import com.harness.core.model.FinalOutputContract;
import com.harness.core.model.RiskLevel;
import com.harness.server.api.ApiError;
import com.harness.server.api.ApiErrorCode;
import io.javalin.http.Context;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StructuredOutputHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void returnsValidatedDataAndMetadata() throws Exception {
        AgentOrchestrator agent = mock(AgentOrchestrator.class);
        ApiRequestAuthenticator authenticator = mock(ApiRequestAuthenticator.class);
        Context context = mock(Context.class);
        StructuredOutputHandler.StructuredOutputRequest request = request();
        when(authenticator.authenticate(context)).thenReturn("token");
        when(context.bodyAsClass(StructuredOutputHandler.StructuredOutputRequest.class))
                .thenReturn(request);
        when(context.header("X-Session-Id")).thenReturn("session-request");
        when(context.json(any())).thenReturn(context);
        when(agent.runStructured(
                any(), any(), anyList(), any(), isNull(), any(), any(), any(), any(), any()))
                .thenReturn(result("{\"eligible\":true,\"reason\":\"qualified\"}"));
        StructuredOutputHandler handler = new StructuredOutputHandler(
                agent, new ConcurrentHashMap<>(), authenticator, objectMapper);

        handler.handle(context);

        ArgumentCaptor<FinalOutputContract.JsonSchema> contractCaptor =
                ArgumentCaptor.forClass(FinalOutputContract.JsonSchema.class);
        verify(agent).runStructured(
                eq("token"),
                eq("analyze customer"),
                eq(List.of()),
                eq("session-request"),
                isNull(),
                any(),
                isNull(),
                eq("user-1"),
                any(),
                contractCaptor.capture());
        assertThat(contractCaptor.getValue().name()).isEqualTo("customerDecision");
        assertThat(contractCaptor.getValue().strict()).isTrue();

        ArgumentCaptor<Object> responseCaptor = ArgumentCaptor.forClass(Object.class);
        verify(context).json(responseCaptor.capture());
        assertThat(responseCaptor.getValue())
                .isInstanceOf(StructuredOutputHandler.StructuredOutputResponse.class);
        StructuredOutputHandler.StructuredOutputResponse response =
                (StructuredOutputHandler.StructuredOutputResponse) responseCaptor.getValue();
        assertThat(response.data().path("eligible").asBoolean()).isTrue();
        assertThat(response.meta().sessionId()).isEqualTo("session-result");
        assertThat(response.meta().traceId()).isEqualTo("trace-1");
    }

    @Test
    void mapsInvalidModelJsonToExplicitGatewayError() throws Exception {
        AgentOrchestrator agent = mock(AgentOrchestrator.class);
        ApiRequestAuthenticator authenticator = mock(ApiRequestAuthenticator.class);
        Context context = mock(Context.class);
        when(authenticator.authenticate(context)).thenReturn("token");
        when(context.bodyAsClass(StructuredOutputHandler.StructuredOutputRequest.class))
                .thenReturn(request());
        when(context.status(502)).thenReturn(context);
        when(context.json(any())).thenReturn(context);
        when(agent.runStructured(
                any(), any(), anyList(), any(), isNull(), any(), any(), any(), any(), any()))
                .thenReturn(result("not-json"));
        StructuredOutputHandler handler = new StructuredOutputHandler(
                agent, new ConcurrentHashMap<>(), authenticator, objectMapper);

        handler.handle(context);

        ArgumentCaptor<Object> errorCaptor = ArgumentCaptor.forClass(Object.class);
        verify(context).status(502);
        verify(context).json(errorCaptor.capture());
        assertThat(errorCaptor.getValue()).isInstanceOf(ApiError.class);
        assertThat(((ApiError) errorCaptor.getValue()).code())
                .isEqualTo(ApiErrorCode.STRUCTURED_OUTPUT_INVALID_JSON);
    }

    private StructuredOutputHandler.StructuredOutputRequest request() throws Exception {
        return new StructuredOutputHandler.StructuredOutputRequest(
                "analyze customer",
                List.of(),
                Map.of("userId", "user-1"),
                new StructuredOutputHandler.OutputSchemaRequest(
                        "customerDecision",
                        true,
                        objectMapper.readTree("""
                                {
                                  "type":"object",
                                  "properties":{
                                    "eligible":{"type":"boolean"},
                                    "reason":{"type":"string"}
                                  },
                                  "required":["eligible","reason"],
                                  "additionalProperties":false
                                }
                                """)));
    }

    private static AgentResult result(String output) {
        AgentTrace trace = AgentTrace.builder()
                .traceId("trace-1")
                .sessionId("session-result")
                .riskLevel(RiskLevel.LOW)
                .build();
        return AgentResult.success(output, trace, List.of());
    }
}
