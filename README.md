# subtitle-extractor

## 项目简介

从成对的简体 / 繁体 SRT 字幕中抽取简繁词语映射（如 `软件 → 軟體`、`赫敏 → 妙麗`），落库为带原文出处、可人工审核的映射库。

XXL-JOB 触发批处理后，Spring Batch 扫描传入目录、按命名约定配对简繁字幕、按位置与时间轴严格对齐，
分批提交给 OpenAI 兼容的 Chat Completions 接口；模型返回的映射经协议校验后按 chunk 事务原子写入 MySQL，
同时记录两侧文件版本、字幕位置、时间和原文，处理完的文件对归档到 `processed/`。

- **幂等**：文件按「路径 + 内容 SHA-256」去重，映射按两侧原词组合去重，重复写入保留首次结果，相同来源与配置不产生重复数据。
- **可续跑**：每个 chunk 提交即检查点，块内任一失败整块回滚，重启从最后一次成功提交继续，已提交的字幕不重跑。
- **可追溯**：每条映射沿逻辑 ID 回到两侧文件路径、内容哈希、字幕位置与原文；一词多译、多处来源分别留证，空映射也会记录。
- **待审核**：映射默认 `PENDING`，人工审核改为 `APPROVED` / `REJECTED`，模型输出不自动发布。
- **配置快照**：模型参数与策略版本以不可变 JSON 存库并按哈希去重，改配置可生成独立的结果集。

Java 25 / Spring Boot 4.1.1 / Spring Batch 6，使用 XXL-JOB 3.4.2 调度。
SRT 解析使用作者最新正式版 `com.github.caseyscarborough:subtitler:2.0.0`（JitPack）。
模型调用使用 Spring AI 2.0.1 的 OpenAI starter，落库使用 MyBatis-Plus 3.5.17，表结构由 Flyway 管理，要求 MySQL 8.0.16+。

## 流程与并发

`subtitleImportJobHandler` → 同步启动 `subtitleImportJob` → 唯一的处理 step `subtitleStep`。

Reader 打开时由 `SubtitlePairScanner` 扫描当前目录并校验简繁文件配对。
`SubtitlePairLoader` 每次解析一对 SRT，`SubtitleCueAligner` 按位置严格对齐字幕条目，
要求简繁条数以及每组起止时间完全一致；序号保留原值，不作为对齐依据。
`SubtitlePairBatchReader` 每次 `read()` 返回同一文件最多 10 组简繁字幕，处理完当前文件对后才加载下一对。
空目录正常结束；内存中只保留当前文件对的字幕和目录的路径索引。

Job 仅有一个处理 step；每个 chunk 成功提交后，由 Writer 将已处理完的简繁文件对移至传入目录下的 `processed/`，不存在时自动创建。
例如传参 `/data/subtitles`，归档目录就是 `/data/subtitles/processed`。保留原文件名，不覆盖已有同名文件。
当前文件处理失败时保留源文件，之前已完成的文件保持归档；归档失败时任务失败，排除冲突后再次调度会继续归档，不重复处理已提交字幕。
移动不参与数据库事务；若中途中断，下次按持久化清单和内容哈希确认已移动文件并继续。
不会移动本次扫描后新加入的文件，下次调度也不会递归扫描 `processed/`。业务库保留导入时的来源路径。

一个模型请求默认最多 10 组，可用 `subtitle.batch.request-size` 在 1～10 之间调整；不足 10 组也会发送，不跨文件。
一个 chunk 默认 4 个模型批次，可以跨文件，最多 40 组字幕，可用 `subtitle.batch.chunk-size` 调整（单位为批次）。
模型请求使用 `FutureTask` 和专用固定线程池，默认同时处理 2 批（单集也能并行）。
`subtitle.batch.concurrency` 可设为 1～8，设为 1 即串行；建议先用 2，确认限额和延迟后再调至 3～4。
线程池在 `SubtitleJobConfiguration` 中定义，由 Spring 管理生命周期。
Reader 只读取字幕；Processor 提交异步模型任务后立即返回 `Future`，不等待结果。
Step 使用 `chunk(properties.getChunkSize())`，直接连接 Reader、Processor 和 Writer。
Writer 按输入顺序收齐当前事务的所有结果，全部成功后才开始落库，统一提交事务和检查点。Writer 注册事务提交回调，提交成功后才移动文件。
默认每块提交 4 个任务，线程池同时执行 2 个，其余排队；一个 chunk 完成后才处理下一块。
任意一批失败则整块回滚并取消剩余任务，恢复时重新处理该块，已提交的块不会重跑。
取消会传递给底层线程池任务以中断请求；Step 关闭时也会取消遗留任务，但不关闭共享线程池。
Batch read/write count 以模型批次计数；模型异常通常在 Writer 等待结果时上报。

