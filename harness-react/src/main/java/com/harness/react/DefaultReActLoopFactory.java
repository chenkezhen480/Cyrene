package com.harness.react;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.provider.ModelProviders;
import com.harness.tool.ToolCatalog;
import com.harness.tool.ToolExecutor;

import java.util.Objects;

/** Default LangChain4j-backed ReAct loop factory. */
public final class DefaultReActLoopFactory implements ReActLoopFactory {
    private final ModelProviders providers;
    private final FinalResponseGenerator finalResponseGenerator;

    public DefaultReActLoopFactory(ModelProviders providers) {
        this.providers = Objects.requireNonNull(providers, "providers");
        this.finalResponseGenerator = new FinalResponseGenerator(
                providers.chat(),
                EnvConfig.get().getInt(EnvKey.MODEL_CHAT_TIMEOUT_SECONDS, 300));
    }

    @Override
    public ReActLoop create(ToolCatalog toolCatalog, ToolExecutor toolExecutor) {
        return create(toolCatalog, toolExecutor, -1);
    }

    @Override
    public ReActLoop create(ToolCatalog toolCatalog, ToolExecutor toolExecutor, int maxIterations) {
        return new ReActEngine(
                providers.chat(),
                toolCatalog,
                toolExecutor,
                providers.vision(),
                providers.voice(),
                maxIterations,
                finalResponseGenerator);
    }
}
