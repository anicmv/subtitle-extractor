package com.github.anicmv.service;

import com.github.anicmv.util.ContentHash;

import com.github.anicmv.job.model.SubtitlePair;
import com.github.anicmv.job.model.SubtitleRecognitionResult;
import com.github.anicmv.job.step.reader.SubtitleCueAligner;
import com.github.anicmv.job.step.reader.SubtitlePairLoader;
import com.github.anicmv.job.step.reader.SubtitlePairScanner;
import com.github.anicmv.job.step.writer.SubtitlePairWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author anicmv
 * @date 2026/9/16 15:36
 * @description 同一落库契约分别运行于 H2 MySQL 模式和真实 MySQL。
 */
abstract class SubtitlePersistenceContract {
    static final String HAIR = "[{\"simplified\":\"头发\",\"traditional\":\"頭髮\"}]";
    static final String SOFTWARE = "[{\"simplified\":\"软件\",\"traditional\":\"軟體\"}]";
    @Autowired SubtitlePairWriter writer;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path directory;

    @BeforeEach
    void clearBusinessTables() {
        for (String table : List.of("term_mapping_evidence", "subtitle_recognition", "term_mapping",
                "subtitle_cue", "subtitle_file", "extraction_profile")) jdbc.update("DELETE FROM " + table);
    }

    @Test
    void keepsRegionalMappingsPendingAndPreservesHumanReview() throws Exception {
        var pairs = load(directory, "头发软件", "頭髮軟體");
        write(new SubtitleRecognitionResult(pairs.getFirst(), HAIR),
                new SubtitleRecognitionResult(pairs.getLast(), SOFTWARE));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM term_mapping WHERE review_status='PENDING'", Integer.class));
        assertEquals("MATCH", jdbc.queryForObject("SELECT orthographic_hint FROM term_mapping WHERE simplified_term='头发'", String.class));
        assertEquals("NO_MATCH", jdbc.queryForObject("SELECT orthographic_hint FROM term_mapping WHERE simplified_term='软件'", String.class));
        jdbc.update("UPDATE term_mapping SET review_status='REJECTED' WHERE simplified_term='软件'");
        var fresh = load(Files.createDirectories(directory.resolve("fresh")), "头发软件", "頭髮軟體");
        write(new SubtitleRecognitionResult(fresh.getFirst(), SOFTWARE));
        assertEquals("REJECTED", jdbc.queryForObject("SELECT review_status FROM term_mapping WHERE simplified_term='软件'", String.class));
    }

