package com.harness.tool.search;

public record SearchCapabilities(
        boolean language,
        boolean dateRange,
        boolean domainFilters
) {
}
