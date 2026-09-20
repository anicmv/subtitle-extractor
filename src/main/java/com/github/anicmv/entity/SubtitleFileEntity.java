package com.github.anicmv.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * @author anicmv
 * @date 2026/9/16 15:36
 * @description 字幕文件表实体：记录配对名称、来源绝对路径与内容 SHA-256。
 */
@Data
@TableName("subtitle_file")
public class SubtitleFileEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String fileKey;
    private String sourcePath;
    private String contentSha256;
}
