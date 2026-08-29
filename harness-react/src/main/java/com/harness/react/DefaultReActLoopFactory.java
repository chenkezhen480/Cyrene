package com.harness.react;

import com.harness.provider.ModelProviders;
import com.harness.provider.ModelProviderRuntime;
import com.harness.tool.ToolCatalog;
import com.harness.tool.ToolExecutor;

import java.util.Objects;

/** Default LangChain4j-backed ReAct loop factory. */
public final class DefaultReActLoopFactory implements ReActLoopFactory {
    private final ModelProviders providers;
    private final ModelProviderRuntime providerRuntime;

    public DefaultReActLoopFactory(ModelProviders providers) {
        this.providers = Objects.requireNonNull(providers, "providers");
        this.providerRuntime = null;
    }

    public DefaultReActLoopFactory(ModelProviderRuntime providerRuntime) {
        this.providerRuntime = Objects.requireNonNull(providerRuntime, "providerRuntime");
        this.providers = null;
    }

    @Override
    public ReActLoop create(ToolCatalog toolCatalog, ToolExecutor toolExecutor) {
        return create(toolCatalog, toolExecutor, -1);
    }

    @Override
    public ReActLoop create(ToolCatalog toolCatalog, ToolExecutor toolExecutor, int maxIterations) {
        if (providerRuntime != null) {
            return new ReActLoop() {
                @Override
                public ReActResult execute(ReActRequest request) {
                    return providerRuntime.withCurrent(current ->
                            createEngine(current, toolCatalog, toolExecutor, maxIterations)
                                    .execute(request));
                }

                @Override
                public ReActResult streamExecute(ReActRequest request) {
                    return providerRuntime.withCurrent(current ->
                            createEngine(current, toolCatalog, toolExecutor, maxIterations)
                                    .streamExecute(request));
                }
            };
        }
        return createEngine(providers, toolCatalog, toolExecutor, maxIterations);
    }

    private static ReActLoop createEngine(
            ModelProviders providers,
            ToolCatalog toolCatalog,
            ToolExecutor toolExecutor,
            int maxIterations
    ) {
        FinalResponseGenerator finalResponseGenerator = new FinalResponseGenerator(
                providers.chat(),
                positiveTimeout(providers.chat().timeoutSeconds()));
        return new ReActEngine(
                providers.chat(),
                toolCatalog,
                toolExecutor,
                providers.vision(),
                providers.voice(),
                maxIterations,
                finalResponseGenerator);
    }

    private static int positiveTimeout(int configured) {
        return configured > 0 ? configured : 300;
    }
}
