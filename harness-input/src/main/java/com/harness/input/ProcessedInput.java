package com.harness.input;

import com.harness.core.model.AgentMessage;

/** Unified input produced before context enrichment and ReAct execution. */
public record ProcessedInput(String userId, AgentMessage message) {}
