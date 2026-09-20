package com.github.anicmv.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * @author anicmv
 * @date 2026/9/16 15:36
 * @description 识别结果表实体：一组简繁字幕条目在指定提取档案下的识别记录，completedAt 为空表示未完成。
 */
@Data
@TableName("subtitle_recognition")
public class SubtitleRecognitionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long simplifiedCueId;
    private Long traditionalCueId;
    private Long profileId;
    private java.time.LocalDateTime completedAt;
}
