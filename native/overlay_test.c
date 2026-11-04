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
    REG(0x424) = 0x11000000;
    REG(0x1808) = 0x02d00780;
    uint32_t ctrl = REG(0x500), alpha = REG(0x524);
    assert(!hide(1280, 720));
    assert(REG(0x500) == ctrl && REG(0x524) == alpha);
    assert(hide(720, 480));
    assert((REG(0x500) & (AEN | ASEL)) == AEN);
    assert((REG(0x500) & ENABLE) != 0);
    assert(REG(0x524) == (alpha & ~ALPHA_MASK));
    assert(REG(0x424) == 0x11000000);
    assert(!hide(720, 480));
    restore();
    assert(REG(0x500) == (ctrl | UPDATE));
    assert(REG(0x524) == alpha);
    assert(REG(0x424) == 0x11000000);
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
    /* The composer hiding the UI: held opaque with no video layer, see-through with one. */
    REG(0x400) = ENABLE | 0x20080c | ASEL | AEN; REG(0x424) = 0;
    guard_ui();
    assert(REG(0x424) == 0);                               /* UI showing normally: untouched */
    REG(0x400) &= ~ASEL;                                   /* ioctl 0x91 */
    REG(0x700) = ENABLE;                                   /* video layer on */
    guard_ui();
    assert(REG(0x424) == 0);
    REG(0x700) = 0x200400;
    guard_ui();
    assert((REG(0x424) & ALPHA_MASK) == UI_OPAQUE && (REG(0x400) & UPDATE));
    REG(0x400) &= ~UPDATE;
    guard_ui();                                            /* already held: nothing more */
    assert(!(REG(0x400) & UPDATE));
    REG(0x600) = ENABLE;
    guard_ui();
    assert(REG(0x424) == 0 && (REG(0x400) & UPDATE));
    REG(0x500) = ctrl; REG(0x50c) = camera_base;
    assert(hide(720, 480));                                /* restore puts the UI's 0 back */
    REG(0x424) |= UI_OPAQUE;
    restore();
    assert((REG(0x424) & ALPHA_MASK) == 0);
    puts("overlay guards, alpha restore, disabled-camera, driver-reset and UI-guard cases passed");
    return 0;
}
