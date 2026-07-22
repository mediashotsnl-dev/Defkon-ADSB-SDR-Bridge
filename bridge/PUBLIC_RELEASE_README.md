# DEFKON ADSB SDR BRIDGE

Open-source Android USB bridge for receiving 1090 MHz ADS-B traffic with a
compatible RTL-SDR dongle and exposing local readsb-style JSON and
SBS/BaseStation feeds to compatible local client applications.

## Local interfaces

- `http://127.0.0.1:5051/aircraft.json`
- SBS/BaseStation TCP on `127.0.0.1:30003`

The Bridge is a separate Android application. Client applications consume only
the local data interfaces and do not need to include the GPL-covered decoder or
SDR driver code.

## License

DEFKON ADSB SDR BRIDGE is licensed under the GNU General Public License version
3 or later. See `LICENSE`, `COPYRIGHT`, and `THIRD_PARTY_NOTICES.md`.

Complete corresponding source, including the vendored readsb, rtl-sdr and
libusb revisions used by the native library, is included in this repository.

## Build

See [BUILDING.md](bridge/BUILDING.md). Release maintainers should also follow
[RELEASING.md](bridge/RELEASING.md).

## Security and privacy

See [SECURITY.md](bridge/SECURITY.md) and [PRIVACY.md](bridge/PRIVACY.md).
