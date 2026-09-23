package com.harness.server;

import com.harness.agent.graph.GraphSpaceAccessService;
import com.harness.agent.graph.GraphSpaceAccessException;
import com.harness.core.knowledge.*;
import com.harness.graph.store.KnowledgeGraphStore;
import com.harness.tool.knowledge.KnowledgeWikiService;
import com.harness.tool.knowledge.authority.KnowledgeHead;
import com.harness.server.api.*;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.*;
import java.util.function.Predicate;

final class KnowledgeWikiHandler {
    private final KnowledgeWikiService service;
    private final GraphSpaceAccessService graphAccess;
    private final KnowledgeGraphStore graphStore;
    private final SessionRequestOwnerResolver owners;

    KnowledgeWikiHandler(
            KnowledgeWikiService service,
            GraphSpaceAccessService graphAccess,
            KnowledgeGraphStore graphStore
    ) {
        this.service = service;
        this.graphAccess = graphAccess;
        this.graphStore = graphStore;
        this.owners = new SessionRequestOwnerResolver();
    }

    void list(Context ctx) {
        execute(ctx, () -> {
            var owner = owner(ctx);
            String type = ctx.queryParam("type");
            var kind = type == null ? KnowledgeConceptType.SOURCE_DOCUMENT : KnowledgeConceptType.valueOf(type);
            var page = service.page(owner.tenantId(), owner.userId(), kind, ctx.queryParam("collection"),
                    KnowledgeWikiService.parseCursor(ctx.queryParam("cursor")), ApiRequestParameters.limit(ctx, 5, 100), authorized(owner));
            ctx.json(page);
        });
    }

    void get(Context ctx) {
        execute(ctx, () -> ctx.json(service.get(ctx.pathParam("conceptId"), authorized(owner(ctx)))));
    }

    void update(Context ctx) {
        execute(ctx, () -> {
            var owner = owner(ctx);
            var draft = ctx.bodyAsClass(WikiUpdate.class);
            if (draft == null || draft.revisionId() == null || draft.revisionId().isBlank())
                throw new IllegalArgumentException("revisionId is required");
            ctx.json(service.update(ctx.pathParam("conceptId"), draft.revisionId(), draft.title(), draft.summary(),
                    owner.userId(), authorized(owner)));
        });
    }

    void delete(Context ctx) {
        execute(ctx, () -> {
            String revisionId = ctx.queryParam("revisionId");
            if (revisionId == null || revisionId.isBlank())
                throw new IllegalArgumentException("revisionId is required");
            ctx.json(service.delete(ctx.pathParam("conceptId"), revisionId, authorized(owner(ctx))));
        });
    }

    void export(Context ctx) {
        execute(ctx, () -> {
            var card = service.get(ctx.pathParam("conceptId"), authorized(owner(ctx)));
            ctx.contentType("text/markdown; charset=utf-8");
            ctx.header("Content-Disposition", "attachment; filename=\"wiki-" + card.conceptId().replaceAll("[^A-Za-z0-9_-]", "_") + ".md\"");
            ctx.result(service.markdown(card));
        });
    }

    void exportAll(Context ctx) {
        execute(ctx, () -> {
            owner(ctx);
            String markdown = service.exportMarkdown();
            ctx.contentType("text/markdown; charset=utf-8");
            ctx.header("Content-Disposition", "attachment; filename=\"llm-wiki.md\"");
            ctx.result(markdown);
        });
    }

    private SessionRequestOwnerResolver.Owner owner(Context ctx) {
        return owners.resolve(ctx, ctx.queryParam("userId"), ctx.queryParam("tenantId"));
    }

