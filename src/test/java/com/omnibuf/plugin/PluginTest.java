package com.omnibuf.plugin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileOptions;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse;
import com.omnibuf.pb.bench.OmniBufLocation;
import com.omnibuf.pb.bench.OmniBufPeerUpdate;
import com.omnibuf.pb.conformance.OmniBufTestAllTypes;
import com.omnibuf.wire.OmniBufEncoder;
import java.io.StringWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginTest {

    @Test
    void generatedEncoderMessageDoesNotShadowWireOmniBufEncoderImport(@TempDir Path tempDir)
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

    @Test
    void generatedOneofCaseFieldDoesNotCollideWithExplicitCaseField(@TempDir Path tempDir)
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

    @Test
    void generatedNestedMessageDoesNotCollideWithUnderscoreSibling(@TempDir Path tempDir)
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

    @Test
    void opaqueOneofSubMsgResetsAcrossInterveningSibling(@TempDir Path tempDir) throws Exception {
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
                PluginTest.class.getClassLoader())) {
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

    @Test
    void pluginRejectsProto2RequiredField() {
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

    @Test
    void generatedModelEqualsHashCodeAndToString() {
        OmniBufPeerUpdate a = new OmniBufPeerUpdate()
            .setAsn(64512)
            .setRouterId("rtr-1")
            .setLocation(new OmniBufLocation().setRegion("us-west").setLatitude(37.77f));
        OmniBufPeerUpdate b = new OmniBufPeerUpdate()
            .setAsn(64512)
            .setRouterId("rtr-1")
            .setLocation(new OmniBufLocation().setRegion("us-west").setLatitude(37.77f));

        // Calling getTags() lazily initializes an empty list; equals/hashCode must still match null list
        assertTrue(b.getTags().isEmpty());
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertTrue(a.toString().contains("OmniBufPeerUpdate"));
        assertTrue(a.toString().contains("rtr-1"));

        b.setAsn(64513);
        assertNotEquals(a, b);

        // byte[] content equality on OmniBufTestAllTypes
        OmniBufTestAllTypes t1 = new OmniBufTestAllTypes().setF7Bytes(new byte[] {1, 2, 3});
        OmniBufTestAllTypes t2 = new OmniBufTestAllTypes().setF7Bytes(new byte[] {1, 2, 3});
        assertEquals(t1, t2);
        assertEquals(t1.hashCode(), t2.hashCode());
        assertArrayEquals(new byte[] {1, 2, 3}, t1.getF7Bytes());
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
}
