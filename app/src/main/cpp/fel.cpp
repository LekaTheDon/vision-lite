// Vision Lite: Dolby Vision profile 7 (FEL/MEL) on Amlogic through the dual-layer amstream port.
//
// The port (/dev/amstream_dves_hevc) takes the untouched P7 Annex-B stream and decodes base + enhancement layer
// itself; the Dolby core composes them. Samples carry a 64-bit microsecond PTS; presentation follows the tsync
// clock (pcrscr, 90 kHz), which the Kotlin renderer keeps on the audio clock. The picture reaches the screen as an
// Amlogic sideband stream on the player's SurfaceView.
//
// Kernel facts (AM9 FEL kernel, include/linux/amlogic/media/utils/amstream.h):
//  - never send AMSTREAM_SET_EOS on this port (wedges the EL decoder),
//  - FRAME_BASE_PATH 1 (amlvideo -> amvideo) or the DV core only sees HDR10,
//  - AMSTREAM_IOC_TSTAMP_uS64 before each sample's first byte,
//  - a VIDEO_PAUSE left on at close freezes every later player.
#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <errno.h>
#include <fcntl.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <new>
#include <vector>

#define TAG "VLFel"
#define WARN(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define EXPORT extern "C" JNIEXPORT

namespace {

const char *const PORT = "/dev/amstream_dves_hevc";
const char *const AMVIDEO = "/dev/amvideo";

// _IOx('S', nr, ...)
const unsigned long IOC_SYSINFO = 0x4004530a;
const unsigned long IOC_VB_STATUS = 0x80045308;
const unsigned long IOC_VPAUSE = 0x40045317;
const unsigned long IOC_GET = 0xc01053c1;
const unsigned long IOC_SET = 0x401053c2;
const unsigned long IOC_TSTAMP_US64 = 0x40045395;

enum : uint32_t {
    SET_VFORMAT = 0x105, SET_VID = 0x107, PORT_INIT = 0x111, SET_PCRSCR = 0x118, SET_DRMMODE = 0x11c,
    SET_FRAME_BASE_PATH = 0x174, GET_VPTS = 0x805,
    VFORMAT_HEVC = 11, DEC_FORMAT_HEVC = 15, PTS_FROM_APP = 1, PATH_AMLVIDEO_AMVIDEO = 1,
};

struct Parm { uint64_t data; uint32_t cmd; uint32_t pad; };
struct SysInfo { uint32_t format, width, height, rate, extra, status, ratio; void *param; uint64_t ratio64; };
static_assert(sizeof(Parm) == 16, "am_ioctl_parm");

struct Port {
    int fd = -1;
    int video = -1;
    bool paused = false;
    std::vector<uint8_t> buf;
    size_t at = 0, len = 0;
    int64_t pts = -1;
    bool ptsDone = true;
};

int set(int fd, uint32_t cmd, uint64_t v) {
    Parm p{v, cmd, 0};
    if (ioctl(fd, IOC_SET, &p) == 0) return 0;
    int e = errno;
    WARN("SET 0x%x: %s", cmd, strerror(e));
    return -e;
}

Port *port(jlong h) { return reinterpret_cast<Port *>(static_cast<uintptr_t>(h)); }
jlong handle(void *p) { return static_cast<jlong>(reinterpret_cast<uintptr_t>(p)); }

// ANativeWindow as the platform defines it (system/window.h); only magic, version and perform() are touched.
struct Base { int magic, version; void *reserved[4]; void (*inc)(Base *); void (*dec)(Base *); };
struct Window {
    Base base;
    uint32_t flags;
    int minSwap, maxSwap;
    float xdpi, ydpi;
    intptr_t oem[4];
    void *fn0, *fn1, *fn2, *fn3, *query;
    int (*perform)(Window *, int, ...);
    void *fn4, *fn5, *fn6, *fn7;
};
static_assert(offsetof(Window, perform) == (sizeof(void *) == 8 ? 152 : 88), "perform");
static_assert(sizeof(Window) == (sizeof(void *) == 8 ? 192 : 108), "window");
const int WINDOW_MAGIC = ('_' << 24) | ('w' << 16) | ('n' << 8) | 'd';
const int SET_SIDEBAND_STREAM = 18;

// native_handle_t + 1 fd + 3 ints = Amlogic's sideband handle (id, video flags, channel).
struct Sideband { ANativeWindow *window; int version, fds, ints, fd; uint32_t id; int flags, channel; };
const uint32_t SIDEBAND_ID = 0xabcdcdef;
const int FLAG_VIDEO_OVERLAY = 0x10;

}  // namespace

