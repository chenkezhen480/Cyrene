package com.harness.preprocess.memory;

import com.harness.core.model.Preference;

import java.util.List;

/**
 * Persistence interface for long-term user preferences.
 */
public interface PreferenceStore {
    List<Preference> loadByUser(String userId);
    void upsert(String userId, String category, String content, String sourceSessionId);
}
