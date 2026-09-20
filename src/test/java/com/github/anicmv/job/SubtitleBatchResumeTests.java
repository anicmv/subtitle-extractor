package com.github.anicmv.job;

import com.github.anicmv.job.model.SubtitlePair;
import com.github.anicmv.job.model.SubtitleRecognitionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.core.BatchStatus;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * @author anicmv
 * @date 2026/9/16 10:57
 * @description 失败与异常退出后的续跑：检查点位置、输入漂移判定、并发上限与不重复请求模型。
 */
class SubtitleBatchResumeTests extends SubtitleBatchTestSupport {

    @Test
    void recoversStaleStartedExecutionAndResumesCommittedCheckpoint() throws Exception {
        pair("stale");
        for (int i = 2; i <= 83; i++) appendCue("stale", i, "00:00:03,000", "字幕" + i, "字幕" + i);
        var fail = new AtomicBoolean(true);
        var positions = new CopyOnWriteArrayList<Integer>();
        when(llm.processBatch(any())).thenAnswer(invocation -> {
            List<SubtitlePair> pairs = invocation.getArgument(0);
            int position = pairs.getFirst().position();
            positions.add(position);
            if (position == 50 && fail.getAndSet(false)) throw new IllegalStateException("failure");
            return pairs.stream().map(pair -> new SubtitleRecognitionResult(pair, "[]")).toList();
        });
        assertThrows(IllegalStateException.class, () -> service.run(directory.toString()));
        var stale = repository.getLastJobExecution(repository.getLastJobInstance("subtitleImportJob"));
        var step = stale.getStepExecutions().iterator().next();
        assertEquals(4, step.getWriteCount());
        // 模拟进程退出前来不及更新状态，但前一块已经提交。
        step.setStatus(BatchStatus.STARTED);
        step.setEndTime(null);
        repository.update(step);
        stale.setStatus(BatchStatus.STARTED);
        stale.setEndTime(null);
        repository.update(stale);
        positions.clear();
        var resumed = service.run(directory.toString());
        assertEquals(BatchStatus.COMPLETED, resumed.getStatus());
        assertEquals(stale.getJobInstance().getId(), resumed.getJobInstance().getId());
        assertEquals(BatchStatus.FAILED, repository.getJobExecution(stale.getId()).getStatus());
        assertEquals(List.of(40, 50, 60, 70, 80), positions.stream().sorted().toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"replace", "modify", "empty"})
    void changedDirectoryStartsNewJobInsteadOfResumingOldCheckpoint(String change) throws Exception {
        pair("old");
        for (int i = 2; i <= 83; i++) appendCue("old", i, "00:00:03,000", "字幕" + i, "字幕" + i);
        when(llm.processBatch(any())).thenAnswer(invocation -> {
            List<SubtitlePair> pairs = invocation.getArgument(0);
            if (pairs.getFirst().position() == 50) throw new IllegalStateException("simulated failure");
            return pairs.stream().map(pair -> new SubtitleRecognitionResult(pair, "[]")).toList();
        });
        assertThrows(IllegalStateException.class, () -> service.run(directory.toString()));
        var failed = repository.getLastJobExecution(repository.getLastJobInstance("subtitleImportJob"));
        assertEquals(4, failed.getStepExecutions().iterator().next().getWriteCount());
        if (change.equals("modify")) {
            var file = directory.resolve("old.chs.srt");
            Files.writeString(file, Files.readString(file).replace("简体字幕", "修改后的字幕"));
        } else {
            Files.delete(directory.resolve("old.chs.srt"));
            Files.delete(directory.resolve("old.cht.srt"));
            if (change.equals("replace")) pair("new");
        }
        var positions = new CopyOnWriteArrayList<Integer>();
        doAnswer(invocation -> {
            List<SubtitlePair> pairs = invocation.getArgument(0);
            positions.add(pairs.getFirst().position());
            return pairs.stream().map(pair -> new SubtitleRecognitionResult(pair, "[]")).toList();
        }).when(llm).processBatch(any());
        var execution = service.run(directory.toString());
        assertEquals(BatchStatus.COMPLETED, execution.getStatus());
        assertNotEquals(failed.getJobInstance().getId(), execution.getJobInstance().getId());
        assertEquals(BatchStatus.FAILED, repository.getJobExecution(failed.getId()).getStatus());
        if (change.equals("empty")) assertTrue(positions.isEmpty());
        else assertTrue(positions.contains(0));
        if (change.equals("replace")) assertEquals(List.of(0), positions);
    }

