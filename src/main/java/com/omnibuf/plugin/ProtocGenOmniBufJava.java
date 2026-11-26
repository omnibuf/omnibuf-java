package com.omnibuf.plugin;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse;
import com.omnibuf.wire.WireType;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
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
 * files per protobuf message (including nested message declarations) into the file's
 * {@code java_package}:
 * <ul>
 *   <li>{@code OmniBuf<Message>.java}: lightweight mutable data model with lazy collection allocation</li>
 *   <li>{@code <Message>OmniBuf.java}: single-pass stackless encoder and single-cursor decoder</li>
 *   <li>{@code Opaque<Message>.java}: zero-copy lazy reader view with {@code spliceFields} mutation</li>
 * </ul>
 */
public final class ProtocGenOmniBufJava {

    private static final int MAX_FIELD_NUMBER = (1 << 29) - 1;

    private static final Set<String> JAVA_RESERVED = Set.of(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
        "const", "continue", "default", "do", "double", "else", "enum", "exports", "extends",
        "false", "final", "finally", "float", "for", "goto", "if", "implements", "import",
        "instanceof", "int", "interface", "long", "module", "native", "new", "non-sealed",
        "null", "open", "opens", "package", "permits", "private", "protected", "provides",
        "public", "record", "requires", "return", "sealed", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "to", "transient",
        "transitive", "true", "try", "uses", "var", "void", "volatile", "while", "with",
        "yield", "_",
        // Internal generated identifiers to avoid shadowing
        "EMPTY_BYTES", "MAX_FIELD_NUMBER", "MAX_INDEXED_FIELD", "buffer", "offset", "length",
        "dirty", "dirtyMask", "dirtyBits", "fieldIndex", "readCount"
    );

    private static final Set<String> RESERVED_PASCAL_NAMES = Set.of(
        "Class", "DirtyFields", "PacketView", "ReadOnlyByteBuffer"
    );

