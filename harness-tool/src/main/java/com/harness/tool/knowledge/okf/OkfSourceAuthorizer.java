package com.harness.tool.knowledge.okf;

import com.harness.core.knowledge.KnowledgeConcept;
import com.harness.core.knowledge.KnowledgeSource;

/** Reauthorizes each source before its resource identifier is exported. */
@FunctionalInterface
public interface OkfSourceAuthorizer {

    boolean canExport(OkfBundleScope scope, KnowledgeConcept concept, KnowledgeSource source);

    /** Reauthorize the document-level resource independently from its evidence sources. */
    default boolean canExportResource(
            OkfBundleScope scope,
            KnowledgeConcept concept,
            String resource
    ) {
        return false;
    }
}
