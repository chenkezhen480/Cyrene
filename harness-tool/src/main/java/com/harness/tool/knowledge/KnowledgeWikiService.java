package com.harness.tool.knowledge;

import com.harness.core.knowledge.*;
import com.harness.core.model.PageResponse;
import com.harness.tool.knowledge.authority.*;
import com.harness.tool.rag.VectorStore;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/** Management of discovery cards; authoritative versions and projection changes are committed together. */
public final class KnowledgeWikiService {
    private final KnowledgeRepository repository;
    private final VectorStore vectorStore;

    public KnowledgeWikiService(KnowledgeRepository repository, VectorStore vectorStore) {
        this.repository = Objects.requireNonNull(repository);
        this.vectorStore = Objects.requireNonNull(vectorStore);
    }

    public PageResponse<WikiCard> page(String tenantId, String userId, KnowledgeConceptType type,
            String collection, KnowledgeConceptCursor cursor, int limit, Predicate<KnowledgeHead> authorized) {
        KnowledgeNamespaceType namespace = namespace(type);
        String owner = type == KnowledgeConceptType.USER_EPISODE ? required(userId, "userId", 128) : null;
        String tenant = type == KnowledgeConceptType.GRAPH_SCHEMA || type == KnowledgeConceptType.GRAPH_SPACE ? null : tenantId;
        var page = type == KnowledgeConceptType.SOURCE_DOCUMENT && collection != null && !collection.isBlank()
                ? repository.findPageInNamespace(tenant, namespace, collection.trim(), type, KnowledgeStatus.STABLE, cursor, limit)
                : repository.findPage(tenant, owner, namespace, type, KnowledgeStatus.STABLE, cursor, limit);
        var cards = new ArrayList<WikiCard>();
        for (var concept : page.items()) {
            var head = repository.findAuthorityById(concept.id()).orElseThrow();
            if (authorized.test(head)) cards.add(card(head));
        }
        return new PageResponse<>(cards, page.pageInfo());
    }

    public WikiCard get(String conceptId, Predicate<KnowledgeHead> authorized) {
        return card(authorizedHead(conceptId, authorized));
    }