    private static final class PluginValidationException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        PluginValidationException(String message) {
            super(message);
        }
    }

    private record OneofInfo(
        int protoIndex,
        String protoName,
        String camelName,
        String pascalName,
        String upperConstName,
        List<Integer> memberNumbers,
        List<String> memberWireMasks
    ) {}

    private record MessageInfo(
        String sourceFileName,
        String protoFullName,
        String simpleName,
        String schemaTypeName,
        String javaPackage,
        boolean isProto2,
        boolean isEditions,
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
        boolean hasExplicitScalarPresence,
        OneofInfo oneof,
        FieldDescriptorProto mapKeyProto,
        FieldDescriptorProto mapValProto,
        MessageInfo subMessage
    ) {}

    private record ResolvedMessage(
        MessageInfo info,
        List<ResolvedField> fields,
        List<OneofInfo> oneofs
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
        resp.setSupportedFeatures(EditionResolver.SUPPORTED_FEATURES);
        resp.setMinimumEdition(EditionResolver.MIN_EDITION)
            .setMaximumEdition(EditionResolver.MAX_EDITION);

        try {
            Set<String> targetFiles = new HashSet<>(request.getFileToGenerateList());
            Map<String, Boolean> enumClosedRegistry = new HashMap<>();
            Map<String, Boolean> fileIsEditions = new HashMap<>();
            Map<String, FileDescriptorProto> unresolvedDepFiles = new HashMap<>();
            List<FileDescriptorProto> processedFiles = new ArrayList<>(request.getProtoFileCount());
            for (FileDescriptorProto file : request.getProtoFileList()) {
                boolean isEditions = EditionResolver.isEditions(file);
                fileIsEditions.put(file.getName(), isEditions);
                EditionResolver.collectEnumClosedMap(file, enumClosedRegistry);
                if (targetFiles.contains(file.getName())) {
                    processedFiles.add(EditionResolver.resolve(file));
                } else {
                    unresolvedDepFiles.put(file.getName(), file);
                    processedFiles.add(file);
                }
            }

            Map<String, MessageInfo> registry = new LinkedHashMap<>();
            Map<String, EnumDescriptorProto> enumRegistry = new LinkedHashMap<>();
            Map<String, Set<String>> usedClassNamesByPkg = new HashMap<>();
            Map<String, String> reservedTopLevelSimple = new HashMap<>();

            for (FileDescriptorProto file : processedFiles) {
                String protoPkg = file.getPackage();
                String javaPkg = file.getOptions().hasJavaPackage() && !file.getOptions().getJavaPackage().isEmpty()
                    ? file.getOptions().getJavaPackage()
                    : protoPkg;
                String protoPrefix = protoPkg.isEmpty() ? "." : "." + protoPkg + ".";
                Set<String> pkgClasses = usedClassNamesByPkg.computeIfAbsent(javaPkg, k -> new HashSet<>());
                for (DescriptorProto msg : file.getMessageTypeList()) {
                    if (!msg.getOptions().getMapEntry()) {
                        String fullProto = protoPrefix + msg.getName();
                        reservedTopLevelSimple.put(fullProto, allocateUniqueSimpleName(msg.getName(), pkgClasses));
                    }
                }
            }

            for (FileDescriptorProto file : processedFiles) {
                String protoPkg = file.getPackage();
                String javaPkg = file.getOptions().hasJavaPackage() && !file.getOptions().getJavaPackage().isEmpty()
                    ? file.getOptions().getJavaPackage()
                    : protoPkg;
                boolean isEditions = Boolean.TRUE.equals(fileIsEditions.get(file.getName()));
                boolean isProto2 = !isEditions && !"proto3".equals(file.getSyntax());
                String protoPrefix = protoPkg.isEmpty() ? "." : "." + protoPkg + ".";
                Set<String> pkgClasses = usedClassNamesByPkg.computeIfAbsent(javaPkg, k -> new HashSet<>());

                if (targetFiles.contains(file.getName())) {
                    validateFileExtensions(file);
                }

                for (EnumDescriptorProto ed : file.getEnumTypeList()) {
                    enumRegistry.put(protoPrefix + ed.getName(), ed);
                }

                for (DescriptorProto msg : file.getMessageTypeList()) {
                    String fullProto = protoPrefix + msg.getName();
                    String topSimple = reservedTopLevelSimple.getOrDefault(fullProto, msg.getName());
                    registerMessageRecursive(
                        file.getName(),
                        targetFiles.contains(file.getName()),
                        javaPkg,
                        isProto2,
                        isEditions,
                        fullProto,
                        topSimple,
                        true,
                        msg,
                        pkgClasses,
                        registry,
                        enumRegistry
                    );
                }
            }

            // Validate and resolve all messages in target files (plus any referenced dependency messages)
            Map<String, ResolvedMessage> resolvedCache = new LinkedHashMap<>();
            Set<String> validatedDepFiles = new HashSet<>();
            List<MessageInfo> targetMessages = new ArrayList<>();
            for (MessageInfo info : registry.values()) {
                if (targetFiles.contains(info.sourceFileName())) {
                    targetMessages.add(info);
                }
            }
            for (MessageInfo info : targetMessages) {
                resolveMessageGraph(
                    info,
                    registry,
                    enumRegistry,
                    enumClosedRegistry,
                    unresolvedDepFiles,
                    validatedDepFiles,
                    resolvedCache
                );
            }

            for (MessageInfo info : targetMessages) {
                ResolvedMessage resolved = resolvedCache.get(info.protoFullName());
                String dirPrefix = info.javaPackage().isEmpty()
                    ? ""
                    : info.javaPackage().replace('.', '/') + "/";

                resp.addFile(
                    CodeGeneratorResponse.File.newBuilder()
                        .setName(dirPrefix + info.modelClass() + ".java")
                        .setContent(emitModelFile(info.sourceFileName(), resolved))
                        .build()
                );
                resp.addFile(
                    CodeGeneratorResponse.File.newBuilder()
                        .setName(dirPrefix + info.codecClass() + ".java")
                        .setContent(emitCodecFile(info.sourceFileName(), resolved))
                        .build()
                );
                resp.addFile(
                    CodeGeneratorResponse.File.newBuilder()
                        .setName(dirPrefix + info.opaqueClass() + ".java")
                        .setContent(emitOpaqueFile(info.sourceFileName(), resolved))
                        .build()
                );
            }

            return resp.build();
        } catch (PluginValidationException | IllegalArgumentException e) {
            return CodeGeneratorResponse.newBuilder()
                .setSupportedFeatures(EditionResolver.SUPPORTED_FEATURES)
                .setMinimumEdition(EditionResolver.MIN_EDITION)
                .setMaximumEdition(EditionResolver.MAX_EDITION)
                .setError(e.getMessage())
                .build();
        }
    }

    private static void validateFileExtensions(FileDescriptorProto file) {
        for (FieldDescriptorProto ext : file.getExtensionList()) {
            if (!EditionResolver.isDescriptorOptionExtension(ext)) {
                throw new PluginValidationException(
                    "omnibuf: extensions are not supported in "
                        + file.getName() + " (" + ext.getName() + " extends " + ext.getExtendee() + ")"
                );
            }
        }
    }

    private static void validateMessageExtensions(String fullProtoName, DescriptorProto msg) {
        if (msg.getExtensionRangeCount() > 0) {
            throw new PluginValidationException(
                "omnibuf: extension ranges are not supported on message " + fullProtoName
            );
        }
        for (FieldDescriptorProto ext : msg.getExtensionList()) {
            if (!EditionResolver.isDescriptorOptionExtension(ext)) {
                throw new PluginValidationException(
                    "omnibuf: extensions are not supported on message "
                        + fullProtoName + " (" + ext.getName() + " extends " + ext.getExtendee() + ")"
                );
            }
        }
    }

    private static void updateResolvedDependencyMessages(
            FileDescriptorProto resolvedFile, Map<String, MessageInfo> registry) {
        String protoPkg = resolvedFile.getPackage();
        String protoPrefix = protoPkg.isEmpty() ? "." : "." + protoPkg + ".";
        for (DescriptorProto msg : resolvedFile.getMessageTypeList()) {
            updateResolvedDepMessageRecursive(protoPrefix + msg.getName(), msg, registry);
        }
    }

    private static void updateResolvedDepMessageRecursive(
            String fullProtoName, DescriptorProto msg, Map<String, MessageInfo> registry) {
        if (msg.getOptions().getMapEntry()) {
            return;
        }
        Map<String, DescriptorProto> mapEntries = new HashMap<>();
        for (DescriptorProto nested : msg.getNestedTypeList()) {
            String nestedFull = fullProtoName + "." + nested.getName();
            if (nested.getOptions().getMapEntry()) {
                mapEntries.put(nestedFull, nested);
            } else {
                updateResolvedDepMessageRecursive(nestedFull, nested, registry);
            }
        }
        MessageInfo old = registry.get(fullProtoName);
        if (old != null) {
            registry.put(
                fullProtoName,
                new MessageInfo(
                    old.sourceFileName(),
                    old.protoFullName(),
                    old.simpleName(),
                    old.schemaTypeName(),
                    old.javaPackage(),
                    old.isProto2(),
                    old.isEditions(),
                    msg,
                    mapEntries
                )
            );
        }
    }

    private static String allocateUniqueSimpleName(String base, Set<String> pkgClasses) {
        String candidate = base;
        int suffix = 2;
        while (pkgClasses.contains("OmniBuf" + candidate)
                || pkgClasses.contains(candidate + "OmniBuf")
                || pkgClasses.contains("Opaque" + candidate)) {
            candidate = base + "_" + suffix;
            suffix++;
        }
        pkgClasses.add("OmniBuf" + candidate);
        pkgClasses.add(candidate + "OmniBuf");
        pkgClasses.add("Opaque" + candidate);
        return candidate;
    }

    private static void registerMessageRecursive(
            String sourceFileName,
            boolean isTargetFile,
            String javaPkg,
            boolean isProto2,
            boolean isEditions,
            String fullProtoName,
            String candidateSimpleName,
            boolean alreadyAllocated,
            DescriptorProto msg,
            Set<String> pkgClasses,
            Map<String, MessageInfo> registry,
            Map<String, EnumDescriptorProto> enumRegistry) {
        if (msg.getOptions().getMapEntry()) {
            return;
        }
        if (isTargetFile) {
            validateMessageExtensions(fullProtoName, msg);
        }

        String flatJavaSimpleName = alreadyAllocated
            ? candidateSimpleName
            : allocateUniqueSimpleName(candidateSimpleName, pkgClasses);

        for (EnumDescriptorProto ed : msg.getEnumTypeList()) {
            enumRegistry.put(fullProtoName + "." + ed.getName(), ed);
        }

        Map<String, DescriptorProto> mapEntries = new HashMap<>();
        for (DescriptorProto nested : msg.getNestedTypeList()) {
            String nestedFullProto = fullProtoName + "." + nested.getName();
            if (nested.getOptions().getMapEntry()) {
                mapEntries.put(nestedFullProto, nested);
            } else {
                registerMessageRecursive(
                    sourceFileName,
                    isTargetFile,
                    javaPkg,
                    isProto2,
                    isEditions,
                    nestedFullProto,
                    flatJavaSimpleName + "_" + nested.getName(),
                    false,
                    nested,
                    pkgClasses,
                    registry,
                    enumRegistry
                );
            }
        }

        registry.put(
            fullProtoName,
            new MessageInfo(
                sourceFileName,
                fullProtoName,
                flatJavaSimpleName,
                msg.getName(),
                javaPkg,
                isProto2,
                isEditions,
                msg,
                mapEntries
            )
        );
    }

    private static ResolvedMessage resolveMessageGraph(
            MessageInfo info,
            Map<String, MessageInfo> registry,
            Map<String, EnumDescriptorProto> enumRegistry,
            Map<String, Boolean> enumClosedRegistry,
            Map<String, FileDescriptorProto> unresolvedDepFiles,
            Set<String> validatedDepFiles,
            Map<String, ResolvedMessage> resolvedCache) {
        ResolvedMessage existing = resolvedCache.get(info.protoFullName());
        if (existing != null) {
            return existing;
        }

        FileDescriptorProto depFile = unresolvedDepFiles.get(info.sourceFileName());
        if (depFile != null) {
            if (validatedDepFiles.add(info.sourceFileName())) {
                FileDescriptorProto resolvedDep = EditionResolver.resolve(depFile);
                validateFileExtensions(resolvedDep);
                updateResolvedDependencyMessages(resolvedDep, registry);
            }
            info = registry.get(info.protoFullName());
        }

        validateMessageExtensions(info.protoFullName(), info.descriptor());

        // Put a placeholder first to guard cyclic message graphs
        List<ResolvedField> fields = new ArrayList<>();
        List<OneofInfo> oneofs = new ArrayList<>();
        ResolvedMessage placeholder = new ResolvedMessage(info, fields, oneofs);
        resolvedCache.put(info.protoFullName(), placeholder);

        Set<String> usedCamels = new HashSet<>();
        Set<String> usedPascals = new HashSet<>();

        // Pre-discover real (non-proto3_optional) oneof groups
        Map<Integer, List<FieldDescriptorProto>> realOneofMembers = new LinkedHashMap<>();
        for (FieldDescriptorProto fd : info.descriptor().getFieldList()) {
            if (fd.hasOneofIndex() && !fd.getProto3Optional()) {
                realOneofMembers.computeIfAbsent(fd.getOneofIndex(), k -> new ArrayList<>()).add(fd);
            }
        }

        Map<Integer, OneofInfo> oneofByIndex = new HashMap<>();
        for (Map.Entry<Integer, List<FieldDescriptorProto>> entry : realOneofMembers.entrySet()) {
            int idx = entry.getKey();
            OneofDescriptorProto od = info.descriptor().getOneofDecl(idx);
            String[] names = sanitizeNames(od.getName(), idx + 1, usedCamels, usedPascals);
            usedCamels.add(names[0] + "Case");
            usedPascals.add(names[1] + "Case");
            List<FieldDescriptorProto> sortedMembers = new ArrayList<>(entry.getValue());
            sortedMembers.sort(Comparator.comparingInt(FieldDescriptorProto::getNumber));
            List<Integer> memberNums = new ArrayList<>(sortedMembers.size());
            List<String> memberMasks = new ArrayList<>(sortedMembers.size());
            for (FieldDescriptorProto mfd : sortedMembers) {
                validateFieldSupported(info, mfd, registry, enumRegistry, enumClosedRegistry);
                memberNums.add(mfd.getNumber());
                memberMasks.add(wireMaskExpr(mfd.getType()));
            }
            String upperConst = toUpperSnake(od.getName(), idx);
            OneofInfo oi = new OneofInfo(
                idx,
                od.getName(),
                names[0],
                names[1],
                upperConst,
                memberNums,
                memberMasks
            );
            oneofByIndex.put(idx, oi);
            oneofs.add(oi);
        }

        List<FieldDescriptorProto> rawFields = new ArrayList<>(info.descriptor().getFieldList());
        rawFields.sort(Comparator.comparingInt(FieldDescriptorProto::getNumber));

        Set<Integer> seenFieldNumbers = new HashSet<>();
        for (FieldDescriptorProto fd : rawFields) {
            int number = fd.getNumber();
            if (number <= 0 || number > MAX_FIELD_NUMBER) {
                throw new PluginValidationException(
                    "omnibuf: field number out of range (" + number + ") on "
                        + info.protoFullName() + "." + fd.getName()
                );
            }
            if (!seenFieldNumbers.add(number)) {
                throw new PluginValidationException(
                    "omnibuf: duplicate field number " + number + " on " + info.protoFullName()
                );
            }

            validateFieldSupported(info, fd, registry, enumRegistry, enumClosedRegistry);

            String protoName = fd.getName();
            String[] names = sanitizeNames(protoName, number, usedCamels, usedPascals);
            String camel = names[0];
            String pascal = names[1];
            boolean repeated = fd.getLabel() == Label.LABEL_REPEATED;

            if (repeated && fd.getType() == Type.TYPE_MESSAGE) {
                DescriptorProto mapEntry = info.mapEntries().get(normalizeTypeName(fd.getTypeName()));
                if (mapEntry != null) {
                    FieldDescriptorProto kf = mapEntry.getField(0);
                    FieldDescriptorProto vf = mapEntry.getField(1);
                    validateMapKeyType(info, fd, kf);
                    validateMapValueType(info, fd, vf, registry, enumRegistry, enumClosedRegistry);
                    MessageInfo valSub = vf.getType() == Type.TYPE_MESSAGE
                        ? registry.get(normalizeTypeName(vf.getTypeName()))
                        : null;
                    if (valSub != null) {
                        resolveMessageGraph(
                            valSub,
                            registry,
                            enumRegistry,
                            enumClosedRegistry,
                            unresolvedDepFiles,
                            validatedDepFiles,
                            resolvedCache
                        );
                    }
                    fields.add(new ResolvedField(
                        fd, number, protoName, camel, pascal, true, false, false, null, kf, vf, valSub
                    ));
                    continue;
                }
            }

            OneofInfo oneof = (fd.hasOneofIndex() && !fd.getProto3Optional())
                ? oneofByIndex.get(fd.getOneofIndex())
                : null;
            boolean explicitScalarPresence = !repeated
                && oneof == null
                && fd.getType() != Type.TYPE_MESSAGE
                && (fd.getProto3Optional() || info.isProto2());

            MessageInfo sub = fd.getType() == Type.TYPE_MESSAGE
                ? registry.get(normalizeTypeName(fd.getTypeName()))
                : null;
            if (sub != null) {
                resolveMessageGraph(
                    sub,
                    registry,
                    enumRegistry,
                    enumClosedRegistry,
                    unresolvedDepFiles,
                    validatedDepFiles,
                    resolvedCache
                );
            }

            fields.add(new ResolvedField(
                fd, number, protoName, camel, pascal, false, repeated, explicitScalarPresence,
                oneof, null, null, sub
            ));
        }

        return placeholder;
    }

    private static void validateFieldSupported(
            MessageInfo info,
            FieldDescriptorProto fd,
            Map<String, MessageInfo> registry,
            Map<String, EnumDescriptorProto> enumRegistry,
            Map<String, Boolean> enumClosedRegistry) {
        String qualifiedField = info.protoFullName() + "." + fd.getName();
        if (fd.getType() == Type.TYPE_GROUP) {
            throw new PluginValidationException(
                "omnibuf: proto2 groups are not supported: " + qualifiedField
            );
        }
        if (fd.getLabel() == Label.LABEL_REQUIRED) {
            throw new PluginValidationException(
                "omnibuf: required fields are not supported: " + qualifiedField
            );
        }
        if (fd.hasDefaultValue() && !isZeroProtoDefault(fd, enumRegistry)) {
            throw new PluginValidationException(
                "omnibuf: custom non-zero default values are not supported on "
                    + qualifiedField + " (default=\"" + fd.getDefaultValue() + "\")"
            );
        }
        if (fd.getType() == Type.TYPE_MESSAGE) {
            String typeName = normalizeTypeName(fd.getTypeName());
            if (!info.mapEntries().containsKey(typeName) && !registry.containsKey(typeName)) {
                throw new PluginValidationException(
                    "omnibuf: unresolved message type " + typeName + " on " + qualifiedField
                );
            }
        } else if (fd.getType() == Type.TYPE_ENUM) {
            String typeName = normalizeTypeName(fd.getTypeName());
            if (!enumRegistry.isEmpty() && !enumRegistry.containsKey(typeName)) {
                throw new PluginValidationException(
                    "omnibuf: unresolved enum type " + typeName + " on " + qualifiedField
                );
            }
            if (info.isEditions() && Boolean.TRUE.equals(enumClosedRegistry.get(typeName))) {
                throw new PluginValidationException(
                    "omnibuf: editions CLOSED enums are not supported (" + qualifiedField + " -> " + typeName + ")"
                );
            }
        }
    }

    private static void validateMapKeyType(
            MessageInfo info, FieldDescriptorProto mapField, FieldDescriptorProto kf) {
        switch (kf.getType()) {
            case TYPE_INT32,
                 TYPE_INT64,
                 TYPE_UINT32,
                 TYPE_UINT64,
                 TYPE_SINT32,
                 TYPE_SINT64,
                 TYPE_FIXED32,
                 TYPE_FIXED64,
                 TYPE_SFIXED32,
                 TYPE_SFIXED64,
                 TYPE_BOOL,
                 TYPE_STRING -> {}
            default -> throw new PluginValidationException(
                "omnibuf: unsupported map key type " + kf.getType() + " on "
                    + info.protoFullName() + "." + mapField.getName()
            );
        }
    }

    private static void validateMapValueType(
            MessageInfo info,
            FieldDescriptorProto mapField,
            FieldDescriptorProto vf,
            Map<String, MessageInfo> registry,
            Map<String, EnumDescriptorProto> enumRegistry,
            Map<String, Boolean> enumClosedRegistry) {
        String qualifiedField = info.protoFullName() + "." + mapField.getName();
        if (vf.getType() == Type.TYPE_GROUP) {
            throw new PluginValidationException(
                "omnibuf: proto2 groups are not supported in map values: " + qualifiedField
            );
        }
        if (vf.getType() == Type.TYPE_MESSAGE) {
            String typeName = normalizeTypeName(vf.getTypeName());
            if (!registry.containsKey(typeName)) {
                throw new PluginValidationException(
                    "omnibuf: unresolved map value message type " + typeName + " on " + qualifiedField
                );
            }
        } else if (vf.getType() == Type.TYPE_ENUM) {
            String typeName = normalizeTypeName(vf.getTypeName());
            if (!enumRegistry.isEmpty() && !enumRegistry.containsKey(typeName)) {
                throw new PluginValidationException(
                    "omnibuf: unresolved map value enum type " + typeName + " on " + qualifiedField
                );
            }
            if (info.isEditions() && Boolean.TRUE.equals(enumClosedRegistry.get(typeName))) {
                throw new PluginValidationException(
                    "omnibuf: editions CLOSED enums are not supported (" + qualifiedField + " -> " + typeName + ")"
                );
            }
        }
    }

    private static boolean isZeroProtoDefault(
            FieldDescriptorProto fd, Map<String, EnumDescriptorProto> enumRegistry) {
        String dv = fd.getDefaultValue();
        return switch (fd.getType()) {
            case TYPE_STRING, TYPE_BYTES -> dv.isEmpty();
            case TYPE_BOOL -> "false".equals(dv);
            case TYPE_INT32,
                 TYPE_INT64,
                 TYPE_UINT32,
                 TYPE_UINT64,
                 TYPE_SINT32,
                 TYPE_SINT64,
                 TYPE_FIXED32,
                 TYPE_FIXED64,
                 TYPE_SFIXED32,
                 TYPE_SFIXED64 -> "0".equals(dv);
            case TYPE_FLOAT, TYPE_DOUBLE -> {
                try {
                    yield Double.doubleToRawLongBits(Double.parseDouble(dv)) == 0L;
                } catch (NumberFormatException e) {
                    yield false;
                }
            }
            case TYPE_ENUM -> {
                EnumDescriptorProto ed = enumRegistry.get(normalizeTypeName(fd.getTypeName()));
                if (ed == null) {
                    yield false;
                }
                for (EnumValueDescriptorProto ev : ed.getValueList()) {
                    if (ev.getName().equals(dv)) {
                        yield ev.getNumber() == 0;
                    }
                }
                yield false;
            }
            default -> false;
        };
    }

    private static String normalizeTypeName(String typeName) {
        if (typeName == null || typeName.isEmpty()) {
            return "";
        }
        return typeName.startsWith(".") ? typeName : "." + typeName;
    }

    // ========================================================================
    // 1. Mutable Model Class (OmniBuf<Message>.java)
    // ========================================================================

    private static String emitModelFile(String sourceProto, ResolvedMessage resolved) {
        MessageInfo info = resolved.info();
        List<ResolvedField> fields = resolved.fields();
        List<OneofInfo> oneofs = resolved.oneofs();

        StringBuilder sb = new StringBuilder(3072);
        sb.append("// Generated by protoc-gen-omnibuf-java. DO NOT EDIT.\n");
        sb.append("// source: ").append(sourceProto).append("\n\n");
        if (!info.javaPackage().isEmpty()) {
            sb.append("package ").append(info.javaPackage()).append(";\n\n");
        }
        sb.append("import com.omnibuf.MarshalOptions;\n");
        boolean needsIntList = false;
        boolean needsLongList = false;
        for (ResolvedField f : fields) {
            if (isRepeatedInt32Scalar(f)) {
                needsIntList = true;
            } else if (isRepeatedInt64Scalar(f)) {
                needsLongList = true;
            }
        }
        if (needsIntList) {
            sb.append("import com.omnibuf.wire.IntArrayList;\n");
        }
        if (needsLongList) {
            sb.append("import com.omnibuf.wire.LongArrayList;\n");
        }
        sb.append("import java.util.ArrayList;\n");
        sb.append("import java.util.LinkedHashMap;\n");
        sb.append("import java.util.List;\n");
        sb.append("import java.util.Map;\n\n");

        sb.append("public final class ").append(info.modelClass()).append(" {\n");
        sb.append("    static final byte[] EMPTY_BYTES = new byte[0];\n\n");

        for (ResolvedField f : fields) {
            String init = modelFieldDeclInit(f);
            sb.append("    ").append(javaFieldType(f)).append(' ').append(f.camelName());
            if (init != null) {
                sb.append(" = ").append(init);
            }
            sb.append(";\n");
            if (f.hasExplicitScalarPresence()) {
                sb.append("    boolean has_").append(f.camelName()).append(";\n");
            }
        }
        for (OneofInfo oi : oneofs) {
            sb.append("    int ").append(oi.camelName()).append("Case;\n");
        }
        sb.append('\n');

        sb.append("    public ").append(info.modelClass()).append("() {}\n\n");

        sb.append("    public void reset() {\n");
        for (ResolvedField f : fields) {
            if (f.isRepeated() || f.isMap()) {
                sb.append("        this.").append(f.camelName()).append(" = null;\n");
            } else {
                sb.append("        this.")
                    .append(f.camelName())
                    .append(" = ")
                    .append(javaScalarResetExpr(f))
                    .append(";\n");
                if (f.hasExplicitScalarPresence()) {
                    sb.append("        this.has_").append(f.camelName()).append(" = false;\n");
                }
            }
        }
        for (OneofInfo oi : oneofs) {
            sb.append("        this.").append(oi.camelName()).append("Case = 0;\n");
        }
        sb.append("    }\n\n");

        for (OneofInfo oi : oneofs) {
            sb.append("    public int get").append(oi.pascalName()).append("Case() {\n");
            sb.append("        return this.").append(oi.camelName()).append("Case;\n");
            sb.append("    }\n\n");

            sb.append("    public ").append(info.modelClass()).append(" clear").append(oi.pascalName()).append("() {\n");
            emitOneofClearSwitch(sb, oi, fields, "        ");
            sb.append("        this.").append(oi.camelName()).append("Case = 0;\n");
            sb.append("        return this;\n");
            sb.append("    }\n\n");

            sb.append("    void prepare").append(oi.pascalName()).append("(int activeField) {\n");
            sb.append("        if (this.").append(oi.camelName()).append("Case != 0 && this.")
                .append(oi.camelName()).append("Case != activeField) {\n");
            emitOneofClearSwitch(sb, oi, fields, "            ");
            sb.append("        }\n");
            sb.append("        this.").append(oi.camelName()).append("Case = activeField;\n");
            sb.append("    }\n\n");
        }

        for (ResolvedField f : fields) {
            String fType = javaFieldType(f);
            if (f.isRepeated()) {
                if (isRepeatedInt32Scalar(f)) {
                    sb.append("    public ").append(fType).append(" get").append(f.pascalName()).append("() {\n");
                    sb.append("        if (this.").append(f.camelName()).append(" == null) {\n");
                    sb.append("            this.").append(f.camelName()).append(" = new IntArrayList();\n");
                    sb.append("        }\n");
                    sb.append("        return this.").append(f.camelName()).append(";\n");
                    sb.append("    }\n\n");

                    sb.append("    IntArrayList ensure").append(f.pascalName()).append("(int minCap) {\n");
                    sb.append("        ").append(fType).append(" cur = this.").append(f.camelName()).append(";\n");
                    sb.append("        int cap = Math.min(minCap, 4096);\n");
                    sb.append("        if (cur == null) {\n");
                    sb.append("            IntArrayList il = new IntArrayList(cap);\n");
                    sb.append("            this.").append(f.camelName()).append(" = il;\n");
                    sb.append("            return il;\n");
                    sb.append("        }\n");
                    sb.append("        if (cur instanceof IntArrayList il) {\n");
                    sb.append("            il.ensureAdditional(cap);\n");
                    sb.append("            return il;\n");
                    sb.append("        }\n");
                    sb.append("        IntArrayList il = new IntArrayList(cur);\n");
                    sb.append("        il.ensureAdditional(cap);\n");
                    sb.append("        this.").append(f.camelName()).append(" = il;\n");
                    sb.append("        return il;\n");
                    sb.append("    }\n\n");
                } else if (isRepeatedInt64Scalar(f)) {
                    sb.append("    public ").append(fType).append(" get").append(f.pascalName()).append("() {\n");
                    sb.append("        if (this.").append(f.camelName()).append(" == null) {\n");
                    sb.append("            this.").append(f.camelName()).append(" = new LongArrayList();\n");
                    sb.append("        }\n");
                    sb.append("        return this.").append(f.camelName()).append(";\n");
                    sb.append("    }\n\n");

                    sb.append("    LongArrayList ensure").append(f.pascalName()).append("(int minCap) {\n");
                    sb.append("        ").append(fType).append(" cur = this.").append(f.camelName()).append(";\n");
                    sb.append("        int cap = Math.min(minCap, 4096);\n");
                    sb.append("        if (cur == null) {\n");
                    sb.append("            LongArrayList ll = new LongArrayList(cap);\n");
                    sb.append("            this.").append(f.camelName()).append(" = ll;\n");
                    sb.append("            return ll;\n");
                    sb.append("        }\n");
                    sb.append("        if (cur instanceof LongArrayList ll) {\n");
                    sb.append("            ll.ensureAdditional(cap);\n");
                    sb.append("            return ll;\n");
                    sb.append("        }\n");
                    sb.append("        LongArrayList ll = new LongArrayList(cur);\n");
                    sb.append("        ll.ensureAdditional(cap);\n");
                    sb.append("        this.").append(f.camelName()).append(" = ll;\n");
                    sb.append("        return ll;\n");
                    sb.append("    }\n\n");
                } else {
                    String elemBox = boxedType(f.proto(), f.subMessage());
                    String arrListType = "ArrayList<" + elemBox + ">";
                    sb.append("    public ").append(fType).append(" get").append(f.pascalName()).append("() {\n");
                    sb.append("        if (this.").append(f.camelName()).append(" == null) {\n");
                    sb.append("            this.").append(f.camelName()).append(" = new ArrayList<>();\n");
                    sb.append("        }\n");
                    sb.append("        return this.").append(f.camelName()).append(";\n");
                    sb.append("    }\n\n");

                    sb.append("    ").append(arrListType).append(" ensure").append(f.pascalName()).append("(int minCap) {\n");
                    sb.append("        ").append(fType).append(" cur = this.").append(f.camelName()).append(";\n");
                    sb.append("        int cap = Math.min(minCap, 4096);\n");
                    sb.append("        if (cur == null) {\n");
                    sb.append("            ").append(arrListType).append(" al = new ArrayList<>(cap);\n");
                    sb.append("            this.").append(f.camelName()).append(" = al;\n");
                    sb.append("            return al;\n");
                    sb.append("        }\n");
                    sb.append("        if (cur instanceof ").append(arrListType).append(" al) {\n");
                    sb.append("            if (al.isEmpty() && cap > 10) {\n");
                    sb.append("                al.ensureCapacity(cap);\n");
                    sb.append("            }\n");
                    sb.append("            return al;\n");
                    sb.append("        }\n");
                    sb.append("        ").append(arrListType).append(" al = new ArrayList<>(cur);\n");
                    sb.append("        this.").append(f.camelName()).append(" = al;\n");
                    sb.append("        return al;\n");
                    sb.append("    }\n\n");
                }
            } else if (f.isMap()) {
                String kBox = boxedType(f.mapKeyProto(), null);
                String vBox = boxedType(f.mapValProto(), f.subMessage());
                String lhmType = "LinkedHashMap<" + kBox + ", " + vBox + ">";
                sb.append("    public ").append(fType).append(" get").append(f.pascalName()).append("() {\n");
                sb.append("        if (this.").append(f.camelName()).append(" == null) {\n");
                sb.append("            this.").append(f.camelName()).append(" = new LinkedHashMap<>();\n");
                sb.append("        }\n");
                sb.append("        return this.").append(f.camelName()).append(";\n");
                sb.append("    }\n\n");

                sb.append("    ").append(lhmType).append(" ensure").append(f.pascalName()).append("(int minCap) {\n");
                sb.append("        ").append(fType).append(" cur = this.").append(f.camelName()).append(";\n");
                sb.append("        int cap = Math.min(minCap, 4096);\n");
                sb.append("        if (cur instanceof ").append(lhmType).append(" lhm) {\n");
                sb.append("            return lhm;\n");
                sb.append("        }\n");
                sb.append("        if (cur == null) {\n");
                sb.append("            ").append(lhmType).append(" lhm = new LinkedHashMap<>(Math.max(4, cap + (cap >>> 1) + 1));\n");
                sb.append("            this.").append(f.camelName()).append(" = lhm;\n");
                sb.append("            return lhm;\n");
                sb.append("        }\n");
                sb.append("        ").append(lhmType).append(" lhm = new LinkedHashMap<>(cur);\n");
                sb.append("        this.").append(f.camelName()).append(" = lhm;\n");
                sb.append("        return lhm;\n");
                sb.append("    }\n\n");
            } else {
                sb.append("    public ").append(fType).append(" get").append(f.pascalName()).append("() {\n");
                sb.append("        return this.").append(f.camelName()).append(";\n");
                sb.append("    }\n\n");

                if (f.oneof() != null) {
                    sb.append("    public boolean has").append(f.pascalName()).append("() {\n");
                    sb.append("        return this.").append(f.oneof().camelName()).append("Case == ")
                        .append(f.number()).append(";\n");
                    sb.append("    }\n\n");

                    sb.append("    public ").append(info.modelClass()).append(" clear").append(f.pascalName()).append("() {\n");
                    sb.append("        if (this.").append(f.oneof().camelName()).append("Case == ")
                        .append(f.number()).append(") {\n");
                    sb.append("            this.").append(f.camelName()).append(" = ")
                        .append(javaScalarResetExpr(f)).append(";\n");
                    sb.append("            this.").append(f.oneof().camelName()).append("Case = 0;\n");
                    sb.append("        }\n");
                    sb.append("        return this;\n");
                    sb.append("    }\n\n");
                } else if (f.hasExplicitScalarPresence()) {
                    sb.append("    public boolean has").append(f.pascalName()).append("() {\n");
                    sb.append("        return this.has_").append(f.camelName()).append(";\n");
                    sb.append("    }\n\n");

                    sb.append("    public ").append(info.modelClass()).append(" clear").append(f.pascalName()).append("() {\n");
                    sb.append("        this.").append(f.camelName()).append(" = ")
                        .append(javaScalarResetExpr(f)).append(";\n");
                    sb.append("        this.has_").append(f.camelName()).append(" = false;\n");
                    sb.append("        return this;\n");
                    sb.append("    }\n\n");
                } else if (f.proto().getType() == Type.TYPE_MESSAGE) {
                    sb.append("    public boolean has").append(f.pascalName()).append("() {\n");
                    sb.append("        return this.").append(f.camelName()).append(" != null;\n");
                    sb.append("    }\n\n");

                    sb.append("    public ").append(info.modelClass()).append(" clear").append(f.pascalName()).append("() {\n");
                    sb.append("        this.").append(f.camelName()).append(" = null;\n");
                    sb.append("        return this;\n");
                    sb.append("    }\n\n");
                }
            }

            sb.append("    public ")
                .append(info.modelClass())
                .append(" set")
                .append(f.pascalName())
                .append('(')
                .append(fType)
                .append(" value) {\n");
            if (f.oneof() != null) {
                if (f.proto().getType() == Type.TYPE_MESSAGE) {
                    sb.append("        if (value == null) {\n");
                    sb.append("            return clear").append(f.pascalName()).append("();\n");
                    sb.append("        }\n");
                }
                sb.append("        prepare").append(f.oneof().pascalName()).append('(')
                    .append(f.number()).append(");\n");
            } else if (f.hasExplicitScalarPresence()) {
                sb.append("        this.has_").append(f.camelName()).append(" = true;\n");
            }
            if (f.proto().getType() == Type.TYPE_STRING && !f.isRepeated() && !f.isMap()) {
                sb.append("        this.").append(f.camelName()).append(" = value == null ? \"\" : value;\n");
            } else if (f.proto().getType() == Type.TYPE_BYTES && !f.isRepeated() && !f.isMap()) {
                sb.append("        this.").append(f.camelName()).append(" = value == null ? EMPTY_BYTES : value;\n");
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
        sb.append("    }\n\n");

        emitModelEqualsHashCodeToString(sb, info, fields, oneofs);

        sb.append("}\n");
        return sb.toString();
    }

    private static void emitModelEqualsHashCodeToString(
            StringBuilder sb, MessageInfo info, List<ResolvedField> fields, List<OneofInfo> oneofs) {
        boolean needsListHelper = false;
        boolean needsBytesListHelper = false;
        boolean needsMapHelper = false;
        boolean needsBytesMapHelper = false;
        for (ResolvedField f : fields) {
            if (f.isRepeated()) {
                if (f.proto().getType() == Type.TYPE_BYTES) {
                    needsBytesListHelper = true;
                } else {
                    needsListHelper = true;
                }
            } else if (f.isMap()) {
                if (f.mapValProto().getType() == Type.TYPE_BYTES) {
                    needsBytesMapHelper = true;
                } else {
                    needsMapHelper = true;
                }
            }
        }

        sb.append("    @Override\n");
        sb.append("    public boolean equals(Object obj) {\n");
        sb.append("        if (this == obj) {\n");
        sb.append("            return true;\n");
        sb.append("        }\n");
        if (fields.isEmpty() && oneofs.isEmpty()) {
            sb.append("        return obj instanceof ").append(info.modelClass()).append(";\n");
            sb.append("    }\n\n");
        } else {
            sb.append("        if (!(obj instanceof ").append(info.modelClass()).append(" other)) {\n");
            sb.append("            return false;\n");
            sb.append("        }\n");
            sb.append("        return ");
            boolean first = true;
            for (ResolvedField f : fields) {
                if (!first) {
                    sb.append("\n            && ");
                }
                first = false;
                String name = f.camelName();
                if (f.isRepeated()) {
                    if (f.proto().getType() == Type.TYPE_BYTES) {
                        sb.append("bytesListEquals(this.").append(name).append(", other.").append(name).append(')');
                    } else {
                        sb.append("listEquals(this.").append(name).append(", other.").append(name).append(')');
                    }
                } else if (f.isMap()) {
                    if (f.mapValProto().getType() == Type.TYPE_BYTES) {
                        sb.append("bytesMapEquals(this.").append(name).append(", other.").append(name).append(')');
                    } else {
                        sb.append("mapEquals(this.").append(name).append(", other.").append(name).append(')');
                    }
                } else {
                    switch (f.proto().getType()) {
                        case TYPE_BYTES -> sb.append("java.util.Arrays.equals(this.").append(name)
                            .append(", other.").append(name).append(')');
                        case TYPE_STRING, TYPE_MESSAGE -> sb.append("java.util.Objects.equals(this.").append(name)
                            .append(", other.").append(name).append(')');
                        case TYPE_FLOAT -> sb.append("Float.floatToIntBits(this.").append(name)
                            .append(") == Float.floatToIntBits(other.").append(name).append(')');
                        case TYPE_DOUBLE -> sb.append("Double.doubleToLongBits(this.").append(name)
                            .append(") == Double.doubleToLongBits(other.").append(name).append(')');
                        default -> sb.append("this.").append(name).append(" == other.").append(name);
                    }
                }
                if (f.hasExplicitScalarPresence()) {
                    sb.append("\n            && this.has_").append(name).append(" == other.has_").append(name);
                }
            }
            for (OneofInfo oi : oneofs) {
                if (!first) {
                    sb.append("\n            && ");
                }
                first = false;
                sb.append("this.").append(oi.camelName()).append("Case == other.")
                    .append(oi.camelName()).append("Case");
            }
            sb.append(";\n");
            sb.append("    }\n\n");
        }

        sb.append("    @Override\n");
        sb.append("    public int hashCode() {\n");
        if (fields.isEmpty() && oneofs.isEmpty()) {
            sb.append("        return 0;\n");
            sb.append("    }\n\n");
        } else {
            sb.append("        int result = 1;\n");
            for (ResolvedField f : fields) {
                String name = f.camelName();
                if (f.isRepeated()) {
                    if (f.proto().getType() == Type.TYPE_BYTES) {
                        sb.append("        result = 31 * result + bytesListHash(this.").append(name).append(");\n");
                    } else {
                        sb.append("        result = 31 * result + (this.").append(name)
                            .append(" == null || this.").append(name).append(".isEmpty() ? 0 : this.")
                            .append(name).append(".hashCode());\n");
                    }
                } else if (f.isMap()) {
                    if (f.mapValProto().getType() == Type.TYPE_BYTES) {
                        sb.append("        result = 31 * result + bytesMapHash(this.").append(name).append(");\n");
                    } else {
                        sb.append("        result = 31 * result + (this.").append(name)
                            .append(" == null || this.").append(name).append(".isEmpty() ? 0 : this.")
                            .append(name).append(".hashCode());\n");
                    }
                } else {
                    switch (f.proto().getType()) {
                        case TYPE_BYTES -> sb.append("        result = 31 * result + java.util.Arrays.hashCode(this.")
                            .append(name).append(");\n");
                        case TYPE_STRING, TYPE_MESSAGE -> sb.append("        result = 31 * result + java.util.Objects.hashCode(this.")
                            .append(name).append(");\n");
                        case TYPE_FLOAT -> sb.append("        result = 31 * result + Float.hashCode(this.")
                            .append(name).append(");\n");
                        case TYPE_DOUBLE -> sb.append("        result = 31 * result + Double.hashCode(this.")
                            .append(name).append(");\n");
                        case TYPE_BOOL -> sb.append("        result = 31 * result + Boolean.hashCode(this.")
                            .append(name).append(");\n");
                        case TYPE_INT64, TYPE_UINT64, TYPE_SINT64, TYPE_FIXED64, TYPE_SFIXED64 ->
                            sb.append("        result = 31 * result + Long.hashCode(this.")
                                .append(name).append(");\n");
                        default -> sb.append("        result = 31 * result + Integer.hashCode(this.")
                            .append(name).append(");\n");
                    }
                }
                if (f.hasExplicitScalarPresence()) {
                    sb.append("        result = 31 * result + Boolean.hashCode(this.has_")
                        .append(name).append(");\n");
                }
            }
            for (OneofInfo oi : oneofs) {
                sb.append("        result = 31 * result + Integer.hashCode(this.")
                    .append(oi.camelName()).append("Case);\n");
            }
            sb.append("        return result;\n");
            sb.append("    }\n\n");
        }

        sb.append("    @Override\n");
        sb.append("    public String toString() {\n");
        if (fields.isEmpty()) {
            sb.append("        return \"").append(info.modelClass()).append("{}\";\n");
            sb.append("    }\n");
        } else {
            sb.append("        StringBuilder sb = new StringBuilder(\"").append(info.modelClass()).append("{\");\n");
            for (int i = 0; i < fields.size(); i++) {
                ResolvedField f = fields.get(i);
                String name = f.camelName();
                sb.append("        sb.append(\"").append(i == 0 ? "" : ", ").append(name).append("=\");\n");
                if (!f.isRepeated() && !f.isMap() && f.proto().getType() == Type.TYPE_BYTES) {
                    sb.append("        sb.append(java.util.Arrays.toString(this.").append(name).append("));\n");
                } else if (!f.isRepeated() && !f.isMap() && f.proto().getType() == Type.TYPE_STRING) {
                    sb.append("        sb.append('\\'').append(this.").append(name).append(").append('\\'');\n");
                } else {
                    sb.append("        sb.append(this.").append(name).append(");\n");
                }
            }
            sb.append("        sb.append('}');\n");
            sb.append("        return sb.toString();\n");
            sb.append("    }\n");
        }

        if (needsListHelper) {
            sb.append("\n    private static boolean listEquals(List<?> a, List<?> b) {\n");
            sb.append("        if (a == null || a.isEmpty()) {\n");
            sb.append("            return b == null || b.isEmpty();\n");
            sb.append("        }\n");
            sb.append("        return b != null && a.equals(b);\n");
            sb.append("    }\n");
        }
        if (needsBytesListHelper) {
            sb.append("\n    private static boolean bytesListEquals(List<byte[]> a, List<byte[]> b) {\n");
            sb.append("        if (a == null || a.isEmpty()) {\n");
            sb.append("            return b == null || b.isEmpty();\n");
            sb.append("        }\n");
            sb.append("        if (b == null || a.size() != b.size()) {\n");
            sb.append("            return false;\n");
            sb.append("        }\n");
            sb.append("        for (int i = 0; i < a.size(); i++) {\n");
            sb.append("            if (!java.util.Arrays.equals(a.get(i), b.get(i))) {\n");
            sb.append("                return false;\n");
            sb.append("            }\n");
            sb.append("        }\n");
            sb.append("        return true;\n");
            sb.append("    }\n");
            sb.append("\n    private static int bytesListHash(List<byte[]> list) {\n");
            sb.append("        if (list == null || list.isEmpty()) {\n");
            sb.append("            return 0;\n");
            sb.append("        }\n");
            sb.append("        int h = 1;\n");
            sb.append("        for (byte[] item : list) {\n");
            sb.append("            h = 31 * h + java.util.Arrays.hashCode(item);\n");
            sb.append("        }\n");
            sb.append("        return h;\n");
            sb.append("    }\n");
        }
        if (needsMapHelper) {
            sb.append("\n    private static boolean mapEquals(Map<?, ?> a, Map<?, ?> b) {\n");
            sb.append("        if (a == null || a.isEmpty()) {\n");
            sb.append("            return b == null || b.isEmpty();\n");
            sb.append("        }\n");
            sb.append("        return b != null && a.equals(b);\n");
            sb.append("    }\n");
        }
        if (needsBytesMapHelper) {
            sb.append("\n    private static <K> boolean bytesMapEquals(Map<K, byte[]> a, Map<K, byte[]> b) {\n");
            sb.append("        if (a == null || a.isEmpty()) {\n");
            sb.append("            return b == null || b.isEmpty();\n");
            sb.append("        }\n");
            sb.append("        if (b == null || a.size() != b.size()) {\n");
            sb.append("            return false;\n");
            sb.append("        }\n");
            sb.append("        for (Map.Entry<K, byte[]> entry : a.entrySet()) {\n");
            sb.append("            if (!b.containsKey(entry.getKey()) || !java.util.Arrays.equals(entry.getValue(), b.get(entry.getKey()))) {\n");
            sb.append("                return false;\n");
            sb.append("            }\n");
            sb.append("        }\n");
            sb.append("        return true;\n");
            sb.append("    }\n");
            sb.append("\n    private static <K> int bytesMapHash(Map<K, byte[]> map) {\n");
            sb.append("        if (map == null || map.isEmpty()) {\n");
            sb.append("            return 0;\n");
            sb.append("        }\n");
            sb.append("        int h = 0;\n");
            sb.append("        for (Map.Entry<K, byte[]> entry : map.entrySet()) {\n");
            sb.append("            h += java.util.Objects.hashCode(entry.getKey()) ^ java.util.Arrays.hashCode(entry.getValue());\n");
            sb.append("        }\n");
            sb.append("        return h;\n");
            sb.append("    }\n");
        }
    }

    private static void emitOneofClearSwitch(
            StringBuilder sb, OneofInfo oi, List<ResolvedField> fields, String indent) {
        sb.append(indent).append("switch (this.").append(oi.camelName()).append("Case) {\n");
        for (ResolvedField f : fields) {
            if (f.oneof() != null && f.oneof().protoIndex() == oi.protoIndex()) {
                sb.append(indent).append("    case ").append(f.number()).append(" -> this.")
                    .append(f.camelName()).append(" = ").append(javaScalarResetExpr(f)).append(";\n");
            }
        }
        sb.append(indent).append("    default -> {}\n");
        sb.append(indent).append("}\n");
    }

    // ========================================================================
    // 2. Static Fast Codec (<Message>OmniBuf.java)
    // ========================================================================

    private static String emitCodecFile(String sourceProto, ResolvedMessage resolved) {
        MessageInfo info = resolved.info();
        List<ResolvedField> fields = resolved.fields();

        StringBuilder sb = new StringBuilder(4096);
        sb.append("// Generated by protoc-gen-omnibuf-java. DO NOT EDIT.\n");
        sb.append("// source: ").append(sourceProto).append("\n\n");
        if (!info.javaPackage().isEmpty()) {
            sb.append("package ").append(info.javaPackage()).append(";\n\n");
        }
        sb.append("import com.omnibuf.MarshalOptions;\n");
        sb.append("import com.omnibuf.SchemaMeta;\n");
        sb.append("import com.omnibuf.UnmarshalOptions;\n");
        sb.append("import com.omnibuf.wire.CodecPool;\n");
        sb.append("import com.omnibuf.wire.PrefixVarint;\n");
        boolean hasSubMessageFields = false;
        for (ResolvedField f : fields) {
            if (f.subMessage() != null) {
                hasSubMessageFields = true;
                break;
            }
        }

        sb.append("import com.omnibuf.wire.WireType;\n");
        sb.append("import java.util.HashSet;\n");
        sb.append("import java.util.LinkedHashMap;\n");
        sb.append("import java.util.List;\n");
        sb.append("import java.util.Map;\n");
        sb.append("import java.util.Set;\n\n");

        sb.append("public final class ").append(info.codecClass()).append(" {\n");
        sb.append("    private static volatile SchemaMeta SCHEMA_META;\n");
        sb.append("    private static volatile byte[] FIELD0_BYTES;\n");
        sb.append("    private static volatile Boolean CYCLIC_SCHEMA;\n\n");

        sb.append("    private ").append(info.codecClass()).append("() {}\n\n");

        sb.append("    public static SchemaMeta schemaMeta() {\n");
        sb.append("        SchemaMeta m = SCHEMA_META;\n");
        sb.append("        if (m == null) {\n");
        sb.append("            boolean[] cyclic = new boolean[1];\n");
        sb.append("            m = schemaMeta(1, new HashSet<>(), cyclic);\n");
        sb.append("            CYCLIC_SCHEMA = cyclic[0];\n");
        sb.append("            SCHEMA_META = m;\n");
        sb.append("        }\n");
        sb.append("        return m;\n");
        sb.append("    }\n\n");

        sb.append("    private static boolean isCyclicSchema() {\n");
        sb.append("        Boolean c = CYCLIC_SCHEMA;\n");
        sb.append("        if (c == null) {\n");
        sb.append("            schemaMeta();\n");
        sb.append("            c = CYCLIC_SCHEMA;\n");
        sb.append("        }\n");
        sb.append("        return Boolean.TRUE.equals(c);\n");
        sb.append("    }\n\n");

        sb.append("    public static SchemaMeta schemaMeta(int schemaDepth, Set<String> active, boolean[] cyclicOut) {\n");
        sb.append("        if (!active.add(\"").append(info.protoFullName()).append("\")) {\n");
        sb.append("            cyclicOut[0] = true;\n");
        sb.append("            return null;\n");
        sb.append("        }\n");
        sb.append("        if (schemaDepth + 1 > WireType.MAX_CONTAINER_DEPTH) {\n");
        sb.append("            active.remove(\"").append(info.protoFullName()).append("\");\n");
        sb.append("            return null;\n");
        sb.append("        }\n");
        sb.append("        try {\n");
        sb.append("            Map<Integer, String> fieldNames = new LinkedHashMap<>(").append(fields.size()).append(");\n");
        for (ResolvedField f : fields) {
            sb.append("            fieldNames.put(")
                .append(f.number())
                .append(", \"")
                .append(escapeJavaString(f.protoName()))
                .append("\");\n");
        }
        sb.append("            Map<Integer, SchemaMeta> children = new LinkedHashMap<>();\n");
        for (ResolvedField f : fields) {
            if (f.subMessage() != null) {
                sb.append("            SchemaMeta child_").append(f.number()).append(" = ")
                    .append(f.subMessage().qualifiedCodec())
                    .append(".schemaMeta(schemaDepth + 2, active, cyclicOut);\n");
                sb.append("            if (child_").append(f.number())
                    .append(" != null && schemaDepth + 3 <= WireType.MAX_CONTAINER_DEPTH) {\n");
                sb.append("                children.put(").append(f.number())
                    .append(", child_").append(f.number()).append(");\n");
                sb.append("            }\n");
            }
        }
        sb.append("            return new SchemaMeta(\"")
            .append(escapeJavaString(info.schemaTypeName()))
            .append("\", fieldNames, children);\n");
        sb.append("        } finally {\n");
        sb.append("            active.remove(\"").append(info.protoFullName()).append("\");\n");
        sb.append("        }\n");
        sb.append("    }\n\n");

        sb.append("    public static SchemaMeta instanceSchemaMeta(")
            .append(info.modelClass())
            .append(" msg, int schemaDepth) {\n");
        sb.append("        if (msg == null || schemaDepth + 1 > WireType.MAX_CONTAINER_DEPTH) {\n");
        sb.append("            return null;\n");
        sb.append("        }\n");
        sb.append("        Map<Integer, String> fieldNames = new LinkedHashMap<>(").append(fields.size()).append(");\n");
        for (ResolvedField f : fields) {
            sb.append("        fieldNames.put(")
                .append(f.number())
                .append(", \"")
                .append(escapeJavaString(f.protoName()))
                .append("\");\n");
        }
        sb.append("        Map<Integer, SchemaMeta> children = new LinkedHashMap<>();\n");
        if (hasSubMessageFields) {
            sb.append("        if (schemaDepth + 3 <= WireType.MAX_CONTAINER_DEPTH) {\n");
            for (ResolvedField f : fields) {
                if (f.subMessage() == null) {
                    continue;
                }
                String subModel = f.subMessage().qualifiedModel();
                String subCodec = f.subMessage().qualifiedCodec();
                if (f.isMap()) {
                    sb.append("            if (msg.").append(f.camelName())
                        .append(" != null && !msg.").append(f.camelName()).append(".isEmpty()) {\n");
                    sb.append("                SchemaMeta child = null;\n");
                    sb.append("                for (").append(subModel).append(" elem : msg.")
                        .append(f.camelName()).append(".values()) {\n");
                    sb.append("                    if (elem != null) {\n");
                    sb.append("                        child = SchemaMeta.merge(child, ")
                        .append(subCodec).append(".instanceSchemaMeta(elem, schemaDepth + 2));\n");
                    sb.append("                    }\n");
                    sb.append("                }\n");
                    sb.append("                if (child != null) {\n");
                    sb.append("                    children.put(").append(f.number()).append(", child);\n");
                    sb.append("                }\n");
                    sb.append("            }\n");
                } else if (f.isRepeated()) {
                    sb.append("            if (msg.").append(f.camelName())
                        .append(" != null && !msg.").append(f.camelName()).append(".isEmpty()) {\n");
                    sb.append("                SchemaMeta child = null;\n");
                    sb.append("                for (int i = 0, n = msg.").append(f.camelName())
                        .append(".size(); i < n; i++) {\n");
                    sb.append("                    ").append(subModel).append(" elem = msg.")
                        .append(f.camelName()).append(".get(i);\n");
                    sb.append("                    if (elem != null) {\n");
                    sb.append("                        child = SchemaMeta.merge(child, ")
                        .append(subCodec).append(".instanceSchemaMeta(elem, schemaDepth + 2));\n");
                    sb.append("                    }\n");
                    sb.append("                }\n");
                    sb.append("                if (child != null) {\n");
                    sb.append("                    children.put(").append(f.number()).append(", child);\n");
                    sb.append("                }\n");
                    sb.append("            }\n");
                } else if (f.oneof() != null) {
                    sb.append("            if (msg.").append(f.oneof().camelName())
                        .append("Case == ").append(f.number())
                        .append(" && msg.").append(f.camelName()).append(" != null) {\n");
                    sb.append("                SchemaMeta child = ").append(subCodec)
                        .append(".instanceSchemaMeta(msg.").append(f.camelName())
                        .append(", schemaDepth + 2);\n");
                    sb.append("                if (child != null) {\n");
                    sb.append("                    children.put(").append(f.number()).append(", child);\n");
                    sb.append("                }\n");
                    sb.append("            }\n");
                } else {
                    sb.append("            if (msg.").append(f.camelName()).append(" != null) {\n");
                    sb.append("                SchemaMeta child = ").append(subCodec)
                        .append(".instanceSchemaMeta(msg.").append(f.camelName())
                        .append(", schemaDepth + 2);\n");
                    sb.append("                if (child != null) {\n");
                    sb.append("                    children.put(").append(f.number()).append(", child);\n");
                    sb.append("                }\n");
                    sb.append("            }\n");
                }
            }
            sb.append("        }\n");
        }
        sb.append("        return new SchemaMeta(\"")
            .append(escapeJavaString(info.schemaTypeName()))
            .append("\", fieldNames, children);\n");
        sb.append("    }\n\n");

        sb.append("    private static byte[] field0Bytes() {\n");
        sb.append("        byte[] b = FIELD0_BYTES;\n");
        sb.append("        if (b == null) {\n");
        sb.append("            b = schemaMeta().encodeField0(true);\n");
        sb.append("            FIELD0_BYTES = b;\n");
        sb.append("        }\n");
        sb.append("        return b;\n");
        sb.append("    }\n\n");

        sb.append("    public static byte[] marshal(").append(info.modelClass()).append(" msg) {\n");
        sb.append("        return marshal(msg, MarshalOptions.DEFAULT);\n");
        sb.append("    }\n\n");

        sb.append("    public static byte[] marshal(")
            .append(info.modelClass())
            .append(" msg, MarshalOptions options) {\n");
        sb.append("        if (msg == null) {\n");
        sb.append("            throw new com.omnibuf.OmniBufException(\"omnibuf: cannot marshal null ")
            .append(escapeJavaString(info.simpleName()))
            .append("\");\n");
        sb.append("        }\n");
        sb.append("        MarshalOptions opts = options == null ? MarshalOptions.DEFAULT : options;\n");
        sb.append("        com.omnibuf.wire.OmniBufEncoder enc = CodecPool.acquireEncoder();\n");
        sb.append("        try {\n");
        sb.append("            if (opts.selfDescribing()) {\n");
        sb.append("                if (isCyclicSchema()) {\n");
        sb.append("                    SchemaMeta dyn = instanceSchemaMeta(msg, 1);\n");
        sb.append("                    if (dyn != null) {\n");
        sb.append("                        dyn.writeField0(enc, opts.deterministic());\n");
        sb.append("                    }\n");
        sb.append("                } else if (opts.deterministic()) {\n");
        sb.append("                    enc.writeRawBytes(field0Bytes());\n");
        sb.append("                } else {\n");
        sb.append("                    schemaMeta().writeField0(enc, false);\n");
        sb.append("                }\n");
        sb.append("            }\n");
        sb.append("            encode(enc, msg, opts.deterministic());\n");
        sb.append("            return enc.toByteArray();\n");
        sb.append("        } finally {\n");
        sb.append("            CodecPool.releaseEncoder(enc);\n");
        sb.append("        }\n");
        sb.append("    }\n\n");

        emitIsLargePayloadMethods(sb, info, fields);

        sb.append("    public static void encode(com.omnibuf.wire.OmniBufEncoder enc, ")
            .append(info.modelClass())
            .append(" msg, boolean deterministic) {\n");
        int idx = 0;
        while (idx < fields.size()) {
            ResolvedField f = fields.get(idx);
            if (isUnconditionalFusableScalar(f)) {
                int runEnd = idx + 1;
                while (runEnd < fields.size()
                        && (runEnd - idx) < 32
                        && isUnconditionalFusableScalar(fields.get(runEnd))) {
                    runEnd++;
                }
                int maxBytes = 0;
                for (int k = idx; k < runEnd; k++) {
                    maxBytes += maxEncodedScalarBytes(fields.get(k));
                }
                sb.append("        {\n");
                sb.append("            byte[] dst = enc.ensureFree(").append(maxBytes).append(");\n");
                sb.append("            int p = enc.rawPosition();\n");
                for (int k = idx; k < runEnd; k++) {
                    ResolvedField sf = fields.get(k);
                    emitFusedScalarWrite(sb, sf, "msg." + sf.camelName(), "            ");
                }
                sb.append("            enc.setRawPosition(p);\n");
                sb.append("        }\n");
                idx = runEnd;
                continue;
            }
            if (f.oneof() != null) {
                sb.append("        if (msg.").append(f.oneof().camelName()).append("Case == ")
                    .append(f.number()).append(") {\n");
                emitFieldEncode(sb, info, f, "msg." + f.camelName(), "            ");
                sb.append("        }\n");
            } else if (f.hasExplicitScalarPresence()) {
                sb.append("        if (msg.has_").append(f.camelName()).append(") {\n");
                emitFieldEncode(sb, info, f, "msg." + f.camelName(), "            ");
                sb.append("        }\n");
            } else {
                emitFieldEncode(sb, info, f, "msg." + f.camelName(), "        ");
            }
            idx++;
        }
        sb.append("    }\n\n");

        sb.append("    public static ").append(info.modelClass()).append(" unmarshal(byte[] data) {\n");
        sb.append("        return unmarshal(data, UnmarshalOptions.DEFAULT);\n");
        sb.append("    }\n\n");

        sb.append("    public static ").append(info.modelClass()).append(" unmarshal(byte[] data, UnmarshalOptions options) {\n");
        sb.append("        if (data == null) {\n");
        sb.append("            throw new com.omnibuf.OmniBufException(\"omnibuf: null packet buffer\");\n");
        sb.append("        }\n");
        sb.append("        UnmarshalOptions opts = options == null ? UnmarshalOptions.DEFAULT : options;\n");
        sb.append("        if (data.length > opts.maxMessageBytes()) {\n");
        sb.append("            throw new com.omnibuf.OmniBufException(\n");
        sb.append("                \"omnibuf: packet length \" + data.length + \" exceeds maxMessageBytes (\" + opts.maxMessageBytes() + \")\"\n");
        sb.append("            );\n");
        sb.append("        }\n");
        sb.append("        ").append(info.modelClass()).append(" out = new ").append(info.modelClass()).append("();\n");
        sb.append("        decodeSlice(data, 0, data.length, out, 0, opts.maxNestingDepth(), opts.maxCollectionElements());\n");
        sb.append("        return out;\n");
        sb.append("    }\n\n");

        sb.append("    public static void unmarshal(byte[] data, ").append(info.modelClass()).append(" target) {\n");
        sb.append("        unmarshal(data, target, UnmarshalOptions.DEFAULT);\n");
        sb.append("    }\n\n");

        sb.append("    public static void unmarshal(byte[] data, ")
            .append(info.modelClass())
            .append(" target, UnmarshalOptions options) {\n");
        sb.append("        if (data == null) {\n");
        sb.append("            throw new com.omnibuf.OmniBufException(\"omnibuf: null packet buffer\");\n");
        sb.append("        }\n");
        sb.append("        if (target == null) {\n");
        sb.append("            throw new com.omnibuf.OmniBufException(\"omnibuf: null target instance\");\n");
        sb.append("        }\n");
        sb.append("        UnmarshalOptions opts = options == null ? UnmarshalOptions.DEFAULT : options;\n");
        sb.append("        if (data.length > opts.maxMessageBytes()) {\n");
        sb.append("            throw new com.omnibuf.OmniBufException(\n");
        sb.append("                \"omnibuf: packet length \" + data.length + \" exceeds maxMessageBytes (\" + opts.maxMessageBytes() + \")\"\n");
        sb.append("            );\n");
        sb.append("        }\n");
        sb.append("        target.reset();\n");
        sb.append("        decodeSlice(data, 0, data.length, target, 0, opts.maxNestingDepth(), opts.maxCollectionElements());\n");
        sb.append("    }\n\n");

        sb.append("    public static void decodeInto(\n");
        sb.append("            byte[] data, int offset, int length, ")
            .append(info.modelClass())
            .append(" target, int depth) {\n");
        sb.append("        if (data == null) {\n");
        sb.append("            throw new com.omnibuf.OmniBufException(\"omnibuf: null packet buffer\");\n");
        sb.append("        }\n");
        sb.append("        if (offset < 0 || length < 0 || length > data.length - offset) {\n");
        sb.append("            throw new com.omnibuf.OmniBufException(\"omnibuf: packet slice out of bounds\");\n");
        sb.append("        }\n");
        sb.append("        if (length > UnmarshalOptions.DEFAULT_MAX_MESSAGE_BYTES) {\n");
        sb.append("            throw new com.omnibuf.OmniBufException(\n");
        sb.append("                \"omnibuf: packet length \" + length + \" exceeds maxMessageBytes (\" + UnmarshalOptions.DEFAULT_MAX_MESSAGE_BYTES + \")\"\n");
        sb.append("            );\n");
        sb.append("        }\n");
        sb.append("        decodeSlice(\n");
        sb.append("            data, offset, offset + length, target, depth,\n");
        sb.append("            UnmarshalOptions.DEFAULT_MAX_NESTING_DEPTH, UnmarshalOptions.DEFAULT_MAX_COLLECTION_ELEMENTS\n");
        sb.append("        );\n");
        sb.append("    }\n\n");

        sb.append("    public static void decodeFrom(com.omnibuf.wire.OmniBufDecoder dec, ")
            .append(info.modelClass())
            .append(" target, int depth) {\n");
        sb.append("        decodeSlice(\n");
        sb.append("            dec.buffer(), dec.position(), dec.limit(), target, depth,\n");
        sb.append("            dec.maxNestingDepth(), dec.maxCollectionElements()\n");
        sb.append("        );\n");
        sb.append("        dec.position(dec.limit());\n");
        sb.append("    }\n\n");

        boolean emitSequentialFastPath = resolved.oneofs().isEmpty() && !fields.isEmpty() && fields.size() <= 16;
        if (emitSequentialFastPath) {
            for (ResolvedField f : fields) {
                if (f.isMap() || f.number() >= 1024) {
                    emitSequentialFastPath = false;
                    break;
                }
            }
        }

        sb.append("    public static void decodeSlice(\n");
        sb.append("            byte[] buf, int pos, int lim, ")
            .append(info.modelClass())
            .append(" target, int depth, int maxDepth, int maxElems) {\n");
        sb.append("        if (depth > maxDepth) {\n");
        sb.append("            com.omnibuf.wire.OmniBufDecoder.throwMaxNestingDepth();\n");
        sb.append("        }\n");
        if (emitSequentialFastPath) {
            for (ResolvedField f : fields) {
                emitSequentialFieldDecode(sb, f);
            }
            sb.append("        if (pos < lim) {\n");
            sb.append("            decodeSliceSlow(buf, pos, lim, target, depth, maxDepth, maxElems);\n");
            sb.append("        }\n");
            sb.append("    }\n\n");

            sb.append("    private static void decodeSliceSlow(\n");
            sb.append("            byte[] buf, int pos, int lim, ")
                .append(info.modelClass())
                .append(" target, int depth, int maxDepth, int maxElems) {\n");
        }
        sb.append("        while (pos < lim) {\n");
        sb.append("            int b0 = buf[pos] & 0xFF;\n");
        sb.append("            int fnum;\n");
        sb.append("            int wt;\n");
        sb.append("            if ((b0 & 1) != 0) {\n");
        sb.append("                pos++;\n");
        sb.append("                fnum = b0 >>> 5;\n");
        sb.append("                wt = (b0 >>> 1) & 0x0F;\n");
        sb.append("            } else if ((b0 & 2) != 0 && lim - pos >= 2) {\n");
        sb.append("                int tag2 = Short.toUnsignedInt(PrefixVarint.getShortLE(buf, pos)) >>> 2;\n");
        sb.append("                pos += 2;\n");
        sb.append("                fnum = tag2 >>> 4;\n");
        sb.append("                wt = tag2 & 0x0F;\n");
        sb.append("            } else {\n");
        sb.append("                long tagSlow = com.omnibuf.wire.OmniBufDecoder.readTagSlowSlice(buf, pos, lim);\n");
        sb.append("                pos += PrefixVarint.widthFromLeadingByte(b0);\n");
        sb.append("                fnum = (int) (tagSlow >>> 4);\n");
        sb.append("                wt = (int) (tagSlow & 0x0FL);\n");
        sb.append("            }\n");
        sb.append("            switch (fnum) {\n");
        sb.append("                case 0 -> pos = com.omnibuf.wire.OmniBufDecoder.skipOrValidateUnknownSlice(buf, pos, lim, 0, wt, depth, maxDepth, maxElems);\n");
        for (ResolvedField f : fields) {
            emitFieldDecodeCase(sb, f);
        }
        sb.append("                default -> pos = com.omnibuf.wire.OmniBufDecoder.skipOrValidateUnknownSlice(buf, pos, lim, fnum, wt, depth, maxDepth, maxElems);\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("    }\n");

        sb.append("}\n");
        return sb.toString();
    }

    private static boolean needsCodecPrefixVarintImport(List<ResolvedField> fields) {
        if (needsPrefixVarintImport(fields)) {
            return true;
        }
        for (ResolvedField f : fields) {
            if (isUnconditionalFusableScalar(f)) {
                if (f.proto().getType() != Type.TYPE_BOOL || f.number() >= 8) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isRepeatedInt32Scalar(ResolvedField f) {
        if (!f.isRepeated()) {
            return false;
        }
        return switch (f.proto().getType()) {
            case TYPE_INT32, TYPE_SINT32, TYPE_UINT32, TYPE_FIXED32, TYPE_SFIXED32, TYPE_ENUM -> true;
            default -> false;
        };
    }

    private static boolean isRepeatedInt64Scalar(ResolvedField f) {
        if (!f.isRepeated()) {
            return false;
        }
        return switch (f.proto().getType()) {
            case TYPE_INT64, TYPE_SINT64, TYPE_UINT64, TYPE_FIXED64, TYPE_SFIXED64 -> true;
            default -> false;
        };
    }

    private static boolean isUnconditionalFusableScalar(ResolvedField f) {
        if (f.isMap() || f.isRepeated() || f.oneof() != null || f.hasExplicitScalarPresence()) {
            return false;
        }
        return switch (f.proto().getType()) {
            case TYPE_UINT32,
                 TYPE_UINT64,
                 TYPE_INT32,
                 TYPE_SINT32,
                 TYPE_ENUM,
                 TYPE_INT64,
                 TYPE_SINT64,
                 TYPE_BOOL,
                 TYPE_FIXED32,
                 TYPE_SFIXED32,
                 TYPE_FLOAT,
                 TYPE_FIXED64,
                 TYPE_SFIXED64,
                 TYPE_DOUBLE -> true;
            default -> false;
        };
    }

    private static int encodedTagByteLength(int fnum) {
        if (fnum < 8) {
            return 1;
        }
        if (fnum < 1024) {
            return 2;
        }
        if (fnum < 33_554_432) {
            return 4;
        }
        return 9;
    }

    private static int maxEncodedScalarBytes(ResolvedField f) {
        int tagBytes = encodedTagByteLength(f.number());
        return tagBytes + switch (f.proto().getType()) {
            case TYPE_BOOL -> 0;
            case TYPE_FIXED32, TYPE_SFIXED32, TYPE_FLOAT -> 4;
            case TYPE_FIXED64, TYPE_SFIXED64, TYPE_DOUBLE -> 8;
            default -> 9;
        };
    }

    private static int wireTypeInt(Type t) {
        return switch (t) {
            case TYPE_UINT32, TYPE_UINT64 -> WireType.WIRE_UINT;
            case TYPE_INT32, TYPE_INT64, TYPE_SINT32, TYPE_SINT64, TYPE_ENUM -> WireType.WIRE_SINT;
            case TYPE_BOOL -> WireType.WIRE_BOOL_FALSE;
            case TYPE_FIXED32 -> WireType.WIRE_FIXED32;
            case TYPE_SFIXED32 -> WireType.WIRE_SFIXED32;
            case TYPE_FLOAT -> WireType.WIRE_FLOAT32;
            case TYPE_FIXED64 -> WireType.WIRE_FIXED64;
            case TYPE_SFIXED64 -> WireType.WIRE_SFIXED64;
            case TYPE_DOUBLE -> WireType.WIRE_FLOAT64;
            case TYPE_STRING -> WireType.WIRE_STRING;
            case TYPE_BYTES -> WireType.WIRE_BYTES;
            case TYPE_MESSAGE -> WireType.WIRE_SUB_MSG;
            default -> throw new IllegalStateException("Unsupported wireTypeInt: " + t);
        };
    }

    private static String tag1BLiteral(int fnum, int wt) {
        int b = ((((fnum << 4) | wt) << 1) | 1) & 0xFF;
        return "(byte) 0x" + Integer.toHexString(b);
    }

    private static String tag2BLiteral(int fnum, int wt) {
        int s = ((((fnum << 4) | wt) << 2) | 2) & 0xFFFF;
        return "(short) 0x" + Integer.toHexString(s);
    }

    private static String tag4BLiteral(int fnum, int wt) {
        int v = (int) (((((long) fnum << 4) | wt) << 3) | 4L);
        return "0x" + Integer.toHexString(v);
    }

    private static String tag9BPayloadLiteral(int fnum, int wt) {
        long v = (Integer.toUnsignedLong(fnum) << 4) | (wt & 0x0FL);
        return "0x" + Long.toHexString(v) + "L";
    }

    private static void emitConstTagWriteToDst(StringBuilder sb, int fnum, int wt, String indent) {
        if (fnum < 8) {
            sb.append(indent).append("dst[p++] = ").append(tag1BLiteral(fnum, wt)).append(";\n");
        } else if (fnum < 1024) {
            sb.append(indent).append("PrefixVarint.setShortLE(dst, p, ")
                .append(tag2BLiteral(fnum, wt)).append(");\n");
            sb.append(indent).append("p += 2;\n");
        } else if (fnum < 33_554_432) {
            sb.append(indent).append("PrefixVarint.setIntLE(dst, p, ")
                .append(tag4BLiteral(fnum, wt)).append(");\n");
            sb.append(indent).append("p += 4;\n");
        } else {
            sb.append(indent).append("dst[p] = 0;\n");
            sb.append(indent).append("PrefixVarint.setLongLE(dst, p + 1, ")
                .append(tag9BPayloadLiteral(fnum, wt)).append(");\n");
            sb.append(indent).append("p += 9;\n");
        }
    }

    private static void emitFusedScalarWrite(
            StringBuilder sb, ResolvedField f, String expr, String indent) {
        int fnum = f.number();
        Type t = f.proto().getType();
        if (t == Type.TYPE_BOOL) {
            if (fnum < 8) {
                sb.append(indent).append("dst[p++] = ").append(expr).append(" ? ")
                    .append(tag1BLiteral(fnum, WireType.WIRE_BOOL_TRUE)).append(" : ")
                    .append(tag1BLiteral(fnum, WireType.WIRE_BOOL_FALSE)).append(";\n");
            } else if (fnum < 1024) {
                sb.append(indent).append("PrefixVarint.setShortLE(dst, p, ").append(expr).append(" ? ")
                    .append(tag2BLiteral(fnum, WireType.WIRE_BOOL_TRUE)).append(" : ")
                    .append(tag2BLiteral(fnum, WireType.WIRE_BOOL_FALSE)).append(");\n");
                sb.append(indent).append("p += 2;\n");
            } else if (fnum < 33_554_432) {
                sb.append(indent).append("PrefixVarint.setIntLE(dst, p, ").append(expr).append(" ? ")
                    .append(tag4BLiteral(fnum, WireType.WIRE_BOOL_TRUE)).append(" : ")
                    .append(tag4BLiteral(fnum, WireType.WIRE_BOOL_FALSE)).append(");\n");
                sb.append(indent).append("p += 4;\n");
            } else {
                sb.append(indent).append("dst[p] = 0;\n");
                sb.append(indent).append("PrefixVarint.setLongLE(dst, p + 1, ").append(expr).append(" ? ")
                    .append(tag9BPayloadLiteral(fnum, WireType.WIRE_BOOL_TRUE)).append(" : ")
                    .append(tag9BPayloadLiteral(fnum, WireType.WIRE_BOOL_FALSE)).append(");\n");
                sb.append(indent).append("p += 9;\n");
            }
            return;
        }
        emitConstTagWriteToDst(sb, fnum, wireTypeInt(t), indent);
        switch (t) {
            case TYPE_UINT32 -> sb.append(indent)
                .append("p = PrefixVarint.write(dst, p, Integer.toUnsignedLong(").append(expr).append("));\n");
            case TYPE_UINT64 -> sb.append(indent)
                .append("p = PrefixVarint.write(dst, p, ").append(expr).append(");\n");
            case TYPE_INT32, TYPE_SINT32, TYPE_ENUM -> sb.append(indent)
                .append("p = PrefixVarint.write(dst, p, PrefixVarint.encodeZigZag32(").append(expr).append("));\n");
            case TYPE_INT64, TYPE_SINT64 -> sb.append(indent)
                .append("p = PrefixVarint.write(dst, p, PrefixVarint.encodeZigZag64(").append(expr).append("));\n");
            case TYPE_FIXED32, TYPE_SFIXED32 -> {
                sb.append(indent).append("PrefixVarint.setIntLE(dst, p, ").append(expr).append(");\n");
                sb.append(indent).append("p += 4;\n");
            }
            case TYPE_FLOAT -> {
                sb.append(indent).append("PrefixVarint.setIntLE(dst, p, Float.floatToRawIntBits(").append(expr).append("));\n");
                sb.append(indent).append("p += 4;\n");
            }
            case TYPE_FIXED64, TYPE_SFIXED64 -> {
                sb.append(indent).append("PrefixVarint.setLongLE(dst, p, ").append(expr).append(");\n");
                sb.append(indent).append("p += 8;\n");
            }
            case TYPE_DOUBLE -> {
                sb.append(indent).append("PrefixVarint.setLongLE(dst, p, Double.doubleToRawLongBits(").append(expr).append("));\n");
                sb.append(indent).append("p += 8;\n");
            }
            default -> throw new IllegalStateException("Unsupported fused scalar: " + t);
        }
    }

    private static void emitIsLargePayloadMethods(
            StringBuilder sb, MessageInfo info, List<ResolvedField> fields) {
        sb.append("    public static boolean isLargePayload(").append(info.modelClass()).append(" msg) {\n");
        sb.append("        return isLargePayload(msg, 2);\n");
        sb.append("    }\n\n");

        sb.append("    public static boolean isLargePayload(")
            .append(info.modelClass()).append(" msg, int depthLeft) {\n");
        sb.append("        if (msg == null || depthLeft < 0) {\n");
        sb.append("            return false;\n");
        sb.append("        }\n");

        List<String> lengthExprs = new ArrayList<>();
        List<ResolvedField> subFields = new ArrayList<>();
        for (ResolvedField f : fields) {
            if (f.isMap()) {
                continue;
            }
            if (!f.isRepeated()
                    && (f.proto().getType() == Type.TYPE_STRING || f.proto().getType() == Type.TYPE_BYTES)) {
                String lenSuffix = f.proto().getType() == Type.TYPE_STRING ? ".length()" : ".length";
                String ref = "msg." + f.camelName();
                if (f.oneof() != null) {
                    lengthExprs.add(
                        "(msg." + f.oneof().camelName() + "Case == " + f.number()
                            + " && " + ref + " != null ? " + ref + lenSuffix + " : 0)"
                    );
                } else if (f.hasExplicitScalarPresence()) {
                    lengthExprs.add(
                        "(msg.has_" + f.camelName() + " && " + ref + " != null ? " + ref + lenSuffix + " : 0)"
                    );
                } else {
                    lengthExprs.add("(" + ref + " == null ? 0 : " + ref + lenSuffix + ")");
                }
            } else if (f.proto().getType() == Type.TYPE_MESSAGE && f.subMessage() != null) {
                subFields.add(f);
            }
        }

        for (int i = 0; i < lengthExprs.size(); i += 16) {
            int end = Math.min(lengthExprs.size(), i + 16);
            if (end - i == 1) {
                sb.append("        if (").append(lengthExprs.get(i)).append(" > 16_383) {\n");
            } else {
                sb.append("        if ((").append(lengthExprs.get(i));
                for (int j = i + 1; j < end; j++) {
                    sb.append("\n                | ").append(lengthExprs.get(j));
                }
                sb.append(") > 16_383) {\n");
            }
            sb.append("            return true;\n");
            sb.append("        }\n");
        }

        if (!subFields.isEmpty()) {
            sb.append("        if (depthLeft > 0) {\n");
            for (ResolvedField sf : subFields) {
                String subCodec = sf.subMessage().qualifiedCodec();
                String ref = "msg." + sf.camelName();
                if (sf.isRepeated()) {
                    sb.append("            if (").append(ref).append(" != null) {\n");
                    sb.append("                for (int i = 0, n = ").append(ref).append(".size(); i < n; i++) {\n");
                    sb.append("                    if (").append(subCodec)
                        .append(".isLargePayload(").append(ref).append(".get(i), depthLeft - 1)) {\n");
                    sb.append("                        return true;\n");
                    sb.append("                    }\n");
                    sb.append("                }\n");
                    sb.append("            }\n");
                } else if (sf.oneof() != null) {
                    sb.append("            if (msg.").append(sf.oneof().camelName()).append("Case == ")
                        .append(sf.number()).append(" && ").append(ref).append(" != null && ")
                        .append(subCodec).append(".isLargePayload(").append(ref).append(", depthLeft - 1)) {\n");
                    sb.append("                return true;\n");
                    sb.append("            }\n");
                } else {
                    sb.append("            if (").append(ref).append(" != null && ")
                        .append(subCodec).append(".isLargePayload(").append(ref).append(", depthLeft - 1)) {\n");
                    sb.append("                return true;\n");
                    sb.append("            }\n");
                }
            }
            sb.append("        }\n");
        }

        sb.append("        return false;\n");
        sb.append("    }\n\n");
    }

    private static boolean needsPrefixVarintImport(List<ResolvedField> fields) {
        for (ResolvedField f : fields) {
            if (f.isMap()) {
                if (!(f.mapKeyProto().getType() == Type.TYPE_STRING
                        && f.mapValProto().getType() == Type.TYPE_UINT64)) {
                    if (isZigZagType(f.mapKeyProto().getType()) || isZigZagType(f.mapValProto().getType())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isZigZagType(Type t) {
        return t == Type.TYPE_INT32
            || t == Type.TYPE_INT64
            || t == Type.TYPE_SINT32
            || t == Type.TYPE_SINT64
            || t == Type.TYPE_ENUM;
    }

    private static void emitFieldEncode(
            StringBuilder sb, MessageInfo ownerInfo, ResolvedField f, String expr, String indent) {
        int fnum = f.number();
        if (f.isMap()) {
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
            String kBox = boxedType(f.mapKeyProto(), null);
            String vBox = boxedType(f.mapValProto(), f.subMessage());
            String keyWT = wireTypeConst(f.mapKeyProto().getType());
            String valWT = wireTypeConst(f.mapValProto().getType());
            String mapVar = "map" + fnum;
            sb.append(indent).append("{\n");
            sb.append(indent).append("    Map<").append(kBox).append(", ").append(vBox).append("> ")
                .append(mapVar).append(" = ").append(expr).append(";\n");
            sb.append(indent).append("    int n = ").append(mapVar).append(" == null ? 0 : ")
                .append(mapVar).append(".size();\n");
            sb.append(indent).append("    int mapPos = enc.beginTypedMapFast(")
                .append(fnum).append(", ").append(keyWT).append(", ").append(valWT).append(", n);\n");
            sb.append(indent).append("    if (n > 0) {\n");
            sb.append(indent).append("        for (Map.Entry<").append(kBox).append(", ").append(vBox)
                .append("> entry : com.omnibuf.wire.OmniBufEncoder.sortMapEntries(")
                .append(mapVar).append(", ").append(keyWT).append(", deterministic)) {\n");
            emitInlineMapEntryWrite(sb, ownerInfo, f.mapKeyProto(), null, "entry.getKey()", "k" + fnum, indent + "            ");
            emitInlineMapEntryWrite(sb, ownerInfo, f.mapValProto(), f.subMessage(), "entry.getValue()", "v" + fnum, indent + "            ");
            sb.append(indent).append("        }\n");
            sb.append(indent).append("    }\n");
            sb.append(indent).append("    enc.endContainerAt(mapPos);\n");
            sb.append(indent).append("}\n");
            return;
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
                    sb.append(indent).append("    boolean largeArr = false;\n");
                    sb.append(indent).append("    for (int i = 0; i < n; i++) {\n");
                    sb.append(indent).append("        if (").append(subCodec)
                        .append(".isLargePayload(").append(varName).append(".get(i))) {\n");
                    sb.append(indent).append("            largeArr = true;\n");
                    sb.append(indent).append("            break;\n");
                    sb.append(indent).append("        }\n");
                    sb.append(indent).append("    }\n");
                    sb.append(indent).append("    int arrPos = enc.beginTypedArrayFast(").append(fnum).append(", WireType.WIRE_SUB_MSG, n, largeArr);\n");
                    sb.append(indent).append("    for (int i = 0; i < n; i++) {\n");
                    sb.append(indent).append("        ").append(subModel).append(" elem = ").append(varName).append(".get(i);\n");
                    sb.append(indent).append("        int elemPos = enc.beginInlineSubMsgFast(")
                        .append(subCodec).append(".isLargePayload(elem));\n");
                    sb.append(indent).append("        if (elem != null) {\n");
                    sb.append(indent).append("            ").append(subCodec).append(".encode(enc, elem, deterministic);\n");
                    sb.append(indent).append("        }\n");
                    sb.append(indent).append("        enc.endContainerAt(elemPos);\n");
                    sb.append(indent).append("    }\n");
                    sb.append(indent).append("    enc.endContainerAt(arrPos);\n");
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
            case TYPE_STRING -> {
                if (fnum < 8) {
                    sb.append(indent).append("enc.writeString1BTag(")
                        .append(tag1BLiteral(fnum, WireType.WIRE_STRING)).append(", ").append(expr).append(");\n");
                } else if (fnum < 1024) {
                    sb.append(indent).append("enc.writeString2BTag(")
                        .append(tag2BLiteral(fnum, WireType.WIRE_STRING)).append(", ").append(expr).append(");\n");
                } else {
                    sb.append(indent).append("enc.writeString(").append(fnum).append(", ").append(expr).append(");\n");
                }
            }
            case TYPE_BYTES -> sb.append(indent).append("enc.writeBytes(").append(fnum).append(", ").append(expr).append(");\n");
            case TYPE_MESSAGE -> {
                String subModel = f.subMessage().qualifiedModel();
                String subCodec = f.subMessage().qualifiedCodec();
                String varName = "sub" + fnum;
                sb.append(indent).append("{\n");
                sb.append(indent).append("    ").append(subModel).append(' ').append(varName).append(" = ").append(expr).append(";\n");
                sb.append(indent).append("    if (").append(varName).append(" != null) {\n");
                sb.append(indent).append("        int subPos = enc.beginSubMsgFast(").append(fnum)
                    .append(", ").append(subCodec).append(".isLargePayload(").append(varName).append("));\n");
                sb.append(indent).append("        ").append(subCodec).append(".encode(enc, ").append(varName).append(", deterministic);\n");
                sb.append(indent).append("        enc.endContainerAt(subPos);\n");
                sb.append(indent).append("    }\n");
                sb.append(indent).append("}\n");
            }
            default -> throw new IllegalStateException("Unsupported singular field: " + f.protoName());
        }
    }

    private static void emitInlineMapEntryWrite(
            StringBuilder sb,
            MessageInfo ownerInfo,
            FieldDescriptorProto fd,
            MessageInfo sub,
            String valExpr,
            String localVar,
            String indent) {
        String box = boxedType(fd, sub);
        sb.append(indent).append(box).append(' ').append(localVar).append(" = ").append(valExpr).append(";\n");
        switch (fd.getType()) {
            case TYPE_STRING -> sb.append(indent)
                .append("enc.writeStringElement(").append(localVar).append(" == null ? \"\" : ").append(localVar).append(");\n");
            case TYPE_BYTES -> sb.append(indent)
                .append("enc.writeBytesElement(").append(localVar).append(" == null ? ")
                .append(ownerInfo.modelClass()).append(".EMPTY_BYTES : ").append(localVar).append(");\n");
            case TYPE_BOOL -> sb.append(indent)
                .append("enc.writeRawByte(Boolean.TRUE.equals(").append(localVar).append(") ? 1 : 0);\n");
            case TYPE_UINT32 -> sb.append(indent)
                .append("enc.writeVarint(Integer.toUnsignedLong(").append(localVar).append(" == null ? 0 : ").append(localVar).append("));\n");
            case TYPE_UINT64 -> sb.append(indent)
                .append("enc.writeVarint(").append(localVar).append(" == null ? 0L : ").append(localVar).append(");\n");
            case TYPE_INT32, TYPE_SINT32, TYPE_ENUM -> sb.append(indent)
                .append("enc.writeVarint(PrefixVarint.encodeZigZag32(").append(localVar).append(" == null ? 0 : ").append(localVar).append("));\n");
            case TYPE_INT64, TYPE_SINT64 -> sb.append(indent)
                .append("enc.writeVarint(PrefixVarint.encodeZigZag64(").append(localVar).append(" == null ? 0L : ").append(localVar).append("));\n");
            case TYPE_FIXED32, TYPE_SFIXED32 -> sb.append(indent)
                .append("enc.writeInlineFixed32(").append(localVar).append(" == null ? 0 : ").append(localVar).append(");\n");
            case TYPE_FLOAT -> sb.append(indent)
                .append("enc.writeInlineFixed32(Float.floatToRawIntBits(").append(localVar).append(" == null ? 0.0f : ").append(localVar).append("));\n");
            case TYPE_FIXED64, TYPE_SFIXED64 -> sb.append(indent)
                .append("enc.writeInlineFixed64(").append(localVar).append(" == null ? 0L : ").append(localVar).append(");\n");
            case TYPE_DOUBLE -> sb.append(indent)
                .append("enc.writeInlineFixed64(Double.doubleToRawLongBits(").append(localVar).append(" == null ? 0.0d : ").append(localVar).append("));\n");
            case TYPE_MESSAGE -> {
                sb.append(indent).append("int elemPos = enc.beginInlineSubMsgFast();\n");
                sb.append(indent).append("if (").append(localVar).append(" != null) {\n");
                sb.append(indent).append("    ").append(sub.qualifiedCodec())
                    .append(".encode(enc, ").append(localVar).append(", deterministic);\n");
                sb.append(indent).append("}\n");
                sb.append(indent).append("enc.endContainerAt(elemPos);\n");
            }
            default -> throw new IllegalStateException("Unsupported map entry type: " + fd.getType());
        }
    }

    private static void emitInlineVarintRead(StringBuilder sb, String varName, String posVar, String limVar, String indent) {
        sb.append(indent).append("long ").append(varName).append(";\n");
        sb.append(indent).append("{\n");
        sb.append(indent).append("    int vb0 = ").append(posVar).append(" < ").append(limVar).append(" ? (buf[").append(posVar).append("] & 0xFF) : 8;\n");
        sb.append(indent).append("    if ((vb0 & 1) != 0) {\n");
        sb.append(indent).append("        ").append(varName).append(" = vb0 >>> 1;\n");
        sb.append(indent).append("        ").append(posVar).append(" += 1;\n");
        sb.append(indent).append("    } else if ((vb0 & 2) != 0 && ").append(limVar).append(" - ").append(posVar).append(" >= 2) {\n");
        sb.append(indent).append("        ").append(varName).append(" = Short.toUnsignedLong(PrefixVarint.getShortLE(buf, ").append(posVar).append(")) >>> 2;\n");
        sb.append(indent).append("        ").append(posVar).append(" += 2;\n");
        sb.append(indent).append("    } else {\n");
        sb.append(indent).append("        ").append(varName).append(" = com.omnibuf.wire.OmniBufDecoder.readVarint4Or9(buf, ").append(posVar).append(", ").append(limVar).append(");\n");
        sb.append(indent).append("        ").append(posVar).append(" += (vb0 == 0 ? 9 : 4);\n");
        sb.append(indent).append("    }\n");
        sb.append(indent).append("}\n");
    }

    private static void emitInlineStringRead(StringBuilder sb, String targetAssign, String posVar, String limVar, String indent) {
        sb.append(indent).append("{\n");
        sb.append(indent).append("    long sp = com.omnibuf.wire.OmniBufDecoder.readStringSpan(buf, ").append(posVar).append(", ").append(limVar).append(");\n");
        sb.append(indent).append("    int ss = (int) (sp >>> 32);\n");
        sb.append(indent).append("    ").append(posVar).append(" = (int) sp;\n");
        sb.append(indent).append("    ").append(targetAssign).append("(com.omnibuf.wire.Utf8Validator.decodeStrictUnchecked(buf, ss, ").append(posVar).append(" - ss));\n");
        sb.append(indent).append("}\n");
    }

    private static void emitInlineLengthSlice(
            StringBuilder sb, String startVar, String endVar, String posVar, String limVar, int minLen, String indent) {
        String helper = minLen == 0
            ? "com.omnibuf.wire.OmniBufDecoder.readStringSpan"
            : (minLen == 2 ? "com.omnibuf.wire.OmniBufDecoder.readLengthSpanMin2" : null);
        if (helper != null) {
            sb.append(indent).append("long pk_").append(startVar).append(" = ").append(helper)
                .append("(buf, ").append(posVar).append(", ").append(limVar).append(");\n");
        } else {
            sb.append(indent).append("long pk_").append(startVar).append(" = PrefixVarint.readLengthSlicePacked(buf, ")
                .append(posVar).append(", ").append(limVar).append(", ").append(minLen).append(");\n");
        }
        sb.append(indent).append("int ").append(startVar).append(" = (int) (pk_").append(startVar).append(" >>> 32);\n");
        sb.append(indent).append("int ").append(endVar).append(" = (int) pk_").append(startVar).append(";\n");
        sb.append(indent).append(posVar).append(" = ").append(endVar).append(";\n");
    }

    private static void emitSequentialFieldDecode(StringBuilder sb, ResolvedField f) {
        int fnum = f.number();
        if (f.isRepeated()) {
            int wt = WireType.WIRE_ARRAY;
            if (fnum < 8) {
                sb.append("        if (pos < lim && buf[pos] == ").append(tag1BLiteral(fnum, wt)).append(") {\n");
                sb.append("            pos++;\n");
            } else {
                sb.append("        if (lim - pos >= 2 && PrefixVarint.getShortLE(buf, pos) == ").append(tag2BLiteral(fnum, wt)).append(") {\n");
                sb.append("            pos += 2;\n");
            }
            String elemWireConst = wireTypeConst(f.proto().getType());
            emitInlineLengthSlice(sb, "arrStart", "arrEnd", "pos", "lim", 2, "            ");
            sb.append("            long arrHdr = com.omnibuf.wire.OmniBufDecoder.readArrayHeaderPacked(buf, arrStart, arrEnd, ")
                .append(elemWireConst).append(", depth, maxDepth, maxElems);\n");
            sb.append("            int ap = (int) (arrHdr >>> 32);\n");
            sb.append("            int count = (int) arrHdr;\n");
            sb.append("            var list = target.ensure").append(f.pascalName()).append("(count);\n");
            sb.append("            if ((long) list.size() + count > maxElems) {\n");
            sb.append("                com.omnibuf.wire.OmniBufDecoder.throwMaxCollectionElements();\n");
            sb.append("            }\n");
            String bulkSlice = bulkRepeatedSliceMethod(f.proto().getType());
            if (bulkSlice != null) {
                sb.append("            ap = com.omnibuf.wire.OmniBufDecoder.").append(bulkSlice).append("(buf, ap, arrEnd, list, count);\n");
            } else {
                sb.append("            for (int i = 0; i < count; i++) {\n");
                switch (f.proto().getType()) {
                    case TYPE_MESSAGE -> {
                        String subModel = f.subMessage().qualifiedModel();
                        String subCodec = f.subMessage().qualifiedCodec();
                        emitInlineLengthSlice(sb, "es", "ee", "ap", "arrEnd", 0, "                ");
                        sb.append("                ").append(subModel).append(" elem = new ").append(subModel).append("();\n");
                        sb.append("                ").append(subCodec).append(".decodeSlice(buf, es, ee, elem, depth + 2, maxDepth, maxElems);\n");
                        sb.append("                list.add(elem);\n");
                    }
                    case TYPE_STRING -> emitInlineStringRead(sb, "list.add", "ap", "arrEnd", "                ");
                    case TYPE_BYTES -> {
                        sb.append("                long bpk = PrefixVarint.readLengthSlicePacked(buf, ap, arrEnd, 0);\n");
                        sb.append("                int bs = (int) (bpk >>> 32);\n");
                        sb.append("                int be = (int) bpk;\n");
                        sb.append("                ap = be;\n");
                        sb.append("                list.add(bs == be ? new byte[0] : java.util.Arrays.copyOfRange(buf, bs, be));\n");
                    }
                    case TYPE_BOOL -> {
                        sb.append("                int bb = buf[ap++] & 0xFF;\n");
                        sb.append("                if (bb > 1) {\n");
                        sb.append("                    com.omnibuf.wire.OmniBufDecoder.throwInvalidBool(bb);\n");
                        sb.append("                }\n");
                        sb.append("                list.add(bb == 1);\n");
                    }
                    case TYPE_FLOAT -> {
                        sb.append("                list.add(Float.intBitsToFloat(PrefixVarint.getIntLE(buf, ap)));\n");
                        sb.append("                ap += 4;\n");
                    }
                    case TYPE_DOUBLE -> {
                        sb.append("                list.add(Double.longBitsToDouble(PrefixVarint.getLongLE(buf, ap)));\n");
                        sb.append("                ap += 8;\n");
                    }
                    default -> throw new IllegalStateException("Unsupported repeated decode type: " + f.proto().getType());
                }
                sb.append("            }\n");
            }
            sb.append("            if (ap != arrEnd) {\n");
            sb.append("                com.omnibuf.wire.OmniBufDecoder.throwTrailingContainerBytes();\n");
            sb.append("            }\n");
            sb.append("        }\n");
            return;
        }

        if (f.proto().getType() == Type.TYPE_BOOL) {
            if (fnum < 8) {
                sb.append("        if (pos < lim && (buf[pos] | 2) == ").append(tag1BLiteral(fnum, WireType.WIRE_BOOL_TRUE)).append(") {\n");
                if (f.hasExplicitScalarPresence()) {
                    sb.append("            target.has_").append(f.camelName()).append(" = true;\n");
                }
                sb.append("            target.").append(f.camelName()).append(" = (buf[pos++] & 2) != 0;\n");
                sb.append("        }\n");
            } else {
                sb.append("        if (lim - pos >= 2 && (PrefixVarint.getShortLE(buf, pos) | 4) == ").append(tag2BLiteral(fnum, WireType.WIRE_BOOL_TRUE)).append(") {\n");
                if (f.hasExplicitScalarPresence()) {
                    sb.append("            target.has_").append(f.camelName()).append(" = true;\n");
                }
                sb.append("            target.").append(f.camelName()).append(" = (PrefixVarint.getShortLE(buf, pos) & 4) != 0;\n");
                sb.append("            pos += 2;\n");
                sb.append("        }\n");
            }
            return;
        }

        int wt = wireTypeInt(f.proto().getType());
        Type ft = f.proto().getType();
        int fixedBytes = switch (ft) {
            case TYPE_FIXED32, TYPE_SFIXED32, TYPE_FLOAT -> 4;
            case TYPE_FIXED64, TYPE_SFIXED64, TYPE_DOUBLE -> 8;
            default -> 0;
        };
        if (fixedBytes > 0) {
            int tagLen = fnum < 8 ? 1 : 2;
            if (fnum < 8) {
                sb.append("        if (lim - pos >= ").append(tagLen + fixedBytes)
                    .append(" && buf[pos] == ").append(tag1BLiteral(fnum, wt)).append(") {\n");
            } else {
                sb.append("        if (lim - pos >= ").append(tagLen + fixedBytes)
                    .append(" && PrefixVarint.getShortLE(buf, pos) == ").append(tag2BLiteral(fnum, wt)).append(") {\n");
            }
            if (f.hasExplicitScalarPresence()) {
                sb.append("            target.has_").append(f.camelName()).append(" = true;\n");
            }
            String fieldAssign = "target." + f.camelName() + " = ";
            switch (ft) {
                case TYPE_FIXED32, TYPE_SFIXED32 ->
                    sb.append("            ").append(fieldAssign).append("PrefixVarint.getIntLE(buf, pos + ").append(tagLen).append(");\n");
                case TYPE_FLOAT ->
                    sb.append("            ").append(fieldAssign).append("Float.intBitsToFloat(PrefixVarint.getIntLE(buf, pos + ").append(tagLen).append("));\n");
                case TYPE_FIXED64, TYPE_SFIXED64 ->
                    sb.append("            ").append(fieldAssign).append("PrefixVarint.getLongLE(buf, pos + ").append(tagLen).append(");\n");
                case TYPE_DOUBLE ->
                    sb.append("            ").append(fieldAssign).append("Double.longBitsToDouble(PrefixVarint.getLongLE(buf, pos + ").append(tagLen).append("));\n");
                default -> throw new IllegalStateException("Unexpected fixed type: " + ft);
            }
            sb.append("            pos += ").append(tagLen + fixedBytes).append(";\n");
            sb.append("        }\n");
            return;
        }
        if (fnum < 8) {
            sb.append("        if (pos < lim && buf[pos] == ").append(tag1BLiteral(fnum, wt)).append(") {\n");
            sb.append("            pos++;\n");
        } else {
            sb.append("        if (lim - pos >= 2 && PrefixVarint.getShortLE(buf, pos) == ").append(tag2BLiteral(fnum, wt)).append(") {\n");
            sb.append("            pos += 2;\n");
        }

        if (f.proto().getType() == Type.TYPE_MESSAGE) {
            String subModel = f.subMessage().qualifiedModel();
            String subCodec = f.subMessage().qualifiedCodec();
            emitInlineLengthSlice(sb, "subStart", "subEnd", "pos", "lim", 0, "            ");
            sb.append("            ").append(subModel).append(" sub = target.").append(f.camelName()).append(";\n");
            sb.append("            if (sub == null) {\n");
            sb.append("                sub = new ").append(subModel).append("();\n");
            sb.append("                target.").append(f.camelName()).append(" = sub;\n");
            sb.append("            }\n");
            sb.append("            ").append(subCodec).append(".decodeSlice(buf, subStart, subEnd, sub, depth + 1, maxDepth, maxElems);\n");
            sb.append("        }\n");
            return;
        }

        if (f.hasExplicitScalarPresence()) {
            sb.append("            target.has_").append(f.camelName()).append(" = true;\n");
        }
        String fieldAssign = "target." + f.camelName() + " = ";
        switch (f.proto().getType()) {
            case TYPE_UINT32 -> {
                emitInlineVarintRead(sb, "v", "pos", "lim", "            ");
                sb.append("            ").append(fieldAssign).append("(int) v;\n");
            }
            case TYPE_UINT64 -> {
                emitInlineVarintRead(sb, "v", "pos", "lim", "            ");
                sb.append("            ").append(fieldAssign).append("v;\n");
            }
            case TYPE_INT32, TYPE_SINT32, TYPE_ENUM -> {
                emitInlineVarintRead(sb, "v", "pos", "lim", "            ");
                sb.append("            ").append(fieldAssign).append("PrefixVarint.decodeZigZag32(v);\n");
            }
            case TYPE_INT64, TYPE_SINT64 -> {
                emitInlineVarintRead(sb, "v", "pos", "lim", "            ");
                sb.append("            ").append(fieldAssign).append("PrefixVarint.decodeZigZag64(v);\n");
            }
            case TYPE_FIXED32, TYPE_SFIXED32 -> {
                sb.append("            if (lim - pos < 4) {\n");
                sb.append("                com.omnibuf.wire.OmniBufDecoder.throwTruncatedFixed32();\n");
                sb.append("            }\n");
                sb.append("            ").append(fieldAssign).append("PrefixVarint.getIntLE(buf, pos);\n");
                sb.append("            pos += 4;\n");
            }
            case TYPE_FLOAT -> {
                sb.append("            if (lim - pos < 4) {\n");
                sb.append("                com.omnibuf.wire.OmniBufDecoder.throwTruncatedFixed32();\n");
                sb.append("            }\n");
                sb.append("            ").append(fieldAssign).append("Float.intBitsToFloat(PrefixVarint.getIntLE(buf, pos));\n");
                sb.append("            pos += 4;\n");
            }
            case TYPE_FIXED64, TYPE_SFIXED64 -> {
                sb.append("            if (lim - pos < 8) {\n");
                sb.append("                com.omnibuf.wire.OmniBufDecoder.throwTruncatedFixed64();\n");
                sb.append("            }\n");
                sb.append("            ").append(fieldAssign).append("PrefixVarint.getLongLE(buf, pos);\n");
                sb.append("            pos += 8;\n");
            }
            case TYPE_DOUBLE -> {
                sb.append("            if (lim - pos < 8) {\n");
                sb.append("                com.omnibuf.wire.OmniBufDecoder.throwTruncatedFixed64();\n");
                sb.append("            }\n");
                sb.append("            ").append(fieldAssign).append("Double.longBitsToDouble(PrefixVarint.getLongLE(buf, pos));\n");
                sb.append("            pos += 8;\n");
            }
            case TYPE_STRING -> emitInlineStringRead(sb, fieldAssign, "pos", "lim", "            ");
            case TYPE_BYTES -> {
                sb.append("            long bpk = PrefixVarint.readLengthSlicePacked(buf, pos, lim, 0);\n");
                sb.append("            int bs = (int) (bpk >>> 32);\n");
                sb.append("            int be = (int) bpk;\n");
                sb.append("            pos = be;\n");
                sb.append("            ").append(fieldAssign).append("bs == be ? new byte[0] : java.util.Arrays.copyOfRange(buf, bs, be);\n");
            }
            default -> throw new IllegalStateException("Unsupported singular decode type: " + f.proto().getType());
        }
        sb.append("        }\n");
    }

    private static String bulkRepeatedSliceMethod(Type t) {
        return switch (t) {
            case TYPE_UINT32 -> "readUint32SliceInto";
            case TYPE_INT32, TYPE_SINT32, TYPE_ENUM -> "readSint32SliceInto";
            case TYPE_FIXED32, TYPE_SFIXED32 -> "readFixed32SliceInto";
            case TYPE_UINT64 -> "readUint64SliceInto";
            case TYPE_INT64, TYPE_SINT64 -> "readSint64SliceInto";
            case TYPE_FIXED64, TYPE_SFIXED64 -> "readFixed64SliceInto";
            default -> null;
        };
    }

    private static void emitFieldDecodeCase(StringBuilder sb, ResolvedField f) {
        int fnum = f.number();
        sb.append("                case ").append(fnum).append(" -> {\n");

        if (f.isMap()) {
            String kBox = boxedType(f.mapKeyProto(), null);
            String vBox = boxedType(f.mapValProto(), f.subMessage());
            String keyWT = wireTypeConst(f.mapKeyProto().getType());
            String valWT = wireTypeConst(f.mapValProto().getType());
            sb.append("                    if (wt != WireType.WIRE_MAP) {\n");
            sb.append("                        pos = com.omnibuf.wire.OmniBufDecoder.skipOrValidateUnknownSlice(buf, pos, lim, fnum, wt, depth, maxDepth, maxElems);\n");
            sb.append("                    } else {\n");
            sb.append("                        com.omnibuf.wire.OmniBufDecoder dec = com.omnibuf.wire.OmniBufDecoder.forSubSlice(buf, pos, lim, maxDepth, maxElems);\n");
            sb.append("                        long mapState = dec.beginMap(")
                .append(keyWT).append(", ").append(valWT).append(", depth);\n");
            sb.append("                        if (mapState >= 0L) {\n");
            sb.append("                            int count = (int) mapState;\n");
            sb.append("                            int mapLimit = (int) (mapState >>> 32);\n");
            sb.append("                            var map = target.ensure").append(f.pascalName()).append("(count);\n");
            sb.append("                            for (int i = 0; i < count; i++) {\n");
            sb.append("                                ").append(kBox).append(" k = ")
                .append(arrayElementReadExpr(f.mapKeyProto().getType())).append(";\n");
            if (f.mapValProto().getType() == Type.TYPE_MESSAGE) {
                String subModel = f.subMessage().qualifiedModel();
                String subCodec = f.subMessage().qualifiedCodec();
                sb.append("                                int elemLimit = dec.readLengthPrefixedLimit(0);\n");
                sb.append("                                ").append(subModel).append(" v = new ").append(subModel).append("();\n");
                sb.append("                                ").append(subCodec).append(".decodeFrom(dec, v, depth + 2);\n");
                sb.append("                                dec.endContainer(elemLimit);\n");
            } else {
                sb.append("                                ").append(vBox).append(" v = ")
                    .append(arrayElementReadExpr(f.mapValProto().getType())).append(";\n");
            }
            sb.append("                                if (!map.containsKey(k) && map.size() >= maxElems) {\n");
            sb.append("                                    throw new com.omnibuf.OmniBufException(\"omnibuf: accumulated WireMap count exceeds maxCollectionElements\");\n");
            sb.append("                                }\n");
            sb.append("                                map.put(k, v);\n");
            sb.append("                            }\n");
            sb.append("                            dec.endContainer(mapLimit);\n");
            sb.append("                        }\n");
            sb.append("                        pos = dec.position();\n");
            sb.append("                    }\n");
            sb.append("                }\n");
            return;
        }

        if (f.isRepeated()) {
            String elemWireConst = wireTypeConst(f.proto().getType());
            sb.append("                    if (wt != WireType.WIRE_ARRAY) {\n");
            sb.append("                        pos = com.omnibuf.wire.OmniBufDecoder.skipOrValidateUnknownSlice(buf, pos, lim, fnum, wt, depth, maxDepth, maxElems);\n");
            sb.append("                    } else {\n");
            emitInlineLengthSlice(sb, "arrStart", "arrEnd", "pos", "lim", 2, "                        ");
            sb.append("                        long arrHdr = com.omnibuf.wire.OmniBufDecoder.readArrayHeaderPacked(buf, arrStart, arrEnd, ")
                .append(elemWireConst).append(", depth, maxDepth, maxElems);\n");
            sb.append("                        int ap = (int) (arrHdr >>> 32);\n");
            sb.append("                        int count = (int) arrHdr;\n");
            sb.append("                        var list = target.ensure").append(f.pascalName()).append("(count);\n");
            sb.append("                        if ((long) list.size() + count > maxElems) {\n");
            sb.append("                            com.omnibuf.wire.OmniBufDecoder.throwMaxCollectionElements();\n");
            sb.append("                        }\n");
            String bulkSlice = bulkRepeatedSliceMethod(f.proto().getType());
            if (bulkSlice != null) {
                sb.append("                        ap = com.omnibuf.wire.OmniBufDecoder.").append(bulkSlice).append("(buf, ap, arrEnd, list, count);\n");
            } else {
                sb.append("                        for (int i = 0; i < count; i++) {\n");
                switch (f.proto().getType()) {
                    case TYPE_MESSAGE -> {
                        String subModel = f.subMessage().qualifiedModel();
                        String subCodec = f.subMessage().qualifiedCodec();
                        emitInlineLengthSlice(sb, "es", "ee", "ap", "arrEnd", 0, "                            ");
                        sb.append("                            ").append(subModel).append(" elem = new ").append(subModel).append("();\n");
                        sb.append("                            ").append(subCodec).append(".decodeSlice(buf, es, ee, elem, depth + 2, maxDepth, maxElems);\n");
                        sb.append("                            list.add(elem);\n");
                    }
                    case TYPE_STRING -> emitInlineStringRead(sb, "list.add", "ap", "arrEnd", "                            ");
                    case TYPE_BYTES -> {
                        sb.append("                            long bpk = PrefixVarint.readLengthSlicePacked(buf, ap, arrEnd, 0);\n");
                        sb.append("                            int bs = (int) (bpk >>> 32);\n");
                        sb.append("                            int be = (int) bpk;\n");
                        sb.append("                            ap = be;\n");
                        sb.append("                            list.add(bs == be ? new byte[0] : java.util.Arrays.copyOfRange(buf, bs, be));\n");
                    }
                    case TYPE_BOOL -> {
                        sb.append("                            int bb = buf[ap++] & 0xFF;\n");
                        sb.append("                            if (bb > 1) {\n");
                        sb.append("                                com.omnibuf.wire.OmniBufDecoder.throwInvalidBool(bb);\n");
                        sb.append("                            }\n");
                        sb.append("                            list.add(bb == 1);\n");
                    }
                    case TYPE_FLOAT -> {
                        sb.append("                            list.add(Float.intBitsToFloat(PrefixVarint.getIntLE(buf, ap)));\n");
                        sb.append("                            ap += 4;\n");
                    }
                    case TYPE_DOUBLE -> {
                        sb.append("                            list.add(Double.longBitsToDouble(PrefixVarint.getLongLE(buf, ap)));\n");
                        sb.append("                            ap += 8;\n");
                    }
                    default -> throw new IllegalStateException("Unsupported repeated decode type: " + f.proto().getType());
                }
                sb.append("                        }\n");
            }
            sb.append("                        if (ap != arrEnd) {\n");
            sb.append("                            com.omnibuf.wire.OmniBufDecoder.throwTrailingContainerBytes();\n");
            sb.append("                        }\n");
            sb.append("                    }\n");
            sb.append("                }\n");
            return;
        }

        if (f.proto().getType() == Type.TYPE_BOOL) {
            sb.append("                    if (wt == WireType.WIRE_BOOL_FALSE || wt == WireType.WIRE_BOOL_TRUE) {\n");
            if (f.oneof() != null) {
                sb.append("                        target.prepare").append(f.oneof().pascalName())
                    .append('(').append(fnum).append(");\n");
            } else if (f.hasExplicitScalarPresence()) {
                sb.append("                        target.has_").append(f.camelName()).append(" = true;\n");
            }
            sb.append("                        target.").append(f.camelName()).append(" = (wt == WireType.WIRE_BOOL_TRUE);\n");
            sb.append("                    } else {\n");
            sb.append("                        pos = com.omnibuf.wire.OmniBufDecoder.skipOrValidateUnknownSlice(buf, pos, lim, fnum, wt, depth, maxDepth, maxElems);\n");
            sb.append("                    }\n");
            sb.append("                }\n");
            return;
        }

        if (f.proto().getType() == Type.TYPE_MESSAGE) {
            String subModel = f.subMessage().qualifiedModel();
            String subCodec = f.subMessage().qualifiedCodec();
            sb.append("                    if (wt == WireType.WIRE_SUB_MSG) {\n");
            emitInlineLengthSlice(sb, "subStart", "subEnd", "pos", "lim", 0, "                        ");
            if (f.oneof() != null) {
                sb.append("                        target.prepare").append(f.oneof().pascalName())
                    .append('(').append(fnum).append(");\n");
            }
            sb.append("                        ").append(subModel).append(" sub = target.").append(f.camelName()).append(";\n");
            sb.append("                        if (sub == null) {\n");
            sb.append("                            sub = new ").append(subModel).append("();\n");
            sb.append("                            target.").append(f.camelName()).append(" = sub;\n");
            sb.append("                        }\n");
            sb.append("                        ").append(subCodec).append(".decodeSlice(buf, subStart, subEnd, sub, depth + 1, maxDepth, maxElems);\n");
            sb.append("                    } else {\n");
            sb.append("                        pos = com.omnibuf.wire.OmniBufDecoder.skipOrValidateUnknownSlice(buf, pos, lim, fnum, wt, depth, maxDepth, maxElems);\n");
            sb.append("                    }\n");
            sb.append("                }\n");
            return;
        }

        String wtConst = wireTypeConst(f.proto().getType());
        sb.append("                    if (wt == ").append(wtConst).append(") {\n");
        if (f.oneof() != null) {
            sb.append("                        target.prepare").append(f.oneof().pascalName())
                .append('(').append(fnum).append(");\n");
        } else if (f.hasExplicitScalarPresence()) {
            sb.append("                        target.has_").append(f.camelName()).append(" = true;\n");
        }
        String fieldAssign = "target." + f.camelName() + " = ";
        switch (f.proto().getType()) {
            case TYPE_UINT32 -> {
                emitInlineVarintRead(sb, "v", "pos", "lim", "                        ");
                sb.append("                        ").append(fieldAssign).append("(int) v;\n");
            }
            case TYPE_UINT64 -> {
                emitInlineVarintRead(sb, "v", "pos", "lim", "                        ");
                sb.append("                        ").append(fieldAssign).append("v;\n");
            }
            case TYPE_INT32, TYPE_SINT32, TYPE_ENUM -> {
                emitInlineVarintRead(sb, "v", "pos", "lim", "                        ");
                sb.append("                        ").append(fieldAssign).append("PrefixVarint.decodeZigZag32(v);\n");
            }
            case TYPE_INT64, TYPE_SINT64 -> {
                emitInlineVarintRead(sb, "v", "pos", "lim", "                        ");
                sb.append("                        ").append(fieldAssign).append("PrefixVarint.decodeZigZag64(v);\n");
            }
            case TYPE_FIXED32, TYPE_SFIXED32 -> {
                sb.append("                        if (lim - pos < 4) {\n");
                sb.append("                            com.omnibuf.wire.OmniBufDecoder.throwTruncatedFixed32();\n");
                sb.append("                        }\n");
                sb.append("                        ").append(fieldAssign).append("PrefixVarint.getIntLE(buf, pos);\n");
                sb.append("                        pos += 4;\n");
            }
            case TYPE_FLOAT -> {
                sb.append("                        if (lim - pos < 4) {\n");
                sb.append("                            com.omnibuf.wire.OmniBufDecoder.throwTruncatedFixed32();\n");
                sb.append("                        }\n");
                sb.append("                        ").append(fieldAssign).append("Float.intBitsToFloat(PrefixVarint.getIntLE(buf, pos));\n");
                sb.append("                        pos += 4;\n");
            }
            case TYPE_FIXED64, TYPE_SFIXED64 -> {
                sb.append("                        if (lim - pos < 8) {\n");
                sb.append("                            com.omnibuf.wire.OmniBufDecoder.throwTruncatedFixed64();\n");
                sb.append("                        }\n");
                sb.append("                        ").append(fieldAssign).append("PrefixVarint.getLongLE(buf, pos);\n");
                sb.append("                        pos += 8;\n");
            }
            case TYPE_DOUBLE -> {
                sb.append("                        if (lim - pos < 8) {\n");
                sb.append("                            com.omnibuf.wire.OmniBufDecoder.throwTruncatedFixed64();\n");
                sb.append("                        }\n");
                sb.append("                        ").append(fieldAssign).append("Double.longBitsToDouble(PrefixVarint.getLongLE(buf, pos));\n");
                sb.append("                        pos += 8;\n");
            }
            case TYPE_STRING -> emitInlineStringRead(sb, fieldAssign, "pos", "lim", "                        ");
            case TYPE_BYTES -> {
                sb.append("                        long bpk = PrefixVarint.readLengthSlicePacked(buf, pos, lim, 0);\n");
                sb.append("                        int bs = (int) (bpk >>> 32);\n");
                sb.append("                        int be = (int) bpk;\n");
                sb.append("                        pos = be;\n");
                sb.append("                        ").append(fieldAssign).append("bs == be ? new byte[0] : java.util.Arrays.copyOfRange(buf, bs, be);\n");
            }
            default -> throw new IllegalStateException("Unsupported singular decode type: " + f.proto().getType());
        }
        sb.append("                    } else {\n");
        sb.append("                        pos = com.omnibuf.wire.OmniBufDecoder.skipOrValidateUnknownSlice(buf, pos, lim, fnum, wt, depth, maxDepth, maxElems);\n");
        sb.append("                    }\n");
        sb.append("                }\n");
    }

    // ========================================================================
    // 3. Opaque Lazy View & Splicer (Opaque<Message>.java)
    // ========================================================================

    private static String emitOpaqueFile(String sourceProto, ResolvedMessage resolved) {
        MessageInfo info = resolved.info();
        List<ResolvedField> fields = resolved.fields();
        List<OneofInfo> oneofs = resolved.oneofs();

        int maxField = 0;
        boolean needsDeterministicFlag = false;
        for (ResolvedField f : fields) {
            if (f.number() > maxField) {
                maxField = f.number();
            }
            if (f.isMap() || f.proto().getType() == Type.TYPE_MESSAGE) {
                needsDeterministicFlag = true;
            }
        }
        boolean useBitSet = maxField >= 64;

        StringBuilder sb = new StringBuilder(4096);
        sb.append("// Generated by protoc-gen-omnibuf-java. DO NOT EDIT.\n");
        sb.append("// source: ").append(sourceProto).append("\n\n");
        if (!info.javaPackage().isEmpty()) {
            sb.append("package ").append(info.javaPackage()).append(";\n\n");
        }
        if (needsPrefixVarintImport(fields)) {
            sb.append("import com.omnibuf.wire.PrefixVarint;\n");
        }
        sb.append("import com.omnibuf.wire.WireType;\n");
        sb.append("import java.nio.ByteBuffer;\n");
        sb.append("import java.util.ArrayList;\n");
        if (useBitSet) {
            sb.append("import java.util.BitSet;\n");
        }
        sb.append("import java.util.Collections;\n");
        sb.append("import java.util.LinkedHashMap;\n");
        sb.append("import java.util.List;\n");
        sb.append("import java.util.Map;\n\n");

        sb.append("public final class ").append(info.opaqueClass()).append(" {\n");
        sb.append("    private static final byte[] EMPTY_BYTES = new byte[0];\n");
        if (useBitSet) {
            sb.append("    private static final BitSet EMPTY_BIT_SET = new BitSet(0);\n");
        }
        sb.append("    private static final int MAX_FIELD_NUMBER = ").append(maxField).append(";\n");
        sb.append("    private static final int MAX_INDEXED_FIELD = ").append(Math.min(maxField, 64)).append(";\n");
        for (OneofInfo oi : oneofs) {
            sb.append("    private static final int[] ONEOF_").append(oi.upperConstName()).append("_FIELDS = new int[] {");
            for (int i = 0; i < oi.memberNumbers().size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(oi.memberNumbers().get(i));
            }
            sb.append("};\n");
            sb.append("    private static final int[] ONEOF_").append(oi.upperConstName()).append("_MASKS = new int[] {");
            for (int i = 0; i < oi.memberWireMasks().size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(oi.memberWireMasks().get(i));
            }
            sb.append("};\n");
        }
        sb.append('\n');

        sb.append("    private static final class DirtyState {\n");
        if (useBitSet) {
            sb.append("        final BitSet dirtyBits = new BitSet();\n");
        } else {
            sb.append("        long dirtyMask;\n");
        }
        for (ResolvedField f : fields) {
            sb.append("        ").append(javaFieldType(f)).append(" dirty_").append(f.camelName()).append(";\n");
            if (f.hasExplicitScalarPresence()) {
                sb.append("        boolean dirtyPresent_").append(f.camelName()).append(";\n");
            }
        }
        for (OneofInfo oi : oneofs) {
            sb.append("        int dirtyOneof_").append(oi.camelName()).append(" = -1;\n");
        }
        sb.append("    }\n\n");

        sb.append("    private final byte[] buffer;\n");
        sb.append("    private final int offset;\n");
        sb.append("    private final int length;\n");
        sb.append("    private DirtyState dirty;\n");
        sb.append("    private volatile long[] fieldIndex;\n");
        sb.append("    private volatile byte readCount;\n\n");

        sb.append("    public ").append(info.opaqueClass()).append("(byte[] packet) {\n");
        sb.append("        this.buffer = packet == null ? EMPTY_BYTES : packet;\n");
        sb.append("        this.offset = 0;\n");
        sb.append("        this.length = this.buffer.length;\n");
        sb.append("    }\n\n");

        sb.append("    public ").append(info.opaqueClass()).append("(byte[] buffer, int offset, int length) {\n");
        sb.append("        byte[] b = buffer == null ? EMPTY_BYTES : buffer;\n");
        sb.append("        com.omnibuf.wire.OmniBufOpaque.checkSliceBounds(b, offset, length);\n");
        sb.append("        this.buffer = b;\n");
        sb.append("        this.offset = offset;\n");
        sb.append("        this.length = length;\n");
        sb.append("    }\n\n");

        sb.append("    public ").append(info.opaqueClass()).append("(com.omnibuf.wire.OmniBufOpaque.PacketView view) {\n");
        sb.append("        this(view.buffer(), view.offset(), view.length());\n");
        sb.append("    }\n\n");

        sb.append("    public com.omnibuf.wire.OmniBufOpaque.PacketView packetView() {\n");
        sb.append("        return new com.omnibuf.wire.OmniBufOpaque.PacketView(buffer, offset, length);\n");
        sb.append("    }\n\n");

        sb.append("    public ByteBuffer asReadOnlyByteBuffer() {\n");
        sb.append("        return ByteBuffer.wrap(buffer, offset, length).asReadOnlyBuffer();\n");
        sb.append("    }\n\n");

        sb.append("    private DirtyState ensureDirty() {\n");
        sb.append("        DirtyState d = this.dirty;\n");
        sb.append("        if (d == null) {\n");
        sb.append("            d = new DirtyState();\n");
        sb.append("            this.dirty = d;\n");
        sb.append("        }\n");
        sb.append("        return d;\n");
        sb.append("    }\n\n");

        sb.append("    private long[] getOrBuildIndex() {\n");
        sb.append("        long[] idx = this.fieldIndex;\n");
        sb.append("        if (idx != null) {\n");
        sb.append("            return idx;\n");
        sb.append("        }\n");
        sb.append("        if (this.readCount == 0) {\n");
        sb.append("            this.readCount = 1;\n");
        sb.append("            return null;\n");
        sb.append("        }\n");
        sb.append("        idx = com.omnibuf.wire.OmniBufOpaque.buildFieldIndex(buffer, offset, length, MAX_INDEXED_FIELD);\n");
        sb.append("        this.fieldIndex = idx;\n");
        sb.append("        return idx;\n");
        sb.append("    }\n\n");

        sb.append("    public boolean hasDirtyFields() {\n");
        sb.append("        DirtyState d = this.dirty;\n");
        if (useBitSet) {
            sb.append("        return d != null && !d.dirtyBits.isEmpty();\n");
        } else {
            sb.append("        return d != null && d.dirtyMask != 0L;\n");
        }
        sb.append("    }\n\n");

        for (OneofInfo oi : oneofs) {
            sb.append("    public int get").append(oi.pascalName()).append("Case() {\n");
            sb.append("        DirtyState d = this.dirty;\n");
            sb.append("        if (d != null && d.dirtyOneof_").append(oi.camelName()).append(" >= 0) {\n");
            sb.append("            return d.dirtyOneof_").append(oi.camelName()).append(";\n");
            sb.append("        }\n");
            sb.append("        return com.omnibuf.wire.OmniBufOpaque.locateActiveOneof(\n");
            sb.append("            buffer, offset, length, ONEOF_")
                .append(oi.upperConstName()).append("_FIELDS, ONEOF_")
                .append(oi.upperConstName()).append("_MASKS\n");
            sb.append("        );\n");
            sb.append("    }\n\n");

            sb.append("    public ").append(info.opaqueClass()).append(" clear").append(oi.pascalName()).append("() {\n");
            sb.append("        DirtyState d = ensureDirty();\n");
            sb.append("        d.dirtyOneof_").append(oi.camelName()).append(" = 0;\n");
            for (int memberNum : oi.memberNumbers()) {
                sb.append("        ").append(markDirtyStmt(useBitSet, memberNum)).append('\n');
            }
            for (ResolvedField mf : fields) {
                if (mf.oneof() != null && mf.oneof().protoIndex() == oi.protoIndex()) {
                    sb.append("        d.dirty_").append(mf.camelName()).append(" = ")
                        .append(opaqueDirtyResetExpr(mf)).append(";\n");
                }
            }
            sb.append("        return this;\n");
            sb.append("    }\n\n");
        }

        for (ResolvedField f : fields) {
            emitOpaqueGetterAndSetter(sb, info, f, useBitSet);
        }

        sb.append("    public byte[] marshal() {\n");
        sb.append("        return marshalInto(null);\n");
        sb.append("    }\n\n");

        sb.append("    public byte[] marshalInto(com.omnibuf.wire.OmniBufEncoder target) {\n");
        sb.append("        DirtyState d = this.dirty;\n");
        sb.append("        return com.omnibuf.wire.OmniBufOpaque.spliceFields(\n");
        sb.append("            buffer, offset, length, ")
            .append(useBitSet ? "(d == null ? EMPTY_BIT_SET : d.dirtyBits)" : "(d == null ? 0L : d.dirtyMask)")
            .append(", MAX_FIELD_NUMBER, this::writeDirtyField, target\n");
        sb.append("        );\n");
        sb.append("    }\n\n");

        sb.append("    public byte[] marshalValidated() {\n");
        sb.append("        return marshalValidatedInto(null);\n");
        sb.append("    }\n\n");

        sb.append("    public byte[] tryMarshal() {\n");
        sb.append("        return marshalValidatedInto(null);\n");
        sb.append("    }\n\n");

        sb.append("    public byte[] marshalValidatedInto(com.omnibuf.wire.OmniBufEncoder target) {\n");
        sb.append("        DirtyState d = this.dirty;\n");
        sb.append("        return com.omnibuf.wire.OmniBufOpaque.spliceFieldsValidated(\n");
        sb.append("            buffer, offset, length, ")
            .append(useBitSet ? "(d == null ? EMPTY_BIT_SET : d.dirtyBits)" : "(d == null ? 0L : d.dirtyMask)")
            .append(", MAX_FIELD_NUMBER, this::writeDirtyField, target\n");
        sb.append("        );\n");
        sb.append("    }\n\n");

        sb.append("    private void writeDirtyField(com.omnibuf.wire.OmniBufEncoder enc, int fieldNumber) {\n");
        sb.append("        DirtyState d = this.dirty;\n");
        sb.append("        if (d == null) {\n");
        sb.append("            return;\n");
        sb.append("        }\n");
        if (needsDeterministicFlag) {
            sb.append("        boolean deterministic = true;\n");
        }
        sb.append("        switch (fieldNumber) {\n");
        for (ResolvedField f : fields) {
            sb.append("            case ").append(f.number()).append(" -> {\n");
            if (f.oneof() != null) {
                sb.append("                if (d.dirtyOneof_").append(f.oneof().camelName())
                    .append(" == ").append(f.number()).append(") {\n");
                emitFieldEncode(sb, info, f, "d.dirty_" + f.camelName(), "                    ");
                sb.append("                }\n");
            } else if (f.hasExplicitScalarPresence()) {
                sb.append("                if (d.dirtyPresent_").append(f.camelName()).append(") {\n");
                emitFieldEncode(sb, info, f, "d.dirty_" + f.camelName(), "                    ");
                sb.append("                }\n");
            } else {
                emitFieldEncode(sb, info, f, "d.dirty_" + f.camelName(), "                ");
            }
            sb.append("            }\n");
        }
        sb.append("            default -> {}\n");
        sb.append("        }\n");
        sb.append("    }\n");

        sb.append("}\n");
        return sb.toString();
    }

    private static String isDirtyExpr(boolean useBitSet, int fnum) {
        if (useBitSet) {
            return "d != null && d.dirtyBits.get(" + fnum + ")";
        }
        long bit = 1L << fnum;
        return "d != null && (d.dirtyMask & 0x" + Long.toHexString(bit) + "L) != 0L";
    }

    private static String markDirtyStmt(boolean useBitSet, int fnum) {
        if (useBitSet) {
            return "d.dirtyBits.set(" + fnum + ");";
        }
        long bit = 1L << fnum;
        return "d.dirtyMask |= 0x" + Long.toHexString(bit) + "L;";
    }

    private static void emitOpaqueOneofMarkDirty(StringBuilder sb, OneofInfo oi, int activeField, boolean useBitSet) {
        sb.append("        d.dirtyOneof_").append(oi.camelName()).append(" = ").append(activeField).append(";\n");
        for (int memberNum : oi.memberNumbers()) {
            sb.append("        ").append(markDirtyStmt(useBitSet, memberNum)).append('\n');
        }
    }

    private static void emitOpaqueGetterAndSetter(
            StringBuilder sb, MessageInfo info, ResolvedField f, boolean useBitSet) {
        int fnum = f.number();
        String dirtyCheck = isDirtyExpr(useBitSet, fnum);
        String markDirty = markDirtyStmt(useBitSet, fnum);

        if (f.isMap()) {
            String kBox = boxedType(f.mapKeyProto(), null);
            String vModelBox = boxedType(f.mapValProto(), f.subMessage());
            String modelMapType = "Map<" + kBox + ", " + vModelBox + ">";

            if (f.mapKeyProto().getType() == Type.TYPE_STRING
                    && f.mapValProto().getType() == Type.TYPE_UINT64) {
                sb.append("    public Map<String, Long> get").append(f.pascalName()).append("() {\n");
                sb.append("        DirtyState d = this.dirty;\n");
                sb.append("        if (").append(dirtyCheck).append(") {\n");
                sb.append("            return d.dirty_").append(f.camelName()).append(";\n");
                sb.append("        }\n");
                sb.append("        return com.omnibuf.wire.OmniBufOpaque.readMapStringUint64(buffer, offset, length, ")
                    .append(fnum).append(");\n");
                sb.append("    }\n\n");
            } else if (f.mapValProto().getType() == Type.TYPE_MESSAGE) {
                String subOpaque = f.subMessage().qualifiedOpaque();
                String subCodec = f.subMessage().qualifiedCodec();
                String opaqueMapType = "Map<" + kBox + ", " + subOpaque + ">";
                String keyWT = wireTypeConst(f.mapKeyProto().getType());
                sb.append("    public ").append(opaqueMapType).append(" get").append(f.pascalName()).append("() {\n");
                sb.append("        DirtyState d = this.dirty;\n");
                sb.append("        if (").append(dirtyCheck).append(") {\n");
                sb.append("            if (d.dirty_").append(f.camelName()).append(" == null || d.dirty_")
                    .append(f.camelName()).append(".isEmpty()) {\n");
                sb.append("                return Collections.emptyMap();\n");
                sb.append("            }\n");
                sb.append("            ").append(opaqueMapType).append(" out = new LinkedHashMap<>();\n");
                sb.append("            for (Map.Entry<").append(kBox).append(", ").append(vModelBox)
                    .append("> entry : d.dirty_").append(f.camelName()).append(".entrySet()) {\n");
                sb.append("                ").append(vModelBox).append(" mv = entry.getValue();\n");
                sb.append("                out.put(entry.getKey(), mv == null ? null : new ")
                    .append(subOpaque).append('(').append(subCodec).append(".marshal(mv)));\n");
                sb.append("            }\n");
                sb.append("            return out;\n");
                sb.append("        }\n");
                sb.append("        return com.omnibuf.wire.OmniBufOpaque.readMap(\n");
                sb.append("            buffer, offset, length, ").append(fnum).append(", ")
                    .append(keyWT).append(", WireType.WIRE_SUB_MSG,\n");
                sb.append("            dec -> ").append(opaqueLambdaReadExpr(f.mapKeyProto().getType())).append(",\n");
                sb.append("            dec -> {\n");
                sb.append("                com.omnibuf.wire.OmniBufDecoder.Slice s = dec.readLengthPrefixedSlice(0);\n");
                sb.append("                return new ").append(subOpaque).append("(dec.buffer(), s.offset(), s.length());\n");
                sb.append("            }\n");
                sb.append("        );\n");
                sb.append("    }\n\n");
            } else {
                String keyWT = wireTypeConst(f.mapKeyProto().getType());
                String valWT = wireTypeConst(f.mapValProto().getType());
                sb.append("    public ").append(modelMapType).append(" get").append(f.pascalName()).append("() {\n");
                sb.append("        DirtyState d = this.dirty;\n");
                sb.append("        if (").append(dirtyCheck).append(") {\n");
                sb.append("            return d.dirty_").append(f.camelName()).append(";\n");
                sb.append("        }\n");
                sb.append("        return com.omnibuf.wire.OmniBufOpaque.readMap(\n");
                sb.append("            buffer, offset, length, ").append(fnum).append(", ")
                    .append(keyWT).append(", ").append(valWT).append(",\n");
                sb.append("            dec -> ").append(opaqueLambdaReadExpr(f.mapKeyProto().getType())).append(",\n");
                sb.append("            dec -> ").append(opaqueLambdaReadExpr(f.mapValProto().getType())).append("\n");
                sb.append("        );\n");
                sb.append("    }\n\n");
            }

            sb.append("    public ").append(info.opaqueClass()).append(" set").append(f.pascalName())
                .append('(').append(modelMapType).append(" value) {\n");
            sb.append("        DirtyState d = ensureDirty();\n");
            sb.append("        d.dirty_").append(f.camelName())
                .append(" = value == null ? new LinkedHashMap<>() : value;\n");
            sb.append("        ").append(markDirty).append('\n');
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
                sb.append("        DirtyState d = this.dirty;\n");
                sb.append("        if (").append(dirtyCheck).append(") {\n");
                sb.append("            if (d.dirty_").append(f.camelName()).append(" == null || d.dirty_").append(f.camelName()).append(".isEmpty()) {\n");
                sb.append("                return Collections.emptyList();\n");
                sb.append("            }\n");
                sb.append("            List<").append(subOpaque).append("> out = new ArrayList<>(d.dirty_").append(f.camelName()).append(".size());\n");
                sb.append("            for (").append(subModel).append(" elem : d.dirty_").append(f.camelName()).append(") {\n");
                sb.append("                out.add(new ").append(subOpaque).append('(').append(subCodec).append(".marshal(elem)));\n");
                sb.append("            }\n");
                sb.append("            return out;\n");
                sb.append("        }\n");
                sb.append("        List<com.omnibuf.wire.OmniBufOpaque.PacketView> views = com.omnibuf.wire.OmniBufOpaque.readSubMsgList(buffer, offset, length, ").append(fnum).append(");\n");
                sb.append("        if (views.isEmpty()) {\n");
                sb.append("            return Collections.emptyList();\n");
                sb.append("        }\n");
                sb.append("        List<").append(subOpaque).append("> out = new ArrayList<>(views.size());\n");
                sb.append("        for (com.omnibuf.wire.OmniBufOpaque.PacketView v : views) {\n");
                sb.append("            out.add(new ").append(subOpaque).append("(v));\n");
                sb.append("        }\n");
                sb.append("        return out;\n");
                sb.append("    }\n\n");

                sb.append("    public ").append(info.opaqueClass()).append(" set").append(f.pascalName())
                    .append("(List<").append(subModel).append("> value) {\n");
                sb.append("        DirtyState d = ensureDirty();\n");
                sb.append("        d.dirty_").append(f.camelName())
                    .append(" = value == null ? new ArrayList<>() : value;\n");
                sb.append("        ").append(markDirty).append('\n');
                sb.append("        return this;\n");
                sb.append("    }\n\n");
                return;
            }

            String fType = javaFieldType(f);
            String readerMethod = opaqueRepeatedReader(f.proto().getType());
            sb.append("    public ").append(fType).append(" get").append(f.pascalName()).append("() {\n");
            sb.append("        DirtyState d = this.dirty;\n");
            sb.append("        if (").append(dirtyCheck).append(") {\n");
            sb.append("            return d.dirty_").append(f.camelName()).append(";\n");
            sb.append("        }\n");
            sb.append("        return com.omnibuf.wire.OmniBufOpaque.").append(readerMethod).append("(buffer, offset, length, ").append(fnum).append(");\n");
            sb.append("    }\n\n");

            sb.append("    public ").append(info.opaqueClass()).append(" set").append(f.pascalName())
                .append('(').append(fType).append(" value) {\n");
            sb.append("        DirtyState d = ensureDirty();\n");
            sb.append("        d.dirty_").append(f.camelName())
                .append(" = value == null ? new ArrayList<>() : value;\n");
            sb.append("        ").append(markDirty).append('\n');
            sb.append("        return this;\n");
            sb.append("    }\n\n");
            return;
        }

        if (f.proto().getType() == Type.TYPE_MESSAGE) {
            String subOpaque = f.subMessage().qualifiedOpaque();
            String subModel = f.subMessage().qualifiedModel();
            String subCodec = f.subMessage().qualifiedCodec();
            sb.append("    public ").append(subOpaque).append(" get").append(f.pascalName()).append("() {\n");
            if (f.oneof() != null) {
                sb.append("        if (get").append(f.oneof().pascalName()).append("Case() != ")
                    .append(fnum).append(") {\n");
                sb.append("            return null;\n");
                sb.append("        }\n");
            }
            sb.append("        DirtyState d = this.dirty;\n");
            sb.append("        if (").append(dirtyCheck).append(") {\n");
            sb.append("            return d.dirty_").append(f.camelName()).append(" == null\n");
            sb.append("                ? null\n");
            sb.append("                : new ").append(subOpaque).append('(').append(subCodec).append(".marshal(d.dirty_").append(f.camelName()).append("));\n");
            sb.append("        }\n");
            if (f.oneof() != null) {
                sb.append("        com.omnibuf.wire.OmniBufOpaque.PacketView view = com.omnibuf.wire.OmniBufOpaque.readOneofSubMsgView(\n");
                sb.append("            buffer, offset, length, ").append(fnum)
                    .append(", ONEOF_").append(f.oneof().upperConstName()).append("_FIELDS, ONEOF_")
                    .append(f.oneof().upperConstName()).append("_MASKS\n");
                sb.append("        );\n");
            } else {
                sb.append("        long[] idx = getOrBuildIndex();\n");
                sb.append("        com.omnibuf.wire.OmniBufOpaque.PacketView view = idx != null\n");
                sb.append("            ? com.omnibuf.wire.OmniBufOpaque.readSubMsgViewIndexed(idx, buffer, offset, length, ").append(fnum).append(")\n");
                sb.append("            : com.omnibuf.wire.OmniBufOpaque.readSubMsgView(buffer, offset, length, ").append(fnum).append(");\n");
            }
            sb.append("        return view == null ? null : new ").append(subOpaque).append("(view);\n");
            sb.append("    }\n\n");

            sb.append("    public boolean has").append(f.pascalName()).append("() {\n");
            if (f.oneof() != null) {
                sb.append("        return get").append(f.oneof().pascalName()).append("Case() == ")
                    .append(fnum).append(";\n");
            } else {
                sb.append("        DirtyState d = this.dirty;\n");
                sb.append("        if (").append(dirtyCheck).append(") {\n");
                sb.append("            return d.dirty_").append(f.camelName()).append(" != null;\n");
                sb.append("        }\n");
                sb.append("        return com.omnibuf.wire.OmniBufOpaque.hasField(buffer, offset, length, ")
                    .append(fnum).append(", 1 << WireType.WIRE_SUB_MSG);\n");
            }
            sb.append("    }\n\n");

            sb.append("    public ").append(info.opaqueClass()).append(" set").append(f.pascalName())
                .append('(').append(subModel).append(" value) {\n");
            if (f.oneof() != null) {
                sb.append("        if (value == null) {\n");
                sb.append("            if (get").append(f.oneof().pascalName()).append("Case() == ")
                    .append(fnum).append(") {\n");
                sb.append("                return clear").append(f.oneof().pascalName()).append("();\n");
                sb.append("            }\n");
                sb.append("            return this;\n");
                sb.append("        }\n");
                sb.append("        DirtyState d = ensureDirty();\n");
                emitOpaqueOneofMarkDirty(sb, f.oneof(), fnum, useBitSet);
                sb.append("        d.dirty_").append(f.camelName()).append(" = value;\n");
            } else {
                sb.append("        DirtyState d = ensureDirty();\n");
                sb.append("        d.dirty_").append(f.camelName()).append(" = value;\n");
                sb.append("        ").append(markDirty).append('\n');
            }
            sb.append("        return this;\n");
            sb.append("    }\n\n");
            if (f.oneof() == null) {
                sb.append("    public ").append(info.opaqueClass()).append(" clear").append(f.pascalName()).append("() {\n");
                sb.append("        return set").append(f.pascalName()).append("(null);\n");
                sb.append("    }\n\n");
            }
            return;
        }

        String fType = javaFieldType(f);
        String readerMethod = opaqueScalarReader(f.proto().getType());
        sb.append("    public ").append(fType).append(" get").append(f.pascalName()).append("() {\n");
        if (f.oneof() != null) {
            sb.append("        if (get").append(f.oneof().pascalName()).append("Case() != ")
                .append(fnum).append(") {\n");
            sb.append("            return ").append(opaqueDirtyResetExpr(f)).append(";\n");
            sb.append("        }\n");
        }
        sb.append("        DirtyState d = this.dirty;\n");
        sb.append("        if (").append(dirtyCheck).append(") {\n");
        sb.append("            return d.dirty_").append(f.camelName()).append(";\n");
        sb.append("        }\n");
        sb.append("        long[] idx = getOrBuildIndex();\n");
        sb.append("        if (idx != null) {\n");
        sb.append("            return com.omnibuf.wire.OmniBufOpaque.").append(readerMethod).append("Indexed(idx, buffer, offset, length, ").append(fnum).append(");\n");
        sb.append("        }\n");
        sb.append("        return com.omnibuf.wire.OmniBufOpaque.").append(readerMethod).append("(buffer, offset, length, ").append(fnum).append(");\n");
        sb.append("    }\n\n");

        if (f.proto().getType() == Type.TYPE_BYTES) {
            sb.append("    public com.omnibuf.wire.OmniBufOpaque.PacketView get").append(f.pascalName()).append("View() {\n");
            if (f.oneof() != null) {
                sb.append("        if (get").append(f.oneof().pascalName()).append("Case() != ")
                    .append(fnum).append(") {\n");
                sb.append("            return new com.omnibuf.wire.OmniBufOpaque.PacketView(EMPTY_BYTES, 0, 0);\n");
                sb.append("        }\n");
            }
            sb.append("        DirtyState d = this.dirty;\n");
            sb.append("        if (").append(dirtyCheck).append(") {\n");
            sb.append("            byte[] b = d.dirty_").append(f.camelName()).append(";\n");
            sb.append("            return new com.omnibuf.wire.OmniBufOpaque.PacketView(b, 0, b.length);\n");
            sb.append("        }\n");
            sb.append("        long[] idx = getOrBuildIndex();\n");
            sb.append("        if (idx != null) {\n");
            sb.append("            return com.omnibuf.wire.OmniBufOpaque.readBytesViewIndexed(idx, buffer, offset, length, ").append(fnum).append(");\n");
            sb.append("        }\n");
            sb.append("        return com.omnibuf.wire.OmniBufOpaque.readBytesView(buffer, offset, length, ").append(fnum).append(");\n");
            sb.append("    }\n\n");

            sb.append("    public ByteBuffer get").append(f.pascalName()).append("Buffer() {\n");
            sb.append("        return get").append(f.pascalName()).append("View().asByteBuffer();\n");
            sb.append("    }\n\n");
        }

        if (f.oneof() != null) {
            sb.append("    public boolean has").append(f.pascalName()).append("() {\n");
            sb.append("        return get").append(f.oneof().pascalName()).append("Case() == ")
                .append(fnum).append(";\n");
            sb.append("    }\n\n");
        } else if (f.hasExplicitScalarPresence()) {
            sb.append("    public boolean has").append(f.pascalName()).append("() {\n");
            sb.append("        DirtyState d = this.dirty;\n");
            sb.append("        if (").append(dirtyCheck).append(") {\n");
            sb.append("            return d.dirtyPresent_").append(f.camelName()).append(";\n");
            sb.append("        }\n");
            sb.append("        return com.omnibuf.wire.OmniBufOpaque.hasField(buffer, offset, length, ")
                .append(fnum).append(", ").append(wireMaskExpr(f.proto().getType())).append(");\n");
            sb.append("    }\n\n");

            sb.append("    public ").append(info.opaqueClass()).append(" clear").append(f.pascalName()).append("() {\n");
            sb.append("        DirtyState d = ensureDirty();\n");
            sb.append("        d.dirty_").append(f.camelName()).append(" = ")
                .append(opaqueDirtyResetExpr(f)).append(";\n");
            sb.append("        d.dirtyPresent_").append(f.camelName()).append(" = false;\n");
            sb.append("        ").append(markDirty).append('\n');
            sb.append("        return this;\n");
            sb.append("    }\n\n");
        }

        sb.append("    public ").append(info.opaqueClass()).append(" set").append(f.pascalName())
            .append('(').append(fType).append(" value) {\n");
        sb.append("        DirtyState d = ensureDirty();\n");
        if (f.proto().getType() == Type.TYPE_STRING) {
            sb.append("        d.dirty_").append(f.camelName()).append(" = value == null ? \"\" : value;\n");
        } else if (f.proto().getType() == Type.TYPE_BYTES) {
            sb.append("        d.dirty_").append(f.camelName()).append(" = value == null ? EMPTY_BYTES : value;\n");
        } else {
            sb.append("        d.dirty_").append(f.camelName()).append(" = value;\n");
        }
        if (f.oneof() != null) {
            emitOpaqueOneofMarkDirty(sb, f.oneof(), fnum, useBitSet);
        } else {
            if (f.hasExplicitScalarPresence()) {
                sb.append("        d.dirtyPresent_").append(f.camelName()).append(" = true;\n");
            }
            sb.append("        ").append(markDirty).append('\n');
        }
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

    private static String modelFieldDeclInit(ResolvedField f) {
        if (f.isMap() || f.isRepeated()) {
            return null;
        }
        return switch (f.proto().getType()) {
            case TYPE_STRING -> "\"\"";
            case TYPE_BYTES -> "EMPTY_BYTES";
            default -> null;
        };
    }

    private static String javaScalarResetExpr(ResolvedField f) {
        return switch (f.proto().getType()) {
            case TYPE_INT32, TYPE_SINT32, TYPE_UINT32, TYPE_FIXED32, TYPE_SFIXED32, TYPE_ENUM -> "0";
            case TYPE_INT64, TYPE_SINT64, TYPE_UINT64, TYPE_FIXED64, TYPE_SFIXED64 -> "0L";
            case TYPE_BOOL -> "false";
            case TYPE_FLOAT -> "0.0f";
            case TYPE_DOUBLE -> "0.0d";
            case TYPE_STRING -> "\"\"";
            case TYPE_BYTES -> "EMPTY_BYTES";
            default -> "null";
        };
    }

    private static String opaqueDirtyResetExpr(ResolvedField f) {
        return javaScalarResetExpr(f);
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

    private static String wireMaskExpr(Type t) {
        if (t == Type.TYPE_BOOL) {
            return "(1 << WireType.WIRE_BOOL_FALSE) | (1 << WireType.WIRE_BOOL_TRUE)";
        }
        return "1 << " + wireTypeConst(t);
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

    private static String bulkRepeatedReadMethod(Type t) {
        return switch (t) {
            case TYPE_UINT32 -> "readUint32Into";
            case TYPE_INT32, TYPE_SINT32, TYPE_ENUM -> "readSint32Into";
            case TYPE_FIXED32, TYPE_SFIXED32 -> "readFixed32Into";
            case TYPE_UINT64 -> "readUint64Into";
            case TYPE_INT64, TYPE_SINT64 -> "readSint64Into";
            case TYPE_FIXED64, TYPE_SFIXED64 -> "readFixed64Into";
            default -> null;
        };
    }

    private static String arrayElementReadExpr(Type t) {
        return switch (t) {
            case TYPE_BOOL -> "dec.readContainerBool()";
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
            default -> throw new IllegalStateException("Unsupported array element read: " + t);
        };
    }

    private static String opaqueLambdaReadExpr(Type t) {
        return arrayElementReadExpr(t);
    }

    private static String opaqueScalarReader(Type t) {
        return switch (t) {
            case TYPE_UINT32 -> "readUint32";
            case TYPE_UINT64 -> "readUint64";
            case TYPE_INT32, TYPE_SINT32, TYPE_ENUM -> "readSint32";
            case TYPE_INT64, TYPE_SINT64 -> "readSint64";
            case TYPE_BOOL -> "readBool";
            case TYPE_FIXED32 -> "readFixed32";
            case TYPE_SFIXED32 -> "readSfixed32";
            case TYPE_FIXED64 -> "readFixed64";
            case TYPE_SFIXED64 -> "readSfixed64";
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
            case TYPE_FIXED32 -> "readFixed32List";
            case TYPE_SFIXED32 -> "readSfixed32List";
            case TYPE_FIXED64 -> "readFixed64List";
            case TYPE_SFIXED64 -> "readSfixed64List";
            case TYPE_FLOAT -> "readFloat32List";
            case TYPE_DOUBLE -> "readFloat64List";
            default -> throw new IllegalStateException("Unsupported opaque repeated: " + t);
        };
    }

    private static String[] sanitizeNames(
            String protoName, int number, Set<String> usedCamels, Set<String> usedPascals) {
        String raw = snakeToCamelRaw(protoName);
        if (raw.isEmpty()) {
            raw = "field" + number;
        } else if (!Character.isJavaIdentifierStart(raw.charAt(0))) {
            raw = "f" + raw;
        }
        String camel = Character.toLowerCase(raw.charAt(0)) + raw.substring(1);
        String pascal = Character.toUpperCase(raw.charAt(0)) + raw.substring(1);

        if (JAVA_RESERVED.contains(camel)) {
            camel = camel + "_";
        }
        if (RESERVED_PASCAL_NAMES.contains(pascal)) {
            pascal = pascal + "_";
        }

        if (!usedCamels.add(camel)) {
            camel = camel + "_" + number;
            usedCamels.add(camel);
        }
        if (!usedPascals.add(pascal)) {
            pascal = pascal + "_" + number;
            usedPascals.add(pascal);
        }
        return new String[] {camel, pascal};
    }

    private static String snakeToCamelRaw(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        boolean upperNext = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '_' || !Character.isJavaIdentifierPart(c)) {
                if (sb.length() > 0) {
                    upperNext = true;
                }
            } else if (upperNext) {
                sb.append(Character.toUpperCase(c));
                upperNext = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String toUpperSnake(String s, int index) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toUpperCase(c));
            } else {
                sb.append('_');
            }
        }
        String res = sb.toString();
        if (res.isEmpty() || Character.isDigit(res.charAt(0))) {
            res = "ONEOF_" + index + "_" + res;
        }
        return res + "_" + index;
    }

    private static String escapeJavaString(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '"') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
