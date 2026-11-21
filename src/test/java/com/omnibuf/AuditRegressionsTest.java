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
import com.omnibuf.wire.Utf8Validator;
import com.omnibuf.wire.WireType;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
}
