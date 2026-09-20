package com.github.anicmv.llm;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author anicmv
 * @date 2026/9/16 10:57
 * @description 验证 OpenAI 自动配置下 ChatModel 与 SubtitleLlmService Bean 可正常装配。
 */
class LlmAutoConfigurationTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(OpenAiChatAutoConfiguration.class, org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration.class))
            .withBean(ToolCallingManager.class, () -> ToolCallingManager.builder().build())
            .withUserConfiguration(SubtitleLlmService.class);

    @Test
    void missingModelFailsStartup() {
        runner.withPropertyValues("spring.ai.model.chat=none").run(context ->
                assertThat(context).hasFailed());
    }

    @Test
    void springAiCreatesModelFromStandardProperties() {
        runner.withPropertyValues("spring.ai.model.chat=openai", "spring.ai.openai.api-key=test-key",
                "spring.ai.openai.base-url=http://localhost:12345/v1", "spring.ai.openai.chat.model=test-model").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ChatModel.class).hasSingleBean(SubtitleLlmService.class);
            assertThat(context.getBean(ChatModel.class).getOptions().getModel()).isEqualTo("test-model");
        });
    }
}
