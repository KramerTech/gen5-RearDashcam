package dev.skramer.dashcam;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.SystemClock;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/** A live-only, latest-frame connection to the separate mirror app. No second camera/RAM reader. */
final class MirrorFeed extends Thread {
    static final String SOCKET = "dev.skramer.dashcam.mirror";
    static final String PACKAGE = "dev.skramer.dashcam.mirror";
    private static final long LEASE_MS = 1500, FRAME_MS = 750;
    /** "MIR2": the frame header carries the recording status. Must match the app's. */
    private static final int MAGIC = 0x4d495232;
    private final boolean mirror;
    private volatile long requested;
    private volatile boolean allowed;
    private volatile LocalSocket client;
    private volatile boolean closeRequested;
    /** Recording status codes, shown in the viewer's corner. */
    static final byte OTHER = 0, RECORDING = 1, DISABLED = 2, NO_DRIVE = 3;
    private volatile byte recording = OTHER;
    private int[] latest;
    private int width, height;
    private long captured;

    MirrorFeed(boolean mirror) { super("mirror socket"); this.mirror = mirror; setDaemon(true); }

    boolean requested() { return requested != 0 && SystemClock.uptimeMillis() - requested < LEASE_MS; }
    void allow(boolean value) { allowed = value; }
    /** Shown in the viewer: RECORDING while frames go to the drive (Guard's test for the status-bar dot), or why not. */
    void recording(byte status) { recording = status; }

    /** Close the visible viewer through its existing connection, without relaunching its Activity. */
    boolean closeViewer() {
        if (client == null || !requested()) return false;
        closeRequested = true;
        return true;
    }

    synchronized void publish(int[] pixels, int w, int h) {
        if (!requested() || !allowed) return;
        if (latest == null || latest.length != pixels.length) latest = new int[pixels.length];
        System.arraycopy(pixels, 0, latest, 0, pixels.length);
        width = w; height = h; captured = SystemClock.uptimeMillis();
    }

    static int appUid() throws Exception {
        Object pm = Class.forName("android.app.AppGlobals").getMethod("getPackageManager").invoke(null);
        return (Integer) Class.forName("android.content.pm.IPackageManager")
                .getMethod("getPackageUid", String.class, int.class).invoke(pm, PACKAGE, 0);
    }

    @Override public void run() {
        try (LocalServerSocket server = new LocalServerSocket(SOCKET)) {
            while (true) {
                try (LocalSocket socket = server.accept()) {
                    if (socket.getPeerCredentials().getUid() != appUid()) continue;
                    closeRequested = false;
                    client = socket;
                    socket.setSoTimeout((int) LEASE_MS);
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    byte[] bytes = new byte[0];
                    IntBuffer buffer = null;
                    long next = 0;
                    while (in.readInt() == MAGIC) {
                        if (closeRequested) {
                            out.writeInt(MAGIC); out.writeInt(-1); out.writeInt(0);
                            out.writeLong(0); out.writeBoolean(false); out.writeByte(OTHER); out.flush();
                            break;
                        }
                        long now = SystemClock.uptimeMillis();
                        requested = now;
                        if (now < next) SystemClock.sleep(next - now);
                        next = SystemClock.uptimeMillis() + 50; // at most 20 fps, even for an eager client
                        int w = 0, h = 0; long at = 0;
                        synchronized (this) {
                            if (allowed && latest != null && SystemClock.uptimeMillis() - captured < FRAME_MS) {
                                if (bytes.length != latest.length * 4) {
                                    bytes = new byte[latest.length * 4];
                                    buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
                                }
                                buffer.clear(); buffer.put(latest);
                                w = width; h = height; at = captured;
                            }
                        }
                        out.writeInt(MAGIC); out.writeInt(w); out.writeInt(h);
                        out.writeLong(at); out.writeBoolean(mirror); out.writeByte(recording);
                        if (w != 0) out.write(bytes);
                        out.flush();
                    }
                } catch (Exception e) {
                    // Closing, sleeping, or a missing app ends just this live-view connection.
                } finally {
                    client = null;
                    closeRequested = false;
                    requested = 0;
                    synchronized (this) { latest = null; captured = 0; }
                }
            }
        } catch (Exception e) { Log.e("mirror connection unavailable", e); }
    }
}
