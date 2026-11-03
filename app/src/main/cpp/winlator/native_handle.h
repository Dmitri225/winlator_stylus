/*
 * Minimal subset of AOSP system/core/libcutils/include/cutils/native_handle.h
 * (Apache License 2.0). Only the layout of native_handle_t is needed here:
 * the header is not part of the public NDK, so it was missing from the repo.
 */
#ifndef WINLATOR_NATIVE_HANDLE_H
#define WINLATOR_NATIVE_HANDLE_H

#ifdef __cplusplus
extern "C" {
#endif

typedef struct native_handle {
    int version;  /* sizeof(native_handle_t) */
    int numFds;   /* number of file descriptors at &data[0] */
    int numInts;  /* number of ints at &data[numFds] */
    int data[0];  /* numFds + numInts ints */
} native_handle_t;

#ifdef __cplusplus
}
#endif

#endif
