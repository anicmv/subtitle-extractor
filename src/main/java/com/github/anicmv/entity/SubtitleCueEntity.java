package com.github.anicmv.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * @author anicmv
 * @date 2026/9/16 15:36
 * @description 字幕条目表实体：按文件加位置唯一定位，保留原始序号、毫秒时间轴与原文。
 */
@Data
@TableName("subtitle_cue")
public class SubtitleCueEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long fileId;
    private Integer position;
    private Integer originalNumber;
    private Long startMs;
    private Long endMs;
    private String rawText;
}
