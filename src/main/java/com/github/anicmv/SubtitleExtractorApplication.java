package com.github.anicmv;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * @author anicmv
 * @date 2026/9/16 10:57
 * @description 应用启动类，负责装配 Spring Batch、LLM 与 XXL-JOB 组件。
 */
@Slf4j
@SpringBootApplication
public class SubtitleExtractorApplication {

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.info("字幕提取器已就绪，启动不会自动导入；请通过 XXL-JOB 的 subtitleImportJobHandler 调度，参数为字幕目录绝对路径");
    }

    public static void main(String[] args) {
        SpringApplication.run(SubtitleExtractorApplication.class, args);
    }
}
