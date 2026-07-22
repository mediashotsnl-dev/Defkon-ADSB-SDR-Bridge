cmake_minimum_required(VERSION 3.22.1)

project(defkon_rtlsdr_bridge)

set(GPL_DIR ${CMAKE_CURRENT_SOURCE_DIR}/gpl)
set(LIBUSB_DIR ${GPL_DIR}/libusb)
set(RTLSDR_DIR ${GPL_DIR}/rtl-sdr)
set(READSB_DIR ${GPL_DIR}/readsb)

set(LIBUSB_GENERATED_DIR ${CMAKE_CURRENT_BINARY_DIR}/generated/libusb)
file(MAKE_DIRECTORY ${LIBUSB_GENERATED_DIR})
configure_file(
    ${CMAKE_CURRENT_SOURCE_DIR}/libusb_version_describe.h.in
    ${LIBUSB_GENERATED_DIR}/version_describe.h
    @ONLY
)

add_library(libusb_static STATIC
    ${LIBUSB_DIR}/libusb/core.c
    ${LIBUSB_DIR}/libusb/descriptor.c
    ${LIBUSB_DIR}/libusb/hotplug.c
    ${LIBUSB_DIR}/libusb/io.c
    ${LIBUSB_DIR}/libusb/sync.c
    ${LIBUSB_DIR}/libusb/strerror.c
    ${LIBUSB_DIR}/libusb/os/linux_usbfs.c
    ${LIBUSB_DIR}/libusb/os/events_posix.c
    ${LIBUSB_DIR}/libusb/os/threads_posix.c
    ${LIBUSB_DIR}/libusb/os/linux_netlink.c
)

target_include_directories(libusb_static PUBLIC
    ${LIBUSB_DIR}/android
    ${LIBUSB_DIR}/libusb
    ${LIBUSB_DIR}/libusb/os
    ${LIBUSB_GENERATED_DIR}
)

target_compile_options(libusb_static PRIVATE
    -fvisibility=hidden
    -pthread
)

add_library(rtlsdr_static STATIC
    ${RTLSDR_DIR}/src/librtlsdr.c
    ${RTLSDR_DIR}/src/tuner_e4k.c
    ${RTLSDR_DIR}/src/tuner_fc0012.c
    ${RTLSDR_DIR}/src/tuner_fc0013.c
    ${RTLSDR_DIR}/src/tuner_fc2580.c
    ${RTLSDR_DIR}/src/tuner_r82xx.c
)

target_include_directories(rtlsdr_static PUBLIC
    ${RTLSDR_DIR}/include
    ${LIBUSB_DIR}/libusb
)

target_compile_definitions(rtlsdr_static PRIVATE
    rtlsdr_STATIC
)

add_library(defkon_rtlsdr_bridge SHARED
    defkon_rtlsdr_bridge.cpp
    native_modes_decoder.cpp
    readsb_bridge_adapter.c
    ${READSB_DIR}/ais_charset.c
    ${READSB_DIR}/comm_b.c
    ${READSB_DIR}/crc.c
    ${READSB_DIR}/cpr.c
    ${READSB_DIR}/demod_2400.c
    ${READSB_DIR}/mode_ac.c
    ${READSB_DIR}/mode_s.c
)

find_library(log-lib log)
find_library(m-lib m)

target_include_directories(defkon_rtlsdr_bridge PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/android_shims
    ${RTLSDR_DIR}/include
)

target_link_libraries(defkon_rtlsdr_bridge
    rtlsdr_static
    libusb_static
    ${log-lib}
    ${m-lib}
)
