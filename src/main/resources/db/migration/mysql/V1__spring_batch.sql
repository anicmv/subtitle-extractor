-- 数据库初始化：Spring Batch 元数据、字幕业务表及映射审核结构。
-- Spring Batch 6.0.5 官方 schema-mysql.sql；随应用迁移一次。
-- 基于官方结构补充中文数据库 COMMENT；关联使用逻辑 ID，不建立外键约束。

CREATE TABLE BATCH_JOB_INSTANCE (
	JOB_INSTANCE_ID BIGINT  NOT NULL PRIMARY KEY COMMENT '作业实例 ID',
	VERSION BIGINT COMMENT '乐观锁版本号',
	JOB_NAME VARCHAR(100) NOT NULL COMMENT '作业名称',
	JOB_KEY VARCHAR(32) NOT NULL COMMENT '标识作业参数生成的实例键',
	constraint JOB_INST_UN unique (JOB_NAME, JOB_KEY)
) ENGINE=InnoDB COMMENT='Spring Batch 作业实例，同名作业与标识参数确定唯一实例';

CREATE TABLE BATCH_JOB_EXECUTION (
	JOB_EXECUTION_ID BIGINT  NOT NULL PRIMARY KEY COMMENT '作业执行 ID',
	VERSION BIGINT COMMENT '乐观锁版本号',
	JOB_INSTANCE_ID BIGINT NOT NULL COMMENT '作业实例 ID',
	CREATE_TIME DATETIME(6) NOT NULL COMMENT '执行记录创建时间',
	START_TIME DATETIME(6) DEFAULT NULL COMMENT '执行开始时间，未开始时为空',
	END_TIME DATETIME(6) DEFAULT NULL COMMENT '执行结束时间，未结束时为空',
	STATUS VARCHAR(10) COMMENT '执行状态',
	EXIT_CODE VARCHAR(2500) COMMENT '退出状态码',
	EXIT_MESSAGE VARCHAR(2500) COMMENT '退出说明或异常信息',
	LAST_UPDATED DATETIME(6) COMMENT '执行记录最后更新时间'
) ENGINE=InnoDB COMMENT='Spring Batch 作业执行记录，一次启动对应一次执行';

CREATE TABLE BATCH_JOB_EXECUTION_PARAMS (
	JOB_EXECUTION_ID BIGINT NOT NULL COMMENT '作业执行 ID',
	PARAMETER_NAME VARCHAR(100) NOT NULL COMMENT '参数名称',
	PARAMETER_TYPE VARCHAR(100) NOT NULL COMMENT '参数值的 Java 类型全限定名',
	PARAMETER_VALUE VARCHAR(2500) COMMENT '参数值的字符串表示',
	IDENTIFYING CHAR(1) NOT NULL COMMENT '是否参与作业实例标识，Y 是、N 否'
) ENGINE=InnoDB COMMENT='Spring Batch 作业执行参数';

CREATE TABLE BATCH_STEP_EXECUTION (
	STEP_EXECUTION_ID BIGINT  NOT NULL PRIMARY KEY COMMENT '步骤执行 ID',
	VERSION BIGINT NOT NULL COMMENT '乐观锁版本号',
	STEP_NAME VARCHAR(100) NOT NULL COMMENT '步骤名称',
	JOB_EXECUTION_ID BIGINT NOT NULL COMMENT '作业执行 ID',
	CREATE_TIME DATETIME(6) NOT NULL COMMENT '执行记录创建时间',
	START_TIME DATETIME(6) DEFAULT NULL COMMENT '执行开始时间，未开始时为空',
	END_TIME DATETIME(6) DEFAULT NULL COMMENT '执行结束时间，未结束时为空',
	STATUS VARCHAR(10) COMMENT '执行状态',
	COMMIT_COUNT BIGINT COMMENT '事务提交次数',
	READ_COUNT BIGINT COMMENT '成功读取的条目数',
	FILTER_COUNT BIGINT COMMENT '处理器过滤的条目数',
	WRITE_COUNT BIGINT COMMENT '成功写入的条目数',
	READ_SKIP_COUNT BIGINT COMMENT '读取阶段跳过的条目数',
	WRITE_SKIP_COUNT BIGINT COMMENT '写入阶段跳过的条目数',
	PROCESS_SKIP_COUNT BIGINT COMMENT '处理阶段跳过的条目数',
	ROLLBACK_COUNT BIGINT COMMENT '事务回滚次数',
	EXIT_CODE VARCHAR(2500) COMMENT '退出状态码',
	EXIT_MESSAGE VARCHAR(2500) COMMENT '退出说明或异常信息',
	LAST_UPDATED DATETIME(6) COMMENT '执行记录最后更新时间'
) ENGINE=InnoDB COMMENT='Spring Batch 步骤执行记录及处理统计';

