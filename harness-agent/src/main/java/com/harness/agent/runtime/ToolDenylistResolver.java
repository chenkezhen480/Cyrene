package com.harness.agent.runtime;

import java.util.Set;

/**
 * Supplies the tool names one tenant identity may not use.
 *
 * <p>Implemented at the server boundary, where the permission table lives; the agent modules
 * only consume the result. An empty set means "nothing is disabled", which is the answer both
 * for an empty stored list and for a tenant with no stored row at all.</p>
 */
@FunctionalInterface
public interface ToolDenylistResolver {

    Set<String> resolve(String tenantId, String identity);

    /** Default used when no permission source is wired. */
    ToolDenylistResolver UNRESTRICTED = (tenantId, identity) -> Set.of();
}
