package com.github.anicmv.job.config;

import com.github.anicmv.job.model.SubtitlePair;
import com.github.anicmv.job.model.SubtitleRecognitionResult;
import com.github.anicmv.job.step.listener.SubtitleJobListener;
import com.github.anicmv.job.step.processor.SubtitlePairProcessor;
import com.github.anicmv.job.step.reader.SubtitleCueAligner;
import com.github.anicmv.job.step.reader.SubtitlePairBatchReader;
import com.github.anicmv.job.step.reader.SubtitlePairLoader;
import com.github.anicmv.job.step.reader.SubtitlePairReader;
import com.github.anicmv.job.step.reader.SubtitlePairScanner;
import com.github.anicmv.job.step.writer.SubtitlePairWriter;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * @author anicmv
 * @date 2026/9/16 10:57
 * @description 装配 Spring Batch：Reader 按任务参数目录创建，Step 按分块串起读取/处理/写入，Job 挂载监听器。
 */
@Configuration
@EnableConfigurationProperties(SubtitleBatchProperties.class)
public class SubtitleJobConfiguration {

    @Bean(destroyMethod = "shutdownNow")
    ExecutorService subtitleProcessorExecutor(SubtitleBatchProperties properties) {
        if (properties.getConcurrency() < 1 || properties.getConcurrency() > 8) {
            throw new IllegalArgumentException("模型并发数必须在 1～8 之间");
        }
        return Executors.newFixedThreadPool(properties.getConcurrency(),
                Thread.ofPlatform().name("subtitle-llm-", 0).factory());
    }

    @Bean
    @StepScope
    SubtitlePairBatchReader subtitlePairReader(
            @Value("#{jobParameters['directory']}") String directory, SubtitleBatchProperties properties,
            SubtitlePairLoader loader, SubtitleCueAligner aligner) {
        var scanner = new SubtitlePairScanner(directory, properties);
        var subtitlePairReader = new SubtitlePairReader(scanner, loader, aligner);
        return new SubtitlePairBatchReader(subtitlePairReader, properties.getRequestSize());
    }

    @Bean
    Step subtitleStep(JobRepository repository, PlatformTransactionManager transactionManager,
                      SubtitlePairBatchReader reader, SubtitlePairProcessor processor, SubtitlePairWriter writer,
                      SubtitleBatchProperties properties) {
        return new StepBuilder("subtitleStep", repository)
                .<List<SubtitlePair>, Future<List<SubtitleRecognitionResult>>>chunk(properties.getChunkSize())
                .transactionManager(transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .build();
    }

    @Bean
    Job subtitleImportJob(JobRepository repository, @Qualifier("subtitleStep") Step step,
                          SubtitleJobListener listener) {
        return new JobBuilder("subtitleImportJob", repository)
                .start(step)
                .listener(listener)
                .build();
    }
}
