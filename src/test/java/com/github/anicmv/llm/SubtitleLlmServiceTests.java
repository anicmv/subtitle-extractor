package com.github.anicmv.llm;

import com.github.anicmv.job.model.SubtitlePair;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import sh.casey.subtitler.model.SubtitleFile;
import sh.casey.subtitler.reader.SrtSubtitleReader;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * @author anicmv
 * @date 2026/9/16 10:57
 * @description 验证 LLM 请求只包含当前一组简繁字幕，并拒绝截断响应、HTTP 失败时不自动重试。
 */
class SubtitleLlmServiceTests {
    @TempDir
    Path directory;

    @Test
    void rejectsSingleCodePointOnEitherSide() throws Exception {
        var cue = subtitles().getSubtitles().getFirst();
        var pair = new SubtitlePair("sample", 0, cue, cue);
        for (var words : List.of(List.of("第", "第二"), List.of("第一", "第"))) {
            ChatModel model = mock(ChatModel.class);
            when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(response(
                    "{\"results\":[{\"id\":0,\"mappings\":[{\"simplified\":\"" + words.get(0)
                            + "\",\"traditional\":\"" + words.get(1) + "\"}]}]}", "stop"));
            var service = new SubtitleLlmService(model, java.time.Duration.ofMinutes(5));
            assertThrows(IllegalStateException.class, () -> service.process(pair));
            verify(model, times(3)).call(any(org.springframework.ai.chat.prompt.Prompt.class));
        }
    }

    @Test
    void retriesTimeoutWithBackoffAndStopsAfterTwoRetries() throws Exception {
        var cue = subtitles().getSubtitles().getFirst();
        var pair = new SubtitlePair("sample", 0, cue, cue);
        ChatModel model = mock(ChatModel.class);
        var timeout = new RuntimeException(new java.io.InterruptedIOException("timeout"));
        var delays = new java.util.ArrayList<Long>();
        var service = new SubtitleLlmService(model, java.time.Duration.ofMinutes(5), delays::add);
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenThrow(timeout)
                .thenReturn(response("{\"results\":[{\"id\":0,\"mappings\":[]}]}", "stop"));
        assertEquals("[]", service.process(pair));
        assertEquals(List.of(1000L), delays);
        reset(model);
        delays.clear();
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenThrow(timeout);
        assertThrows(IllegalStateException.class, () -> service.process(pair));
        verify(model, times(3)).call(any(org.springframework.ai.chat.prompt.Prompt.class));
        assertEquals(List.of(1000L, 2000L), delays);
    }

    @Test
    void interruptionDuringBackoffStopsRequestsAndPreservesInterrupt() throws Exception {
        var cue = subtitles().getSubtitles().getFirst();
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(new RuntimeException(new java.net.SocketTimeoutException()));
        var service = new SubtitleLlmService(model, java.time.Duration.ofMinutes(5), millis -> {
            throw new InterruptedException();
        });
        try {
            assertThrows(IllegalStateException.class, () -> service.process(new SubtitlePair("sample", 0, cue, cue)));
            assertTrue(Thread.currentThread().isInterrupted());
            verify(model, times(1)).call(any(org.springframework.ai.chat.prompt.Prompt.class));
        } finally {
            Thread.interrupted();
        }
    }

