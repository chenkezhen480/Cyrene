package com.harness.tool;

import com.harness.core.model.ToolSpec;

import java.util.List;

/**
 * Read-only catalog used by an agent run to discover and resolve tools.
 */
public interface ToolCatalog {

    Tool get(String name);

    List<ToolSpec> getAll();

    boolean contains(String name);

    int size();

    long version();
}
