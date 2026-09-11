package com.harness.tool.knowledge.okf;

import com.harness.core.knowledge.OkfKnowledgeDocument;

/** Confirms that an imported source exists and is readable in the requested scope. */
@FunctionalInterface
public interface OkfImportSourceResolver {

    boolean existsAndReadable(OkfBundleScope scope, OkfKnowledgeDocument.Source source);
}