    @Test
    void concurrentRequestsResumeFromLastCommittedChunk() throws Exception {
        pair("parallel");
        for (int i = 2; i <= 83; i++) appendCue("parallel", i, "00:00:03,000", "字幕" + i, "字幕" + i);
        var secondStarted = new CountDownLatch(1);
        var firstStarted = new CountDownLatch(1);
        var fail = new AtomicBoolean(true);
        var positions = new CopyOnWriteArrayList<Integer>();
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        when(llm.processBatch(any())).thenAnswer(invocation -> {
            List<SubtitlePair> pairs = invocation.getArgument(0);
            int position = pairs.getFirst().position();
            positions.add(position);
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                if (position == 0) {
                    firstStarted.countDown();
                    assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
                }
                if (position == 10) {
                    assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
                    secondStarted.countDown();
                }
                if (position == 50 && fail.getAndSet(false)) throw new IllegalStateException("simulated timeout");
                return pairs.stream().map(pair -> new SubtitleRecognitionResult(pair, "[]")).toList();
            } finally {
                active.decrementAndGet();
            }
        });
        properties.setConcurrency(2);
        try {
            assertThrows(IllegalStateException.class, () -> service.run(directory.toString()));
            var failed = repository.getLastJobExecution(repository.getLastJobInstance("subtitleImportJob"));
            assertEquals(4, failed.getStepExecutions().iterator().next().getWriteCount());
            assertEquals(2, peak.get());
            assertEquals(0, active.get());
            positions.clear();
            var written = new ArrayList<Integer>();
            doAnswer(invocation -> {
                org.springframework.batch.infrastructure.item.Chunk<Future<List<SubtitleRecognitionResult>>> chunk = invocation.getArgument(0);
                for (var batch : chunk) written.add(batch.get().getFirst().pair().position());
                return invocation.callRealMethod();
            }).when(writer).write(any());
            var resumed = service.run(directory.toString());
            assertEquals(BatchStatus.COMPLETED, resumed.getStatus());
            assertEquals(failed.getJobInstance().getId(), resumed.getJobInstance().getId());
            assertEquals(List.of(40, 50, 60, 70, 80), positions.stream().sorted().toList());
            assertEquals(List.of(40, 50, 60, 70, 80), written);
            assertTrue(peak.get() <= 2);
        } finally {
            properties.setConcurrency(2);
        }
    }

    @Test
    void resumesFailedDirectoryWithoutRepeatingCommittedModelBatches() throws Exception {
        pair("resume");
        for (int i = 2; i <= 83; i++) appendCue("resume", i, "00:00:03,000", "字幕" + i, "字幕" + i);
        var positions = new CopyOnWriteArrayList<Integer>();
        var fail = new AtomicBoolean(true);
        when(llm.processBatch(any())).thenAnswer(invocation -> {
            List<SubtitlePair> pairs = invocation.getArgument(0);
            int position = pairs.getFirst().position();
            positions.add(position);
            if (position == 50 && fail.getAndSet(false)) throw new IllegalStateException("timeout exhausted");
            return pairs.stream().map(pair -> new SubtitleRecognitionResult(pair, "[]")).toList();
        });
        assertThrows(IllegalStateException.class, () -> service.run(directory.toString()));
        var failed = repository.getLastJobExecution(repository.getLastJobInstance("subtitleImportJob"));
        assertEquals(BatchStatus.FAILED, failed.getStatus());
        assertEquals(4, failed.getStepExecutions().iterator().next().getWriteCount());
        positions.clear();
        var resumed = service.run(directory.toString());
        assertEquals(BatchStatus.COMPLETED, resumed.getStatus());
        assertEquals(failed.getJobInstance().getId(), resumed.getJobInstance().getId());
        assertNotEquals(failed.getId(), resumed.getId());
        assertEquals(List.of(40, 50, 60, 70, 80), positions.stream().sorted().toList());
    }
}