package com.github.anicmv.service;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * @author anicmv
 * @date 2026/9/16 15:36
 * @description H2 MySQL 模式下的字幕落库契约测试。
 */
@SpringBootTest(properties = {"xxl.job.enabled=false", "spring.ai.openai.api-key=test-key"})
class SubtitlePersistenceTests extends SubtitlePersistenceContract {}
