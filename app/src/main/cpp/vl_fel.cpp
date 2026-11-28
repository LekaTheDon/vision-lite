// Vision's driver for the Amlogic dual-layer Dolby Vision port (/dev/amstream_dves_hevc).
//
// Thin by design: every call is one syscall on an fd the Kotlin side owns (FelPort). Samples are written straight
// from the player's direct buffer, no copy here; the Kotlin side keeps the buffer until its last byte is in.
// Kernel interface facts used (Amlogic amstream uapi): ioctl magic 'S', AMSTREAM_IOC_SET/GET carry
// struct am_ioctl_parm { u64 data; u32 cmd; u32 reserved; }, SYSINFO carries struct dec_sysinfo.
// Sideband: the platform ANativeWindow's perform(NATIVE_WINDOW_SET_SIDEBAND_STREAM) hook (AOSP window.h layout),
// with an Amlogic sideband native handle (1 fd + id/flags/channel ints).
#include <jni.h>
#include <android/log.h>
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

#define TAG "VlFel"
#define WARN(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

const char *const PORT = "/dev/amstream_dves_hevc";
const char *const AMVIDEO = "/dev/amvideo";

// _IOW/_IOR/_IOWR('S', nr, size) for the amstream ioctls used here.
constexpr unsigned long iocW(unsigned nr, unsigned size) { return (1UL << 30) | (size << 16) | ('S' << 8) | nr; }
constexpr unsigned long iocR(unsigned nr, unsigned size) { return (2UL << 30) | (size << 16) | ('S' << 8) | nr; }
constexpr unsigned long iocWR(unsigned nr, unsigned size) { return (3UL << 30) | (size << 16) | ('S' << 8) | nr; }

struct Parm { uint64_t data; uint32_t cmd; uint32_t reserved; };
static_assert(sizeof(Parm) == 16, "am_ioctl_parm");
struct DecSysinfo { uint32_t format, width, height, rate, extra, status, ratio; void *param; uint64_t ratio64; };

const unsigned long IOC_SYSINFO = iocW(0x0a, 4);
const unsigned long IOC_VB_STATUS = iocR(0x08, 4);
const unsigned long IOC_VPAUSE = iocW(0x17, 4);
const unsigned long IOC_GET = iocWR(0xc1, sizeof(Parm));
const unsigned long IOC_SET = iocW(0xc2, sizeof(Parm));
const unsigned long IOC_TSTAMP_US64 = iocW(0x95, 4);  // the argument points to a u64

int set(int fd, uint32_t cmd, uint64_t v) {
    Parm p{v, cmd, 0};
    if (ioctl(fd, IOC_SET, &p) == 0) return 0;
    int e = errno;
    WARN("set 0x%x: %s", cmd, strerror(e));
    return -e;
}

// ---- sideband (AOSP nativebase.h / window.h, Android 14) ----
struct Base { int magic, version; void *reserved[4]; void (*incRef)(Base *); void (*decRef)(Base *); };
struct Window {
    Base common; uint32_t flags; int minSwap, maxSwap; float xdpi, ydpi; intptr_t oem[4];
    void *setSwapInterval, *dequeueOld, *lockOld, *queueOld, *query;
    int (*perform)(Window *, int, ...);
    void *cancelOld, *dequeue, *queue, *cancel;
};
static_assert(offsetof(Window, perform) == (sizeof(void *) == 8 ? 152 : 88), "perform");
static_assert(sizeof(Window) == (sizeof(void *) == 8 ? 192 : 108), "ANativeWindow");
constexpr int WINDOW_MAGIC = ('_' << 24) | ('w' << 16) | ('n' << 8) | 'd';
constexpr int SET_SIDEBAND_STREAM = 18;

struct Handle { int version, numFds, numInts, fd; uint32_t id; int flags, channel; };
static_assert(sizeof(Handle) == 28, "native_handle 1 fd + 3 ints");

struct Band { ANativeWindow *win; Handle h; };

}  // namespace

