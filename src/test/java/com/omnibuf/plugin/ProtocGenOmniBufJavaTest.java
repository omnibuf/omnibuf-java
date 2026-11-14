package com.omnibuf.plugin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileOptions;
import com.google.protobuf.DescriptorProtos.MessageOptions;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse;
import com.omnibuf.MarshalOptions;
import com.omnibuf.OmniBufBlind;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProtocGenOmniBufJavaTest {

    private static DescriptorProto mapEntry(
            String entryName,
            FieldDescriptorProto.Type keyType,
            FieldDescriptorProto.Type valType,
            String valTypeName) {
        FieldDescriptorProto.Builder valBuilder = FieldDescriptorProto.newBuilder()
            .setName("value")
            .setNumber(2)
            .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
            .setType(valType);
        if (valTypeName != null) {
            valBuilder.setTypeName(valTypeName);
        }
        return DescriptorProto.newBuilder()
            .setName(entryName)
            .setOptions(MessageOptions.newBuilder().setMapEntry(true))
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("key")
                    .setNumber(1)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .setType(keyType)
            )
            .addField(valBuilder)
            .build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void generatesCompilesAndExecutesMapsOneofsOptionalsNestedTypesKeywordsHighFieldsAndCycles(
            @TempDir Path tempDir) throws Exception {
        // Build a rich real-world proto3 FileDescriptorProto exercising:
        // - Nested messages (InnerTag) and nested enums (InnerState)
        // - All map<K, V> key types (int32, uint32, sint64, bool, fixed32, sfixed64, string)
        //   and scalar/enum/sub-message values
        // - Real oneof (choice: string, uint64, InnerTag)
        // - proto3 optional scalars (opt_zero, opt_label)
        // - Java keywords ("class", "default") and underscore-only ("__") field names
        // - High field numbers > 64 (1, 64, 65, 100) exercising BitSet Opaque dirty tracking
        // - Cyclic recursive message (RecNode) with SelfDescribing SchemaMeta across depths
        DescriptorProto innerTag = DescriptorProto.newBuilder()
            .setName("InnerTag")
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("code")
                    .setNumber(1)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
            )
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("score")
                    .setNumber(2)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .setType(FieldDescriptorProto.Type.TYPE_INT32)
            )
            .build();

        EnumDescriptorProto innerState = EnumDescriptorProto.newBuilder()
            .setName("InnerState")
            .addValue(EnumValueDescriptorProto.newBuilder().setName("INNER_IDLE").setNumber(0))
            .addValue(EnumValueDescriptorProto.newBuilder().setName("INNER_ACTIVE").setNumber(1))
            .build();

        DescriptorProto complexEvent = DescriptorProto.newBuilder()
            .setName("ComplexEvent")
            .addNestedType(innerTag)
            .addEnumType(innerState)
            .addNestedType(mapEntry("MapI32StrEntry", FieldDescriptorProto.Type.TYPE_INT32, FieldDescriptorProto.Type.TYPE_STRING, null))
            .addNestedType(mapEntry("MapU32I64Entry", FieldDescriptorProto.Type.TYPE_UINT32, FieldDescriptorProto.Type.TYPE_INT64, null))
            .addNestedType(mapEntry("MapS64BoolEntry", FieldDescriptorProto.Type.TYPE_SINT64, FieldDescriptorProto.Type.TYPE_BOOL, null))
            .addNestedType(mapEntry("MapBoolU32Entry", FieldDescriptorProto.Type.TYPE_BOOL, FieldDescriptorProto.Type.TYPE_UINT32, null))
            .addNestedType(mapEntry("MapF32MsgEntry", FieldDescriptorProto.Type.TYPE_FIXED32, FieldDescriptorProto.Type.TYPE_MESSAGE, ".gen.test.ComplexEvent.InnerTag"))
            .addNestedType(mapEntry("MapSf64EnumEntry", FieldDescriptorProto.Type.TYPE_SFIXED64, FieldDescriptorProto.Type.TYPE_ENUM, ".gen.test.ComplexEvent.InnerState"))
            .addNestedType(mapEntry("MapStrBytesEntry", FieldDescriptorProto.Type.TYPE_STRING, FieldDescriptorProto.Type.TYPE_BYTES, null))
            // Oneof declarations: index 0 = real "choice", index 1 = synthetic "_opt_zero", index 2 = synthetic "_opt_label"
            .addOneofDecl(OneofDescriptorProto.newBuilder().setName("choice"))
            .addOneofDecl(OneofDescriptorProto.newBuilder().setName("_opt_zero"))
            .addOneofDecl(OneofDescriptorProto.newBuilder().setName("_opt_label"))
            // Field #1: low scalar (also tests BitSet non-aliasing with field #65!)
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("low_id")
                    .setNumber(1)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .setType(FieldDescriptorProto.Type.TYPE_UINT32)
            )
            // Maps #2..#8
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("map_i32_str")
                    .setNumber(2)
                    .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                    .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                    .setTypeName(".gen.test.ComplexEvent.MapI32StrEntry")
            )
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("map_u32_i64")
                    .setNumber(3)
                    .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                    .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                    .setTypeName(".gen.test.ComplexEvent.MapU32I64Entry")
            )
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("map_s64_bool")
                    .setNumber(4)
                    .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                    .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                    .setTypeName(".gen.test.ComplexEvent.MapS64BoolEntry")
            )
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("map_bool_u32")
                    .setNumber(5)
                    .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                    .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                    .setTypeName(".gen.test.ComplexEvent.MapBoolU32Entry")
            )
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("map_f32_msg")
                    .setNumber(6)
                    .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                    .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                    .setTypeName(".gen.test.ComplexEvent.MapF32MsgEntry")
            )
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("map_sf64_enum")
                    .setNumber(7)
                    .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                    .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                    .setTypeName(".gen.test.ComplexEvent.MapSf64EnumEntry")
            )
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("map_str_bytes")
                    .setNumber(8)
                    .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                    .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                    .setTypeName(".gen.test.ComplexEvent.MapStrBytesEntry")
            )
            // Oneof choice members (#10, #11, #12)
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("text_opt")
                    .setNumber(10)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
                    .setOneofIndex(0)
            )
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("num_opt")
                    .setNumber(11)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .setType(FieldDescriptorProto.Type.TYPE_UINT64)
                    .setOneofIndex(0)
            )
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("tag_opt")
                    .setNumber(12)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                    .setTypeName(".gen.test.ComplexEvent.InnerTag")
                    .setOneofIndex(0)
            )
            // proto3 optional scalars (#15, #16)
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("opt_zero")
                    .setNumber(15)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .setType(FieldDescriptorProto.Type.TYPE_INT32)
                    .setOneofIndex(1)
                    .setProto3Optional(true)
            )
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("opt_label")
                    .setNumber(16)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
                    .setOneofIndex(2)
                    .setProto3Optional(true)
            )
            // Java keywords & underscore-only names (#20, #21, #22)
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("class")
                    .setNumber(20)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
            )
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("default")
                    .setNumber(21)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .setType(FieldDescriptorProto.Type.TYPE_INT32)
            )
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("__")
                    .setNumber(22)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .setType(FieldDescriptorProto.Type.TYPE_UINT32)
            )
            // High field numbers >= 64 (#64, #65, #100)
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("f64_boundary")
                    .setNumber(64)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .setType(FieldDescriptorProto.Type.TYPE_UINT64)
            )
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("f65_above")
                    .setNumber(65)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .setType(FieldDescriptorProto.Type.TYPE_UINT64)
            )
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("f100_high")
                    .setNumber(100)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
            )
            .build();

        DescriptorProto recNode = DescriptorProto.newBuilder()
            .setName("RecNode")
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("node_name")
                    .setNumber(1)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
            )
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("child")
                    .setNumber(2)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                    .setTypeName(".gen.test.RecNode")
            )
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("items")
                    .setNumber(3)
                    .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                    .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                    .setTypeName(".gen.test.RecNode")
            )
            .build();

        FileDescriptorProto fileProto = FileDescriptorProto.newBuilder()
            .setName("real_world_schema.proto")
            .setPackage("gen.test")
            .setSyntax("proto3")
            .setOptions(FileOptions.newBuilder().setJavaPackage("com.omnibuf.gen.dynamic"))
            .addMessageType(complexEvent)
            .addMessageType(recNode)
            .build();

        CodeGeneratorRequest req = CodeGeneratorRequest.newBuilder()
            .addProtoFile(fileProto)
            .addFileToGenerate("real_world_schema.proto")
            .build();

        CodeGeneratorResponse resp = ProtocGenOmniBufJava.generate(req);
        assertFalse(resp.hasError(), () -> "Unexpected plugin error: " + resp.getError());
        assertEquals(9, resp.getFileCount()); // 3 messages (ComplexEvent, InnerTag, RecNode) * 3 files each

        // Write generated sources and compile with javac -Xlint:all,-this-escape -Werror
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
                "-d", classesDir.toString(),
                "-Xlint:all,-this-escape",
                "-Werror"
            );
            boolean ok = compiler.getTask(diagOut, fm, null, options, null, units).call();
            assertTrue(ok, () -> "Generated code failed strict javac compilation:\n" + diagOut);
        }

        // Load compiled generated classes and test round-trip + Opaque splicing
        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {classesDir.toUri().toURL()},
                ProtocGenOmniBufJavaTest.class.getClassLoader())) {
            Class<?> innerModelCls = loader.loadClass("com.omnibuf.gen.dynamic.OmniBufComplexEvent_InnerTag");
            Class<?> eventModelCls = loader.loadClass("com.omnibuf.gen.dynamic.OmniBufComplexEvent");
            Class<?> eventCodecCls = loader.loadClass("com.omnibuf.gen.dynamic.ComplexEventOmniBuf");
            Class<?> eventOpaqueCls = loader.loadClass("com.omnibuf.gen.dynamic.OpaqueComplexEvent");

            Object inner1 = innerModelCls.getConstructor().newInstance();
            innerModelCls.getMethod("setCode", String.class).invoke(inner1, "edge");
            innerModelCls.getMethod("setScore", int.class).invoke(inner1, -7);

            Object ev = eventModelCls.getConstructor().newInstance();
            eventModelCls.getMethod("setLowId", int.class).invoke(ev, 111);
            eventModelCls.getMethod("setMapI32Str", Map.class).invoke(ev, Map.of(-5, "minus-five", 9, "nine"));
            eventModelCls.getMethod("setMapU32I64", Map.class).invoke(ev, Map.of(42, -9999L));
            eventModelCls.getMethod("setMapS64Bool", Map.class).invoke(ev, Map.of(-123456789L, true, 7L, false));
            eventModelCls.getMethod("setMapBoolU32", Map.class).invoke(ev, Map.of(true, 100, false, 200));
            eventModelCls.getMethod("setMapF32Msg", Map.class).invoke(ev, Map.of(0x12345678, inner1));
            eventModelCls.getMethod("setMapSf64Enum", Map.class).invoke(ev, Map.of(-88L, 1));
            eventModelCls.getMethod("setMapStrBytes", Map.class).invoke(
                ev, Map.of("bin", new byte[] {9, 8, 7})
            );

            // Oneof mutual exclusion: set TextOpt first, then NumOpt -> TextOpt must clear!
            eventModelCls.getMethod("setTextOpt", String.class).invoke(ev, "first-branch");
            assertEquals(10, eventModelCls.getMethod("getChoiceCase").invoke(ev));
            eventModelCls.getMethod("setNumOpt", long.class).invoke(ev, 777_888L);
            assertEquals(11, eventModelCls.getMethod("getChoiceCase").invoke(ev));
            assertEquals("", eventModelCls.getMethod("getTextOpt").invoke(ev));

            // proto3 optional: explicitly set opt_zero = 0 (must serialize on wire and preserve presence!)
            assertFalse((Boolean) eventModelCls.getMethod("hasOptZero").invoke(ev));
            eventModelCls.getMethod("setOptZero", int.class).invoke(ev, 0);
            assertTrue((Boolean) eventModelCls.getMethod("hasOptZero").invoke(ev));
            assertFalse((Boolean) eventModelCls.getMethod("hasOptLabel").invoke(ev));

            // Escaped keywords & underscore-only identifiers
            eventModelCls.getMethod("setClass_", String.class).invoke(ev, "kw-class");
            eventModelCls.getMethod("setDefault", int.class).invoke(ev, -33);
            eventModelCls.getMethod("setField22", int.class).invoke(ev, 222);

            // High fields >= 64
            eventModelCls.getMethod("setF64Boundary", long.class).invoke(ev, 6400L);
            eventModelCls.getMethod("setF65Above", long.class).invoke(ev, 6500L);
            eventModelCls.getMethod("setF100High", String.class).invoke(ev, "field-hundred");

            Method marshalDet = eventCodecCls.getMethod("marshal", eventModelCls, MarshalOptions.class);
            Method unmarshal = eventCodecCls.getMethod("unmarshal", byte[].class);
            byte[] wire = (byte[]) marshalDet.invoke(null, ev, MarshalOptions.DETERMINISTIC);

            Object decoded = unmarshal.invoke(null, (Object) wire);
            assertEquals(111, eventModelCls.getMethod("getLowId").invoke(decoded));
            assertEquals(Map.of(-5, "minus-five", 9, "nine"), eventModelCls.getMethod("getMapI32Str").invoke(decoded));
            assertEquals(Map.of(42, -9999L), eventModelCls.getMethod("getMapU32I64").invoke(decoded));
            assertEquals(Map.of(-123456789L, true, 7L, false), eventModelCls.getMethod("getMapS64Bool").invoke(decoded));
            assertEquals(Map.of(true, 100, false, 200), eventModelCls.getMethod("getMapBoolU32").invoke(decoded));
            assertEquals(Map.of(-88L, 1), eventModelCls.getMethod("getMapSf64Enum").invoke(decoded));
            Map<String, byte[]> decStrBytes =
                (Map<String, byte[]>) eventModelCls.getMethod("getMapStrBytes").invoke(decoded);
            assertArrayEquals(new byte[] {9, 8, 7}, decStrBytes.get("bin"));

            assertEquals(11, eventModelCls.getMethod("getChoiceCase").invoke(decoded));
            assertEquals(777_888L, eventModelCls.getMethod("getNumOpt").invoke(decoded));
            assertTrue((Boolean) eventModelCls.getMethod("hasOptZero").invoke(decoded));
            assertEquals(0, eventModelCls.getMethod("getOptZero").invoke(decoded));
            assertFalse((Boolean) eventModelCls.getMethod("hasOptLabel").invoke(decoded));
            assertEquals("kw-class", eventModelCls.getMethod("getClass_").invoke(decoded));
            assertEquals(-33, eventModelCls.getMethod("getDefault").invoke(decoded));
            assertEquals(222, eventModelCls.getMethod("getField22").invoke(decoded));
            assertEquals(6400L, eventModelCls.getMethod("getF64Boundary").invoke(decoded));
            assertEquals(6500L, eventModelCls.getMethod("getF65Above").invoke(decoded));
            assertEquals("field-hundred", eventModelCls.getMethod("getF100High").invoke(decoded));

            // Opaque >64-field BitSet splice verification:
            // Mutate ONLY field #65 and switch oneof branch to TextOpt (#10).
            // Field #1 (low_id = 111) MUST NOT be clobbered by 1L << 65 wrap-around!
            Object opaque = eventOpaqueCls.getConstructor(byte[].class).newInstance((Object) wire);
            assertEquals(111, eventOpaqueCls.getMethod("getLowId").invoke(opaque));
            assertEquals(6500L, eventOpaqueCls.getMethod("getF65Above").invoke(opaque));
            assertTrue((Boolean) eventOpaqueCls.getMethod("hasOptZero").invoke(opaque));

            eventOpaqueCls.getMethod("setF65Above", long.class).invoke(opaque, 99999L);
            eventOpaqueCls.getMethod("setTextOpt", String.class).invoke(opaque, "switched-oneof");
            byte[] spliced = (byte[]) eventOpaqueCls.getMethod("marshalValidated").invoke(opaque);

            Object afterSplice = unmarshal.invoke(null, (Object) spliced);
            assertEquals(111, eventModelCls.getMethod("getLowId").invoke(afterSplice));
            assertEquals(6400L, eventModelCls.getMethod("getF64Boundary").invoke(afterSplice));
            assertEquals(99999L, eventModelCls.getMethod("getF65Above").invoke(afterSplice));
            assertEquals("field-hundred", eventModelCls.getMethod("getF100High").invoke(afterSplice));
            assertEquals(10, eventModelCls.getMethod("getChoiceCase").invoke(afterSplice));
            assertEquals("switched-oneof", eventModelCls.getMethod("getTextOpt").invoke(afterSplice));
            assertNull(eventModelCls.getMethod("getTagOpt").invoke(afterSplice));

            // Recursive RecNode SelfDescribing SchemaMeta across depths 0, 1, 2
            Class<?> recModelCls = loader.loadClass("com.omnibuf.gen.dynamic.OmniBufRecNode");
            Class<?> recCodecCls = loader.loadClass("com.omnibuf.gen.dynamic.RecNodeOmniBuf");

            Object leaf = recModelCls.getConstructor().newInstance();
            recModelCls.getMethod("setNodeName", String.class).invoke(leaf, "leaf-d2");

            Object mid = recModelCls.getConstructor().newInstance();
            recModelCls.getMethod("setNodeName", String.class).invoke(mid, "mid-d1");
            recModelCls.getMethod("setChild", recModelCls).invoke(mid, leaf);

            Object root = recModelCls.getConstructor().newInstance();
            recModelCls.getMethod("setNodeName", String.class).invoke(root, "root-d0");
            recModelCls.getMethod("setChild", recModelCls).invoke(root, mid);

            byte[] selfDescBytes = (byte[]) recCodecCls
                .getMethod("marshal", recModelCls, MarshalOptions.class)
                .invoke(null, root, MarshalOptions.SELF_DESCRIBING);

            Map<String, Object> blindRec = OmniBufBlind.decodeBlind(selfDescBytes);
            assertEquals("root-d0", blindRec.get("node_name"));
            Map<String, Object> blindMid = (Map<String, Object>) blindRec.get("child");
            assertEquals("mid-d1", blindMid.get("node_name"));
            Map<String, Object> blindLeaf = (Map<String, Object>) blindMid.get("child");
            assertEquals("leaf-d2", blindLeaf.get("node_name"));
        }
    }

    @Test
    void pluginReturnsCleanErrorResponseForGroupExtensionsAndNonZeroProto2Defaults() {
        // 1. TYPE_GROUP rejected via CodeGeneratorResponse.error
        FileDescriptorProto groupFile = FileDescriptorProto.newBuilder()
            .setName("bad_group.proto")
            .setPackage("bad")
            .setSyntax("proto2")
            .addMessageType(
                DescriptorProto.newBuilder()
                    .setName("WithGroup")
                    .addField(
                        FieldDescriptorProto.newBuilder()
                            .setName("grp")
                            .setNumber(1)
                            .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                            .setType(FieldDescriptorProto.Type.TYPE_GROUP)
                            .setTypeName(".bad.WithGroup.Grp")
                    )
            )
            .build();
        CodeGeneratorResponse groupResp = ProtocGenOmniBufJava.generate(
            CodeGeneratorRequest.newBuilder()
                .addProtoFile(groupFile)
                .addFileToGenerate("bad_group.proto")
                .build()
        );
        assertTrue(groupResp.hasError());
        assertEquals(0, groupResp.getFileCount());

        // 2. proto2 extension_range rejected via CodeGeneratorResponse.error
        FileDescriptorProto extFile = FileDescriptorProto.newBuilder()
            .setName("bad_ext.proto")
            .setPackage("bad")
            .setSyntax("proto2")
            .addMessageType(
                DescriptorProto.newBuilder()
                    .setName("Extendable")
                    .addExtensionRange(
                        DescriptorProto.ExtensionRange.newBuilder().setStart(10).setEnd(20)
                    )
            )
            .build();
        CodeGeneratorResponse extResp = ProtocGenOmniBufJava.generate(
            CodeGeneratorRequest.newBuilder()
                .addProtoFile(extFile)
                .addFileToGenerate("bad_ext.proto")
                .build()
        );
        assertTrue(extResp.hasError());
        assertEquals(0, extResp.getFileCount());

        // 3. Non-zero proto2 custom default_value rejected via CodeGeneratorResponse.error
        FileDescriptorProto defFile = FileDescriptorProto.newBuilder()
            .setName("bad_default.proto")
            .setPackage("bad")
            .setSyntax("proto2")
            .addMessageType(
                DescriptorProto.newBuilder()
                    .setName("CustomDefault")
                    .addField(
                        FieldDescriptorProto.newBuilder()
                            .setName("retries")
                            .setNumber(1)
                            .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                            .setType(FieldDescriptorProto.Type.TYPE_INT32)
                            .setDefaultValue("42")
                    )
            )
            .build();
        CodeGeneratorResponse defResp = ProtocGenOmniBufJava.generate(
            CodeGeneratorRequest.newBuilder()
                .addProtoFile(defFile)
                .addFileToGenerate("bad_default.proto")
                .build()
        );
        assertTrue(defResp.hasError());
        assertEquals(0, defResp.getFileCount());
    }
}
