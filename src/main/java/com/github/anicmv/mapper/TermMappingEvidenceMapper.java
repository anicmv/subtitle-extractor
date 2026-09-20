package com.github.anicmv.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * @author anicmv
 * @date 2026/9/16 15:36
 * @description term_mapping_evidence 表 Mapper：写入识别结果与词语映射的关联证据。
 */
@Mapper
public interface TermMappingEvidenceMapper {
    @Insert("INSERT INTO term_mapping_evidence (recognition_id, mapping_id) VALUES (#{recognitionId}, #{mappingId})")
    void insert(@Param("recognitionId") long recognitionId, @Param("mappingId") long mappingId);
}
