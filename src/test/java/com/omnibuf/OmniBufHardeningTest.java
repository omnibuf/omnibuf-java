package com.omnibuf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.omnibuf.annotation.OmniBufField;
import com.omnibuf.annotation.OmniBufMessage;
import com.omnibuf.annotation.WireKind;
import com.omnibuf.pb.bench.OmniBufPeerUpdate;
import com.omnibuf.pb.bench.PeerUpdate;
import com.omnibuf.pb.bench.PeerUpdateOmniBuf;
import com.omnibuf.wire.OmniBufEncoder;
import com.omnibuf.wire.OmniBufOpaque;
import com.omnibuf.wire.PrefixVarint;
import com.omnibuf.wire.WireType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OmniBufHardeningTest {

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
        byte[] bFirst = java.util.Arrays.copyOfRange(f0First, 1 + lenFirst.bytesRead(), f0First.length);

        byte[] f0Rep = childReplaced.encodeField0(true);
        PrefixVarint.Decoded lenRep = PrefixVarint.decode(f0Rep, 1, f0Rep.length);
        byte[] bRep = java.util.Arrays.copyOfRange(f0Rep, 1 + lenRep.bytesRead(), f0Rep.length);

        byte[] f0Merge = childMerged.encodeField0(true);
        PrefixVarint.Decoded lenMerge = PrefixVarint.decode(f0Merge, 1, f0Merge.length);
        byte[] bMerge = java.util.Arrays.copyOfRange(f0Merge, 1 + lenMerge.bytesRead(), f0Merge.length);

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
            () -> OmniBufMapper.unmarshal(
                wire,
                com.omnibuf.conformance.ConformanceModels.AnnotatedPeerUpdate.class,
                tinyBytes
            )
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
    void omniBufProtobufRejectsGroupAndPopulatedProto2Extensions() throws Exception {
        // Build a synthetic proto2 FileDescriptor with both a GROUP field and an extension field
        FileDescriptorProto fileProto = FileDescriptorProto.newBuilder()
            .setName("legacy_proto2.proto")
            .setPackage("test.legacy")
            .setSyntax("proto2")
            .addMessageType(
                DescriptorProto.newBuilder()
                    .setName("GroupHolder")
                    .addField(
                        FieldDescriptorProto.newBuilder()
                            .setName("mygroup")
                            .setNumber(1)
                            .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                            .setType(FieldDescriptorProto.Type.TYPE_GROUP)
                            .setTypeName(".test.legacy.GroupHolder.MyGroup")
                    )
                    .addNestedType(
                        DescriptorProto.newBuilder()
                            .setName("MyGroup")
                            .addField(
                                FieldDescriptorProto.newBuilder()
                                    .setName("x")
                                    .setNumber(2)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                                    .setType(FieldDescriptorProto.Type.TYPE_INT32)
                            )
                    )
            )
            .addMessageType(
                DescriptorProto.newBuilder()
                    .setName("ExtendableMsg")
                    .addField(
                        FieldDescriptorProto.newBuilder()
                            .setName("id")
                            .setNumber(1)
                            .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                            .setType(FieldDescriptorProto.Type.TYPE_UINT32)
                    )
                    .addExtensionRange(
                        DescriptorProto.ExtensionRange.newBuilder().setStart(100).setEnd(200)
                    )
            )
            .addExtension(
                FieldDescriptorProto.newBuilder()
                    .setName("ext_note")
                    .setExtendee(".test.legacy.ExtendableMsg")
                    .setNumber(105)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
            )
            .build();

        FileDescriptor fd = FileDescriptor.buildFrom(fileProto, new FileDescriptor[0]);

        // GROUP message rejected on both marshal and unmarshal
        Descriptor groupDesc = fd.findMessageTypeByName("GroupHolder");
        DynamicMessage groupMsg = DynamicMessage.getDefaultInstance(groupDesc);
        assertThrows(OmniBufException.class, () -> OmniBufProtobuf.marshal(groupMsg));

        OmniBufEncoder dummyWire = new OmniBufEncoder();
        dummyWire.writeUint(1, 5L);
        assertThrows(
            OmniBufException.class,
            () -> OmniBufProtobuf.unmarshal(dummyWire.toByteArray(), groupMsg)
        );

        // Populated proto2 extension rejected on marshal (never silently dropped!)
        Descriptor extDesc = fd.findMessageTypeByName("ExtendableMsg");
        FieldDescriptor extField = fd.findExtensionByName("ext_note");
        DynamicMessage populatedExt = DynamicMessage.newBuilder(extDesc)
            .setField(extDesc.findFieldByName("id"), 7)
            .setField(extField, "must-not-be-silently-dropped")
            .build();
        assertThrows(OmniBufException.class, () -> OmniBufProtobuf.marshal(populatedExt));

        // Ensure PrefixVarint helper isn't unused
        assertEquals(1, PrefixVarint.encodedLength(0L));
    }
}
