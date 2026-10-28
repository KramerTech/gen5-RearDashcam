/* Exercise register guards/restoration against RAM, never /dev/mem. */
#define main overlay_daemon_main
#include "overlay.c"
#undef main
#include <assert.h>

int main(void) {
    uint32_t memory[0x2000 / 4] = {0};
    regs = memory; camera_base = 0x86500000; camera_end = 0x87b00000;
    REG(0x500) = ENABLE | 0x20040c;
    REG(0x508) = (480u << 16) | 720;
    REG(0x50c) = camera_base;
    REG(0x524) = 0xab12cd34;
    REG(0x1808) = 0x02d00780;
    uint32_t ctrl = REG(0x500), alpha = REG(0x524);
    assert(!hide(1280, 720));
    assert(REG(0x500) == ctrl && REG(0x524) == alpha);
    assert(hide(720, 480));
    assert((REG(0x500) & (AEN | ASEL)) == AEN);
    assert((REG(0x500) & ENABLE) != 0);
    assert(REG(0x524) == (alpha & ~ALPHA_MASK));
    assert(!hide(720, 480));
    restore();
    assert(REG(0x500) == (ctrl | UPDATE));
    assert(REG(0x524) == alpha);
    assert(hide(720, 480));
    REG(0x500) &= ~ENABLE;
    restore();
    assert(!(REG(0x500) & ENABLE)); /* cleanup cannot enable a stopped camera */
    REG(0x500) = ctrl;
    assert(hide(720, 480));
    REG(0x500) = ctrl; REG(0x524) = 0x12345678; /* driver reset for stock view */
    restore();
    assert(REG(0x500) == ctrl && REG(0x524) == 0x12345678);
    REG(0x50c) = camera_end;
    assert(!hide(720, 480));
    REG(0x50c) = camera_base; REG(0x500) &= ~ENABLE;
    assert(!hide(720, 480));
    puts("overlay guards, alpha restore, disabled-camera and driver-reset cases passed");
    return 0;
}
