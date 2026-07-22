#ifndef NET_COMPAT_H
#define NET_COMPAT_H

#ifdef __APPLE__

#include <sys/types.h>
#include <sys/socket.h>
#include <sys/fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <unistd.h>

/* Socket flags */
#ifndef SOCK_NONBLOCK
#define SOCK_NONBLOCK 0x00004000  /* Match Linux's value */
#endif

#ifndef SOCK_CLOEXEC
#define SOCK_CLOEXEC FD_CLOEXEC
#endif

/* TCP socket options */
#ifndef SOL_TCP
#define SOL_TCP IPPROTO_TCP
#endif

/* TCP keepalive options */
#ifndef TCP_KEEPIDLE
#define TCP_KEEPIDLE TCP_KEEPALIVE
#endif

#ifndef TCP_KEEPINTVL
#define TCP_KEEPINTVL 0x101  /* Time between keepalive probes */
#endif

#ifndef TCP_KEEPCNT
#define TCP_KEEPCNT   0x102  /* Number of keepalive probes before disconnect */
#endif

/* Basic socket operations */
static inline int set_socket_nonblock(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags == -1) return -1;
    return fcntl(fd, F_SETFL, flags | O_NONBLOCK);
}

static inline int create_socket_nonblock(int domain, int type, int protocol) {
    int fd = socket(domain, type & ~SOCK_NONBLOCK, protocol);
    if (fd < 0) return fd;
    
    if (type & SOCK_NONBLOCK) {
        if (set_socket_nonblock(fd) < 0) {
            close(fd);
            return -1;
        }
    }
    
    return fd;
}

/* accept4 compatibility */
static inline int accept4(int sockfd, struct sockaddr *addr, socklen_t *addrlen, int flags) {
    int fd = accept(sockfd, addr, addrlen);
    if (fd < 0) return fd;
    
    if (flags & SOCK_NONBLOCK) {
        if (set_socket_nonblock(fd) < 0) {
            close(fd);
            return -1;
        }
    }
    
    if (flags & SOCK_CLOEXEC) {
        int current = fcntl(fd, F_GETFD);
        if (current < 0 || fcntl(fd, F_SETFD, current | FD_CLOEXEC) < 0) {
            close(fd);
            return -1;
        }
    }
    
    return fd;
}

/* Socket creation override */
#ifdef socket
#undef socket
#endif
#define socket(domain, type, protocol) create_socket_nonblock(domain, type, protocol)

/* TCP keepalive compatibility */
static inline int set_tcp_keepalive(int fd, int idle, int interval, int count) {
    int ret = 0;
    
    /* Enable keepalive */
    int yes = 1;
    ret |= setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &yes, sizeof(yes));
    
    /* On macOS, TCP_KEEPALIVE is in seconds */
    ret |= setsockopt(fd, IPPROTO_TCP, TCP_KEEPALIVE, &idle, sizeof(idle));
    
    /* macOS doesn't support changing the interval and count directly */
    (void)interval;
    (void)count;
    
    return ret;
}

#endif /* __APPLE__ */
#endif /* NET_COMPAT_H */
