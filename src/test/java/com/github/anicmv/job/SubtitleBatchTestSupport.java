package com.github.anicmv.job;

import com.github.anicmv.job.config.SubtitleBatchProperties;
import com.github.anicmv.job.model.SubtitlePair;
import com.github.anicmv.job.model.SubtitleRecognitionResult;
import com.github.anicmv.job.step.processor.SubtitlePairProcessor;
import com.github.anicmv.job.step.reader.SubtitleCueAligner;
import com.github.anicmv.job.step.reader.SubtitlePairLoader;
import com.github.anicmv.job.step.reader.SubtitlePairReader;
import com.github.anicmv.job.step.reader.SubtitlePairScanner;
import com.github.anicmv.xxl.service.SubtitleJobService;
import com.github.anicmv.xxl.service.SubtitleXxlJob;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * @author anicmv
 * @date 2026/9/16 10:57
 * @description 字幕批处理测试的共享夹具：上下文配置、临时目录、构造辅助与线程探针。
 * 各子类沿用同一份注解，Spring 测试上下文只创建并缓存一次。
 */
@SpringBootTest(properties = {"xxl.job.enabled=false", "spring.ai.model.chat=none"})
@Import(SubtitleBatchTestSupport.ProbeConfiguration.class)
abstract class SubtitleBatchTestSupport {
    @TempDir
    Path directory;

    @Autowired
    SubtitleJobService service;

    @Autowired
    SubtitleBatchProperties properties;

    @MockitoBean
    com.github.anicmv.llm.SubtitleLlmService llm;

    @MockitoSpyBean
    com.github.anicmv.job.step.writer.SubtitlePairWriter writer;

    @Autowired
    ProbeProcessor probe;

    @Autowired
    SubtitleXxlJob handler;

    @Autowired
    JobRepository repository;

    void appendCue(String key, int number, String start, String sc, String tc) throws IOException {
        Files.writeString(directory.resolve(key + ".chs.srt"),
                number + "\n" + start + " --> 00:00:04,000\n" + sc + "\n\n", java.nio.file.StandardOpenOption.APPEND);
        Files.writeString(directory.resolve(key + ".cht.srt"),
                number + "\n" + start + " --> 00:00:04,000\n" + tc + "\n\n", java.nio.file.StandardOpenOption.APPEND);
    }

    SubtitlePairReader reader() {
        return new SubtitlePairReader(new SubtitlePairScanner(directory.toString(), properties),
                new SubtitlePairLoader(), new SubtitleCueAligner());
    }

    void pair(String name) throws IOException {
        srt(name + ".chs.srt", "简体字幕", false);
        srt(name + ".cht.srt", "繁體字幕", false);
    }

    void srt(String name, String content, boolean bom) throws IOException {
        Files.writeString(directory.resolve(name),
                (bom ? "﻿" : "") + "1\n00:00:01,000 --> 00:00:02,000\n" + content + "\n\n");
    }

    /** 记录每批字幕执行线程名的探针 Processor。 */
    static class ProbeProcessor extends SubtitlePairProcessor {
        ProbeProcessor(com.github.anicmv.llm.SubtitleLlmService llm, ExecutorService executor) { super(llm, executor); }

        final Map<String, String> threads = new ConcurrentHashMap<>();

        @Override
        public Future<List<SubtitleRecognitionResult>> process(List<SubtitlePair> pairs) {
            pairs.forEach(pair -> threads.put(pair.key(), Thread.currentThread().getName()));
            return super.process(pairs);
        }
    }

    /** 测试配置：以 ProbeProcessor 替换正式 Processor。 */
    @TestConfiguration
    static class ProbeConfiguration {
        @Bean
        @Primary
        ProbeProcessor probeProcessor(com.github.anicmv.llm.SubtitleLlmService llm,
                @Qualifier("subtitleProcessorExecutor") ExecutorService executor) {
            return new ProbeProcessor(llm, executor);
        }
    }
}