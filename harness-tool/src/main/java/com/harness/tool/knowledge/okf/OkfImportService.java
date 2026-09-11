package com.harness.tool.knowledge.okf;

import com.harness.core.knowledge.KnowledgeConcept;
import com.harness.core.knowledge.KnowledgeConceptType;
import com.harness.core.knowledge.KnowledgeIdentity;
import com.harness.core.knowledge.KnowledgeIndexOperation;
import com.harness.core.knowledge.KnowledgeIndexTask;
import com.harness.core.knowledge.KnowledgeIndexTaskStatus;
import com.harness.core.knowledge.KnowledgeLink;
import com.harness.core.knowledge.KnowledgeLinkType;
import com.harness.core.knowledge.KnowledgeNamespaceType;
import com.harness.core.knowledge.KnowledgeRevision;
import com.harness.core.knowledge.KnowledgeSource;
import com.harness.core.knowledge.KnowledgeSourceType;
import com.harness.core.knowledge.KnowledgeStatus;
import com.harness.core.knowledge.OkfKnowledgeDocument;
import com.harness.tool.knowledge.authority.KnowledgeHead;
import com.harness.tool.knowledge.authority.KnowledgeRepository;
import com.harness.tool.knowledge.authority.KnowledgeRevisionChange;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Review-first OKF importer. Imported content is always committed as Draft. */
public final class OkfImportService {

    private static final String IMPORTER_ID = "cyrene-okf-import/v1";
    private static final int MAX_DOCUMENTS = 100;
    private static final Pattern OKF_VERSION = Pattern.compile(
            "(?m)^okf_version:\\s*[\"']?0\\.2[\"']?\\s*$");

    private final KnowledgeRepository repository;
    private final OkfImportSourceResolver sourceResolver;
    private final OkfMarkdownCodec codec;
    private final OkfSensitiveDataRedactor redactor;
    private final Clock clock;

    public OkfImportService(
            KnowledgeRepository repository,
            OkfImportSourceResolver sourceResolver,
            Clock clock
    ) {
        this(repository, sourceResolver, new OkfMarkdownCodec(),
                new OkfSensitiveDataRedactor(), clock);
    }

