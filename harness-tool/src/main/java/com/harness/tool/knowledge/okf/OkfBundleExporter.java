package com.harness.tool.knowledge.okf;

import com.harness.core.knowledge.KnowledgeConcept;
import com.harness.core.knowledge.KnowledgeConceptCursor;
import com.harness.core.knowledge.KnowledgeConceptType;
import com.harness.core.knowledge.KnowledgeIndexTask;
import com.harness.core.knowledge.KnowledgeLink;
import com.harness.core.knowledge.KnowledgeLinkCursor;
import com.harness.core.knowledge.KnowledgeNamespaceType;
import com.harness.core.knowledge.KnowledgeOkfMapper;
import com.harness.core.knowledge.KnowledgeRevision;
import com.harness.core.knowledge.KnowledgeSource;
import com.harness.core.knowledge.KnowledgeSourceCursor;
import com.harness.core.knowledge.KnowledgeVerification;
import com.harness.core.knowledge.KnowledgeVerificationCursor;
import com.harness.core.knowledge.OkfKnowledgeDocument;
import com.harness.core.model.PageResponse;
import com.harness.tool.knowledge.authority.KnowledgeHead;
import com.harness.tool.knowledge.authority.KnowledgeIndexOutboxStore;
import com.harness.tool.knowledge.authority.KnowledgeRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Exports one strictly authorized current-view OKF bundle. */
public final class OkfBundleExporter {

    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int DEFAULT_MAX_CONCEPTS = 1_000;
    private static final int DEFAULT_MAX_LOG_EVENTS = 10_000;
    private static final int MAX_ASSOCIATIONS_PER_REVISION = 10_000;

    private final KnowledgeRepository repository;
    private final KnowledgeIndexOutboxStore outboxStore;
    private final OkfSourceAuthorizer sourceAuthorizer;
    private final KnowledgeOkfMapper mapper;
    private final OkfMarkdownCodec codec;
    private final OkfSensitiveDataRedactor redactor;
    private final Clock clock;
    private final int pageSize;
    private final int maxConcepts;
    private final int maxLogEvents;

    public OkfBundleExporter(
            KnowledgeRepository repository,
            KnowledgeIndexOutboxStore outboxStore,
            OkfSourceAuthorizer sourceAuthorizer,
            Clock clock
    ) {
        this(repository, outboxStore, sourceAuthorizer, new KnowledgeOkfMapper(),
                new OkfMarkdownCodec(), new OkfSensitiveDataRedactor(), clock,
                DEFAULT_PAGE_SIZE, DEFAULT_MAX_CONCEPTS, DEFAULT_MAX_LOG_EVENTS);
    }

    OkfBundleExporter(
            KnowledgeRepository repository,
            KnowledgeIndexOutboxStore outboxStore,
            OkfSourceAuthorizer sourceAuthorizer,
            KnowledgeOkfMapper mapper,
            OkfMarkdownCodec codec,
            OkfSensitiveDataRedactor redactor,
            Clock clock,
            int pageSize,
            int maxConcepts,
            int maxLogEvents
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.outboxStore = Objects.requireNonNull(outboxStore, "outboxStore");
        this.sourceAuthorizer = Objects.requireNonNull(sourceAuthorizer, "sourceAuthorizer");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.redactor = Objects.requireNonNull(redactor, "redactor");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (pageSize < 1 || pageSize > 100 || maxConcepts < 1 || maxLogEvents < 1) {
            throw new IllegalArgumentException("Invalid OKF export bounds");
        }
        this.pageSize = pageSize;
        this.maxConcepts = maxConcepts;
        this.maxLogEvents = maxLogEvents;
    }

