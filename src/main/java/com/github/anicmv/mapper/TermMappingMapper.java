package com.github.anicmv.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.github.anicmv.entity.TermMappingEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * @author anicmv
 * @date 2026/9/16 15:36
 * @description term_mapping 表 Mapper：提供表访问与 insertIfAbsent 幂等插入。
 */
@Mapper
public interface TermMappingMapper extends BaseMapper<TermMappingEntity> {
    // 仅插入或锁定既有行，不覆盖不可变内容；不依赖 affectedRows/生成键判断是否插入。
    @Insert("""
            INSERT INTO term_mapping (mapping_key, simplified_term, traditional_term, review_status, orthographic_hint)
            VALUES (#{mappingKey}, #{simplifiedTerm}, #{traditionalTerm}, #{reviewStatus}, #{orthographicHint})
            ON DUPLICATE KEY UPDATE id = id
            """)
    void insertIfAbsent(TermMappingEntity row);
}
