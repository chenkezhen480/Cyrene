package com.harness.core.model;

/**
 * Lightweight index for a skill, containing only name and description.
 * Used at startup to build the skill catalog without loading full content.
 */
public record SkillIndex(String name, String description, String filePath) {}
