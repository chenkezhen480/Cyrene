package com.harness.input.memory;

import com.harness.core.model.Preference;

import java.util.List;

/**
 * No-op preference store. Used when HARNESS_AUDIT_STORE=none.
 */
public class NoOpPreferenceStore implements PreferenceStore {
    @Override public List<Preference> loadByUser(String userId) { return List.of(); }
    @Override public void upsert(String userId, String category, String content, String sourceSessionId) {}
}