    OkfImportService(
            KnowledgeRepository repository,
            OkfImportSourceResolver sourceResolver,
            OkfMarkdownCodec codec,
            OkfSensitiveDataRedactor redactor,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.sourceResolver = Objects.requireNonNull(sourceResolver, "sourceResolver");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.redactor = Objects.requireNonNull(redactor, "redactor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public OkfImportReview review(OkfBundleScope scope, Map<String, String> files) {
        Objects.requireNonNull(scope, "scope");
        validateBundleFiles(files);
        List<OkfImportReview.Entry> entries = new ArrayList<>();
        Set<String> conceptIds = new LinkedHashSet<>();
        files.entrySet().stream()
                .filter(entry -> !reserved(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> entries.add(reviewDocument(
                        scope, entry.getKey(), entry.getValue(), conceptIds)));
        return new OkfImportReview(entries);
    }

    public OkfImportResult importApproved(
            OkfBundleScope scope,
            Map<String, String> files,
            Collection<String> approvedPaths
    ) {
        Set<String> approved = normalizeApprovedPaths(approvedPaths);
        OkfImportReview review = review(scope, files);
        Map<String, OkfImportReview.Entry> byPath = new HashMap<>();
        review.entries().forEach(entry -> byPath.put(entry.path(), entry));
        if (!byPath.keySet().containsAll(approved)) {
            throw new IllegalArgumentException("approvedPaths contains a path outside the reviewed bundle");
        }

        List<OkfImportReview.Entry> selected = approved.stream()
                .map(byPath::get)
                .filter(entry -> entry.action() != OkfImportReview.Action.UNCHANGED)
                .toList();
        Optional<OkfImportReview.Entry> rejected = selected.stream()
                .filter(entry -> entry.action() == OkfImportReview.Action.REJECTED)
                .findFirst();
        if (rejected.isPresent()) {
            throw new IllegalArgumentException(
                    "Cannot import rejected OKF document " + rejected.get().path() + ": "
                            + String.join("; ", rejected.get().issues()));
        }

        Instant now = clock.instant();
        Set<String> selectedIds = new LinkedHashSet<>();
        selected.forEach(entry -> {
            if (!selectedIds.add(entry.conceptId())) {
                throw new IllegalArgumentException(
                        "Multiple approved documents target Concept " + entry.conceptId());
            }
        });
        Map<String, KnowledgeHead> linkedHeads = loadLinkedHeads(scope, selected, selectedIds);
        List<KnowledgeRevisionChange> changes = selected.stream()
                .map(entry -> change(scope, entry, linkedHeads, selectedIds, now))
                .toList();
        if (!changes.isEmpty()) {
            repository.commitChanges(changes);
        }
        return new OkfImportResult(changes.size(), changes.stream()
                .map(change -> change.concept().id()).toList());
    }

    private OkfImportReview.Entry reviewDocument(
            OkfBundleScope scope,
            String path,
            String markdown,
            Set<String> conceptIds
    ) {
        List<String> issues = new ArrayList<>();
        OkfKnowledgeDocument document;
        try {
            document = codec.read(markdown);
        } catch (IllegalArgumentException e) {
            return rejected(path, List.of(e.getMessage()), null);
        }
        if (redactor.containsSensitiveData(markdown)) {
            issues.add("document contains credential-like data");
        }
        KnowledgeConceptType conceptType = conceptType(document.type());
        if (conceptType == null) {
            issues.add("unsupported Cyrene Concept type: " + document.type());
        } else if (!typeAllowed(scope, conceptType)) {
            issues.add("Concept type is outside the requested bundle boundary: " + document.type());
        }
        if (document.title() == null) {
            issues.add("title is required for Cyrene import");
        }
        if (document.sources().isEmpty()) {
            issues.add("at least one source is required for import");
        }
        if (document.resource() != null) {
            OkfKnowledgeDocument.Source resource = new OkfKnowledgeDocument.Source(
                    null, document.resource(), null);
            if (!document.resource().startsWith("cyrene://")
                    || !sourceResolver.existsAndReadable(scope, resource)) {
                issues.add("document resource does not exist or is not readable: "
                        + document.resource());
            }
        }
        for (OkfKnowledgeDocument.Source source : document.sources()) {
            if (!source.resource().startsWith("cyrene://")) {
                issues.add("source resource is not a resolvable cyrene URI: " + source.resource());
            } else if (!sourceResolver.existsAndReadable(scope, source)) {
                issues.add("source does not exist or is not readable: " + source.resource());
            }
        }
        validateLinks(document.extensions().get("x-cyrene-links"), issues);
        if (conceptType == null || !issues.isEmpty()) {
            return rejected(path, issues, document);
        }

        String logicalKey = extensionString(document, "x-cyrene-logical-key", false, issues);
        String suppliedConceptId = extensionString(document, "x-cyrene-concept-id", false, issues);
        String conceptId = resolveConceptId(scope, conceptType, logicalKey,
                suppliedConceptId, document, issues);
        if (conceptId == null || !conceptIds.add(conceptId)) {
            if (conceptId != null) {
                issues.add("multiple documents target the same Concept");
            }
            return rejected(path, issues, document);
        }

        Optional<KnowledgeHead> existing = repository.findById(conceptId);
        if (existing.isPresent()) {
            KnowledgeConcept concept = existing.get().concept();
            if (!scope.permits(concept)) {
                issues.add("existing Concept is outside the import scope");
            }
            if (concept.conceptType() != conceptType
                    || !Objects.equals(concept.logicalKey(), logicalKey)) {
                issues.add("existing Concept identity does not match the document schema");
            }
        }
        if (!issues.isEmpty()) {
            return rejected(path, issues, document);
        }
        if (existing.isPresent() && sameImportedDocument(
                existing.get().currentRevision(), document)) {
            return new OkfImportReview.Entry(
                    path, OkfImportReview.Action.UNCHANGED, conceptId,
                    existing.get().concept().currentRevisionId(), List.of(), document);
        }
        return new OkfImportReview.Entry(
                path,
                existing.isPresent()
                        ? OkfImportReview.Action.REVISE_REQUIRES_APPROVAL
                        : OkfImportReview.Action.CREATE_REQUIRES_APPROVAL,
                conceptId,
                existing.map(head -> head.concept().currentRevisionId()).orElse(null),
                List.of(),
                document);
    }

    private KnowledgeRevisionChange change(
            OkfBundleScope scope,
            OkfImportReview.Entry entry,
            Map<String, KnowledgeHead> linkedHeads,
            Set<String> selectedIds,
            Instant now
    ) {
        OkfKnowledgeDocument document = entry.document();
        KnowledgeConceptType conceptType = Objects.requireNonNull(conceptType(document.type()));
        String logicalKey = extensionString(document, "x-cyrene-logical-key", false,
                new ArrayList<>());
        Optional<KnowledgeHead> existing = repository.findById(entry.conceptId());
        String currentRevisionId = existing.map(head -> head.concept().currentRevisionId()).orElse(null);
        if (!Objects.equals(currentRevisionId, entry.expectedRevisionId())) {
            throw new IllegalStateException(
                    "Knowledge Concept changed after import review: " + entry.conceptId());
        }

        long expectedVersion = existing.map(head -> head.concept().version()).orElse(0L);
        long revisionNumber = expectedVersion + 1;
        String contentHash = KnowledgeIdentity.sha256(codec.write(document));
        String revisionId = KnowledgeIdentity.revisionId(
                entry.conceptId(), revisionNumber, contentHash);
        KnowledgeConcept concept = new KnowledgeConcept(
                entry.conceptId(), conceptTenantId(scope),
                scope.kind() == OkfBundleScope.Kind.USER ? scope.userId() : null,
                namespace(scope), scope.conceptNamespaceKey(conceptType),
                conceptType, logicalKey, KnowledgeStatus.DRAFT,
                revisionId, revisionNumber, document.staleAfter(),
                existing.map(head -> head.concept().createdAt()).orElse(now), now);
        KnowledgeRevision revision = new KnowledgeRevision(
                revisionId, concept.id(), revisionNumber, document.title(),
                document.description(), document.body(), IMPORTER_ID, now, contentHash,
                importMetadata(document), now);
        List<KnowledgeSource> sources = document.sources().stream()
                .map(source -> new KnowledgeSource(
                        revisionId, sourceType(source.resource()), sourceId(source),
                        source.resource(),
                        source.lastModified() == null ? document.generated().at() : source.lastModified(),
                        now))
                .toList();
        List<KnowledgeLink> links = links(document, concept.id(), linkedHeads, selectedIds, now);
        List<KnowledgeIndexTask> indexTasks = indexTasks(conceptType, concept.id(), revisionId, now);
        return new KnowledgeRevisionChange(
                concept, expectedVersion, revision, sources, List.of(), links, indexTasks);
    }

    private Map<String, KnowledgeHead> loadLinkedHeads(
            OkfBundleScope scope,
            List<OkfImportReview.Entry> selected,
            Set<String> selectedIds
    ) {
        LinkedHashSet<String> linkedIds = new LinkedHashSet<>();
        selected.forEach(entry -> linkedIds.addAll(linkTargets(entry.document())));
        linkedIds.removeAll(selectedIds);
        if (linkedIds.size() > 100) {
            throw new IllegalArgumentException("OKF import links exceed the lookup limit");
        }
        Map<String, KnowledgeHead> heads = repository.findByIds(List.copyOf(linkedIds));
        for (String linkedId : linkedIds) {
            KnowledgeHead head = heads.get(linkedId);
            if (head == null || !scope.permits(head.concept())) {
                throw new SecurityException("Linked Concept is outside the import scope: " + linkedId);
            }
        }
        return heads;
    }

    private List<KnowledgeLink> links(
            OkfKnowledgeDocument document,
            String conceptId,
            Map<String, KnowledgeHead> linkedHeads,
            Set<String> selectedIds,
            Instant now
    ) {
        Object value = document.extensions().get("x-cyrene-links");
        if (value == null) {
            return List.of();
        }
        List<KnowledgeLink> links = new ArrayList<>();
        for (Object item : (List<?>) value) {
            Map<?, ?> map = (Map<?, ?>) item;
            String target = String.valueOf(map.get("concept_id"));
            if (!selectedIds.contains(target) && !linkedHeads.containsKey(target)) {
                throw new IllegalArgumentException("Linked Concept is missing: " + target);
            }
            KnowledgeLinkType type = KnowledgeLinkType.valueOf(
                    String.valueOf(map.get("type")).toUpperCase(Locale.ROOT));
            links.add(new KnowledgeLink(conceptId, target, type, now));
        }
        return List.copyOf(links);
    }

    private List<KnowledgeIndexTask> indexTasks(
            KnowledgeConceptType conceptType,
            String conceptId,
            String revisionId,
            Instant now
    ) {
        if (conceptType == KnowledgeConceptType.USER_PREFERENCE) {
            return List.of();
        }
        return List.of(new KnowledgeIndexTask(
                null, conceptId, revisionId,
                KnowledgeIndexOperation.UPSERT_CURRENT, KnowledgeIndexTaskStatus.PENDING,
                0, now, null, null, null, now));
    }

    private static Map<String, Object> importMetadata(OkfKnowledgeDocument document) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        if (document.resource() != null) {
            metadata.put("resourceUri", document.resource());
        }
        metadata.put("okfStatus", document.status().storageValue());
        metadata.put("okfGenerated", Map.of(
                "by", document.generated().by(), "at", document.generated().at().toString()));
        if (!document.verified().isEmpty()) {
            metadata.put("okfVerified", document.verified().stream()
                    .map(value -> Map.of("by", value.by(), "at", value.at().toString()))
                    .toList());
        }
        metadata.put("okfExtensions", document.extensions());
        return Map.copyOf(metadata);
    }

    private static String resolveConceptId(
            OkfBundleScope scope,
            KnowledgeConceptType conceptType,
            String logicalKey,
            String suppliedConceptId,
            OkfKnowledgeDocument document,
            List<String> issues
    ) {
        String derived;
        switch (conceptType) {
            case USER_PREFERENCE -> {
                if (logicalKey == null) {
                    issues.add("User Preference requires x-cyrene-logical-key");
                    return null;
                }
                derived = KnowledgeIdentity.preferenceConceptId(
                        scope.tenantId(), scope.userId(), logicalKey);
            }
            case USER_EPISODE -> derived = suppliedConceptId == null
                    ? KnowledgeIdentity.episodeConceptId(
                    scope.tenantId(), scope.userId(),
                    "okf:" + KnowledgeIdentity.sha256(document.body()), 0)
                    : suppliedConceptId;
            case OPERATION_PLAYBOOK -> {
                if (logicalKey == null) {
                    issues.add("Operation Playbook requires x-cyrene-logical-key");
                    return null;
                }
                derived = KnowledgeIdentity.playbookConceptId(scope.tenantId(), logicalKey);
            }
            case SOURCE_DOCUMENT -> {
                if (logicalKey == null) {
                    issues.add("Source Document requires x-cyrene-logical-key");
                    return null;
                }
                derived = KnowledgeIdentity.sourceDocumentConceptId(
                        scope.tenantId(), scope.namespaceKey(), logicalKey);
            }
            default -> {
                if (logicalKey == null) {
                    issues.add(document.type() + " requires x-cyrene-logical-key");
                    return null;
                }
                derived = KnowledgeIdentity.wikiConceptId(
                        conceptTenantId(scope), conceptType,
                        scope.conceptNamespaceKey(conceptType), logicalKey);
            }
        }
        if (suppliedConceptId != null && conceptType != KnowledgeConceptType.USER_EPISODE
                && !suppliedConceptId.equals(derived)) {
            issues.add("x-cyrene-concept-id does not match the deterministic scope identity");
        }
        return derived;
    }

    private boolean sameImportedDocument(
            KnowledgeRevision current,
            OkfKnowledgeDocument document
    ) {
        return current != null
                && IMPORTER_ID.equals(current.generatedBy())
                && current.contentHash().equals(KnowledgeIdentity.sha256(codec.write(document)));
    }

    private static KnowledgeConceptType conceptType(String type) {
        for (KnowledgeConceptType value : KnowledgeConceptType.values()) {
            if (value.displayName().equals(type)) {
                return value;
            }
        }
        return null;
    }

    private static boolean typeAllowed(
            OkfBundleScope scope,
            KnowledgeConceptType conceptType
    ) {
        return switch (scope.kind()) {
            case USER -> conceptType.isUserOwned();
            case TENANT_OPERATION, GLOBAL_OPERATION ->
                    conceptType == KnowledgeConceptType.OPERATION_PLAYBOOK;
            case COLLECTION -> conceptType == KnowledgeConceptType.SOURCE_DOCUMENT;
            case GRAPH -> conceptType == KnowledgeConceptType.GRAPH_SCHEMA
                    || conceptType == KnowledgeConceptType.GRAPH_SPACE;
        };
    }

    private static String extensionString(
            OkfKnowledgeDocument document,
            String key,
            boolean required,
            List<String> issues
    ) {
        Object value = document.extensions().get(key);
        if (value == null) {
            if (required) {
                issues.add(key + " is required");
            }
            return null;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            issues.add(key + " must be a non-blank string");
            return null;
        }
        return text.trim();
    }

    private static void validateLinks(Object value, List<String> issues) {
        if (value == null) {
            return;
        }
        if (!(value instanceof List<?> list)) {
            issues.add("x-cyrene-links must be a list");
            return;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)
                    || !(map.get("concept_id") instanceof String conceptId)
                    || conceptId.isBlank()
                    || !(map.get("type") instanceof String type)) {
                issues.add("x-cyrene-links contains an invalid relation");
                continue;
            }
            try {
                KnowledgeLinkType.valueOf(type.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                issues.add("x-cyrene-links contains an unsupported relation type: " + type);
            }
        }
    }

