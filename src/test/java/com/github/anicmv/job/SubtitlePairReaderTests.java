package com.github.anicmv.job;

import com.github.anicmv.job.config.SubtitleBatchProperties;
import com.github.anicmv.job.model.SubtitlePair;
import com.github.anicmv.job.step.reader.SubtitlePairBatchReader;
import com.github.anicmv.job.step.reader.SubtitlePairReader;
import com.github.anicmv.job.step.reader.SubtitlePairScanner;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.item.ExecutionContext;

import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author anicmv
 * @date 2026/9/16 10:57
 * @description 直接驱动 Reader 与 Scanner：文件名配对、SRT 解析、批次切分与检查点位置。
 */
class SubtitlePairReaderTests extends SubtitleBatchTestSupport {

    @Test
    void rejectsInvalidDirectoriesAndAmbiguousFilesButSkipsUnpairedFiles() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> service.run("relative/path"));
        srt("episode.chs.srt", "简体", false);
        var unpairedReader = reader();
        unpairedReader.open(new ExecutionContext());
        assertNull(unpairedReader.read());
        unpairedReader.close();
        srt("episode.cht.srt", "繁體", false);
        var reader = reader();
        reader.open(new ExecutionContext());
        assertEquals("episode", reader.read().key());
        assertNull(reader.read());
        reader.close();
        srt("episode.zh-cn.srt", "重复", false);
        assertThrows(IllegalArgumentException.class, () -> reader().open(new ExecutionContext()));
    }

    @Test
    void parsesUtf8BomMultilineAndResumesAtNextPair() throws Exception {
        srt("sample.zh-CN.srt", "简体第一行\n第二行", true);
        srt("sample.zh-TW.srt", "繁體第一行\n第二行", false);
        pair("z-next");
        SubtitlePairReader reader = reader();
        reader.open(new ExecutionContext());
        SubtitlePair item = reader.read();
        assertEquals("sample", item.key());
        assertEquals("简体第一行\n第二行", item.simplified().getText());
        assertEquals("繁體第一行\n第二行", item.traditional().getText());
        ExecutionContext checkpoint = new ExecutionContext();
        reader.update(checkpoint);
        reader.close();
        SubtitlePairReader reopened = reader();
        reopened.open(checkpoint);
        assertEquals("z-next", reopened.read().key());
        assertNull(reopened.read());
        reopened.update(checkpoint);
        reopened.close();
        reopened.open(checkpoint);
        assertNull(reopened.read());
        reopened.close();
    }

    @Test
    void pairsPrefixMarkersWithSuffixCounterpartAndSkipsUnknownNames() throws IOException {
        srt("CHS_episode01.srt", "简体字幕", false);
        srt("episode01.cht.srt", "繁體字幕", false);
        srt("notes.srt", "无关内容", false);
        SubtitlePairReader reader = reader();
        reader.open(new ExecutionContext());
        SubtitlePair pair = reader.read();
        assertEquals("episode01", pair.key());
        assertEquals("简体字幕", pair.simplified().getText());
        assertEquals("繁體字幕", pair.traditional().getText());
        assertNull(reader.read());
        reader.close();
    }

    @Test
    void pairsSimplifiedAndTraditionalTitlesWithoutChangingFilesOrContent() throws IOException {
        String simplifiedName = "CHS_龙之家族_S01E01.srt";
        String traditionalName = "CHT_龍之家族_S01E01.srt";
        srt(simplifiedName, "龙之家族字幕", false);
        srt(traditionalName, "龍之家族字幕", false);
        String simplifiedSource = Files.readString(directory.resolve(simplifiedName));
        String traditionalSource = Files.readString(directory.resolve(traditionalName));
        SubtitlePairReader reader = reader();
        reader.open(new ExecutionContext());
        try {
            SubtitlePair pair = reader.read();
            assertEquals("龙之家族_S01E01", pair.key());
            assertEquals("龙之家族字幕", pair.simplified().getText());
            assertEquals("龍之家族字幕", pair.traditional().getText());
            assertNull(reader.read());
        } finally {
            reader.close();
        }
        assertEquals(simplifiedSource, Files.readString(directory.resolve(simplifiedName)));
        assertEquals(traditionalSource, Files.readString(directory.resolve(traditionalName)));
    }

    @Test
    void pairsSpaceSeparatedPrefixNames() throws IOException {
        srt("CHS 黄泉使者 第14集_CatchPlay.srt", "简体字幕", false);
        srt("CHT 黃泉使者 第14集_CatchPlay.srt", "繁體字幕", false);
        SubtitlePairReader reader = reader();
        reader.open(new ExecutionContext());
        try {
            SubtitlePair pair = reader.read();
            assertEquals("黄泉使者 第14集_CatchPlay", pair.key());
            assertEquals("简体字幕", pair.simplified().getText());
            assertEquals("繁體字幕", pair.traditional().getText());
            assertNull(reader.read());
        } finally {
            reader.close();
        }
    }

    @Test
    void rejectsMissingMarkerConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new SubtitlePairScanner(directory.toString(), new SubtitleBatchProperties()));
    }

    @Test
    void resumesInsideFileThenMovesToNextFileWithoutSkippingItems() throws IOException {
        pair("a");
        appendCue("a", 2, "00:00:03,000", "第二条", "第二條");
        appendCue("a", 3, "00:00:03,000", "第三条", "第三條");
        pair("b");
        var reader = reader();
        reader.open(new ExecutionContext());
        assertEquals(0, reader.read().position());
        var checkpoint = new ExecutionContext();
        reader.update(checkpoint);
        // 模拟提交后继续读取但未提交，重启必须从最后提交位置恢复。
        assertEquals(1, reader.read().position());
        reader.close();
        reader = reader();
        reader.open(checkpoint);
        assertEquals("第二条", reader.read().simplified().getText());
        assertEquals(2, reader.read().position());
        assertEquals("b", reader.read().key());
        assertNull(reader.read());
    }

    @Test
    void batchesTenWithoutCrossingFilesAndRestoresCommittedPosition() throws IOException {
        pair("a");
        for (int i = 2; i <= 23; i++) appendCue("a", i, "00:00:03,000", "字幕" + i, "字幕" + i);
        pair("b");
        var reader = new SubtitlePairBatchReader(reader(), 10);
        reader.open(new ExecutionContext());
        assertEquals(10, reader.read().size());
        var checkpoint = new ExecutionContext();
        reader.update(checkpoint);
        assertEquals(10, reader.read().size());
        reader.close();
        reader.open(checkpoint);
        var resumed = reader.read();
        assertEquals(10, resumed.size());
        assertEquals(10, resumed.getFirst().position());
        assertEquals(3, reader.read().size());
        assertEquals("b", reader.read().getFirst().key());
        assertNull(reader.read());
        reader.close();
    }

    @Test
    void rejectsCountAndTimelineMismatch() throws IOException {
        pair("a");
        Files.writeString(directory.resolve("a.chs.srt"),
                "2\n00:00:03,000 --> 00:00:04,000\n多一条\n\n", java.nio.file.StandardOpenOption.APPEND);
        var countReader = reader();
        countReader.open(new ExecutionContext());
        assertTrue(assertThrows(IllegalArgumentException.class, countReader::read).getMessage().contains("条数"));
        pair("a");
        var traditional = directory.resolve("a.cht.srt");
        Files.writeString(traditional, Files.readString(traditional).replace("00:00:01,000", "00:00:01,100"));
        var timeReader = reader();
        timeReader.open(new ExecutionContext());
        assertTrue(assertThrows(IllegalArgumentException.class, timeReader::read).getMessage().contains("时间轴"));
    }

    @Test
    void rejectsChangedInputsAndLegacyCheckpoints() throws IOException {
        pair("a");
        var reader = reader();
        reader.open(new ExecutionContext());
        reader.read();
        var checkpoint = new ExecutionContext();
        reader.update(checkpoint);
        reader.close();
        srt("a.chs.srt", "内容变化", false);
        assertThrows(IllegalArgumentException.class, () -> reader().open(checkpoint));
        var legacy = new ExecutionContext();
        legacy.putInt("pair.nextIndex", 1);
        assertThrows(IllegalArgumentException.class, () -> reader().open(legacy));
    }
}