CREATE TABLE BATCH_STEP_EXECUTION_CONTEXT (
	STEP_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY COMMENT '步骤执行 ID',
	SHORT_CONTEXT VARCHAR(2500) NOT NULL COMMENT '序列化上下文的短文本表示，过长时保存摘要',
	-- 归档清单包含整批字幕路径及内容哈希，可能超过 TEXT 的 65,535 字节上限，故用 LONGTEXT。
	SERIALIZED_CONTEXT LONGTEXT COMMENT '超过短文本容量时保存的完整序列化上下文'
) ENGINE=InnoDB COMMENT='Spring Batch 步骤执行上下文，用于检查点与失败恢复';

CREATE TABLE BATCH_JOB_EXECUTION_CONTEXT (
	JOB_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY COMMENT '作业执行 ID',
	SHORT_CONTEXT VARCHAR(2500) NOT NULL COMMENT '序列化上下文的短文本表示，过长时保存摘要',
	-- 与步骤上下文同理，归档清单可能超过 TEXT 上限。
	SERIALIZED_CONTEXT LONGTEXT COMMENT '超过短文本容量时保存的完整序列化上下文'
) ENGINE=InnoDB COMMENT='Spring Batch 作业执行上下文，用于跨步骤共享状态与恢复';

CREATE TABLE BATCH_STEP_EXECUTION_SEQ (
	ID BIGINT NOT NULL COMMENT '当前序列值，用于分配后续 ID',
	UNIQUE_KEY CHAR(1) NOT NULL COMMENT '固定单行标识，确保序列表只有一条记录',
	constraint UNIQUE_KEY_UN unique (UNIQUE_KEY)
) ENGINE=InnoDB COMMENT='Spring Batch 步骤执行 ID 序列表';

INSERT INTO BATCH_STEP_EXECUTION_SEQ (ID, UNIQUE_KEY) select * from (select 0 as ID, '0' as UNIQUE_KEY) as tmp where not exists(select * from BATCH_STEP_EXECUTION_SEQ);

CREATE TABLE BATCH_JOB_EXECUTION_SEQ (
	ID BIGINT NOT NULL COMMENT '当前序列值，用于分配后续 ID',
	UNIQUE_KEY CHAR(1) NOT NULL COMMENT '固定单行标识，确保序列表只有一条记录',
	constraint UNIQUE_KEY_UN unique (UNIQUE_KEY)
) ENGINE=InnoDB COMMENT='Spring Batch 作业执行 ID 序列表';

INSERT INTO BATCH_JOB_EXECUTION_SEQ (ID, UNIQUE_KEY) select * from (select 0 as ID, '0' as UNIQUE_KEY) as tmp where not exists(select * from BATCH_JOB_EXECUTION_SEQ);

CREATE TABLE BATCH_JOB_INSTANCE_SEQ (
	ID BIGINT NOT NULL COMMENT '当前序列值，用于分配后续 ID',
	UNIQUE_KEY CHAR(1) NOT NULL COMMENT '固定单行标识，确保序列表只有一条记录',
	constraint UNIQUE_KEY_UN unique (UNIQUE_KEY)
) ENGINE=InnoDB COMMENT='Spring Batch 作业实例 ID 序列表';

INSERT INTO BATCH_JOB_INSTANCE_SEQ (ID, UNIQUE_KEY) select * from (select 0 as ID, '0' as UNIQUE_KEY) as tmp where not exists(select * from BATCH_JOB_INSTANCE_SEQ);