// bit 0 port writable, bit 1 amvideo usable, bit 2 Dolby Vision driver present.
EXPORT jint JNICALL Java_com_lekathedon_visionlite_player_fel_FelNative_probe(JNIEnv *, jclass) {
    struct stat st{};
    return (access(PORT, W_OK) == 0 ? 1 : 0) | (access(AMVIDEO, R_OK | W_OK) == 0 ? 2 : 0) |
           (stat("/sys/class/amdolby_vision", &st) == 0 ? 4 : 0);
}

// Handle, or -errno. rate = 96000 / fps.
EXPORT jlong JNICALL Java_com_lekathedon_visionlite_player_fel_FelNative_open(JNIEnv *, jclass, jint w, jint h, jint rate) {
    int fd = open(PORT, O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return -errno;
    int r = set(fd, SET_VFORMAT, VFORMAT_HEVC);
    if (!r) r = set(fd, SET_VID, 0);
    if (!r) set(fd, SET_FRAME_BASE_PATH, PATH_AMLVIDEO_AMVIDEO);  // older kernels: default path
    if (!r) {
        SysInfo si{DEC_FORMAT_HEVC, (uint32_t) w, (uint32_t) h, (uint32_t) (rate > 0 ? rate : 4004), 0, 0, 0,
                   reinterpret_cast<void *>(static_cast<uintptr_t>(PTS_FROM_APP)), 0};
        if (ioctl(fd, IOC_SYSINFO, &si) < 0) r = -errno;
    }
    if (!r) r = set(fd, SET_DRMMODE, 0);
    if (!r) r = set(fd, PORT_INIT, 0);
    Port *p = r ? nullptr : new (std::nothrow) Port();
    if (!p) { close(fd); return r ? r : -ENOMEM; }
    p->fd = fd;
    return handle(p);
}

// Takes a copy of one sample (pts < 0: no timestamp, e.g. parameter sets). 0, -EBUSY (previous not written) or -errno.
EXPORT jint JNICALL Java_com_lekathedon_visionlite_player_fel_FelNative_queue(
        JNIEnv *env, jclass, jlong h, jobject data, jint off, jint len, jlong pts) {
    Port *p = port(h);
    if (!p || p->fd < 0) return -EBADF;
    if (p->at < p->len) return -EBUSY;
    auto *src = static_cast<uint8_t *>(env->GetDirectBufferAddress(data));
    if (!src || off < 0 || len <= 0 || (jlong) off + len > env->GetDirectBufferCapacity(data)) return -EINVAL;
    if (p->buf.size() < (size_t) len) {
        try { p->buf.resize((size_t) len); } catch (...) { return -ENOMEM; }
    }
    memcpy(p->buf.data(), src + off, (size_t) len);
    p->at = 0;
    p->len = (size_t) len;
    p->pts = pts;
    p->ptsDone = pts < 0;
    return 0;
}

// Writes what the stream buffer takes. Bytes still pending (0 = done) or -errno.
EXPORT jint JNICALL Java_com_lekathedon_visionlite_player_fel_FelNative_drain(JNIEnv *, jclass, jlong h) {
    Port *p = port(h);
    if (!p || p->fd < 0) return -EBADF;
    if (p->at >= p->len) return 0;
    if (!p->ptsDone) {
        uint64_t v = (uint64_t) p->pts;
        if (ioctl(p->fd, IOC_TSTAMP_US64, &v) < 0) return -errno;
        p->ptsDone = true;
    }
    while (p->at < p->len) {
        ssize_t n = write(p->fd, p->buf.data() + p->at, p->len - p->at);
        if (n > 0) { p->at += (size_t) n; continue; }
        if (n < 0 && errno == EINTR) continue;
        if (n < 0 && errno != EAGAIN) return -errno;
        break;  // buffer full
    }
    return (jint) (p->len - p->at);
}

EXPORT jint JNICALL Java_com_lekathedon_visionlite_player_fel_FelNative_setClock(JNIEnv *, jclass, jlong h, jint pts90k) {
    Port *p = port(h);
    return p && p->fd >= 0 ? set(p->fd, SET_PCRSCR, (uint32_t) pts90k) : -EBADF;
}

// Shown frame's PTS (90 kHz, u32) or -errno.
EXPORT jlong JNICALL Java_com_lekathedon_visionlite_player_fel_FelNative_shownPts(JNIEnv *, jclass, jlong h) {
    Port *p = port(h);
    if (!p || p->fd < 0) return -EBADF;
    Parm q{0, GET_VPTS, 0};
    if (ioctl(p->fd, IOC_GET, &q) < 0) return -errno;
    return (jlong) (uint32_t) q.data;
}

// Stream buffer fill in percent, or -errno.
EXPORT jint JNICALL Java_com_lekathedon_visionlite_player_fel_FelNative_fill(JNIEnv *, jclass, jlong h) {
    Port *p = port(h);
    if (!p || p->fd < 0) return -EBADF;
    int32_t raw[64] = {};  // am_io_param: data, len, buf_status{size, data_len, free_len, ...}
    if (ioctl(p->fd, IOC_VB_STATUS, raw) < 0) return -errno;
    return raw[2] > 0 ? (jint) ((int64_t) raw[3] * 100 / raw[2]) : -EIO;
}

EXPORT jint JNICALL Java_com_lekathedon_visionlite_player_fel_FelNative_pause(JNIEnv *, jclass, jlong h, jboolean on) {
    Port *p = port(h);
    if (!p) return -EBADF;
    if (p->video < 0 && (p->video = open(AMVIDEO, O_RDWR | O_CLOEXEC)) < 0) return -errno;
    if (ioctl(p->video, IOC_VPAUSE, on ? 1 : 0) < 0) return -errno;
    p->paused = on;
    return 0;
}

EXPORT void JNICALL Java_com_lekathedon_visionlite_player_fel_FelNative_close(JNIEnv *, jclass, jlong h) {
    Port *p = port(h);
    if (!p) return;
    if (p->video >= 0) {
        if (p->paused) ioctl(p->video, IOC_VPAUSE, 0);
        close(p->video);
    }
    if (p->fd >= 0) close(p->fd);
    delete p;
}

// Puts the Amlogic video plane in this Surface's rect. Handle or -errno.
EXPORT jlong JNICALL Java_com_lekathedon_visionlite_player_fel_FelNative_attach(JNIEnv *env, jclass, jobject surface) {
    ANativeWindow *nw = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    if (!nw) return -EINVAL;
    auto *w = reinterpret_cast<Window *>(nw);
    if (w->base.magic != WINDOW_MAGIC || w->base.version != (int) sizeof(Window) || !w->perform) {
        WARN("unexpected window layout");
        ANativeWindow_release(nw);
        return -ENOSYS;
    }
    int fd = open("/dev/null", O_RDONLY | O_CLOEXEC);
    auto *sb = fd < 0 ? nullptr : new (std::nothrow) Sideband{nw, 12, 1, 3, fd, SIDEBAND_ID, FLAG_VIDEO_OVERLAY, 0};
    if (!sb) {
        if (fd >= 0) close(fd);
        ANativeWindow_release(nw);
        return -ENOMEM;
    }
    int r = w->perform(w, SET_SIDEBAND_STREAM, &sb->version);
    if (r) {
        close(fd);
        ANativeWindow_release(nw);
        delete sb;
        return r < 0 ? r : -EIO;
    }
    return handle(sb);
}

EXPORT void JNICALL Java_com_lekathedon_visionlite_player_fel_FelNative_detach(JNIEnv *, jclass, jlong h) {
    auto *sb = reinterpret_cast<Sideband *>(static_cast<uintptr_t>(h));
    if (!sb) return;
    auto *w = reinterpret_cast<Window *>(sb->window);
    w->perform(w, SET_SIDEBAND_STREAM, (void *) nullptr);
    close(sb->fd);
    ANativeWindow_release(sb->window);
    delete sb;
}
