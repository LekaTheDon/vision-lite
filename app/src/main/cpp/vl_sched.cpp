// Big-core pinning for playback threads (DebridStream's libds_sched idea, written for Lite): on a box whose cores
// differ (big.LITTLE), the threads that feed the picture and draw subtitles stay on the fastest cores. Boxes whose
// cores are all the same (the AM9: 4 x 2.4 GHz) are left alone.
#include <jni.h>
#include <sched.h>
#include <stdio.h>
#include <unistd.h>

static long max_freq(int cpu) {
    char path[96];
    snprintf(path, sizeof path, "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
    FILE *f = fopen(path, "r");
    if (!f) return -1;
    long v = -1;
    if (fscanf(f, "%ld", &v) != 1) v = -1;
    fclose(f);
    return v;
}

// The fastest cores as a mask; 0 when every core is the same (nothing to gain) or nothing can be read.
static int big_mask(cpu_set_t *set) {
    long n = sysconf(_SC_NPROCESSORS_CONF);
    if (n <= 1 || n > CPU_SETSIZE) return 0;
    long top = -1, low = -1;
    for (int c = 0; c < n; c++) {
        long f = max_freq(c);
        if (f <= 0) continue;
        if (f > top) top = f;
        if (low < 0 || f < low) low = f;
    }
    if (top <= 0 || top == low) return 0;
    CPU_ZERO(set);
    int count = 0;
    for (int c = 0; c < n; c++) if (max_freq(c) == top) { CPU_SET(c, set); count++; }
    return count;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_lekathedon_visionlite_player_BigCores_nativeBigCount(JNIEnv *, jclass) {
    cpu_set_t set;
    return big_mask(&set);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_lekathedon_visionlite_player_BigCores_nativePin(JNIEnv *, jclass, jint tid) {
    cpu_set_t set;
    if (big_mask(&set) <= 0) return 0;
    return sched_setaffinity((pid_t) tid, sizeof set, &set) == 0 ? 1 : -1;
}
