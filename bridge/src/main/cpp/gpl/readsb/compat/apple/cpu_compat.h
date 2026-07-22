#ifndef CPU_COMPAT_H
#define CPU_COMPAT_H

#ifdef __APPLE__

#include <stdint.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/sysctl.h>

/* CPU set definitions */
typedef uint64_t cpu_set_t;
#define CPU_SETSIZE 1024
#define CPU_COUNT(set) __builtin_popcountll(*(set))
#define CPU_ZERO(set) (*(set) = 0)
#define CPU_SET(cpu, set) (*(set) |= (1ULL << (cpu)))
#define CPU_CLR(cpu, set) (*(set) &= ~(1ULL << (cpu)))
#define CPU_ISSET(cpu, set) ((*(set) & (1ULL << (cpu))) != 0)

/* CPU affinity functions */
static inline int sched_getaffinity(pid_t pid, size_t cpu_size, cpu_set_t *mask) {
    (void)pid;      // Suppress unused parameter warning
    (void)cpu_size; // Suppress unused parameter warning
    int32_t core_count = 0;
    size_t len = sizeof(core_count);
    if (sysctlbyname("hw.logicalcpu", &core_count, &len, NULL, 0) == 0) {
        CPU_ZERO(mask);
        for (int i = 0; i < core_count; i++) {
            CPU_SET(i, mask);
        }
        return 0;
    }
    return -1;
}

static inline int sched_setaffinity(pid_t pid, size_t cpusetsize, cpu_set_t *mask) {
    /* On macOS, we can't actually set CPU affinity, so we just pretend it worked */
    (void)pid;
    (void)cpusetsize;
    (void)mask;
    return 0;
}

#endif /* __APPLE__ */
#endif /* CPU_COMPAT_H */