## 输入约定

XXL-JOB 的执行参数直接填写**执行器机器上**可读目录的绝对路径，不加 JSON 或引号，例如：

```text
/data/subtitles
```

目录当前层（不递归）示例：

```text
episode01.chs.srt
episode01.cht.srt
episode02.zh-CN.srt
episode02.zh-TW.srt
CHS_episode03.srt
CHT_episode03.srt
```

格式为 `共同名称.语言标识.srt`（标识前支持 `.` / `_` / `-`），
或 `语言标识_共同名称.srt`（标识后支持 `.` / `_` / `-` / 空格，同一种命名可与后缀式互为另一半配对）。
两种式子同时命中时按后缀式解析。
共同名称通过 opencc4j 繁转简后作为配对键，大小写敏感；原始文件名和字幕内容保持不变。
例如 `CHS_龙之家族_S01E01.srt` 与 `CHT_龍之家族_S01E01.srt` 可以配对。
语言标识和扩展名大小写不敏感。
简体默认标识：`chs, sc, zh-cn, zh-hans, 简, 简体`；
繁体默认标识：`cht, tc, zh-tw, zh-hant, 繁, 繁体`。
可在 `subtitle.batch.simplified-markers` / `traditional-markers` 修改。
使用 UTF-8 SRT，支持 UTF-8 BOM；其他编码请先转为 UTF-8。

非 SRT 文件忽略；命名无法识别的 SRT 文件跳过并记 WARN，不中断任务；
缺少配对的字幕记录 WARN 并跳过，留在原目录，不参与处理或归档；重复语言文件在 Reader 打开时失败。
空字幕文件、解析异常、简繁条数或时间轴不一致使 Step / Job 失败。XXL-JOB 等待 Job 结束才反馈结果。
已提交的 chunk 不随后续失败回滚；模型调用不参与数据库事务，未提交的 chunk 重试时可能再次调用模型。
再次调度同一规范化目录时，最新任务若为 FAILED / STOPPED 且未完成文件的清单与内容未变（已归档文件按原清单校验），则恢复该任务的检查点；
已完成的任务允许创建新实例，读取当前目录剩余或新加入的字幕。单机部署使用操作系统文件锁识别活跃任务，进程退出自动释放锁；
重新触发时取得锁后，将遗留的 STARTING / STARTED / STOPPING 执行恢复为 FAILED，再按输入指纹决定续跑或新建。
UNKNOWN 仍拒绝自动恢复，以免误判事务提交状态。此恢复方案仅适用于同机、同一临时目录的执行器；
不要让不同主机或不同临时目录的执行器共享任务库并运行同一任务，跨主机部署需改用分布式锁。
同目录替换、增删或修改字幕后会自动创建新任务，从头读取；没有兼容检查点时也创建新任务。
Reader 仍保留恢复校验，防止启动判断后输入再次发生变化。旧任务同样可恢复，
但只能从旧 chunk 最后一次成功提交的位置开始，未提交的模型结果需要重新请求。

## 启动与调度

```bash
./gradlew test
XXL_JOB_ENABLED=true \
XXL_JOB_ADMIN_ADDRESSES=http://localhost:8080/xxl-job-admin \
XXL_JOB_ACCESS_TOKEN=your-token \
XXL_JOB_EXECUTOR_ADDRESS=http://127.0.0.1:9999 \
XXL_JOB_EXECUTOR_IP=127.0.0.1 \
XXL_JOB_EXECUTOR_PORT=9999 \
./gradlew bootRun
```

启动不会自动运行 Batch Job。必须显式配置 XXL_JOB_ENABLED；开启后要求可访问的调度中心。
在 XXL-JOB 调度中心配置执行器 AppName `subtitle-extractor-executor`，新增 BEAN 任务：

- JobHandler：`subtitleImportJobHandler`
- 执行参数：字幕目录绝对路径
- 阻塞策略：建议单机串行；单次任务内部并发请求模型、按字幕顺序提交
- 路由策略：选择单个执行器；当前未按 XXL 广播分片参数切分输入目录

