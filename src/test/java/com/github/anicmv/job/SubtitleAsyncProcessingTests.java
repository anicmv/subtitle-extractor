package com.github.anicmv.job;

import com.github.anicmv.job.model.SubtitlePair;
import com.github.anicmv.job.model.SubtitleRecognitionResult;
import com.github.anicmv.job.step.processor.SubtitlePairProcessor;
import com.github.anicmv.job.step.writer.SubtitlePairWriter;
import com.github.anicmv.llm.SubtitleLlmService;
import com.github.anicmv.service.SubtitlePersistenceService;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.item.Chunk;

import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class SubtitleAsyncProcessingTests {
    @Test
    void failedChunkDoesNotWritePartialResultsAndInterruptsRemainingRequest() throws Exception {
        var llm = mock(SubtitleLlmService.class);
        var persistence = mock(SubtitlePersistenceService.class);
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        when(llm.processBatch(anyList())).thenAnswer(invocation -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
                return List.of();
            } catch (InterruptedException exception) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var processor = new SubtitlePairProcessor(llm, executor);
            try {
                var pending = processor.process(List.of(mock(SubtitlePair.class)));
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertFalse(pending.isDone());
                var success = CompletableFuture.completedFuture(List.of(mock(SubtitleRecognitionResult.class)));
                var failure = CompletableFuture.<List<SubtitleRecognitionResult>>failedFuture(new IllegalStateException("timeout"));
                assertThrows(IllegalStateException.class, () -> new SubtitlePairWriter(persistence)
                        .write(new Chunk<>(List.of(success, failure, pending))));
                verifyNoInteractions(persistence);
                assertTrue(interrupted.await(5, TimeUnit.SECONDS));
                assertTrue(pending.isCancelled());
                assertEquals(42, executor.submit(() -> 42).get(5, TimeUnit.SECONDS));
            } finally {
                processor.close();
            }
        }
    }

    @Test
    void closingProcessorCancelsQueuedWorkWithoutClosingSharedExecutor() throws Exception {
        var llm = mock(SubtitleLlmService.class);
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        when(llm.processBatch(anyList())).thenAnswer(invocation -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException exception) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return List.of();
        });
        try (var executor = Executors.newFixedThreadPool(1)) {
            var processor = new SubtitlePairProcessor(llm, executor);
            var running = processor.process(List.of(mock(SubtitlePair.class)));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            var queued = processor.process(List.of(mock(SubtitlePair.class)));
            processor.close();
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertTrue(running.isCancelled());
            assertTrue(queued.isCancelled());
            executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
            verify(llm, times(1)).processBatch(anyList());
            assertFalse(executor.isShutdown());
        }
    }
}
