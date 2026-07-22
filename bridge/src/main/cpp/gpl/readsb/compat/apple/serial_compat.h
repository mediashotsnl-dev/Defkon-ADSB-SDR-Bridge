#ifndef SERIAL_COMPAT_H
#define SERIAL_COMPAT_H

#ifdef __APPLE__

#include <termios.h>
#include <sys/ioctl.h>
#include <IOKit/serial/ioss.h>

/* Define missing baud rates for macOS */
#ifndef B460800
#define B460800 460800
#endif

#ifndef B500000
#define B500000 500000
#endif

#ifndef B576000
#define B576000 576000
#endif

#ifndef B921600
#define B921600 921600
#endif

#ifndef B1000000
#define B1000000 1000000
#endif

#ifndef B1152000
#define B1152000 1152000
#endif

#ifndef B1500000
#define B1500000 1500000
#endif

#ifndef B2000000
#define B2000000 2000000
#endif

#ifndef B2500000
#define B2500000 2500000
#endif

#ifndef B3000000
#define B3000000 3000000
#endif

#ifndef B3500000
#define B3500000 3500000
#endif

#ifndef B4000000
#define B4000000 4000000
#endif

#ifdef __cplusplus
extern "C" {
#endif

#ifdef __cplusplus
}
#endif

#endif /* __APPLE__ */
#endif /* SERIAL_COMPAT_H */
