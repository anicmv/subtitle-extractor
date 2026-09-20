package com.github.anicmv.xxl.service;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.stereotype.Component;

/**
 * @author anicmv
 * @date 2026/9/16 10:57
 * @description XXL-JOB 调度入口 subtitleImportJobHandler：取任务参数运行字幕导入，并向调度中心回报成功或失败。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubtitleXxlJob {
    private final SubtitleJobService service;

    @XxlJob("subtitleImportJobHandler")
    public void execute() {
        String directory = XxlJobHelper.getJobParam();
        log.info("收到 XXL-JOB 字幕调度 jobId={} 目录={}", XxlJobHelper.getJobId(), directory);
        XxlJobHelper.log("开始字幕导入，目录={}", directory);
        try {
            JobExecution execution = service.run(directory);
            log.info("XXL-JOB 字幕导入完成 jobId={} executionId={}", XxlJobHelper.getJobId(), execution.getId());
            XxlJobHelper.log("字幕导入完成 executionId={} 状态={}", execution.getId(), execution.getStatus());
            XxlJobHelper.handleSuccess("字幕任务完成：" + execution.getId());
        } catch (Exception exception) {
            log.error("XXL-JOB 字幕导入失败", exception);
            XxlJobHelper.log(exception);
            XxlJobHelper.handleFail(exception.toString());
        }
    }
}
