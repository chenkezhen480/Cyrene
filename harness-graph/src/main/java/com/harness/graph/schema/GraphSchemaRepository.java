package com.harness.graph.schema;

import java.util.List;
import java.util.Optional;

public interface GraphSchemaRepository {

    List<StoredGraphSchema> list();

    Optional<StoredGraphSchema> find(String schemaId);

    void save(StoredGraphSchema schema);

    void delete(String schemaId);
}
