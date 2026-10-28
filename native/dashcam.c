/*
 * The recorder's per-pixel work, which Dalvik (this unit's VM) runs about ten times slower than
 * C: converting frames to I420 for the encoder, and copying the reverse view's frames scaled.
 * Loaded by Native.java; the recorder does the same in Java if it isn't there.
 */
#include <jni.h>
#include <stdint.h>
#include <string.h>
#ifdef __ARM_NEON
#include <arm_neon.h>
#endif

#include "convert.h"

/*
 * BT.601 video range, as in Recorder.encode: Y = (66R + 129G + 25B + 4224) >> 8,
 * U = (112B - 38R - 74G + 32896) >> 8, V = (112R - 94G - 18B + 32896) >> 8. All in unsigned
 * 16 bits: the constants keep every intermediate between 0 and 65535. Chroma comes from the top
 * left pixel of each 2x2. Pixels are 0xAARRGGBB ints, so B, G, R, A bytes in memory.
 */
static inline uint8_t y_of(uint32_t p) {
    uint32_t r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
    return (uint8_t) ((66 * r + 129 * g + 25 * b + 4224) >> 8);
}

static inline uint8_t u_of(uint32_t p) {
    uint32_t r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
    return (uint8_t) ((112 * b + 32896 - 38 * r - 74 * g) >> 8);
}

static inline uint8_t v_of(uint32_t p) {
    uint32_t r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
    return (uint8_t) ((112 * r + 32896 - 94 * g - 18 * b) >> 8);
}

/* Plain C, for widths that aren't a multiple of 16 (from column x on) and for checking the NEON version. */
void to_i420_c(const uint32_t *argb, int w, int h, uint8_t *out, int uoff, int voff, int mirror, int from) {
    int half = w / 2;
    for (int row = 0; row < h; row++) {
        const uint32_t *s = argb + (size_t) row * w;
        uint8_t *y = out + (size_t) row * w;
        uint8_t *u = out + uoff + (size_t) (row >> 1) * half, *v = out + voff + (size_t) (row >> 1) * half;
        for (int x = from; x < w; x += 2) {
            int dx = mirror ? w - 1 - x : x;  /* pixel x lands here; x + 1 one step further on */
            int step = mirror ? -1 : 1;
            y[dx] = y_of(s[x]);
            y[dx + step] = y_of(s[x + 1]);
            if ((row & 1) == 0) {
                int c = mirror ? half - 1 - x / 2 : x / 2;
                u[c] = u_of(s[x]);
                v[c] = v_of(s[x]);
            }
        }
    }
}

void to_i420(const uint32_t *argb, int w, int h, uint8_t *out, int uoff, int voff, int mirror) {
#ifdef __ARM_NEON
    int half = w / 2, done = w & ~15;
    const uint8x8_t k66 = vdup_n_u8(66), k129 = vdup_n_u8(129), k25 = vdup_n_u8(25);
    const uint8x8_t k112 = vdup_n_u8(112), k38 = vdup_n_u8(38), k74 = vdup_n_u8(74), k94 = vdup_n_u8(94), k18 = vdup_n_u8(18);
    const uint16x8_t k4224 = vdupq_n_u16(4224), k32896 = vdupq_n_u16(32896);
    for (int row = 0; row < h; row++) {
        const uint8_t *s = (const uint8_t *) (argb + (size_t) row * w);
        uint8_t *yrow = out + (size_t) row * w;
        uint8_t *urow = out + uoff + (size_t) (row >> 1) * half, *vrow = out + voff + (size_t) (row >> 1) * half;
        for (int x = 0; x < done; x += 16) {
            uint8x16x4_t p = vld4q_u8(s + x * 4);  /* 16 pixels: val[0] B, [1] G, [2] R */
            uint16x8_t lo = vmull_u8(vget_low_u8(p.val[2]), k66);
            lo = vmlal_u8(lo, vget_low_u8(p.val[1]), k129);
            lo = vmlal_u8(lo, vget_low_u8(p.val[0]), k25);
            uint16x8_t hi = vmull_u8(vget_high_u8(p.val[2]), k66);
            hi = vmlal_u8(hi, vget_high_u8(p.val[1]), k129);
            hi = vmlal_u8(hi, vget_high_u8(p.val[0]), k25);
            uint8x16_t y = vcombine_u8(vshrn_n_u16(vaddq_u16(lo, k4224), 8), vshrn_n_u16(vaddq_u16(hi, k4224), 8));
            if (mirror) {
                y = vrev64q_u8(y);
                y = vcombine_u8(vget_high_u8(y), vget_low_u8(y));
                vst1q_u8(yrow + w - x - 16, y);
            } else {
                vst1q_u8(yrow + x, y);
            }
            if ((row & 1) == 0) {
                /* Even pixels only: the top left of each 2x2. */
                uint8x8_t b = vget_low_u8(vuzpq_u8(p.val[0], p.val[0]).val[0]);
                uint8x8_t g = vget_low_u8(vuzpq_u8(p.val[1], p.val[1]).val[0]);
                uint8x8_t r = vget_low_u8(vuzpq_u8(p.val[2], p.val[2]).val[0]);
                uint16x8_t u = vaddq_u16(vmull_u8(b, k112), k32896);
                u = vmlsl_u8(u, r, k38);
                u = vmlsl_u8(u, g, k74);
                uint16x8_t v = vaddq_u16(vmull_u8(r, k112), k32896);
                v = vmlsl_u8(v, g, k94);
                v = vmlsl_u8(v, b, k18);
                uint8x8_t u8 = vshrn_n_u16(u, 8), v8 = vshrn_n_u16(v, 8);
                int c = x / 2;
                if (mirror) {
                    vst1_u8(urow + half - c - 8, vrev64_u8(u8));
                    vst1_u8(vrow + half - c - 8, vrev64_u8(v8));
                } else {
                    vst1_u8(urow + c, u8);
                    vst1_u8(vrow + c, v8);
                }
            }
        }
    }
    if (done < w) to_i420_c(argb, w, h, out, uoff, voff, mirror, done);
#else
    to_i420_c(argb, w, h, out, uoff, voff, mirror, 0);
#endif
}

