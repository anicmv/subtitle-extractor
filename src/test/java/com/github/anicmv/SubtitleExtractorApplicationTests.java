package com.github.anicmv;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * @author anicmv
 * @date 2026/9/16 10:57
 * @description 验证关闭 XXL-JOB、使用占位 OpenAI 配置时应用上下文可正常启动。
 */
@SpringBootTest(properties = {"xxl.job.enabled=false", "spring.ai.model.chat=openai",
        "spring.ai.openai.api-key=test-key", "spring.ai.openai.base-url=http://localhost:12345/v1",
        "spring.ai.openai.chat.model=test-model"})
class SubtitleExtractorApplicationTests {

    @Test
    void contextLoads() {
    }
}
