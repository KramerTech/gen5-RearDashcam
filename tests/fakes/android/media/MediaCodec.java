package android.media;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Queue;

/** JVM test double; this does not encode video or validate device codec support. */
public final class MediaCodec {
    public static final int BUFFER_FLAG_SYNC_FRAME = 1;
    public static final int BUFFER_FLAG_CODEC_CONFIG = 2;
    public static final int INFO_TRY_AGAIN_LATER = -1;
    public static int typeLookups;
    public static boolean unavailable;
    public static String requestedName;
    public boolean released;
    public int outputReleases;
    private final Queue<BufferInfo> pending = new ArrayDeque<>();
    private ByteBuffer[] output;

    public static final class BufferInfo {
        public int offset, size, flags;
        public long presentationTimeUs;
    }

    public static MediaCodec createByCodecName(String name) throws IOException {
        requestedName = name;
        if (unavailable) throw new IOException("test codec unavailable: " + name);
        return new MediaCodec();
    }

    public static MediaCodec createEncoderByType(String mime) throws IOException {
        typeLookups++;
        return new MediaCodec();
    }

    public void setOutput(byte[][] data, int[] flags) {
        output = new ByteBuffer[data.length];
        for (int i = 0; i < data.length; i++) {
            // Exercise BufferInfo offsets as well as codec-config assembly.
            output[i] = ByteBuffer.allocate(data[i].length + 5);
            output[i].position(3);
            output[i].put(data[i]);
            BufferInfo info = new BufferInfo();
            info.offset = 3;
            info.size = data[i].length;
            info.flags = flags[i];
            info.presentationTimeUs = 123456;
            pending.add(info);
        }
        outputReleases = 0;
    }

    public ByteBuffer[] getOutputBuffers() { return output; }

    public int dequeueOutputBuffer(BufferInfo info, long timeout) {
        BufferInfo next = pending.poll();
        if (next == null) return INFO_TRY_AGAIN_LATER;
        info.offset = next.offset;
        info.size = next.size;
        info.flags = next.flags;
        info.presentationTimeUs = next.presentationTimeUs;
        return output.length - pending.size() - 1;
    }

    public void releaseOutputBuffer(int index, boolean render) { outputReleases++; }
    public void stop() {}
    public void release() { released = true; }
}
