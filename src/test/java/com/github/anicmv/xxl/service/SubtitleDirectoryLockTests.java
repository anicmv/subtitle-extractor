package com.github.anicmv.xxl.service;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SubtitleDirectoryLockTests {
    @TempDir Path directory;

    @Test
    void refusesConcurrentOwnerAndAllowsReuseAfterRelease() throws Exception {
        try (var lock = SubtitleDirectoryLock.acquire(directory)) {
            assertThrows(IllegalStateException.class, () -> SubtitleDirectoryLock.acquire(directory));
        }
        try (var lock = SubtitleDirectoryLock.acquire(directory)) {
            assertNotNull(lock);
        }
    }
}
