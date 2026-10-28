#ifndef DASHCAM_CONVERT_H
#define DASHCAM_CONVERT_H

#include <stdint.h>
#include <stddef.h>

/* argb (w x h, 0xAARRGGBB) to I420 in out: Y at 0, U at uoff, V at voff; mirrored left to right if mirror. w even. */
void to_i420(const uint32_t *argb, int w, int h, uint8_t *out, int uoff, int voff, int mirror);
/* The same in plain C, from column from on (0 for all). */
void to_i420_c(const uint32_t *argb, int w, int h, uint8_t *out, int uoff, int voff, int mirror, int from);
/* rows x cols samples of a frame srcWidth pixels wide at src, into dst. */
void copy_scaled(const uint32_t *src, int srcWidth, const int *rows, int h, const int *cols, int w, uint32_t *dst);

void hash_strips(const uint8_t *address, size_t stride, int count, uint32_t hashes[4]);

#endif
