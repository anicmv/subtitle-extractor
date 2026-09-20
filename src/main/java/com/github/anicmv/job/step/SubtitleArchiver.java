package com.github.anicmv.job.step;

import com.github.anicmv.util.ContentHash;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** 校验并归档已提交文件，允许恢复移动了一半的文件对。 */
@Slf4j
public final class SubtitleArchiver {
    private SubtitleArchiver() {}

    public static void archive(Map<String, String> files) throws IOException {
        if (files.isEmpty()) return;
        // 先检查整份清单，避免已知冲突导致一对字幕只移动一半。
        for (var entry : files.entrySet()) {
            Path source = Path.of(entry.getKey());
            Path target = target(source);
            if (Files.exists(source)) {
                verify(source, entry.getValue());
                if (Files.exists(target)) throw new IOException("归档目标已存在，不覆盖：" + target);
            } else {
                verify(target, entry.getValue());
            }
        }
        for (var entry : files.entrySet()) {
            Path source = Path.of(entry.getKey());
            if (Files.exists(source)) {
                verify(source, entry.getValue());
                Path target = target(source);
                Files.createDirectories(target.getParent());
                Files.move(source, target);
                log.info("字幕归档完成 {} -> {}", source, target);
            }
        }
    }

    private static Path target(Path source) {
        return source.getParent().resolve("processed").resolve(source.getFileName());
    }

    private static void verify(Path file, String expectedHash) throws IOException {
        if (!Files.isRegularFile(file) || !ContentHash.sha256(file).equals(expectedHash)) {
            throw new IOException("字幕缺失或内容已变化，不能归档：" + file);
        }
    }
}
