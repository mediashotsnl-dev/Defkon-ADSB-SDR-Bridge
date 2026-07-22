#include <jni.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <ctime>
#include <iomanip>
#include <mutex>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>

#include "readsb_bridge_adapter.h"

extern "C" {
#include "gpl/readsb/cpr.h"
}

namespace {
constexpr int MAG_TAIL_SIZE = 256;
constexpr int READSB_MAG_TAIL_SIZE = 512;
constexpr int READSB_MAG_PADDING = 512;
constexpr int READSB_MAX_MESSAGES_PER_BLOCK = 256;
constexpr int PREAMBLE_SAMPLES = 16;
constexpr int MESSAGE_BITS = 112;
constexpr int MESSAGE_BYTES = 14;
constexpr int MESSAGE_SAMPLES = MESSAGE_BITS * 2;
constexpr int DECODER_MODE_READSB_CORE = 2;
constexpr int64_t AIRCRAFT_STATE_TTL_MS = 10 * 60 * 1000;
constexpr int64_t AIRCRAFT_PRUNE_INTERVAL_MS = 30 * 1000;
constexpr size_t MAX_AIRCRAFT_STATES = 4096;

const std::array<int, 88> MODES_CHECKSUM_TABLE = {
    0x3935ea, 0x1c9af5, 0xf1b77e, 0x78dbbf, 0xc397db, 0x9e31e9, 0xb0e2f0, 0x587178,
    0x2c38bc, 0x161c5e, 0x0b0e2f, 0xfa7d13, 0x82c48d, 0xbe9842, 0x5f4c21, 0xd05c14,
    0x682e0a, 0x341705, 0xe5f186, 0x72f8c3, 0xc68665, 0x9cb936, 0x4e5c9b, 0xd8d449,
    0x939020, 0x49c810, 0x24e408, 0x127204, 0x093902, 0x049c81, 0xfdb444, 0x7eda22,
    0x3f6d11, 0xe04c8c, 0x702646, 0x381323, 0xe3f395, 0x8e03ce, 0x4701e7, 0xdc7af7,
    0x91c77f, 0xb719bb, 0xa476d9, 0xadc168, 0x56e0b4, 0x2b705a, 0x15b82d, 0xf52612,
    0x7a9309, 0xc2b380, 0x6159c0, 0x30ace0, 0x185670, 0x0c2b38, 0x06159c, 0x030ace,
    0x018567, 0xff38b7, 0x80665f, 0xbfc92b, 0xa01e91, 0xaff54c, 0x57faa6, 0x2bfd53,
    0xea04ad, 0x8af852, 0x457c29, 0xdd4410, 0x6ea208, 0x375104, 0x1ba882, 0x0dd441,
    0xf91024, 0x7c8812, 0x3e4409, 0xe0d800, 0x706c00, 0x383600, 0x1c1b00, 0x0e0d80,
    0x0706c0, 0x038360, 0x01c1b0, 0x00e0d8, 0x00706c, 0x003836, 0x001c1b, 0xfff409
};

struct AircraftState {
    std::string callsign;
    bool has_lat = false;
    bool has_lon = false;
    bool has_altitude = false;
    bool has_speed = false;
    bool has_track = false;
    bool has_even = false;
    bool has_odd = false;
    double lat = 0.0;
    double lon = 0.0;
    int altitude_ft = 0;
    int speed_kt = 0;
    int track_deg = 0;
    int even_lat = 0;
    int even_lon = 0;
    int odd_lat = 0;
    int odd_lon = 0;
    int64_t even_ms = 0;
    int64_t odd_ms = 0;
    int64_t last_seen_ms = 0;
};

struct DecoderState {
    std::vector<int> tail = std::vector<int>(MAG_TAIL_SIZE);
    int tail_size = 0;
    std::vector<int> mag;
    std::vector<uint16_t> readsb_tail = std::vector<uint16_t>(READSB_MAG_TAIL_SIZE);
    int readsb_tail_size = 0;
    std::vector<uint16_t> readsb_mag;
    int64_t readsb_sample_timestamp = 0;
    std::unordered_map<uint32_t, AircraftState> aircraft;
    bool has_pending_iq_byte = false;
    uint8_t pending_iq_byte = 0;
    int64_t last_aircraft_prune_ms = 0;
    int frames_seen = 0;
    int sbs_lines = 0;
    int64_t candidate_preambles = 0;
};

std::mutex decoder_mutex;
DecoderState decoder;

int64_t now_ms() {
    auto now = std::chrono::system_clock::now();
    return std::chrono::duration_cast<std::chrono::milliseconds>(now.time_since_epoch()).count();
}

void prune_aircraft_states(int64_t timestamp_ms) {
    if (decoder.aircraft.size() <= MAX_AIRCRAFT_STATES &&
        timestamp_ms - decoder.last_aircraft_prune_ms < AIRCRAFT_PRUNE_INTERVAL_MS) {
        return;
    }
    decoder.last_aircraft_prune_ms = timestamp_ms;

    for (auto iterator = decoder.aircraft.begin(); iterator != decoder.aircraft.end();) {
        if (timestamp_ms - iterator->second.last_seen_ms > AIRCRAFT_STATE_TTL_MS) {
            iterator = decoder.aircraft.erase(iterator);
        } else {
            ++iterator;
        }
    }
    while (decoder.aircraft.size() > MAX_AIRCRAFT_STATES) {
        auto oldest = decoder.aircraft.end();
        for (auto iterator = decoder.aircraft.begin(); iterator != decoder.aircraft.end(); ++iterator) {
            if (oldest == decoder.aircraft.end() || iterator->second.last_seen_ms < oldest->second.last_seen_ms) {
                oldest = iterator;
            }
        }
        if (oldest == decoder.aircraft.end()) break;
        decoder.aircraft.erase(oldest);
    }
}

bool looks_like_preamble(const std::vector<int> &mag, int pos) {
    int p0 = std::max(mag[pos], mag[pos + 1]);
    int p1 = std::max(mag[pos + 2], mag[pos + 3]);
    int p2 = std::max(mag[pos + 7], mag[pos + 8]);
    int p3 = std::max(mag[pos + 9], mag[pos + 10]);
    int quiet_sum =
        mag[pos + 4] + mag[pos + 5] + mag[pos + 6] +
        mag[pos + 11] + mag[pos + 12] + mag[pos + 13] + mag[pos + 14] + mag[pos + 15];
    int quiet_average = quiet_sum / 8;
    int quiet_max = std::max(
        std::max(std::max(mag[pos + 4], mag[pos + 5]), std::max(mag[pos + 6], mag[pos + 11])),
        std::max(std::max(mag[pos + 12], mag[pos + 13]), std::max(mag[pos + 14], mag[pos + 15]))
    );
    int peak_min = std::min(std::min(p0, p1), std::min(p2, p3));
    int peak_sum = p0 + p1 + p2 + p3;
    return peak_min * 3 > quiet_average * 5 &&
        peak_min * 4 > quiet_max * 5 &&
        peak_sum > quiet_sum;
}

bool valid_checksum(const std::array<uint8_t, MESSAGE_BYTES> &message) {
    int checksum = 0;
    for (int bit = 0; bit < 88; bit++) {
        int value = (message[bit / 8] >> (7 - (bit % 8))) & 1;
        if (value != 0) checksum ^= MODES_CHECKSUM_TABLE[bit];
    }
    int parity = (message[11] << 16) | (message[12] << 8) | message[13];
    return checksum == parity;
}

bool decode_message(
    const std::vector<int> &mag,
    int start,
    int mag_length,
    std::array<uint8_t, MESSAGE_BYTES> &message,
    bool readsb_core
) {
    if (start + MESSAGE_SAMPLES > mag_length) return false;
    message.fill(0);
    for (int bit = 0; bit < MESSAGE_BITS; bit++) {
        int first = mag[start + bit * 2];
        int second = mag[start + bit * 2 + 1];
        int signal = first + second;
        int diff = first - second;
        if (std::abs(diff) * 16 < signal) return false;
        if (diff > 0) {
            message[bit / 8] |= static_cast<uint8_t>(1 << (7 - (bit % 8)));
        }
    }
    int df = (message[0] & 0xff) >> 3;
    if (df != 17 && df != 18) return false;
    return readsb_core || valid_checksum(message);
}

int get_bits(const std::array<uint8_t, MESSAGE_BYTES> &message, int start, int length) {
    int value = 0;
    for (int i = 0; i < length; i++) {
        int bit_index = start + i;
        int bit = (message[bit_index / 8] >> (7 - (bit_index % 8))) & 1;
        value = (value << 1) | bit;
    }
    return value;
}

int pos_mod(int value, int divisor) {
    int result = value % divisor;
    return result < 0 ? result + divisor : result;
}

int cpr_nl(double lat) {
    double a = std::abs(lat);
    if (a < 10.47047130) return 59;
    if (a < 14.82817437) return 58;
    if (a < 18.18626357) return 57;
    if (a < 21.02939493) return 56;
    if (a < 23.54504487) return 55;
    if (a < 25.82924707) return 54;
    if (a < 27.93898710) return 53;
    if (a < 29.91135686) return 52;
    if (a < 31.77209708) return 51;
    if (a < 33.53993436) return 50;
    if (a < 35.22899598) return 49;
    if (a < 36.85025108) return 48;
    if (a < 38.41241892) return 47;
    if (a < 39.92256684) return 46;
    if (a < 41.38651832) return 45;
    if (a < 42.80914012) return 44;
    if (a < 44.19454951) return 43;
    if (a < 45.54626723) return 42;
    if (a < 46.86733252) return 41;
    if (a < 48.16039128) return 40;
    if (a < 49.42776439) return 39;
    if (a < 50.67150166) return 38;
    if (a < 51.89342469) return 37;
    if (a < 53.09516153) return 36;
    if (a < 54.27817472) return 35;
    if (a < 55.44378444) return 34;
    if (a < 56.59318756) return 33;
    if (a < 57.72747354) return 32;
    if (a < 58.84763776) return 31;
    if (a < 59.95459277) return 30;
    if (a < 61.04917774) return 29;
    if (a < 62.13216659) return 28;
    if (a < 63.20427479) return 27;
    if (a < 64.26616523) return 26;
    if (a < 65.31845310) return 25;
    if (a < 66.36171008) return 24;
    if (a < 67.39646774) return 23;
    if (a < 68.42322022) return 22;
    if (a < 69.44242631) return 21;
    if (a < 70.45451075) return 20;
    if (a < 71.45986473) return 19;
    if (a < 72.45884545) return 18;
    if (a < 73.45177442) return 17;
    if (a < 74.43893416) return 16;
    if (a < 75.42056257) return 15;
    if (a < 76.39684391) return 14;
    if (a < 77.36789461) return 13;
    if (a < 78.33374083) return 12;
    if (a < 79.29428225) return 11;
    if (a < 80.24923213) return 10;
    if (a < 81.19801349) return 9;
    if (a < 82.13956981) return 8;
    if (a < 83.07199445) return 7;
    if (a < 83.99173563) return 6;
    if (a < 84.89166191) return 5;
    if (a < 85.75541621) return 4;
    if (a < 86.53536998) return 3;
    if (a < 87.00000000) return 2;
    return 1;
}

std::string decode_callsign(const std::array<uint8_t, MESSAGE_BYTES> &message) {
    static const std::string table = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ#####_###############0123456789######";
    std::string callsign;
    for (int i = 0; i < 8; i++) {
        int value = get_bits(message, 40 + i * 6, 6);
        char c = value < static_cast<int>(table.size()) ? table[value] : ' ';
        if (c != '#' && c != '_') callsign.push_back(c);
    }
    while (!callsign.empty() && callsign.back() == ' ') callsign.pop_back();
    return callsign;
}

bool decode_altitude_ft(int code, int &altitude_ft) {
    if (code == 0) return false;
    int q_bit = (code >> 4) & 1;
    if (q_bit == 0) return false;
    int n = ((code & 0x0fe0) >> 1) | (code & 0x000f);
    altitude_ft = n * 25 - 1000;
    return true;
}

void decode_velocity(const std::array<uint8_t, MESSAGE_BYTES> &message, AircraftState &state) {
    int subtype = get_bits(message, 37, 3);
    if (subtype != 1 && subtype != 2) return;
    int ew_sign = get_bits(message, 45, 1);
    int ew_velocity = get_bits(message, 46, 10) - 1;
    int ns_sign = get_bits(message, 56, 1);
    int ns_velocity = get_bits(message, 57, 10) - 1;
    if (ew_velocity < 0 || ns_velocity < 0) return;
    double east = ew_sign == 1 ? -ew_velocity : ew_velocity;
    double north = ns_sign == 1 ? -ns_velocity : ns_velocity;
    state.speed_kt = static_cast<int>(std::round(std::sqrt(east * east + north * north)));
    double track = std::atan2(east, north) * 180.0 / M_PI;
    if (track < 0) track += 360.0;
    state.track_deg = static_cast<int>(std::round(track)) % 360;
    state.has_speed = true;
    state.has_track = true;
}

bool decode_global_position(AircraftState &state) {
    if (!state.has_even || !state.has_odd) return false;
    if (std::llabs(state.even_ms - state.odd_ms) > 10000) return false;
    double even_lat = state.even_lat / 131072.0;
    double odd_lat = state.odd_lat / 131072.0;
    int j = static_cast<int>(std::floor((59 * even_lat) - (60 * odd_lat) + 0.5));
    double rlat_even = 6.0 * (pos_mod(j, 60) + even_lat);
    double rlat_odd = (360.0 / 59.0) * (pos_mod(j, 59) + odd_lat);
    if (rlat_even >= 270.0) rlat_even -= 360.0;
    if (rlat_odd >= 270.0) rlat_odd -= 360.0;
    if (cpr_nl(rlat_even) != cpr_nl(rlat_odd)) return false;

    bool use_odd = state.odd_ms > state.even_ms;
    double lat = use_odd ? rlat_odd : rlat_even;
    int nl = cpr_nl(lat) - (use_odd ? 1 : 0);
    if (nl < 1) nl = 1;

    double even_lon = state.even_lon / 131072.0;
    double odd_lon = state.odd_lon / 131072.0;
    int m = static_cast<int>(std::floor((even_lon * (cpr_nl(lat) - 1)) - (odd_lon * cpr_nl(lat)) + 0.5));
    double lon = (360.0 / nl) * (pos_mod(m, nl) + (use_odd ? odd_lon : even_lon));
    if (lon > 180.0) lon -= 360.0;

    state.lat = lat;
    state.lon = lon;
    state.has_lat = true;
    state.has_lon = true;
    return true;
}

bool decode_readsb_global_position(AircraftState &state) {
    if (!state.has_even || !state.has_odd) return false;
    if (std::llabs(state.even_ms - state.odd_ms) > 10000) return false;

    bool use_odd = state.odd_ms > state.even_ms;
    double lat = 0.0;
    double lon = 0.0;
    int result = decodeCPRairborne(
        state.even_lat,
        state.even_lon,
        state.odd_lat,
        state.odd_lon,
        use_odd ? 1 : 0,
        &lat,
        &lon
    );
    if (result != 0) return false;

    state.lat = lat;
    state.lon = lon;
    state.has_lat = true;
    state.has_lon = true;
    return true;
}

void utc_date_time(int64_t timestamp_ms, std::string &date, std::string &time) {
    std::time_t seconds = static_cast<std::time_t>(timestamp_ms / 1000);
    int millis = static_cast<int>(timestamp_ms % 1000);
    std::tm tm{};
    gmtime_r(&seconds, &tm);
    std::ostringstream date_stream;
    date_stream << std::put_time(&tm, "%Y/%m/%d");
    date = date_stream.str();
    std::ostringstream time_stream;
    time_stream << std::put_time(&tm, "%H:%M:%S") << "." << std::setw(3) << std::setfill('0') << millis;
    time = time_stream.str();
}

std::string hex24(uint32_t value) {
    std::ostringstream out;
    out << std::uppercase << std::hex << std::setw(6) << std::setfill('0') << (value & 0xffffff);
    return out.str();
}

std::string to_sbs_line(uint32_t hex, const AircraftState &state, int transmission_type, bool include_position) {
    if (transmission_type == 1 && state.callsign.empty()) return "";
    if (transmission_type == 3 && (!include_position || !state.has_lat || !state.has_lon)) return "";
    if (transmission_type == 4 && (!state.has_speed || !state.has_track)) return "";

    std::string date;
    std::string time;
    utc_date_time(state.last_seen_ms > 0 ? state.last_seen_ms : now_ms(), date, time);

    std::ostringstream out;
    out << "MSG," << transmission_type << ",1,1," << hex24(hex) << ",1,"
        << date << "," << time << "," << date << "," << time << ","
        << state.callsign << ",";
    if (state.has_altitude) out << state.altitude_ft;
    out << ",";
    if (state.has_speed) out << state.speed_kt;
    out << ",";
    if (state.has_track) out << state.track_deg;
    out << ",";
    if (include_position && state.has_lat) out << std::fixed << std::setprecision(5) << state.lat;
    out << ",";
    if (include_position && state.has_lon) out << std::fixed << std::setprecision(5) << state.lon;
    out << ",0,0,0,0,0,0";
    return out.str();
}

void handle_message(const std::array<uint8_t, MESSAGE_BYTES> &message, bool readsb_core, std::ostringstream &lines) {
    if (readsb_core) {
        ReadsbBridgeMessage decoded{};
        if (!readsb_bridge_decode(message.data(), &decoded)) return;
        if (decoded.addr == 0) return;

        int64_t timestamp_ms = now_ms();
        prune_aircraft_states(timestamp_ms);
        AircraftState &state = decoder.aircraft[decoded.addr & 0xffffff];
        state.last_seen_ms = timestamp_ms;
        decoder.frames_seen += 1;

        std::string line;
        if (decoded.has_callsign) {
            state.callsign = decoded.callsign;
            while (!state.callsign.empty() && state.callsign.back() == ' ') state.callsign.pop_back();
            line = to_sbs_line(decoded.addr, state, 1, false);
        }
        if (decoded.has_altitude) {
            state.altitude_ft = decoded.altitude_ft;
            state.has_altitude = true;
        }
        if (decoded.has_velocity) {
            state.speed_kt = decoded.speed_kt;
            state.track_deg = decoded.track_deg;
            state.has_speed = true;
            state.has_track = true;
            line = to_sbs_line(decoded.addr, state, 4, false);
        }
        if (decoded.has_cpr) {
            if (decoded.cpr_odd) {
                state.odd_lat = decoded.cpr_lat;
                state.odd_lon = decoded.cpr_lon;
                state.odd_ms = state.last_seen_ms;
                state.has_odd = true;
            } else {
                state.even_lat = decoded.cpr_lat;
                state.even_lon = decoded.cpr_lon;
                state.even_ms = state.last_seen_ms;
                state.has_even = true;
            }
            if (decode_readsb_global_position(state)) {
                line = to_sbs_line(decoded.addr, state, 3, true);
            }
        }

        if (!line.empty()) {
            decoder.sbs_lines += 1;
            lines << line << '\n';
        }
        return;
    }

    uint32_t hex = (static_cast<uint32_t>(message[1]) << 16) |
        (static_cast<uint32_t>(message[2]) << 8) |
        static_cast<uint32_t>(message[3]);
    int type_code = get_bits(message, 32, 5);
    if (type_code <= 0 || type_code > 31) return;

    int64_t timestamp_ms = now_ms();
    prune_aircraft_states(timestamp_ms);
    AircraftState &state = decoder.aircraft[hex];
    state.last_seen_ms = timestamp_ms;
    decoder.frames_seen += 1;

    std::string line;
    if (type_code >= 1 && type_code <= 4) {
        state.callsign = decode_callsign(message);
        line = to_sbs_line(hex, state, 1, false);
    } else if (type_code >= 9 && type_code <= 18) {
        int altitude = 0;
        if (decode_altitude_ft(get_bits(message, 40, 12), altitude)) {
            state.altitude_ft = altitude;
            state.has_altitude = true;
        }
        bool odd = get_bits(message, 53, 1) == 1;
        if (odd) {
            state.odd_lat = get_bits(message, 54, 17);
            state.odd_lon = get_bits(message, 71, 17);
            state.odd_ms = state.last_seen_ms;
            state.has_odd = true;
        } else {
            state.even_lat = get_bits(message, 54, 17);
            state.even_lon = get_bits(message, 71, 17);
            state.even_ms = state.last_seen_ms;
            state.has_even = true;
        }
        bool position_ready = readsb_core
            ? decode_readsb_global_position(state)
            : decode_global_position(state);
        if (position_ready) {
            line = to_sbs_line(hex, state, 3, true);
        }
    } else if (type_code == 19) {
        decode_velocity(message, state);
        line = to_sbs_line(hex, state, 4, false);
    }

    if (!line.empty()) {
        decoder.sbs_lines += 1;
        lines << line << '\n';
    }
}

void handle_readsb_decoded_message(const ReadsbBridgeMessage &decoded, std::ostringstream &lines) {
    if (decoded.addr == 0) return;

    int64_t timestamp_ms = now_ms();
    prune_aircraft_states(timestamp_ms);
    AircraftState &state = decoder.aircraft[decoded.addr & 0xffffff];
    state.last_seen_ms = timestamp_ms;
    decoder.frames_seen += 1;

    std::string line;
    if (decoded.has_callsign) {
        state.callsign = decoded.callsign;
        while (!state.callsign.empty() && state.callsign.back() == ' ') state.callsign.pop_back();
        line = to_sbs_line(decoded.addr, state, 1, false);
    }
    if (decoded.has_altitude) {
        state.altitude_ft = decoded.altitude_ft;
        state.has_altitude = true;
    }
    if (decoded.has_velocity) {
        state.speed_kt = decoded.speed_kt;
        state.track_deg = decoded.track_deg;
        state.has_speed = true;
        state.has_track = true;
        line = to_sbs_line(decoded.addr, state, 4, false);
    }
    if (decoded.has_cpr) {
        if (decoded.cpr_odd) {
            state.odd_lat = decoded.cpr_lat;
            state.odd_lon = decoded.cpr_lon;
            state.odd_ms = state.last_seen_ms;
            state.has_odd = true;
        } else {
            state.even_lat = decoded.cpr_lat;
            state.even_lon = decoded.cpr_lon;
            state.even_ms = state.last_seen_ms;
            state.has_even = true;
        }
        if (decode_readsb_global_position(state)) {
            line = to_sbs_line(decoded.addr, state, 3, true);
        }
    }

    if (!line.empty()) {
        decoder.sbs_lines += 1;
        lines << line << '\n';
    }
}

void process_readsb_demodulator(jbyte *iq, jint length, std::ostringstream &lines) {
    int pairs = (static_cast<int>(length) + (decoder.has_pending_iq_byte ? 1 : 0)) / 2;
    if (pairs <= 0) {
        if (length == 1 && !decoder.has_pending_iq_byte) {
            decoder.pending_iq_byte = static_cast<uint8_t>(iq[0]);
            decoder.has_pending_iq_byte = true;
        }
        return;
    }

    int mag_length = decoder.readsb_tail_size + pairs;
    int padded_length = mag_length + READSB_MAG_PADDING;
    if (static_cast<int>(decoder.readsb_mag.size()) < padded_length) {
        decoder.readsb_mag.resize(padded_length);
    }

    std::copy(
        decoder.readsb_tail.begin(),
        decoder.readsb_tail.begin() + decoder.readsb_tail_size,
        decoder.readsb_mag.begin()
    );

    const double scale = 364.0;
    auto write_magnitude = [&](int output_index, uint8_t i_value, uint8_t q_value) {
        int i_sample = static_cast<int>(i_value) - 127;
        int q_sample = static_cast<int>(q_value) - 127;
        int power = i_sample * i_sample + q_sample * q_sample;
        int magnitude = static_cast<int>(std::sqrt(static_cast<double>(power)) * scale);
        decoder.readsb_mag[decoder.readsb_tail_size + output_index] =
            static_cast<uint16_t>(std::min(65535, std::max(0, magnitude)));
    };

    int input_index = 0;
    int pair_index = 0;
    if (decoder.has_pending_iq_byte && length > 0) {
        write_magnitude(pair_index++, decoder.pending_iq_byte, static_cast<uint8_t>(iq[input_index++]));
        decoder.has_pending_iq_byte = false;
    }
    while (input_index + 1 < length) {
        write_magnitude(
            pair_index++,
            static_cast<uint8_t>(iq[input_index]),
            static_cast<uint8_t>(iq[input_index + 1])
        );
        input_index += 2;
    }
    if (input_index < length) {
        decoder.pending_iq_byte = static_cast<uint8_t>(iq[input_index]);
        decoder.has_pending_iq_byte = true;
    }
    std::fill(
        decoder.readsb_mag.begin() + mag_length,
        decoder.readsb_mag.begin() + padded_length,
        0
    );

    std::array<ReadsbBridgeMessage, READSB_MAX_MESSAGES_PER_BLOCK> decoded{};
    int decoded_count = readsb_bridge_demodulate_2400(
        decoder.readsb_mag.data(),
        mag_length,
        decoder.readsb_sample_timestamp,
        now_ms(),
        decoded.data(),
        static_cast<int>(decoded.size())
    );
    for (int i = 0; i < decoded_count; i++) {
        handle_readsb_decoded_message(decoded[i], lines);
    }

    decoder.readsb_sample_timestamp += static_cast<int64_t>(pairs) * 5;
    decoder.readsb_tail_size = std::min(READSB_MAG_TAIL_SIZE, mag_length);
    std::copy(
        decoder.readsb_mag.begin() + (mag_length - decoder.readsb_tail_size),
        decoder.readsb_mag.begin() + mag_length,
        decoder.readsb_tail.begin()
    );
}
}