    private static Set<String> linkTargets(OkfKnowledgeDocument document) {
        Object value = document.extensions().get("x-cyrene-links");
        if (!(value instanceof List<?> list)) {
            return Set.of();
        }
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map && map.get("concept_id") instanceof String target) {
                targets.add(target);
            }
        }
        return Set.copyOf(targets);
    }

    private static KnowledgeSourceType sourceType(String resource) {
        if (resource.startsWith("cyrene://session/")) {
            return KnowledgeSourceType.SESSION_MESSAGE;
        }
        if (resource.startsWith("cyrene://trace/")) {
            return KnowledgeSourceType.TRACE;
        }
        if (resource.startsWith("cyrene://feedback/")) {
            return KnowledgeSourceType.USER_FEEDBACK;
        }
        if (resource.startsWith("cyrene://artifacts/")) {
            return KnowledgeSourceType.KNOWLEDGE_ARTIFACT;
        }
        if (resource.startsWith("cyrene://knowledge/")) {
            return KnowledgeSourceType.KNOWLEDGE_CONCEPT;
        }
        return KnowledgeSourceType.BUSINESS_RESULT;
    }

    private static String sourceId(OkfKnowledgeDocument.Source source) {
        return source.id() == null
                ? KnowledgeIdentity.sha256(source.resource()).substring(0, 32)
                : source.id();
    }

    private static KnowledgeNamespaceType namespace(OkfBundleScope scope) {
        return switch (scope.kind()) {
            case USER -> KnowledgeNamespaceType.USER_MEMORY;
            case TENANT_OPERATION, GLOBAL_OPERATION -> KnowledgeNamespaceType.OPERATION_MEMORY;
            case COLLECTION -> KnowledgeNamespaceType.COLLECTION;
            case GRAPH -> KnowledgeNamespaceType.GRAPH;
        };
    }

    private static String conceptTenantId(OkfBundleScope scope) {
        return scope.kind() == OkfBundleScope.Kind.GRAPH ? null : scope.tenantId();
    }

    private static OkfImportReview.Entry rejected(
            String path,
            List<String> issues,
            OkfKnowledgeDocument document
    ) {
        return new OkfImportReview.Entry(
                path, OkfImportReview.Action.REJECTED, null, null, issues, document);
    }

    private static void validateBundleFiles(Map<String, String> files) {
        if (files == null || !files.containsKey("index.md") || !files.containsKey("log.md")) {
            throw new IllegalArgumentException("OKF bundle requires index.md and log.md");
        }
        String index = files.get("index.md");
        if (index == null || !OKF_VERSION.matcher(index.replace("\r\n", "\n")).find()) {
            throw new IllegalArgumentException("index.md must declare okf_version 0.2");
        }
        long documentCount = files.keySet().stream().filter(path -> !reserved(path)).count();
        if (documentCount > MAX_DOCUMENTS) {
            throw new IllegalArgumentException("OKF bundle exceeds the document limit");
        }
        files.forEach((path, content) -> {
            if (path == null || path.isBlank() || path.startsWith("/") || path.startsWith("\\")
                    || path.contains("..") || path.contains(":")) {
                throw new IllegalArgumentException("Unsafe OKF bundle path: " + path);
            }
            if (content == null) {
                throw new IllegalArgumentException("OKF bundle content is required: " + path);
            }
        });
    }

    private static Set<String> normalizeApprovedPaths(Collection<String> paths) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (paths != null) {
            for (String path : paths) {
                if (path == null || path.isBlank()) {
                    throw new IllegalArgumentException("approvedPaths contains a blank path");
                }
                normalized.add(path.trim());
            }
        }
        return Set.copyOf(normalized);
    }

    private static boolean reserved(String path) {
        return "index.md".equals(path) || "log.md".equals(path);
    }
}
