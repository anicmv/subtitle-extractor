-- MySQL 8.0.16+ / InnoDB。哈希键避免 utf8mb4 长字符串唯一索引超限。
-- hash 命中后由持久化服务核对完整原值，不能静默接受碰撞。
CREATE TABLE subtitle_file (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    file_key CHAR(64) NOT NULL,
    source_path TEXT NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    CONSTRAINT uq_subtitle_file UNIQUE (file_key)
);

CREATE TABLE subtitle_cue (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    file_id BIGINT NOT NULL,
    position INT NOT NULL,
    original_number INT,
    start_ms BIGINT NOT NULL,
    end_ms BIGINT NOT NULL,
    raw_text LONGTEXT NOT NULL,
    CONSTRAINT uq_subtitle_cue UNIQUE (file_id, position),

    CONSTRAINT ck_cue_position CHECK (position >= 0),
    CONSTRAINT ck_cue_time CHECK (start_ms >= 0 AND end_ms >= start_ms)
);

CREATE TABLE extraction_profile (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    config_sha256 CHAR(64) NOT NULL,
    config_json LONGTEXT NOT NULL,
    CONSTRAINT uq_extraction_profile UNIQUE (config_sha256)
);

CREATE TABLE subtitle_recognition (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    simplified_cue_id BIGINT NOT NULL,
    traditional_cue_id BIGINT NOT NULL,
    profile_id BIGINT NOT NULL,
    -- NULL 仅用于事务内占位，写入所有证据后设置 UTC 时间并一起提交。
    completed_at DATETIME(6),
    CONSTRAINT uq_subtitle_recognition UNIQUE (simplified_cue_id, traditional_cue_id, profile_id),



    CONSTRAINT ck_recognition_cues CHECK (simplified_cue_id <> traditional_cue_id)
);

CREATE TABLE term_mapping (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    mapping_key CHAR(64) NOT NULL,
    simplified_term TEXT NOT NULL,
    traditional_term TEXT NOT NULL,
    review_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (review_status IN ('PENDING', 'APPROVED', 'REJECTED')),
    orthographic_hint VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' CHECK (orthographic_hint IN ('UNKNOWN', 'MATCH', 'NO_MATCH')),
    CONSTRAINT uq_term_mapping UNIQUE (mapping_key),
    CONSTRAINT ck_mapping_terms CHECK (
        CHAR_LENGTH(simplified_term) > 0 AND CHAR_LENGTH(traditional_term) > 0
        AND simplified_term <> traditional_term
    )
);

CREATE TABLE term_mapping_evidence (
    recognition_id BIGINT NOT NULL,
    mapping_id BIGINT NOT NULL,
    PRIMARY KEY (recognition_id, mapping_id)
);

-- 数据库表及字段注释，与 MySQL 建表迁移保持一致。
COMMENT ON TABLE subtitle_file IS '字幕来源文件版本，按路径与文件内容哈希唯一标识';
COMMENT ON COLUMN subtitle_file.id IS '文件版本主键';
COMMENT ON COLUMN subtitle_file.file_key IS '文件路径与内容哈希组合生成的 SHA-256 去重键';
COMMENT ON COLUMN subtitle_file.source_path IS '原始字幕文件路径';
COMMENT ON COLUMN subtitle_file.content_sha256 IS '原始文件字节内容的 SHA-256 哈希';
COMMENT ON TABLE subtitle_cue IS '字幕文件版本中的原始条目，保留位置、时间轴与正文';
COMMENT ON COLUMN subtitle_cue.id IS '字幕条目主键';
COMMENT ON COLUMN subtitle_cue.file_id IS '所属字幕文件版本 ID';
COMMENT ON COLUMN subtitle_cue.position IS '文件内从 0 开始的条目位置，不依赖原编号';
COMMENT ON COLUMN subtitle_cue.original_number IS 'SRT 原始编号，可重复或跳号';
COMMENT ON COLUMN subtitle_cue.start_ms IS '字幕开始时间，单位毫秒';
COMMENT ON COLUMN subtitle_cue.end_ms IS '字幕结束时间，单位毫秒';
COMMENT ON COLUMN subtitle_cue.raw_text IS '保留原始字符及换行的字幕正文';
COMMENT ON TABLE extraction_profile IS '不可变的模型提取配置快照';
COMMENT ON COLUMN extraction_profile.id IS '提取配置主键';
COMMENT ON COLUMN extraction_profile.config_sha256 IS '规范化配置 JSON 的 SHA-256 去重键';
COMMENT ON COLUMN extraction_profile.config_json IS '不可变配置 JSON，包含模型参数与策略版本，不包含密钥';
COMMENT ON TABLE subtitle_recognition IS '指定配置下的简繁字幕对识别记录';
COMMENT ON COLUMN subtitle_recognition.id IS '识别记录主键';
COMMENT ON COLUMN subtitle_recognition.simplified_cue_id IS '简体字幕条目 ID';
COMMENT ON COLUMN subtitle_recognition.traditional_cue_id IS '繁体字幕条目 ID';
COMMENT ON COLUMN subtitle_recognition.profile_id IS '本次识别采用的提取配置 ID';
COMMENT ON COLUMN subtitle_recognition.completed_at IS '识别及证据写入完成的 UTC 时间，事务占位时为空';
COMMENT ON TABLE term_mapping IS '全局去重的有方向简繁词语映射及人工审核状态';
COMMENT ON COLUMN term_mapping.id IS '词语映射主键';
COMMENT ON COLUMN term_mapping.mapping_key IS '两侧原始词语组合生成的 SHA-256 去重键';
COMMENT ON COLUMN term_mapping.simplified_term IS '简体原文中的词语';
COMMENT ON COLUMN term_mapping.traditional_term IS '繁体原文中的对应词语';
COMMENT ON COLUMN term_mapping.review_status IS '人工审核状态：PENDING 待审核、APPROVED 通过、REJECTED 拒绝';
COMMENT ON COLUMN term_mapping.orthographic_hint IS 'OpenCC 字形提示：UNKNOWN 未计算、MATCH 匹配、NO_MATCH 不匹配，不代表语义审核结论';
COMMENT ON TABLE term_mapping_evidence IS '识别结果与词语映射的来源证据关联';
COMMENT ON COLUMN term_mapping_evidence.recognition_id IS '提供原文出处的识别记录 ID';
COMMENT ON COLUMN term_mapping_evidence.mapping_id IS '该识别结果提取的词语映射 ID';
CREATE INDEX ix_recognition_traditional ON subtitle_recognition (traditional_cue_id);
CREATE INDEX ix_recognition_profile ON subtitle_recognition (profile_id);
CREATE INDEX ix_evidence_mapping ON term_mapping_evidence (mapping_id);
