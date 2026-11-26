package com.omnibuf.plugin;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.Edition;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.FeatureSet;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.UnknownFieldSet;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves Protobuf Editions (2023, 2024, 2026) features and validates OmniBuf schema constraints.
 */
final class EditionResolver {

    static final int MIN_EDITION = Edition.EDITION_2023_VALUE;
    static final int MAX_EDITION = Edition.EDITION_2026_VALUE;
    static final int SUPPORTED_FEATURES =
        CodeGeneratorResponse.Feature.FEATURE_PROTO3_OPTIONAL_VALUE
            | CodeGeneratorResponse.Feature.FEATURE_SUPPORTS_EDITIONS_VALUE;

    private EditionResolver() {}

    static boolean isEditions(FileDescriptorProto file) {
        return "editions".equals(file.getSyntax());
    }

    static int extractEditionNumber(FileDescriptorProto file) {
        if (file.hasEdition()) {
            return file.getEdition().getNumber();
        }
        UnknownFieldSet unknown = file.getUnknownFields();
        if (unknown.hasField(FileDescriptorProto.EDITION_FIELD_NUMBER)) {
            List<Long> varints = unknown.getField(FileDescriptorProto.EDITION_FIELD_NUMBER).getVarintList();
            if (!varints.isEmpty()) {
                return (int) varints.get(0).longValue();
            }
        }
        return Edition.EDITION_UNKNOWN_VALUE;
    }

    static boolean hasEditionField(FileDescriptorProto file) {
        return file.hasEdition()
            || file.getUnknownFields().hasField(FileDescriptorProto.EDITION_FIELD_NUMBER);
    }

    static boolean isDescriptorOptionExtension(FieldDescriptorProto ext) {
        String extendee = normalizeTypeName(ext.getExtendee());
        if (!extendee.startsWith(".google.protobuf.") || !extendee.endsWith("Options")) {
            return false;
        }
        return extendee.indexOf('.', ".google.protobuf.".length()) < 0;
    }

    static FeatureSet defaultEditionFeatures() {
        return FeatureSet.newBuilder()
            .setFieldPresence(FeatureSet.FieldPresence.EXPLICIT)
            .setEnumType(FeatureSet.EnumType.OPEN)
            .setRepeatedFieldEncoding(FeatureSet.RepeatedFieldEncoding.PACKED)
            .setUtf8Validation(FeatureSet.Utf8Validation.VERIFY)
            .setMessageEncoding(FeatureSet.MessageEncoding.LENGTH_PREFIXED)
            .setJsonFormat(FeatureSet.JsonFormat.ALLOW)
            .build();
    }

    /**
     * Records whether every enum declared in {@code file} is closed without throwing on
     * unsupported dependency features (such as {@code google/protobuf/descriptor.proto}).
     */
    static void collectEnumClosedMap(FileDescriptorProto file, Map<String, Boolean> enumClosedOut) {
        String protoPkg = file.getPackage();
        String prefix = protoPkg.isEmpty() ? "." : "." + protoPkg + ".";
        if (isEditions(file)) {
            FeatureSet base = defaultEditionFeatures().toBuilder()
                .mergeFrom(file.getOptions().getFeatures())
                .build();
            for (EnumDescriptorProto e : file.getEnumTypeList()) {
                FeatureSet resolved = base.toBuilder().mergeFrom(e.getOptions().getFeatures()).build();
                enumClosedOut.put(prefix + e.getName(), resolved.getEnumType() == FeatureSet.EnumType.CLOSED);
            }
            for (DescriptorProto msg : file.getMessageTypeList()) {
                collectMessageEnums(prefix + msg.getName(), msg, base, enumClosedOut);
            }
            return;
        }
        boolean closedBySyntax = !"proto3".equals(file.getSyntax());
        for (EnumDescriptorProto e : file.getEnumTypeList()) {
            enumClosedOut.put(prefix + e.getName(), closedBySyntax);
        }
        for (DescriptorProto msg : file.getMessageTypeList()) {
            collectLegacyMessageEnums(prefix + msg.getName(), msg, closedBySyntax, enumClosedOut);
        }
    }

