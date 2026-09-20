package com.github.anicmv.job.step.reader;

import com.github.anicmv.job.model.SubtitleSource;
import com.github.anicmv.util.ContentHash;
import org.springframework.stereotype.Component;
import sh.casey.subtitler.model.SubtitleFile;
import sh.casey.subtitler.reader.SrtSubtitleReader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * @author anicmv
 * @date 2026/9/16 10:57
 * @description 解析私有快照，使来源哈希与解析字节一致。
 */
@Component
public class SubtitlePairLoader {
    /** 解析一对字幕文件的结果：简繁字幕内容与各自来源。 */
    public record LoadedPair(String key, SubtitleFile simplified, SubtitleFile traditional,
                             SubtitleSource simplifiedSource, SubtitleSource traditionalSource) {}
    /** 单个字幕文件的解析结果及其来源。 */
    private record LoadedFile(SubtitleFile subtitles, SubtitleSource source) {}

    public LoadedPair load(SubtitlePairScanner.PairFiles files) {
        var simplified = loadFile(files.simplified());
        var traditional = loadFile(files.traditional());
        if (simplified.subtitles().getSubtitles().isEmpty() || traditional.subtitles().getSubtitles().isEmpty()) {
            throw new IllegalArgumentException("字幕对内存在空字幕文件：" + files.key());
        }
        return new LoadedPair(files.key(), simplified.subtitles(), traditional.subtitles(),
                simplified.source(), traditional.source());
    }

    private LoadedFile loadFile(String filename) {
        Path snapshot = null;
        try {
            var path = Path.of(filename).toAbsolutePath().normalize();
            byte[] bytes = Files.readAllBytes(path);
            var source = new SubtitleSource(path.toString(), ContentHash.sha256(bytes));
            // subtitler 只提供路径 API；解析临时快照，避免哈希后源文件变化。
            snapshot = Files.createTempFile("subtitle-snapshot-", ".srt");
            Files.write(snapshot, bytes);
            var subtitles = new SrtSubtitleReader().read(snapshot.toString());
            subtitles.setPath(source.path());
            return new LoadedFile(subtitles, source);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } finally {
            if (snapshot != null) {
                try {
                    Files.deleteIfExists(snapshot);
                } catch (IOException exception) {
                    throw new UncheckedIOException("无法清理字幕快照", exception);
                }
            }
        }
    }
}
