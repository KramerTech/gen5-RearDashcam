package dev.skramer.dashcam.player;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Shows camera frames as they are in RAM (BGRA bytes) through OpenGL: each frame is uploaded as
 * a texture, as is, and the shader swaps the colors into place, ignores alpha and mirrors, so the
 * CPU only copies. Frames are drawn whole, fit to the view.
 */
final class RamView extends GLSurfaceView implements GLSurfaceView.Renderer {
    private static final String VERTEX = "attribute vec2 p; attribute vec2 uv; varying mediump vec2 v;"
            + " void main() { v = uv; gl_Position = vec4(p, 0.0, 1.0); }";
    private static final String FRAGMENT = "precision mediump float; varying mediump vec2 v; uniform sampler2D t;"
            + " void main() { gl_FragColor = vec4(texture2D(t, v).bgr, 1.0); }";

    private final Object lock = new Object();
    private final FloatBuffer corners = floats(-1, -1, 1, -1, -1, 1, 1, 1);
    private final FloatBuffer straight = floats(0, 1, 1, 1, 0, 0, 1, 0), mirrored = floats(1, 1, 0, 1, 1, 0, 0, 0);
    /** Guarded by lock: the next frame to draw. */
    private ByteBuffer pending;
    private int pendingW, pendingH;
    private boolean fresh, mirror;
    /** GL thread only. */
    private int program, texture, aPos, aUv, uTex, texW, texH, surfaceW, surfaceH;
    /** For the stats: frames uploaded, and the time spent uploading them. */
    volatile long uploads, uploadNs;
    /** Set if OpenGL failed; nothing is drawn after. */
    volatile String error;

    RamView(Context c) {
        super(c);
        setEGLContextClientVersion(2);
        setRenderer(this);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    /** From any thread: a w × h frame of BGRA bytes, copied, to draw next. */
    void post(byte[] bgra, int w, int h, boolean mirror) {
        synchronized (lock) {
            if (pending == null || pending.capacity() != bgra.length) pending = ByteBuffer.allocateDirect(bgra.length);
            pending.clear();
            pending.put(bgra);
            pending.flip();
            pendingW = w;
            pendingH = h;
            this.mirror = mirror;
            fresh = true;
        }
        requestRender();
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        try {
            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, shader(GLES20.GL_VERTEX_SHADER, VERTEX));
            GLES20.glAttachShader(program, shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT));
            GLES20.glLinkProgram(program);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) throw new IllegalStateException(GLES20.glGetProgramInfoLog(program));
            aPos = GLES20.glGetAttribLocation(program, "p");
            aUv = GLES20.glGetAttribLocation(program, "uv");
            uTex = GLES20.glGetUniformLocation(program, "t");
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            texture = t[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            texW = texH = 0;
            synchronized (lock) {
                fresh = pending != null;  // a new context has no texture yet
            }
        } catch (Throwable t) {
            error = t.toString();
        }
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int w, int h) {
        surfaceW = w;
        surfaceH = h;
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        GLES20.glViewport(0, 0, surfaceW, surfaceH);
        GLES20.glClearColor(0, 0, 0, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (error != null) return;
        try {
            int w, h;
            boolean flip;
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            synchronized (lock) {
                if (pending == null) return;
                w = pendingW;
                h = pendingH;
                flip = mirror;
                if (fresh) {
                    long t0 = System.nanoTime();
                    pending.position(0);
                    if (w != texW || h != texH) {
                        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pending);
                        texW = w;
                        texH = h;
                    } else {
                        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pending);
                    }
                    fresh = false;
                    uploadNs += System.nanoTime() - t0;
                    uploads++;
                }
            }
            // Fit the frame to the view.
            int vw = surfaceW, vh = surfaceW * h / w;
            if (vh > surfaceH) {
                vh = surfaceH;
                vw = surfaceH * w / h;
            }
            GLES20.glViewport((surfaceW - vw) / 2, (surfaceH - vh) / 2, vw, vh);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glUniform1i(uTex, 0);
            FloatBuffer uv = flip ? mirrored : straight;
            corners.position(0);
            uv.position(0);
            GLES20.glEnableVertexAttribArray(aPos);
            GLES20.glEnableVertexAttribArray(aUv);
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, corners);
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, uv);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            int e = GLES20.glGetError();
            if (e != GLES20.GL_NO_ERROR) throw new IllegalStateException("GL error 0x" + Integer.toHexString(e));
        } catch (Throwable t) {
            error = t.toString();
        }
    }

    private static int shader(int type, String source) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, source);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException(GLES20.glGetShaderInfoLog(s));
        return s;
    }

    private static FloatBuffer floats(float... v) {
        FloatBuffer b = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(v).position(0);
        return b;
    }
}