    private static void collectMessageEnums(
            String fullMsgName,
            DescriptorProto msg,
            FeatureSet parent,
            Map<String, Boolean> enumClosedOut) {
        FeatureSet features = parent.toBuilder().mergeFrom(msg.getOptions().getFeatures()).build();
        for (EnumDescriptorProto e : msg.getEnumTypeList()) {
            FeatureSet resolved = features.toBuilder().mergeFrom(e.getOptions().getFeatures()).build();
            enumClosedOut.put(fullMsgName + "." + e.getName(), resolved.getEnumType() == FeatureSet.EnumType.CLOSED);
        }
        for (DescriptorProto nested : msg.getNestedTypeList()) {
            collectMessageEnums(fullMsgName + "." + nested.getName(), nested, features, enumClosedOut);
        }
    }

    private static void collectLegacyMessageEnums(
            String fullMsgName,
            DescriptorProto msg,
            boolean closedBySyntax,
            Map<String, Boolean> enumClosedOut) {
        for (EnumDescriptorProto e : msg.getEnumTypeList()) {
            enumClosedOut.put(fullMsgName + "." + e.getName(), closedBySyntax);
        }
        for (DescriptorProto nested : msg.getNestedTypeList()) {
            collectLegacyMessageEnums(fullMsgName + "." + nested.getName(), nested, closedBySyntax, enumClosedOut);
        }
    }

    static void validateFileSyntaxAndEdition(FileDescriptorProto source) {
        if (isEditions(source)) {
            int edition = extractEditionNumber(source);
            if (edition != Edition.EDITION_2023_VALUE
                    && edition != Edition.EDITION_2024_VALUE
                    && edition != Edition.EDITION_2026_VALUE) {
                throw new IllegalArgumentException(
                    "omnibuf: unsupported protobuf edition " + edition + " in " + source.getName()
                );
            }
            return;
        }
        String syntax = source.getSyntax();
        if (!syntax.isEmpty() && !"proto2".equals(syntax) && !"proto3".equals(syntax)) {
            throw new IllegalArgumentException(
                "omnibuf: unsupported protobuf edition/syntax \"" + syntax + "\" in " + source.getName()
            );
        }
        if (hasEditionField(source)) {
            int edition = extractEditionNumber(source);
            if (edition != Edition.EDITION_PROTO2_VALUE && edition != Edition.EDITION_PROTO3_VALUE) {
                throw new IllegalArgumentException(
                    "omnibuf: unsupported protobuf edition " + edition + " in " + source.getName()
                );
            }
        }
    }

    static FileDescriptorProto resolve(FileDescriptorProto source) {
        validateFileSyntaxAndEdition(source);
        if (!isEditions(source)) {
            return source;
        }

        for (FieldDescriptorProto ext : source.getExtensionList()) {
            if (!isDescriptorOptionExtension(ext)) {
                throw new IllegalArgumentException(
                    "omnibuf: editions extensions are not supported in "
                        + source.getName() + " (" + ext.getName() + " extends " + ext.getExtendee() + ")"
                );
            }
        }

        FeatureSet base = defaultEditionFeatures().toBuilder()
            .mergeFrom(source.getOptions().getFeatures())
            .build();
        FileDescriptorProto.Builder file = source.toBuilder().setSyntax("proto3");
        for (EnumDescriptorProto e : source.getEnumTypeList()) {
            checkEnum(e, base);
        }
        for (int i = 0; i < source.getMessageTypeCount(); i++) {
            file.setMessageType(i, resolveMessage(source.getMessageType(i), base));
        }
        return file.build();
    }

    private static void checkEnum(EnumDescriptorProto e, FeatureSet parent) {
        FeatureSet features = parent.toBuilder().mergeFrom(e.getOptions().getFeatures()).build();
        if (features.getEnumType() != FeatureSet.EnumType.OPEN) {
            throw new IllegalArgumentException(
                "omnibuf: editions CLOSED enums are not supported (" + e.getName() + ")"
            );
        }
    }

