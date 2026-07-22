package com.mediashots.defkonadsbbridge;

final class NativeRtlSdrDriver {
    private static final String LIBRARY_NAME = "defkon_rtlsdr_bridge";
    private static final boolean LIBRARY_LOADED;

    static {
        boolean loaded;
        try {
            System.loadLibrary(LIBRARY_NAME);
            loaded = true;
        } catch (UnsatisfiedLinkError error) {
            loaded = false;
        }
        LIBRARY_LOADED = loaded;
    }

    private NativeRtlSdrDriver() {
    }

    static boolean isLibraryLoaded() {
        return LIBRARY_LOADED;
    }

    static boolean isCoreLinked() {
        return LIBRARY_LOADED && nativeIsCoreLinked();
    }

    static String nativeStatus() {
        if (!LIBRARY_LOADED) return "NATIVE DRIVER LIBRARY NOT LOADED";
        return nativeStatusInternal();
    }

    static boolean openFromUsbFileDescriptor(int fileDescriptor) {
        return LIBRARY_LOADED && nativeOpenFromUsbFileDescriptor(fileDescriptor);
    }

    static boolean tuneAdsb1090(int sampleRateHz) {
        return LIBRARY_LOADED && sampleRateHz > 0 && nativeTuneAdsb1090(sampleRateHz);
    }

    static int readSamples(byte[] target, int length) {
        if (!LIBRARY_LOADED || target == null || length <= 0) return -1;
        return nativeReadSamples(target, Math.min(length, target.length));
    }

    static void close() {
        if (LIBRARY_LOADED) nativeClose();
    }

    private static native String nativeStatusInternal();

    private static native boolean nativeIsCoreLinked();

    private static native boolean nativeOpenFromUsbFileDescriptor(int fileDescriptor);

    private static native boolean nativeTuneAdsb1090(int sampleRateHz);

    private static native int nativeReadSamples(byte[] target, int length);

    private static native void nativeClose();
}
