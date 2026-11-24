package com.omnibuf;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.omnibuf.wire.OmniBufDecoder;
import com.omnibuf.wire.OmniBufEncoder;
import com.omnibuf.wire.PrefixVarint;
import com.omnibuf.wire.WireType;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BlindTest {

    @Test
    void blindRejectsFourSegmentWireSubMsgCrossSliceByteFusion() {
        OmniBufEncoder enc = new OmniBufEncoder();
        // Segment 1: empty 0-byte sub-message
        enc.writeTag(8, WireType.WIRE_SUB_MSG);
        enc.writeVarint(0);
        // Segment 2: valid [0x21, 0x03] (field_1 = 1)
        enc.writeTag(8, WireType.WIRE_SUB_MSG);
        enc.writeVarint(2);
        enc.writeRawBytes(new byte[] {0x21, 0x03});
        // Segment 3: truncated [0x21, 0x02] (incomplete 2-byte prefix-varint)
        enc.writeTag(8, WireType.WIRE_SUB_MSG);
        enc.writeVarint(2);
        enc.writeRawBytes(new byte[] {0x21, 0x02});
        // Segment 4: [0x01] (would fuse with segment 3 into field_1 = 64 if unvalidated)
        enc.writeTag(8, WireType.WIRE_SUB_MSG);
        enc.writeVarint(1);
        enc.writeRawBytes(new byte[] {0x01});

        byte[] pkt = enc.toByteArray();
        assertThrows(OmniBufException.class, () -> OmniBufBlind.decodeBlind(pkt));
    }

    @Test
    @SuppressWarnings("unchecked")
    void blindDecodesNonEmptyBoolKeyedMapOfArraysAndMapOfMaps() {
        OmniBufEncoder enc = new OmniBufEncoder();

        // Field #1: WireMap(WIRE_BOOL_FALSE, WIRE_ARRAY) { false: [10, 20], true: [30] }
        enc.beginTypedMap(1, WireType.WIRE_BOOL_FALSE, WireType.WIRE_ARRAY, 2);
        enc.writeRawByte(0); // key = false
        // ArrayBody: elemWT = WIRE_UINT (0x00), count = 2 (0x05), values = 10 (0x15), 20 (0x29)
        enc.writeBytesElement(new byte[] {WireType.WIRE_UINT, 0x05, 0x15, 0x29});
        enc.writeRawByte(1); // key = true
        // ArrayBody: elemWT = WIRE_UINT (0x00), count = 1 (0x03), value = 30 (0x3D)
        enc.writeBytesElement(new byte[] {WireType.WIRE_UINT, 0x03, 0x3D});
        enc.endTypedMap();

        // Field #2: WireMap(WIRE_BOOL_FALSE, WIRE_MAP) { true: { "k": 7 } }
        enc.beginTypedMap(2, WireType.WIRE_BOOL_FALSE, WireType.WIRE_MAP, 1);
        enc.writeRawByte(1); // key = true
        // MapBody: keyWT = WIRE_STRING (10), valWT = WIRE_UINT (0), count = 1 (0x03), "k" (len=1 -> 0x03, 'k'=0x6B), 7 (0x0F)
        enc.writeBytesElement(new byte[] {WireType.WIRE_STRING, WireType.WIRE_UINT, 0x03, 0x03, 0x6B, 0x0F});
        enc.endTypedMap();

        byte[] pkt = enc.toByteArray();
        assertDoesNotThrow(() -> OmniBufDecoder.validateMessage(pkt, 0, pkt.length, 0));

        Map<String, Object> decoded = assertDoesNotThrow(() -> OmniBufBlind.decodeBlind(pkt));
        Map<String, Object> f1 = (Map<String, Object>) decoded.get("field_1");
        assertEquals(
            List.of(UnsignedLong.fromLongBits(10L), UnsignedLong.fromLongBits(20L)),
            f1.get("false")
        );
        assertEquals(List.of(UnsignedLong.fromLongBits(30L)), f1.get("true"));

        Map<String, Object> f2 = (Map<String, Object>) decoded.get("field_2");
        assertEquals(Map.of("k", UnsignedLong.fromLongBits(7L)), f2.get("true"));
    }

    @Test
    void blindJsonFormatsMidRangeFloatsWithoutScientificNotation() {
        OmniBufEncoder enc = new OmniBufEncoder();
        enc.writeFloat32(13, 0.000125f);
        enc.writeFloat64(14, 15000000.0);

        String json = OmniBufBlind.decodeBlindJson(enc.toByteArray());
        assertEquals("{\"field_13\":0.000125,\"field_14\":15000000}", json);
    }

    @Test
    void blindRejectsEmptyMapWithWireExtendedValue() {
        // Field #1 WireMap: KeyWT=WireString(10), ValWT=WireExtended(15), Count=0 (0x01)
        OmniBufEncoder extMapEnc = new OmniBufEncoder();
        extMapEnc.writeTag(1, WireType.WIRE_MAP);
        extMapEnc.writeVarint(3);
        extMapEnc.writeRawBytes(new byte[] {WireType.WIRE_STRING, WireType.WIRE_EXTENDED, 0x01});
        byte[] extMapPkt = extMapEnc.toByteArray();
        assertThrows(OmniBufException.class, () -> OmniBufBlind.decodeBlind(extMapPkt));
    }

    @Test
    void schemaMetaEqualsHashCodeAndToString() {
        SchemaMeta child1 = new SchemaMeta("Child", Map.of(1, "x"), null);
        SchemaMeta child2 = new SchemaMeta("Child", Map.of(1, "x"), Map.of());
        SchemaMeta childDiff = new SchemaMeta("Child", Map.of(1, "y"), null);

        SchemaMeta a = new SchemaMeta("Root", Map.of(1, "id", 2, "child"), Map.of(2, child1));
        SchemaMeta b = new SchemaMeta("Root", Map.of(1, "id", 2, "child"), Map.of(2, child2));
        SchemaMeta diffType = new SchemaMeta("Other", Map.of(1, "id", 2, "child"), Map.of(2, child1));
        SchemaMeta diffFields = new SchemaMeta("Root", Map.of(1, "name", 2, "child"), Map.of(2, child1));
        SchemaMeta diffChildren = new SchemaMeta("Root", Map.of(1, "id", 2, "child"), Map.of(2, childDiff));

        assertEquals(a, a);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, diffType);
        assertNotEquals(a, diffFields);
        assertNotEquals(a, diffChildren);
        assertNotEquals(a, null);
        assertNotEquals(a, "Root");
        assertTrue(a.toString().contains("Root"));
        assertTrue(a.toString().contains("id"));
        assertTrue(a.toString().contains("Child"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaMetaAndBlindHandleSingleMapDuplicateReplaceEmptyNameClearRecursiveShapeAndSignedZero() {
        // 1. Duplicate key inside a SINGLE Field #3 WireMap replaces prior entry (Spec §6.6),
        //    whereas a SECOND Field #3 occurrence merges recursively (Spec §8.2).
        SchemaMeta childFirst = new SchemaMeta("ChildV1", Map.of(1, "old_col", 2, "only_v1"), Map.of());
        SchemaMeta childReplaced = new SchemaMeta("ChildV2", Map.of(1, "new_col"), Map.of());
        SchemaMeta childMerged = new SchemaMeta("", Map.of(3, "merged_col"), Map.of());

        // Helper to extract raw SchemaMeta body from encodeField0 (strips Field #0 tag 0x19 + length varint)
        byte[] f0First = childFirst.encodeField0(true);
        PrefixVarint.Decoded lenFirst = PrefixVarint.decode(f0First, 1, f0First.length);
        byte[] bFirst = Arrays.copyOfRange(f0First, 1 + lenFirst.bytesRead(), f0First.length);

        byte[] f0Rep = childReplaced.encodeField0(true);
        PrefixVarint.Decoded lenRep = PrefixVarint.decode(f0Rep, 1, f0Rep.length);
        byte[] bRep = Arrays.copyOfRange(f0Rep, 1 + lenRep.bytesRead(), f0Rep.length);

        byte[] f0Merge = childMerged.encodeField0(true);
        PrefixVarint.Decoded lenMerge = PrefixVarint.decode(f0Merge, 1, f0Merge.length);
        byte[] bMerge = Arrays.copyOfRange(f0Merge, 1 + lenMerge.bytesRead(), f0Merge.length);

        OmniBufEncoder metaEnc = new OmniBufEncoder();
        // First Field #3 WireMap with Count = 2, both keys = 5 (second entry replaces first completely!)
        metaEnc.beginTypedMap(3, WireType.WIRE_UINT, WireType.WIRE_SUB_MSG, 2);
        metaEnc.writeVarint(5L);
        metaEnc.writeVarint(bFirst.length);
        metaEnc.writeRawBytes(bFirst);
        metaEnc.writeVarint(5L);
        metaEnc.writeVarint(bRep.length);
        metaEnc.writeRawBytes(bRep);
        metaEnc.endTypedMap();
        // Second Field #3 WireMap with Count = 1, key = 5 (merges recursively onto ChildV2!)
        metaEnc.beginTypedMap(3, WireType.WIRE_UINT, WireType.WIRE_SUB_MSG, 1);
        metaEnc.writeVarint(5L);
        metaEnc.writeVarint(bMerge.length);
        metaEnc.writeRawBytes(bMerge);
        metaEnc.endTypedMap();

        byte[] rawMeta = metaEnc.toByteArray();
        SchemaMeta parsed = SchemaMeta.decode(rawMeta, 0, rawMeta.length, 1);
        SchemaMeta c5 = parsed.children().get(5);
        assertNotNull(c5);
        assertEquals("ChildV2", c5.typeName());
        // field #2 from ChildV1 was wiped by single-map duplicate replacement; #1 and #3 remain!
        assertEquals(Map.of(1, "new_col", 3, "merged_col"), c5.fields());

        // 2. Explicit empty string "" in inner Field #0 clears inherited ancestor field name back to field_<N>
        SchemaMeta rootMeta = new SchemaMeta(
            "Outer",
            Map.of(1, "sub"),
            Map.of(1, new SchemaMeta("Inner", Map.of(1, "inherited_name", 2, "kept_name"), Map.of()))
        );
        SchemaMeta innerClearMeta = new SchemaMeta("Inner", Map.of(1, ""), Map.of());

        OmniBufEncoder clearPacket = new OmniBufEncoder();
        rootMeta.writeField0(clearPacket, true);
        clearPacket.beginSubMsg(1);
        innerClearMeta.writeField0(clearPacket, true);
        clearPacket.writeUint(1, 77L);
        clearPacket.writeUint(2, 88L);
        clearPacket.endSubMsg();

        Map<String, Object> clearedBlind = OmniBufBlind.decodeBlind(clearPacket.toByteArray());
        Map<String, Object> subMap = (Map<String, Object>) clearedBlind.get("sub");
        assertTrue(subMap.containsKey("field_1"));
        assertEquals(UnsignedLong.fromLongBits(77L), subMap.get("field_1"));
        assertEquals(UnsignedLong.fromLongBits(88L), subMap.get("kept_name"));

        // 3. Recursive container shape mismatch: array<array<uint>> followed by array<array<string>>
        OmniBufEncoder nestedShape = new OmniBufEncoder();
        nestedShape.beginTypedArray(1, WireType.WIRE_ARRAY, 1);
        // Inner array element body (length-prefixed inside WireArray of WireArray): ElemWT=UINT, Count=1, val=5
        nestedShape.writeVarint(3);
        nestedShape.writeRawBytes(new byte[] {WireType.WIRE_UINT, 0x03, 0x0B});
        nestedShape.endTypedArray();

        nestedShape.beginTypedArray(1, WireType.WIRE_ARRAY, 1);
        // Second occurrence has ElemWT=STRING, Count=1, val="x"
        nestedShape.writeVarint(4);
        nestedShape.writeRawBytes(new byte[] {WireType.WIRE_STRING, 0x03, 0x03, 0x78});
        nestedShape.endTypedArray();
        assertThrows(
            OmniBufException.class,
            () -> OmniBufBlind.decodeBlind(nestedShape.toByteArray())
        );

        // 4. Public decodeBlindJson formats -0.0f / -0.0d as "-0.0" and throws on +-Infinity / NaN
        OmniBufEncoder negZeroEnc = new OmniBufEncoder();
        negZeroEnc.writeFloat32(1, -0.0f);
        negZeroEnc.writeFloat64(2, -0.0d);
        assertEquals(
            "{\"field_1\":-0.0,\"field_2\":-0.0}",
            OmniBufBlind.decodeBlindJson(negZeroEnc.toByteArray())
        );

        OmniBufEncoder infEnc = new OmniBufEncoder();
        infEnc.writeFloat32(1, Float.POSITIVE_INFINITY);
        assertThrows(OmniBufException.class, () -> OmniBufBlind.decodeBlindJson(infEnc.toByteArray()));
    }
}