    @Test
    void persistsExactFileAndCueProvenanceIncludingEmptyResults() throws Exception {
        var pairs = load(directory, "头发软件", "頭髮軟體");
        write(new SubtitleRecognitionResult(pairs.getFirst(), HAIR), new SubtitleRecognitionResult(pairs.getLast(), "[]"));
        write(new SubtitleRecognitionResult(pairs.getFirst(), SOFTWARE));
        assertEquals(2, count("subtitle_file"));
        assertEquals(4, count("subtitle_cue"));
        assertEquals(2, count("subtitle_recognition"));
        assertEquals(1, count("term_mapping"));
        assertEquals(1, count("term_mapping_evidence"));
        var origin = jdbc.queryForMap("""
                SELECT sf.source_path AS sc_path, tf.source_path AS tc_path,
                       sc.position AS sc_position, tc.position AS tc_position,
                       sc.original_number AS sc_number, sc.start_ms, sc.end_ms, sc.raw_text,
                       sf.content_sha256, m.simplified_term, m.traditional_term
                FROM term_mapping_evidence e
                JOIN term_mapping m ON m.id=e.mapping_id
                JOIN subtitle_recognition r ON r.id=e.recognition_id
                JOIN subtitle_cue sc ON sc.id=r.simplified_cue_id
                JOIN subtitle_cue tc ON tc.id=r.traditional_cue_id
                JOIN subtitle_file sf ON sf.id=sc.file_id
                JOIN subtitle_file tf ON tf.id=tc.file_id
                """);
        assertEquals(directory.resolve("episode.chs.srt").toString(), origin.get("sc_path"));
        assertEquals(directory.resolve("episode.cht.srt").toString(), origin.get("tc_path"));
        assertEquals(0, ((Number) origin.get("sc_position")).intValue());
        assertEquals(0, ((Number) origin.get("tc_position")).intValue());
        assertEquals(7, ((Number) origin.get("sc_number")).intValue());
        assertEquals(1000, ((Number) origin.get("start_ms")).longValue());
        assertEquals(2000, ((Number) origin.get("end_ms")).longValue());
        assertEquals("头发", origin.get("simplified_term"));
        assertEquals(pairs.getFirst().simplifiedSource().sha256(), origin.get("content_sha256"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM subtitle_recognition WHERE completed_at IS NULL", Integer.class));
        assertFalse(jdbc.queryForObject("SELECT config_json FROM extraction_profile", String.class).contains("test-key"));
    }

    @Test
    void emptyResultIsFinalAndDoesNotCollectMappingsOnReplay() throws Exception {
        var pair = load(directory, "头发软件", "頭髮軟體").getFirst();
        write(new SubtitleRecognitionResult(pair, "[]"));
        write(new SubtitleRecognitionResult(pair, HAIR));
        assertEquals(1, count("subtitle_recognition"));
        assertEquals(0, count("term_mapping_evidence"));
        assertEquals(0, count("term_mapping"));
    }

    @Test
    void keepsEvidenceAcrossPositionsPathsAndChangedFileVersions() throws Exception {
        var original = load(directory, "头发软件", "頭髮軟體");
        for (var pair : original) write(new SubtitleRecognitionResult(pair, HAIR));
        var another = load(Files.createDirectory(directory.resolve("other")), "头发软件", "頭髮軟體");
        write(new SubtitleRecognitionResult(another.getFirst(), HAIR));
        var updated = load(directory, "头发软件更新", "頭髮軟體更新");
        write(new SubtitleRecognitionResult(updated.getFirst(), HAIR));
        assertEquals(6, count("subtitle_file"));
        assertEquals(4, count("subtitle_recognition"));
        assertEquals(1, count("term_mapping"));
        assertEquals(4, count("term_mapping_evidence"));
        assertNotEquals(original.getFirst().simplifiedSource().sha256(), updated.getFirst().simplifiedSource().sha256());
    }

    @Test
    void rollsBackTheWholeChunkOnInvalidLaterResult() throws Exception {
        var pairs = load(directory, "头发软件", "頭髮軟體");
        assertThrows(RuntimeException.class, () -> write(new SubtitleRecognitionResult(pairs.getFirst(), HAIR),
                new SubtitleRecognitionResult(pairs.getLast(), "[{\"simplified\":\"不存在\",\"traditional\":\"頭髮\"}]")));
        for (String table : List.of("subtitle_file", "subtitle_cue", "subtitle_recognition", "extraction_profile",
                "term_mapping", "term_mapping_evidence")) assertEquals(0, count(table), table);
    }

    @Test
    void rejectsInconsistentContentForAnExistingFileVersion() throws Exception {
        var pair = load(directory, "头发软件", "頭髮軟體").getFirst();
        write(new SubtitleRecognitionResult(pair, HAIR));
        pair.simplified().setText("另一个正文");
        assertThrows(IllegalStateException.class, () -> write(new SubtitleRecognitionResult(pair, "[]")));
        assertEquals(1, count("term_mapping_evidence"));
    }

    @Test
    void supportsOneToManyAndExactCharacters() throws Exception {
        var pair = load(directory, "软件abcABC", "軟體軟件詞").getFirst();
        write(new SubtitleRecognitionResult(pair, """
                [{"simplified":"软件","traditional":"軟體"},
                 {"simplified":"软件","traditional":"軟件"},
                 {"simplified":"abc","traditional":"詞"},
                 {"simplified":"ABC","traditional":"詞"}]
                """));
        assertEquals(4, count("term_mapping"));
        assertEquals(4, count("term_mapping_evidence"));
    }

    @Test
    void acceptsLongUnicodeTermsWithoutUniqueIndexTruncation() throws Exception {
        String sc = "头".repeat(1024) + "𠮷";
        String tc = "頭".repeat(1024) + "𠮷";
        var pair = load(directory, sc, tc).getFirst();
        write(new SubtitleRecognitionResult(pair,
                "[{\"simplified\":\"" + sc + "\",\"traditional\":\"" + tc + "\"}]"));
        assertEquals(sc, jdbc.queryForObject("SELECT simplified_term FROM term_mapping", String.class));
        assertEquals(tc, jdbc.queryForObject("SELECT traditional_term FROM term_mapping", String.class));
    }

    @Test
    void concurrentDifferentResponsesNeverMergeEvidence() throws Exception {
        var pair = load(directory, "头发软件", "頭髮軟體").getFirst();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of(HAIR, SOFTWARE).stream().map(content -> executor.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                write(new SubtitleRecognitionResult(pair, content));
                return null;
            })).toList();
            start.countDown();
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
        }
        assertEquals(1, count("subtitle_recognition"));
        assertEquals(1, count("term_mapping_evidence"));
        assertEquals(1, count("term_mapping"));
    }

    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }

    private void write(SubtitleRecognitionResult... results) {
        writer.write(new Chunk<>(List.of(java.util.concurrent.CompletableFuture.completedFuture(List.of(results)))));
    }

    private List<SubtitlePair> load(Path root, String sc, String tc) throws Exception {
        Path simplified = root.resolve("episode.chs.srt");
        Path traditional = root.resolve("episode.cht.srt");
        Files.writeString(simplified, srt(sc));
        Files.writeString(traditional, "\uFEFF" + srt(tc));
        var loaded = new SubtitlePairLoader().load(new SubtitlePairScanner.PairFiles("episode", simplified.toString(), traditional.toString()));
        var pairs = new SubtitleCueAligner().align(loaded);
        assertEquals(ContentHash.sha256(Files.readAllBytes(traditional)), pairs.getFirst().traditionalSource().sha256());
        return pairs;
    }

    private String srt(String text) {
        // 原编号重复仍是两个不同位置。
        return "7\n00:00:01,000 --> 00:00:02,000\n" + text + "\n\n7\n00:00:03,000 --> 00:00:04,000\n" + text + "\n\n";
    }
}
