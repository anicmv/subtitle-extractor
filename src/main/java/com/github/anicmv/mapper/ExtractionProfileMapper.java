package com.github.anicmv.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.github.anicmv.entity.ExtractionProfileEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * @author anicmv
 * @date 2026/9/16 15:36
 * @description extraction_profile 表 Mapper：提供表访问与 insertIfAbsent 幂等插入。
 */
@Mapper
public interface ExtractionProfileMapper extends BaseMapper<ExtractionProfileEntity> {
    // 仅插入或锁定既有行，不覆盖不可变内容；不依赖 affectedRows/生成键判断是否插入。
    @Insert("""
            INSERT INTO extraction_profile (config_sha256, config_json)
            VALUES (#{configSha256}, #{configJson})
            ON DUPLICATE KEY UPDATE id = id
            """)
    void insertIfAbsent(ExtractionProfileEntity row);
}