extern "C" {

// 1 = port writable, 2 = /dev/amvideo usable, 4 = Dolby Vision driver present.
JNIEXPORT jint JNICALL Java_com_lekathedon_visionlite_player_fel_FelPort_nCaps(JNIEnv *, jclass) {
    struct stat st{};
    return (access(PORT, W_OK) == 0 ? 1 : 0) | (access(AMVIDEO, R_OK | W_OK) == 0 ? 2 : 0) |
           (stat("/sys/class/amdolby_vision", &st) == 0 ? 4 : 0);
}

// Opens and configures the port for HEVC with app timestamps. Returns the fd or -errno.
JNIEXPORT jint JNICALL Java_com_lekathedon_visionlite_player_fel_FelPort_nOpen(JNIEnv *, jclass, jint w, jint h, jint rate) {
    int fd = open(PORT, O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return -errno;
    int r;
    DecSysinfo si{15 /* VIDEO_DEC_FORMAT_HEVC */, (uint32_t) w, (uint32_t) h, (uint32_t) (rate > 0 ? rate : 4004), 0, 0, 0,
                  reinterpret_cast<void *>(1) /* external PTS */, 0};
    if ((r = set(fd, 0x105 /* SET_VFORMAT */, 11 /* VFORMAT_HEVC */)) < 0 || (r = set(fd, 0x107 /* SET_VID */, 0)) < 0) goto fail;
    // Frames go "amlvideo -> amvideo", where the Dolby Vision driver composes BL + EL. Older ports lack it: go on.
    set(fd, 0x174 /* SET_FRAME_BASE_PATH */, 1);
    if (ioctl(fd, IOC_SYSINFO, &si) < 0) { r = -errno; goto fail; }
    if ((r = set(fd, 0x11c /* SET_DRMMODE */, 0)) < 0 || (r = set(fd, 0x111 /* PORT_INIT */, 0)) < 0) goto fail;
    return fd;
fail:
    close(fd);
    return r;
}

// Timestamp (us) for the next byte written. 0 or -errno.
JNIEXPORT jint JNICALL Java_com_lekathedon_visionlite_player_fel_FelPort_nPts(JNIEnv *, jclass, jint fd, jlong us) {
    uint64_t v = (uint64_t) us;
    return ioctl(fd, IOC_TSTAMP_US64, &v) == 0 ? 0 : -errno;
}

// Writes what the port takes now from a direct buffer. Bytes written (0 = full, try later) or -errno.
JNIEXPORT jint JNICALL Java_com_lekathedon_visionlite_player_fel_FelPort_nWrite(JNIEnv *env, jclass, jint fd, jobject buf, jint off, jint len) {
    auto *p = static_cast<uint8_t *>(env->GetDirectBufferAddress(buf));
    if (!p || off < 0 || len < 0 || (jlong) off + len > env->GetDirectBufferCapacity(buf)) return -EINVAL;
    size_t done = 0;
    while (done < (size_t) len) {
        ssize_t n = write(fd, p + off + done, (size_t) len - done);
        if (n > 0) { done += (size_t) n; continue; }
        if (n < 0 && errno == EINTR) continue;
        if (n < 0 && errno != EAGAIN) return -errno;
        break;
    }
    return (jint) done;
}

JNIEXPORT jint JNICALL Java_com_lekathedon_visionlite_player_fel_FelPort_nSet(JNIEnv *, jclass, jint fd, jint cmd, jlong v) {
    return set(fd, (uint32_t) cmd, (uint64_t) v);
}

// AMSTREAM_GET_* value (u32) or -errno.
JNIEXPORT jlong JNICALL Java_com_lekathedon_visionlite_player_fel_FelPort_nGet(JNIEnv *, jclass, jint fd, jint cmd) {
    Parm p{0, (uint32_t) cmd, 0};
    return ioctl(fd, IOC_GET, &p) == 0 ? (jlong) (uint32_t) p.data : -errno;
}

// Stream buffer: out = {size, data_len, free_len}. 0 or -errno.
JNIEXPORT jint JNICALL Java_com_lekathedon_visionlite_player_fel_FelPort_nFill(JNIEnv *env, jclass, jint fd, jintArray out) {
    int32_t raw[64] = {};  // am_io_param {int data; int len; buf_status ...}: the kernel may copy more, keep room
    if (ioctl(fd, IOC_VB_STATUS, raw) < 0) return -errno;
    env->SetIntArrayRegion(out, 0, 3, reinterpret_cast<jint *>(raw + 2));
    return 0;
}

JNIEXPORT jint JNICALL Java_com_lekathedon_visionlite_player_fel_FelPort_nCtl(JNIEnv *, jclass) {
    int fd = open(AMVIDEO, O_RDWR | O_CLOEXEC);
    return fd >= 0 ? fd : -errno;
}

// Video presentation hold (1) / go (0) on /dev/amvideo. 0 or -errno.
JNIEXPORT jint JNICALL Java_com_lekathedon_visionlite_player_fel_FelPort_nHold(JNIEnv *, jclass, jint ctl, jboolean on) {
    int v = on ? 1 : 0;
    return ioctl(ctl, IOC_VPAUSE, v) == 0 ? 0 : -errno;
}

JNIEXPORT void JNICALL Java_com_lekathedon_visionlite_player_fel_FelPort_nClose(JNIEnv *, jclass, jint fd) {
    if (fd >= 0) close(fd);
}

// Puts an Amlogic sideband stream (flags: 0x20 = OMX video) on the Surface. Handle or -errno.
JNIEXPORT jlong JNICALL Java_com_lekathedon_visionlite_player_fel_FelPort_nBand(JNIEnv *env, jclass, jobject surface, jint flags, jint channel) {
    ANativeWindow *win = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    if (!win) return -EINVAL;
    auto *w = reinterpret_cast<Window *>(win);
    if (w->common.magic != WINDOW_MAGIC || w->common.version != (int) sizeof(Window) || !w->perform) {
        WARN("sideband: window layout %x/%d not the expected one", w->common.magic, w->common.version);
        ANativeWindow_release(win);
        return -ENOSYS;
    }
    int nul = open("/dev/null", O_RDONLY | O_CLOEXEC);
    auto *b = nul >= 0 ? new (std::nothrow) Band{win, Handle{12, 1, 3, nul, 0xabcdcdef, flags, channel}} : nullptr;
    int r = b ? w->perform(w, SET_SIDEBAND_STREAM, &b->h) : -ENOMEM;
    if (r != 0) {
        WARN("sideband: set failed %d", r);
        if (nul >= 0) close(nul);
        delete b;
        ANativeWindow_release(win);
        return r < 0 ? r : -EIO;
    }
    return (jlong) reinterpret_cast<uintptr_t>(b);
}

JNIEXPORT void JNICALL Java_com_lekathedon_visionlite_player_fel_FelPort_nBandOff(JNIEnv *, jclass, jlong h) {
    auto *b = reinterpret_cast<Band *>((uintptr_t) h);
    if (!b) return;
    auto *w = reinterpret_cast<Window *>(b->win);
    w->perform(w, SET_SIDEBAND_STREAM, static_cast<Handle *>(nullptr));
    close(b->h.fd);
    ANativeWindow_release(b->win);
    delete b;
}

}  // extern "C"
