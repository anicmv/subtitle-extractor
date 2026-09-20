package com.github.anicmv.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.github.anicmv.entity.ExtractionProfileEntity;
import com.github.anicmv.entity.SubtitleCueEntity;
import com.github.anicmv.entity.SubtitleFileEntity;
import com.github.anicmv.entity.SubtitleRecognitionEntity;
import com.github.anicmv.entity.TermMappingEntity;
import com.github.anicmv.job.model.SubtitlePair;
import com.github.anicmv.job.model.SubtitleRecognitionResult;
import com.github.anicmv.job.model.SubtitleSource;
import com.github.anicmv.mapper.ExtractionProfileMapper;
import com.github.anicmv.mapper.SubtitleCueMapper;
import com.github.anicmv.mapper.SubtitleFileMapper;
import com.github.anicmv.mapper.SubtitleRecognitionMapper;
import com.github.anicmv.mapper.TermMappingEvidenceMapper;
import com.github.anicmv.mapper.TermMappingMapper;
import com.github.anicmv.util.ContentHash;
import com.github.houbb.opencc4j.util.ZhConverterUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sh.casey.subtitler.model.Subtitle;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * @author anicmv
 * @date 2026/9/16 15:36
 * @description 与 Writer/Batch chunk 共用事务；所有识别、词对和证据原子提交。
 */
