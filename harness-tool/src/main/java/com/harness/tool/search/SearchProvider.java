package com.harness.tool.search;

public interface SearchProvider {

    String id();

    SearchCapabilities capabilities();

    SearchResponse search(SearchRequest request);

    SearchHealth health();
}
