package com.harness.tool.skill;

import com.harness.core.model.Skill;
import com.harness.core.model.SkillIndex;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages skill indexes with two layers:
 * - persistentIndex: loaded from HARNESS_SKILL_DIR at startup (shared across all sessions)
 * - temporarySkills: registered from user file uploads (session-scoped, isolated by sessionId)
 *
 * Session-scoped data follows session lifecycle:
 * - Created when user uploads a temporary .md skill
 * - Persists across requests within the same session
 * - Cleared when session expires (idle TTL) or explicitly closed
 */
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    private final Map<String, SkillIndex> persistentIndex = new ConcurrentHashMap<>();

    // Session-scoped temporary skills: sessionId → (skillName → Skill)
    private final Map<String, Map<String, Skill>> temporarySkills = new ConcurrentHashMap<>();

    // Last access time per session (for TTL expiration)
    private final Map<String, Long> lastAccess = new ConcurrentHashMap<>();

    private final long sessionTtlMs;

    public SkillRegistry() {
        int ttlHours = EnvConfig.get().getInt(EnvKey.CACHE_SESSION_TTL_HOURS, 12);
        this.sessionTtlMs = (long) ttlHours * 3600 * 1000;
        log.info("[SkillRegistry] Initialized with session TTL={}h", ttlHours);
    }

    /**
     * Scan directory and build persistent index at startup.
     */
    public void loadIndex(Path dir) {
        List<SkillIndex> indexes = SkillLoader.scanIndex(dir);
        for (SkillIndex idx : indexes) {
            persistentIndex.put(idx.name(), idx);
        }
        log.info("Skill index loaded: {} skills from {}", indexes.size(), dir);
    }

    /**
     * Register a temporary skill from file upload (session-scoped).
     */
    public void addTemporary(String sessionId, Skill skill) {
        if (sessionId == null) {
            log.warn("Cannot add temporary skill without sessionId: {}", skill.name());
            return;
        }
        temporarySkills.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                .put(skill.name(), skill);
        touchSession(sessionId);
        log.debug("Temporary skill registered: {} (session={})", skill.name(), sessionId);
    }

    /**
     * Clear all session-scoped temporary skills.
     * Called when session is explicitly closed or expires.
     */
    public void clearSession(String sessionId) {
        if (sessionId != null) {
            Map<String, Skill> removedTemp = temporarySkills.remove(sessionId);
            lastAccess.remove(sessionId);

            int removedCount = removedTemp != null ? removedTemp.size() : 0;
            if (removedCount > 0) {
                log.debug("Cleared {} temporary skills for session {}",
                        removedTemp.size(),
                        sessionId);
            }
        }
    }

    /**
     * Evict expired sessions based on idle TTL.
     * Call periodically from cleanup scheduler.
     */
    public int evictExpired() {
        long now = System.currentTimeMillis();
        int evicted = 0;
        Iterator<Map.Entry<String, Long>> it = lastAccess.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            if (now - entry.getValue() > sessionTtlMs) {
                String sid = entry.getKey();
                clearSession(sid);
                it.remove();
                evicted++;
            }
        }
        if (evicted > 0) {
            log.info("[SkillRegistry] Evicted {} expired sessions (TTL={}h)", evicted, sessionTtlMs / 3600000);
        }
        return evicted;
    }

    /**
     * Lookup skill index by name. Temporary skills (for the given session) take priority.
     * Automatically evicts expired session data.
     */
    public SkillIndex get(String name, String sessionId) {
        // Check temporary skills for this session first
        if (sessionId != null) {
            if (isExpired(sessionId)) {
                clearSession(sessionId);
            } else {
                touchSession(sessionId);
                Map<String, Skill> sessionSkills = temporarySkills.get(sessionId);
                if (sessionSkills != null && sessionSkills.containsKey(name)) {
                    Skill skill = sessionSkills.get(name);
                    return new SkillIndex(skill.name(), skill.description(), null);
                }
            }
        }
        return persistentIndex.get(name);
    }

    /**
     * Get full Skill object. Lookup order: temporary skills, then persistent disk index.
     * Automatically evicts expired session data.
     */
    public Skill getFull(String name, String sessionId) {
        if (sessionId != null) {
            if (isExpired(sessionId)) {
                clearSession(sessionId);
            } else {
                touchSession(sessionId);
                // Check temporary skills
                Map<String, Skill> sessionSkills = temporarySkills.get(sessionId);
                if (sessionSkills != null && sessionSkills.containsKey(name)) {
                    return sessionSkills.get(name);
                }
            }
        }
        // Fall back to persistent index — load from disk
        SkillIndex idx = persistentIndex.get(name);
        if (idx != null) {
            return SkillLoader.loadFull(idx);
        }
        return null;
    }

    /**
     * List all skill indexes (persistent + temporary for the given session).
     */
    public List<SkillIndex> listAll(String sessionId) {
        Map<String, SkillIndex> merged = new LinkedHashMap<>(persistentIndex);
        // Add temporary skills for this session
        if (sessionId != null) {
            Map<String, Skill> sessionSkills = temporarySkills.get(sessionId);
            if (sessionSkills != null) {
                for (Skill skill : sessionSkills.values()) {
                    merged.put(skill.name(), new SkillIndex(skill.name(), skill.description(), null));
                }
            }
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * Get all temporary Skill objects for a session (for sub-agent propagation).
     */
    public List<Skill> getTemporarySkills(String sessionId) {
        if (sessionId == null) return List.of();
        Map<String, Skill> sessionSkills = temporarySkills.get(sessionId);
        if (sessionSkills == null) return List.of();
        return new ArrayList<>(sessionSkills.values());
    }

    /**
     * Bulk register temporary skills (for sub-agent propagation from parent).
     */
    public void addTemporaryBulk(String sessionId, List<Skill> skills) {
        if (sessionId == null || skills == null) return;
        for (Skill skill : skills) {
            addTemporary(sessionId, skill);
        }
    }

    /**
     * Total number of unique skills (persistent + all sessions).
     */
    public int size() {
        Set<String> names = new HashSet<>(persistentIndex.keySet());
        for (Map<String, Skill> sessionSkills : temporarySkills.values()) {
            names.addAll(sessionSkills.keySet());
        }
        return names.size();
    }

    /**
     * Size for a specific session (persistent + session temporary).
     */
    public int size(String sessionId) {
        return listAll(sessionId).size();
    }

    private boolean isExpired(String sessionId) {
        Long last = lastAccess.get(sessionId);
        if (last == null) return false;
        return System.currentTimeMillis() - last > sessionTtlMs;
    }

    private void touchSession(String sessionId) {
        lastAccess.put(sessionId, System.currentTimeMillis());
    }
}
