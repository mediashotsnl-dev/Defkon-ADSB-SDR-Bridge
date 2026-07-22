#ifndef SENDFILE_COMPAT_H
#define SENDFILE_COMPAT_H

#ifdef __APPLE__

#include <sys/types.h>
#include <sys/socket.h>
#include <sys/uio.h>
#include <unistd.h>
#include <errno.h>

/* sendfile compatibility for macOS */
static inline ssize_t linux_sendfile(int out_fd, int in_fd, off_t *offset, size_t count) {
    off_t len = count;
    
    /* Use native macOS sendfile if possible */
    if (sendfile(in_fd, out_fd, *offset, &len, NULL, 0) == 0) {
        *offset += len;
        return len;
    }
    
    /* Fall back to read/write if sendfile fails */
    char buffer[8192];
    size_t remaining = count;
    ssize_t total = 0;
    
    if (offset && lseek(in_fd, *offset, SEEK_SET) == -1) {
        return -1;
    }
    
    while (remaining > 0) {
        size_t to_read = (remaining < sizeof(buffer)) ? remaining : sizeof(buffer);
        
        ssize_t bytes_read = read(in_fd, buffer, to_read);
        if (bytes_read <= 0) {
            if (bytes_read == 0) break;  /* EOF */
            if (errno == EINTR) continue;
            if (total > 0) break;        /* Return partial success */
            return -1;
        }
        
        size_t to_write = bytes_read;
        size_t written = 0;
        
        while (written < to_write) {
            ssize_t ret = write(out_fd, buffer + written, to_write - written);
            if (ret <= 0) {
                if (ret == -1 && errno == EINTR) continue;
                if (total > 0 || written > 0) {
                    /* Return partial success */
                    total += written;
                    if (offset) *offset += total;
                    return total;
                }
                return -1;
            }
            written += ret;
        }
        
        total += written;
        remaining -= written;
    }
    
    if (offset) *offset += total;
    return total;
}

#define sendfile(out_fd, in_fd, offset, count) linux_sendfile(out_fd, in_fd, offset, count)

#endif /* __APPLE__ */
#endif /* SENDFILE_COMPAT_H */
