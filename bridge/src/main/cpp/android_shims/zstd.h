#ifndef DEFKON_ANDROID_ZSTD_SHIM_H
#define DEFKON_ANDROID_ZSTD_SHIM_H

#include <stddef.h>
#include <stdlib.h>

typedef struct ZSTD_CCtx_s ZSTD_CCtx;
typedef struct ZSTD_DCtx_s ZSTD_DCtx;
typedef struct ZSTD_CStream_s ZSTD_CStream;

typedef struct {
    const void *src;
    size_t size;
    size_t pos;
} ZSTD_inBuffer;

typedef struct {
    void *dst;
    size_t size;
    size_t pos;
} ZSTD_outBuffer;

#if defined(__ANDROID__) && !defined(DEFKON_HAS_ANDROID_ALIGNED_ALLOC_SHIM)
#define DEFKON_HAS_ANDROID_ALIGNED_ALLOC_SHIM
static inline void *aligned_alloc(size_t alignment, size_t size) {
    (void) alignment;
    return malloc(size);
}
#endif

#endif
