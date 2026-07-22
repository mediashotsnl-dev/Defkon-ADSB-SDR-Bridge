#ifndef THREAD_COMPAT_H
#define THREAD_COMPAT_H

#ifdef __APPLE__

#include <pthread.h>
#include <time.h>
#include <errno.h>
#include <unistd.h>
#include "cpu_compat.h"

/* Forward declarations */
static inline int pthread_timedjoin_np(pthread_t thread, void **retval, const struct timespec *abstime);

/* pthread_tryjoin_np implementation for macOS */
static inline int pthread_tryjoin_np(pthread_t thread, void **retval) {
    struct timespec ts = { 0, 0 }; // Zero timeout
    return pthread_timedjoin_np(thread, retval, &ts);
}

/* pthread_timedjoin_np implementation for macOS */
static inline int pthread_timedjoin_np(pthread_t thread, void **retval, const struct timespec *abstime) {
    int res;
    struct timespec ts_start;
    
    /* Try joining immediately first */
    res = pthread_kill(thread, 0);
    if (res != 0) {
        return res;
    }
    
    if (clock_gettime(CLOCK_REALTIME, &ts_start) != 0) {
        return EINVAL;
    }
    
    while ((res = pthread_join(thread, retval)) == EBUSY) {
        struct timespec ts_now;
        if (clock_gettime(CLOCK_REALTIME, &ts_now) != 0) {
            return EINVAL;
        }
        
        /* Check if we've exceeded the timeout */
        if (ts_now.tv_sec > ts_start.tv_sec + abstime->tv_sec ||
            (ts_now.tv_sec == ts_start.tv_sec + abstime->tv_sec &&
             ts_now.tv_nsec >= ts_start.tv_nsec + abstime->tv_nsec)) {
            return ETIMEDOUT;
        }
        
        /* Sleep for a short time before trying again */
        struct timespec ts_sleep = { 0, 1000000 }; /* 1ms */
        nanosleep(&ts_sleep, NULL);
    }
    
    return res;
}

#endif /* __APPLE__ */
#endif /* THREAD_COMPAT_H */
