package com.github.anicmv.xxl.service;

import com.github.anicmv.util.ContentHash;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** 同机执行器的目录锁；进程退出时由操作系统释放。锁文件保留，避免删除后产生两个锁对象。 */
final class SubtitleDirectoryLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private SubtitleDirectoryLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    static SubtitleDirectoryLock acquire(Path directory) throws IOException {
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "subtitle-extractor-locks");
        Files.createDirectories(root);
        Path file = root.resolve(ContentHash.key(directory.toRealPath().toString()) + ".lock");
        var channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IllegalStateException("同目录任务仍在其他进程运行：" + directory);
            return new SubtitleDirectoryLock(channel, lock);
        } catch (IOException | RuntimeException exception) {
            channel.close();
            if (exception instanceof OverlappingFileLockException) {
                throw new IllegalStateException("同目录任务仍在运行：" + directory, exception);
            }
            throw exception;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
