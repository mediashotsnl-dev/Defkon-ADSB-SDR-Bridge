#ifndef DEFKON_READSB_BRIDGE_ADAPTER_H
#define DEFKON_READSB_BRIDGE_ADAPTER_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ReadsbBridgeMessage {
    uint32_t addr;
    int msgtype;
    int metype;
    int correctedbits;
    int has_callsign;
    char callsign[16];
    int has_altitude;
    int altitude_ft;
    int has_velocity;
    int speed_kt;
    int track_deg;
    int has_cpr;
    int cpr_odd;
    int cpr_lat;
    int cpr_lon;
} ReadsbBridgeMessage;

void readsb_bridge_init(void);
int readsb_bridge_decode(const uint8_t *message, ReadsbBridgeMessage *out);
int readsb_bridge_demodulate_2400(
    const uint16_t *magnitude,
    int length,
    int64_t sample_timestamp,
    int64_t system_timestamp_ms,
    ReadsbBridgeMessage *out,
    int max_out
);

#ifdef __cplusplus
}
#endif

#endif
