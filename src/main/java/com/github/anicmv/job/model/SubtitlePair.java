package com.github.anicmv.job.model;

import sh.casey.subtitler.model.Subtitle;

/**
 * @author anicmv
 * @date 2026/9/16 10:51
 * @description 一条 Item：同一文件对中按位置和时间轴严格对齐的一组简繁字幕。position 从 0 开始。
 */
public record SubtitlePair(String key, int position, Subtitle simplified, Subtitle traditional,
                           SubtitleSource simplifiedSource, SubtitleSource traditionalSource) {
    /** 供不落库的模型单元测试或独立调用使用；Writer 要求真实来源。 */
    public SubtitlePair(String key, int position, Subtitle simplified, Subtitle traditional) {
        this(key, position, simplified, traditional, null, null);
    }
}
