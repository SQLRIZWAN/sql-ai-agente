// sysinfo.cpp — low-overhead CPU/RAM sampling for the on-device system monitor.
#include <jni.h>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <unistd.h>
#include <sys/sysinfo.h>

namespace {

struct CpuSample { unsigned long long idle, total; };
CpuSample g_prev{0, 0};
bool g_has_prev = false;

CpuSample read_cpu() {
    CpuSample s{0, 0};
    FILE* f = fopen("/proc/stat", "r");
    if (!f) return s;
    char line[512];
    if (fgets(line, sizeof(line), f)) {
        // cpu  user nice system idle iowait irq softirq steal ...
        unsigned long long u = 0, n = 0, sy = 0, id = 0, io = 0, ir = 0, si = 0, st = 0;
        sscanf(line, "cpu %llu %llu %llu %llu %llu %llu %llu %llu",
               &u, &n, &sy, &id, &io, &ir, &si, &st);
        s.idle = id + io;
        s.total = u + n + sy + id + io + ir + si + st;
    }
    fclose(f);
    return s;
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_sqlai_agente_core_monitor_SystemMonitor_nativeCpuPercent(JNIEnv*, jobject) {
    CpuSample cur = read_cpu();
    if (!g_has_prev) { g_prev = cur; g_has_prev = true; return 0; }
    unsigned long long dTotal = cur.total - g_prev.total;
    unsigned long long dIdle = cur.idle - g_prev.idle;
    g_prev = cur;
    if (dTotal == 0) return 0;
    int pct = (int)((100.0 * (double)(dTotal - dIdle)) / (double)dTotal);
    return pct < 0 ? 0 : (pct > 100 ? 100 : pct);
}

JNIEXPORT jlong JNICALL
Java_com_sqlai_agente_core_monitor_SystemMonitor_nativeTotalMem(JNIEnv*, jobject) {
    struct sysinfo si{};
    if (sysinfo(&si) != 0) return 0;
    return (jlong)si.totalram * (jlong)si.mem_unit;
}

JNIEXPORT jlong JNICALL
Java_com_sqlai_agente_core_monitor_SystemMonitor_nativeAvailMem(JNIEnv*, jobject) {
    struct sysinfo si{};
    if (sysinfo(&si) != 0) return 0;
    return (jlong)si.freeram * (jlong)si.mem_unit;
}

JNIEXPORT jlong JNICALL
Java_com_sqlai_agente_core_monitor_SystemMonitor_nativePssBytes(JNIEnv* env, jobject) {
    // PSS of this process only — cheap approximation via /proc/self/smaps_rollup.
    FILE* f = fopen("/proc/self/smaps_rollup", "r");
    if (!f) return 0;
    char line[256];
    long long pss = 0;
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "Pss:", 4) == 0) {
            pss = atoll(line + 4);
            break;
        }
    }
    fclose(f);
    (void)env;
    return (jlong)pss * 1024LL;
}

JNIEXPORT jint JNICALL
Java_com_sqlai_agente_core_monitor_SystemMonitor_nativeThreadCount(JNIEnv*, jobject) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/self/task");
    // Count entries by directory scan fallback: use /proc/self/status Threads line.
    FILE* f = fopen("/proc/self/status", "r");
    if (!f) return 0;
    char line[128];
    int threads = 0;
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "Threads:", 8) == 0) { threads = atoi(line + 8); break; }
    }
    fclose(f);
    return threads;
}

} // extern "C"