extern "C" JNIEXPORT void JNICALL
Java_com_mediashots_defkonadsbbridge_NativeModeSDecoder_nativeReset(
    JNIEnv *,
    jclass
) {
    std::lock_guard<std::mutex> lock(decoder_mutex);
    readsb_bridge_init();
    decoder = DecoderState();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mediashots_defkonadsbbridge_NativeModeSDecoder_nativeProcessIq(
    JNIEnv *env,
    jclass,
    jbyteArray iq_array,
    jint length,
    jint decoder_mode
) {
    if (iq_array == nullptr || length <= 0) {
        return env->NewStringUTF("");
    }

    jbyte *iq = env->GetByteArrayElements(iq_array, nullptr);
    if (iq == nullptr) {
        return env->NewStringUTF("");
    }

    std::ostringstream lines;
    {
        std::lock_guard<std::mutex> lock(decoder_mutex);
        bool readsb_core = decoder_mode == DECODER_MODE_READSB_CORE;
        if (readsb_core) {
            process_readsb_demodulator(iq, length, lines);
        } else {
            int pairs = (static_cast<int>(length) + (decoder.has_pending_iq_byte ? 1 : 0)) / 2;
            if (pairs > 0) {
                int mag_length = decoder.tail_size + pairs;
                if (static_cast<int>(decoder.mag.size()) < mag_length) {
                    decoder.mag.resize(mag_length);
                }
                std::copy(decoder.tail.begin(), decoder.tail.begin() + decoder.tail_size, decoder.mag.begin());
                auto write_magnitude = [&](int output_index, uint8_t i_value, uint8_t q_value) {
                    int i_sample = static_cast<int>(i_value) - 127;
                    int q_sample = static_cast<int>(q_value) - 127;
                    decoder.mag[decoder.tail_size + output_index] = i_sample * i_sample + q_sample * q_sample;
                };
                int input_index = 0;
                int pair_index = 0;
                if (decoder.has_pending_iq_byte && length > 0) {
                    write_magnitude(pair_index++, decoder.pending_iq_byte, static_cast<uint8_t>(iq[input_index++]));
                    decoder.has_pending_iq_byte = false;
                }
                while (input_index + 1 < length) {
                    write_magnitude(
                        pair_index++,
                        static_cast<uint8_t>(iq[input_index]),
                        static_cast<uint8_t>(iq[input_index + 1])
                    );
                    input_index += 2;
                }
                if (input_index < length) {
                    decoder.pending_iq_byte = static_cast<uint8_t>(iq[input_index]);
                    decoder.has_pending_iq_byte = true;
                }

                int scan_limit = mag_length - PREAMBLE_SAMPLES - MESSAGE_SAMPLES;
                std::array<uint8_t, MESSAGE_BYTES> message{};
                for (int pos = 0; pos < scan_limit; pos++) {
                    if (looks_like_preamble(decoder.mag, pos)) {
                        decoder.candidate_preambles += 1;
                        if (decode_message(decoder.mag, pos + PREAMBLE_SAMPLES, mag_length, message, readsb_core)) {
                            handle_message(message, readsb_core, lines);
                            pos += PREAMBLE_SAMPLES + MESSAGE_SAMPLES - 1;
                        }
                    }
                }

                decoder.tail_size = std::min(MAG_TAIL_SIZE, mag_length);
                std::copy(
                    decoder.mag.begin() + (mag_length - decoder.tail_size),
                    decoder.mag.begin() + mag_length,
                    decoder.tail.begin()
                );
            } else if (length == 1 && !decoder.has_pending_iq_byte) {
                decoder.pending_iq_byte = static_cast<uint8_t>(iq[0]);
                decoder.has_pending_iq_byte = true;
            }
        }
    }

    env->ReleaseByteArrayElements(iq_array, iq, JNI_ABORT);
    return env->NewStringUTF(lines.str().c_str());
}
