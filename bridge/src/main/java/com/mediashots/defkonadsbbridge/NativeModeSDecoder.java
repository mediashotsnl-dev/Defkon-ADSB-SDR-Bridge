package com.mediashots.defkonadsbbridge;

final class NativeModeSDecoder {
    private NativeModeSDecoder() {
    }

    static boolean isAvailable() {
        return NativeRtlSdrDriver.isLibraryLoaded();
    }

    static void reset() {
        if (isAvailable()) nativeReset();
    }

    static String processIq(byte[] iq, int length, int decoderMode) {
        if (!isAvailable() || iq == null || length <= 0) return "";
        return nativeProcessIq(iq, Math.min(length, iq.length), decoderMode);
    }

    private static native void nativeReset();

    private static native String nativeProcessIq(byte[] iq, int length, int decoderMode);
}
