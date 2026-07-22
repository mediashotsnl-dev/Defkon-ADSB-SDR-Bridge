# DEFKON ADSB SDR BRIDGE

Open-source Android helper app for local ADS-B reception.

## Current module state

This module is the bridge app:

- Detects likely RTL-SDR USB devices.
- Requests Android USB permission.
- Starts a foreground service with a persistent notification.
- The service hosts a readsb-style JSON feed on `127.0.0.1:5051/aircraft.json`.
- An SBS/BaseStation compatibility server remains available on port `30003`.
- Can send a test SBS target line for end-to-end client testing.
- Has an NDK/JNI entry point for a built-in RTL-SDR driver.
- Opens the Android USB file descriptor in the bridge service.
- Falls back to the existing Android RTL-SDR driver app through `iqsrc://` while the native GPL core is not linked.
- Requests fallback IQ on `127.0.0.1:14423`.
- Tunes the SDR route for ADS-B at `1090.000 MHz`.
- Decodes basic 1090 MHz Mode-S/ADS-B frames into SBS/BaseStation lines.

The intended production route is built-in USB SDR inside the bridge. The current
fallback route can use the separate Android RTL-SDR driver package
`marto.rtl_tcp_andro` for testing.

## GPL driver route

The built-in RTL-SDR driver core is a GPL route. Keep this bridge distributed
as a complete open-source component, include the bridge source, and provide the
required GPL notices and corresponding source for the driver route.

The Bridge as a combined work is licensed under GNU GPL version 3 or later.
Complete corresponding source for public releases is published at:

- https://github.com/mediashotsnl-dev/Defkon-ADSB-SDR-Bridge/tree/bridge-v0.1.0

See `LICENSE`, `COPYRIGHT`, `THIRD_PARTY_NOTICES.md`, `BUILDING.md`, and
`RELEASING.md`. The same license and source information is available from the
Bridge application's `OPEN SOURCE AND LICENSES` button.

Consumer applications read SBS/BaseStation data from localhost and do not need
to include SDR driver code.

## Data contract

Compatible clients can read the local readsb-style JSON feed:

- URL: `http://127.0.0.1:5051/aircraft.json`

The Bridge also writes SBS/BaseStation `MSG` lines for compatibility:

- Host: `127.0.0.1`
- Port: `30003`

Both localhost feeds keep client applications independent from SDR driver code.

The TCP server runs in `BridgeService`, so it can keep serving a foreground
client application.
