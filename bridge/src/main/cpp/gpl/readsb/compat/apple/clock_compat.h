#ifndef CLOCK_COMPAT_H
#define CLOCK_COMPAT_H

#ifdef __APPLE__

#include <time.h>
#include <errno.h>
#include <sys/time.h>
#include <mach/mach_time.h>
#include <pthread.h>

/* Clock definitions */
#ifndef CLOCK_MONOTONIC
#define CLOCK_MONOTONIC 1
#endif

#ifndef TIMER_ABSTIME
#define TIMER_ABSTIME 1
#endif

/* Mach timebase info for conversion */
static mach_timebase_info_data_t __clock_timebase_info;
static pthread_once_t __clock_timebase_once = PTHREAD_ONCE_INIT;

static void __clock_timebase_init(void) {
    mach_timebase_info(&__clock_timebase_info);
}

/* Clock compatibility functions */
static inline uint64_t clock_gettime_nsec(void) {
    pthread_once(&__clock_timebase_once, __clock_timebase_init);
    return (mach_absolute_time() * __clock_timebase_info.numer) / __clock_timebase_info.denom;
}

static inline int clock_nanosleep(clockid_t clock_id, int flags,
                                const struct timespec *request,
                                struct timespec *remain) {
    (void)clock_id; // Only CLOCK_MONOTONIC supported
    (void)remain;   // remain not supported on macOS

    pthread_once(&__clock_timebase_once, __clock_timebase_init);

    if (flags & TIMER_ABSTIME) {
        // For absolute time, calculate the relative delay
        struct timespec now;
        clock_gettime(CLOCK_MONOTONIC, &now);

        uint64_t now_ns = (uint64_t)now.tv_sec * 1000000000ULL + now.tv_nsec;
        uint64_t target_ns = (uint64_t)request->tv_sec * 1000000000ULL + request->tv_nsec;

        if (now_ns >= target_ns) {
            return 0; // Target time already passed
        }

        uint64_t delay_ns = target_ns - now_ns;
        struct timespec ts = {
            .tv_sec = delay_ns / 1000000000ULL,
            .tv_nsec = delay_ns % 1000000000ULL
        };

        while (nanosleep(&ts, &ts) == -1) {
            if (errno != EINTR) {
                return errno;
            }
        }
    } else {
        // For relative time, just use nanosleep directly
        struct timespec ts = *request;
        while (nanosleep(&ts, &ts) == -1) {
            if (errno != EINTR) {
                return errno;
            }
        }
    }

    return 0;
}

#endif /* __APPLE__ */
#endif /* CLOCK_COMPAT_H */
