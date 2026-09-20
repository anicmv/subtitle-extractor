package com.github.anicmv.job.step.reader;

import com.github.anicmv.job.config.SubtitleBatchProperties;
import com.github.houbb.opencc4j.util.ZhConverterUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author anicmv
 * @date 2026/9/16 10:51
 * @description 扫描当前层的文件路径，按配对名称排序；不加载字幕内容。
 */
@Slf4j
public final class SubtitlePairScanner {
    private final String directory;
    private final Map<String, String> languages = new LinkedHashMap<>();
    private final Pattern suffixPattern;
    private final Pattern prefixPattern;

    public SubtitlePairScanner(String directory, SubtitleBatchProperties properties) {
        this.directory = directory;
        registerMarkers("简体", properties.getSimplifiedMarkers());
        registerMarkers("繁体", properties.getTraditionalMarkers());
        String alternatives = languages.keySet().stream().map(Pattern::quote).collect(Collectors.joining("|"));
        suffixPattern = Pattern.compile("^(.+)[._-](" + alternatives + ")\\.srt$", Pattern.CASE_INSENSITIVE);
        prefixPattern = Pattern.compile("^(" + alternatives + ")[._ -](.+)\\.srt$", Pattern.CASE_INSENSITIVE);
    }

    private void registerMarkers(String language, List<String> markers) {
        if (markers == null || markers.isEmpty()) {
            throw new IllegalArgumentException(language + "标记不能为空");
        }
        for (String marker : markers) {
            String normalized = marker == null ? null : marker.trim().toLowerCase(Locale.ROOT);
            if (normalized == null || normalized.isEmpty() || languages.putIfAbsent(normalized, language) != null) {
                throw new IllegalArgumentException("字幕语言标记为空或重复：" + marker);
            }
        }
    }

    /**
     * 后缀标记优先（title.chs.srt），未命中再试前缀标记（CHS_title.srt）；都不命中返回 null。
     */
    private Located locate(String filename) {
        Matcher suffix = suffixPattern.matcher(filename);
        if (suffix.matches()) {
            return new Located(suffix.group(1), languages.get(suffix.group(2).toLowerCase(Locale.ROOT)));
        }
        Matcher prefix = prefixPattern.matcher(filename);
        if (prefix.matches()) {
            return new Located(prefix.group(2), languages.get(prefix.group(1).toLowerCase(Locale.ROOT)));
        }
        return null;
    }

    List<PairFiles> scan() {
        Path root = requireReadableRoot();
        log.info("开始扫描字幕目录={}", root);
        var result = completePairs(groupByPairKey(root));
        log.info("字幕扫描完成 目录={} 文件对数={}", root, result.size());
        return result;
    }

    private Path requireReadableRoot() {
        if (directory == null || directory.isBlank()) {
            throw new IllegalArgumentException("必须提供字幕目录");
        }
        Path root = Path.of(directory);
        if (!root.isAbsolute() || !Files.isDirectory(root) || !Files.isReadable(root)) {
            throw new IllegalArgumentException("需要一个可读的绝对目录：" + directory);
        }
        return root;
    }

    /** 按配对键归组；只规范化键，原始文件路径与字幕内容保持不变。 */
    private Map<String, Map<String, String>> groupByPairKey(Path root) {
        Map<String, Map<String, String>> pairs = new TreeMap<>();
        try (Stream<Path> paths = Files.list(root)) {
            paths.filter(Files::isRegularFile).sorted().forEach(path -> {
                String filename = path.getFileName().toString();
                if (!filename.toLowerCase(Locale.ROOT).endsWith(".srt")) {
                    return;
                }
                Located located = locate(filename);
                if (located == null) {
                    log.warn("跳过无法识别的 SRT 文件名={}；期望 title.chs.srt 或 CHS_title.srt", filename);
                    return;
                }
                String key = ZhConverterUtil.toSimple(located.key());
                String language = located.language();
                Map<String, String> pair = pairs.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
                if (pair.putIfAbsent(language, path.toAbsolutePath().normalize().toString()) != null) {
                    throw new IllegalArgumentException(key + " 存在重复的" + language + "字幕文件");
                }
            });
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return pairs;
    }

    /** 只保留简繁齐全的配对；未配对的文件跳过，留给下次任务。 */
    private static List<PairFiles> completePairs(Map<String, Map<String, String>> pairs) {
        return pairs.entrySet().stream().filter(entry -> {
            var files = entry.getValue();
            if (files.size() != 2) {
                log.warn("跳过未配对字幕={} 缺少={} 已有文件={}", entry.getKey(),
                        files.containsKey("简体") ? "繁体" : "简体", files.values());
                return false;
            }
            return true;
        }).map(entry -> {
            var files = entry.getValue();
            return new PairFiles(entry.getKey(), files.get("简体"), files.get("繁体"));
        }).toList();
    }

    /** 目录中的一个文件及其简繁语言标记。 */
    private record Located(String key, String language) {}

    /** 配对成功的一对简繁文件绝对路径。 */
    public record PairFiles(String key, String simplified, String traditional) {
        /** 简体、繁体文件路径，顺序固定，供遍历使用。 */
        public List<String> filenames() {
            return List.of(simplified, traditional);
        }
    }

    public String currentFingerprint() {
        return fingerprint(scan());
    }

    static String fingerprint(List<SubtitlePairScanner.PairFiles> files) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var pair : files) {
                for (String filename : pair.filenames()) {
                    digest.update(filename.getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    digest.update(fileDigest(filename));
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** 流式读取文件内容摘要，避免整份载入内存。 */
    private static byte[] fileDigest(String filename) throws IOException, NoSuchAlgorithmException {
        var contentDigest = MessageDigest.getInstance("SHA-256");
        try (var stream = new DigestInputStream(Files.newInputStream(Path.of(filename)), contentDigest)) {
            stream.transferTo(OutputStream.nullOutputStream());
        }
        return contentDigest.digest();
    }
}
