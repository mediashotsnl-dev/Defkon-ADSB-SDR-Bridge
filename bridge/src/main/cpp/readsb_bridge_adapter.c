#include "readsb_bridge_adapter.h"

#include <math.h>
#include <string.h>
#include <sys/time.h>

#include "gpl/readsb/readsb.h"

struct _Modes Modes;
struct _Threads Threads;

static int initialized;
static uint32_t seen_icao[4096];
static int seen_icao_next;
static struct modesMessage demod_message;
static ReadsbBridgeMessage *demod_out;
static int demod_out_count;
static int demod_out_max;

void setExit(int arg) {
    Modes.exit = arg;
}

int priorityTasksPending() {
    return 0;
}

void priorityTasksRun() {
}

char *sprint_uuid1(uint64_t id1, char *p) {
    sprintf(p, "%016llx", (unsigned long long) id1);
    return p + strlen(p);
}

void printACASInfoShort(uint32_t addr, unsigned char *MV, struct aircraft *a, struct modesMessage *mm, int64_t now) {
    (void) addr;
    (void) MV;
    (void) a;
    (void) mm;
    (void) now;
}

int64_t mstime(void) {
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return (int64_t) tv.tv_sec * 1000 + tv.tv_usec / 1000;
}

int64_t receiveclock_ns_elapsed(int64_t t1, int64_t t2) {
    return ((t2 - t1) * 1000) / 12;
}

int64_t receiveclock_ms_elapsed(int64_t t1, int64_t t2) {
    return (t2 - t1) / 12000;
}

void icaoFilterInit() {
    memset(seen_icao, 0, sizeof(seen_icao));
    seen_icao_next = 0;
}

void icaoFilterDestroy() {
}

void icaoFilterExpire() {
}

void icaoFilterAdd(uint32_t addr) {
    if (addr == 0 || addr == HEX_UNKNOWN) return;
    for (int i = 0; i < (int) (sizeof(seen_icao) / sizeof(seen_icao[0])); i++) {
        if (seen_icao[i] == addr) return;
    }
    seen_icao[seen_icao_next] = addr;
    seen_icao_next = (seen_icao_next + 1) & 4095;
}

int icaoFilterTest(uint32_t addr) {
    if (addr == 0 || addr == HEX_UNKNOWN) return 0;
    for (int i = 0; i < (int) (sizeof(seen_icao) / sizeof(seen_icao[0])); i++) {
        if (seen_icao[i] == addr) return 1;
    }
    return 0;
}

uint32_t icaoFilterTestFuzzy(uint32_t partial) {
    for (int i = 0; i < (int) (sizeof(seen_icao) / sizeof(seen_icao[0])); i++) {
        uint32_t addr = seen_icao[i];
        if (addr != 0 && (addr & 0xffff) == (partial & 0xffff)) return addr;
    }
    return 0;
}

void readsb_bridge_init(void) {
    if (initialized) return;
    initialized = 1;
    memset(&Modes, 0, sizeof(Modes));
    memset(&Threads, 0, sizeof(Threads));
    Modes.nfix_crc = 2;
    Modes.fixDF = 1;
    Modes.check_crc = 1;
    Modes.decode_all = 0;
    Modes.net_verbatim = 0;
    Modes.preambleThreshold = PREAMBLE_THRESHOLD_DEFAULT;
    Modes.sample_rate = 2400000.0;
    Modes.loudThreshold = 180;
    Modes.noiseLowThreshold = 20;
    Modes.noiseHighThreshold = 80;
    modeACInit();
    modesChecksumInit(Modes.nfix_crc);
    icaoFilterInit();
}

static int rounded_heading(float heading) {
    int value = (int) lroundf(heading);
    value %= 360;
    return value < 0 ? value + 360 : value;
}

static int fill_bridge_message(const struct modesMessage *mm, ReadsbBridgeMessage *out) {
    if (mm == NULL || out == NULL) return 0;
    if (mm->addr == HEX_UNKNOWN && mm->AA == 0) return 0;

    memset(out, 0, sizeof(*out));
    out->addr = mm->addr != HEX_UNKNOWN ? mm->addr : mm->AA;
    out->msgtype = mm->msgtype;
    out->metype = mm->metype;
    out->correctedbits = mm->correctedbits;

    if (mm->callsign_valid) {
        out->has_callsign = 1;
        strncpy(out->callsign, mm->callsign, sizeof(out->callsign) - 1);
    }

    if (mm->baro_alt_valid && mm->baro_alt_unit == UNIT_FEET) {
        out->has_altitude = 1;
        out->altitude_ft = mm->baro_alt;
    }

    if (mm->gs_valid && mm->heading_valid) {
        out->has_velocity = 1;
        out->speed_kt = (int) lroundf(mm->gs.selected);
        out->track_deg = rounded_heading(mm->heading);
    }

    if (mm->cpr_valid && mm->cpr_type == CPR_AIRBORNE) {
        out->has_cpr = 1;
        out->cpr_odd = mm->cpr_odd ? 1 : 0;
        out->cpr_lat = (int) mm->cpr_lat;
        out->cpr_lon = (int) mm->cpr_lon;
    }

    return 1;
}

int readsb_bridge_decode(const uint8_t *message, ReadsbBridgeMessage *out) {
    if (message == NULL || out == NULL) return 0;
    readsb_bridge_init();

    struct modesMessage mm;
    memset(&mm, 0, sizeof(mm));
    memcpy(mm.msg, message, MODES_LONG_MSG_BYTES);
    mm.signalLevel = 1.0;
    mm.sysTimestamp = mstime();

    int score = scoreModesMessage(mm.msg, MODES_LONG_MSG_BITS);
    if (score <= 0) return 0;
    mm.score = score;

    int result = decodeModesMessage(&mm);
    if (result < 0) return 0;
    return fill_bridge_message(&mm, out);
}

struct modesMessage *netGetMM(struct messageBuffer *buf) {
    (void) buf;
    memset(&demod_message, 0, sizeof(demod_message));
    return &demod_message;
}

void netUseMessage(struct modesMessage *mm) {
    if (demod_out == NULL || demod_out_count >= demod_out_max) return;
    if (fill_bridge_message(mm, &demod_out[demod_out_count])) {
        demod_out_count++;
    }
}

void netDrainMessageBuffers() {
}

int readsb_bridge_demodulate_2400(
    const uint16_t *magnitude,
    int length,
    int64_t sample_timestamp,
    int64_t system_timestamp_ms,
    ReadsbBridgeMessage *out,
    int max_out
) {
    if (magnitude == NULL || length <= 0 || out == NULL || max_out <= 0) return 0;
    readsb_bridge_init();

    struct mag_buf mag;
    memset(&mag, 0, sizeof(mag));
    mag.data = (uint16_t *) magnitude;
    mag.length = (unsigned) length;
    mag.sampleTimestamp = sample_timestamp;
    mag.sysTimestamp = system_timestamp_ms;
    mag.sysMicroseconds = system_timestamp_ms * 1000;

    double sum_power = 0.0;
    for (int i = 0; i < length; i++) {
        double normalized = magnitude[i] / 65535.0;
        sum_power += normalized * normalized;
    }
    mag.mean_power = sum_power / length;

    demod_out = out;
    demod_out_count = 0;
    demod_out_max = max_out;
    demodulate2400(&mag);
    demod_out = NULL;
    demod_out_max = 0;
    return demod_out_count;
}
