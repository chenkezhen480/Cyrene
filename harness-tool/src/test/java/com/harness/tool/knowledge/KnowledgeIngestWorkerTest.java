package com.harness.tool.knowledge;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class KnowledgeIngestWorkerTest {
    @Test
    void waitsForConfigurationWithoutConsumingJobAttempts() throws InterruptedException {
        EnvConfig.init(Map.of(EnvKey.KNOWLEDGE_COMPILER_BATCH_SIZE, "1"));
        KnowledgeIngestService service = mock(KnowledgeIngestService.class);
        CountDownLatch checked = new CountDownLatch(1);
        try (KnowledgeIngestWorker worker = new KnowledgeIngestWorker(service,
                () -> { checked.countDown(); return false; })) {
            worker.start();
            assertThat(checked.await(2, TimeUnit.SECONDS)).isTrue();
        }
        verify(service, never()).processNext();
    }
}