    private org.springframework.context.annotation.AnnotationConfigApplicationContext modelContext(HttpServer server) {
        var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        new org.springframework.boot.test.context.ConfigDataApplicationContextInitializer().initialize(context);
        org.springframework.boot.test.util.TestPropertyValues.of(
                "spring.ai.model.chat=openai",
                "spring.ai.openai.base-url=http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "spring.ai.openai.api-key=test-key",
                "spring.ai.openai.chat.model=test-model",
                "spring.ai.openai.max-retries=0",
                "spring.ai.openai.timeout=7m").applyTo(context);
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "model-test-options", java.util.Map.of("spring.ai.openai.chat.extra-body[enable_thinking]", false)));
        context.registerBean(org.springframework.ai.model.tool.ToolCallingManager.class,
                () -> org.springframework.ai.model.tool.ToolCallingManager.builder().build());
        context.register(org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration.class);
        context.register(SubtitleLlmService.class);
        context.refresh();
        return context;
    }

    private SubtitleFile subtitles() throws Exception {
        Path file = directory.resolve("sample.srt");
        Files.writeString(file, "1\n00:00:01,000 --> 00:00:02,000\n第一行\n第二行\n\n"
                + "2\n00:00:03,000 --> 00:00:04,000\n另一条字幕\n\n");
        return new SrtSubtitleReader().read(file.toString());
    }

    @Test
    void sendsOneRequestContainingOnlyTheCurrentSimplifiedAndTraditionalCue() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var serverExecutor = Executors.newCachedThreadPool()) {
            server.setExecutor(serverExecutor);

            AtomicInteger requests = new AtomicInteger();
            var bodies = new CopyOnWriteArrayList<String>();
            var authorizations = new CopyOnWriteArrayList<String>();
            var timeouts = new CopyOnWriteArrayList<String>();
            server.createContext("/v1/chat/completions", exchange -> {
                try {
                    bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
                    timeouts.add(exchange.getRequestHeaders().getFirst("X-Stainless-Timeout"));
                    requests.incrementAndGet();

                    byte[] response = ("{\"id\":\"test\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"test-model\","
                            + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"results\\\":[{\\\"id\\\":0,\\\"mappings\\\":[]}]}\"},\"finish_reason\":\"stop\"}]}")
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    exchange.close();
                }
            });
            server.start();
            try (var context = modelContext(server)) {
                var service = context.getBean(SubtitleLlmService.class);
                var file = subtitles();
                var results = service.process(new SubtitlePair("sample", 0, file.getSubtitles().getFirst(), file.getSubtitles().getFirst()));
                assertEquals(1, requests.get());
                assertEquals("[]", results);
                assertEquals(List.of("420"), timeouts);
                var requestBody = tools.jackson.databind.json.JsonMapper.builder().build().readTree(bodies.getFirst());
                assertTrue(requestBody.path("enable_thinking").isBoolean());
                assertFalse(requestBody.path("enable_thinking").asBoolean());
                assertTrue(authorizations.stream().allMatch("Bearer test-key"::equals));
                assertTrue(bodies.stream().allMatch(body -> body.contains("test-model")
                        && body.contains("simplified") && body.contains("traditional") && body.contains("messages")
                        && body.contains("第一行") && !body.contains("另一条字幕")
                        && !body.contains("00:00:") && !body.contains("-->")));
                assertEquals("第一行\n第二行", file.getSubtitles().getFirst().getText());
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void batchUsesOneCallAndPreservesSource() throws Exception {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(response(
                "{\"results\":[{\"id\":0,\"mappings\":[]},{\"id\":1,\"mappings\":[]}]}", "stop"));
        var cues = subtitles().getSubtitles();
        var pairs = List.of(new SubtitlePair("sample", 20, cues.getFirst(), cues.getFirst()),
                new SubtitlePair("sample", 21, cues.getLast(), cues.getLast()));
        var results = new SubtitleLlmService(model, java.time.Duration.ofMinutes(5)).processBatch(pairs);
        assertEquals(List.of("[]", "[]"), results.stream().map(r -> r.content()).toList());
        assertEquals(pairs, results.stream().map(r -> r.pair()).toList());
        var captor = org.mockito.ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
        verify(model, times(1)).call(captor.capture());
        assertTrue(captor.getValue().getContents().contains("另一条字幕"));
        assertTrue(captor.getValue().getContents().contains("第一行"));
        var input = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(captor.getValue().getUserMessage().getText());
        assertTrue(input.isObject());
        assertEquals(1, input.size());
        input = input.path("cues");
        assertTrue(input.isArray());
        assertEquals(2, input.size());
        assertEquals(0, input.get(0).path("id").intValue());
        assertEquals(1, input.get(1).path("id").intValue());
        assertEquals("第一行\n第二行", input.get(0).path("simplified").asString());
        assertEquals("第一行\n第二行", input.get(0).path("traditional").asString());
        assertEquals(3, input.get(0).size());
    }

    @Test
    void acceptsOmittedCuesAndEmptyResults() throws Exception {
        var cues = subtitles().getSubtitles();
        cues.getFirst().setText("头发");
        cues.getLast().setText("頭髮");
        var pairs = List.of(new SubtitlePair("sample", 0, cues.getFirst(), cues.getLast()),
                new SubtitlePair("sample", 1, cues.getFirst(), cues.getLast()));
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(response(
                """
                {"results":[{"id":1,"mappings":[{"simplified":"头发","traditional":"頭髮"}]}]}
                """, "stop"), response("{\"results\":[]}", "stop"));
        var service = new SubtitleLlmService(model, java.time.Duration.ofMinutes(5));
        var results = service.processBatch(pairs);
        assertEquals(pairs, results.stream().map(r -> r.pair()).toList());
        assertEquals("[]", results.getFirst().content());
        assertTrue(results.getLast().content().contains("頭髮"));
        assertEquals(List.of("[]", "[]"), service.processBatch(pairs).stream().map(r -> r.content()).toList());
        verify(model, times(2)).call(any(org.springframework.ai.chat.prompt.Prompt.class));
    }

    @Test
    void rejectsDuplicateUnknownMalformedAndTruncatedBatchResults() throws Exception {
        var cue = subtitles().getSubtitles().getFirst();
        var pairs = List.of(new SubtitlePair("sample", 0, cue, cue), new SubtitlePair("sample", 1, cue, cue));
        for (String json : List.of("[]", "not json",
                "{\"results\":[{\"id\":2,\"mappings\":[]}]}",
                "{\"results\":[{\"id\":0,\"mappings\":[]},{\"id\":0,\"mappings\":[]}]}",
                "{\"results\":[{\"id\":1,\"mappings\":[]},{\"id\":0,\"mappings\":[]}]}",
                "{\"results\":[{\"id\":0,\"mappings\":null},{\"id\":1,\"mappings\":[]}]}",
                "{\"results\":[{\"id\":0,\"mappings\":[],\"extra\":true},{\"id\":1,\"mappings\":[]}]}")) {
            ChatModel model = mock(ChatModel.class);
            when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(response(json, "stop"));
            assertThrows(IllegalStateException.class, () -> new SubtitleLlmService(model, java.time.Duration.ofMinutes(5)).processBatch(pairs));
        }
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(response("[]", "length"));
        assertThrows(IllegalStateException.class, () -> new SubtitleLlmService(model, java.time.Duration.ofMinutes(5)).processBatch(pairs));
    }

    @Test
    void validatesMappingsAgainstOriginalText() throws Exception {
        var cue = subtitles().getSubtitles().getFirst();
        cue.setText("头发 123 !");
        var traditional = subtitles().getSubtitles().getLast();
        traditional.setText("頭髮 456 ?");
        var pair = new SubtitlePair("sample", 30, cue, traditional);
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        for (var words : List.of(List.of("头发", "頭髮"), List.of("不存在", "頭髮"),
                List.of("头发", "头发"), List.of("123", "456"), List.of("!", "?"), List.of("", "頭髮"))) {
            var mapping = java.util.Map.of("simplified", words.get(0), "traditional", words.get(1));
            ChatModel model = mock(ChatModel.class);
            var json = mapper.writeValueAsString(java.util.Map.of("results", List.of(
                    java.util.Map.of("id", 0, "mappings", List.of(mapping)))));
            when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(response(json, "stop"));
            var service = new SubtitleLlmService(model, java.time.Duration.ofMinutes(5));
            if (words.get(0).equals("头发") && words.get(1).equals("頭髮")) {
                assertEquals(mapper.readTree(mapper.writeValueAsString(List.of(mapping))),
                        mapper.readTree(service.process(pair)));
            } else {
                assertThrows(IllegalStateException.class, () -> service.process(pair));
                verify(model, times(3)).call(any(org.springframework.ai.chat.prompt.Prompt.class));
            }
        }
    }

    @Test
    void retriesOnlyInvalidCueAndRetainsValidMappings() throws Exception {
        var cues = subtitles().getSubtitles();
        cues.getFirst().setText("头发软件");
        cues.getLast().setText("頭髮軟體");
        var pair = new SubtitlePair("sample", 30, cues.getFirst(), cues.getLast());
        var other = new SubtitlePair("sample", 31, cues.getFirst(), cues.getLast());
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(response("""
                {"results":[{"id":0,"mappings":[
                  {"simplified":"头发","traditional":"頭髮"},
                  {"simplified":"不存在","traditional":"軟體"}]},
                  {"id":1,"mappings":[]}]}
                """, "stop"), response("""
                {"results":[{"id":0,"mappings":[{"simplified":"软件","traditional":"軟體"}]}]}
                """, "stop"));
        var results = new SubtitleLlmService(model, java.time.Duration.ofMinutes(5)).processBatch(List.of(pair, other));
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        assertEquals(2, json.readTree(results.getFirst().content()).size());
        assertEquals("[]", results.getLast().content());
        var captor = org.mockito.ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
        verify(model, times(2)).call(captor.capture());
        var retry = json.readTree(captor.getAllValues().getLast().getUserMessage().getText());
        assertEquals(1, retry.path("cues").size());
        assertTrue(retry.path("validationErrors").toString().contains("简体词语不存在"));
    }

    @Test
    void capsRetriesAndKeepsValidSubset() throws Exception {
        var cues = subtitles().getSubtitles();
        cues.getFirst().setText("头发");
        cues.getLast().setText("頭髮");
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(response("""
                {"results":[{"id":0,"mappings":[
                  {"simplified":"头发","traditional":"頭髮"},
                  {"simplified":"不存在","traditional":"頭髮"},
                  {"simplified":"头发","traditional":"頭髮"},
                  {"simplified":123,"traditional":"頭髮"}]}]}
                """, "stop"));
        var result = new SubtitleLlmService(model, java.time.Duration.ofMinutes(5)).process(new SubtitlePair("sample", 30, cues.getFirst(), cues.getLast()));
        assertEquals(1, tools.jackson.databind.json.JsonMapper.builder().build().readTree(result).size());
        assertFalse(result.contains("不存在"));
        verify(model, times(3)).call(any(org.springframework.ai.chat.prompt.Prompt.class));
    }

    private ChatResponse response(String content, String reason) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content),
                ChatGenerationMetadata.builder().finishReason(reason).build())));
    }

    @Test
    void rejectsTruncatedResponses() throws Exception {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("partial"), ChatGenerationMetadata.builder().finishReason("length").build()))));
        var service = new SubtitleLlmService(model, java.time.Duration.ofMinutes(5));
        var pair = new SubtitlePair("truncated", 0, subtitles().getSubtitles().getFirst(), subtitles().getSubtitles().getFirst());
        assertThrows(IllegalStateException.class, () -> service.process(pair));
    }

    @Test
    void failsOnHttpErrorWithoutAutomaticRetries() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
        });
        server.start();
        try (var context = modelContext(server)) {
            var service = context.getBean(SubtitleLlmService.class);
            var pair = new SubtitlePair("rate-limited", 0, subtitles().getSubtitles().getFirst(), subtitles().getSubtitles().getFirst());
            assertThrows(IllegalStateException.class, () -> service.process(pair));
        } finally {
            server.stop(0);
        }
        assertEquals(1, requests.get());
    }
}
