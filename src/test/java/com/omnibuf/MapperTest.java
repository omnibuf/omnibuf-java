package com.omnibuf;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.omnibuf.annotation.OmniBufField;
import com.omnibuf.annotation.OmniBufMessage;
import com.omnibuf.annotation.WireKind;
import com.omnibuf.wire.OmniBufDecoder;
import com.omnibuf.wire.OmniBufEncoder;
import com.omnibuf.wire.OmniBufOpaque;
import com.omnibuf.wire.PrefixVarint;
import com.omnibuf.wire.WireType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

public class MapperTest {

    @OmniBufMessage(name = "ConcreteCollectionsPojo")
    public static final class ConcreteCollectionsPojo {
        @OmniBufField(id = 1, name = "tags")
        public LinkedList<String> tags;

        @OmniBufField(id = 2, name = "metrics")
        public TreeMap<String, Long> metrics;
    }

    @OmniBufMessage(name = "PrimitiveArrayPojo")
    public static final class PrimitiveArrayPojo {
        @OmniBufField(id = 1, name = "ints", wire = WireKind.SINT)
        public int[] ints;

        @OmniBufField(id = 2, name = "longs", wire = WireKind.SINT)
        public long[] longs;
    }

    @OmniBufMessage(name = "UnsignedLongPojo")
    public static final class UnsignedLongPojo {
        @OmniBufField(id = 1, name = "counter")
        public UnsignedLong counter = UnsignedLong.ZERO;

        @OmniBufField(id = 2, name = "fixed_counter", wire = WireKind.FIXED64)
        public UnsignedLong fixedCounter = UnsignedLong.ZERO;

        @OmniBufField(id = 3, name = "history")
        public List<UnsignedLong> history = new ArrayList<>();

        @OmniBufField(id = 4, name = "fixed_history", wire = WireKind.FIXED64)
        public List<UnsignedLong> fixedHistory = new ArrayList<>();

        @OmniBufField(id = 5, name = "buckets", keyWire = WireKind.UINT, valueWire = WireKind.FIXED64)
        public Map<UnsignedLong, UnsignedLong> buckets = new HashMap<>();
    }

    @OmniBufMessage(name = "UnsignedLongRecord")
    public record UnsignedLongRecord(
        @OmniBufField(id = 1, name = "counter") UnsignedLong counter,
        @OmniBufField(id = 2, name = "fixed_counter", wire = WireKind.FIXED64) UnsignedLong fixedCounter
    ) {}

    @OmniBufMessage(name = "InvalidUnsignedLongWire")
    public static final class InvalidUnsignedLongWire {
        @OmniBufField(id = 1, name = "bad", wire = WireKind.SINT)
        public UnsignedLong bad = UnsignedLong.ZERO;
    }

    public enum Role {
        UNKNOWN,
        PRIMARY,
        STANDBY,
        OBSERVER
    }

    @OmniBufMessage(name = "IntegerKeyMaps")
    public static final class IntegerKeyMaps {
        @OmniBufField(id = 1, name = "auto_map")
        public Map<Integer, String> autoMap;

        @OmniBufField(id = 2, name = "uint_map", keyWire = WireKind.UINT)
        public Map<Integer, String> uintMap;

        @OmniBufField(id = 3, name = "sint_map", keyWire = WireKind.SINT)
        public Map<Integer, String> sintMap;

        @OmniBufField(id = 4, name = "fixed32_map", keyWire = WireKind.FIXED32)
        public Map<Integer, String> fixed32Map;

        @OmniBufField(id = 5, name = "sfixed32_map", keyWire = WireKind.SFIXED32)
        public Map<Integer, String> sfixed32Map;
    }

    @OmniBufMessage(name = "EnumHolder")
    public record EnumHolder(
        @OmniBufField(id = 1, name = "role") Role role,
        @OmniBufField(id = 2, name = "backup_roles") List<Role> backupRoles,
        @OmniBufField(id = 3, name = "by_role") Map<Role, String> byRole,
        @OmniBufField(id = 4, name = "by_host") Map<String, Role> byHost
    ) {}

    public static class BaseHeader {
        @OmniBufField(id = 5, name = "base_seq")
        public long baseSeq;
    }

    @OmniBufMessage(name = "DerivedPacket")
    public static final class DerivedPacket extends BaseHeader {
        @OmniBufField(id = 1, name = "child_id")
        public int childId;

        @OmniBufField(id = 8, name = "tail_tag")
        public String tailTag = "";
    }

    @OmniBufMessage(name = "BadWireString")
    public static final class BadWireString {
        @OmniBufField(id = 1, wire = WireKind.FLOAT64)
        public String wrongWire;
    }

