# Third-party notices

DEFKON ADSB SDR BRIDGE contains and links the following open-source software.
The complete vendored source used by this release is included under
`src/main/cpp/gpl/`.

## readsb

- Upstream: https://github.com/wiedehopf/readsb
- Vendored revision: `0bfd0473d0d6c9bd46dcc7091a323b945b165d15`
- License: GNU GPL version 3 or later, with incorporated GPLv2-or-later and
  BSD-style work as described by the upstream project
- License files: `src/main/cpp/gpl/readsb/COPYING` and
  `src/main/cpp/gpl/readsb/LICENSE`

The Bridge links selected readsb decoder sources into its native shared
library. Original copyright and BSD attribution notices are retained in the
vendored source and license files.

## rtl-sdr

- Upstream: https://gitea.osmocom.org/sdr/rtl-sdr.git
- Vendored revision: `84195f169f5b4b7dc06a10efb1e210d02b49e51c`
- License: GNU GPL version 2 or later
- License file: `src/main/cpp/gpl/rtl-sdr/COPYING`

Local modification: `rtlsdr_open_from_fd(...)` was added so an Android USB
permission file descriptor can be opened through `libusb_wrap_sys_device(...)`.

## libusb

- Upstream: https://github.com/libusb/libusb
- Vendored revision: `19afc23a53dbd92bb501ccea61bd43f05d8003a6`
- License: GNU LGPL version 2.1 or later; individual utility/example files may
  carry compatible licenses recorded in their SPDX headers
- License file: `src/main/cpp/gpl/libusb/COPYING`

## Optional external fallback driver

The Bridge can invoke the separately installed Android application
`marto.rtl_tcp_andro` through its public intent/localhost interface. That app is
not bundled into this program. Its upstream project is:
https://github.com/signalwareltd/rtl_tcp_andro-

## Combined-work license

Because GPL-covered readsb and rtl-sdr code is linked into the Bridge native
library, DEFKON ADSB SDR BRIDGE is distributed as a whole under GNU GPL version
3 or later. No additional restriction is imposed on recipients' GPL rights.
