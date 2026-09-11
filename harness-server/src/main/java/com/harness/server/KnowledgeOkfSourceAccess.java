package com.harness.server;

import com.harness.core.knowledge.KnowledgeArtifact;
import com.harness.core.knowledge.KnowledgeConcept;
import com.harness.core.knowledge.KnowledgeSource;
import com.harness.core.knowledge.OkfKnowledgeDocument;
import com.harness.core.model.AgentTrace;
import com.harness.input.memory.SessionStore;
import com.harness.input.memory.MessageStore;
import com.harness.agent.graph.GraphSpaceAccessService;
import com.harness.graph.schema.GraphSchemaRegistry;
import com.harness.tool.knowledge.authority.KnowledgeArtifactRepository;
import com.harness.tool.knowledge.authority.KnowledgeHead;
import com.harness.tool.knowledge.authority.KnowledgeRepository;
import com.harness.tool.knowledge.okf.OkfBundleScope;
import com.harness.tool.knowledge.okf.OkfImportSourceResolver;
import com.harness.tool.knowledge.okf.OkfSourceAuthorizer;
import com.harness.trace.store.TraceStore;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Resolves OKF source URIs against live authorities without exposing source bodies. */
final class KnowledgeOkfSourceAccess implements OkfSourceAuthorizer, OkfImportSourceResolver {

    private final KnowledgeRepository knowledgeRepository;
    private final KnowledgeArtifactRepository artifactRepository;
    private final SessionStore sessionStore;
    private final MessageStore messageStore;
    private final TraceStore traceStore;
    private final GraphSpaceAccessService graphAccessService;
    private final GraphSchemaRegistry graphSchemaRegistry;

    KnowledgeOkfSourceAccess(
            KnowledgeRepository knowledgeRepository,
            KnowledgeArtifactRepository artifactRepository,
            SessionStore sessionStore,
            MessageStore messageStore,
            TraceStore traceStore,
            GraphSpaceAccessService graphAccessService,
            GraphSchemaRegistry graphSchemaRegistry
    ) {
        this.knowledgeRepository = Objects.requireNonNull(
                knowledgeRepository, "knowledgeRepository");
        this.artifactRepository = Objects.requireNonNull(
                artifactRepository, "artifactRepository");
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
        this.messageStore = Objects.requireNonNull(messageStore, "messageStore");
        this.traceStore = Objects.requireNonNull(traceStore, "traceStore");
        this.graphAccessService = Objects.requireNonNull(
                graphAccessService, "graphAccessService");
        this.graphSchemaRegistry = Objects.requireNonNull(
                graphSchemaRegistry, "graphSchemaRegistry");
    }

    @Override
    public boolean canExport(
            OkfBundleScope scope,
            KnowledgeConcept concept,
            KnowledgeSource source
    ) {
        return scope.permits(concept) && readable(scope, source.sourceResource());
    }

    @Override
    public boolean existsAndReadable(
            OkfBundleScope scope,
            OkfKnowledgeDocument.Source source
    ) {
        return readable(scope, source.resource());
    }

    @Override
    public boolean canExportResource(
            OkfBundleScope scope,
            KnowledgeConcept concept,
            String resource
    ) {
        return scope.permits(concept) && readable(scope, resource);
    }

    private boolean readable(OkfBundleScope scope, String resource) {
        try {
            URI uri = URI.create(resource);
            if (!"cyrene".equals(uri.getScheme()) || uri.getHost() == null) {
                return false;
            }
            List<String> path = Arrays.stream(uri.getPath().split("/"))
                    .filter(segment -> !segment.isBlank())
                    .toList();
            return switch (uri.getHost()) {
                case "session" -> readableMessage(scope, path);
                case "trace" -> readableTrace(scope, path);
                case "artifacts" -> readableArtifact(scope, path);
                case "knowledge" -> readableKnowledge(scope, path);
                case "graph-schemas" -> readableGraphSchema(scope, path);
                case "graphs" -> readableGraph(scope, path);
                default -> false;
            };
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean readableMessage(OkfBundleScope scope, List<String> path) {
        if (scope.kind() != OkfBundleScope.Kind.USER
                || path.size() != 3
                || !"message".equals(path.get(1))) {
            return false;
        }
        long messageId = parsePositiveLong(path.get(2));
        return messageId > 0
                && sessionStore.findByIdAndOwner(
                path.getFirst(), scope.userId(), scope.tenantId()).isPresent()
                && messageStore.findByIdAndSession(messageId, path.getFirst()).isPresent();
    }

    private boolean readableTrace(OkfBundleScope scope, List<String> path) {
        if (scope.kind() != OkfBundleScope.Kind.USER || path.size() != 1) {
            return false;
        }
        AgentTrace trace = traceStore.findById(path.getFirst()).orElse(null);
        return trace != null
                && scope.userId().equals(trace.userId())
                && trace.sessionId() != null
                && sessionStore.findByIdAndOwner(
                trace.sessionId(), scope.userId(), scope.tenantId()).isPresent();
    }

    private boolean readableArtifact(OkfBundleScope scope, List<String> path) {
        if (path.size() != 1) {
            return false;
        }
        KnowledgeArtifact artifact = artifactRepository.findById(path.getFirst()).orElse(null);
        if (artifact == null || artifact.status() != KnowledgeArtifact.Status.ACTIVE
                || !Objects.equals(normalize(artifact.tenantId()), normalize(scope.tenantId()))) {
            return false;
        }
        return scope.kind() == OkfBundleScope.Kind.COLLECTION
                && scope.namespaceKey().equals(artifact.collectionKey());
    }

    private boolean readableKnowledge(OkfBundleScope scope, List<String> path) {
        if (path.isEmpty()) {
            return false;
        }
        KnowledgeHead head = knowledgeRepository.findById(path.getFirst()).orElse(null);
        if (head == null || !scope.permits(head.concept())) {
            return false;
        }
        return path.size() == 1
                || path.size() == 3
                && "revisions".equals(path.get(1))
                && path.get(2).equals(head.concept().currentRevisionId());
    }

    private boolean readableGraphSchema(OkfBundleScope scope, List<String> path) {
        return readableGraphScope(scope)
                && path.size() == 1
                && scope.schemaId().equals(path.getFirst())
                && graphSchemaRegistry.find(path.getFirst()).isPresent();
    }

    private boolean readableGraph(OkfBundleScope scope, List<String> path) {
        if (!readableGraphScope(scope)
                || path.isEmpty()
                || !scope.graphId().equals(path.getFirst())) {
            return false;
        }
        return path.size() == 1;
    }

    private boolean readableGraphScope(OkfBundleScope scope) {
        if (scope.kind() != OkfBundleScope.Kind.GRAPH) {
            return false;
        }
        try {
            graphAccessService.requireReadable(
                    scope.tenantId(), scope.graphId(), scope.schemaId());
            return true;
        } catch (SecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    private static long parsePositiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