    @OmniBufMessage(name = "BadKeyWireScalar")
    public static final class BadKeyWireScalar {
        @OmniBufField(id = 1, keyWire = WireKind.UINT)
        public int notAMap;
    }

    @OmniBufMessage(name = "InnerRecord")
    public record InnerRecord(
        @OmniBufField(id = 1, name = "host") String host,
        @OmniBufField(id = 2, name = "port") int port
    ) {}

    @OmniBufMessage(name = "OuterWithRecord")
    public static final class OuterWithRecord {
        @OmniBufField(id = 1, name = "endpoint")
        public InnerRecord endpoint;

        @OmniBufField(id = 2, name = "items")
        public List<String> items = new ArrayList<>(List.of("ctor-default-must-not-duplicate"));
    }

    @OmniBufMessage(name = "GrandParentMutable")
    public static final class GrandParentMutable {
        @OmniBufField(id = 1, name = "nested_mutable")
        public OuterWithRecord nestedMutable;
    }

    @OmniBufMessage(name = "CyclicNode")
    public record CyclicNode(
        @OmniBufField(id = 1, name = "node_id") int nodeId,
        @OmniBufField(id = 2, name = "title") String title,
        @OmniBufField(id = 3, name = "next") CyclicNode next,
        @OmniBufField(id = 4, name = "kids") List<CyclicNode> kids
    ) {}

    @Test
    void mapperUnmarshalsConcreteLinkedListAndTreeMapFields() {
        ConcreteCollectionsPojo src = new ConcreteCollectionsPojo();
        src.tags = new LinkedList<>(List.of("alpha", "beta"));
        src.metrics = new TreeMap<>(Map.of("k1", 10L, "k2", 20L));

        byte[] wire = OmniBufMapper.marshal(src, MarshalOptions.DETERMINISTIC);
        ConcreteCollectionsPojo decoded = assertDoesNotThrow(
            () -> OmniBufMapper.unmarshal(wire, ConcreteCollectionsPojo.class)
        );
        assertEquals(List.of("alpha", "beta"), decoded.tags);
        assertEquals(Map.of("k1", 10L, "k2", 20L), decoded.metrics);
    }

    @Test
    void mapperDecodesManyPrimitiveArrayChunksLinearly() {
        int chunks = 4000;
        OmniBufEncoder enc = new OmniBufEncoder(chunks * 16);
        for (int i = 0; i < chunks; i++) {
            enc.beginTypedArray(1, WireType.WIRE_SINT, 1);
            enc.writeVarint(PrefixVarint.encodeZigZag32(i - 2000));
            enc.endTypedArray();
            enc.beginTypedArray(2, WireType.WIRE_SINT, 1);
            enc.writeVarint(PrefixVarint.encodeZigZag64((long) i * 1000L - 500L));
            enc.endTypedArray();
        }
        byte[] pkt = enc.toByteArray();

        PrimitiveArrayPojo decoded = OmniBufMapper.unmarshal(pkt, PrimitiveArrayPojo.class);
        assertEquals(chunks, decoded.ints.length);
        assertEquals(chunks, decoded.longs.length);
        for (int i = 0; i < chunks; i++) {
            assertEquals(i - 2000, decoded.ints[i]);
            assertEquals((long) i * 1000L - 500L, decoded.longs[i]);
        }
    }

    @Test
    void mapperSupportsUnsignedLongScalarsListsMapsAndRecords() {
        UnsignedLong highVal = UnsignedLong.parseUnsigned("18446744073709551615"); // 2^64 - 1
        UnsignedLong midVal = UnsignedLong.parseUnsigned("9223372036854775808");   // 2^63

        UnsignedLongPojo pojo = new UnsignedLongPojo();
        pojo.counter = highVal;
        pojo.fixedCounter = midVal;
        pojo.history = List.of(UnsignedLong.ZERO, midVal, highVal);
        pojo.fixedHistory = List.of(highVal, UnsignedLong.fromLongBits(42L));
        pojo.buckets = Map.of(highVal, midVal, UnsignedLong.fromLongBits(7L), highVal);

        byte[] wire = OmniBufMapper.marshal(pojo, MarshalOptions.DETERMINISTIC);

        // Verify wire types: field #1 is WIRE_UINT and field #2 is WIRE_FIXED64
        OmniBufDecoder dec = new OmniBufDecoder(wire, 0, wire.length);
        long tag1 = dec.readTag();
        assertEquals(1, (int) (tag1 >>> 4));
        assertEquals(WireType.WIRE_UINT, (int) (tag1 & 0x0FL));
        assertEquals(-1L, dec.readUint64());
        long tag2 = dec.readTag();
        assertEquals(2, (int) (tag2 >>> 4));
        assertEquals(WireType.WIRE_FIXED64, (int) (tag2 & 0x0FL));
        assertEquals(Long.MIN_VALUE, dec.readFixed64());

        UnsignedLongPojo decoded = OmniBufMapper.unmarshal(wire, UnsignedLongPojo.class);
        assertEquals(highVal, decoded.counter);
        assertEquals(midVal, decoded.fixedCounter);
        assertEquals(pojo.history, decoded.history);
        assertEquals(pojo.fixedHistory, decoded.fixedHistory);
        assertEquals(pojo.buckets, decoded.buckets);

        // Record round-trip including default values on empty packet
        UnsignedLongRecord rec = new UnsignedLongRecord(highVal, midVal);
        byte[] recWire = OmniBufMapper.marshal(rec);
        assertEquals(rec, OmniBufMapper.unmarshal(recWire, UnsignedLongRecord.class));
        assertEquals(
            new UnsignedLongRecord(UnsignedLong.ZERO, UnsignedLong.ZERO),
            OmniBufMapper.unmarshal(new byte[0], UnsignedLongRecord.class)
        );

        // Incompatible WireKind.SINT for UnsignedLong must fail at codec compilation
        assertThrows(OmniBufException.class, () -> OmniBufMapper.marshal(new InvalidUnsignedLongWire()));
    }

