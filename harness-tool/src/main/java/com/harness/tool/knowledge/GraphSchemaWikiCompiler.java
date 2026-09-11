package com.harness.tool.knowledge;

import com.harness.graph.schema.GraphSchemaDetails;

/** Keeps the searchable Graph Schema Wiki Concept aligned with the Schema Registry. */
public interface GraphSchemaWikiCompiler {

    void synchronize(GraphSchemaDetails schema);

    void deprecate(String schemaId);
}
