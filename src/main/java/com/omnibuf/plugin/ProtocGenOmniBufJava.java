package com.omnibuf.plugin;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code protoc-gen-omnibuf-java} code generator plugin.
 *
 * <p>Reads a {@link CodeGeneratorRequest} from {@code System.in} and emits three Java 21 source
 * files per top-level protobuf message into the file's {@code java_package}:
 * <ul>
 *   <li>{@code OmniBuf<Message>.java}: lightweight mutable data model with zero-overhead fields</li>
 *   <li>{@code <Message>OmniBuf.java}: single-pass static encoder/decoder and {@code SchemaMeta}</li>
 *   <li>{@code Opaque<Message>.java}: zero-copy lazy reader view with {@code spliceFields} mutation</li>
 * </ul>
 */
public final class ProtocGenOmniBufJava {

    private record MessageInfo(
        String protoFullName,
        String simpleName,
        String javaPackage,
        DescriptorProto descriptor,
        Map<String, DescriptorProto> mapEntries
    ) {
        String modelClass() {
            return "OmniBuf" + simpleName;
        }

        String codecClass() {
            return simpleName + "OmniBuf";
        }

        String opaqueClass() {
            return "Opaque" + simpleName;
        }

        String qualifiedModel() {
            return javaPackage.isEmpty() ? modelClass() : javaPackage + "." + modelClass();
        }

        String qualifiedCodec() {
            return javaPackage.isEmpty() ? codecClass() : javaPackage + "." + codecClass();
        }

        String qualifiedOpaque() {
            return javaPackage.isEmpty() ? opaqueClass() : javaPackage + "." + opaqueClass();
        }
    }

    private record ResolvedField(
        FieldDescriptorProto proto,
        int number,
        String protoName,
        String camelName,
        String pascalName,
        boolean isMap,
        boolean isRepeated,
        FieldDescriptorProto mapKeyProto,
        FieldDescriptorProto mapValProto,
        MessageInfo subMessage
    ) {}

    private ProtocGenOmniBufJava() {}

    public static void main(String[] args) throws IOException {
        CodeGeneratorRequest request = CodeGeneratorRequest.parseFrom(System.in);
        CodeGeneratorResponse response = generate(request);
        response.writeTo(System.out);
        System.out.flush();
    }

    public static CodeGeneratorResponse generate(CodeGeneratorRequest request) {
        CodeGeneratorResponse.Builder resp = CodeGeneratorResponse.newBuilder();
        resp.setSupportedFeatures(
            CodeGeneratorResponse.Feature.FEATURE_PROTO3_OPTIONAL_VALUE
        );

        Map<String, MessageInfo> registry = new LinkedHashMap<>();
        Set<String> targetFiles = new HashSet<>(request.getFileToGenerateList());

        for (FileDescriptorProto file : request.getProtoFileList()) {
            String protoPkg = file.getPackage();
            String javaPkg = file.getOptions().hasJavaPackage() && !file.getOptions().getJavaPackage().isEmpty()
                ? file.getOptions().getJavaPackage()
                : protoPkg;
            for (DescriptorProto msg : file.getMessageTypeList()) {
                if (msg.getOptions().getMapEntry()) {
                    continue;
                }
                String fullProtoName = protoPkg.isEmpty()
                    ? "." + msg.getName()
                    : "." + protoPkg + "." + msg.getName();
                Map<String, DescriptorProto> mapEntries = new HashMap<>();
                for (DescriptorProto nested : msg.getNestedTypeList()) {
                    if (nested.getOptions().getMapEntry()) {
                        mapEntries.put(fullProtoName + "." + nested.getName(), nested);
                    }
                }
                registry.put(
                    fullProtoName,
                    new MessageInfo(fullProtoName, msg.getName(), javaPkg, msg, mapEntries)
                );
            }
        }

        for (FileDescriptorProto file : request.getProtoFileList()) {
            if (!targetFiles.contains(file.getName())) {
                continue;
            }
            String protoPkg = file.getPackage();
            for (DescriptorProto msg : file.getMessageTypeList()) {
                if (msg.getOptions().getMapEntry()) {
                    continue;
                }
                String fullProtoName = protoPkg.isEmpty()
                    ? "." + msg.getName()
                    : "." + protoPkg + "." + msg.getName();
                MessageInfo info = registry.get(fullProtoName);
                List<ResolvedField> fields = resolveFields(info, registry);
                String dirPrefix = info.javaPackage().isEmpty()
                    ? ""
                    : info.javaPackage().replace('.', '/') + "/";

                resp.addFile(
                    CodeGeneratorResponse.File.newBuilder()
                        .setName(dirPrefix + info.modelClass() + ".java")
                        .setContent(emitModelFile(file.getName(), info, fields))
                        .build()
                );
                resp.addFile(
                    CodeGeneratorResponse.File.newBuilder()
                        .setName(dirPrefix + info.codecClass() + ".java")
                        .setContent(emitCodecFile(file.getName(), info, fields))
                        .build()
                );
                resp.addFile(
                    CodeGeneratorResponse.File.newBuilder()
                        .setName(dirPrefix + info.opaqueClass() + ".java")
                        .setContent(emitOpaqueFile(file.getName(), info, fields))
                        .build()
                );
            }
        }

        return resp.build();
    }

    private static List<ResolvedField> resolveFields(
            MessageInfo info, Map<String, MessageInfo> registry) {
        List<ResolvedField> out = new ArrayList<>();
        for (FieldDescriptorProto fd : info.descriptor().getFieldList()) {
            int number = fd.getNumber();
            String protoName = fd.getName();
            String camel = snakeToCamel(protoName);
            String pascal = Character.toUpperCase(camel.charAt(0)) + camel.substring(1);
            boolean repeated = fd.getLabel() == Label.LABEL_REPEATED;

            if (repeated && fd.getType() == Type.TYPE_MESSAGE) {
                DescriptorProto mapEntry = info.mapEntries().get(fd.getTypeName());
                if (mapEntry != null) {
                    FieldDescriptorProto kf = mapEntry.getField(0);
                    FieldDescriptorProto vf = mapEntry.getField(1);
                    MessageInfo valSub = vf.getType() == Type.TYPE_MESSAGE
                        ? registry.get(vf.getTypeName())
                        : null;
                    out.add(new ResolvedField(
                        fd, number, protoName, camel, pascal, true, false, kf, vf, valSub
                    ));
                    continue;
                }
            }

            MessageInfo sub = fd.getType() == Type.TYPE_MESSAGE
                ? registry.get(fd.getTypeName())
                : null;
            out.add(new ResolvedField(
                fd, number, protoName, camel, pascal, false, repeated, null, null, sub
            ));
        }
        return out;
    }