    @Test
    void mapperDecodesMapIntegerKeysAsTrueIntegersWithoutHeapPollution() {
        IntegerKeyMaps src = new IntegerKeyMaps();
        src.autoMap = Map.of(42, "forty-two", 7, "seven");
        src.uintMap = Map.of(100, "hundred", 0, "zero");
        src.sintMap = Map.of(-15, "neg-fifteen", 15, "pos-fifteen");
        src.fixed32Map = Map.of(1024, "kilo");
        src.sfixed32Map = Map.of(-2048, "neg-two-kilo");

        byte[] wire = OmniBufMapper.marshal(src, MarshalOptions.DETERMINISTIC);
        IntegerKeyMaps dst = OmniBufMapper.unmarshal(wire, IntegerKeyMaps.class);

        // Iterating keys must yield Integer instances (no ClassCastException) and Map.get(int) must hit
        for (Integer k : dst.autoMap.keySet()) {
            assertEquals(Integer.class, k.getClass());
        }
        assertEquals("forty-two", dst.autoMap.get(42));
        assertEquals("hundred", dst.uintMap.get(100));
        assertEquals("neg-fifteen", dst.sintMap.get(-15));
        assertEquals("kilo", dst.fixed32Map.get(1024));
        assertEquals("neg-two-kilo", dst.sfixed32Map.get(-2048));
    }

    @Test
    void mapperSupportsJavaEnumsSuperclassFieldOrderingAndRejectsIncompatibleWireAnnotations() {
        EnumHolder holder = new EnumHolder(
            Role.PRIMARY,
            List.of(Role.STANDBY, Role.OBSERVER),
            Map.of(Role.PRIMARY, "fra01", Role.STANDBY, "ams02"),
            Map.of("fra01", Role.PRIMARY, "ams02", Role.STANDBY)
        );
        byte[] enumBytes = OmniBufMapper.marshal(holder, MarshalOptions.DETERMINISTIC);
        EnumHolder decodedHolder = OmniBufMapper.unmarshal(enumBytes, EnumHolder.class);
        assertEquals(holder, decodedHolder);

        // Subclass declares field #1, superclass declares field #5, subclass declares field #8:
        // Deterministic emission must write field #1 before #5 before #8.
        DerivedPacket derived = new DerivedPacket();
        derived.childId = 11;
        derived.baseSeq = 22L;
        derived.tailTag = "tail";
        byte[] derivedWire = OmniBufMapper.marshal(derived, MarshalOptions.DETERMINISTIC);

        OmniBufOpaque.LocatedField loc1 = OmniBufOpaque.locateField(derivedWire, 1, 1 << WireType.WIRE_UINT);
        OmniBufOpaque.LocatedField loc5 = OmniBufOpaque.locateField(derivedWire, 5, 1 << WireType.WIRE_UINT);
        OmniBufOpaque.LocatedField loc8 = OmniBufOpaque.locateField(derivedWire, 8, 1 << WireType.WIRE_STRING);
        assertTrue(loc1.found() && loc5.found() && loc8.found());
        assertTrue(loc1.valueOffset() < loc5.valueOffset());
        assertTrue(loc5.valueOffset() < loc8.valueOffset());

        DerivedPacket roundTripDerived = OmniBufMapper.unmarshal(derivedWire, DerivedPacket.class);
        assertEquals(11, roundTripDerived.childId);
        assertEquals(22L, roundTripDerived.baseSeq);
        assertEquals("tail", roundTripDerived.tailTag);

        // Incompatible @OmniBufField wire annotations fail fast
        BadWireString badStr = new BadWireString();
        badStr.wrongWire = "nope";
        assertThrows(OmniBufException.class, () -> OmniBufMapper.marshal(badStr));

        BadKeyWireScalar badKey = new BadKeyWireScalar();
        badKey.notAMap = 5;
        assertThrows(OmniBufException.class, () -> OmniBufMapper.marshal(badKey));
    }

