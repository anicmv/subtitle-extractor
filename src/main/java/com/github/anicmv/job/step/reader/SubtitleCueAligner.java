package com.github.anicmv.job.step.reader;

import com.github.anicmv.job.model.SubtitlePair;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * @author anicmv
 * @date 2026/9/16 10:57
 * @description 将同一文件对的简繁字幕按位置逐条对齐；条数或时间轴不一致直接抛异常。
 */
@Component
public class SubtitleCueAligner {
    public List<SubtitlePair> align(SubtitlePairLoader.LoadedPair files) {
        var simplified = files.simplified().getSubtitles();
        var traditional = files.traditional().getSubtitles();
        if (simplified.size() != traditional.size()) {
            throw new IllegalArgumentException("简繁字幕条数不一致：" + files.key()
                    + " 简体=" + simplified.size() + " 繁体=" + traditional.size());
        }
        List<SubtitlePair> pairs = new ArrayList<>(simplified.size());
        for (int i = 0; i < simplified.size(); i++) {
            var sc = simplified.get(i);
            var tc = traditional.get(i);
            if (!Objects.equals(sc.getStart(), tc.getStart()) || !Objects.equals(sc.getEnd(), tc.getEnd())) {
                throw new IllegalArgumentException("简繁字幕时间轴不一致：" + files.key() + " 第 " + (i + 1) + " 组");
            }
            pairs.add(new SubtitlePair(files.key(), i, sc, tc, files.simplifiedSource(), files.traditionalSource()));
        }
        return List.copyOf(pairs);
    }
}
