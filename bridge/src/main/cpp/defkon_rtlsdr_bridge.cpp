#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <mutex>

#include "rtl-sdr.h"

#define LOG_TAG "DefkonRtlSdrBridge"

namespace {
constexpr int ADSB_FREQUENCY_HZ = 1090000000;
std::mutex device_mutex;
rtlsdr_dev_t *device = nullptr;
bool closing = false;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mediashots_defkonadsbbridge_NativeRtlSdrDriver_nativeStatusInternal(
    JNIEnv *env,
    jclass
) {
    std::lock_guard<std::mutex> lock(device_mutex);
    const char *status = device != nullptr
        ? "NATIVE RTL-SDR DRIVER OPEN"
        : "NATIVE RTL-SDR DRIVER READY";
    return env->NewStringUTF(status);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mediashots_defkonadsbbridge_NativeRtlSdrDriver_nativeIsCoreLinked(
    JNIEnv *,
    jclass
) {
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mediashots_defkonadsbbridge_NativeRtlSdrDriver_nativeOpenFromUsbFileDescriptor(
    JNIEnv *,
    jclass,
    jint file_descriptor
) {
    __android_log_print(
        ANDROID_LOG_INFO,
        LOG_TAG,
        "nativeOpenFromUsbFileDescriptor fd=%d",
        static_cast<int>(file_descriptor)
    );

    std::lock_guard<std::mutex> lock(device_mutex);
    closing = true;
    if (device != nullptr) {
        rtlsdr_dev_t *old_device = device;
        device = nullptr;
        rtlsdr_close(old_device);
    }
    closing = false;

    int result = rtlsdr_open_from_fd(&device, static_cast<intptr_t>(file_descriptor));
    if (result != 0 || device == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "rtlsdr_open_from_fd failed result=%d", result);
        device = nullptr;
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mediashots_defkonadsbbridge_NativeRtlSdrDriver_nativeTuneAdsb1090(
    JNIEnv *,
    jclass,
    jint sample_rate_hz
) {
    if (sample_rate_hz <= 0) {
        return JNI_FALSE;
    }
    __android_log_print(
        ANDROID_LOG_INFO,
        LOG_TAG,
        "nativeTuneAdsb1090 freq=%d sampleRate=%d",
        ADSB_FREQUENCY_HZ,
        static_cast<int>(sample_rate_hz)
    );

    std::lock_guard<std::mutex> lock(device_mutex);
    if (device == nullptr || closing) {
        return JNI_FALSE;
    }

    int result = rtlsdr_set_sample_rate(device, static_cast<uint32_t>(sample_rate_hz));
    if (result != 0) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "rtlsdr_set_sample_rate failed result=%d", result);
        return JNI_FALSE;
    }

    rtlsdr_set_tuner_gain_mode(device, 0);
    rtlsdr_set_agc_mode(device, 1);

    result = rtlsdr_set_center_freq(device, ADSB_FREQUENCY_HZ);
    if (result != 0) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "rtlsdr_set_center_freq failed result=%d", result);
        return JNI_FALSE;
    }

    result = rtlsdr_reset_buffer(device);
    if (result != 0) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "rtlsdr_reset_buffer result=%d", result);
    }

    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mediashots_defkonadsbbridge_NativeRtlSdrDriver_nativeReadSamples(
    JNIEnv *env,
    jclass,
    jbyteArray target,
    jint length
) {
    if (target == nullptr || length <= 0) {
        return -1;
    }

    jbyte *buffer = env->GetByteArrayElements(target, nullptr);
    if (buffer == nullptr) {
        return -1;
    }

    int read = 0;
    int result = 0;
    {
        std::lock_guard<std::mutex> lock(device_mutex);
        if (device == nullptr || closing) {
            env->ReleaseByteArrayElements(target, buffer, JNI_ABORT);
            return -1;
        }
        result = rtlsdr_read_sync(device, buffer, length, &read);
    }
    env->ReleaseByteArrayElements(target, buffer, 0);
    if (result != 0) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "rtlsdr_read_sync failed result=%d", result);
        return result;
    }
    return read;
}

extern "C" JNIEXPORT void JNICALL
Java_com_mediashots_defkonadsbbridge_NativeRtlSdrDriver_nativeClose(
    JNIEnv *,
    jclass
) {
    std::lock_guard<std::mutex> lock(device_mutex);
    if (device != nullptr) {
        closing = true;
        rtlsdr_dev_t *old_device = device;
        device = nullptr;
        rtlsdr_close(old_device);
        closing = false;
    }
}