    // ========================================================================
    // 1. Mutable Model Class (OmniBuf<Message>.java)
    // ========================================================================

    private static String emitModelFile(
            String sourceProto, MessageInfo info, List<ResolvedField> fields) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("// Generated by protoc-gen-omnibuf-java. DO NOT EDIT.\n");
        sb.append("// source: ").append(sourceProto).append("\n\n");
        if (!info.javaPackage().isEmpty()) {
            sb.append("package ").append(info.javaPackage()).append(";\n\n");
        }
        sb.append("import com.omnibuf.MarshalOptions;\n");
        sb.append("import java.util.ArrayList;\n");
        sb.append("import java.util.LinkedHashMap;\n");
        sb.append("import java.util.List;\n");
        sb.append("import java.util.Map;\n\n");

        sb.append("public final class ").append(info.modelClass()).append(" {\n");
        sb.append("    private static final byte[] EMPTY_BYTES = new byte[0];\n\n");

        for (ResolvedField f : fields) {
            sb.append("    private ")
                .append(javaFieldType(f))
                .append(' ')
                .append(f.camelName())
                .append(" = ")
                .append(javaDefaultInit(f))
                .append(";\n");
        }
        sb.append('\n');

        sb.append("    public ").append(info.modelClass()).append("() {}\n\n");

        sb.append("    public void reset() {\n");
        for (ResolvedField f : fields) {
            if (f.isMap()) {
                sb.append("        this.").append(f.camelName()).append(" = new LinkedHashMap<>();\n");
            } else if (f.isRepeated()) {
                sb.append("        this.").append(f.camelName()).append(" = new ArrayList<>();\n");
            } else {
                sb.append("        this.")
                    .append(f.camelName())
                    .append(" = ")
                    .append(javaDefaultInit(f))
                    .append(";\n");
            }
        }
        sb.append("    }\n\n");

        for (ResolvedField f : fields) {
            String fType = javaFieldType(f);
            sb.append("    public ").append(fType).append(" get").append(f.pascalName()).append("() {\n");
            sb.append("        return this.").append(f.camelName()).append(";\n");
            sb.append("    }\n\n");

            sb.append("    public ")
                .append(info.modelClass())
                .append(" set")
                .append(f.pascalName())
                .append('(')
                .append(fType)
                .append(" value) {\n");
            if (f.proto().getType() == Type.TYPE_STRING && !f.isRepeated() && !f.isMap()) {
                sb.append("        this.").append(f.camelName()).append(" = value == null ? \"\" : value;\n");
            } else if (f.proto().getType() == Type.TYPE_BYTES && !f.isRepeated() && !f.isMap()) {
                sb.append("        this.").append(f.camelName()).append(" = value == null ? EMPTY_BYTES : value;\n");
            } else if (f.isRepeated()) {
                sb.append("        this.").append(f.camelName()).append(" = value == null ? new ArrayList<>() : value;\n");
            } else if (f.isMap()) {
                sb.append("        this.").append(f.camelName()).append(" = value == null ? new LinkedHashMap<>() : value;\n");
            } else {
                sb.append("        this.").append(f.camelName()).append(" = value;\n");
            }
            sb.append("        return this;\n");
            sb.append("    }\n\n");
        }

        sb.append("    public byte[] marshal() {\n");
        sb.append("        return ").append(info.codecClass()).append(".marshal(this, MarshalOptions.DEFAULT);\n");
        sb.append("    }\n\n");

        sb.append("    public byte[] marshal(MarshalOptions options) {\n");
        sb.append("        return ").append(info.codecClass()).append(".marshal(this, options);\n");
        sb.append("    }\n");

