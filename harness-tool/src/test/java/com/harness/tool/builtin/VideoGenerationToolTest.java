package com.harness.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.model.Artifact;
import com.harness.core.modelconfig.ModelConfig;
import com.harness.core.modelconfig.ModelConfigKey;
import com.harness.tool.artifact.ArtifactSessionContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class VideoGenerationToolTest {
    @Test void backgroundCompletionKeepsOwnerAndRejectsForeignOrUnknownTaskIds() throws Exception {
        HttpServer api=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        String url="http://127.0.0.1:"+api.getAddress().getPort();
        var polls=new AtomicInteger();
        api.createContext("/",exchange->{
            String path=exchange.getRequestURI().getPath();
            byte[] body;
            if(path.equals("/submit")) body="{\"task_id\":\"task-1\"}".getBytes();
            else if(path.startsWith("/status/")) { polls.incrementAndGet();body=("{\"status\":\"completed\",\"video_url\":\""+url+"/video\"}").getBytes(); }
            else body=new byte[]{1,2,3};
            exchange.sendResponseHeaders(200,body.length);
            try(var output=exchange.getResponseBody()){ output.write(body); }
        });
        api.start();
        var saved=new AtomicReference<Artifact>();var done=new CountDownLatch(1);var mapper=new ObjectMapper();
        var tasks=new java.util.concurrent.ConcurrentHashMap<String,VideoGenerationTool.TaskState>();
        var config=ModelConfig.of(Map.of(ModelConfigKey.VIDEO_API_KEY,"test",ModelConfigKey.VIDEO_BASE_URL,url));
        try(var tool=new VideoGenerationTool((bytes,name,mime,session)->{
            var artifact=new Artifact("result",session,name,Artifact.ArtifactType.VIDEO,mime,bytes.length,"test",Instant.now());
            saved.set(artifact);return artifact;
        },(session,artifact)->done.countDown(),config,tasks)) {
            ArtifactSessionContext.set("owner-session");
            tool.execute(mapper.createObjectNode().put("action","submit").put("prompt","test"));
            var duplicateCalls = new java.util.ArrayList<java.util.concurrent.CompletableFuture<Void>>();
            for(int i=0;i<2;i++) duplicateCalls.add(java.util.concurrent.CompletableFuture.runAsync(()->{
                ArtifactSessionContext.set("owner-session");
                try { assertThatThrownBy(()->tool.execute(mapper.createObjectNode().put("action","submit").put("prompt","duplicate")))
                        .hasMessageContaining("ID collision"); }
                finally { ArtifactSessionContext.clear(); }
            }));
            java.util.concurrent.CompletableFuture.allOf(duplicateCalls.toArray(java.util.concurrent.CompletableFuture[]::new)).get(5,TimeUnit.SECONDS);
            assertThat(tasks).hasSize(1);
            try(var replacement=new VideoGenerationTool((bytes,name,mime,session)->{throw new AssertionError("Old task must retain its original provider");},null,config,tasks)) {
            ArtifactSessionContext.set("foreign-session");
            assertThatThrownBy(()->replacement.execute(mapper.createObjectNode().put("action","check").put("task_id","task-1")))
                    .hasMessageContaining("access denied");
            assertThatThrownBy(()->replacement.execute(mapper.createObjectNode().put("action","check").put("task_id","unknown")))
                    .hasMessageContaining("access denied");
            ArtifactSessionContext.clear();
            assertThat(done.await(15,TimeUnit.SECONDS)).isTrue();
            assertThat(saved.get().sessionId()).isEqualTo("owner-session");
            ArtifactSessionContext.set("owner-session");
            assertThat(replacement.executeOutput(mapper.createObjectNode().put("action","check").put("task_id","task-1")).artifacts())
                    .containsExactly(saved.get());
            assertThat(polls).hasValue(1);
            }
        } finally { ArtifactSessionContext.clear();api.stop(0); }
    }
}
