# DEFKON ADSB SDR BRIDGE

Open-source Android app for receiving 1090 MHz ADS-B traffic with a compatible
RTL-SDR USB dongle. It decodes traffic locally and provides readsb-style JSON
and SBS/BaseStation feeds to compatible applications.

## Source for release 0.1.6

The complete source for Bridge 0.1.6 is at
[bridge-v0.1.6](https://github.com/mediashotsnl-dev/Defkon-ADSB-SDR-Bridge/tree/bridge-v0.1.6).
Use the source tag matching the version of the APK you received. The tag includes
the Android and native build files, local modifications, and vendored readsb,
rtl-sdr, and libusb source used by this release.

## Local interfaces

- `http://127.0.0.1:5051/aircraft.json`
- SBS/BaseStation TCP on `127.0.0.1:30003`

The Bridge is a separate Android application. Client applications consume only
the local data interfaces and do not need to include the GPL-covered decoder or
SDR driver code.

## License

DEFKON ADSB SDR BRIDGE is licensed under the GNU General Public License version
3 or later. See [LICENSE](LICENSE), [COPYRIGHT](COPYRIGHT), and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the Bridge license and
third-party attributions.

## Build

See [BUILDING.md](bridge/BUILDING.md). Release maintainers should also follow
[RELEASING.md](bridge/RELEASING.md).

## Security and privacy

See [SECURITY.md](bridge/SECURITY.md) and [PRIVACY.md](bridge/PRIVACY.md).