执行器通过 `XXL_JOB_EXECUTOR_IP`、`XXL_JOB_EXECUTOR_PORT`、
`XXL_JOB_EXECUTOR_ADDRESS` 配置调度中心可达地址，日志位于 `./logs/xxl-job`。

## MySQL 与业务落库

使用本机 Docker MySQL（`localhost:3306`），连接账号配置在本地 `application-dev.yaml`（由
`application-example.yaml` 复制后填值，该文件不提交），
应用库为 `subtitle_extractor`。MyBatis-Plus 3.5.17 使用 Boot 4 专用 starter，Flyway 在启动时
自动创建/迁移业务表及 Spring Batch 6 元数据表；要求 MySQL 8.0.16+。
H2 仅用于默认测试，运行时不再使用内存数据库。
V1 直接把 Batch 的 Job / Step 序列化上下文列建为 `LONGTEXT`，支持超过 64 KiB 的字幕归档清单。
迁移脚本已从两个合并为一个，本地已迁移过的库需重建（`DROP DATABASE subtitle_extractor;`，重启后自动重建）。

六张业务表：文件版本、字幕条目、抽取配置、识别结果、词语映射和来源证据。
每条映射可沿外键追溯两侧文件路径、内容哈希、字幕位置、原序号、时间和原文。
文件内容修改保留新旧版本；不同目录同名文件不会混淆。详见 [持久化设计与查询 SQL](docs/subtitle-persistence-design.md)。

Writer 与 chunk 共用事务，全部结果和证据原子提交。相同来源及配置重复写入保留首次结果，
空映射也会记录，支持一词多译、多处来源。配置变化可生成独立结果；手动重抽取可调整
`subtitle.persistence.rerun-version`，抽取规则升级时调整 `pipeline-version`。

成功后再次调度创建新的 JobInstance；已归档字幕不会再次请求模型。失败任务满足恢复条件时续跑原实例。
Reader 保存下一条 Item 的文件对/字幕组位置与目录内容指纹，恢复时拒绝输入变化。
XXL handler 当前不提供重启历史 execution 的入口。迁移后的库支持跨进程保留执行状态和业务结果。

```bash
# 默认离线测试使用 H2，不访问本机 MySQL。
./gradlew test
# 真实 MySQL 测试创建并清理独立随机测试库，使用本机配置的账号。
RUN_MYSQL_TESTS=true ./gradlew test --tests '*MySqlSubtitlePersistenceTests' --rerun-tasks
```

代码中 `com.github.anicmv.entity` / `com.github.anicmv.mapper` 定义 MyBatis-Plus 实体和 Mapper，
`SubtitlePersistenceService` 负责幂等与来源校验，`SubtitlePairWriter` 负责 chunk 事务。

## Spring AI 大模型接入

使用 Spring AI 2.0.1 的 `spring-ai-starter-model-openai` 自动创建 `OpenAiChatModel`，调用 OpenAI 兼容的 Chat Completions 接口。
必须设置 `SPRING_AI_MODEL_CHAT=openai` 并提供模型配置；模型不可用时启动失败，不再跳过调用。
所有模型配置使用 Spring AI 原生属性：`spring.ai.openai.base-url`、`api-key`、
`chat.model`、`max-retries`（默认 0）、`timeout`（本项目设为 5 分钟）。其他生成参数按当前 Spring AI 版本的原生配置设置。
`OPENAI_BASE_URL` 填 API 根路径（例如 `https://api.openai.com/v1`）。

```bash
export SPRING_AI_MODEL_CHAT=openai
export OPENAI_BASE_URL=https://api.openai.com/v1
export OPENAI_API_KEY=your-key
export OPENAI_MODEL=your-model
./gradlew bootRun
```

