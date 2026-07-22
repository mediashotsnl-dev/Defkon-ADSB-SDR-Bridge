# GPL RTL-SDR Core Sources

This directory contains the native SDR sources used by the bridge.

Current structure:

- `libusb/`
- `rtl-sdr/`
- `readsb/`
- Android-specific file-descriptor open patch in `rtl-sdr/src/librtlsdr.c`.

This directory belongs only to the `bridge` APK. Do not copy these sources into
another application module unless that combined distribution satisfies the
applicable GPL requirements.

Target behavior for the native driver:

- Open the Android USB device through the permission-granted file descriptor.
- Initialize RTL2832U plus supported tuner, such as R820T/R820T2/R860.
- Set sample rate to `2,400,000` for the built-in readsb demodulator.
- Tune center frequency to `1,090,000,000 Hz`.
- Stream unsigned 8-bit IQ samples into `NativeRtlSdrDriver.nativeReadSamples`.
- Decode ADS-B through the native Bridge decoder with readsb demod_2400,
  Mode-S/CRC, Mode A-C, Comm-B, and CPR support when `READSB CORE` mode is
  selected.

When these sources are updated, update `bridge/NOTICE_ADSB_SDR.md` with exact
upstream repositories, commit hashes, license files, and local modifications.