        sb.append("}\n");
        return sb.toString();
    }

    // ========================================================================
    // 2. Static Fast Codec (<Message>OmniBuf.java)
    // ========================================================================

    private static String emitCodecFile(
            String sourceProto, MessageInfo info, List<ResolvedField> fields) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("// Generated by protoc-gen-omnibuf-java. DO NOT EDIT.\n");
        sb.append("// source: ").append(sourceProto).append("\n\n");
        if (!info.javaPackage().isEmpty()) {
            sb.append("package ").append(info.javaPackage()).append(";\n\n");
        }
        sb.append("import com.omnibuf.MarshalOptions;\n");
        sb.append("import com.omnibuf.OmniBufException;\n");
        sb.append("import com.omnibuf.SchemaMeta;\n");
        sb.append("import com.omnibuf.wire.OmniBufDecoder;\n");
        sb.append("import com.omnibuf.wire.OmniBufEncoder;\n");
        sb.append("import com.omnibuf.wire.WireType;\n");
        sb.append("import java.util.LinkedHashMap;\n");
        sb.append("import java.util.List;\n");
        sb.append("import java.util.Map;\n\n");

        sb.append("public final class ").append(info.codecClass()).append(" {\n");
        sb.append("    private static final ThreadLocal<OmniBufEncoder> ENCODER_POOL =\n");
        sb.append("        ThreadLocal.withInitial(() -> new OmniBufEncoder(512));\n\n");

        sb.append("    private static final SchemaMeta SCHEMA_META = buildSchemaMeta();\n");
        sb.append("    private static final byte[] FIELD0_BYTES = SCHEMA_META.encodeField0(true);\n\n");

        sb.append("    private ").append(info.codecClass()).append("() {}\n\n");

        sb.append("    private static SchemaMeta buildSchemaMeta() {\n");
        sb.append("        Map<Integer, String> fieldNames = new LinkedHashMap<>(").append(fields.size()).append(");\n");
        for (ResolvedField f : fields) {
            sb.append("        fieldNames.put(")
                .append(f.number())
                .append(", \"")
                .append(f.protoName())
                .append("\");\n");
        }
        sb.append("        Map<Integer, SchemaMeta> children = new LinkedHashMap<>();\n");
        for (ResolvedField f : fields) {
            if (f.subMessage() != null && !f.subMessage().protoFullName().equals(info.protoFullName())) {
                sb.append("        children.put(")
                    .append(f.number())
                    .append(", ")
                    .append(f.subMessage().qualifiedCodec())
                    .append(".schemaMeta());\n");
            }
        }
        sb.append("        return new SchemaMeta(\"").append(info.simpleName()).append("\", fieldNames, children);\n");
        sb.append("    }\n\n");

        sb.append("    public static SchemaMeta schemaMeta() {\n");
        sb.append("        return SCHEMA_META;\n");
        sb.append("    }\n\n");

        sb.append("    public static byte[] marshal(").append(info.modelClass()).append(" msg) {\n");
        sb.append("        return marshal(msg, MarshalOptions.DEFAULT);\n");
        sb.append("    }\n\n");

        sb.append("    public static byte[] marshal(")
            .append(info.modelClass())
            .append(" msg, MarshalOptions options) {\n");
        sb.append("        if (msg == null) {\n");
        sb.append("            throw new OmniBufException(\"omnibuf: cannot marshal null ")
            .append(info.simpleName())
            .append("\");\n");
        sb.append("        }\n");
        sb.append("        MarshalOptions opts = options == null ? MarshalOptions.DEFAULT : options;\n");
        sb.append("        OmniBufEncoder enc = ENCODER_POOL.get();\n");
        sb.append("        enc.reset();\n");
        sb.append("        if (opts.selfDescribing()) {\n");
        sb.append("            if (opts.deterministic()) {\n");
        sb.append("                enc.writeRawBytes(FIELD0_BYTES);\n");
        sb.append("            } else {\n");
        sb.append("                SCHEMA_META.writeField0(enc, false);\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("        encode(enc, msg, opts.deterministic());\n");
        sb.append("        return enc.toByteArray();\n");
        sb.append("    }\n\n");

        sb.append("    public static void encode(OmniBufEncoder enc, ")
            .append(info.modelClass())
            .append(" msg, boolean deterministic) {\n");
        for (ResolvedField f : fields) {
            emitFieldEncode(sb, f, "msg.get" + f.pascalName() + "()", "        ");
        }
        sb.append("    }\n\n");

        sb.append("    public static ").append(info.modelClass()).append(" unmarshal(byte[] data) {\n");
        sb.append("        if (data == null) {\n");
        sb.append("            throw new OmniBufException(\"omnibuf: null packet buffer\");\n");
        sb.append("        }\n");
        sb.append("        ").append(info.modelClass()).append(" out = new ").append(info.modelClass()).append("();\n");
        sb.append("        decodeInto(data, 0, data.length, out, 0);\n");
        sb.append("        return out;\n");
        sb.append("    }\n\n");

        sb.append("    public static void unmarshal(byte[] data, ").append(info.modelClass()).append(" target) {\n");
        sb.append("        if (data == null) {\n");
        sb.append("            throw new OmniBufException(\"omnibuf: null packet buffer\");\n");
        sb.append("        }\n");
        sb.append("        if (target == null) {\n");
        sb.append("            throw new OmniBufException(\"omnibuf: null target instance\");\n");
        sb.append("        }\n");
        sb.append("        target.reset();\n");
        sb.append("        decodeInto(data, 0, data.length, target, 0);\n");
        sb.append("    }\n\n");

        sb.append("    public static void decodeInto(\n");
        sb.append("            byte[] data, int offset, int length, ")
            .append(info.modelClass())
            .append(" target, int depth) {\n");
        sb.append("        if (depth > OmniBufDecoder.MAX_CONTAINER_DEPTH) {\n");
        sb.append("            throw new OmniBufException(\"omnibuf: nesting depth exceeds MaxNestingDepth\");\n");
        sb.append("        }\n");
        sb.append("        OmniBufDecoder dec = new OmniBufDecoder(data, offset, length);\n");
        sb.append("        while (dec.hasRemaining()) {\n");
        sb.append("            long tag = dec.readTag();\n");
        sb.append("            int fnum = (int) (tag >>> 4);\n");
        sb.append("            int wt = (int) (tag & 0x0FL);\n");
        sb.append("            switch (fnum) {\n");
        sb.append("                case 0 -> dec.skipOrValidateUnknown(0, wt, depth);\n");
        for (ResolvedField f : fields) {
            emitFieldDecodeCase(sb, f);
        }
        sb.append("                default -> dec.skipOrValidateUnknown(fnum, wt, depth);\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("    }\n");

        sb.append("}\n");
        return sb.toString();
    }

    private static void emitFieldEncode(
            StringBuilder sb, ResolvedField f, String expr, String indent) {
        int fnum = f.number();
        if (f.isMap()) {
            // Currently generated map helper supports map<string, uint64> directly and generic scalar maps.
            if (f.mapKeyProto().getType() == Type.TYPE_STRING
                    && f.mapValProto().getType() == Type.TYPE_UINT64) {
                sb.append(indent)
                    .append("enc.writeMapStringUint64(")
                    .append(fnum)
                    .append(", ")
                    .append(expr)
                    .append(", deterministic);\n");
                return;
            }
            throw new IllegalStateException("Unsupported map signature in protoc-gen-omnibuf-java: " + f.protoName());
        }

        if (f.isRepeated()) {
            switch (f.proto().getType()) {
                case TYPE_STRING -> sb.append(indent)
                    .append("enc.writeStringList(").append(fnum).append(", ").append(expr).append(");\n");
                case TYPE_BYTES -> sb.append(indent)
                    .append("enc.writeBytesList(").append(fnum).append(", ").append(expr).append(");\n");
                case TYPE_UINT32 -> sb.append(indent)
                    .append("enc.writeUint32List(").append(fnum).append(", ").append(expr).append(");\n");
                case TYPE_UINT64 -> sb.append(indent)
                    .append("enc.writeUint64List(").append(fnum).append(", ").append(expr).append(");\n");
                case TYPE_INT32, TYPE_SINT32, TYPE_ENUM -> sb.append(indent)
                    .append("enc.writeSint32List(").append(fnum).append(", ").append(expr).append(");\n");
                case TYPE_INT64, TYPE_SINT64 -> sb.append(indent)
                    .append("enc.writeSint64List(").append(fnum).append(", ").append(expr).append(");\n");
                case TYPE_BOOL -> sb.append(indent)
                    .append("enc.writeBoolList(").append(fnum).append(", ").append(expr).append(");\n");
                case TYPE_FIXED32 -> sb.append(indent)
                    .append("enc.writeFixed32List(").append(fnum).append(", WireType.WIRE_FIXED32, ").append(expr).append(");\n");
                case TYPE_SFIXED32 -> sb.append(indent)
                    .append("enc.writeFixed32List(").append(fnum).append(", WireType.WIRE_SFIXED32, ").append(expr).append(");\n");
                case TYPE_FIXED64 -> sb.append(indent)
                    .append("enc.writeFixed64List(").append(fnum).append(", WireType.WIRE_FIXED64, ").append(expr).append(");\n");
                case TYPE_SFIXED64 -> sb.append(indent)
                    .append("enc.writeFixed64List(").append(fnum).append(", WireType.WIRE_SFIXED64, ").append(expr).append(");\n");
                case TYPE_FLOAT -> sb.append(indent)
                    .append("enc.writeFloat32List(").append(fnum).append(", ").append(expr).append(");\n");
                case TYPE_DOUBLE -> sb.append(indent)
                    .append("enc.writeFloat64List(").append(fnum).append(", ").append(expr).append(");\n");
                case TYPE_MESSAGE -> {
                    String subModel = f.subMessage().qualifiedModel();
                    String subCodec = f.subMessage().qualifiedCodec();
                    String varName = "list" + fnum;
                    sb.append(indent).append("{\n");
                    sb.append(indent).append("    List<").append(subModel).append("> ").append(varName)
                        .append(" = ").append(expr).append(";\n");
                    sb.append(indent).append("    int n = ").append(varName).append(" == null ? 0 : ").append(varName).append(".size();\n");
                    sb.append(indent).append("    enc.beginSubMsgArr(").append(fnum).append(");\n");
                    sb.append(indent).append("    for (int i = 0; i < n; i++) {\n");
                    sb.append(indent).append("        enc.beginSubMsgElem();\n");
                    sb.append(indent).append("        ").append(subModel).append(" elem = ").append(varName).append(".get(i);\n");
                    sb.append(indent).append("        if (elem != null) {\n");
                    sb.append(indent).append("            ").append(subCodec).append(".encode(enc, elem, deterministic);\n");
                    sb.append(indent).append("        }\n");
                    sb.append(indent).append("        enc.endSubMsgElem();\n");
                    sb.append(indent).append("    }\n");
                    sb.append(indent).append("    enc.endSubMsgArr();\n");
                    sb.append(indent).append("}\n");
                }
                default -> throw new IllegalStateException("Unsupported repeated field: " + f.protoName());
            }
            return;
        }

        switch (f.proto().getType()) {
            case TYPE_UINT32 -> sb.append(indent).append("enc.writeUint32(").append(fnum).append(", ").append(expr).append(");\n");
            case TYPE_UINT64 -> sb.append(indent).append("enc.writeUint(").append(fnum).append(", ").append(expr).append(");\n");
            case TYPE_INT32, TYPE_SINT32, TYPE_ENUM -> sb.append(indent).append("enc.writeSint32(").append(fnum).append(", ").append(expr).append(");\n");
            case TYPE_INT64, TYPE_SINT64 -> sb.append(indent).append("enc.writeSint(").append(fnum).append(", ").append(expr).append(");\n");
            case TYPE_BOOL -> sb.append(indent).append("enc.writeBool(").append(fnum).append(", ").append(expr).append(");\n");
            case TYPE_FIXED32 -> sb.append(indent).append("enc.writeFixed32(").append(fnum).append(", ").append(expr).append(");\n");
            case TYPE_SFIXED32 -> sb.append(indent).append("enc.writeSfixed32(").append(fnum).append(", ").append(expr).append(");\n");
            case TYPE_FIXED64 -> sb.append(indent).append("enc.writeFixed64(").append(fnum).append(", ").append(expr).append(");\n");
            case TYPE_SFIXED64 -> sb.append(indent).append("enc.writeSfixed64(").append(fnum).append(", ").append(expr).append(");\n");
            case TYPE_FLOAT -> sb.append(indent).append("enc.writeFloat32(").append(fnum).append(", ").append(expr).append(");\n");
            case TYPE_DOUBLE -> sb.append(indent).append("enc.writeFloat64(").append(fnum).append(", ").append(expr).append(");\n");
            case TYPE_STRING -> sb.append(indent).append("enc.writeString(").append(fnum).append(", ").append(expr).append(");\n");
            case TYPE_BYTES -> sb.append(indent).append("enc.writeBytes(").append(fnum).append(", ").append(expr).append(");\n");
            case TYPE_MESSAGE -> {
                String subModel = f.subMessage().qualifiedModel();
                String subCodec = f.subMessage().qualifiedCodec();
                String varName = "sub" + fnum;
                sb.append(indent).append("{\n");
                sb.append(indent).append("    ").append(subModel).append(' ').append(varName).append(" = ").append(expr).append(";\n");
                sb.append(indent).append("    if (").append(varName).append(" != null) {\n");
                sb.append(indent).append("        enc.beginSubMsg(").append(fnum).append(");\n");
                sb.append(indent).append("        ").append(subCodec).append(".encode(enc, ").append(varName).append(", deterministic);\n");
                sb.append(indent).append("        enc.endSubMsg();\n");
                sb.append(indent).append("    }\n");
                sb.append(indent).append("}\n");
            }
            default -> throw new IllegalStateException("Unsupported singular field: " + f.protoName());
        }
    }

    private static void emitFieldDecodeCase(StringBuilder sb, ResolvedField f) {
        int fnum = f.number();
        sb.append("                case ").append(fnum).append(" -> {\n");

        if (f.isMap()) {
            sb.append("                    if (wt != WireType.WIRE_MAP) {\n");
            sb.append("                        dec.skipOrValidateUnknown(fnum, wt, depth);\n");
            sb.append("                    } else {\n");
            sb.append("                        if (depth + 1 > OmniBufDecoder.MAX_CONTAINER_DEPTH) {\n");
            sb.append("                            throw new OmniBufException(\"omnibuf: WireMap exceeds MaxNestingDepth\");\n");
            sb.append("                        }\n");
            sb.append("                        int valStart = dec.position();\n");
            sb.append("                        OmniBufDecoder.Slice body = dec.readLengthPrefixedSlice(3);\n");
            sb.append("                        OmniBufDecoder.MapHeader hdr = OmniBufDecoder.parseMapHeader(data, body.offset(), body.end());\n");
            sb.append("                        if (hdr.keyWireType() != WireType.WIRE_STRING || hdr.valWireType() != WireType.WIRE_UINT) {\n");
            sb.append("                            OmniBufDecoder.validateValue(data, valStart, dec.limit(), WireType.WIRE_MAP, depth);\n");
            sb.append("                        } else {\n");
            sb.append("                            OmniBufDecoder mDec = new OmniBufDecoder(data, hdr.payloadStart(), hdr.payloadEnd() - hdr.payloadStart());\n");
            sb.append("                            Map<String, Long> map = target.get").append(f.pascalName()).append("();\n");
            sb.append("                            for (int i = 0; i < hdr.count(); i++) {\n");
            sb.append("                                String k = mDec.readString();\n");
            sb.append("                                long v = mDec.readUint64();\n");
            sb.append("                                map.put(k, v);\n");
            sb.append("                            }\n");
            sb.append("                            if (mDec.hasRemaining()) {\n");
            sb.append("                                throw new OmniBufException(\"omnibuf: WireMap has trailing payload bytes\");\n");
            sb.append("                            }\n");
            sb.append("                        }\n");
            sb.append("                    }\n");
            sb.append("                }\n");
            return;
        }

        if (f.isRepeated()) {
            String elemWireConst = wireTypeConst(f.proto().getType());
            sb.append("                    if (wt != WireType.WIRE_ARRAY) {\n");
            sb.append("                        dec.skipOrValidateUnknown(fnum, wt, depth);\n");
            sb.append("                    } else {\n");
            sb.append("                        if (depth + 1 > OmniBufDecoder.MAX_CONTAINER_DEPTH) {\n");
            sb.append("                            throw new OmniBufException(\"omnibuf: WireArray exceeds MaxNestingDepth\");\n");
            sb.append("                        }\n");
            sb.append("                        int valStart = dec.position();\n");
            sb.append("                        OmniBufDecoder.Slice body = dec.readLengthPrefixedSlice(2);\n");
            sb.append("                        OmniBufDecoder.ArrayHeader hdr = OmniBufDecoder.parseArrayHeader(data, body.offset(), body.end());\n");
            sb.append("                        if (hdr.elemWireType() != ").append(elemWireConst).append(") {\n");
            sb.append("                            OmniBufDecoder.validateValue(data, valStart, dec.limit(), WireType.WIRE_ARRAY, depth);\n");
            sb.append("                        } else {\n");
            sb.append("                            OmniBufDecoder aDec = new OmniBufDecoder(data, hdr.payloadStart(), hdr.payloadEnd() - hdr.payloadStart());\n");
            sb.append("                            var list = target.get").append(f.pascalName()).append("();\n");
            sb.append("                            for (int i = 0; i < hdr.count(); i++) {\n");
            if (f.proto().getType() == Type.TYPE_MESSAGE) {
                String subModel = f.subMessage().qualifiedModel();
                String subCodec = f.subMessage().qualifiedCodec();
                sb.append("                                OmniBufDecoder.Slice es = aDec.readLengthPrefixedSlice(0);\n");
                sb.append("                                ").append(subModel).append(" elem = new ").append(subModel).append("();\n");
                sb.append("                                ").append(subCodec).append(".decodeInto(data, es.offset(), es.length(), elem, depth + 2);\n");
                sb.append("                                list.add(elem);\n");
            } else {
                sb.append("                                list.add(").append(arrayElementReadExpr(f.proto().getType())).append(");\n");
            }
            sb.append("                            }\n");
            sb.append("                            if (aDec.hasRemaining()) {\n");
            sb.append("                                throw new OmniBufException(\"omnibuf: WireArray has trailing payload bytes\");\n");
            sb.append("                            }\n");
            sb.append("                        }\n");
            sb.append("                    }\n");
            sb.append("                }\n");
            return;
        }

        if (f.proto().getType() == Type.TYPE_BOOL) {
            sb.append("                    if (wt == WireType.WIRE_BOOL_FALSE || wt == WireType.WIRE_BOOL_TRUE) {\n");
            sb.append("                        target.set").append(f.pascalName()).append("(wt == WireType.WIRE_BOOL_TRUE);\n");
            sb.append("                    } else {\n");
            sb.append("                        dec.skipOrValidateUnknown(fnum, wt, depth);\n");
            sb.append("                    }\n");
            sb.append("                }\n");
            return;
        }

        if (f.proto().getType() == Type.TYPE_MESSAGE) {
            String subModel = f.subMessage().qualifiedModel();
            String subCodec = f.subMessage().qualifiedCodec();
            sb.append("                    if (wt == WireType.WIRE_SUB_MSG) {\n");
            sb.append("                        OmniBufDecoder.Slice s = dec.readLengthPrefixedSlice(0);\n");
            sb.append("                        ").append(subModel).append(" sub = target.get").append(f.pascalName()).append("();\n");
            sb.append("                        if (sub == null) {\n");
            sb.append("                            sub = new ").append(subModel).append("();\n");
            sb.append("                            target.set").append(f.pascalName()).append("(sub);\n");
            sb.append("                        }\n");
            sb.append("                        ").append(subCodec).append(".decodeInto(data, s.offset(), s.length(), sub, depth + 1);\n");
            sb.append("                    } else {\n");
            sb.append("                        dec.skipOrValidateUnknown(fnum, wt, depth);\n");
            sb.append("                    }\n");
            sb.append("                }\n");
            return;
        }

        String wtConst = wireTypeConst(f.proto().getType());
        sb.append("                    if (wt == ").append(wtConst).append(") {\n");
        sb.append("                        target.set")
            .append(f.pascalName())
            .append('(')
            .append(singularReadExpr(f.proto().getType()))
            .append(");\n");
        sb.append("                    } else {\n");
        sb.append("                        dec.skipOrValidateUnknown(fnum, wt, depth);\n");
        sb.append("                    }\n");
        sb.append("                }\n");
    }

    // ========================================================================
    // 3. Opaque Lazy View & Splicer (Opaque<Message>.java)
    // ========================================================================

    private static String emitOpaqueFile(
            String sourceProto, MessageInfo info, List<ResolvedField> fields) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("// Generated by protoc-gen-omnibuf-java. DO NOT EDIT.\n");
        sb.append("// source: ").append(sourceProto).append("\n\n");
        if (!info.javaPackage().isEmpty()) {
            sb.append("package ").append(info.javaPackage()).append(";\n\n");
        }
        sb.append("import com.omnibuf.wire.OmniBufEncoder;\n");
        sb.append("import com.omnibuf.wire.OmniBufOpaque;\n");
        sb.append("import com.omnibuf.wire.WireType;\n");
        sb.append("import java.util.ArrayList;\n");
        sb.append("import java.util.Collections;\n");
        sb.append("import java.util.LinkedHashMap;\n");
        sb.append("import java.util.List;\n");
        sb.append("import java.util.Map;\n\n");

        int maxField = 0;
        for (ResolvedField f : fields) {
            if (f.number() > maxField) {
                maxField = f.number();
            }
        }

        sb.append("public final class ").append(info.opaqueClass()).append(" {\n");
        sb.append("    private static final byte[] EMPTY_BYTES = new byte[0];\n");
        sb.append("    private static final int MAX_FIELD_NUMBER = ").append(maxField).append(";\n\n");

        sb.append("    private final byte[] buffer;\n");
        sb.append("    private final int offset;\n");
        sb.append("    private final int length;\n");
        sb.append("    private long dirtyMask;\n\n");

        for (ResolvedField f : fields) {
            sb.append("    private ").append(javaFieldType(f)).append(" dirty_").append(f.camelName()).append(";\n");
        }
        sb.append('\n');

        sb.append("    public ").append(info.opaqueClass()).append("(byte[] packet) {\n");
        sb.append("        this.buffer = packet == null ? EMPTY_BYTES : packet;\n");
        sb.append("        this.offset = 0;\n");
        sb.append("        this.length = this.buffer.length;\n");
        sb.append("    }\n\n");

        sb.append("    public ").append(info.opaqueClass()).append("(byte[] buffer, int offset, int length) {\n");
        sb.append("        this.buffer = buffer == null ? EMPTY_BYTES : buffer;\n");
        sb.append("        this.offset = offset;\n");
        sb.append("        this.length = length;\n");
        sb.append("    }\n\n");

        sb.append("    public ").append(info.opaqueClass()).append("(OmniBufOpaque.PacketView view) {\n");
        sb.append("        this(view.buffer(), view.offset(), view.length());\n");
        sb.append("    }\n\n");

        sb.append("    public boolean hasDirtyFields() {\n");
        sb.append("        return dirtyMask != 0L;\n");
        sb.append("    }\n\n");

        for (ResolvedField f : fields) {
            emitOpaqueGetterAndSetter(sb, info, f);
        }

        sb.append("    public byte[] marshal() {\n");
        sb.append("        return marshalInto(null);\n");
        sb.append("    }\n\n");

        sb.append("    public byte[] marshalInto(OmniBufEncoder target) {\n");
        sb.append("        return OmniBufOpaque.spliceFields(\n");
        sb.append("            buffer, offset, length, dirtyMask, MAX_FIELD_NUMBER, this::writeDirtyField, target\n");
        sb.append("        );\n");
        sb.append("    }\n\n");

        sb.append("    private void writeDirtyField(OmniBufEncoder enc, int fieldNumber) {\n");
        sb.append("        boolean deterministic = true;\n");
        sb.append("        switch (fieldNumber) {\n");
        for (ResolvedField f : fields) {
            sb.append("            case ").append(f.number()).append(" -> {\n");
            emitFieldEncode(sb, f, "this.dirty_" + f.camelName(), "                ");
            sb.append("            }\n");
        }
        sb.append("            default -> {}\n");
        sb.append("        }\n");
        sb.append("    }\n");

        sb.append("}\n");
        return sb.toString();
    }

    private static void emitOpaqueGetterAndSetter(
            StringBuilder sb, MessageInfo info, ResolvedField f) {
        int fnum = f.number();
        long bit = 1L << fnum;
        String bitLit = "0x" + Long.toHexString(bit) + "L";

        if (f.isMap()) {
            sb.append("    public Map<String, Long> get").append(f.pascalName()).append("() {\n");
            sb.append("        if ((dirtyMask & ").append(bitLit).append(") != 0L) {\n");
            sb.append("            return dirty_").append(f.camelName()).append(";\n");
            sb.append("        }\n");
            sb.append("        return OmniBufOpaque.readMapStringUint64(buffer, offset, length, ").append(fnum).append(");\n");
            sb.append("    }\n\n");

            sb.append("    public ").append(info.opaqueClass()).append(" set").append(f.pascalName())
                .append("(Map<String, Long> value) {\n");
            sb.append("        this.dirty_").append(f.camelName())
                .append(" = value == null ? new LinkedHashMap<>() : value;\n");
            sb.append("        this.dirtyMask |= ").append(bitLit).append(";\n");
            sb.append("        return this;\n");
            sb.append("    }\n\n");
            return;
        }

        if (f.isRepeated()) {
            if (f.proto().getType() == Type.TYPE_MESSAGE) {
                String subOpaque = f.subMessage().qualifiedOpaque();
                String subModel = f.subMessage().qualifiedModel();
                String subCodec = f.subMessage().qualifiedCodec();
                sb.append("    public List<").append(subOpaque).append("> get").append(f.pascalName()).append("() {\n");
                sb.append("        if ((dirtyMask & ").append(bitLit).append(") != 0L) {\n");
                sb.append("            if (dirty_").append(f.camelName()).append(" == null || dirty_").append(f.camelName()).append(".isEmpty()) {\n");
                sb.append("                return Collections.emptyList();\n");
                sb.append("            }\n");
                sb.append("            List<").append(subOpaque).append("> out = new ArrayList<>(dirty_").append(f.camelName()).append(".size());\n");
                sb.append("            for (").append(subModel).append(" elem : dirty_").append(f.camelName()).append(") {\n");
                sb.append("                out.add(new ").append(subOpaque).append('(').append(subCodec).append(".marshal(elem)));\n");
                sb.append("            }\n");
                sb.append("            return out;\n");
                sb.append("        }\n");
                sb.append("        List<OmniBufOpaque.PacketView> views = OmniBufOpaque.readSubMsgList(buffer, offset, length, ").append(fnum).append(");\n");
                sb.append("        if (views.isEmpty()) {\n");
                sb.append("            return Collections.emptyList();\n");
                sb.append("        }\n");
                sb.append("        List<").append(subOpaque).append("> out = new ArrayList<>(views.size());\n");
                sb.append("        for (OmniBufOpaque.PacketView v : views) {\n");
                sb.append("            out.add(new ").append(subOpaque).append("(v));\n");
                sb.append("        }\n");
                sb.append("        return out;\n");
                sb.append("    }\n\n");

                sb.append("    public ").append(info.opaqueClass()).append(" set").append(f.pascalName())
                    .append("(List<").append(subModel).append("> value) {\n");
                sb.append("        this.dirty_").append(f.camelName())
                    .append(" = value == null ? new ArrayList<>() : value;\n");
                sb.append("        this.dirtyMask |= ").append(bitLit).append(";\n");
                sb.append("        return this;\n");
                sb.append("    }\n\n");
                return;
            }

            String fType = javaFieldType(f);
            String readerMethod = opaqueRepeatedReader(f.proto().getType());
            sb.append("    public ").append(fType).append(" get").append(f.pascalName()).append("() {\n");
            sb.append("        if ((dirtyMask & ").append(bitLit).append(") != 0L) {\n");
            sb.append("            return dirty_").append(f.camelName()).append(";\n");
            sb.append("        }\n");
            sb.append("        return OmniBufOpaque.").append(readerMethod).append("(buffer, offset, length, ").append(fnum).append(");\n");
            sb.append("    }\n\n");

            sb.append("    public ").append(info.opaqueClass()).append(" set").append(f.pascalName())
                .append('(').append(fType).append(" value) {\n");
            sb.append("        this.dirty_").append(f.camelName())
                .append(" = value == null ? new ArrayList<>() : value;\n");
            sb.append("        this.dirtyMask |= ").append(bitLit).append(";\n");
            sb.append("        return this;\n");
            sb.append("    }\n\n");
            return;
        }

        if (f.proto().getType() == Type.TYPE_MESSAGE) {
            String subOpaque = f.subMessage().qualifiedOpaque();
            String subModel = f.subMessage().qualifiedModel();
            String subCodec = f.subMessage().qualifiedCodec();
            sb.append("    public ").append(subOpaque).append(" get").append(f.pascalName()).append("() {\n");
            sb.append("        if ((dirtyMask & ").append(bitLit).append(") != 0L) {\n");
            sb.append("            return dirty_").append(f.camelName()).append(" == null\n");
            sb.append("                ? null\n");
            sb.append("                : new ").append(subOpaque).append('(').append(subCodec).append(".marshal(dirty_").append(f.camelName()).append("));\n");
            sb.append("        }\n");
            sb.append("        OmniBufOpaque.PacketView view = OmniBufOpaque.readSubMsgView(buffer, offset, length, ").append(fnum).append(");\n");
            sb.append("        return view == null ? null : new ").append(subOpaque).append("(view);\n");
            sb.append("    }\n\n");

            sb.append("    public ").append(info.opaqueClass()).append(" set").append(f.pascalName())
                .append('(').append(subModel).append(" value) {\n");
            sb.append("        this.dirty_").append(f.camelName()).append(" = value;\n");
            sb.append("        this.dirtyMask |= ").append(bitLit).append(";\n");
            sb.append("        return this;\n");
            sb.append("    }\n\n");
            return;
        }

        String fType = javaFieldType(f);
        String readerMethod = opaqueScalarReader(f.proto().getType());
        sb.append("    public ").append(fType).append(" get").append(f.pascalName()).append("() {\n");
        sb.append("        if ((dirtyMask & ").append(bitLit).append(") != 0L) {\n");
        sb.append("            return dirty_").append(f.camelName()).append(";\n");
        sb.append("        }\n");
        sb.append("        return OmniBufOpaque.").append(readerMethod).append("(buffer, offset, length, ").append(fnum).append(");\n");
        sb.append("    }\n\n");

        sb.append("    public ").append(info.opaqueClass()).append(" set").append(f.pascalName())
            .append('(').append(fType).append(" value) {\n");
        if (f.proto().getType() == Type.TYPE_STRING) {
            sb.append("        this.dirty_").append(f.camelName()).append(" = value == null ? \"\" : value;\n");
        } else if (f.proto().getType() == Type.TYPE_BYTES) {
            sb.append("        this.dirty_").append(f.camelName()).append(" = value == null ? EMPTY_BYTES : value;\n");
        } else {
            sb.append("        this.dirty_").append(f.camelName()).append(" = value;\n");
        }
        sb.append("        this.dirtyMask |= ").append(bitLit).append(";\n");
        sb.append("        return this;\n");
        sb.append("    }\n\n");
    }

    // ========================================================================
    // Type & Naming Helpers
    // ========================================================================

    private static String javaFieldType(ResolvedField f) {
        if (f.isMap()) {
            return "Map<" + boxedType(f.mapKeyProto(), null) + ", " + boxedType(f.mapValProto(), f.subMessage()) + ">";
        }
        if (f.isRepeated()) {
            return "List<" + boxedType(f.proto(), f.subMessage()) + ">";
        }
        return switch (f.proto().getType()) {
            case TYPE_INT32, TYPE_SINT32, TYPE_UINT32, TYPE_FIXED32, TYPE_SFIXED32, TYPE_ENUM -> "int";
            case TYPE_INT64, TYPE_SINT64, TYPE_UINT64, TYPE_FIXED64, TYPE_SFIXED64 -> "long";
            case TYPE_BOOL -> "boolean";
            case TYPE_FLOAT -> "float";
            case TYPE_DOUBLE -> "double";
            case TYPE_STRING -> "String";
            case TYPE_BYTES -> "byte[]";
            case TYPE_MESSAGE -> f.subMessage().qualifiedModel();
            default -> throw new IllegalStateException("Unsupported proto type: " + f.proto().getType());
        };
    }

    private static String boxedType(FieldDescriptorProto fd, MessageInfo sub) {
        return switch (fd.getType()) {
            case TYPE_INT32, TYPE_SINT32, TYPE_UINT32, TYPE_FIXED32, TYPE_SFIXED32, TYPE_ENUM -> "Integer";
            case TYPE_INT64, TYPE_SINT64, TYPE_UINT64, TYPE_FIXED64, TYPE_SFIXED64 -> "Long";
            case TYPE_BOOL -> "Boolean";
            case TYPE_FLOAT -> "Float";
            case TYPE_DOUBLE -> "Double";
            case TYPE_STRING -> "String";
            case TYPE_BYTES -> "byte[]";
            case TYPE_MESSAGE -> sub.qualifiedModel();
            default -> throw new IllegalStateException("Unsupported boxed type: " + fd.getType());
        };
    }

    private static String javaDefaultInit(ResolvedField f) {
        if (f.isMap()) {
            return "new LinkedHashMap<>()";
        }
        if (f.isRepeated()) {
            return "new ArrayList<>()";
        }
        return switch (f.proto().getType()) {
            case TYPE_INT32, TYPE_SINT32, TYPE_UINT32, TYPE_FIXED32, TYPE_SFIXED32, TYPE_ENUM -> "0";
            case TYPE_INT64, TYPE_SINT64, TYPE_UINT64, TYPE_FIXED64, TYPE_SFIXED64 -> "0L";
            case TYPE_BOOL -> "false";
            case TYPE_FLOAT -> "0.0f";
            case TYPE_DOUBLE -> "0.0d";
            case TYPE_STRING -> "\"\"";
            case TYPE_BYTES -> "EMPTY_BYTES";
            case TYPE_MESSAGE -> "null";
            default -> "null";
        };
    }

    private static String wireTypeConst(Type t) {
        return switch (t) {
            case TYPE_UINT32, TYPE_UINT64 -> "WireType.WIRE_UINT";
            case TYPE_INT32, TYPE_INT64, TYPE_SINT32, TYPE_SINT64, TYPE_ENUM -> "WireType.WIRE_SINT";
            case TYPE_BOOL -> "WireType.WIRE_BOOL_FALSE";
            case TYPE_FIXED32 -> "WireType.WIRE_FIXED32";
            case TYPE_SFIXED32 -> "WireType.WIRE_SFIXED32";
            case TYPE_FLOAT -> "WireType.WIRE_FLOAT32";
            case TYPE_FIXED64 -> "WireType.WIRE_FIXED64";
            case TYPE_SFIXED64 -> "WireType.WIRE_SFIXED64";
            case TYPE_DOUBLE -> "WireType.WIRE_FLOAT64";
            case TYPE_STRING -> "WireType.WIRE_STRING";
            case TYPE_BYTES -> "WireType.WIRE_BYTES";
            case TYPE_MESSAGE -> "WireType.WIRE_SUB_MSG";
            default -> throw new IllegalStateException("Unsupported WireType mapping: " + t);
        };
    }

    private static String singularReadExpr(Type t) {
        return switch (t) {
            case TYPE_UINT32 -> "dec.readUint32()";
            case TYPE_UINT64 -> "dec.readUint64()";
            case TYPE_INT32, TYPE_SINT32, TYPE_ENUM -> "dec.readSint32()";
            case TYPE_INT64, TYPE_SINT64 -> "dec.readSint64()";
            case TYPE_FIXED32, TYPE_SFIXED32 -> "dec.readFixed32()";
            case TYPE_FIXED64, TYPE_SFIXED64 -> "dec.readFixed64()";
            case TYPE_FLOAT -> "dec.readFloat32()";
            case TYPE_DOUBLE -> "dec.readFloat64()";
            case TYPE_STRING -> "dec.readString()";
            case TYPE_BYTES -> "dec.readBytes()";
            default -> throw new IllegalStateException("Unsupported scalar read: " + t);
        };
    }

    private static String arrayElementReadExpr(Type t) {
        return switch (t) {
            case TYPE_BOOL -> "aDec.readContainerBool()";
            case TYPE_UINT32 -> "aDec.readUint32()";
            case TYPE_UINT64 -> "aDec.readUint64()";
            case TYPE_INT32, TYPE_SINT32, TYPE_ENUM -> "aDec.readSint32()";
            case TYPE_INT64, TYPE_SINT64 -> "aDec.readSint64()";
            case TYPE_FIXED32, TYPE_SFIXED32 -> "aDec.readFixed32()";
            case TYPE_FIXED64, TYPE_SFIXED64 -> "aDec.readFixed64()";
            case TYPE_FLOAT -> "aDec.readFloat32()";
            case TYPE_DOUBLE -> "aDec.readFloat64()";
            case TYPE_STRING -> "aDec.readString()";
            case TYPE_BYTES -> "aDec.readBytes()";
            default -> throw new IllegalStateException("Unsupported array element read: " + t);
        };
    }

    private static String opaqueScalarReader(Type t) {
        return switch (t) {
            case TYPE_UINT32 -> "readUint32";
            case TYPE_UINT64 -> "readUint64";
            case TYPE_INT32, TYPE_SINT32, TYPE_ENUM -> "readSint32";
            case TYPE_INT64, TYPE_SINT64 -> "readSint64";
            case TYPE_BOOL -> "readBool";
            case TYPE_FIXED32, TYPE_SFIXED32 -> "readFixed32";
            case TYPE_FIXED64, TYPE_SFIXED64 -> "readFixed64";
            case TYPE_FLOAT -> "readFloat32";
            case TYPE_DOUBLE -> "readFloat64";
            case TYPE_STRING -> "readString";
            case TYPE_BYTES -> "readBytes";
            default -> throw new IllegalStateException("Unsupported opaque scalar: " + t);
        };
    }

    private static String opaqueRepeatedReader(Type t) {
        return switch (t) {
            case TYPE_STRING -> "readStringList";
            case TYPE_BYTES -> "readBytesList";
            case TYPE_UINT32 -> "readUint32List";
            case TYPE_UINT64 -> "readUint64List";
            case TYPE_INT32, TYPE_SINT32, TYPE_ENUM -> "readSint32List";
            case TYPE_INT64, TYPE_SINT64 -> "readSint64List";
            case TYPE_BOOL -> "readBoolList";
            case TYPE_FIXED32, TYPE_SFIXED32 -> "readFixed32List";
            case TYPE_FIXED64, TYPE_SFIXED64 -> "readFixed64List";
            case TYPE_FLOAT -> "readFloat32List";
            case TYPE_DOUBLE -> "readFloat64List";
            default -> throw new IllegalStateException("Unsupported opaque repeated: " + t);
        };
    }

    private static String snakeToCamel(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean upperNext = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '_') {
                upperNext = true;
            } else if (upperNext) {
                sb.append(Character.toUpperCase(c));
                upperNext = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