/*
 * Copies one frame from the camera RAM at src (srcWidth pixels a row), keeping only the given
 * rows and columns. The RAM is mapped uncached, so each row is read whole, in one memcpy (the
 * fastest way to read that memory), then sampled from the copy.
 */
void copy_scaled(const uint32_t *src, int srcWidth, const int *rows, int h, const int *cols, int w, uint32_t *dst) {
    uint32_t line[srcWidth];
    for (int r = 0; r < h; r++) {
        memcpy(line, src + (size_t) rows[r] * srcWidth, (size_t) srcWidth * 4);
        uint32_t *d = dst + (size_t) r * w;
        for (int c = 0; c < w; c++) d[c] = line[cols[c]];
    }
}

JNIEXPORT void JNICALL Java_dev_skramer_dashcam_Native_toI420(JNIEnv *env, jclass cls __attribute__((unused)), jintArray argb,
        jint w, jint h, jobject out, jint uoff, jint voff, jboolean mirror) {
    uint8_t *o = (*env)->GetDirectBufferAddress(env, out);
    jlong cap = (*env)->GetDirectBufferCapacity(env, out);
    if (o == NULL || cap < (jlong) w * h * 3 / 2 || (*env)->GetArrayLength(env, argb) < w * h) return;  /* checked in Java too */
    jint *a = (*env)->GetPrimitiveArrayCritical(env, argb, NULL);
    if (a == NULL) return;
    to_i420((const uint32_t *) a, w, h, o, uoff, voff, mirror);
    (*env)->ReleasePrimitiveArrayCritical(env, argb, a, JNI_ABORT);
}

JNIEXPORT void JNICALL Java_dev_skramer_dashcam_Native_copyScaled(JNIEnv *env, jclass cls __attribute__((unused)), jlong src, jint srcWidth,
        jintArray rows, jintArray cols, jintArray dst) {
    jint h = (*env)->GetArrayLength(env, rows), w = (*env)->GetArrayLength(env, cols);
    if ((*env)->GetArrayLength(env, dst) < w * h) return;
    jint *r = (*env)->GetPrimitiveArrayCritical(env, rows, NULL);
    jint *c = (*env)->GetPrimitiveArrayCritical(env, cols, NULL);
    jint *d = (*env)->GetPrimitiveArrayCritical(env, dst, NULL);
    if (r != NULL && c != NULL && d != NULL) {
        copy_scaled((const uint32_t *) (intptr_t) src, srcWidth, r, h, c, w, (uint32_t *) d);
    }
    if (d != NULL) (*env)->ReleasePrimitiveArrayCritical(env, dst, d, 0);
    if (c != NULL) (*env)->ReleasePrimitiveArrayCritical(env, cols, c, JNI_ABORT);
    if (r != NULL) (*env)->ReleasePrimitiveArrayCritical(env, rows, r, JNI_ABORT);
}

/* Same 32-bit hash as Java, reading the four DMA strips in one JNI call. */
void hash_strips(const uint8_t *address, size_t stride, int count, uint32_t hashes[4]) {
    uint32_t strip[count];
    for (int slot = 0; slot < 4; slot++) {
        /* Bulk reads halve uncached DMA read time on this head unit. Hash the
         * cached copy rather than interleaving arithmetic with device loads. */
        memcpy(strip, address + slot * stride, (size_t)count * 4);
        uint32_t hash = 1;
        for (int i = 0; i < count; i++) hash = 31u * hash + strip[i];
        hashes[slot] = hash;
    }
}

JNIEXPORT void JNICALL Java_dev_skramer_dashcam_Native_hashStrips(JNIEnv *env, jclass cls __attribute__((unused)),
        jlong address, jlong stride, jint count, jintArray hashes) {
    if (count < 1 || count > 640 || stride < (jlong)count * 4 || (*env)->GetArrayLength(env, hashes) < 4) return;
    uint32_t result[4];
    hash_strips((const uint8_t *)(intptr_t)address, (size_t)stride, count, result);
    (*env)->SetIntArrayRegion(env, hashes, 0, 4, (const jint *)result);
}