每批字幕提交一个请求，仅携带批次内临时 ID 和简繁多行文本，不发送字幕序号、时间轴或文件内 position。
请求采用 `{"cues":[{"id":0,"simplified":"简体正文","traditional":"繁体正文"}]}`，使用 Map 构造输入。
模型返回 `{"results":[{"id":0,"mappings":[{"simplified":"头发","traditional":"頭髮"}]}]}`，严格校验 ID 顺序、字段和数量；无映射时返回空数组。
映射必须为对应正文中的连续词语子串，拒绝相同词语、重复映射和无效内容；每条结果的 content 保存 mappings 数组的 JSON。
提示词按语境提取字形或用词不同的简繁词语映射，无法确定对应关系时不输出。chunk 大小只控制事务提交。
网络超时最多额外重试两次，分别退避 1 秒和 2 秒；线程中断立即停止重试。
当前 qwen3.8-flash 按[阿里云文档](https://help.aliyun.com/zh/model-studio/deep-thinking)默认开启思考模式。
`application.yaml` 中关闭该模式的 `spring.ai.openai.chat.extra-body[enable_thinking]=false` 目前是注释状态，
即思考模式未关闭；需要时可取消注释，减少生成思考内容的耗时。
真实提速和提取质量仍需用实际字幕测量；切换到其他模型时需检查其是否支持该参数。
保留 `spring.ai.openai.max-retries: 0`，避免 SDK 与应用重试叠加。
不自动拆批；超长字幕仍可能超过模型限制。每次调用记录组数和耗时，并在解析前以 INFO 打印模型分词原始结果，
包含字幕对、起始位置、组数、是否校验重试、结束原因和完整响应正文；格式错误或截断的响应也会打印。
响应为空、格式不合法或结束原因不是 `stop` 时失败，异常向 Batch / XXL-JOB 传播。
`spring.ai.openai.timeout: 5m` 将单次 HTTP 请求总超时从 Spring AI 默认的 60 秒延长到 5 分钟，
可通过 `SPRING_AI_OPENAI_TIMEOUT` 覆盖（例如 `10m`）。服务同时将该值显式传入每次聊天请求，
规避 Spring AI 2.0.1 自动配置生成聊天选项时遗漏 timeout、请求又回退到 60 秒的问题。
超时重试耗尽后使当前 chunk 回滚并令任务失败，不会跳过字幕；单次请求含重试最长约 15 分钟加 3 秒退避。
若出现约 60 秒的 `InterruptedIOException: timeout`，请检查该配置是否生效；`Socket closed` 可能是超时取消请求的后续异常。

模型输出经 `SubtitleRecognitionResult` 传递给 Writer，校验后拆为关系表保存；不把 mappings JSON 数组作为业务存储，不修改输入字幕。

OpenAI 与 XXL-JOB 的地址、密钥来自本地 `application-dev.yaml`，也可用环境变量（`OPENAI_API_KEY`、`XXL_JOB_*`）覆盖；两者都为空时模型自动配置会因缺少凭据导致启动失败。字幕语言标记仅由 `application.yaml` 提供，Java 不再重复定义默认值。

## 批量请求基准测试

```bash
RUN_LLM_BENCHMARK=true ./gradlew test --tests '*SubtitleLlmBenchmarkTests' --rerun-tasks
```

默认测试不调用真实模型。显式启用后使用当前模型配置，对 10 组工程样本先发送 10 次单组请求，
再发送 1 次十组请求，两者使用相同提示词。样本来自前五类严格对齐 fixture，地区词汇两组重复一次，
合并成一个虚拟文件用于填满批次；时间偏移、拆分合并样本不适用当前严格对齐流程。
结果保存在 `build/reports/llm-benchmark.json`，包含耗时和逐组响应，供检查漏项、串位和语义差异。
这是一次顺序测量，受服务端负载、缓存和输出长度影响；不是正式术语准确率指标。

## 日志与 IDEA 启动

应用使用 SLF4J + Log4j2，默认 INFO 同时输出到控制台和 `./logs/subtitle-extractor.log`。
相对路径以运行配置的 Working directory 为准，建议设为项目根目录。
IDEA 运行 `SubtitleExtractorApplication.main` 后，在 Run / Debug 的 Console 查看日志；
启动完成会提示等待 XXL-JOB 调度，只有调度触发后才会出现扫描、模型调用和写入日志。
任务结束输出 Batch 批次读写计数、提交和回滚次数；Writer 完成日志不代表事务已经提交。

`./logs/xxl-job` 是 XXL-JOB 自己的调度日志目录，记录 handler 开始、完成和失败，
完整业务过程请看应用控制台或应用日志文件。
若连 Spring Boot 启动日志都没有，先在 IDEA 的 Gradle 工具窗口执行 Reload All Gradle Projects，
确认运行模块使用本项目运行时依赖（包含 `spring-boot-starter-log4j2`），并取消 Console 的输出过滤。
同时检查运行参数、环境变量和激活的 profile 是否覆盖了 `logging.level.root`、
`logging.config` 或 `org.springframework.boot.logging.LoggingSystem`。
需要逐条落库细节时，在 Program arguments 加 `--logging.level.com.github.anicmv=DEBUG`。
