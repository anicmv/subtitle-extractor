package com.github.anicmv.job.model;

import java.nio.file.Path;

/**
 * @author anicmv
 * @date 2026/9/16 15:36
 * @description 文件来源与解析时的内容版本，不以配对名称代替文件身份。
 */
public record SubtitleSource(String path, String sha256) {
    public SubtitleSource {
        if (path == null || !Path.of(path).isAbsolute() || sha256 == null
                || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("字幕来源必须包含绝对路径和 SHA-256");
        }
        path = Path.of(path).normalize().toString();
    }
}
