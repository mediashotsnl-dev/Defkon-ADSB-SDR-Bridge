package com.mediashots.defkonadsbbridge;

import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RtlTcpAdsbReaderTest {
    @Test
    public void preservesOddIqByteAcrossChunks() throws Exception {
        RtlTcpAdsbReader split = reader();
        split.processIq(new byte[]{100}, 1);
        assertTrue(booleanField(split, "hasPendingIqByte"));
        split.processIq(new byte[]{120}, 1);
        assertFalse(booleanField(split, "hasPendingIqByte"));

        RtlTcpAdsbReader contiguous = reader();
        contiguous.processIq(new byte[]{100, 120}, 2);

        assertEquals(firstTailMagnitude(contiguous), firstTailMagnitude(split));
    }

    @Test
    public void builtInDecoderModesUseMatchingSampleRates() {
        assertEquals(2_400_000, NativeAdsbReader.sampleRateForDecoderMode(BridgeService.DECODER_MODE_READSB_CORE));
        assertEquals(2_000_000, NativeAdsbReader.sampleRateForDecoderMode(BridgeService.DECODER_MODE_NATIVE_FAST));
        assertEquals(2_000_000, NativeAdsbReader.sampleRateForDecoderMode(BridgeService.DECODER_MODE_LEGACY_JAVA));
    }

    private static RtlTcpAdsbReader reader() {
        return new RtlTcpAdsbReader("127.0.0.1", line -> { }, status -> { });
    }

    private static boolean booleanField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static int firstTailMagnitude(RtlTcpAdsbReader reader) throws Exception {
        Field field = reader.getClass().getDeclaredField("tail");
        field.setAccessible(true);
        return ((int[]) field.get(reader))[0];
    }
}