@Service
@RequiredArgsConstructor
public class SubtitlePersistenceService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final SubtitleFileMapper files;
    private final SubtitleCueMapper cues;
    private final ExtractionProfileMapper profiles;
    private final SubtitleRecognitionMapper recognitions;
    private final TermMappingMapper mappings;
    private final TermMappingEvidenceMapper evidence;
    private final ExtractionProfileSnapshot snapshot;

    @Transactional(propagation = Propagation.MANDATORY)
    public void save(SubtitleRecognitionResult result) {
        var pair = result.pair();
        validatePair(pair);
        var terms = parseMappings(result);
        long profileId = profile();
        long sc = cue(pair.simplifiedSource(), pair.position(), pair.simplified());
        long tc = cue(pair.traditionalSource(), pair.position(), pair.traditional());
        var candidate = new SubtitleRecognitionEntity();
        candidate.setSimplifiedCueId(sc);
        candidate.setTraditionalCueId(tc);
        candidate.setProfileId(profileId);

        // upsert 获取唯一键行锁。并发的第二个事务等待首个事务提交，再读其完成状态。
        // 不使用 affectedRows 判断新旧，兼容 Connector/J 的 foundRows 设置。
        recognitions.insertIfAbsent(candidate);
        var recognition = recognitions.selectOne(new QueryWrapper<SubtitleRecognitionEntity>()
                .eq("simplified_cue_id", sc).eq("traditional_cue_id", tc).eq("profile_id", profileId)
                .last("FOR UPDATE"));
        if (recognition.getCompletedAt() != null) return;

        for (var term : terms) {
            long mappingId = mapping(term);
            evidence.insert(recognition.getId(), mappingId);
        }
        recognition.setCompletedAt(LocalDateTime.now(ZoneOffset.UTC));
        recognitions.updateById(recognition);
    }

    private long profile() {
        var row = new ExtractionProfileEntity();
        row.setConfigSha256(snapshot.hash());
        row.setConfigJson(snapshot.json());
        var stored = findOrInsert(profiles, new QueryWrapper<ExtractionProfileEntity>()
                .eq("config_sha256", row.getConfigSha256()), profiles::insertIfAbsent, row);
        requireEqual(row.getConfigJson(), stored.getConfigJson(), "配置哈希冲突");
        return stored.getId();
    }

    private long cue(SubtitleSource source, int position, Subtitle subtitle) {
        var file = new SubtitleFileEntity();
        file.setSourcePath(source.path());
        file.setContentSha256(source.sha256());
        file.setFileKey(ContentHash.key(source.path(), source.sha256()));
        var storedFile = findOrInsert(files, new QueryWrapper<SubtitleFileEntity>()
                .eq("file_key", file.getFileKey()), files::insertIfAbsent, file);
        requireEqual(file.getSourcePath(), storedFile.getSourcePath(), "文件哈希冲突");
        requireEqual(file.getContentSha256(), storedFile.getContentSha256(), "文件版本冲突");

        var row = new SubtitleCueEntity();
        row.setFileId(storedFile.getId());
        row.setPosition(position);
        row.setOriginalNumber(subtitle.getNumber());
        row.setStartMs(subtitle.getStartMilliseconds());
        row.setEndMs(subtitle.getEndMilliseconds());
        row.setRawText(subtitle.getText());
        var stored = findOrInsert(cues, new QueryWrapper<SubtitleCueEntity>()
                .eq("file_id", row.getFileId()).eq("position", position), cues::insertIfAbsent, row);
        row.setId(stored.getId());
        requireEqual(row, stored, "同一文件版本的字幕内容不一致");
        return stored.getId();
    }

    private long mapping(Term term) {
        var row = new TermMappingEntity();
        row.setMappingKey(ContentHash.key(term.simplified(), term.traditional()));
        row.setSimplifiedTerm(term.simplified());
        row.setTraditionalTerm(term.traditional());
        row.setReviewStatus("PENDING");
        row.setOrthographicHint(ZhConverterUtil.toSimple(term.traditional())
                .equals(term.simplified()) ? "MATCH" : "NO_MATCH");
        var stored = findOrInsert(mappings, new QueryWrapper<TermMappingEntity>()
                .eq("mapping_key", row.getMappingKey()), mappings::insertIfAbsent, row);
        requireEqual(row.getSimplifiedTerm(), stored.getSimplifiedTerm(), "词对哈希冲突");
        requireEqual(row.getTraditionalTerm(), stored.getTraditionalTerm(), "词对哈希冲突");
        return stored.getId();
    }

    private static <T> T findOrInsert(BaseMapper<T> mapper, QueryWrapper<T> query, Consumer<T> insert, T row) {
        T existing = mapper.selectOne(query);
        if (existing != null) return existing;
        insert.accept(row);
        // 当前读，避免 MySQL REPEATABLE READ 快照看不到刚提交的竞争者。
        return Objects.requireNonNull(mapper.selectOne(query.last("FOR UPDATE")));
    }

    private static void requireEqual(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) throw new IllegalStateException(message);
    }

    private static void validatePair(SubtitlePair pair) {
        if (pair.simplifiedSource() == null || pair.traditionalSource() == null || pair.position() < 0
                || pair.simplifiedSource().path().equals(pair.traditionalSource().path())) {
            throw new IllegalArgumentException("落库必须携带两份不同文件的真实来源及字幕位置");
        }
        for (var cue : List.of(pair.simplified(), pair.traditional())) {
            if (cue.getText() == null || cue.getStartMilliseconds() == null || cue.getEndMilliseconds() == null
                    || cue.getStartMilliseconds() < 0 || cue.getEndMilliseconds() < cue.getStartMilliseconds()) {
                throw new IllegalArgumentException("字幕正文或时间无效");
            }
        }
        requireEqual(pair.simplified().getStartMilliseconds(), pair.traditional().getStartMilliseconds(), "起点未对齐");
        requireEqual(pair.simplified().getEndMilliseconds(), pair.traditional().getEndMilliseconds(), "终点未对齐");
    }

    /** 一对已校验的简繁词语。 */
    private record Term(String simplified, String traditional) {}

    private static List<Term> parseMappings(SubtitleRecognitionResult result) {
        var root = JSON.readTree(result.content());
        if (root == null || !root.isArray()) throw new IllegalArgumentException("识别结果必须是词对数组");
        var terms = new ArrayList<Term>();
        var seen = new HashSet<Term>();
        for (var item : root) {
            var sc = item.path("simplified");
            var tc = item.path("traditional");
            if (!item.isObject() || item.size() != 2 || !sc.isString() || !tc.isString()) {
                throw new IllegalArgumentException("词对字段无效");
            }
            var term = new Term(sc.asString(), tc.asString());
            if (!validTerm(term.simplified()) || !validTerm(term.traditional())
                    || term.simplified().equals(term.traditional())
                    || !result.pair().simplified().getText().contains(term.simplified())
                    || !result.pair().traditional().getText().contains(term.traditional()) || !seen.add(term)) {
                throw new IllegalArgumentException("词对必须不同、去重且来自对应字幕原文");
            }
            terms.add(term);
        }
        // 对共享词对使用稳定锁顺序，减少不同字幕并发入库的死锁机会。
        terms.sort(Comparator.comparing(Term::simplified).thenComparing(Term::traditional));
        return terms;
    }

    private static boolean validTerm(String term) {
        return term.codePointCount(0, term.length()) <= 4096
                && term.codePoints().anyMatch(Character::isLetter)
                && !term.matches("(?s).*[\\p{P}\\p{Z}\\s].*");
    }
}