    private static DescriptorProto resolveMessage(DescriptorProto source, FeatureSet parent) {
        if (source.getExtensionRangeCount() > 0) {
            throw new IllegalArgumentException(
                "omnibuf: editions extension ranges are not supported on message " + source.getName()
            );
        }
        for (FieldDescriptorProto ext : source.getExtensionList()) {
            if (!isDescriptorOptionExtension(ext)) {
                throw new IllegalArgumentException(
                    "omnibuf: editions extensions are not supported on message "
                        + source.getName() + " (" + ext.getName() + " extends " + ext.getExtendee() + ")"
                );
            }
        }

        FeatureSet features = parent.toBuilder().mergeFrom(source.getOptions().getFeatures()).build();
        DescriptorProto.Builder msg = source.toBuilder();
        for (EnumDescriptorProto e : source.getEnumTypeList()) {
            checkEnum(e, features);
        }

        Map<String, FeatureSet> mapFieldFeaturesByEntry = new HashMap<>();
        for (int i = 0; i < source.getFieldCount(); i++) {
            FieldDescriptorProto field = source.getField(i);
            FeatureSet.Builder resolvedBuilder = features.toBuilder();
            if (field.hasOneofIndex()
                    && field.getOneofIndex() >= 0
                    && field.getOneofIndex() < source.getOneofDeclCount()) {
                resolvedBuilder.mergeFrom(
                    source.getOneofDecl(field.getOneofIndex()).getOptions().getFeatures()
                );
            }
            FeatureSet resolved = resolvedBuilder.mergeFrom(field.getOptions().getFeatures()).build();

            if (field.getLabel() == FieldDescriptorProto.Label.LABEL_REQUIRED
                    || (resolved.getFieldPresence() != FeatureSet.FieldPresence.EXPLICIT
                        && resolved.getFieldPresence() != FeatureSet.FieldPresence.IMPLICIT)) {
                throw new IllegalArgumentException(
                    "omnibuf: editions LEGACY_REQUIRED is not supported (" + field.getName() + ")"
                );
            }
            if (field.getType() == FieldDescriptorProto.Type.TYPE_STRING
                    && resolved.getUtf8Validation() != FeatureSet.Utf8Validation.VERIFY) {
                throw new IllegalArgumentException(
                    "omnibuf: editions UTF-8 NONE is not supported (" + field.getName() + ")"
                );
            }

            if (field.getLabel() == FieldDescriptorProto.Label.LABEL_REPEATED
                    && (field.getType() == FieldDescriptorProto.Type.TYPE_MESSAGE
                        || field.getType() == FieldDescriptorProto.Type.TYPE_GROUP)
                    && field.getOptions().hasFeatures()) {
                String entrySimple = simpleTypeName(field.getTypeName());
                if (!entrySimple.isEmpty()) {
                    mapFieldFeaturesByEntry.put(entrySimple, field.getOptions().getFeatures());
                }
            }

            FieldDescriptorProto.Builder f = field.toBuilder();
            if (field.getType() == FieldDescriptorProto.Type.TYPE_GROUP) {
                f.setType(FieldDescriptorProto.Type.TYPE_MESSAGE);
            }
            if (!field.hasOneofIndex()
                    && field.getLabel() != FieldDescriptorProto.Label.LABEL_REPEATED
                    && !source.getOptions().getMapEntry()
                    && f.getType() != FieldDescriptorProto.Type.TYPE_MESSAGE) {
                f.setProto3Optional(resolved.getFieldPresence() == FeatureSet.FieldPresence.EXPLICIT);
            }
            msg.setField(i, f);
        }

        for (int i = 0; i < source.getNestedTypeCount(); i++) {
            DescriptorProto nested = source.getNestedType(i);
            FeatureSet nestedParent = features;
            if (nested.getOptions().getMapEntry()) {
                FeatureSet mapFieldFeatures = mapFieldFeaturesByEntry.get(nested.getName());
                if (mapFieldFeatures != null) {
                    nestedParent = features.toBuilder().mergeFrom(mapFieldFeatures).build();
                }
            }
            msg.setNestedType(i, resolveMessage(nested, nestedParent));
        }
        return msg.build();
    }

    private static String normalizeTypeName(String typeName) {
        if (typeName == null || typeName.isEmpty()) {
            return "";
        }
        return typeName.startsWith(".") ? typeName : "." + typeName;
    }

    private static String simpleTypeName(String typeName) {
        if (typeName == null || typeName.isEmpty()) {
            return "";
        }
        int dot = typeName.lastIndexOf('.');
        return dot >= 0 ? typeName.substring(dot + 1) : typeName;
    }
}