    private Predicate<KnowledgeHead> authorized(SessionRequestOwnerResolver.Owner owner) {
        return head -> {
            var concept = head.concept();
            if (concept.conceptType() == KnowledgeConceptType.USER_PREFERENCE) return false;
            if (concept.conceptType() == KnowledgeConceptType.USER_EPISODE)
                return Objects.equals(concept.userId(), owner.userId()) && Objects.equals(concept.tenantId(), owner.tenantId());
            if (concept.userId() != null || (concept.tenantId() != null && !Objects.equals(concept.tenantId(), owner.tenantId()))) return false;
            // Graph access decisions come from the access service as exceptions. Catching only
            // SecurityException left the real type — GraphSpaceAccessException, a plain
            // RuntimeException — to escape as a 500 instead of denying the read.
            if (concept.conceptType() == KnowledgeConceptType.GRAPH_SPACE) {
                try {
                    graphAccess.requireReadable(owner.tenantId(),
                            head.routeText("graphId"), head.routeText("schemaId"));
                    return true;
                } catch (SecurityException | GraphSpaceAccessException denied) {
                    return false;
                }
            }
            if (concept.conceptType() == KnowledgeConceptType.GRAPH_SCHEMA) {
                try {
                    return canReadSchema(owner.tenantId(), head.routeText("schemaId"));
                } catch (SecurityException | GraphSpaceAccessException denied) {
                    return false;
                }
            }
            return true;
        };
    }

    /**
     * A Schema Wiki card is readable when no graph space uses that Schema yet, and otherwise when any
     * graph space built on it is readable.
     *
     * <p>The first case is what makes a newly created Schema manageable: its card describes the
     * Schema definition and its query capabilities, never graph data, and a Schema no graph space
     * uses holds no data to disclose. Without it the only readable graph-schema card in a fresh
     * deployment is whichever Schema happens to own an existing graph space.</p>
     */
    private boolean canReadSchema(String tenantId, String schemaId) {
        if (!"none".equals(graphStore.providerName())
                && !graphStore.hasGraphSpacesForSchema(schemaId)) {
            return true;
        }
        String cursor = "";
        while (true) {
            var page = graphAccess.listReadable(tenantId, 100, cursor);
            if (page.items().stream().anyMatch(space -> space.schemaId().equals(schemaId))) {
                return true;
            }
            if (!page.pageInfo().hasMore()) {
                return false;
            }
            String next = page.pageInfo().nextCursor();
            if (next.isBlank() || next.equals(cursor)) {
                throw new IllegalStateException("Graph pagination did not advance");
            }
            cursor = next;
        }
    }

    private void execute(Context ctx, Runnable action) {
        try { action.run(); }
        catch (SessionRequestOwnerResolver.OwnerResolutionException denied) { ApiResponses.error(ctx, 401, ApiErrorCode.UNAUTHORIZED, denied.getMessage()); }
        catch (NoSuchElementException missing) { ApiResponses.error(ctx, 404, ApiErrorCode.NOT_FOUND, missing.getMessage()); }
        catch (SecurityException denied) { ApiResponses.error(ctx, 403, ApiErrorCode.FORBIDDEN, denied.getMessage()); }
        catch (HttpResponseException invalid) { ApiResponses.error(ctx, invalid.getStatus(), ApiErrorCode.fromHttpStatus(invalid.getStatus()), invalid.getMessage()); }
        catch (IllegalArgumentException invalid) { ApiResponses.error(ctx, 400, ApiErrorCode.INVALID_REQUEST, invalid.getMessage()); }
        catch (IllegalStateException conflict) { ApiResponses.error(ctx, 409, ApiErrorCode.CONFLICT, conflict.getMessage()); }
        catch (Exception failure) {
            if (failure instanceof JsonProcessingException)
                ApiResponses.error(ctx, 400, ApiErrorCode.INVALID_REQUEST, "Invalid Wiki JSON request");
            else ApiResponses.error(ctx, 500, ApiErrorCode.INTERNAL_ERROR, "Wiki operation failed: " + failure.getMessage());
        }
    }

    record WikiUpdate(String revisionId, String title, String summary) {}
}
