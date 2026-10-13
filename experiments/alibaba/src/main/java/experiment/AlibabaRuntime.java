package experiment;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/** Official autonomous ReactAgent, independently assembled for the synthetic host. */
public final class AlibabaRuntime {
    private final ReactAgent agent;
    public AlibabaRuntime(ChatModel model, CrmTools tools, String systemPrompt, int maxCalls) {
        agent = ReactAgent.builder().name("isolated_crm_agent").model(model).tools(ToolCallbacks.from(tools))
                .systemPrompt(systemPrompt).saver(new MemorySaver()).enableLogging(false)
                .hooks(ModelCallLimitHook.builder().runLimit(maxCalls).exitBehavior(ModelCallLimitHook.ExitBehavior.ERROR).build()).build();
    }
    public String call(String prompt, String token, String sessionId) throws Exception {
        var config = RunnableConfig.builder().threadId(sessionId).addMetadata("fixtureToken", token).addMetadata("_stream_", false).build();
        return agent.call(prompt, config).getText();
    }
    public static ChatModel model(Map<String, String> settings) throws Exception {
        if (!"openai".equals(settings.get("chat.provider"))) throw new IllegalArgumentException("This comparator requires the configured OpenAI-compatible provider");
        String format = settings.getOrDefault("chat.apiFormat", "");
        if (!format.isBlank() && !"chat_completions".equals(format)) throw new IllegalArgumentException("Use matching chat_completions transport");
        URI base = URI.create(required(settings, "chat.baseUrl"));
        String origin = new URI(base.getScheme(), base.getAuthority(), null, null, null).toString();
        String path = base.getPath().replaceAll("/$", "") + "/chat/completions";
        Duration timeout = Duration.ofSeconds(Integer.parseInt(settings.getOrDefault("chat.timeoutSeconds", "300")));
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        var api = OpenAiApi.builder().baseUrl(origin).completionsPath(path).apiKey(required(settings, "chat.apiKey"))
                .restClientBuilder(RestClient.builder().requestFactory(factory)).build();
        var options = OpenAiChatOptions.builder().model(required(settings, "chat.model"))
                .temperature(Double.parseDouble(required(settings, "chat.temperature")))
                .maxTokens(Integer.parseInt(required(settings, "chat.maxTokens")))
                .reasoningEffort("none").internalToolExecutionEnabled(false).build();
        return OpenAiChatModel.builder().openAiApi(api).defaultOptions(options)
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
    }
    private static String required(Map<String, String> settings, String key) {
        String value = settings.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing model setting: " + key);
        return value;
    }
}
