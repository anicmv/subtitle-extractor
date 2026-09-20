package com.github.anicmv.llm;

import com.github.anicmv.job.model.SubtitlePair;
import com.github.anicmv.job.model.SubtitleRecognitionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import sh.casey.subtitler.reader.SrtSubtitleReader;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author anicmv
 * @date 2026/9/16 15:36
 * @description 显式启用才调用真实模型；同一提示词比较 1 组与 10 组，保留全文供人工评估。
 */
@EnabledIfEnvironmentVariable(named = "RUN_LLM_BENCHMARK", matches = "true")
@SpringBootTest(properties = "xxl.job.enabled=false")
class SubtitleLlmBenchmarkTests {
    @Autowired SubtitleLlmService llm;

    @Test
    void compareSingleAndTenCueRequests() throws Exception {
        var pairs = new ArrayList<SubtitlePair>();
        var cases = new ArrayList<String>();
        Path root = Path.of("src/test/resources/fixtures/term-mapping");
        for (String name : List.of("01-orthographic", "02-regional", "03-proper-name", "04-ignore", "05-uncertain", "02-regional")) {
            var sc = new SrtSubtitleReader().read(root.resolve(name + "/" + name + ".chs.srt").toString()).getSubtitles();
            var tc = new SrtSubtitleReader().read(root.resolve(name + "/" + name + ".cht.srt").toString()).getSubtitles();
            for (int i = 0; i < sc.size(); i++) {
                pairs.add(new SubtitlePair("benchmark", pairs.size(), sc.get(i), tc.get(i)));
                cases.add(name + ":" + (i + 1));
            }
        }
        assertEquals(10, pairs.size());
        var singles = new ArrayList<SubtitleRecognitionResult>();
        var singleTimes = new ArrayList<Long>();
        for (var pair : pairs) {
            long start = System.nanoTime();
            singles.addAll(llm.processBatch(List.of(pair)));
            singleTimes.add((System.nanoTime() - start) / 1_000_000);
        }
        long start = System.nanoTime();
        var batch = llm.processBatch(pairs);
        long batchMs = (System.nanoTime() - start) / 1_000_000;
        var rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < pairs.size(); i++) {
            rows.add(Map.of("id", i, "fixture", cases.get(i), "simplified", pairs.get(i).simplified().getText(),
                    "traditional", pairs.get(i).traditional().getText(), "single", singles.get(i).content(),
                    "batch", batch.get(i).content()));
        }
        var report = Map.of("single_ms", singleTimes, "single_total_ms", singleTimes.stream().mapToLong(Long::longValue).sum(),
                "batch_ms", batchMs, "results", rows, "note", "一次顺序测量；单组和批量使用相同提示词；合成样本合并为一个虚拟文件，包含两组重复样本；非术语准确率评测");
        Path output = Path.of("build/reports/llm-benchmark.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report));
        assertEquals(pairs, batch.stream().map(SubtitleRecognitionResult::pair).toList());
    }
}
