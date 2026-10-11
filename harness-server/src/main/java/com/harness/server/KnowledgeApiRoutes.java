package com.harness.server;

import com.harness.agent.AgentOrchestrator;
import com.harness.server.api.ApiErrorCode;
import com.harness.server.api.ApiResponses;
import com.harness.server.security.InternalApiRouteRegistry;
import com.harness.tool.knowledge.KnowledgeDocumentLifecycleService;
import com.harness.tool.knowledge.KnowledgeIngestService;
import com.harness.tool.knowledge.KnowledgeWikiService;
import com.harness.tool.knowledge.authority.ContentAddressedArtifactStorage;
import com.harness.tool.knowledge.authority.KnowledgeArtifactRepository;
import com.harness.tool.knowledge.authority.MysqlKnowledgeIngestJobStore;
import com.harness.trace.store.TraceStore;
import io.javalin.Javalin;
import io.javalin.http.Handler;

import java.nio.file.Path;

import static com.harness.core.security.ApiEndpointDescriptor.ResourcePolicy.GLOBAL_MANAGEMENT;

/** Composes vector-dependent endpoints without requiring vectors for authoritative Wiki access. */
final class KnowledgeApiRoutes {
    private KnowledgeApiRoutes() {}

    static KnowledgeWikiService register(Javalin app, InternalApiRouteRegistry routes,
            AgentOrchestrator agent, KnowledgeArtifactRepository artifacts, TraceStore traces, Path uploadDir) {
        var vectors = agent.vectorStore();
        var repository = agent.knowledgeRepository();
        var wiki = new KnowledgeWikiService(repository, vectors);
        KnowledgeUploadHandler upload = null;
        KnowledgeManagementHandler management = null;
        if (vectors != null) {
            var ingest = new KnowledgeIngestService(agent.embeddingModel(), vectors,
                    agent.documentConversionService(), agent.documentSummarizer(),
                    new ContentAddressedArtifactStorage(uploadDir), artifacts,
                    new MysqlKnowledgeIngestJobStore(repository), repository, agent.wikiIdentityResolver());
            upload = new KnowledgeUploadHandler(ingest, traces);
            management = new KnowledgeManagementHandler(vectors,
                    new KnowledgeDocumentLifecycleService(repository, vectors), wiki);
        }
        Handler disabled = ctx -> ApiResponses.error(ctx, 503, ApiErrorCode.CAPABILITY_DISABLED,
                "Knowledge vector capability is disabled (HARNESS_RAG_PROVIDER=none)");
        routes.route(app, "POST", "/api/knowledge/upload", "knowledge.upload", "knowledge", GLOBAL_MANAGEMENT,
                upload == null ? disabled : upload::handle);
        routes.route(app, "GET", "/api/knowledge/{collection}", "knowledge.documents", "knowledge", GLOBAL_MANAGEMENT,
                management == null ? disabled : management::listDocuments);
        routes.route(app, "GET", "/api/knowledge", "knowledge.collections", "knowledge", GLOBAL_MANAGEMENT,
                management == null ? disabled : management::listCollections);
        routes.route(app, "GET", "/api/knowledge/{collection}/{documentId}", "knowledge.read", "knowledge", GLOBAL_MANAGEMENT,
                management == null ? disabled : management::getDocument);
        routes.route(app, "PUT", "/api/knowledge/{collection}/{documentId}", "knowledge.update", "knowledge", GLOBAL_MANAGEMENT,
                management == null ? disabled : management::updateDocument);
        routes.route(app, "DELETE", "/api/knowledge/{collection}", "knowledge.deleteCollection", "knowledge", GLOBAL_MANAGEMENT,
                management == null ? disabled : management::deleteCollection);
        routes.route(app, "DELETE", "/api/knowledge/{collection}/{documentId}", "knowledge.deleteDocument", "knowledge", GLOBAL_MANAGEMENT,
                management == null ? disabled : management::deleteDocument);

        Handler requireKnowledge = ctx -> {
            var runtime = agent.knowledgeVectorRuntime();
            if (runtime.enabled() && !runtime.isReady()) {
                ApiResponses.error(ctx, 503, ApiErrorCode.KNOWLEDGE_NOT_READY, runtime.status().message());
                ctx.skipRemainingHandlers();
            }
        };
        app.before("/api/knowledge", requireKnowledge);
        app.before("/api/knowledge/*", requireKnowledge);
        return wiki;
    }
}
