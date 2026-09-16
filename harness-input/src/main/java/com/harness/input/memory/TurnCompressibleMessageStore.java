package com.harness.input.memory;

import java.util.List;

/** Optional store capability for atomically replacing one completed Turn. */
public interface TurnCompressibleMessageStore extends MessageStore {

    void replaceTurnWithSummary(
            String sessionId,
            List<Long> messageIds,
            MessageWrite summary);
}