    public WikiCard update(String conceptId, String expectedRevisionId, String title, String summary,
            String editor, Predicate<KnowledgeHead> authorized) {
        String safeTitle = required(title, "title", 512);
        String safeSummary = required(summary, "summary", 2048);
        AtomicReference<WikiCard> result = new AtomicReference<>();
        AtomicReference<String> copiedRevision = new AtomicReference<>();
        AtomicReference<String> copiedCollection = new AtomicReference<>();
        try {
            repository.withAuthorityLock(conceptId, () -> {
                var head = authorizedHead(conceptId, authorized);
                if (!Objects.equals(expectedRevisionId, head.currentVersion()))
                    throw new IllegalStateException("Wiki has changed; reload it before saving");
                var snapshot = repository.findSnapshot(head.currentVersion());
                var previous = snapshot.revision();
                var current = head.concept();
                Instant now = Instant.now();
                long version = current.version() + 1;
                var metadata = new LinkedHashMap<>(previous.metadata());
                metadata.put("previousRevisionId", previous.id());
                metadata.put("wikiEditedAt", now.toString());
                metadata.put("wikiEditId", UUID.randomUUID().toString());
                String hash = KnowledgeIdentity.sha256(previous.body() + safeTitle + safeSummary + metadata.get("wikiEditId"));
                var revision = new KnowledgeRevision(KnowledgeIdentity.revisionId(conceptId, version, hash),
                        conceptId, version, safeTitle, safeSummary, previous.body(),
                        "cyrene-wiki-editor/" + required(editor, "editor", 128), now, hash, metadata, now);
                if (current.conceptType() == KnowledgeConceptType.SOURCE_DOCUMENT) {
                    copiedRevision.set(revision.id());
                    copiedCollection.set(current.namespaceKey());
                    vectorStore.copyDocumentRevision(current.namespaceKey(), conceptId, previous.id(), revision.id());
                }
                var concept = new KnowledgeConcept(conceptId, current.tenantId(), current.userId(), current.namespaceType(),
                        current.namespaceKey(), current.conceptType(), current.logicalKey(), current.status(),
                        revision.id(), version, current.staleAfter(), current.createdAt(), now);
                var sources = snapshot.sources().stream().map(source -> new KnowledgeSource(revision.id(), source.sourceType(),
                        source.sourceId(), source.sourceResource(), source.observedAt(), source.createdAt())).toList();
                var index = new KnowledgeIndexTask(null, conceptId, revision.id(), KnowledgeIndexOperation.UPSERT_CURRENT,
                        KnowledgeIndexTaskStatus.PENDING, 0, now, null, null, null, now);
                repository.commitChanges(List.of(new KnowledgeRevisionChange(concept, current.version(), revision,
                        sources, List.of(), List.of(), List.of(index))));
                result.set(card(new KnowledgeHead(concept, revision), revision));
            });
            return result.get();
        } catch (RuntimeException failure) {
            if (copiedRevision.get() != null) {
                try { vectorStore.deleteDocumentRevision(copiedCollection.get(), conceptId, copiedRevision.get()); }
                catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }

    public String exportMarkdown() {
        // ponytail: exports are assembled in memory; stage a streamed file if their size approaches the heap budget.
        var text = new StringBuilder("# LLM Wiki\n\n");
        for (var type : List.of(KnowledgeConceptType.SOURCE_DOCUMENT, KnowledgeConceptType.GRAPH_SCHEMA,
                KnowledgeConceptType.GRAPH_SPACE, KnowledgeConceptType.USER_EPISODE, KnowledgeConceptType.OPERATION_PLAYBOOK)) {
            KnowledgeConceptCursor cursor = null;
            boolean graphHeadingWritten = false;
            while (true) {
                var page = repository.findManagementPage(type, KnowledgeStatus.STABLE, cursor, 100);
                for (var concept : page.items()) {
                    var head = repository.findAuthorityById(concept.id()).orElseThrow();
                    if (head.concept().status() != KnowledgeStatus.STABLE)
                        throw new IllegalStateException("Wiki changed during export; retry the export");
                    var card = card(head);
                    if (type == KnowledgeConceptType.GRAPH_SPACE) {
                        if (!graphHeadingWritten) { text.append("\n## Graph bindings\n\n"); graphHeadingWritten = true; }
                        if (!(card.route().get("graphId") instanceof String graphId) || graphId.isBlank()
                                || !(card.route().get("schemaId") instanceof String schemaId) || schemaId.isBlank())
                            throw new IllegalStateException("Graph Wiki is missing its graph/schema binding");
                        text.append("- Graph: `").append(graphId)
                                .append("`; Schema: `").append(schemaId)
                                .append("`; Wiki ID: `").append(card.conceptId()).append("`\n");
                    } else text.append("\n---\n\n").append(markdown(card));
                }
                if (!page.pageInfo().hasMore()) break;
                var next = parseCursor(page.pageInfo().nextCursor());
                if (next == null || next.equals(cursor)) throw new IllegalStateException("Wiki export pagination did not advance");
                cursor = next;
            }
        }
        return text.toString();
    }

    public static KnowledgeConceptCursor parseCursor(String text) {
        if (text == null || text.isBlank()) return null;
        int split = text.lastIndexOf('|');
        if (split < 1) throw new IllegalArgumentException("Invalid Wiki cursor");
        try { return new KnowledgeConceptCursor(Instant.parse(text.substring(0, split)), text.substring(split + 1)); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid Wiki cursor", invalid); }
    }

    public String markdown(WikiCard card) {
        String text = "# " + card.title() + "\n\n" + card.summary() + "\n\n"
                + "- Wiki ID: `" + card.conceptId() + "`\n- Type: `" + card.conceptType() + "`\n"
                + "- Revision: `" + card.revisionId() + "`\n- Version: " + card.version() + "\n";
        if (card.namespaceKey() != null) text += "- Namespace: `" + card.namespaceKey() + "`\n";
        if (!card.capability().isBlank()) text += "\n## Graph Capability / Schema Card\n\n" + card.capability() + "\n";
        return text;
    }

    private KnowledgeHead authorizedHead(String id, Predicate<KnowledgeHead> authorized) {
        var head = repository.findAuthorityById(required(id, "conceptId", 64)).orElseThrow(
                () -> new NoSuchElementException("Wiki not found"));
        namespace(head.concept().conceptType());
        if (!authorized.test(head)) throw new SecurityException("Wiki exceeds the authorized scope");
        if (head.concept().status() != KnowledgeStatus.STABLE) throw new IllegalStateException("Wiki is not active");
        return head;
    }

    private WikiCard card(KnowledgeHead head) {
        return card(head, repository.findMetadataSnapshot(head.currentVersion()).revision());
    }

    private WikiCard card(KnowledgeHead head, KnowledgeRevision revision) {
        if (!Objects.equals(head.currentVersion(), revision.id()) || !head.concept().id().equals(revision.conceptId()))
            throw new IllegalStateException("Wiki projection differs from its current authority");
        var concept = head.concept();
        boolean graph = concept.conceptType() == KnowledgeConceptType.GRAPH_SCHEMA || concept.conceptType() == KnowledgeConceptType.GRAPH_SPACE;
        return new WikiCard(concept.id(), concept.conceptType(), concept.namespaceKey(), revision.id(), concept.version(),
                revision.title(), revision.description() == null ? "" : revision.description(),
                graph ? revision.body() : "", head.routeData(), concept.updatedAt());
    }

    private static KnowledgeNamespaceType namespace(KnowledgeConceptType type) {
        if (type == null) throw new IllegalArgumentException("Wiki type is required");
        return switch (type) {
            case SOURCE_DOCUMENT -> KnowledgeNamespaceType.COLLECTION;
            case GRAPH_SCHEMA, GRAPH_SPACE -> KnowledgeNamespaceType.GRAPH;
            case USER_EPISODE -> KnowledgeNamespaceType.USER_MEMORY;
            case OPERATION_PLAYBOOK -> KnowledgeNamespaceType.OPERATION_MEMORY;
            case USER_PREFERENCE -> throw new IllegalArgumentException("User preferences are not Wiki cards");
        };
    }

    private static String required(String text, String field, int max) {
        if (text == null || text.isBlank() || text.length() > max) throw new IllegalArgumentException(field + " is required and must be at most " + max + " characters");
        return text.strip();
    }

    public record WikiCard(String conceptId, KnowledgeConceptType conceptType, String namespaceKey,
            String revisionId, long version, String title, String summary, String capability,
            Map<String, Object> route, Instant updatedAt) {}
}
