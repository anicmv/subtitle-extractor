package com.github.anicmv.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {"xxl.job.enabled=false", "spring.ai.openai.api-key=test-key",
        "spring.flyway.enabled=true", "spring.sql.init.mode=never", "spring.batch.jdbc.initialize-schema=never",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver"})
/**
 * @author anicmv
 * @date 2026/9/16 15:36
 * @description 真实 MySQL 落库契约测试：动态建临时库并执行 Flyway，须 RUN_MYSQL_TESTS=true 显式启用。
 */
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
class MySqlSubtitlePersistenceTests extends SubtitlePersistenceContract {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.github.anicmv.llm.SubtitleLlmService llm;

    private static final String DATABASE = "subtitle_extractor_test_" + UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        var yaml = new org.springframework.beans.factory.config.YamlPropertiesFactoryBean();
        yaml.setResources(new org.springframework.core.io.ClassPathResource("application.yaml"));
        var config = java.util.Objects.requireNonNull(yaml.getObject());
        var parts = config.getProperty("spring.datasource.url").split("\\?", 2);
        String url = parts[0].substring(0, parts[0].lastIndexOf('/') + 1) + DATABASE
                + (parts.length > 1 ? "?" + parts[1] : "");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> config.getProperty("spring.datasource.username"));
        registry.add("spring.datasource.password", () -> config.getProperty("spring.datasource.password"));
    }

    @Test
    void migrationsAreRepeatableAndUseRealMySql(@Autowired Flyway flyway) {
        assertTrue(jdbc.queryForObject("SELECT VERSION()", String.class).matches("[89]\\..*"));
        assertEquals(0, flyway.migrate().migrationsExecuted);
    }

    @Test
    void batchJobCommitsMetadataAndBusinessResultsTogether(
            @Autowired com.github.anicmv.xxl.service.SubtitleJobService jobs) throws Exception {
        java.nio.file.Files.writeString(directory.resolve("job.chs.srt"),
                "1\n00:00:01,000 --> 00:00:02,000\n头发\n\n");
        java.nio.file.Files.writeString(directory.resolve("job.cht.srt"),
                "1\n00:00:01,000 --> 00:00:02,000\n頭髮\n\n");
        org.mockito.Mockito.when(llm.processBatch(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            java.util.List<com.github.anicmv.job.model.SubtitlePair> pairs = invocation.getArgument(0);
            return pairs.stream().map(pair -> new com.github.anicmv.job.model.SubtitleRecognitionResult(pair, HAIR)).toList();
        });
        assertEquals(org.springframework.batch.core.BatchStatus.COMPLETED, jobs.run(directory.toString()).getStatus());
        assertEquals(org.springframework.batch.core.BatchStatus.COMPLETED, jobs.run(directory.toString()).getStatus());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM term_mapping_evidence", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM BATCH_JOB_EXECUTION e JOIN BATCH_JOB_EXECUTION_PARAMS p "
                + "ON e.JOB_EXECUTION_ID=p.JOB_EXECUTION_ID WHERE e.STATUS='COMPLETED' "
                + "AND p.PARAMETER_NAME='directory' AND p.PARAMETER_VALUE=?", Integer.class, directory.toString()));
    }

    @Test
    void storesLargeArchiveManifestInBatchContext(
            @Autowired com.github.anicmv.xxl.service.SubtitleJobService jobs,
            @Autowired org.springframework.batch.core.repository.JobRepository repository) {
        var job = jobs.run(directory.toString());
        var step = repository.getLastStepExecution(job.getJobInstance(), "subtitleStep");
        var manifest = new java.util.HashMap<String, String>();
        for (int i = 0; i < 2000; i++) {
            manifest.put(directory.resolve("很长的字幕名称第" + i + "集.chs.srt").toString(), "a".repeat(64));
        }
        step.getExecutionContext().put("pair.archiveFiles", manifest);
        repository.updateExecutionContext(step);
        var restored = repository.getLastStepExecution(job.getJobInstance(), "subtitleStep");
        assertEquals(manifest, restored.getExecutionContext().get("pair.archiveFiles"));
        assertTrue(jdbc.queryForObject("SELECT OCTET_LENGTH(SERIALIZED_CONTEXT) "
                + "FROM BATCH_STEP_EXECUTION_CONTEXT WHERE STEP_EXECUTION_ID = ?", Long.class, step.getId()) > 65535);
        job.getExecutionContext().put("largeContext", manifest);
        repository.updateExecutionContext(job);
        assertEquals(manifest, repository.getJobExecution(job.getId()).getExecutionContext().get("largeContext"));
    }

    @AfterAll
    static void removeOwnedDatabase(@Autowired JdbcTemplate jdbc) {
        // 仅删除本测试随机创建的数据库，不触碰应用库或用户已有数据。
        jdbc.execute("DROP DATABASE " + DATABASE);
    }
}
