package com.github.anicmv.xxl.service;

import com.github.anicmv.job.config.SubtitleBatchProperties;
import com.github.anicmv.job.step.reader.SubtitlePairScanner;
import com.github.anicmv.job.step.reader.SubtitleCheckpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * @author anicmv
 * @date 2026/9/16 10:57
 * @description 同目录最新任务失败或停止时恢复检查点，否则创建新任务；非 COMPLETED 状态抛异常。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SubtitleJobService {
    private final JobOperator jobOperator;
    private final JobRepository repository;
    private final SubtitleBatchProperties batchProperties;
    @Qualifier("subtitleImportJob")
    private final Job job;

    public synchronized JobExecution run(String directory) {
        Path path = requireReadableDirectory(directory);
        String normalized = path.normalize().toString();
        JobParameters parameters = new JobParametersBuilder()
                .addString("directory", normalized)
                .addString("requestId", UUID.randomUUID().toString())
                .toJobParameters();
        try (var directoryLock = SubtitleDirectoryLock.acquire(path)) {
            // 同步执行 Batch 和逐组 LLM 请求，回调前等待整个 Job 完成。
            JobExecution execution = startOrResume(latestExecution(normalized), normalized, parameters);
            requireCompleted(execution);
            return execution;
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Path requireReadableDirectory(String directory) {
        if (directory == null || directory.isBlank()) {
            throw new IllegalArgumentException("XXL-JOB 参数必须是绝对目录路径");
        }
        Path path = Path.of(directory);
        if (!path.isAbsolute() || !Files.isDirectory(path) || !Files.isReadable(path)) {
            throw new IllegalArgumentException("需要一个可读的绝对目录：" + directory);
        }
        return path;
    }

    /**
     * 同目录遗留任务按状态处置：UNKNOWN 拒绝自动恢复，异常退出遗留的 RUNNING 先 recover 成 FAILED；
     * 仅当上一任务 FAILED/STOPPED 且检查点可续跑时才 restart，否则带新 requestId 新建实例。
     */
    private JobExecution startOrResume(JobExecution previous, String directory, JobParameters parameters) throws Exception {
        if (previous != null && previous.getStatus() == BatchStatus.UNKNOWN) {
            throw new IllegalStateException("任务事务状态未知，需核实后恢复：" + previous.getId());
        }
        if (previous != null && previous.getStatus().isRunning()) {
            log.warn("已取得目录进程锁，恢复异常退出遗留的任务 executionId={} status={}",
                    previous.getId(), previous.getStatus());
            previous = jobOperator.recover(previous);
            if (previous.getStatus() != BatchStatus.FAILED) {
                throw new IllegalStateException("遗留任务恢复失败：" + previous.getId());
            }
        }
        boolean resumable = previous != null
                && (previous.getStatus() == BatchStatus.FAILED || previous.getStatus() == BatchStatus.STOPPED)
                && canResume(previous, directory);
        return resumable ? jobOperator.restart(previous) : jobOperator.start(job, parameters);
    }

    private static void requireCompleted(JobExecution execution) {
        if (execution.getStatus() != BatchStatus.COMPLETED) {
            throw new IllegalStateException("字幕任务 " + execution.getId()
                    + " 以状态 " + execution.getStatus() + " 结束："
                    + execution.getExitStatus().getExitDescription());
        }
    }

    private boolean canResume(JobExecution previous, String directory) {
        var step = repository.getLastStepExecution(previous.getJobInstance(), "subtitleStep");
        if (step == null || !SubtitleCheckpoint.isCompatible(step.getExecutionContext())) {
            log.info("无兼容字幕检查点，创建新任务 旧任务={} 目录={}", previous.getId(), directory);
            return false;
        }
        boolean unchanged = SubtitleCheckpoint.canResume(
                new SubtitlePairScanner(directory, batchProperties), step.getExecutionContext());
        if (unchanged) {
            log.info("字幕输入未变化，恢复任务 旧任务={} 目录={}", previous.getId(), directory);
        } else {
            log.info("字幕输入已变化，创建新任务 旧任务={} 目录={}", previous.getId(), directory);
        }
        return unchanged;
    }

    private JobExecution latestExecution(String directory) {
        for (int offset = 0; ; offset += 100) {
            var instances = repository.getJobInstances(job.getName(), offset, 100);
            for (var instance : instances) {
                var execution = repository.getLastJobExecution(instance);
                if (execution != null && directory.equals(execution.getJobParameters().getString("directory"))) {
                    return execution;
                }
            }
            if (instances.size() < 100) return null;
        }
    }
}
