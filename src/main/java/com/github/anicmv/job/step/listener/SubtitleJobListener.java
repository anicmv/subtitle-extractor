package com.github.anicmv.job.step.listener;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.stereotype.Component;

/**
 * @author anicmv
 * @date 2026/9/16 10:57
 * @description Job 启动时记录参数，结束时输出状态与失败异常。
 */
@Slf4j
@Component
public class SubtitleJobListener implements JobExecutionListener {

    @Override
    public void beforeJob(JobExecution execution) {
        log.info("字幕任务开始 id={} 参数={}", execution.getId(), execution.getJobParameters());
    }

    @Override
    public void afterJob(JobExecution execution) {
        log.info("字幕任务结束 id={} 状态={}", execution.getId(), execution.getStatus());
        execution.getStepExecutions().forEach(step -> log.info(
                "字幕步骤统计 executionId={} step={} 状态={} 读取批次={} 写入批次={} 提交次数={} 回滚次数={} 开始={} 结束={}",
                execution.getId(), step.getStepName(), step.getStatus(), step.getReadCount(), step.getWriteCount(),
                step.getCommitCount(), step.getRollbackCount(), step.getStartTime(), step.getEndTime()));
        execution.getAllFailureExceptions().forEach(exception -> log.error("字幕任务失败", exception));
    }
}
