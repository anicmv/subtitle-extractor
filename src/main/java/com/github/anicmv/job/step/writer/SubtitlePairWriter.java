package com.github.anicmv.job.step.writer;

import com.github.anicmv.job.model.SubtitleRecognitionResult;
import com.github.anicmv.service.SubtitlePersistenceService;
import com.github.anicmv.job.step.reader.SubtitleCheckpoint;
import com.github.anicmv.job.step.SubtitleArchiver;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author anicmv
 * @date 2026/9/16 10:57
 * @description 同一 chunk 内原子保存识别结果、词语映射与字幕来源，提交后归档已完成文件。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubtitlePairWriter implements ItemWriter<Future<List<SubtitleRecognitionResult>>> {
    private final SubtitlePersistenceService persistence;

    @Override
    @Transactional
    public void write(Chunk<? extends Future<List<SubtitleRecognitionResult>>> chunk) {
        var results = new ArrayList<SubtitleRecognitionResult>();
        try {
            for (var future : chunk) results.addAll(future.get());
        } catch (InterruptedException exception) {
            chunk.forEach(future -> future.cancel(true));
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待模型批次时被中断", exception);
        } catch (ExecutionException | RuntimeException exception) {
            chunk.forEach(future -> future.cancel(true));
            throw new IllegalStateException("模型 chunk 处理失败", exception);
        }
        log.info("开始写入识别结果 批次数={} 字幕组数={}", chunk.size(), results.size());
        for (var result : results) {
            persistence.save(result);
            log.debug("保存识别结果 字幕对={} 字幕组位置={}", result.pair().key(), result.pair().position());
        }
        log.info("识别结果写入完成，等待事务提交 字幕组数={}", results.size());
        archiveAfterCommit();
    }

    private void archiveAfterCommit() {
        var stepContext = StepSynchronizationManager.getContext();
        // 契约测试等场景在 step 之外直接调用，没有检查点也就没有归档目标。
        if (stepContext == null) {
            log.debug("无 step 上下文，跳过归档");
            return;
        }
        var executionContext = stepContext.getStepExecution().getExecutionContext();
        int previouslyCompleted = SubtitleCheckpoint.fileIndex(executionContext);
        // Reader 随后更新检查点；等结果和检查点一起提交后再归档，回滚时不移动文件。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    SubtitleArchiver.archive(SubtitleCheckpoint.restore(executionContext)
                            .completedFilesSince(previouslyCompleted));
                } catch (IOException exception) {
                    throw new java.io.UncheckedIOException(exception);
                }
            }
        });
    }

}
