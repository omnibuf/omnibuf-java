package com.omnibuf;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileOptions;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse;
import com.omnibuf.annotation.OmniBufField;
import com.omnibuf.annotation.OmniBufMessage;
import com.omnibuf.pb.bench.OmniBufPeerUpdate;
import com.omnibuf.pb.bench.OpaquePeerUpdate;
import com.omnibuf.pb.bench.PeerUpdateOmniBuf;
import com.omnibuf.plugin.ProtocGenOmniBufJava;
import com.omnibuf.wire.OmniBufDecoder;
import com.omnibuf.wire.OmniBufEncoder;
import com.omnibuf.wire.OmniBufOpaque;
import com.omnibuf.wire.Utf8Validator;
import com.omnibuf.wire.WireType;
import java.io.StringWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditRegressionsTest {

    @OmniBufMessage(name = "ConcreteCollectionsPojo")
    public static final class ConcreteCollectionsPojo {
        @OmniBufField(id = 1, name = "tags")
        public LinkedList<String> tags;

        @OmniBufField(id = 2, name = "metrics")
        public TreeMap<String, Long> metrics;
    }

    // JV-1: OmniBufBlind 4-segment WireSubMsg cross-slice byte fusion
    @Test
    void jv1_blindRejectsFourSegmentWireSubMsgCrossSliceByteFusion() {
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

    // JV-2: OmniBufOpaque silent zero/empty return past 128 MiB (0x07FF_FFFF)
    @Test
    void jv2_opaqueReadsFieldsPast128MiBOffsetBoundary() {
        OmniBufPeerUpdate peer = new OmniBufPeerUpdate()
            .setAsn(64512)
            .setRouterId("rtr-1")
            .setSequence(99L);
        byte[] encoded = PeerUpdateOmniBuf.marshal(peer, MarshalOptions.SELF_DESCRIBING);

        int offset = 134_217_600;
        byte[] slab = new byte[134_218_000];
        System.arraycopy(encoded, 0, slab, offset, encoded.length);
        assertTrue(offset + encoded.length > 0x07FF_FFFF);

        OpaquePeerUpdate view = new OpaquePeerUpdate(slab, offset, encoded.length);
        assertEquals(64512, view.getAsn());
        assertEquals("rtr-1", view.getRouterId());
        assertEquals(99L, view.getSequence());
    }

    // JV-3a: OmniBufProtobuf proto2 closed enum VALUES[-1] ArrayIndexOutOfBoundsException
    @Test
    void jv3a_protobufUnmarshalUnknownClosedProto2EnumDoesNotThrowArrayIndexOutOfBounds() {
        // DescriptorProtos.FieldDescriptorProto is a compiled proto2 GeneratedMessage;
        // field #5 (type) is a closed proto2 enum (FieldDescriptorProto.Type).
        OmniBufEncoder enc = new OmniBufEncoder();
        enc.writeString(1, "sample_field");
        enc.writeSint32(5, 999);
        byte[] pkt = enc.toByteArray();

        FieldDescriptorProto decoded = assertDoesNotThrow(
            () -> OmniBufProtobuf.unmarshal(pkt, FieldDescriptorProto.getDefaultInstance())
        );
        assertEquals("sample_field", decoded.getName());
    }

    // JV-3b (part 1): Missing proto2 required field must throw OmniBufException, not UninitializedMessageException
    @Test
    void jv3b_protobufUnmarshalMissingProto2RequiredFieldThrowsOmniBufException() throws Exception {
        FileDescriptor fd = buildProto2RequiredDescriptor();
        Descriptor reqInner = fd.findMessageTypeByName("ReqInner");

        OmniBufEncoder enc = new OmniBufEncoder();
        enc.writeUint32(1, 10); // missing required field #2 ("b")
        byte[] pkt = enc.toByteArray();

        assertThrows(
            OmniBufException.class,
            () -> OmniBufProtobuf.unmarshal(pkt, DynamicMessage.getDefaultInstance(reqInner))
        );
    }

    // JV-3b (part 2): Concatenated sub-message splitting proto2 required fields across 2 segments must succeed
    @Test
    void jv3b_protobufUnmarshalMergesSplitConcatenatedProto2RequiredSubMsg() throws Exception {
        FileDescriptor fd = buildProto2RequiredDescriptor();
        Descriptor reqOuter = fd.findMessageTypeByName("ReqOuter");
        Descriptor reqInner = fd.findMessageTypeByName("ReqInner");

        OmniBufEncoder enc = new OmniBufEncoder();
        // Segment 1 sets required field #1 (a = 10)
        enc.beginSubMsg(1);
        enc.writeUint32(1, 10);
        enc.endSubMsg();
        // Segment 2 sets required field #2 (b = "ok")
        enc.beginSubMsg(1);
        enc.writeString(2, "ok");
        enc.endSubMsg();
        byte[] splitPkt = enc.toByteArray();

        DynamicMessage decoded = assertDoesNotThrow(
            () -> OmniBufProtobuf.unmarshal(splitPkt, DynamicMessage.getDefaultInstance(reqOuter))
        );
        DynamicMessage inner = (DynamicMessage) decoded.getField(reqOuter.findFieldByName("inner"));
        assertEquals(10, inner.getField(reqInner.findFieldByName("a")));
        assertEquals("ok", inner.getField(reqInner.findFieldByName("b")));
    }

    private static FileDescriptor buildProto2RequiredDescriptor() throws Exception {
        DescriptorProto reqInner = DescriptorProto.newBuilder()
            .setName("ReqInner")
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("a")
                    .setNumber(1)
                    .setLabel(FieldDescriptorProto.Label.LABEL_REQUIRED)
                    .setType(FieldDescriptorProto.Type.TYPE_UINT32)
            )
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("b")
                    .setNumber(2)
                    .setLabel(FieldDescriptorProto.Label.LABEL_REQUIRED)
                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
            )
            .build();

        DescriptorProto reqOuter = DescriptorProto.newBuilder()
            .setName("ReqOuter")
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("inner")
                    .setNumber(1)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                    .setTypeName(".audit.p2.ReqInner")
            )
            .build();

        FileDescriptorProto fileProto = FileDescriptorProto.newBuilder()
            .setName("audit_req.proto")
            .setPackage("audit.p2")
            .setSyntax("proto2")
            .addMessageType(reqInner)
            .addMessageType(reqOuter)
            .build();

        return FileDescriptor.buildFrom(fileProto, new FileDescriptor[0]);
    }

    // JV-4: OmniBufBlind rejects non-empty WireMap<bool, WireArray> and WireMap<bool, WireMap>
    @Test
    @SuppressWarnings("unchecked")
    void jv4_blindDecodesNonEmptyBoolKeyedMapOfArraysAndMapOfMaps() {
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

    // JV-7a: NullPointerException on null key in deterministic map sorting
    @Test
    void jv7a_deterministicMapSortRejectsNullKeyWithOmniBufExceptionNotNpe() {
        HashMap<String, Long> map = new HashMap<>();
        map.put(null, 0L);
        map.put("a", 1L);

        OmniBufEncoder enc = new OmniBufEncoder();
        assertThrows(OmniBufException.class, () -> enc.writeMapStringUint64(1, map, true));
    }

    // JV-7b: Intransitive surrogate comparator cycle in Utf8Validator.UTF8_ORDER
    @Test
    void jv7b_utf8OrderComparatorSatisfiesTransitivityAcrossSurrogateBoundary() {
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

    // JV-8a: ProtocGenOmniBufJava import shadowing on message Encoder
    @Test
    void jv8a_pluginGeneratedEncoderMessageDoesNotShadowWireOmniBufEncoderImport(@TempDir Path tempDir)
            throws Exception {
        FileDescriptorProto fileProto = FileDescriptorProto.newBuilder()
            .setName("encoder_shadow.proto")
            .setPackage("audit.gen8a")
            .setSyntax("proto3")
            .setOptions(FileOptions.newBuilder().setJavaPackage("com.omnibuf.audit.gen8a"))
            .addMessageType(
                DescriptorProto.newBuilder()
                    .setName("Encoder")
                    .addField(
                        FieldDescriptorProto.newBuilder()
                            .setName("id")
                            .setNumber(1)
                            .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                            .setType(FieldDescriptorProto.Type.TYPE_UINT32)
                    )
            )
            .build();

        CodeGeneratorResponse resp = ProtocGenOmniBufJava.generate(
            CodeGeneratorRequest.newBuilder()
                .addProtoFile(fileProto)
                .addFileToGenerate("encoder_shadow.proto")
                .build()
        );
        assertFalse(resp.hasError(), () -> "Unexpected plugin error: " + resp.getError());

        CodeGeneratorResponse.File codecFile = resp.getFileList().stream()
            .filter(f -> f.getName().endsWith("/EncoderOmniBuf.java"))
            .findFirst()
            .orElseThrow();
        String content = codecFile.getContent();
        boolean hasWireImport = content.contains("import com.omnibuf.wire.OmniBufEncoder;");
        boolean hasUnqualifiedModelParam = content.contains("marshal(OmniBufEncoder ");
        assertFalse(
            hasWireImport && hasUnqualifiedModelParam,
            "EncoderOmniBuf.java simultaneously imports wire.OmniBufEncoder and uses unqualified OmniBufEncoder model"
        );

        assertCompilesCleanly(resp, tempDir);
    }

    // JV-8b: ProtocGenOmniBufJava <oneof>Case field collision with explicit <oneof>_case field
    @Test
    void jv8b_pluginGeneratedOneofCaseFieldDoesNotCollideWithExplicitCaseField(@TempDir Path tempDir)
            throws Exception {
        FileDescriptorProto fileProto = FileDescriptorProto.newBuilder()
            .setName("oneof_case_collision.proto")
            .setPackage("audit.gen8b")
            .setSyntax("proto3")
            .setOptions(FileOptions.newBuilder().setJavaPackage("com.omnibuf.audit.gen8b"))
            .addMessageType(
                DescriptorProto.newBuilder()
                    .setName("Msg")
                    .addOneofDecl(OneofDescriptorProto.newBuilder().setName("status"))
                    .addField(
                        FieldDescriptorProto.newBuilder()
                            .setName("ok")
                            .setNumber(1)
                            .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                            .setType(FieldDescriptorProto.Type.TYPE_UINT32)
                            .setOneofIndex(0)
                    )
                    .addField(
                        FieldDescriptorProto.newBuilder()
                            .setName("status_case")
                            .setNumber(2)
                            .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                            .setType(FieldDescriptorProto.Type.TYPE_INT32)
                    )
            )
            .build();

        CodeGeneratorResponse resp = ProtocGenOmniBufJava.generate(
            CodeGeneratorRequest.newBuilder()
                .addProtoFile(fileProto)
                .addFileToGenerate("oneof_case_collision.proto")
                .build()
        );
        assertFalse(resp.hasError(), () -> "Unexpected plugin error: " + resp.getError());

        CodeGeneratorResponse.File modelFile = resp.getFileList().stream()
            .filter(f -> f.getName().endsWith("/OmniBufMsg.java"))
            .findFirst()
            .orElseThrow();
        String content = modelFile.getContent();
        int firstDecl = content.indexOf("int statusCase;");
        int secondDecl = firstDecl < 0 ? -1 : content.indexOf("int statusCase;", firstDecl + 1);
        assertEquals(-1, secondDecl, "OmniBufMsg.java emitted duplicate 'int statusCase;' fields");

        assertCompilesCleanly(resp, tempDir);
    }

    // JV-8c: ProtocGenOmniBufJava nested Foo.Bar vs top-level Foo_Bar file collision
    @Test
    void jv8c_pluginGeneratedNestedMessageDoesNotCollideWithUnderscoreSibling(@TempDir Path tempDir)
            throws Exception {
        DescriptorProto foo = DescriptorProto.newBuilder()
            .setName("Foo")
            .addNestedType(
                DescriptorProto.newBuilder()
                    .setName("Bar")
                    .addField(
                        FieldDescriptorProto.newBuilder()
                            .setName("x")
                            .setNumber(1)
                            .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                            .setType(FieldDescriptorProto.Type.TYPE_UINT32)
                    )
            )
            .build();

        DescriptorProto fooBar = DescriptorProto.newBuilder()
            .setName("Foo_Bar")
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("y")
                    .setNumber(1)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .setType(FieldDescriptorProto.Type.TYPE_UINT32)
            )
            .build();

        FileDescriptorProto fileProto = FileDescriptorProto.newBuilder()
            .setName("nested_collision.proto")
            .setPackage("audit.gen8c")
            .setSyntax("proto3")
            .setOptions(FileOptions.newBuilder().setJavaPackage("com.omnibuf.audit.gen8c"))
            .addMessageType(foo)
            .addMessageType(fooBar)
            .build();

        CodeGeneratorResponse resp = ProtocGenOmniBufJava.generate(
            CodeGeneratorRequest.newBuilder()
                .addProtoFile(fileProto)
                .addFileToGenerate("nested_collision.proto")
                .build()
        );
        assertFalse(resp.hasError(), () -> "Unexpected plugin error: " + resp.getError());

        Set<String> seenNames = new HashSet<>();
        for (CodeGeneratorResponse.File f : resp.getFileList()) {
            assertTrue(
                seenNames.add(f.getName()),
                () -> "Duplicate generated output file name: " + f.getName()
            );
        }

        assertCompilesCleanly(resp, tempDir);
    }

    private static void assertCompilesCleanly(CodeGeneratorResponse resp, Path tempDir) throws Exception {
        Path srcDir = tempDir.resolve("src");
        Path classesDir = tempDir.resolve("classes");
        Files.createDirectories(classesDir);

        List<Path> javaFiles = new ArrayList<>();
        for (CodeGeneratorResponse.File f : resp.getFileList()) {
            Path p = srcDir.resolve(f.getName());
            Files.createDirectories(p.getParent());
            Files.writeString(p, f.getContent(), StandardCharsets.UTF_8);
            javaFiles.add(p);
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK JavaCompiler must be available");
        StringWriter diagOut = new StringWriter();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            Iterable<? extends javax.tools.JavaFileObject> units = fm.getJavaFileObjectsFromPaths(javaFiles);
            List<String> options = List.of(
                "-classpath", System.getProperty("java.class.path"),
                "-d", classesDir.toString()
            );
            boolean ok = compiler.getTask(diagOut, fm, null, options, null, units).call();
            assertTrue(ok, () -> "Generated code failed javac compilation:\n" + diagOut);
        }
    }

    // JV-9: OmniBufMapper ClassCastException on concrete LinkedList / TreeMap fields
    @Test
    void jv9_mapperUnmarshalsConcreteLinkedListAndTreeMapFields() {
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

    // JV-10: OmniBufBlind.decodeBlindJson emits scientific notation inside [1e-6, 1e21)
    @Test
    void jv10_blindJsonFormatsMidRangeFloatsWithoutScientificNotation() {
        OmniBufEncoder enc = new OmniBufEncoder();
        enc.writeFloat32(13, 0.000125f);
        enc.writeFloat64(14, 15000000.0);

        String json = OmniBufBlind.decodeBlindJson(enc.toByteArray());
        assertEquals("{\"field_13\":0.000125,\"field_14\":15000000}", json);
    }

    // JV-13: 3-arg Opaque constructor missing slice bounds validation
    @Test
    void jv13_opaqueThreeArgConstructorValidatesSliceBoundsImmediately() {
        RuntimeException thrown = assertThrows(
            RuntimeException.class,
            () -> new OpaquePeerUpdate(new byte[10], -5, 20)
        );
        assertTrue(
            thrown instanceof OmniBufException || thrown instanceof IndexOutOfBoundsException,
            () -> "Expected OmniBufException or IndexOutOfBoundsException, got: " + thrown
        );
    }

    @OmniBufMessage(name = "PrimitiveArrayPojo")
    public static final class PrimitiveArrayPojo {
        @OmniBufField(id = 1, name = "ints", wire = com.omnibuf.annotation.WireKind.SINT)
        public int[] ints;

        @OmniBufField(id = 2, name = "longs", wire = com.omnibuf.annotation.WireKind.SINT)
        public long[] longs;
    }

    // Audit §7.1: Collection ElementType / KeyType / ValueType mismatch must throw OmniBufException
    @Test
    void audit7_1_collectionHeaderTypeMismatchThrowsAcrossAllDecoders() {
        // PeerUpdate field #7 is repeated string tags; send WireArray<WireUint> instead
        OmniBufEncoder badArrEnc = new OmniBufEncoder();
        badArrEnc.writeUint32List(7, List.of(1, 2, 3));
        byte[] badArrPkt = badArrEnc.toByteArray();

        assertThrows(OmniBufException.class, () -> PeerUpdateOmniBuf.unmarshal(badArrPkt));
        assertThrows(
            OmniBufException.class,
            () -> OmniBufProtobuf.unmarshal(badArrPkt, com.omnibuf.pb.bench.PeerUpdate.getDefaultInstance())
        );
        assertThrows(
            OmniBufException.class,
            () -> OmniBufMapper.unmarshal(
                badArrPkt, com.omnibuf.conformance.ConformanceModels.AnnotatedPeerUpdate.class
            )
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
            () -> OmniBufMapper.unmarshal(badMapPkt, ConcreteCollectionsPojo.class)
        );
        assertThrows(
            OmniBufException.class,
            () -> OmniBufOpaque.readMapStringUint64(badMapPkt, 0, badMapPkt.length, 2)
        );
    }

    // Audit §7.2: OmniBufOpaque enforces exact WireFixed32/WireSfixed32 and WireFixed64/WireSfixed64 matching
    @Test
    void audit7_2_opaqueEnforcesExactFixedVsSfixedWireTypeMatching() {
        OmniBufEncoder enc = new OmniBufEncoder();
        enc.writeSfixed32(1, -12345);
        enc.writeFixed32(2, 54321);
        enc.writeSfixed64(3, -9876543210L);
        enc.writeFixed64(4, 1234567890123L);
        enc.writeFixed32List(5, WireType.WIRE_SFIXED32, List.of(-1, -2));
        enc.writeFixed64List(6, WireType.WIRE_SFIXED64, List.of(-10L, -20L));
        byte[] pkt = enc.toByteArray();

        long[] idx = OmniBufOpaque.buildFieldIndex(pkt, 0, pkt.length, 8);

        // Exact matches succeed
        assertEquals(-12345, OmniBufOpaque.readSfixed32(pkt, 0, pkt.length, 1));
        assertEquals(-12345, OmniBufOpaque.readSfixed32Indexed(idx, pkt, 0, pkt.length, 1));
        assertEquals(54321, OmniBufOpaque.readFixed32(pkt, 0, pkt.length, 2));
        assertEquals(54321, OmniBufOpaque.readFixed32Indexed(idx, pkt, 0, pkt.length, 2));
        assertEquals(-9876543210L, OmniBufOpaque.readSfixed64(pkt, 0, pkt.length, 3));
        assertEquals(-9876543210L, OmniBufOpaque.readSfixed64Indexed(idx, pkt, 0, pkt.length, 3));
        assertEquals(1234567890123L, OmniBufOpaque.readFixed64(pkt, 0, pkt.length, 4));
        assertEquals(1234567890123L, OmniBufOpaque.readFixed64Indexed(idx, pkt, 0, pkt.length, 4));

        // Swapped fixed/sfixed scalar reads return default 0 (consistent with hasField == false)
        assertFalse(OmniBufOpaque.hasField(pkt, 0, pkt.length, 1, 1 << WireType.WIRE_FIXED32));
        assertEquals(0, OmniBufOpaque.readFixed32(pkt, 0, pkt.length, 1));
        assertEquals(0, OmniBufOpaque.readFixed32Indexed(idx, pkt, 0, pkt.length, 1));
        assertFalse(OmniBufOpaque.hasField(pkt, 0, pkt.length, 2, 1 << WireType.WIRE_SFIXED32));
        assertEquals(0, OmniBufOpaque.readSfixed32(pkt, 0, pkt.length, 2));
        assertEquals(0, OmniBufOpaque.readSfixed32Indexed(idx, pkt, 0, pkt.length, 2));
        assertFalse(OmniBufOpaque.hasField(pkt, 0, pkt.length, 3, 1 << WireType.WIRE_FIXED64));
        assertEquals(0L, OmniBufOpaque.readFixed64(pkt, 0, pkt.length, 3));
        assertEquals(0L, OmniBufOpaque.readFixed64Indexed(idx, pkt, 0, pkt.length, 3));
        assertFalse(OmniBufOpaque.hasField(pkt, 0, pkt.length, 4, 1 << WireType.WIRE_SFIXED64));
        assertEquals(0L, OmniBufOpaque.readSfixed64(pkt, 0, pkt.length, 4));
        assertEquals(0L, OmniBufOpaque.readSfixed64Indexed(idx, pkt, 0, pkt.length, 4));

        // Swapped fixed/sfixed list reads throw OmniBufException
        assertEquals(List.of(-1, -2), OmniBufOpaque.readSfixed32List(pkt, 0, pkt.length, 5));
        assertThrows(OmniBufException.class, () -> OmniBufOpaque.readFixed32List(pkt, 0, pkt.length, 5));
        assertEquals(List.of(-10L, -20L), OmniBufOpaque.readSfixed64List(pkt, 0, pkt.length, 6));
        assertThrows(OmniBufException.class, () -> OmniBufOpaque.readFixed64List(pkt, 0, pkt.length, 6));
    }

    // Audit §7.3: Multi-chunk int[] / long[] decode on mutable POJO is linear and preserves all chunks
    @Test
    void audit7_3_mapperDecodesManyPrimitiveArrayChunksLinearly() {
        int chunks = 4000;
        OmniBufEncoder enc = new OmniBufEncoder(chunks * 16);
        for (int i = 0; i < chunks; i++) {
            enc.beginTypedArray(1, WireType.WIRE_SINT, 1);
            enc.writeVarint(com.omnibuf.wire.PrefixVarint.encodeZigZag32(i - 2000));
            enc.endTypedArray();
            enc.beginTypedArray(2, WireType.WIRE_SINT, 1);
            enc.writeVarint(com.omnibuf.wire.PrefixVarint.encodeZigZag64((long) i * 1000L - 500L));
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

    // Audit §7.4: Opaque oneof submessage getter resets accumulated slices across intervening sibling
    @Test
    void audit7_4_opaqueOneofSubMsgResetsAcrossInterveningSibling(@TempDir Path tempDir) throws Exception {
        DescriptorProto inner = DescriptorProto.newBuilder()
            .setName("Payload")
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("a")
                    .setNumber(1)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
            )
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("b")
                    .setNumber(2)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .setType(FieldDescriptorProto.Type.TYPE_UINT32)
            )
            .build();

        DescriptorProto holder = DescriptorProto.newBuilder()
            .setName("OneofHolder")
            .addOneofDecl(OneofDescriptorProto.newBuilder().setName("choice"))
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("sub_opt")
                    .setNumber(1)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                    .setTypeName(".audit.oneof.Payload")
                    .setOneofIndex(0)
            )
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("num_opt")
                    .setNumber(2)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .setType(FieldDescriptorProto.Type.TYPE_UINT32)
                    .setOneofIndex(0)
            )
            .build();

        FileDescriptorProto fileProto = FileDescriptorProto.newBuilder()
            .setName("oneof_submsg.proto")
            .setPackage("audit.oneof")
            .setSyntax("proto3")
            .setOptions(FileOptions.newBuilder().setJavaPackage("com.omnibuf.audit.oneof"))
            .addMessageType(inner)
            .addMessageType(holder)
            .build();

        CodeGeneratorResponse resp = ProtocGenOmniBufJava.generate(
            CodeGeneratorRequest.newBuilder()
                .addProtoFile(fileProto)
                .addFileToGenerate("oneof_submsg.proto")
                .build()
        );
        assertFalse(resp.hasError(), () -> "Unexpected plugin error: " + resp.getError());
        assertCompilesCleanly(resp, tempDir);

        // Wire: sub_opt{a="stale"} -> num_opt=99 -> sub_opt{b=42}
        OmniBufEncoder switchedEnc = new OmniBufEncoder();
        switchedEnc.beginSubMsg(1);
        switchedEnc.writeString(1, "stale");
        switchedEnc.endSubMsg();
        switchedEnc.writeUint32(2, 99);
        switchedEnc.beginSubMsg(1);
        switchedEnc.writeUint32(2, 42);
        switchedEnc.endSubMsg();
        byte[] switchedPkt = switchedEnc.toByteArray();

        // Wire: sub_opt{a="kept"} -> sub_opt{b=7} (no intervening sibling -> must merge)
        OmniBufEncoder mergedEnc = new OmniBufEncoder();
        mergedEnc.beginSubMsg(1);
        mergedEnc.writeString(1, "kept");
        mergedEnc.endSubMsg();
        mergedEnc.beginSubMsg(1);
        mergedEnc.writeUint32(2, 7);
        mergedEnc.endSubMsg();
        byte[] mergedPkt = mergedEnc.toByteArray();

        Path classesDir = tempDir.resolve("classes");
        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {classesDir.toUri().toURL()},
                AuditRegressionsTest.class.getClassLoader())) {
            Class<?> opaqueHolderCls = loader.loadClass("com.omnibuf.audit.oneof.OpaqueOneofHolder");
            Class<?> opaquePayloadCls = loader.loadClass("com.omnibuf.audit.oneof.OpaquePayload");

            Object switchedView = opaqueHolderCls.getConstructor(byte[].class).newInstance((Object) switchedPkt);
            Object subSwitched = opaqueHolderCls.getMethod("getSubOpt").invoke(switchedView);
            assertNotNull(subSwitched);
            assertEquals("", opaquePayloadCls.getMethod("getA").invoke(subSwitched));
            assertEquals(42, opaquePayloadCls.getMethod("getB").invoke(subSwitched));

            Object mergedView = opaqueHolderCls.getConstructor(byte[].class).newInstance((Object) mergedPkt);
            Object subMerged = opaqueHolderCls.getMethod("getSubOpt").invoke(mergedView);
            assertNotNull(subMerged);
            assertEquals("kept", opaquePayloadCls.getMethod("getA").invoke(subMerged));
            assertEquals(7, opaquePayloadCls.getMethod("getB").invoke(subMerged));
        }
    }

    // Audit §7.5: OmniBufOpaque lazy scalar getters throw on malformed framing / truncated trailing fields
    @Test
    void audit7_5_opaqueScalarGettersThrowOnTruncatedFraming() {
        OmniBufEncoder enc = new OmniBufEncoder();
        enc.writeUint32(1, 64512);
        enc.writeTag(2, WireType.WIRE_STRING);
        enc.writeVarint(50); // truncated string claiming 50 bytes
        byte[] truncated = enc.toByteArray();

        OpaquePeerUpdate view = new OpaquePeerUpdate(truncated);
        assertThrows(OmniBufException.class, view::getAsn);
        assertThrows(OmniBufException.class, view::getRouterId);
        assertThrows(
            OmniBufException.class,
            () -> OmniBufOpaque.buildFieldIndex(truncated, 0, truncated.length, 16)
        );
    }

    // Audit §7.6: Empty WireMap with ValueType=WireExtended rejected by OmniBufBlind; proto2 required rejected by plugin
    @Test
    void audit7_6_blindRejectsEmptyMapWithWireExtendedValueAndPluginRejectsProto2Required() {
        // Field #1 WireMap: KeyWT=WireString(10), ValWT=WireExtended(15), Count=0 (0x01)
        OmniBufEncoder extMapEnc = new OmniBufEncoder();
        extMapEnc.writeTag(1, WireType.WIRE_MAP);
        extMapEnc.writeVarint(3);
        extMapEnc.writeRawBytes(new byte[] {WireType.WIRE_STRING, WireType.WIRE_EXTENDED, 0x01});
        byte[] extMapPkt = extMapEnc.toByteArray();
        assertThrows(OmniBufException.class, () -> OmniBufBlind.decodeBlind(extMapPkt));

        // Proto2 LABEL_REQUIRED rejected by ProtocGenOmniBufJava
        FileDescriptorProto reqFile = FileDescriptorProto.newBuilder()
            .setName("bad_required.proto")
            .setPackage("bad.req")
            .setSyntax("proto2")
            .addMessageType(
                DescriptorProto.newBuilder()
                    .setName("HasRequired")
                    .addField(
                        FieldDescriptorProto.newBuilder()
                            .setName("must_exist")
                            .setNumber(1)
                            .setLabel(FieldDescriptorProto.Label.LABEL_REQUIRED)
                            .setType(FieldDescriptorProto.Type.TYPE_UINT32)
                    )
            )
            .build();
        CodeGeneratorResponse resp = ProtocGenOmniBufJava.generate(
            CodeGeneratorRequest.newBuilder()
                .addProtoFile(reqFile)
                .addFileToGenerate("bad_required.proto")
                .build()
        );
        assertTrue(resp.hasError());
        assertEquals(0, resp.getFileCount());
    }

    // Audit Finding 1: OmniBufOpaque.spliceFieldsIntoBitSet must not allocate 64 MiB BitSet for clean/unknown max field numbers
    @Test
    void auditFinding1_spliceFieldsBitSetDoesNotAmplifyMemoryOnMaxFieldNumber() {
        int maxFieldNum = WireType.MAX_FIELD_NUMBER; // 536_870_911 (9-byte tag)
        OmniBufEncoder enc = new OmniBufEncoder();
        enc.writeUint32(maxFieldNum, 42);
        enc.writeUint32(70, 1);
        byte[] packet = enc.toByteArray();

        BitSet dirty = new BitSet();
        dirty.set(70);
        dirty.set(71);

        byte[] spliced = null;
        for (int i = 0; i < 16; i++) {
            spliced = OmniBufOpaque.spliceFields(
                packet,
                0,
                packet.length,
                dirty,
                71,
                (out, fnum) -> {
                    if (fnum == 70) {
                        out.writeUint32(70, 2);
                    } else if (fnum == 71) {
                        out.writeUint32(71, 3);
                    }
                },
                null
            );
        }

        assertNotNull(spliced);
        assertEquals(42, OmniBufOpaque.readUint32(spliced, 0, spliced.length, maxFieldNum));
        assertEquals(2, OmniBufOpaque.readUint32(spliced, 0, spliced.length, 70));
        assertEquals(3, OmniBufOpaque.readUint32(spliced, 0, spliced.length, 71));
    }

    // Audit Finding 2: Unpaired high surrogate at 2048-char chunk boundary must throw, never silently drop
    @Test
    void auditFinding2_writeLargeStringStreamedRejectsUnpairedHighSurrogateAt2048Boundary() {
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

    // Audit Finding 3: Accumulated maxCollectionElements enforced across concatenated segments in OmniBufOpaque & SchemaMeta
    @Test
    void auditFinding3_accumulatedMaxCollectionElementsEnforcedAcrossConcatenatedSegments() {
        // 1) OmniBufOpaque concatenated WireArray segments (3 + 3 = 6 elements)
        OmniBufEncoder arrEnc = new OmniBufEncoder();
        arrEnc.writeUint32List(1, List.of(10, 20, 30));
        arrEnc.writeUint32List(1, List.of(40, 50, 60));
        arrEnc.writeStringList(2, List.of("a", "b", "c"));
        arrEnc.writeStringList(2, List.of("d", "e", "f"));
        arrEnc.writeFixed32List(3, WireType.WIRE_FIXED32, List.of(1, 2, 3));
        arrEnc.writeFixed32List(3, WireType.WIRE_FIXED32, List.of(4, 5, 6));
        byte[] arrPkt = arrEnc.toByteArray();

        assertThrows(OmniBufException.class, () -> OmniBufOpaque.readUint32List(arrPkt, 0, arrPkt.length, 1, 5));
        assertEquals(List.of(10, 20, 30, 40, 50, 60), OmniBufOpaque.readUint32List(arrPkt, 0, arrPkt.length, 1, 6));
        assertThrows(OmniBufException.class, () -> OmniBufOpaque.readStringList(arrPkt, 0, arrPkt.length, 2, 5));
        assertEquals(List.of("a", "b", "c", "d", "e", "f"), OmniBufOpaque.readStringList(arrPkt, 0, arrPkt.length, 2, 6));
        assertThrows(OmniBufException.class, () -> OmniBufOpaque.readFixed32List(arrPkt, 0, arrPkt.length, 3, 5));
        assertEquals(List.of(1, 2, 3, 4, 5, 6), OmniBufOpaque.readFixed32List(arrPkt, 0, arrPkt.length, 3, 6));

        // 2) OmniBufOpaque concatenated WireMap segments (2 + 2 = 4 distinct keys)
        OmniBufEncoder mapEnc = new OmniBufEncoder();
        mapEnc.writeMapStringUint64(4, Map.of("k1", 1L, "k2", 2L), true);
        mapEnc.writeMapStringUint64(4, Map.of("k3", 3L, "k4", 4L), true);
        // Overlapping keys across segments (2 + 2 = 2 distinct keys)
        mapEnc.writeMapStringUint64(5, Map.of("dup1", 1L, "dup2", 2L), true);
        mapEnc.writeMapStringUint64(5, Map.of("dup1", 10L, "dup2", 20L), true);
        byte[] mapPkt = mapEnc.toByteArray();

        assertThrows(OmniBufException.class, () -> OmniBufOpaque.readMapStringUint64(mapPkt, 0, mapPkt.length, 4, 3));
        assertEquals(
            Map.of("k1", 1L, "k2", 2L, "k3", 3L, "k4", 4L),
            OmniBufOpaque.readMapStringUint64(mapPkt, 0, mapPkt.length, 4, 4)
        );
        assertEquals(
            Map.of("dup1", 10L, "dup2", 20L),
            OmniBufOpaque.readMapStringUint64(mapPkt, 0, mapPkt.length, 5, 2)
        );

        assertThrows(
            OmniBufException.class,
            () -> OmniBufOpaque.readMap(
                mapPkt, 0, mapPkt.length, 4,
                WireType.WIRE_STRING, WireType.WIRE_UINT,
                OmniBufDecoder::readString, OmniBufDecoder::readVarint, 3
            )
        );
        assertEquals(
            Map.of("k1", 1L, "k2", 2L, "k3", 3L, "k4", 4L),
            OmniBufOpaque.readMap(
                mapPkt, 0, mapPkt.length, 4,
                WireType.WIRE_STRING, WireType.WIRE_UINT,
                OmniBufDecoder::readString, OmniBufDecoder::readVarint, 4
            )
        );

        // 3) SchemaMeta concatenated field #2 (fields) and field #3 (children) maps
        OmniBufEncoder schemaFieldsEnc = new OmniBufEncoder();
        schemaFieldsEnc.beginTypedMap(2, WireType.WIRE_UINT, WireType.WIRE_STRING, 2);
        schemaFieldsEnc.writeVarint(1);
        schemaFieldsEnc.writeStringElement("a");
        schemaFieldsEnc.writeVarint(2);
        schemaFieldsEnc.writeStringElement("b");
        schemaFieldsEnc.endTypedMap();
        schemaFieldsEnc.beginTypedMap(2, WireType.WIRE_UINT, WireType.WIRE_STRING, 2);
        schemaFieldsEnc.writeVarint(3);
        schemaFieldsEnc.writeStringElement("c");
        schemaFieldsEnc.writeVarint(4);
        schemaFieldsEnc.writeStringElement("d");
        schemaFieldsEnc.endTypedMap();
        byte[] schemaFieldsBody = schemaFieldsEnc.toByteArray();

        assertThrows(
            OmniBufException.class,
            () -> SchemaMeta.decode(schemaFieldsBody, 0, schemaFieldsBody.length, 1, 32, 3)
        );
        SchemaMeta decodedFields = SchemaMeta.decode(schemaFieldsBody, 0, schemaFieldsBody.length, 1, 32, 4);
        assertEquals(Map.of(1, "a", 2, "b", 3, "c", 4, "d"), decodedFields.fields());

        OmniBufEncoder schemaChildrenEnc = new OmniBufEncoder();
        schemaChildrenEnc.beginTypedMap(3, WireType.WIRE_UINT, WireType.WIRE_SUB_MSG, 2);
        schemaChildrenEnc.writeVarint(1);
        schemaChildrenEnc.beginInlineSubMsg();
        schemaChildrenEnc.writeString(1, "Child1");
        schemaChildrenEnc.endInlineSubMsg();
        schemaChildrenEnc.writeVarint(2);
        schemaChildrenEnc.beginInlineSubMsg();
        schemaChildrenEnc.writeString(1, "Child2");
        schemaChildrenEnc.endInlineSubMsg();
        schemaChildrenEnc.endTypedMap();
        schemaChildrenEnc.beginTypedMap(3, WireType.WIRE_UINT, WireType.WIRE_SUB_MSG, 2);
        schemaChildrenEnc.writeVarint(3);
        schemaChildrenEnc.beginInlineSubMsg();
        schemaChildrenEnc.writeString(1, "Child3");
        schemaChildrenEnc.endInlineSubMsg();
        schemaChildrenEnc.writeVarint(4);
        schemaChildrenEnc.beginInlineSubMsg();
        schemaChildrenEnc.writeString(1, "Child4");
        schemaChildrenEnc.endInlineSubMsg();
        schemaChildrenEnc.endTypedMap();
        byte[] schemaChildrenBody = schemaChildrenEnc.toByteArray();

        assertThrows(
            OmniBufException.class,
            () -> SchemaMeta.decode(schemaChildrenBody, 0, schemaChildrenBody.length, 1, 32, 3)
        );
        SchemaMeta decodedChildren = SchemaMeta.decode(schemaChildrenBody, 0, schemaChildrenBody.length, 1, 32, 4);
        assertEquals(4, decodedChildren.children().size());
    }

    // Audit Finding 4: Utf8Validator overflow-safe bounds checks
    @Test
    void auditFinding4_utf8ValidatorHandlesTruncatedMultiByteSequencesCleanly() {
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

    // Audit Finding 5: OmniBufDecoder.readVarint4Or9 rejects 1-byte and 2-byte prefix-varints with bit 2 set
    @Test
    void auditFinding5_readVarint4Or9RequiresExact4ByteOr9BytePrefix() {
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
}
