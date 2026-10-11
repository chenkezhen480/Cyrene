package com.harness.graph.build;

import com.harness.graph.schema.GraphSchemaDefinition;

/** Schema is returned only after authorization of the draft's fixed graph space. */
public record GraphDraftReviewDetails(GraphDraftView draft, GraphSchemaDefinition schema) { }
