# DEFKON ADSB SDR BRIDGE - SDR Driver Notice

This bridge is structured for a built-in RTL-SDR driver core and can also start
and consume IQ data from the Android RTL-SDR driver package as a fallback route:

- Package: `marto.rtl_tcp_andro`
- Protocol: `iqsrc://` / rtl_tcp-compatible localhost IQ stream
- Requested tuning: `1090.000 MHz`
- Built-in readsb-core sample rate: `2,400,000 sps`
- External fallback driver sample rate: `2,000,000 sps`

The built-in RTL-SDR driver route is GPL-covered once `librtlsdr`/`libusb` sources
are linked into the bridge. The bridge must be handled as a separate
open-source/GPL-compatible component in distribution. Consumer applications
remain separate and consume traffic data through the localhost JSON/SBS
interfaces.

Relevant upstream project:

- https://github.com/signalwareltd/rtl_tcp_andro-
- https://gitea.osmocom.org/sdr/rtl-sdr.git
- https://github.com/libusb/libusb.git
- https://github.com/wiedehopf/readsb.git

Vendored source revisions currently present in `bridge/src/main/cpp/gpl/`:

- `rtl-sdr`: `84195f169f5b4b7dc06a10efb1e210d02b49e51c`
- `libusb`: `19afc23a53dbd92bb501ccea61bd43f05d8003a6`
- `readsb`: `0bfd0473d0d6c9bd46dcc7091a323b945b165d15`

Local bridge-specific RTL-SDR modification:

- Added `rtlsdr_open_from_fd(...)` so Android USB permission file descriptors can
  be opened with `libusb_wrap_sys_device(...)`.

The bridge-side Mode-S/ADS-B decoder has a selectable `READSB CORE` mode. In
that mode, the native Bridge decoder links readsb demod_2400, Mode-S, CRC,
Mode A-C, Comm-B, and CPR source files from the vendored `readsb/` source tree
through a small Android adapter. Keep the readsb GPLv3-or-later license files and
the upstream dump1090 BSD-style attribution in the bridge source distribution
when publishing this app.
