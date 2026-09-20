package com.github.anicmv.job.step.reader;

import com.github.anicmv.job.model.SubtitlePair;
import com.github.anicmv.job.step.SubtitleArchiver;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamReader;

import java.util.ArrayList;
import java.util.List;

/**
 * @author anicmv
 * @date 2026/9/16 10:46
 * @description 管理文件对/字幕组遍历和检查点；一次 read 只返回一组简繁字幕。
 */
@Slf4j
public class SubtitlePairReader implements ItemStreamReader<SubtitlePair> {
    private final SubtitlePairScanner scanner;
    private final SubtitlePairLoader loader;
    private final SubtitleCueAligner aligner;
    private List<SubtitlePairScanner.PairFiles> files = List.of();
    private List<SubtitlePair> cues = List.of();
    private int fileIndex;
    private int cueIndex;
    private SubtitleCheckpoint checkpoint;

    public SubtitlePairReader(SubtitlePairScanner scanner, SubtitlePairLoader loader, SubtitleCueAligner aligner) {
        this.scanner = scanner;
        this.loader = loader;
        this.aligner = aligner;
    }

    @Override
    public SubtitlePair read() {
        if (fileIndex >= files.size()) return null;
        if (cues.isEmpty()) {
            log.info("开始加载字幕 文件对={}/{} 字幕对={}", fileIndex + 1, files.size(), files.get(fileIndex).key());
            cues = aligner.align(loader.load(files.get(fileIndex)));
            log.info("字幕加载并对齐完成 字幕对={} 组数={}", files.get(fileIndex).key(), cues.size());
        }
        var item = cues.get(cueIndex++);
        if (cueIndex == cues.size()) {
            fileIndex++;
            cueIndex = 0;
            cues = List.of();
        }
        return item;
    }

    public List<SubtitlePair> readBatch(int size) {
        if (size < 1) throw new IllegalArgumentException("批次大小必须大于 0");
        if (fileIndex >= files.size()) return null;
        int currentFile = fileIndex;
        var batch = new ArrayList<SubtitlePair>();
        while (batch.size() < size && fileIndex == currentFile) batch.add(read());
        return List.copyOf(batch);
    }

    @Override
    public void open(ExecutionContext context) {
        close();
        checkpoint = SubtitleCheckpoint.open(scanner, context);
        files = checkpoint.files();
        fileIndex = checkpoint.fileIndex();
        cueIndex = checkpoint.cueIndex();
        try {
            SubtitleArchiver.archive(checkpoint.completedFilesSince(0));
        } catch (IOException exception) {
            throw new IllegalArgumentException("已提交字幕无法恢复归档", exception);
        }
        log.info("字幕读取器就绪 文件对数={} 下一文件索引={} 下一字幕组索引={}", files.size(), fileIndex, cueIndex);
        if (cueIndex > 0) {
            cues = aligner.align(loader.load(files.get(fileIndex)));
            if (cueIndex >= cues.size()) throw new IllegalArgumentException("字幕组检查点位置无效：" + cueIndex);
        }
    }

    @Override
    public void update(ExecutionContext context) {
        checkpoint.at(fileIndex, cueIndex).save(context);
    }

    @Override
    public void close() {
        files = List.of();
        cues = List.of();
        fileIndex = 0;
        cueIndex = 0;
    }
}
