package com.harness.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.agent.context.ContextBuilder;
import com.harness.agent.knowledge.KnowledgeToolRuntimeContext;
import com.harness.core.env.EnvConfig;
import com.harness.core.model.ArtifactStore;
import com.harness.core.modelconfig.ModelConfig;
import com.harness.core.runtime.RunTrace;
import com.harness.graph.store.KnowledgeGraphStore;
import com.harness.provider.ModelProviders;
import com.harness.provider.VoiceCapabilities;
import com.harness.provider.VoiceModelProvider;
import com.harness.tool.artifact.ArtifactStorageService;
import com.harness.tool.artifact.UploadedFileAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentToolRuntimeAccessTest {
    @TempDir Path root;
    @Test void modelAudioReferencesEnforceOwnerAndOnlyExplicitNoneModeAllowsAnonymousUploads() throws Exception {
        Path input=Files.createDirectory(root.resolve("input"));
        String name="12345678-1234-1234-1234-123456789abc.wav";
        Files.write(input.resolve(name),new byte[]{1});
        var mapper=new ObjectMapper();
        mapper.writeValue(input.resolve(name+".owner.json").toFile(),new UploadedFileAccess.FileOwner("owner-a","tenant-a","audio/wav"));
        var config=new java.util.HashMap<String,String>();
        config.put("HARNESS_AUTH_MODE","jwt"); config.put("HARNESS_INTERNAL_API_AUTHORIZATION_ENABLED","true");
        config.put("HARNESS_KNOWLEDGE_UPLOAD_DIR",root.toString());
        config.put("HARNESS_SKILL_DIR",root.toString()); config.put("HARNESS_MCP_CONFIG_FILE",root.resolve("absent.json").toString());
        for(String key:List.of("HARNESS_CODE_TOOLS_ENABLED","HARNESS_SHELL_ENABLED","HARNESS_PROJECT_DISCOVERY_ENABLED",
                "HARNESS_TOOL_WEB_SEARCH_ENABLED","HARNESS_TOOL_URL_READER_ENABLED","HARNESS_TOOL_BROWSER_ENABLED"))config.put(key,"false");
        EnvConfig.init(config);
        var providers=mock(ModelProviders.class); var voice=mock(VoiceModelProvider.class);
        when(providers.voice()).thenReturn(voice);
        when(voice.capabilities()).thenReturn(new VoiceCapabilities(true,false,List.of("audio/wav"),List.of()));
        when(voice.maxTranscriptionSizeBytes()).thenReturn(1024L);
        when(voice.transcribe(any(),eq("audio/wav"))).thenReturn("transcribed");
        var graph=mock(KnowledgeGraphStore.class);when(graph.providerName()).thenReturn("none");
        var artifacts=mock(ArtifactStore.class);
        var runtime=new AgentToolRuntime(providers,null,null,graph,null,null,artifacts,
                new ArtifactStorageService(artifacts,root.resolve("artifacts"),1),
                ModelConfig.of(Map.of(com.harness.core.modelconfig.ModelConfigKey.VOICE_PROVIDER,"test")),mock(ContextBuilder.class));
        var catalog=runtime.tools().snapshot();
        var audio=catalog.get("transcribe_audio");
        var arguments=mapper.createObjectNode().put("file","/files/input/"+name);
        try {
            KnowledgeToolRuntimeContext.activate("tenant-b","owner-b",null,null,catalog,RunTrace.noop());
            assertThatThrownBy(()->audio.execute(arguments)).hasMessageContaining("access denied");
            verify(voice,never()).transcribe(any(),any());
            KnowledgeToolRuntimeContext.activate("tenant-a","owner-a",null,null,catalog,RunTrace.noop());
            assertThat(audio.execute(arguments)).isEqualTo("transcribed");
            mapper.writeValue(input.resolve(name+".owner.json").toFile(),new UploadedFileAccess.FileOwner("anonymous","000000","audio/wav"));
            config.put("HARNESS_AUTH_MODE","none");config.put("HARNESS_INTERNAL_API_AUTHORIZATION_ENABLED","false");EnvConfig.init(config);
            KnowledgeToolRuntimeContext.activate(null,"development-user",null,null,catalog,RunTrace.noop());
            assertThat(audio.execute(arguments)).isEqualTo("transcribed");
            config.put("HARNESS_INTERNAL_API_AUTHORIZATION_ENABLED","true");EnvConfig.init(config);
            assertThatThrownBy(()->audio.execute(arguments)).hasMessageContaining("access denied");
        } finally { KnowledgeToolRuntimeContext.clear(); }
    }
}
