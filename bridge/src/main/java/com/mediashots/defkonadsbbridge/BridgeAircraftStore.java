package com.mediashots.defkonadsbbridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class BridgeAircraftStore {
    private static final long POSITION_HOLD_MS = 45_000L;
    private static final long AIRCRAFT_TTL_MS = 60_000L;
    private static final double JUMP_GRACE_KM = 0.35;
    private static final double JUMP_SPEED_FACTOR = 2.8;

    private final Map<String, Aircraft> aircraftByHex = new LinkedHashMap<>();
    private long totalMessages;
    private long totalPositions;
    private long rejectedJumps;
    private long lastMessageMs;
    private long lastPositionMs;

    synchronized void clear() {
        aircraftByHex.clear();
        totalMessages = 0L;
        totalPositions = 0L;
        rejectedJumps = 0L;
        lastMessageMs = 0L;
        lastPositionMs = 0L;
    }

    synchronized void mergeSbsLine(String line, long nowMs) {
        SbsUpdate update = SbsUpdate.parse(line);
        if (update == null) return;

        Aircraft aircraft = aircraftByHex.get(update.hex);
        if (aircraft == null) {
            aircraft = new Aircraft(update.hex);
            aircraftByHex.put(update.hex, aircraft);
        }

        aircraft.lastMessageTimeMs = nowMs;
        aircraft.messageCount += 1;
        totalMessages += 1;
        lastMessageMs = nowMs;

        if (update.flight != null) aircraft.flight = update.flight;
        if (update.altitudeFt != null) aircraft.altitudeFt = update.altitudeFt;
        if (update.groundSpeedKt != null) aircraft.groundSpeedKt = update.groundSpeedKt;
        if (update.trackDeg != null) aircraft.trackDeg = update.trackDeg;

        if (update.lat != null && update.lon != null && isValidPosition(update.lat, update.lon)) {
            if (isReasonablePositionUpdate(aircraft, update.lat, update.lon, nowMs)) {
                aircraft.lat = update.lat;
                aircraft.lon = update.lon;
                aircraft.lastPositionTimeMs = nowMs;
                totalPositions += 1;
                lastPositionMs = nowMs;
            } else {
                rejectedJumps += 1;
            }
        }

        prune(nowMs);
    }

    synchronized int recentAircraftCount(long nowMs) {
        prune(nowMs);
        return aircraftByHex.size();
    }

    synchronized String toAircraftJson(long nowMs) {
        try {
            prune(nowMs);

            JSONArray array = new JSONArray();
            for (Aircraft aircraft : aircraftByHex.values()) {
                JSONObject item = new JSONObject();
                item.put("hex", aircraft.hex);
                if (aircraft.flight != null && !aircraft.flight.isEmpty()) item.put("flight", aircraft.flight);
                if (aircraft.altitudeFt != null) item.put("alt_baro", aircraft.altitudeFt);
                if (aircraft.groundSpeedKt != null) item.put("gs", aircraft.groundSpeedKt);
                if (aircraft.trackDeg != null) item.put("track", aircraft.trackDeg);

                double seenSec = secondsSince(nowMs, aircraft.lastMessageTimeMs);
                item.put("seen", roundSeconds(seenSec));
                item.put("messages", aircraft.messageCount);

                if (aircraft.lastPositionTimeMs > 0L) {
                    double seenPosSec = secondsSince(nowMs, aircraft.lastPositionTimeMs);
                    item.put("seen_pos", roundSeconds(seenPosSec));
                    if (seenPosSec <= POSITION_HOLD_MS / 1000.0 && aircraft.lat != null && aircraft.lon != null) {
                        item.put("lat", roundCoordinate(aircraft.lat));
                        item.put("lon", roundCoordinate(aircraft.lon));
                    }
                }

                array.put(item);
            }

            JSONObject root = new JSONObject();
            root.put("now", nowMs / 1000.0);
            root.put("total", array.length());
            root.put("messages", totalMessages);
            root.put("positions", totalPositions);
            root.put("rejected_jumps", rejectedJumps);
            root.put("last_message_seen", lastMessageMs > 0L ? roundSeconds(secondsSince(nowMs, lastMessageMs)) : JSONObject.NULL);
            root.put("last_position_seen", lastPositionMs > 0L ? roundSeconds(secondsSince(nowMs, lastPositionMs)) : JSONObject.NULL);
            root.put("aircraft", array);
            return root.toString();
        } catch (Exception ignored) {
            return "{\"now\":" + (nowMs / 1000.0) + ",\"total\":0,\"aircraft\":[]}";
        }
    }

    private void prune(long nowMs) {
        Iterator<Map.Entry<String, Aircraft>> iterator = aircraftByHex.entrySet().iterator();
        while (iterator.hasNext()) {
            Aircraft aircraft = iterator.next().getValue();
            if (nowMs - aircraft.lastMessageTimeMs > AIRCRAFT_TTL_MS) {
                iterator.remove();
            }
        }
    }

    private boolean isReasonablePositionUpdate(Aircraft aircraft, double lat, double lon, long nowMs) {
        if (aircraft.lat == null || aircraft.lon == null || aircraft.lastPositionTimeMs <= 0L) return true;

        double jumpKm = distanceKm(aircraft.lat, aircraft.lon, lat, lon);
        if (jumpKm <= JUMP_GRACE_KM) return true;

        double elapsedSec = Math.max(1.0, (nowMs - aircraft.lastPositionTimeMs) / 1000.0);
        double speedKmh = (aircraft.groundSpeedKt != null ? aircraft.groundSpeedKt : 90.0) * 1.852;
        double allowedKm = JUMP_GRACE_KM + speedKmh * JUMP_SPEED_FACTOR * (elapsedSec / 3600.0);
        return jumpKm <= allowedKm;
    }

    private static boolean isValidPosition(double lat, double lon) {
        return lat >= -90.0 && lat <= 90.0 && lon >= -180.0 && lon <= 180.0 &&
            !(Math.abs(lat) < 0.000001 && Math.abs(lon) < 0.000001);
    }

    private static double secondsSince(long nowMs, long thenMs) {
        return Math.max(0.0, (nowMs - thenMs) / 1000.0);
    }

    private static double roundSeconds(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static double roundCoordinate(double value) {
        return Math.round(value * 100000.0) / 100000.0;
    }

    private static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double earthRadiusKm = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double rLat1 = Math.toRadians(lat1);
        double rLat2 = Math.toRadians(lat2);
        double sinLat = Math.sin(dLat / 2.0);
        double sinLon = Math.sin(dLon / 2.0);
        double a = sinLat * sinLat + Math.cos(rLat1) * Math.cos(rLat2) * sinLon * sinLon;
        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
        return earthRadiusKm * c;
    }

    private static final class Aircraft {
        final String hex;
        String flight;
        Double lat;
        Double lon;
        Integer altitudeFt;
        Double groundSpeedKt;
        Double trackDeg;
        long lastMessageTimeMs;
        long lastPositionTimeMs;
        int messageCount;

        Aircraft(String hex) {
            this.hex = hex;
        }
    }

    private static final class SbsUpdate {
        final String hex;
        final String flight;
        final Double lat;
        final Double lon;
        final Integer altitudeFt;
        final Double groundSpeedKt;
        final Double trackDeg;

        SbsUpdate(
            String hex,
            String flight,
            Double lat,
            Double lon,
            Integer altitudeFt,
            Double groundSpeedKt,
            Double trackDeg
        ) {
            this.hex = hex;
            this.flight = flight;
            this.lat = lat;
            this.lon = lon;
            this.altitudeFt = altitudeFt;
            this.groundSpeedKt = groundSpeedKt;
            this.trackDeg = trackDeg;
        }

        static SbsUpdate parse(String line) {
            if (line == null) return null;
            String[] fields = line.split(",", -1);
            if (fields.length < 22 || !"MSG".equalsIgnoreCase(fields[0].trim())) return null;

            String hex = clean(fields[4]);
            if (hex == null) return null;
            return new SbsUpdate(
                hex.toUpperCase(Locale.US),
                clean(fields[10]),
                parseDouble(fields[14]),
                parseDouble(fields[15]),
                parseInt(fields[11]),
                parseDouble(fields[12]),
                parseDouble(fields[13])
            );
        }

        private static String clean(String value) {
            if (value == null) return null;
            String trimmed = value.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }

        private static Integer parseInt(String value) {
            String cleaned = clean(value);
            if (cleaned == null) return null;
            try {
                return Integer.parseInt(cleaned);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        private static Double parseDouble(String value) {
            String cleaned = clean(value);
            if (cleaned == null) return null;
            try {
                double parsed = Double.parseDouble(cleaned);
                return Double.isFinite(parsed) ? parsed : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }
}
