package com.omnibuf.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.omnibuf.MapperTest;
import com.omnibuf.OmniBufBlind;
import com.omnibuf.OmniBufException;
import com.omnibuf.OmniBufMapper;
import com.omnibuf.OmniBufProtobuf;
import com.omnibuf.UnmarshalOptions;
import com.omnibuf.conformance.ConformanceModels;
import com.omnibuf.pb.bench.OmniBufPeerUpdate;
import com.omnibuf.pb.bench.OpaquePeerUpdate;
import com.omnibuf.pb.bench.PeerUpdate;
import com.omnibuf.pb.bench.PeerUpdateOmniBuf;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class WireTest {

    @Test
    void deterministicMapSortRejectsNullKeyWithOmniBufException() {
        HashMap<String, Long> map = new HashMap<>();
        map.put(null, 0L);
        map.put("a", 1L);

        OmniBufEncoder enc = new OmniBufEncoder();
        assertThrows(OmniBufException.class, () -> enc.writeMapStringUint64(1, map, true));
    }

    @Test
    void utf8OrderComparatorSatisfiesTransitivityAcrossSurrogateBoundary() {
        String c = "\uD800\uFFFF";
        String b = "\uE000";
        String a = "\uD800\uDC00";

        int cb = Utf8Validator.UTF8_ORDER.compare(c, b);
        int ba = Utf8Validator.UTF8_ORDER.compare(b, a);
        int ac = Utf8Validator.UTF8_ORDER.compare(a, c);

        assertFalse(
            cb < 0 && ba < 0 && ac < 0,
            () -> "Intransitive cycle detected: compare(C,B)=" + cb + ", compare(B,A)=" + ba + ", compare(A,C)=" + ac
        );

        String[] items = {a, b, c};
        for (String x : items) {
            for (String y : items) {
                assertEquals(
                    Integer.signum(Utf8Validator.UTF8_ORDER.compare(x, y)),
                    -Integer.signum(Utf8Validator.UTF8_ORDER.compare(y, x)),
                    "Comparator antisymmetry violated"
                );
                for (String z : items) {
                    if (Utf8Validator.UTF8_ORDER.compare(x, y) < 0
                            && Utf8Validator.UTF8_ORDER.compare(y, z) < 0) {
                        assertTrue(
                            Utf8Validator.UTF8_ORDER.compare(x, z) < 0,
                            "Comparator transitivity violated"
                        );
                    }
                }
            }
        }
    }

    @Test
    void writeLargeStringStreamedRejectsUnpairedHighSurrogateAtChunkBoundary() {
        String prefix2047 = "a".repeat(2047);

        // Unpaired high surrogate at index 2047 followed by non-low-surrogate 'b' at index 2048
        String unpairedAtBoundary = prefix2047 + "\uD800b";
        OmniBufEncoder enc1 = new OmniBufEncoder();
        assertThrows(OmniBufException.class, () -> enc1.writeString(1, unpairedAtBoundary));

        // Unpaired high surrogate at exact end of 2048-char chunk
        String unpairedAtEnd = prefix2047 + "\uD800";
        OmniBufEncoder enc2 = new OmniBufEncoder();
        assertThrows(OmniBufException.class, () -> enc2.writeString(1, unpairedAtEnd));

        // Valid surrogate pair straddling the 2048-char boundary (2047 + 2048) must round-trip cleanly
        String validPairAtBoundary = prefix2047 + "\uD83D\uDE00z";
        OmniBufEncoder enc3 = new OmniBufEncoder();
        enc3.writeString(1, validPairAtBoundary);
        byte[] pkt = enc3.toByteArray();
        assertEquals(validPairAtBoundary, OmniBufOpaque.readString(pkt, 0, pkt.length, 1));
    }

    @Test
    void utf8ValidatorRejectsTruncatedMultiByteSequences() {
        // 8-byte ASCII prefix followed by truncated 3-byte and 4-byte UTF-8 sequences
        byte[] trunc3 = new byte[] {'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', (byte) 0xE2, (byte) 0x82};
        byte[] valid3 = new byte[] {'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', (byte) 0xE2, (byte) 0x82, (byte) 0xAC};
        byte[] trunc4 = new byte[] {'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', (byte) 0xF0, (byte) 0x9F, (byte) 0x98};
        byte[] valid4 = new byte[] {
            'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', (byte) 0xF0, (byte) 0x9F, (byte) 0x98, (byte) 0x80
        };

        assertFalse(Utf8Validator.isValidUtf8(trunc3, 0, trunc3.length));
        assertTrue(Utf8Validator.isValidUtf8(valid3, 0, valid3.length));
        assertFalse(Utf8Validator.isValidUtf8(trunc4, 0, trunc4.length));
        assertTrue(Utf8Validator.isValidUtf8(valid4, 0, valid4.length));
    }

    @Test
    void readVarint4Or9RequiresExact4ByteOr9BytePrefix() {
        // 1-byte varint with bit 2 set (b0 = 0x05 -> bits 0..2 = 101)
        byte[] oneByte = new byte[] {0x05, 0x00, 0x00, 0x00};
        assertThrows(OmniBufException.class, () -> OmniBufDecoder.readVarint4Or9(oneByte, 0, oneByte.length));

        // 2-byte varint with bit 2 set (b0 = 0x06 -> bits 0..2 = 110)
        byte[] twoByte = new byte[] {0x06, 0x01, 0x00, 0x00};
        assertThrows(OmniBufException.class, () -> OmniBufDecoder.readVarint4Or9(twoByte, 0, twoByte.length));

        // Valid 4-byte varint: value = 16_384 -> (16_384 << 3) | 4
        OmniBufEncoder enc4 = new OmniBufEncoder();
        enc4.writeVarint(16_384L);
        byte[] fourByte = enc4.toByteArray();
        assertEquals(4, fourByte.length);
        assertEquals(16_384L, OmniBufDecoder.readVarint4Or9(fourByte, 0, fourByte.length));

        // Valid 9-byte varint: value = 1L << 40
        OmniBufEncoder enc9 = new OmniBufEncoder();
        enc9.writeVarint(1L << 40);
        byte[] nineByte = enc9.toByteArray();
        assertEquals(9, nineByte.length);
        assertEquals(1L << 40, OmniBufDecoder.readVarint4Or9(nineByte, 0, nineByte.length));
    }

    @Test
    void codecPoolIsSafeForVirtualThreadsAndReusesPlatformSlots() throws Exception {
        // Platform thread should reuse its ThreadLocal encoder slot
        OmniBufEncoder p1 = CodecPool.acquireEncoder();
        CodecPool.releaseEncoder(p1);
        OmniBufEncoder p2 = CodecPool.acquireEncoder();
        assertSame(p1, p2);
        CodecPool.releaseEncoder(p2);

        // Virtual threads should not pin ThreadLocal slots and must safely encode/decode concurrently
        int threadCount = 64;
        List<Thread> threads = new ArrayList<>(threadCount);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int id = i;
            Thread vt = Thread.ofVirtual().start(() -> {
                try {
                    OmniBufEncoder e1 = CodecPool.acquireEncoder();
                    CodecPool.releaseEncoder(e1);
                    OmniBufEncoder e2 = CodecPool.acquireEncoder();
                    assertNotSame(e1, e2, "Virtual thread must not retain ThreadLocal encoder slot");
                    e2.writeUint32(1, id);
                    e2.writeString(2, "vt-utf8-\u20AC-\uD83D\uDE00-" + id);
                    byte[] bytes = e2.toByteArray();
                    CodecPool.releaseEncoder(e2);

                    OmniBufDecoder d1 = CodecPool.acquireDecoder(bytes, 0, bytes.length);
                    CodecPool.releaseDecoder(d1);
                    OmniBufDecoder d2 = CodecPool.acquireDecoder(bytes, 0, bytes.length);
                    assertNotSame(d1, d2, "Virtual thread must not retain ThreadLocal decoder slot");
                    assertEquals((1L << 4) | WireType.WIRE_UINT, d2.readTag());
                    assertEquals(id, d2.readUint32());
                    assertEquals((2L << 4) | WireType.WIRE_STRING, d2.readTag());
                    assertEquals("vt-utf8-\u20AC-\uD83D\uDE00-" + id, d2.readString());
                    CodecPool.releaseDecoder(d2);
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
            threads.add(vt);
        }
        for (Thread vt : threads) {
            vt.join();
        }
        assertTrue(failures.isEmpty(), () -> "Virtual thread failures: " + failures);
    }

    @Test
    void collectionHeaderTypeMismatchThrowsAcrossAllDecoders() {
        // PeerUpdate field #7 is repeated string tags; send WireArray<WireUint> instead
        OmniBufEncoder badArrEnc = new OmniBufEncoder();
        badArrEnc.writeUint32List(7, List.of(1, 2, 3));
        byte[] badArrPkt = badArrEnc.toByteArray();

        assertThrows(OmniBufException.class, () -> PeerUpdateOmniBuf.unmarshal(badArrPkt));
        assertThrows(
            OmniBufException.class,
            () -> OmniBufProtobuf.unmarshal(badArrPkt, PeerUpdate.getDefaultInstance())
        );
        assertThrows(
            OmniBufException.class,
            () -> OmniBufMapper.unmarshal(badArrPkt, ConformanceModels.AnnotatedPeerUpdate.class)
        );
        assertThrows(OmniBufException.class, () -> new OpaquePeerUpdate(badArrPkt).getTags());
        assertThrows(
            OmniBufException.class,
            () -> OmniBufOpaque.readStringList(badArrPkt, 0, badArrPkt.length, 7)
        );

        // ConcreteCollectionsPojo field #2 is Map<String, Long> (WIRE_STRING -> WIRE_SINT/UINT);
        // send WireMap<WireUint, WireString> instead
        OmniBufEncoder badMapEnc = new OmniBufEncoder();
        badMapEnc.beginTypedMap(2, WireType.WIRE_UINT, WireType.WIRE_STRING, 1);
        badMapEnc.writeVarint(1L);
        badMapEnc.writeStringElement("v");
        badMapEnc.endTypedMap();
        byte[] badMapPkt = badMapEnc.toByteArray();

        assertThrows(
            OmniBufException.class,
            () -> OmniBufMapper.unmarshal(badMapPkt, MapperTest.ConcreteCollectionsPojo.class)
        );
        assertThrows(
            OmniBufException.class,
            () -> OmniBufOpaque.readMapStringUint64(badMapPkt, 0, badMapPkt.length, 2)
        );
    }

    @Test
    void unmarshalOptionsLimitsEnforcedAcrossAllTiers() {
        OmniBufPeerUpdate peer = new OmniBufPeerUpdate()
            .setAsn(64512)
            .setRouterId("rtr-limits")
            .setTags(List.of("one", "two", "three", "four"));
        byte[] wire = peer.marshal();

        // 1. maxMessageBytes exceeded
        UnmarshalOptions tinyBytes = UnmarshalOptions.DEFAULT.withMaxMessageBytes(10);
        assertThrows(OmniBufException.class, () -> PeerUpdateOmniBuf.unmarshal(wire, tinyBytes));
        assertThrows(OmniBufException.class, () -> OmniBufBlind.decodeBlind(wire, tinyBytes));
        assertThrows(
            OmniBufException.class,
            () -> OmniBufProtobuf.unmarshal(wire, PeerUpdate.getDefaultInstance(), tinyBytes)
        );
        assertThrows(
            OmniBufException.class,
            () -> OmniBufMapper.unmarshal(wire, ConformanceModels.AnnotatedPeerUpdate.class, tinyBytes)
        );

        // 2. maxCollectionElements exceeded (4 tags > limit of 2)
        UnmarshalOptions tinyCount = UnmarshalOptions.DEFAULT.withMaxCollectionElements(2);
        assertThrows(OmniBufException.class, () -> PeerUpdateOmniBuf.unmarshal(wire, tinyCount));
        assertThrows(OmniBufException.class, () -> OmniBufBlind.decodeBlind(wire, tinyCount));
        assertThrows(
            OmniBufException.class,
            () -> OmniBufProtobuf.unmarshal(wire, PeerUpdate.getDefaultInstance(), tinyCount)
        );

        // 3. maxNestingDepth exceeded (WireArray of tags enters depth 1 > limit of 0)
        UnmarshalOptions zeroDepth = UnmarshalOptions.DEFAULT.withMaxNestingDepth(0);
        assertThrows(OmniBufException.class, () -> PeerUpdateOmniBuf.unmarshal(wire, zeroDepth));
        assertThrows(OmniBufException.class, () -> OmniBufBlind.decodeBlind(wire, zeroDepth));
    }

    @Test
    void doubleAndFloatArrayListsRoundTripWithoutBoxingAndRejectNulls() {
        DoubleArrayList doubles = new DoubleArrayList(4);
        doubles.addDouble(-0.0d);
        doubles.addDouble(Math.PI);
        doubles.addAll(new double[] {Double.POSITIVE_INFINITY, Double.NaN, 42.5d});
        assertEquals(5, doubles.size());
        assertEquals(Math.PI, doubles.getDouble(1));
        assertThrows(NullPointerException.class, () -> doubles.add(null));

        FloatArrayList floats = new FloatArrayList(4);
        floats.addFloat(-1.5f);
        floats.addAll(new float[] {0.0f, Float.MAX_VALUE});
        assertEquals(3, floats.size());
        assertEquals(-1.5f, floats.getFloat(0));
        assertThrows(NullPointerException.class, () -> floats.add(null));

        OmniBufEncoder enc = new OmniBufEncoder();
        enc.writeFloat64List(1, doubles);
        enc.writeFloat32List(2, floats);
        byte[] wire = enc.toByteArray();

        OmniBufDecoder dec = new OmniBufDecoder(wire);
        assertEquals((1L << 4) | WireType.WIRE_ARRAY, dec.readTag());
        long arr1 = dec.beginArray(WireType.WIRE_FLOAT64, 0);
        DoubleArrayList outD = new DoubleArrayList((int) arr1);
        int end1 = OmniBufDecoder.readFloat64SliceInto(dec.buffer(), dec.position(), dec.limit(), outD, (int) arr1);
        dec.position(end1);
        dec.endContainer((int) (arr1 >>> 32));
        assertEquals(doubles, outD);

        assertEquals((2L << 4) | WireType.WIRE_ARRAY, dec.readTag());
        long arr2 = dec.beginArray(WireType.WIRE_FLOAT32, 0);
        FloatArrayList outF = new FloatArrayList((int) arr2);
        int end2 = OmniBufDecoder.readFloat32SliceInto(dec.buffer(), dec.position(), dec.limit(), outF, (int) arr2);
        dec.position(end2);
        dec.endContainer((int) (arr2 >>> 32));
        assertEquals(floats, outF);
    }

    @Test
    void writeStringListAndReadStringSliceIntoHandleLargeListsAsciiUtf8AndInvalidUtf8() {
        List<String> large = new ArrayList<>(600);
        for (int i = 0; i < 600; i++) {
            large.add(i % 25 == 0 ? "utf8-\u20AC-\uD83D\uDE00-" + i : "item-payload-string-value-" + i);
        }
        large.add("");
        large.add("x".repeat(200));

        OmniBufEncoder enc = new OmniBufEncoder();
        enc.writeStringList(1, large);
        byte[] wire = enc.toByteArray();

        OmniBufDecoder dec = new OmniBufDecoder(wire);
        assertEquals((1L << 4) | WireType.WIRE_ARRAY, dec.readTag());
        long arr = dec.beginArray(WireType.WIRE_STRING, 0);
        ArrayList<String> decoded = new ArrayList<>((int) arr);
        int end = OmniBufDecoder.readStringSliceInto(dec.buffer(), dec.position(), dec.limit(), decoded, (int) arr);
        dec.position(end);
        dec.endContainer((int) (arr >>> 32));
        assertEquals(large, decoded);

        // Verify PeerUpdateOmniBuf.estimateMinSize accounts for repeated strings
        OmniBufPeerUpdate bigPeer = new OmniBufPeerUpdate().setTags(large);
        assertTrue(PeerUpdateOmniBuf.estimateMinSize(bigPeer, 2) > 16_383);
        assertTrue(PeerUpdateOmniBuf.isLargePayload(bigPeer));

        // Invalid UTF-8 inside WireArray<WireString> must be rejected by readStringSliceInto
        byte[] badPayload = new byte[] {0x05, 'a', (byte) 0xFF};
        ArrayList<String> sink = new ArrayList<>();
        assertThrows(
            OmniBufException.class,
            () -> OmniBufDecoder.readStringSliceInto(badPayload, 0, badPayload.length, sink, 1)
        );
    }
}
