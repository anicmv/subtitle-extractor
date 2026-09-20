package com.github.anicmv.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * @author anicmv
 * @date 2026/9/16 15:36
 * @description 提取配置档案表实体：固定一份提取配置的哈希与 JSON 快照。
 */
@Data
@TableName("extraction_profile")
public class ExtractionProfileEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String configSha256;
    private String configJson;
}
