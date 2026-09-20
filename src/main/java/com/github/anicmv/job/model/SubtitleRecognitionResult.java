package com.github.anicmv.job.model;

/**
 * @author anicmv
 * @date 2026/9/16 10:51
 * @description 将识别结果连同来源交给 Writer，避免丢失模型输出。
 */
public record SubtitleRecognitionResult(SubtitlePair pair, String content) {
}
