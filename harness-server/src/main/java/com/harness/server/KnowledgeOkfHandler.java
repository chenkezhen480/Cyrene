package com.harness.server;

import com.harness.agent.graph.GraphSpaceAccessService;
import com.harness.server.api.ApiErrorCode;
import com.harness.server.api.ApiResponses;
import com.harness.tool.knowledge.okf.OkfBundle;
import com.harness.tool.knowledge.okf.OkfBundleExporter;
import com.harness.tool.knowledge.okf.OkfBundleScope;
import com.harness.tool.knowledge.okf.OkfImportResult;
import com.harness.tool.knowledge.okf.OkfImportReview;
import com.harness.tool.knowledge.okf.OkfImportService;
import io.javalin.http.Context;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Authenticated management boundary for explicit OKF exchange operations. */
final class KnowledgeOkfHandler {

    private final OkfBundleExporter exporter;
    private final OkfImportService importer;
    private final GraphSpaceAccessService graphAccessService;
    private final SessionRequestOwnerResolver ownerResolver;
    private final boolean enabled;

    KnowledgeOkfHandler(
            OkfBundleExporter exporter,
            OkfImportService importer,
            GraphSpaceAccessService graphAccessService,
            boolean enabled
    ) {
        this(exporter, importer, graphAccessService, new SessionRequestOwnerResolver(), enabled);
    }

    KnowledgeOkfHandler(
            OkfBundleExporter exporter,
            OkfImportService importer,
            GraphSpaceAccessService graphAccessService,
            SessionRequestOwnerResolver ownerResolver,
            boolean enabled
    ) {
        this.exporter = Objects.requireNonNull(exporter, "exporter");
        this.importer = Objects.requireNonNull(importer, "importer");
        this.graphAccessService = Objects.requireNonNull(
                graphAccessService, "graphAccessService");
        this.ownerResolver = Objects.requireNonNull(ownerResolver, "ownerResolver");
        this.enabled = enabled;
    }

    void exportBundle(Context context) {
        execute(context, () -> {
            ExchangeRequest request = context.bodyAsClass(ExchangeRequest.class);
            OkfBundleScope scope = resolveScope(context, request);
            OkfBundle bundle = exporter.export(scope);
            context.json(bundle);
        });
    }

    void reviewImport(Context context) {
        execute(context, () -> {
            ExchangeRequest request = context.bodyAsClass(ExchangeRequest.class);
            OkfBundleScope scope = resolveScope(context, request);
            OkfImportReview review = importer.review(scope, requiredFiles(request));
            context.json(review);
        });
    }

    void commitImport(Context context) {
        execute(context, () -> {
            ExchangeRequest request = context.bodyAsClass(ExchangeRequest.class);
            OkfBundleScope scope = resolveScope(context, request);
            OkfImportResult result = importer.importApproved(
                    scope, requiredFiles(request), request.approvedPaths());
            context.json(result);
        });
    }

    private void execute(Context context, Runnable action) {
        if (!enabled) {
            ApiResponses.error(
                    context, 404, ApiErrorCode.NOT_FOUND, "OKF exchange is disabled");
            return;
        }
        try {
            action.run();
        } catch (SessionRequestOwnerResolver.OwnerResolutionException e) {
            ApiResponses.error(context, 401, ApiErrorCode.UNAUTHORIZED, e.getMessage());
        } catch (SecurityException e) {
            ApiResponses.error(context, 403, ApiErrorCode.FORBIDDEN, e.getMessage());
        } catch (IllegalArgumentException e) {
            ApiResponses.error(context, 400, ApiErrorCode.INVALID_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            ApiResponses.error(context, 409, ApiErrorCode.CONFLICT, e.getMessage());
        } catch (RuntimeException e) {
            ApiResponses.error(
                    context, 500, ApiErrorCode.INTERNAL_ERROR, "OKF exchange failed");
        }
    }

    private OkfBundleScope resolveScope(Context context, ExchangeRequest request) {
        if (request == null || request.kind() == null || request.kind().isBlank()) {
            throw new IllegalArgumentException("kind is required");
        }
        SessionRequestOwnerResolver.Owner owner = ownerResolver.resolve(
                context, request.userId(), request.tenantId());
        String kind = request.kind().trim().toLowerCase(Locale.ROOT);
        return switch (kind) {
            case "user" -> OkfBundleScope.user(owner.tenantId(), owner.userId());
            case "tenant_operation" -> OkfBundleScope.tenantOperation(owner.tenantId());
            case "global_operation" -> OkfBundleScope.globalOperation();
            case "collection" -> OkfBundleScope.collection(
                    owner.tenantId(), required(request.namespaceKey(), "namespaceKey"));
            case "graph" -> graphScope(owner, request);
            default -> throw new IllegalArgumentException("Unsupported OKF bundle kind: " + kind);
        };
    }

    private OkfBundleScope graphScope(
            SessionRequestOwnerResolver.Owner owner,
            ExchangeRequest request
    ) {
        String graphId = required(request.graphId(), "graphId");
        String schemaId = required(request.schemaId(), "schemaId");
        graphAccessService.requireReadable(owner.tenantId(), graphId, schemaId);
        return OkfBundleScope.graph(owner.tenantId(), graphId, schemaId);
    }

    private static Map<String, String> requiredFiles(ExchangeRequest request) {
        if (request.files() == null) {
            throw new IllegalArgumentException("files is required");
        }
        return request.files();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    record ExchangeRequest(
            String kind,
            String userId,
            String tenantId,
            String namespaceKey,
            String graphId,
            String schemaId,
            Map<String, String> files,
            List<String> approvedPaths
    ) {
    }
}
