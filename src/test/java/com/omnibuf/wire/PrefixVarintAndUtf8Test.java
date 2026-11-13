package com.omnibuf.wire;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.omnibuf.OmniBufException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PrefixVarintAndUtf8Test {

    @Test
    void prefixVarintRoundTripsAcrossAllWidthTiers() {
        long[] values = {
            0L,
            1L,
            127L,
            128L,
            255L,
            16_383L,
            16_384L,
            65_535L,
            536_870_911L,
            536_870_912L,
            0x7FFF_FFFF_FFFF_FFFFL,
            0x8000_0000_0000_0000L,
            0xFFFF_FFFF_FFFF_FFFFL
        };
        int[] expectedSizes = {1, 1, 1, 2, 2, 2, 4, 4, 4, 9, 9, 9, 9};

        byte[] buf = new byte[16];
        for (int i = 0; i < values.length; i++) {
            long v = values[i];
            int expectedLen = expectedSizes[i];
            assertEquals(expectedLen, PrefixVarint.encodedLength(v));
            int end = PrefixVarint.write(buf, 0, v);
            assertEquals(expectedLen, end);
            PrefixVarint.Decoded dec = PrefixVarint.decode(buf, 0, end);
            assertEquals(expectedLen, dec.bytesRead());
            assertEquals(v, dec.value());
        }
    }

    @Test
    void acceptsReserved2ByteBackpatchedVarintAndRejectsReservedMarkers() {
        byte[] twoByteSlot = new byte[2];
        PrefixVarint.writeOverlong2Byte(twoByteSlot, 0, 42);
        PrefixVarint.Decoded dec = PrefixVarint.decode(twoByteSlot, 0, 2);
        assertEquals(2, dec.bytesRead());
        assertEquals(42L, dec.value());

        // Reserved marker bytes where (b0 & 0x07) == 0 && b0 != 0
        for (int marker : new int[] {0x08, 0x10, 0x20, 0x40, 0x80, 0xF8}) {
            byte[] packet = new byte[] {(byte) marker, 0, 0, 0, 0, 0, 0, 0, 0};
            assertThrows(OmniBufException.class, () -> PrefixVarint.decode(packet, 0, packet.length));
        }
    }

    @Test
    void rejectsTruncatedVarints() {
        assertThrows(OmniBufException.class, () -> PrefixVarint.decode(new byte[0], 0, 0));
        assertThrows(OmniBufException.class, () -> PrefixVarint.decode(new byte[] {0x02}, 0, 1));
        assertThrows(OmniBufException.class, () -> PrefixVarint.decode(new byte[] {0x04, 0x00, 0x01}, 0, 3));
        assertThrows(
            OmniBufException.class,
            () -> PrefixVarint.decode(new byte[] {0x00, 1, 2, 3, 4, 5, 6, 7}, 0, 8)
        );
    }

    @Test
    void zigZag32And64RoundTripExtremes() {
        int[] i32s = {0, -1, 1, -2, 2, -62, Integer.MAX_VALUE, Integer.MIN_VALUE};
        for (int v : i32s) {
            long enc = PrefixVarint.encodeZigZag32(v);
            assertEquals(v, PrefixVarint.decodeZigZag32(enc));
        }

        long[] i64s = {0L, -1L, 1L, -2L, 2L, Long.MAX_VALUE, Long.MIN_VALUE};
        for (long v : i64s) {
            long enc = PrefixVarint.encodeZigZag64(v);
            assertEquals(v, PrefixVarint.decodeZigZag64(enc));
        }
    }

    @Test
    void strictUtf8AcceptsValidMultilingualStrings() {
        String[] valid = {
            "",
            "edge-rtr-fra01.core.backbone.net",
            "a\u0000b",
            "Caf\u00E9 \uFFFD",
            "\u6771\u4EAC\u30BF\u30EF\u30FC",
            "\u0645\u0631\u062D\u0628\u0627",
            "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66"
        };
        for (String s : valid) {
            byte[] encoded = Utf8Validator.encodeStrict(s);
            assertArrayEquals(s.getBytes(StandardCharsets.UTF_8), encoded);
            assertTrue(Utf8Validator.isValidUtf8(encoded, 0, encoded.length));
            assertEquals(s, Utf8Validator.decodeStrict(encoded, 0, encoded.length));
        }
    }

    @Test
    void strictUtf8RejectsInvalidBytesAndUnpairedSurrogates() {
        byte[][] invalid = {
            {(byte) 0xFF},
            {(byte) 0x80},
            {(byte) 0xC0, (byte) 0x80}, // Overlong NUL
            {(byte) 0xE0, (byte) 0x80, (byte) 0x80}, // Overlong 3-byte
            {(byte) 0xED, (byte) 0xA0, (byte) 0x80}, // UTF-16 high surrogate U+D800
            {(byte) 0xED, (byte) 0xBF, (byte) 0xBF}, // UTF-16 low surrogate U+DFFF
            {(byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80}, // > U+10FFFF
            {(byte) 0xE2, (byte) 0x82} // Truncated 3-byte
        };
        for (byte[] seq : invalid) {
            assertFalse(Utf8Validator.isValidUtf8(seq, 0, seq.length));
            assertThrows(OmniBufException.class, () -> Utf8Validator.decodeStrict(seq, 0, seq.length));
        }

        // Unpaired UTF-16 surrogates on Java String encode
        assertThrows(OmniBufException.class, () -> Utf8Validator.encodeStrict("bad\uD800high"));
        assertThrows(OmniBufException.class, () -> Utf8Validator.encodeStrict("bad\uDC00low"));
    }

    @Test
    void utf8OrderSortsSupplementaryCodepointsAfterBmp() {
        String bmpHigh = "\uFFFF"; // 3-byte UTF-8: EF BF BF (U+FFFF)
        String supplementary = "\uD800\uDC00"; // 4-byte UTF-8: F0 90 80 80 (U+10000)

        // Standard UTF-16 String.compareTo orders '\uD800' < '\uFFFF', opposite to UTF-8 byte order
        assertTrue(supplementary.compareTo(bmpHigh) < 0);
        // Utf8Validator.UTF8_ORDER matches true UTF-8 lexicographic byte order
        assertTrue(Utf8Validator.UTF8_ORDER.compare(bmpHigh, supplementary) < 0);
    }
}
