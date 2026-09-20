package com.github.anicmv.job;

import com.github.anicmv.job.model.SubtitlePair;
import com.github.anicmv.job.model.SubtitleRecognitionResult;
import com.xxl.job.core.context.XxlJobContext;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author anicmv
 * @date 2026/9/16 10:57
 * @description 端到端跑通一个目录：批次拆分、读写计数、XXL-JOB 回报与 Processor 透传。
 */
class SubtitleBatchJobTests extends SubtitleBatchTestSupport {

    @Test
    void xxlHandlerReportsFinalSuccessAndFailure() throws IOException {
        pair("scheduled");
        XxlJobContext success = new XxlJobContext(1L, directory.toString(), 1L, 0L, null, 0, 1);
        try {
            XxlJobContext.setXxlJobContext(success);
            handler.execute();
            assertEquals(XxlJobContext.HANDLE_CODE_SUCCESS, success.getHandleCode());
            assertTrue(success.getHandleMsg().startsWith("字幕任务完成："));

            pair("scheduled");
            Files.writeString(directory.resolve("scheduled.chs.srt"), "invalid srt");
            XxlJobContext failure = new XxlJobContext(2L, directory.toString(), 2L, 0L, null, 0, 1);
            XxlJobContext.setXxlJobContext(failure);
            handler.execute();
            assertEquals(XxlJobContext.HANDLE_CODE_FAIL, failure.getHandleCode());
            assertTrue(failure.getHandleMsg().contains("FAILED"));
        } finally {
            XxlJobContext.setXxlJobContext(null);
        }
    }

    @Test
    void processesPairsThenArchivesAndAllowsRerun() throws Exception {
        pair("episode1");
        pair("episode2");
        appendCue("episode1", 2, "00:00:03,000", "简体第二条", "繁體第二條");
        appendCue("episode2", 2, "00:00:03,000", "简体第二条", "繁體第二條");
        probe.threads.clear();
        var batches = new ArrayList<List<SubtitleRecognitionResult>>();
        when(llm.processBatch(any())).thenAnswer(invocation -> {
            List<SubtitlePair> pairs = invocation.getArgument(0);
            return pairs.stream().map(pair -> new SubtitleRecognitionResult(pair, "[]")).toList();
        });
        doAnswer(invocation -> {
            org.springframework.batch.infrastructure.item.Chunk<Future<List<SubtitleRecognitionResult>>> chunk = invocation.getArgument(0);
            var results = new ArrayList<SubtitleRecognitionResult>();
            for (var future : chunk) results.addAll(future.get());
            batches.add(results);
            return invocation.callRealMethod();
        }).when(writer).write(any());
        JobExecution execution = service.run(directory.toString());
        assertEquals(BatchStatus.COMPLETED, execution.getStatus());
        assertEquals(1, execution.getStepExecutions().size());
        StepExecution step = execution.getStepExecutions().stream()
                .filter(item -> item.getStepName().equals("subtitleStep")).findFirst().orElseThrow();
        assertEquals("subtitleStep", step.getStepName());
        assertEquals(2L, step.getReadCount());
        assertEquals(2L, step.getWriteCount());
        assertEquals(Set.of("episode1", "episode2"), probe.threads.keySet());
        assertTrue(probe.threads.values().stream().allMatch(Thread.currentThread().getName()::equals));
        assertEquals(List.of(4), batches.stream().map(List::size).toList());
        assertEquals(List.of("episode1:0", "episode1:1", "episode2:0", "episode2:1"),
                batches.stream().flatMap(List::stream)
                        .map(result -> result.pair().key() + ":" + result.pair().position()).toList());
        assertTrue(batches.stream().flatMap(List::stream).allMatch(result -> "[]".equals(result.content())));
        for (String name : List.of("episode1.chs.srt", "episode1.cht.srt", "episode2.chs.srt", "episode2.cht.srt")) {
            assertTrue(Files.isRegularFile(directory.resolve("processed").resolve(name)));
            assertTrue(Files.notExists(directory.resolve(name)));
        }
        probe.threads.clear();
        JobExecution rerun = service.run(directory.toString());
        assertTrue(probe.threads.isEmpty());
        assertEquals(BatchStatus.COMPLETED, rerun.getStatus());
        assertNotEquals(execution.getJobInstance().getId(), rerun.getJobInstance().getId());
    }

    @Test
    void jobMakesThreeModelCallsForTwentyThreeGroups() throws Exception {
        pair("episode");
        for (int i = 2; i <= 23; i++) appendCue("episode", i, "00:00:03,000", "字幕" + i, "字幕" + i);
        var sizes = new CopyOnWriteArrayList<Integer>();
        when(llm.processBatch(any())).thenAnswer(invocation -> {
            List<SubtitlePair> pairs = invocation.getArgument(0);
            sizes.add(pairs.size());
            return pairs.stream().map(pair -> new SubtitleRecognitionResult(pair, "[]")).toList();
        });
        var execution = service.run(directory.toString());
        assertEquals(BatchStatus.COMPLETED, execution.getStatus());
        assertEquals(List.of(3, 10, 10), sizes.stream().sorted().toList());
        assertEquals(3, execution.getStepExecutions().iterator().next().getWriteCount());
    }

    @Test
    void emptyDirectoryCompletesWithNoItems() {
        JobExecution execution = service.run(directory.toString());
        assertEquals(BatchStatus.COMPLETED, execution.getStatus());
        assertEquals(0L, execution.getStepExecutions().iterator().next().getReadCount());
    }

    @Test
    void propagatesParsingFailureInsteadOfReportingSuccess() throws IOException {
        pair("broken");
        Files.writeString(directory.resolve("broken.chs.srt"), "not an srt file");
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.run(directory.toString()));
        assertTrue(error.getMessage().contains("FAILED"));
    }

    @Test
    void processorPassesModelResultAndSourceToWriter() throws Exception {
        var pair = mock(SubtitlePair.class);
        when(pair.key()).thenReturn("sample");
        when(llm.processBatch(List.of(pair)))
                .thenReturn(List.of(new SubtitleRecognitionResult(pair, "识别结果")));
        var result = probe.process(List.of(pair)).get().getFirst();
        assertEquals(pair, result.pair());
        assertEquals("识别结果", result.content());
    }
}