/* Camera-layer alpha control for units whose device tree shows the tcc803x display layout.
 * Boot-started by NEMESIS for CAP_SYS_RAWIO. Only uid 1000 can request a camera
 * lease; no polling, framebuffer copies, overlay routing or priority changes.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/system_properties.h>
#include <sys/un.h>
#include <unistd.h>

#define ENDPOINT "dev.skramer.dashcam.overlay"
#define AEN (1u << 11)
#define ASEL (1u << 24)
#define UPDATE (1u << 16)
#define ENABLE (1u << 28)
#define ALPHA_MASK 0x00ff00ffu
static volatile sig_atomic_t stopping;
static void stop(int sig) { (void)sig; stopping = 1; }
static volatile uint32_t *regs;
static uint32_t saved_bits, saved_alpha, saved_size;
static int hidden;
static uint32_t camera_base, camera_end;
#define REG(o) regs[(o) / 4]

static void restore(void) {
    if (!hidden) return;
    uint32_t ctrl = REG(0x500);
    /* A camera restart resets AEN. Do not restore saved values into a new
     * session after the driver has already restored its own settings. */
    if ((ctrl & (AEN | ASEL)) == AEN && REG(0x508) == saved_size) {
        REG(0x524) = (REG(0x524) & ~ALPHA_MASK) | saved_alpha;
        __sync_synchronize();
        REG(0x500) = (REG(0x500) & ~(AEN | ASEL)) | saved_bits | UPDATE;
        __sync_synchronize();
    }
    hidden = 0;
    fprintf(stderr, "overlay: restored camera alpha\n");
}

static int hide(unsigned w, unsigned h) {
    uint32_t ctrl = REG(0x500), base = REG(0x50c);
    uint64_t ring = (uint64_t)w * h * 16;
    if (hidden || w < 64 || h < 64 || w > 1920 || h > 1080 || (w | h) & 1 ||
        !(ctrl & ENABLE) || (ctrl & 31) != 12 ||
        REG(0x508) != ((h << 16) | w) || base < camera_base ||
        (uint64_t)base + (uint64_t)w * h * 4 > camera_end ||
        ring > camera_end - camera_base || REG(0x1808) != 0x02d00780) {
        fprintf(stderr, "overlay: refused %ux%u: rdma1 ctrl %08x size %08x base %08x, camera ram %08x-%08x, "
                "wmix0 size %08x%s\n", w, h, ctrl, REG(0x508), base, camera_base, camera_end, REG(0x1808),
                hidden ? ", already transparent" : "");
        return 0;
    }
    saved_bits = ctrl & (AEN | ASEL);
    saved_alpha = REG(0x524) & ALPHA_MASK;
    saved_size = REG(0x508);
    hidden = 1;
    REG(0x524) &= ~ALPHA_MASK;
    __sync_synchronize();
    REG(0x500) = (REG(0x500) & ~ASEL) | AEN | UPDATE;
    __sync_synchronize();
    fprintf(stderr, "overlay: camera transparent (%ux%u)\n", w, h);
    return 1;
}

static int read_request(int fd, unsigned char *buf, size_t size) {
    while (size && !stopping) {
        ssize_t n = recv(fd, buf, size, 0);
        if (n <= 0) return 0;
        buf += n; size -= (size_t)n;
    }
    return !stopping;
}

/* Big-endian 32-bit cells of a device-tree property; how many were read. */
static int dt_cells(const char *path, uint32_t *cells, int max) {
    unsigned char raw[64];
    int fd = open(path, O_RDONLY | O_CLOEXEC), n = 0;
    if (fd < 0) return 0;
    ssize_t len = read(fd, raw, sizeof raw);
    close(fd);
    for (; len >= 4 * (n + 1) && n < max; n++)
        cells[n] = (uint32_t)raw[4 * n] << 24 | (uint32_t)raw[4 * n + 1] << 16 | (uint32_t)raw[4 * n + 2] << 8 | raw[4 * n + 3];
    return n;
}

/* The display block at 0x12000000, and the main display's layers RDMA 0-3 (the camera's RDMA1):
 * the layout every register access here assumes. */
