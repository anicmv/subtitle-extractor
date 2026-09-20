package com.github.anicmv.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * @author anicmv
 * @date 2026/9/16 15:36
 * @description 词语映射表实体：全局唯一的简繁词对，mapping_key 为去重键。
 */
@Data
@TableName("term_mapping")
public class TermMappingEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String mappingKey;
    private String simplifiedTerm;
    private String traditionalTerm;
    private String reviewStatus;
    private String orthographicHint;
}