    @Test
    void mapperValidatesSplitRecordSlicesPreservesNestedMergesAndClearsConstructorDefaults() {
        // 1. Valid duplicate WireSubMsg occurrences on a record merge fields from both slices
        // (first WireSubMsg carries only field #1 host, second carries only field #2 port)
        OmniBufEncoder splitValid = new OmniBufEncoder();
        splitValid.beginSubMsg(1);
        splitValid.writeString(1, "fra01.internal");
        splitValid.endSubMsg();
        splitValid.writeStringList(2, List.of("a"));
        splitValid.beginSubMsg(1);
        splitValid.writeUint32(2, 8443);
        splitValid.endSubMsg();
        splitValid.writeStringList(2, List.of("b"));

        OuterWithRecord merged = OmniBufMapper.unmarshal(splitValid.toByteArray(), OuterWithRecord.class);
        assertNotNull(merged.endpoint);
        assertEquals("fra01.internal", merged.endpoint.host());
        assertEquals(8443, merged.endpoint.port());
        // Constructor-populated default ("ctor-default-must-not-duplicate") must NOT appear!
        assertEquals(List.of("a", "b"), merged.items);

        // 1b. Nested mutable POJO appearing twice in a GrandParent packet must ALSO merge its inner record
        OmniBufEncoder gpSplit = new OmniBufEncoder();
        gpSplit.beginSubMsg(1);
        gpSplit.beginSubMsg(1);
        gpSplit.writeString(1, "ams02.internal");
        gpSplit.endSubMsg();
        gpSplit.endSubMsg();
        gpSplit.beginSubMsg(1);
        gpSplit.beginSubMsg(1);
        gpSplit.writeUint32(2, 9090);
        gpSplit.endSubMsg();
        gpSplit.endSubMsg();
        GrandParentMutable gpMerged = OmniBufMapper.unmarshal(gpSplit.toByteArray(), GrandParentMutable.class);
        assertNotNull(gpMerged.nestedMutable);
        assertNotNull(gpMerged.nestedMutable.endpoint);
        assertEquals("ams02.internal", gpMerged.nestedMutable.endpoint.host());
        assertEquals(9090, gpMerged.nestedMutable.endpoint.port());

        // 2. Truncated first record slice + second slice must throw OmniBufException (never fuse bytes)
        OmniBufEncoder splitCorrupt = new OmniBufEncoder();
        splitCorrupt.writeTag(1, WireType.WIRE_SUB_MSG);
        splitCorrupt.writeVarint(2);
        splitCorrupt.writeRawBytes(new byte[] {0x41, 0x02}); // Field #2 WireUint + truncated 2B varint
        splitCorrupt.writeTag(1, WireType.WIRE_SUB_MSG);
        splitCorrupt.writeVarint(1);
        splitCorrupt.writeRawBytes(new byte[] {0x01});
        assertThrows(
            OmniBufException.class,
            () -> OmniBufMapper.unmarshal(splitCorrupt.toByteArray(), OuterWithRecord.class)
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void cyclicSelfDescribingMapperEmitsSchemaMetaAcrossPopulatedDepths() {
        CyclicNode root = new CyclicNode(
            1,
            "root",
            new CyclicNode(
                2,
                "depth1",
                new CyclicNode(3, "depth2", null, List.of()),
                List.of(new CyclicNode(4, "depth2-kid", null, List.of()))
            ),
            List.of()
        );

        byte[] wire = OmniBufMapper.marshal(root, MarshalOptions.SELF_DESCRIBING);
        Map<String, Object> blind = OmniBufBlind.decodeBlind(wire);
        assertEquals("root", blind.get("title"));

        Map<String, Object> d1 = (Map<String, Object>) blind.get("next");
        assertNotNull(d1);
        assertEquals("depth1", d1.get("title"));

        Map<String, Object> d2 = (Map<String, Object>) d1.get("next");
        assertNotNull(d2);
        assertEquals("depth2", d2.get("title"));

        List<Map<String, Object>> d2Kids = (List<Map<String, Object>>) d1.get("kids");
        assertEquals(1, d2Kids.size());
        assertEquals("depth2-kid", d2Kids.get(0).get("title"));
    }
}
