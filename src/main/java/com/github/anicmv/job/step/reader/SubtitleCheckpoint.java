package com.github.anicmv.job.step.reader;

import org.springframework.batch.infrastructure.item.ExecutionContext;
import com.github.anicmv.util.ContentHash;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 集中管理检查点格式；保留原有键以兼容已提交任务。完成清单由文件索引推导。 */
public record SubtitleCheckpoint(List<SubtitlePairScanner.PairFiles> files, Map<String, String> hashes,
                                 String fingerprint, int fileIndex, int cueIndex) {
    public SubtitleCheckpoint {
        files = List.copyOf(files);
        hashes = Map.copyOf(hashes);
        if (fileIndex < 0 || fileIndex > files.size() || cueIndex < 0
                || (fileIndex == files.size() && cueIndex != 0)) {
            throw new IllegalArgumentException("字幕检查点位置无效");
        }
    }

    public static int fileIndex(ExecutionContext context) {
        return context.getInt("pair.fileIndex", 0);
    }

    public static boolean isCompatible(ExecutionContext context) {
        return context.containsKey("pair.fingerprint") && !context.containsKey("pair.nextIndex");
    }

    public static SubtitleCheckpoint open(SubtitlePairScanner scanner, ExecutionContext context) {
        if (context.containsKey("pair.nextIndex")) {
            throw new IllegalArgumentException("旧版整文件检查点不兼容，请启动新的任务实例");
        }
        if (context.containsKey("pair.files")) {
            if (!canResume(scanner, context)) {
                throw new IllegalArgumentException("输入文件清单或内容已变化，不能恢复字幕检查点");
            }
            return restore(context);
        }
        var files = scanner.scan();
        var fingerprint = SubtitlePairScanner.fingerprint(files);
        if (context.containsKey("pair.fingerprint")
                && !fingerprint.equals(context.getString("pair.fingerprint"))) {
            throw new IllegalArgumentException("输入文件清单或内容已变化，不能恢复字幕检查点");
        }
        return new SubtitleCheckpoint(files, hashFiles(files), fingerprint, fileIndex(context),
                context.getInt("pair.cueIndex", 0));
    }

    public static SubtitleCheckpoint restore(ExecutionContext context) {
        return new SubtitleCheckpoint(savedFiles(context), savedHashes(context), context.getString("pair.fingerprint"),
                fileIndex(context), context.getInt("pair.cueIndex", 0));
    }

    public SubtitleCheckpoint at(int fileIndex, int cueIndex) {
        return new SubtitleCheckpoint(files, hashes, fingerprint, fileIndex, cueIndex);
    }

    public Map<String, String> completedFilesSince(int previousIndex) {
        var completed = new TreeMap<String, String>();
        for (var pair : files.subList(previousIndex, fileIndex)) {
            completed.put(pair.simplified(), hashes.get(pair.simplified()));
            completed.put(pair.traditional(), hashes.get(pair.traditional()));
        }
        return completed;
    }

    public void save(ExecutionContext context) {
        if (!context.containsKey("pair.files")) {
            context.put("pair.files", new ArrayList<>(files.stream()
                    .map(pair -> new ArrayList<>(List.of(pair.key(), pair.simplified(), pair.traditional()))).toList()));
            context.put("pair.archiveFiles", new HashMap<>(hashes));
        }
        context.putString("pair.fingerprint", fingerprint);
        context.putInt("pair.fileIndex", fileIndex);
        context.putInt("pair.cueIndex", cueIndex);
    }

    @SuppressWarnings("unchecked")
    private static List<SubtitlePairScanner.PairFiles> savedFiles(ExecutionContext context) {
        var saved = (List<List<String>>) context.get("pair.files");
        return saved.stream().map(pair -> new SubtitlePairScanner.PairFiles(pair.get(0), pair.get(1), pair.get(2))).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> savedHashes(ExecutionContext context) {
        return (Map<String, String>) context.get("pair.archiveFiles");
    }

    private static String hash(String filename) {
        try {
            return ContentHash.sha256(Path.of(filename));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Map<String, String> hashFiles(List<SubtitlePairScanner.PairFiles> files) {
        var hashes = new HashMap<String, String>();
        for (var pair : files) {
            for (String filename : pair.filenames()) {
                hashes.put(filename, hash(filename));
            }
        }
        return hashes;
    }

    /** 剩余文件的内容是否与检查点记录一致；任一文件不可读即认为不可恢复。 */
    private static boolean matchesSavedHashes(List<SubtitlePairScanner.PairFiles> files, Map<String, String> saved) {
        try {
            for (var pair : files) {
                for (String filename : pair.filenames()) {
                    if (!hash(filename).equals(saved.get(filename))) return false;
                }
            }
            return true;
        } catch (UncheckedIOException exception) {
            return false;
        }
    }

    public static boolean canResume(SubtitlePairScanner scanner, ExecutionContext context) {
        if (!context.containsKey("pair.files")) {
            return scanner.currentFingerprint().equals(context.getString("pair.fingerprint"));
        }
        var original = savedFiles(context);
        int next = context.getInt("pair.fileIndex", 0);
        if (next < 0 || next > original.size()) return false;
        // 全部处理提交后优先补归档，新文件留给下次任务。
        if (next == original.size()) return true;
        var remaining = original.subList(next, original.size());
        var completed = new HashSet<>(original.subList(0, next));
        var current = scanner.scan().stream().filter(pair -> !completed.contains(pair)).toList();
        if (!current.equals(remaining)) return false;
        return matchesSavedHashes(remaining, savedHashes(context));
    }

}