    public OkfBundle export(OkfBundleScope scope) {
        Objects.requireNonNull(scope, "scope");
        Instant generatedAt = clock.instant();
        List<KnowledgeHead> heads = loadHeads(scope);
        heads.sort(Comparator.comparing((KnowledgeHead head) -> head.concept().conceptType().name())
                .thenComparing(head -> head.currentRevision().title(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(head -> head.concept().id()));

        Map<String, String> pathByConcept = new HashMap<>();
        for (KnowledgeHead head : heads) {
            pathByConcept.put(head.concept().id(), documentPath(head.concept()));
        }

        LinkedHashMap<String, String> files = new LinkedHashMap<>();
        List<IndexEntry> indexEntries = new ArrayList<>(heads.size());
        int omittedSourceCount = 0;
        for (KnowledgeHead head : heads) {
            KnowledgeConcept concept = head.concept();
            KnowledgeRevision revision = head.currentRevision();
            List<KnowledgeSource> authorizedSources = new ArrayList<>();
            for (KnowledgeSource source : loadSources(revision.id())) {
                if (sourceAuthorizer.canExport(scope, concept, source)
                        && !redactor.containsSensitiveData(source.sourceResource())) {
                    authorizedSources.add(source);
                } else {
                    omittedSourceCount++;
                }
            }
            List<KnowledgeVerification> verifications = loadVerifications(revision.id());
            List<KnowledgeLink> links = loadOutgoingLinks(concept.id()).stream()
                    .filter(link -> pathByConcept.containsKey(link.toConceptId()))
                    .toList();

            OkfKnowledgeDocument document = mapper.map(
                    concept, revision, authorizedSources, verifications);
            if (document.resource() != null
                    && !sourceAuthorizer.canExportResource(
                    scope, concept, document.resource())) {
                document = copy(document, null, document.extensions(), document.body());
            }
            document = withRelations(document, links, pathByConcept);
            document = sanitize(document);
            String path = pathByConcept.get(concept.id());
            files.put(path, codec.write(document));
            indexEntries.add(new IndexEntry(concept, revision, path, links));
        }

        LogResult log = buildLog(heads);
        files.put("index.md", renderIndex(scope, generatedAt, indexEntries, omittedSourceCount));
        files.put("log.md", renderLog(scope, generatedAt, log));
        return new OkfBundle(
                scope, generatedAt, files, heads.size(), omittedSourceCount, log.truncated());
    }

    private List<KnowledgeHead> loadHeads(OkfBundleScope scope) {
        List<KnowledgeHead> heads = new ArrayList<>();
        for (KnowledgeConceptType conceptType : conceptTypes(scope)) {
            KnowledgeConceptCursor cursor = null;
            boolean hasMore;
            do {
                PageResponse<KnowledgeConcept> page = scopedPage(
                        scope, conceptType, cursor);
                Map<String, KnowledgeHead> pageHeads = repository.findByIds(
                        page.items().stream().map(KnowledgeConcept::id).toList());
                for (KnowledgeConcept listed : page.items()) {
                    KnowledgeHead head = pageHeads.get(listed.id());
                    if (head == null || head.currentRevision() == null) {
                        throw new IllegalStateException(
                                "Knowledge head changed during OKF export: " + listed.id());
                    }
                    if (!scope.permits(head.concept())) {
                        throw new SecurityException(
                                "Knowledge Concept escaped OKF bundle scope: " + listed.id());
                    }
                    heads.add(head);
                    if (heads.size() > maxConcepts) {
                        throw new IllegalStateException(
                                "OKF bundle exceeds the configured concept limit");
                    }
                }
                hasMore = page.pageInfo().hasMore();
                cursor = hasMore ? conceptCursor(page.items()) : null;
            } while (hasMore);
        }
        return heads;
    }

    private List<KnowledgeSource> loadSources(String revisionId) {
        List<KnowledgeSource> result = new ArrayList<>();
        KnowledgeSourceCursor cursor = null;
        boolean hasMore;
        do {
            PageResponse<KnowledgeSource> page = repository.findSourcePage(
                    revisionId, cursor, pageSize);
            result.addAll(page.items());
            requireAssociationBound(result, "sources");
            hasMore = page.pageInfo().hasMore();
            cursor = hasMore ? KnowledgeSourceCursor.from(last(page.items())) : null;
        } while (hasMore);
        return List.copyOf(result);
    }

    private List<KnowledgeVerification> loadVerifications(String revisionId) {
        List<KnowledgeVerification> result = new ArrayList<>();
        KnowledgeVerificationCursor cursor = null;
        boolean hasMore;
        do {
            PageResponse<KnowledgeVerification> page = repository.findVerificationPage(
                    revisionId, cursor, pageSize);
            result.addAll(page.items());
            requireAssociationBound(result, "verifications");
            hasMore = page.pageInfo().hasMore();
            cursor = hasMore ? KnowledgeVerificationCursor.from(last(page.items())) : null;
        } while (hasMore);
        return List.copyOf(result);
    }

    private List<KnowledgeLink> loadOutgoingLinks(String conceptId) {
        List<KnowledgeLink> result = new ArrayList<>();
        KnowledgeLinkCursor cursor = null;
        boolean hasMore;
        do {
            PageResponse<KnowledgeLink> page = repository.findOutgoingLinkPage(
                    conceptId, cursor, pageSize);
            result.addAll(page.items());
            requireAssociationBound(result, "links");
            hasMore = page.pageInfo().hasMore();
            cursor = hasMore ? KnowledgeLinkCursor.outgoing(last(page.items())) : null;
        } while (hasMore);
        return List.copyOf(result);
    }

    private LogResult buildLog(List<KnowledgeHead> heads) {
        List<LogEvent> events = new ArrayList<>();
        boolean truncated = false;
        outer:
        for (KnowledgeHead head : heads) {
            Long beforeRevision = null;
            boolean hasMore;
            do {
                PageResponse<KnowledgeRevision> page = repository.findRevisionPage(
                        head.concept().id(), beforeRevision, pageSize);
                for (KnowledgeRevision revision : page.items()) {
                    if (!addEvent(events, new LogEvent(
                            revision.generatedAt(), "revision", head.concept().id(),
                            "revision " + revision.revisionNumber() + " generated by "
                                    + revision.generatedBy()))) {
                        truncated = true;
                        break outer;
                    }
                    for (KnowledgeVerification verification : loadVerifications(revision.id())) {
                        if (!addEvent(events, new LogEvent(
                                verification.verifiedAt(), "verification", head.concept().id(),
                                verification.result().name().toLowerCase(Locale.ROOT)
                                        + " by " + verification.verifiedBy()))) {
                            truncated = true;
                            break outer;
                        }
                    }
                }
                hasMore = page.pageInfo().hasMore();
                beforeRevision = hasMore ? last(page.items()).revisionNumber() : null;
            } while (hasMore);
            if (head.concept().status() == com.harness.core.knowledge.KnowledgeStatus.DEPRECATED
                    && !addEvent(events, new LogEvent(
                    head.concept().updatedAt(), "deprecation", head.concept().id(),
                    "current Concept marked deprecated"))) {
                truncated = true;
                break;
            }
        }

        if (!truncated && !heads.isEmpty()) {
            List<String> conceptIds = heads.stream().map(head -> head.concept().id()).toList();
            for (int start = 0; start < conceptIds.size() && !truncated; start += 100) {
                List<String> batch = conceptIds.subList(start, Math.min(start + 100, conceptIds.size()));
                long afterId = 0;
                boolean hasMore;
                do {
                    PageResponse<KnowledgeIndexTask> page = outboxStore.findPageByConceptIds(
                            batch, afterId, pageSize);
                    for (KnowledgeIndexTask task : page.items()) {
                        Instant eventAt = task.completedAt() == null ? task.createdAt() : task.completedAt();
                        if (!addEvent(events, new LogEvent(
                                eventAt, "index", task.conceptId(),
                                task.operation().name().toLowerCase(Locale.ROOT) + " "
                                        + task.status().storageValue()))) {
                            truncated = true;
                            break;
                        }
                    }
                    hasMore = !truncated && page.pageInfo().hasMore();
                    afterId = hasMore ? last(page.items()).id() : 0;
                } while (hasMore);
            }
        }
        events.sort(Comparator.comparing(LogEvent::at).reversed()
                .thenComparing(LogEvent::conceptId)
                .thenComparing(LogEvent::kind));
        return new LogResult(List.copyOf(events), truncated);
    }

    private boolean addEvent(List<LogEvent> events, LogEvent event) {
        if (events.size() >= maxLogEvents) {
            return false;
        }
        events.add(event);
        return true;
    }

    private OkfKnowledgeDocument withRelations(
            OkfKnowledgeDocument document,
            List<KnowledgeLink> links,
            Map<String, String> pathByConcept
    ) {
        if (links.isEmpty()) {
            return document;
        }
        List<Map<String, String>> relationValues = links.stream()
                .map(link -> Map.of(
                        "concept_id", link.toConceptId(),
                        "type", link.linkType().name().toLowerCase(Locale.ROOT)))
                .toList();
        LinkedHashMap<String, Object> extensions = new LinkedHashMap<>(document.extensions());
        extensions.put("x-cyrene-links", relationValues);
        StringBuilder body = new StringBuilder(document.body().stripTrailing())
                .append("\n\n## Relationships\n");
        for (KnowledgeLink link : links) {
            body.append("\n- [")
                    .append(markdownText(link.toConceptId()))
                    .append("](")
                    .append(relativePath(pathByConcept.get(link.toConceptId())))
                    .append(") — ")
                    .append(link.linkType().name().toLowerCase(Locale.ROOT));
        }
        return copy(document, document.resource(), extensions, body.toString());
    }

    @SuppressWarnings("unchecked")
    private OkfKnowledgeDocument sanitize(OkfKnowledgeDocument document) {
        String resource = redactor.containsSensitiveData(document.resource())
                ? null : document.resource();
        Map<String, Object> extensions = (Map<String, Object>) redactor.redactValue(
                document.extensions());
        List<OkfKnowledgeDocument.Source> sources = document.sources().stream()
                .filter(source -> !redactor.containsSensitiveData(source.resource()))
                .map(source -> new OkfKnowledgeDocument.Source(
                        redactor.redact(source.id()), source.resource(), source.lastModified()))
                .toList();
        List<OkfKnowledgeDocument.Verified> verified = document.verified().stream()
                .map(item -> new OkfKnowledgeDocument.Verified(
                        redactor.redact(item.by()), item.at()))
                .toList();
        return new OkfKnowledgeDocument(
                document.type(),
                redactor.redact(document.title()),
                redactor.redact(document.description()),
                resource,
                new OkfKnowledgeDocument.Generated(
                        redactor.redact(document.generated().by()), document.generated().at()),
                verified,
                document.status(),
                document.staleAfter(),
                sources,
                extensions,
                redactor.redact(document.body()));
    }

    private static OkfKnowledgeDocument copy(
            OkfKnowledgeDocument document,
            String resource,
            Map<String, Object> extensions,
            String body
    ) {
        return new OkfKnowledgeDocument(
                document.type(), document.title(), document.description(), resource,
                document.generated(), document.verified(), document.status(),
                document.staleAfter(), document.sources(), extensions, body);
    }

    private String renderIndex(
            OkfBundleScope scope,
            Instant generatedAt,
            List<IndexEntry> entries,
            int omittedSourceCount
    ) {
        StringBuilder markdown = new StringBuilder()
                .append("---\nokf_version: \"0.2\"\ngenerated_at: \"")
                .append(generatedAt)
                .append("\"\nx-cyrene-bundle-kind: ")
                .append(scope.kind().name().toLowerCase(Locale.ROOT))
                .append("\nx-cyrene-omitted-source-count: ")
                .append(omittedSourceCount)
                .append("\n---\n\n# Knowledge Index\n");
        for (IndexEntry entry : entries) {
            KnowledgeConcept concept = entry.concept();
            KnowledgeRevision revision = entry.revision();
            markdown.append("\n- [")
                    .append(markdownText(redactor.redact(revision.title())))
                    .append("](").append(entry.path()).append(") — ")
                    .append(concept.conceptType().displayName())
                    .append(" · ").append(concept.status().storageValue());
            if (revision.description() != null) {
                markdown.append("\n  ").append(markdownText(
                        redactor.redact(revision.description())));
            }
            for (KnowledgeLink link : entry.links()) {
                markdown.append("\n  - ")
                        .append(link.linkType().name().toLowerCase(Locale.ROOT))
                        .append(": `").append(redactor.redact(link.toConceptId())).append('`');
            }
        }
        return markdown.append('\n').toString();
    }

    private String renderLog(
            OkfBundleScope scope,
            Instant generatedAt,
            LogResult result
    ) {
        StringBuilder markdown = new StringBuilder()
                .append("---\nokf_version: \"0.2\"\ngenerated_at: \"")
                .append(generatedAt)
                .append("\"\nx-cyrene-bundle-kind: ")
                .append(scope.kind().name().toLowerCase(Locale.ROOT))
                .append("\nx-cyrene-log-truncated: ").append(result.truncated())
                .append("\n---\n\n# Update Log\n");
        for (LogEvent event : result.events()) {
            markdown.append("\n- ").append(event.at()).append(" — ")
                    .append(event.kind()).append(" — `")
                    .append(redactor.redact(event.conceptId())).append("` — ")
                    .append(markdownText(redactor.redact(event.message())));
        }
        return markdown.append('\n').toString();
    }

    private static List<KnowledgeConceptType> conceptTypes(OkfBundleScope scope) {
        return switch (scope.kind()) {
            case USER -> List.of(
                    KnowledgeConceptType.USER_PREFERENCE,
                    KnowledgeConceptType.USER_EPISODE);
            case TENANT_OPERATION, GLOBAL_OPERATION -> List.of(
                    KnowledgeConceptType.OPERATION_PLAYBOOK);
            case COLLECTION -> List.of(KnowledgeConceptType.SOURCE_DOCUMENT);
            case GRAPH -> List.of(
                    KnowledgeConceptType.GRAPH_SCHEMA,
                    KnowledgeConceptType.GRAPH_SPACE);
        };
    }

    private PageResponse<KnowledgeConcept> scopedPage(
            OkfBundleScope scope,
            KnowledgeConceptType conceptType,
            KnowledgeConceptCursor cursor
    ) {
        return switch (scope.kind()) {
            case USER -> repository.findPage(
                    scope.tenantId(), scope.userId(), KnowledgeNamespaceType.USER_MEMORY,
                    conceptType, null, cursor, pageSize);
            case TENANT_OPERATION, GLOBAL_OPERATION -> repository.findPage(
                    scope.tenantId(), null, KnowledgeNamespaceType.OPERATION_MEMORY,
                    conceptType, null, cursor, pageSize);
            case COLLECTION -> repository.findPageInNamespace(
                    scope.tenantId(), KnowledgeNamespaceType.COLLECTION, scope.namespaceKey(),
                    conceptType, null, cursor, pageSize);
            case GRAPH -> repository.findPageInNamespace(
                    null, KnowledgeNamespaceType.GRAPH,
                    scope.conceptNamespaceKey(conceptType),
                    conceptType, null, cursor, pageSize);
        };
    }

    private static KnowledgeConceptCursor conceptCursor(List<KnowledgeConcept> items) {
        KnowledgeConcept last = last(items);
        return new KnowledgeConceptCursor(last.updatedAt(), last.id());
    }

    private static String documentPath(KnowledgeConcept concept) {
        return "concepts/" + concept.conceptType().name().toLowerCase(Locale.ROOT)
                .replace('_', '-') + "/" + concept.id() + ".md";
    }

    private static String relativePath(String bundlePath) {
        return "../../" + bundlePath;
    }

    private static String markdownText(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r", " ").replace("\n", " ")
                .replace("[", "\\[").replace("]", "\\]")
                .replace("(", "\\(").replace(")", "\\)");
    }

    private static <T> T last(List<T> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalStateException("Paged result advertised more data without an item");
        }
        return items.get(items.size() - 1);
    }

    private static void requireAssociationBound(List<?> items, String association) {
        if (items.size() > MAX_ASSOCIATIONS_PER_REVISION) {
            throw new IllegalStateException(
                    "OKF export exceeds the " + association + " limit");
        }
    }

    private record IndexEntry(
            KnowledgeConcept concept,
            KnowledgeRevision revision,
            String path,
            List<KnowledgeLink> links
    ) {
    }

    private record LogEvent(Instant at, String kind, String conceptId, String message) {
    }

    private record LogResult(List<LogEvent> events, boolean truncated) {
    }
}
