package com.github.anicmv.job.step.reader;

import com.github.anicmv.job.model.SubtitlePair;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamReader;
import java.util.List;

/**
 * @author anicmv
 * @date 2026/9/16 10:51
 * @description 按同一文件最多 size 组读取，检查点仍指向下一组字幕。
 */
public class SubtitlePairBatchReader implements ItemStreamReader<List<SubtitlePair>> {
    private final SubtitlePairReader delegate;
    private final int size;

    public SubtitlePairBatchReader(SubtitlePairReader delegate, int size) {
        if (size < 1 || size > 10) throw new IllegalArgumentException("模型批次大小必须在 1～10 之间");
        this.delegate = delegate;
        this.size = size;
    }
    @Override public List<SubtitlePair> read() { return delegate.readBatch(size); }
    @Override public void open(ExecutionContext context) { delegate.open(context); }
    @Override public void update(ExecutionContext context) { delegate.update(context); }
    @Override public void close() { delegate.close(); }
}
