#ifdef __APPLE__

#include "epoll_shim.h"
#include <unistd.h>
#include <fcntl.h>

int epoll_create1(int flags) {
    (void)flags; // Suppress unused parameter warning
    return kqueue();
}

int epoll_create(int size) {
    (void)size; // Suppress unused parameter warning
    return epoll_create1(0);
}

int epoll_ctl(int epfd, int op, int fd, struct epoll_event *event) {
    struct kevent ke;
    
    if (op == EPOLL_CTL_ADD || op == EPOLL_CTL_MOD) {
        uint16_t flags = EV_ADD;
        if (event->events & EPOLLIN)
            EV_SET(&ke, fd, EVFILT_READ, flags, 0, 0, event->data.ptr);
        if (event->events & EPOLLOUT)
            EV_SET(&ke, fd, EVFILT_WRITE, flags, 0, 0, event->data.ptr);
        return kevent(epfd, &ke, 1, NULL, 0, NULL);
    } else if (op == EPOLL_CTL_DEL) {
        EV_SET(&ke, fd, EVFILT_READ, EV_DELETE, 0, 0, NULL);
        kevent(epfd, &ke, 1, NULL, 0, NULL);
        EV_SET(&ke, fd, EVFILT_WRITE, EV_DELETE, 0, 0, NULL);
        return kevent(epfd, &ke, 1, NULL, 0, NULL);
    }
    
    return -1;
}

int epoll_wait(int epfd, struct epoll_event *events, int maxevents, int timeout) {
    struct kevent kevents[maxevents];
    struct timespec ts;
    
    if (timeout >= 0) {
        ts.tv_sec = timeout / 1000;
        ts.tv_nsec = (timeout % 1000) * 1000000;
    }
    
    int nev = kevent(epfd, NULL, 0, kevents, maxevents,
                     timeout >= 0 ? &ts : NULL);
    
    for (int i = 0; i < nev; i++) {
        events[i].events = 0;
        if (kevents[i].filter == EVFILT_READ)
            events[i].events |= EPOLLIN;
        if (kevents[i].filter == EVFILT_WRITE)
            events[i].events |= EPOLLOUT;
        if (kevents[i].flags & EV_ERROR)
            events[i].events |= EPOLLERR;
        if (kevents[i].flags & EV_EOF)
            events[i].events |= EPOLLHUP;
        events[i].data.ptr = kevents[i].udata;
    }
    
    return nev;
}

#endif /* __APPLE__ */
