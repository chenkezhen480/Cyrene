package com.harness.core.knowledge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeIdentityTest {

    @Test
    void preferenceAndArtifactIdsAreDeterministicAcrossNullTenantRepresentations() {
        assertThat(KnowledgeIdentity.preferenceConceptId(null, "user-1", "response.verbosity"))
                .isEqualTo(KnowledgeIdentity.preferenceConceptId("  ", "user-1", "response.verbosity"));
        assertThat(KnowledgeIdentity.artifactId(
                null, "docs", KnowledgeArtifactType.SOURCE_FILE, "abc"))
                .isEqualTo(KnowledgeIdentity.artifactId(
                        "", "docs", KnowledgeArtifactType.SOURCE_FILE, "abc"));
    }
}