static int vioc_layout(void) {
    uint32_t reg[2], rdma[5];
    if (dt_cells("/proc/device-tree/fbdisplay@12000000/reg", reg, 2) != 2 ||
        reg[0] != 0x12000000 || reg[1] < 0x2000) return 0;
    return dt_cells("/proc/device-tree/fbdisplay@12000000/fbdisplay0@12000000/telechips,rdma", rdma, 5) == 5 &&
        rdma[1] == 0 && rdma[2] == 1 && rdma[3] == 2 && rdma[4] == 3;
}

/* Nothing to do on this unit: stay up so NEMESIS doesn't restart (and log) it every 10 s. */
static int idle(const char *why, const char *device) {
    fprintf(stderr, "overlay: %s on %s; no register access\n", why, device);
    for (;;) pause();
}

int main(void) {
    char device[PROP_VALUE_MAX] = "";
    __system_property_get("ro.product.device", device);
    if (!vioc_layout()) return idle("different display layout", device);
    FILE *pmap = fopen("/proc/pmap", "r");
    char line[256], name[128]; unsigned base, size;
    while (pmap && fgets(line, sizeof line, pmap)) {
        if (sscanf(line, "%x %x %127s", &base, &size, name) == 3 && !strcmp(name, "rearcamera")) {
            if (size <= UINT32_MAX - base) { camera_base = base; camera_end = base + size; }
            break;
        }
    }
    if (pmap) fclose(pmap);
    if (!camera_base || camera_end <= camera_base) return idle("no rearcamera region in /proc/pmap", device);
    fprintf(stderr, "overlay: %s, camera ram %08x-%08x\n", device, camera_base, camera_end);
    int mem = open("/dev/mem", O_RDWR | O_SYNC | O_CLOEXEC);
    if (mem < 0) { perror("overlay: /dev/mem (reboot after install/update)"); return 1; }
    regs = mmap(NULL, 0x2000, PROT_READ | PROT_WRITE, MAP_SHARED, mem, 0x12000000);
    close(mem);
    if (regs == MAP_FAILED) { perror("overlay: mmap"); return 1; }
    /* Keep the mapping; give up root and all capabilities before listening. */
    if (setgid(1000) || setuid(1000)) { perror("overlay: drop root"); return 1; }
    struct sigaction action; memset(&action, 0, sizeof action);
    action.sa_handler = stop;
    sigaction(SIGTERM, &action, NULL); sigaction(SIGINT, &action, NULL);
    sigaction(SIGHUP, &action, NULL); signal(SIGPIPE, SIG_IGN);
    int listener = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    struct sockaddr_un address; memset(&address, 0, sizeof address);
    address.sun_family = AF_UNIX;
    memcpy(address.sun_path + 1, ENDPOINT, sizeof ENDPOINT - 1);
    if (listener < 0 || bind(listener, (struct sockaddr *)&address,
            offsetof(struct sockaddr_un, sun_path) + sizeof ENDPOINT) || listen(listener, 2)) {
        perror("overlay: socket"); return 1;
    }
    fprintf(stderr, "overlay: ready\n");
    while (!stopping) {
        int client = accept(listener, NULL, NULL);
        if (client < 0) { if (errno == EINTR) continue; break; }
        struct ucred peer; socklen_t length = sizeof peer;
        if (getsockopt(client, SOL_SOCKET, SO_PEERCRED, &peer, &length) || peer.uid != 1000) {
            close(client); continue;
        }
        /* Bound partial commands, then sleep indefinitely for release/death. */
        struct timeval timeout = {0, 500000};
        setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof timeout);
        unsigned char request[5];
        int ok = read_request(client, request, sizeof request) && request[0] == 'H' &&
            hide(((unsigned)request[1] << 8) | request[2], ((unsigned)request[3] << 8) | request[4]);
        if (ok) {
            timeout.tv_usec = 0;
            setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof timeout);
            if (send(client, "H", 1, MSG_NOSIGNAL) == 1) {
                unsigned char release;
                read_request(client, &release, 1);
            }
            restore();
            send(client, "S", 1, MSG_NOSIGNAL);
        } else send(client, "N", 1, MSG_NOSIGNAL);
        close(client);
    }
    restore(); close(listener); munmap((void *)regs, 0x2000);
    return 0;
}