-- MySQL 8.0.16+ / InnoDB。哈希键避免 utf8mb4 长字符串唯一索引超限。
-- hash 命中后由持久化服务核对完整原值，不能静默接受碰撞。
CREATE TABLE subtitle_file (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '文件版本主键',
    file_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '文件路径与内容哈希组合生成的 SHA-256 去重键',
    source_path TEXT NOT NULL COMMENT '原始字幕文件路径',
    content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原始文件字节内容的 SHA-256 哈希',
    CONSTRAINT uq_subtitle_file UNIQUE (file_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='字幕来源文件版本，按路径与文件内容哈希唯一标识';

CREATE TABLE subtitle_cue (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '字幕条目主键',
    file_id BIGINT NOT NULL COMMENT '所属字幕文件版本 ID',
    position INT NOT NULL COMMENT '文件内从 0 开始的条目位置，不依赖原编号',
    original_number INT COMMENT 'SRT 原始编号，可重复或跳号',
    start_ms BIGINT NOT NULL COMMENT '字幕开始时间，单位毫秒',
    end_ms BIGINT NOT NULL COMMENT '字幕结束时间，单位毫秒',
    raw_text LONGTEXT NOT NULL COMMENT '保留原始字符及换行的字幕正文',
    CONSTRAINT uq_subtitle_cue UNIQUE (file_id, position),

    CONSTRAINT ck_cue_position CHECK (position >= 0),
    CONSTRAINT ck_cue_time CHECK (start_ms >= 0 AND end_ms >= start_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='字幕文件版本中的原始条目，保留位置、时间轴与正文';

CREATE TABLE extraction_profile (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '提取配置主键',
    config_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化配置 JSON 的 SHA-256 去重键',
    config_json LONGTEXT NOT NULL COMMENT '不可变配置 JSON，包含模型参数与策略版本，不包含密钥',
    CONSTRAINT uq_extraction_profile UNIQUE (config_sha256)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='不可变的模型提取配置快照';

CREATE TABLE subtitle_recognition (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '识别记录主键',
    simplified_cue_id BIGINT NOT NULL COMMENT '简体字幕条目 ID',
    traditional_cue_id BIGINT NOT NULL COMMENT '繁体字幕条目 ID',
    profile_id BIGINT NOT NULL COMMENT '本次识别采用的提取配置 ID',
    -- NULL 仅用于事务内占位，写入所有证据后设置 UTC 时间并一起提交。
    completed_at DATETIME(6) COMMENT '识别及证据写入完成的 UTC 时间，事务占位时为空',
    CONSTRAINT uq_subtitle_recognition UNIQUE (simplified_cue_id, traditional_cue_id, profile_id),



    CONSTRAINT ck_recognition_cues CHECK (simplified_cue_id <> traditional_cue_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='指定配置下的简繁字幕对识别记录';

CREATE TABLE term_mapping (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '词语映射主键',
    mapping_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '两侧原始词语组合生成的 SHA-256 去重键',
    simplified_term TEXT NOT NULL COMMENT '简体原文中的词语',
    traditional_term TEXT NOT NULL COMMENT '繁体原文中的对应词语',
    review_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '人工审核状态：PENDING 待审核、APPROVED 通过、REJECTED 拒绝',
    orthographic_hint VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' COMMENT 'OpenCC 字形提示：UNKNOWN 未计算、MATCH 匹配、NO_MATCH 不匹配，不代表语义审核结论',
    CONSTRAINT uq_term_mapping UNIQUE (mapping_key),
    CONSTRAINT ck_mapping_review CHECK (review_status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_mapping_hint CHECK (orthographic_hint IN ('UNKNOWN', 'MATCH', 'NO_MATCH')),
    INDEX ix_mapping_review (review_status, id),
    CONSTRAINT ck_mapping_terms CHECK (
        CHAR_LENGTH(simplified_term) > 0 AND CHAR_LENGTH(traditional_term) > 0
        AND simplified_term <> traditional_term
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='全局去重的有方向简繁词语映射及人工审核状态';

CREATE TABLE term_mapping_evidence (
    recognition_id BIGINT NOT NULL COMMENT '提供原文出处的识别记录 ID',
    mapping_id BIGINT NOT NULL COMMENT '该识别结果提取的词语映射 ID',
    PRIMARY KEY (recognition_id, mapping_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='识别结果与词语映射的来源证据关联';

-- 显式保留原外键所需的非唯一索引，支持关联查询。
CREATE INDEX ix_job_execution_instance ON BATCH_JOB_EXECUTION (JOB_INSTANCE_ID);
CREATE INDEX ix_job_params_execution ON BATCH_JOB_EXECUTION_PARAMS (JOB_EXECUTION_ID);
CREATE INDEX ix_step_execution_job ON BATCH_STEP_EXECUTION (JOB_EXECUTION_ID);
CREATE INDEX ix_recognition_traditional ON subtitle_recognition (traditional_cue_id);
CREATE INDEX ix_recognition_profile ON subtitle_recognition (profile_id);
CREATE INDEX ix_evidence_mapping ON term_mapping_evidence (mapping_id);
