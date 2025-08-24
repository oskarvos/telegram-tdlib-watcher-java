package com.oleg.td;

import java.io.Closeable;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Менеджер блокировки экземпляра приложения.
 */
public final class LockManager implements Closeable {
    private final Path lockFile;
    private final FileChannel ch;
    private final FileLock lock;

    private LockManager(Path file, FileChannel ch, FileLock lock) {
        this.lockFile = file;
        this.ch = ch;
        this.lock = lock;
    }

    public static LockManager tryLock(Path file) throws Exception {
        FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = ch.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null;
        }
        return new LockManager(file, ch, lock);
    }

    public boolean isAcquired() {
        return lock != null;
    }

    public Path lockFile() {
        return lockFile;
    }

    @Override
    public void close() {
        try {
            if (lock != null) lock.release();
        } catch (Exception ignored) {
        }
        try {
            if (ch != null) ch.close();
        } catch (Exception ignored) {
        }
    }
}
