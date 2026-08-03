package com.harness.server;

import com.harness.core.exception.AgentException;
import com.harness.graph.build.GraphDataConversionException;
import com.harness.graph.schema.GraphSchemaPersistenceException;
import com.harness.graph.store.GraphStoreException;
import com.harness.server.api.ApiErrorCode;
import com.harness.server.api.ApiResponses;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.NoSuchElementException;
import java.util.Objects;

final class GraphRequestExecutor {

    private static final Logger log = LoggerFactory.getLogger(GraphRequestExecutor.class);

    private final GraphRequestAuthenticator authenticator;

    GraphRequestExecutor(GraphRequestAuthenticator authenticator) {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
    }

    void execute(Context context, HandlerAction action) {
        try {
            authenticator.authenticate(context);
            action.run();
        } catch (NoSuchElementException e) {
            ApiResponses.error(context, 404, ApiErrorCode.NOT_FOUND, e.getMessage());
        } catch (SecurityException e) {
            ApiResponses.error(context, 403, ApiErrorCode.FORBIDDEN, e.getMessage());
        } catch (AgentException e) {
            ApiResponses.error(context, 401, ApiErrorCode.UNAUTHORIZED, e.getMessage());
        } catch (GraphDataConversionException e) {
            log.warn("[GraphAPI] Natural-language graph parsing failed: {}", e.getMessage());
            ApiResponses.error(context, 422, ApiErrorCode.GRAPH_PARSE_FAILED, e.getMessage());
        } catch (IllegalArgumentException e) {
            ApiResponses.error(context, 400, ApiErrorCode.INVALID_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            ApiResponses.error(context, 409, ApiErrorCode.CONFLICT, e.getMessage());
        } catch (GraphStoreException | GraphSchemaPersistenceException e) {
            log.error("[GraphAPI] Operation failed: {}", e.getMessage(), e);
            ApiResponses.error(
                    context, 500, ApiErrorCode.GRAPH_OPERATION_FAILED, e.getMessage());
        } catch (Exception e) {
            log.error("[GraphAPI] Unexpected operation failure: {}", e.getMessage(), e);
            ApiResponses.error(context, 500, ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    @FunctionalInterface
    interface HandlerAction {
        void run();
    }
}
