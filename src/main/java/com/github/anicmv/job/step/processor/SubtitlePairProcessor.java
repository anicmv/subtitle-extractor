package com.github.anicmv.job.step.processor;

import com.github.anicmv.job.model.SubtitlePair;
import com.github.anicmv.job.model.SubtitleRecognitionResult;
import com.github.anicmv.llm.SubtitleLlmService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.batch.infrastructure.item.ItemStream;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

/**
 * @author anicmv
 * @date 2026/9/16 10:57
 * @description 向专用线程池提交 LLM 批次，立即返回 Future，交由 Writer 等待。
 */
@Component
public class SubtitlePairProcessor implements ItemProcessor<List<SubtitlePair>, Future<List<SubtitleRecognitionResult>>>, ItemStream {
    private final SubtitleLlmService llm;
    private final ExecutorService executor;
    private final ConcurrentLinkedDeque<Future<List<SubtitleRecognitionResult>>> pending = new ConcurrentLinkedDeque<>();

    public SubtitlePairProcessor(SubtitleLlmService llm,
            @Qualifier("subtitleProcessorExecutor") ExecutorService executor) {
        this.llm = llm;
        this.executor = executor;
    }

    @Override
    public Future<List<SubtitleRecognitionResult>> process(List<SubtitlePair> pairs) {
        var input = List.copyOf(pairs);
        var task = new FutureTask<>(() -> llm.processBatch(input)) {
            @Override
            protected void done() {
                pending.remove(this);
            }
        };
        pending.add(task);
        try {
            executor.execute(task);
        } catch (RuntimeException exception) {
            task.cancel(false);
            throw exception;
        }
        return task;
    }

    @Override
    public void close() {
        // 先取消后提交的排队任务，再中断正在执行的任务。
        pending.descendingIterator().forEachRemaining(future -> future.cancel(true));
    }
}
