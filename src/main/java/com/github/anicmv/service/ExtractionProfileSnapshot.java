package com.github.anicmv.service;

import com.github.anicmv.util.ContentHash;
import com.github.anicmv.job.config.SubtitleBatchProperties;
import com.github.anicmv.llm.SubtitleLlmService;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.TreeMap;

/**
 * @author anicmv
 * @date 2026/9/16 15:36
 * @description 启动时固定配置版本；只序列化生成参数，不保存连接凭据。
 */
@Component
public class ExtractionProfileSnapshot {
    private final String json;
    private final String hash;

    public ExtractionProfileSnapshot(Environment environment, SubtitleBatchProperties batch) {
        var mapper = JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
        var chat = Binder.get(environment).bind("spring.ai.openai.chat", OpenAiChatProperties.class)
                .orElseGet(OpenAiChatProperties::new);
        var config = new TreeMap<String, Object>();
        config.put("pipelineVersion", environment.getRequiredProperty("subtitle.persistence.pipeline-version"));
        config.put("rerunVersion", environment.getRequiredProperty("subtitle.persistence.rerun-version"));
        config.put("promptSha256", SubtitleLlmService.promptFingerprint());
        config.put("requestSize", batch.getRequestSize());
        config.put("mappingValidationVersion", "filtered-retry-review-v1");
        config.put("mappingValidationRetries", 2);
        config.put("endpoint", environment.getProperty("spring.ai.openai.chat.base-url",
                environment.getProperty("spring.ai.openai.base-url", "https://api.openai.com/v1")));
        // toOptions() 不包含继承的 apiKey/credential/代理等连接属性。
        config.put("options", mapper.readValue(mapper.writeValueAsString(chat.toOptions()), Map.class));
        json = mapper.writeValueAsString(config);
        hash = ContentHash.key(json);
    }

    public String json() { return json; }
    public String hash() { return hash; }
}
