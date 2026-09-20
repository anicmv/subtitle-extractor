package com.github.anicmv.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.github.anicmv.entity.SubtitleCueEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * @author anicmv
 * @date 2026/9/16 15:36
 * @description subtitle_cue 表 Mapper：提供表访问与 insertIfAbsent 幂等插入。
 */
@Mapper
public interface SubtitleCueMapper extends BaseMapper<SubtitleCueEntity> {
    // 仅插入或锁定既有行，不覆盖不可变内容；不依赖 affectedRows/生成键判断是否插入。
    @Insert("""
            INSERT INTO subtitle_cue (file_id, position, original_number, start_ms, end_ms, raw_text)
            VALUES (#{fileId}, #{position}, #{originalNumber}, #{startMs}, #{endMs}, #{rawText})
            ON DUPLICATE KEY UPDATE id = id
            """)
    void insertIfAbsent(SubtitleCueEntity row);
}
