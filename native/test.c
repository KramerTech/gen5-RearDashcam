/*
 * Checks the NEON conversion against the plain C one, and times both, on the head unit:
 *   ./build.sh builds it as dist-test/convert-test; push it to /data/local/tmp and run it.
 * Not part of the package.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "convert.h"

static double now_ms(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1e3 + t.tv_nsec / 1e6;
}

int main(void) {
    int sizes[][2] = {{720, 480}, {726, 480}, {16, 2}, {1140, 720}};
    int failed = 0;
    srand(1);
    for (int s = 0; s < 4; s++) {
        int w = sizes[s][0], h = sizes[s][1], n = w * h;
        uint32_t *argb = malloc(n * 4);
        for (int i = 0; i < n; i++) argb[i] = 0xff000000u | ((uint32_t) rand() & 0xffffff);
        argb[0] = 0xffffffff;
        argb[1] = 0xff000000;
        uint8_t *a = malloc(n * 3 / 2), *b = malloc(n * 3 / 2);
        for (int mirror = 0; mirror < 2; mirror++) {
            memset(a, 0x55, n * 3 / 2);
            memset(b, 0xaa, n * 3 / 2);
            to_i420(argb, w, h, a, n, n + n / 4, mirror);
            to_i420_c(argb, w, h, b, n, n + n / 4, mirror, 0);
            int diff = memcmp(a, b, n * 3 / 2) != 0;
            failed |= diff;
            printf("%dx%d mirror %d: %s\n", w, h, mirror, diff ? "DIFFERENT" : "same");
        }
        if (w == 720) {
            double t0 = now_ms();
            for (int i = 0; i < 100; i++) to_i420(argb, w, h, a, n, n + n / 4, 1);
            double t1 = now_ms();
            for (int i = 0; i < 100; i++) to_i420_c(argb, w, h, b, n, n + n / 4, 1, 0);
            double t2 = now_ms();
            printf("720x480 mirrored: NEON %.2f ms, C %.2f ms a frame\n", (t1 - t0) / 100, (t2 - t1) / 100);
        }
        free(argb);
        free(a);
        free(b);
    }
    puts(failed ? "FAILED" : "ok");
    return failed;
}
