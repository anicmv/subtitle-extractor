package com.github.anicmv.job.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * @author anicmv
 * @date 2026/9/16 10:57
 * @description 字幕批处理参数（subtitle.batch.*）：事务批次数、模型请求组数与简繁体文件名标记。
 */
@Data
@ConfigurationProperties("subtitle.batch")
public class SubtitleBatchProperties {
    private int chunkSize = 4;
    private int requestSize = 10;
    private int concurrency = 2;
    private List<String> simplifiedMarkers;
    private List<String> traditionalMarkers;
}
