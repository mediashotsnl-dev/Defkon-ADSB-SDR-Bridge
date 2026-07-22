package com.mediashots.defkonadsbbridge;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class BridgeAircraftStoreTest {
    @Test
    public void mergesSbsFieldsIntoAircraftJson() throws Exception {
        BridgeAircraftStore store = new BridgeAircraftStore();
        store.mergeSbsLine(
            "MSG,3,1,1,ABC123,1,2026/07/16,10:00:00.000,2026/07/16,10:00:00.000,TEST123,1200,95,270,52.09000,5.12000,0,0,0,0,0,0",
            1_000L
        );

        JSONObject root = new JSONObject(store.toAircraftJson(1_500L));
        JSONArray aircraft = root.getJSONArray("aircraft");
        assertEquals(1, aircraft.length());
        JSONObject target = aircraft.getJSONObject(0);
        assertEquals("ABC123", target.getString("hex"));
        assertEquals("TEST123", target.getString("flight"));
        assertEquals(1200, target.getInt("alt_baro"));
        assertEquals(52.09, target.getDouble("lat"), 0.00001);
        assertEquals(5.12, target.getDouble("lon"), 0.00001);
    }

    @Test
    public void removesAircraftAfterTtl() throws Exception {
        BridgeAircraftStore store = new BridgeAircraftStore();
        store.mergeSbsLine(
            "MSG,1,1,1,ABC123,1,2026/07/16,10:00:00.000,2026/07/16,10:00:00.000,TEST123,,,,,,0,0,0,0,0,0",
            1_000L
        );

        JSONObject root = new JSONObject(store.toAircraftJson(61_001L));
        assertEquals(0, root.getJSONArray("aircraft").length());
    }

    @Test
    public void ignoresNonFiniteNumericFieldsWithoutBreakingFeed() throws Exception {
        BridgeAircraftStore store = new BridgeAircraftStore();
        store.mergeSbsLine(
            "MSG,4,1,1,ABC123,1,2026/07/16,10:00:00.000,2026/07/16,10:00:00.000,,1200,NaN,Infinity,,,0,0,0,0,0,0",
            1_000L
        );

        JSONObject target = new JSONObject(store.toAircraftJson(1_500L))
            .getJSONArray("aircraft")
            .getJSONObject(0);
        assertEquals("ABC123", target.getString("hex"));
        assertFalse(target.has("gs"));
        assertFalse(target.has("track"));
    }
}
