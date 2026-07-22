#ifndef STAT_COMPAT_H
#define STAT_COMPAT_H

#ifdef __APPLE__
#include <sys/stat.h>

/* macOS uses st_mtimespec instead of st_mtim */
#define st_mtim st_mtimespec

#endif /* __APPLE__ */
#endif /* STAT_COMPAT_H */
