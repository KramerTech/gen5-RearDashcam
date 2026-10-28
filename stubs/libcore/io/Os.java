package libcore.io;

import java.io.FileDescriptor;

// Compile-only stub of the hidden framework interface (the real methods throw ErrnoException).
public interface Os {
    FileDescriptor open(String path, int flags, int mode) throws Exception;
    void close(FileDescriptor fd) throws Exception;
    long mmap(long address, long byteCount, int prot, int flags, FileDescriptor fd, long offset) throws Exception;
    void munmap(long address, long byteCount) throws Exception;
}
