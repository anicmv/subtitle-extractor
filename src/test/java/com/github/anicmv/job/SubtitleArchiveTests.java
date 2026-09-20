package com.github.anicmv.job;

import com.github.anicmv.job.model.SubtitlePair;
import com.github.anicmv.job.model.SubtitleRecognitionResult;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * @author anicmv
 * @date 2026/9/16 10:57
 * @description 归档行为：只移动完整配对、跟随 chunk 提交、允许下游取走已归档文件、支持补归档。
 */
class SubtitleArchiveTests extends SubtitleBatchTestSupport {

    @Test
    void processesAndArchivesOnlyCompletePairsLeavingUnpairedFiles() throws Exception {
        pair("complete");
        srt("CHT CHIIKAWA 第357集_Viu.srt", "繁體", false);
        var execution = service.run(directory.toString());
        assertEquals(BatchStatus.COMPLETED, execution.getStatus());
        assertTrue(Files.exists(directory.resolve("processed/complete.chs.srt")));
        assertTrue(Files.exists(directory.resolve("processed/complete.cht.srt")));
        assertTrue(Files.exists(directory.resolve("CHT CHIIKAWA 第357集_Viu.srt")));
        assertTrue(Files.notExists(directory.resolve("processed/CHT CHIIKAWA 第357集_Viu.srt")));
        var rerun = service.run(directory.toString());
        assertEquals(BatchStatus.COMPLETED, rerun.getStatus());
        assertTrue(Files.exists(directory.resolve("CHT CHIIKAWA 第357集_Viu.srt")));
    }

    @Test
    void archivesFileInCommittedChunkAndResumesFailure() throws Exception {
        pair("a");
        for (int i = 2; i <= 40; i++) appendCue("a", i, "00:00:03,000", "字幕" + i, "字幕" + i);
        pair("b");
        var fail = new AtomicBoolean(true);
        var seen = new CopyOnWriteArrayList<String>();
        when(llm.processBatch(any())).thenAnswer(invocation -> {
            List<SubtitlePair> pairs = invocation.getArgument(0);
            String key = pairs.getFirst().key();
            seen.add(key);
            if (key.equals("b")) {
                assertTrue(Files.exists(directory.resolve("processed/a.chs.srt")));
                assertTrue(Files.exists(directory.resolve("processed/a.cht.srt")));
                if (fail.getAndSet(false)) throw new IllegalStateException("failure in second file");
            }
            return pairs.stream().map(pair -> new SubtitleRecognitionResult(pair, "[]")).toList();
        });
        assertThrows(IllegalStateException.class, () -> service.run(directory.toString()));
        var failed = repository.getLastJobExecution(repository.getLastJobInstance("subtitleImportJob"));
        assertTrue(Files.exists(directory.resolve("b.chs.srt")));
        seen.clear();
        var resumed = service.run(directory.toString());
        assertEquals(BatchStatus.COMPLETED, resumed.getStatus());
        assertEquals(1, resumed.getStepExecutions().size());
        assertEquals(failed.getJobInstance().getId(), resumed.getJobInstance().getId());
        assertEquals(List.of("b"), seen);
        assertTrue(Files.exists(directory.resolve("processed/b.chs.srt")));
    }

    @Test
    void laterCommitsDoNotReadPreviouslyArchivedFiles() throws Exception {
        pair("a");
        for (int i = 2; i <= 40; i++) appendCue("a", i, "00:00:03,000", "字幕" + i, "字幕" + i);
        pair("b");
        when(llm.processBatch(any())).thenAnswer(invocation -> {
            List<SubtitlePair> pairs = invocation.getArgument(0);
            if (pairs.getFirst().key().equals("b")) {
                // 下游已取走上一个 chunk 的归档文件，不应阻止后续 chunk 提交。
                Files.delete(directory.resolve("processed/a.chs.srt"));
                Files.delete(directory.resolve("processed/a.cht.srt"));
            }
            return pairs.stream().map(pair -> new SubtitleRecognitionResult(pair, "[]")).toList();
        });
        assertEquals(BatchStatus.COMPLETED, service.run(directory.toString()).getStatus());
        assertTrue(Files.exists(directory.resolve("processed/b.chs.srt")));
        assertTrue(Files.exists(directory.resolve("processed/b.cht.srt")));
    }

    @Test
    void resumesPartialArchiveWithoutRepeatingProcessing() throws Exception {
        pair("archive");
        Files.createDirectories(directory.resolve("processed"));
        Path conflict = directory.resolve("processed/archive.cht.srt");
        Files.writeString(conflict, "existing file");
        assertThrows(IllegalStateException.class, () -> service.run(directory.toString()));
        var failed = repository.getLastJobExecution(repository.getLastJobInstance("subtitleImportJob"));
        assertEquals("existing file", Files.readString(conflict));
        assertTrue(Files.exists(directory.resolve("archive.chs.srt")));
        assertTrue(Files.exists(directory.resolve("archive.cht.srt")));
        Files.delete(conflict);
        // 模拟移动了一半后进程退出，归档事务尚未提交。
        Files.move(directory.resolve("archive.chs.srt"), directory.resolve("processed/archive.chs.srt"));
        pair("new"); // 归档只处理原任务清单，不移动后来添加的文件。
        probe.threads.clear();
        var resumed = service.run(directory.toString());
        assertEquals(BatchStatus.COMPLETED, resumed.getStatus());
        assertEquals(failed.getJobInstance().getId(), resumed.getJobInstance().getId());
        assertTrue(probe.threads.isEmpty());
        assertTrue(Files.exists(conflict));
        assertTrue(Files.notExists(directory.resolve("archive.cht.srt")));
        assertTrue(Files.exists(directory.resolve("new.chs.srt")));
        assertTrue(Files.exists(directory.resolve("new.cht.srt")));
    }
}