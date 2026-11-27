package com.omnibuf;

import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.Edition;
import com.google.protobuf.DescriptorProtos.FeatureSet;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.OneofDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.UnknownFieldSet;
import com.omnibuf.wire.CodecPool;
import com.omnibuf.wire.OmniBufDecoder;
import com.omnibuf.wire.OmniBufEncoder;
import com.omnibuf.wire.PrefixVarint;
import com.omnibuf.wire.WireType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Descriptor-driven OmniBuf codec for standard {@code protoc --java_out} {@link Message}
 * types ({@code TIER_PROTOBUF_TYPES}).
 */
public final class OmniBufProtobuf {

    private static final byte[] EMPTY_BYTES = new byte[0];

    private static final class DescriptorPlan {
        final Descriptor descriptor;
        final boolean isEditions;
        final boolean isExtendable;
        final FieldDescriptor[] orderedFields;

        DescriptorPlan(
                Descriptor descriptor,
                boolean isEditions,
                boolean isExtendable,
                FieldDescriptor[] orderedFields) {
            this.descriptor = descriptor;
            this.isEditions = isEditions;
            this.isExtendable = isExtendable;
            this.orderedFields = orderedFields;
        }
    }

    private static final ClassValue<AtomicReference<DescriptorPlan>> PLAN_CACHE = new ClassValue<>() {
        @Override
        protected AtomicReference<DescriptorPlan> computeValue(Class<?> type) {
            return new AtomicReference<>();
        }
    };

    private OmniBufProtobuf() {}

    public static byte[] marshal(MessageOrBuilder message) {
        return marshal(message, MarshalOptions.DEFAULT);
    }

    public static byte[] marshal(MessageOrBuilder message, MarshalOptions options) {
        if (message == null) {
            throw new OmniBufException("omnibuf: cannot marshal null protobuf message");
        }
        DescriptorPlan plan = validatedPlanFor(message);
        if (!message.isInitialized()) {
            throw new OmniBufException("omnibuf: protobuf message has uninitialized required fields");
        }
        MarshalOptions opts = options == null ? MarshalOptions.DEFAULT : options;
        OmniBufEncoder enc = CodecPool.acquireEncoder();
        try {
            if (opts.selfDescribing()) {
                boolean[] cyclicOut = new boolean[1];
                SchemaMeta meta = buildSchemaMeta(
                    plan.descriptor, 1, new HashSet<>(), cyclicOut
                );
                if (cyclicOut[0]) {
                    meta = buildValueSchemaMeta(message, 1);
                }
                if (meta != null) {
                    meta.writeField0(enc, opts.deterministic());
                }
            }
            encodeMessage(enc, message, plan, opts.deterministic());
            return enc.toByteArray();
        } finally {
            CodecPool.releaseEncoder(enc);
        }
    }

    public static void unmarshal(byte[] data, Message.Builder builder) {
        unmarshal(data, builder, UnmarshalOptions.DEFAULT);
    }

    public static void unmarshal(byte[] data, Message.Builder builder, UnmarshalOptions options) {
        if (data == null) {
            throw new OmniBufException("omnibuf: null packet buffer");
        }
        if (builder == null) {
            throw new OmniBufException("omnibuf: null protobuf Message.Builder");
        }
        DescriptorPlan plan = validatedPlanFor(builder);
        builder.clear();
        OmniBufDecoder dec = CodecPool.acquireDecoder(data, 0, data.length, options);
        try {
            decodeIntoBuilder(dec, builder, plan, 0);
        } finally {
            CodecPool.releaseDecoder(dec);
        }
        if (!builder.isInitialized()) {
            throw new OmniBufException(
                "omnibuf: protobuf message missing required fields: "
                    + builder.findInitializationErrors()
            );
        }
    }

    public static <M extends Message> M unmarshal(byte[] data, M defaultInstance) {
        return unmarshal(data, defaultInstance, UnmarshalOptions.DEFAULT);
    }

    @SuppressWarnings("unchecked")
    public static <M extends Message> M unmarshal(
            byte[] data, M defaultInstance, UnmarshalOptions options) {
        if (defaultInstance == null) {
            throw new OmniBufException("omnibuf: null protobuf defaultInstance");
        }
        Message.Builder builder = defaultInstance.newBuilderForType();
        unmarshal(data, builder, options);
        return (M) builder.build();
    }

    public static SchemaMeta schemaMetaFor(Descriptor descriptor) {
        if (descriptor != null) {
            validateDescriptorSupported(descriptor);
        }
        return buildSchemaMeta(descriptor, 1, new HashSet<>(), new boolean[1]);
    }

    private static boolean isEditionsFile(FileDescriptor file) {
        return file != null && "editions".equals(file.toProto().getSyntax());
    }

    private static boolean isMessageLike(FieldDescriptor fd) {
        return fd.getType() == FieldDescriptor.Type.MESSAGE
            || (fd.getType() == FieldDescriptor.Type.GROUP && isEditionsFile(fd.getFile()));
    }

    private static int extractEditionNumber(FileDescriptorProto proto) {
        if (proto.hasEdition()) {
            return proto.getEdition().getNumber();
        }
        UnknownFieldSet unknown = proto.getUnknownFields();
        if (unknown.hasField(FileDescriptorProto.EDITION_FIELD_NUMBER)) {
            List<Long> varints = unknown.getField(FileDescriptorProto.EDITION_FIELD_NUMBER).getVarintList();
            if (!varints.isEmpty()) {
                return (int) varints.get(0).longValue();
            }
        }
        return Edition.EDITION_UNKNOWN_VALUE;
    }

    private static boolean hasEditionField(FileDescriptorProto proto) {
        return proto.hasEdition()
            || proto.getUnknownFields().hasField(FileDescriptorProto.EDITION_FIELD_NUMBER);
    }

    private static boolean isDescriptorOptionExtension(FieldDescriptorProto ext) {
        String extendee = ext.getExtendee();
        if (extendee == null || extendee.isEmpty()) {
            return false;
        }
        if (!extendee.startsWith(".")) {
            extendee = "." + extendee;
        }
        if (!extendee.startsWith(".google.protobuf.") || !extendee.endsWith("Options")) {
            return false;
        }
        return extendee.indexOf('.', ".google.protobuf.".length()) < 0;
    }

    private static FeatureSet defaultEditionFeatures() {
        return FeatureSet.newBuilder()
            .setFieldPresence(FeatureSet.FieldPresence.EXPLICIT)
            .setEnumType(FeatureSet.EnumType.OPEN)
            .setRepeatedFieldEncoding(FeatureSet.RepeatedFieldEncoding.PACKED)
            .setUtf8Validation(FeatureSet.Utf8Validation.VERIFY)
            .setMessageEncoding(FeatureSet.MessageEncoding.LENGTH_PREFIXED)
            .setJsonFormat(FeatureSet.JsonFormat.ALLOW)
            .build();
    }

    private static FeatureSet resolveMessageFeatures(Descriptor md) {
        Deque<Descriptor> chain = new ArrayDeque<>();
        for (Descriptor cur = md; cur != null; cur = cur.getContainingType()) {
            chain.addFirst(cur);
        }
        FeatureSet.Builder fb = defaultEditionFeatures().toBuilder();
        fb.mergeFrom(md.getFile().toProto().getOptions().getFeatures());
        for (Descriptor d : chain) {
            fb.mergeFrom(d.toProto().getOptions().getFeatures());
        }
        return fb.build();
    }

    private static FeatureSet resolveFieldFeatures(FieldDescriptor fd, FeatureSet messageFeatures) {
        FeatureSet.Builder fb = messageFeatures.toBuilder();
        OneofDescriptor oneof = fd.getContainingOneof();
        if (oneof != null) {
            fb.mergeFrom(oneof.toProto().getOptions().getFeatures());
        }
        fb.mergeFrom(fd.toProto().getOptions().getFeatures());
        return fb.build();
    }

    private static boolean isZeroDescriptorDefault(FieldDescriptor fd) {
        Object dv = fd.getDefaultValue();
        return switch (fd.getType()) {
            case INT32, SINT32, UINT32, FIXED32, SFIXED32 -> dv instanceof Integer i && i == 0;
            case INT64, SINT64, UINT64, FIXED64, SFIXED64 -> dv instanceof Long l && l == 0L;
            case FLOAT -> dv instanceof Float f && Float.floatToRawIntBits(f) == 0;
            case DOUBLE -> dv instanceof Double d && Double.doubleToRawLongBits(d) == 0L;
            case BOOL -> Boolean.FALSE.equals(dv);
            case STRING -> dv instanceof String s && s.isEmpty();
            case BYTES -> dv instanceof ByteString bs && bs.isEmpty();
            case ENUM -> dv instanceof EnumValueDescriptor ev && ev.getNumber() == 0;
            default -> false;
        };
    }

    private static void validateFileSupported(FileDescriptor file) {
        FileDescriptorProto proto = file.toProto();
        if ("editions".equals(proto.getSyntax())) {
            int edition = extractEditionNumber(proto);
            if (edition != Edition.EDITION_2023_VALUE
                    && edition != Edition.EDITION_2024_VALUE
                    && edition != Edition.EDITION_2026_VALUE) {
                throw new OmniBufException(
                    "omnibuf: unsupported protobuf edition " + edition + " in " + file.getName()
                );
            }
            for (FieldDescriptorProto ext : proto.getExtensionList()) {
                if (!isDescriptorOptionExtension(ext)) {
                    throw new OmniBufException(
                        "omnibuf: editions extensions are not supported in "
                            + file.getName() + " (" + ext.getName() + " extends " + ext.getExtendee() + ")"
                    );
                }
            }
            for (EnumDescriptor ed : file.getEnumTypes()) {
                if (ed.isClosed()) {
                    throw new OmniBufException(
                        "omnibuf: editions CLOSED enums are not supported (" + ed.getFullName() + ")"
                    );
                }
            }
            return;
        }
        String syntax = proto.getSyntax();
        if (!syntax.isEmpty() && !"proto2".equals(syntax) && !"proto3".equals(syntax)) {
            throw new OmniBufException(
                "omnibuf: unsupported protobuf edition/syntax \"" + syntax + "\" in " + file.getName()
            );
        }
        if (hasEditionField(proto)) {
            int edition = extractEditionNumber(proto);
            if (edition != Edition.EDITION_PROTO2_VALUE && edition != Edition.EDITION_PROTO3_VALUE) {
                throw new OmniBufException(
                    "omnibuf: unsupported protobuf edition " + edition + " in " + file.getName()
                );
            }
        }
    }

    private static void validateDescriptorSupported(Descriptor root) {
        if (root == null) {
            return;
        }
        validateDescriptorRecursive(root, new HashSet<>(), new HashSet<>());
    }

    private static void validateDescriptorRecursive(
            Descriptor md, Set<Descriptor> visited, Set<FileDescriptor> validatedFiles) {
        if (!visited.add(md)) {
            return;
        }
        FileDescriptor file = md.getFile();
        if (validatedFiles.add(file)) {
            validateFileSupported(file);
        }
        boolean isEditions = isEditionsFile(file);
        FeatureSet mdFeatures = null;
        if (isEditions) {
            if (md.isExtendable()) {
                throw new OmniBufException(
                    "omnibuf: editions extension ranges are not supported on message " + md.getFullName()
                );
            }
            for (FieldDescriptorProto ext : md.toProto().getExtensionList()) {
                if (!isDescriptorOptionExtension(ext)) {
                    throw new OmniBufException(
                        "omnibuf: editions extensions are not supported on message "
                            + md.getFullName() + " (" + ext.getName() + " extends " + ext.getExtendee() + ")"
                    );
                }
            }
            for (EnumDescriptor ed : md.getEnumTypes()) {
                if (ed.isClosed()) {
                    throw new OmniBufException(
                        "omnibuf: editions CLOSED enums are not supported (" + ed.getFullName() + ")"
                    );
                }
            }
            mdFeatures = resolveMessageFeatures(md);
        }

        for (FieldDescriptor fd : md.getFields()) {
            if (fd.getType() == FieldDescriptor.Type.GROUP && !isEditions) {
                throw new OmniBufException(
                    "omnibuf: proto2 groups are not supported: " + fd.getFullName()
                );
            }
            if (fd.isMapField()) {
                Descriptor entryDesc = fd.getMessageType();
                FieldDescriptor keyFd = entryDesc.findFieldByNumber(1);
                FieldDescriptor valFd = entryDesc.findFieldByNumber(2);
                if (isEditions) {
                    FeatureSet mapBase = mdFeatures.toBuilder()
                        .mergeFrom(fd.toProto().getOptions().getFeatures())
                        .mergeFrom(entryDesc.toProto().getOptions().getFeatures())
                        .build();
                    if (keyFd != null) {
                        FeatureSet keyFeatures = mapBase.toBuilder()
                            .mergeFrom(keyFd.toProto().getOptions().getFeatures())
                            .build();
                        if (keyFd.getType() == FieldDescriptor.Type.STRING
                                && keyFeatures.getUtf8Validation() != FeatureSet.Utf8Validation.VERIFY) {
                            throw new OmniBufException(
                                "omnibuf: editions UTF-8 NONE is not supported (" + fd.getFullName() + ".key)"
                            );
                        }
                    }
                    if (valFd != null) {
                        FeatureSet valFeatures = mapBase.toBuilder()
                            .mergeFrom(valFd.toProto().getOptions().getFeatures())
                            .build();
                        if (valFd.getType() == FieldDescriptor.Type.STRING
                                && valFeatures.getUtf8Validation() != FeatureSet.Utf8Validation.VERIFY) {
                            throw new OmniBufException(
                                "omnibuf: editions UTF-8 NONE is not supported (" + fd.getFullName() + ".value)"
                            );
                        }
                        if (valFd.getType() == FieldDescriptor.Type.ENUM && valFd.getEnumType().isClosed()) {
                            throw new OmniBufException(
                                "omnibuf: editions CLOSED enums are not supported ("
                                    + fd.getFullName() + " -> " + valFd.getEnumType().getFullName() + ")"
                            );
                        }
                    }
                }
                if (valFd != null) {
                    if (valFd.getType() == FieldDescriptor.Type.GROUP && !isEditionsFile(valFd.getFile())) {
                        throw new OmniBufException(
                            "omnibuf: proto2 groups are not supported: " + valFd.getFullName()
                        );
                    }
                    if (isMessageLike(valFd)) {
                        validateDescriptorRecursive(valFd.getMessageType(), visited, validatedFiles);
                    }
                }
                continue;
            }

            if (isEditions) {
                FeatureSet fdFeatures = resolveFieldFeatures(fd, mdFeatures);
                if (fd.isRequired()
                        || (fdFeatures.getFieldPresence() != FeatureSet.FieldPresence.EXPLICIT
                            && fdFeatures.getFieldPresence() != FeatureSet.FieldPresence.IMPLICIT)) {
                    throw new OmniBufException(
                        "omnibuf: editions LEGACY_REQUIRED is not supported (" + fd.getFullName() + ")"
                    );
                }
                if (fd.getType() == FieldDescriptor.Type.STRING
                        && fdFeatures.getUtf8Validation() != FeatureSet.Utf8Validation.VERIFY) {
                    throw new OmniBufException(
                        "omnibuf: editions UTF-8 NONE is not supported (" + fd.getFullName() + ")"
                    );
                }
                if (fd.getType() == FieldDescriptor.Type.ENUM && fd.getEnumType().isClosed()) {
                    throw new OmniBufException(
                        "omnibuf: editions CLOSED enums are not supported ("
                            + fd.getFullName() + " -> " + fd.getEnumType().getFullName() + ")"
                    );
                }
                if (fd.hasDefaultValue() && !isZeroDescriptorDefault(fd)) {
                    throw new OmniBufException(
                        "omnibuf: custom non-zero default values are not supported on " + fd.getFullName()
                    );
                }
            }

            if (isMessageLike(fd)) {
                validateDescriptorRecursive(fd.getMessageType(), visited, validatedFiles);
            }
        }
    }

    private static DescriptorPlan validatedPlanFor(MessageOrBuilder message) {
        Descriptor md = message.getDescriptorForType();
        Class<?> cls = message.getClass();
        if (cls != DynamicMessage.class && cls != DynamicMessage.Builder.class) {
            AtomicReference<DescriptorPlan> slot = PLAN_CACHE.get(cls);
            DescriptorPlan cached = slot.get();
            if (cached != null && cached.descriptor == md) {
                return cached;
            }
            validateDescriptorSupported(md);
            DescriptorPlan plan = buildDescriptorPlan(md);
            if (cached == null) {
                slot.compareAndSet(null, plan);
            }
            return plan;
        }
        validateDescriptorSupported(md);
        return buildDescriptorPlan(md);
    }

    private static DescriptorPlan planForNested(MessageOrBuilder message) {
        Descriptor md = message.getDescriptorForType();
        Class<?> cls = message.getClass();
        if (cls != DynamicMessage.class && cls != DynamicMessage.Builder.class) {
            AtomicReference<DescriptorPlan> slot = PLAN_CACHE.get(cls);
            DescriptorPlan cached = slot.get();
            if (cached != null && cached.descriptor == md) {
                return cached;
            }
            validateDescriptorSupported(md);
            DescriptorPlan plan = buildDescriptorPlan(md);
            if (cached == null) {
                slot.compareAndSet(null, plan);
            }
            return plan;
        }
        return buildDescriptorPlan(md);
    }

    private static DescriptorPlan buildDescriptorPlan(Descriptor md) {
        boolean isEditions = isEditionsFile(md.getFile());
        List<FieldDescriptor> ordered = orderedFields(md, isEditions);
        return new DescriptorPlan(
            md,
            isEditions,
            md.isExtendable(),
            ordered.toArray(new FieldDescriptor[0])
        );
    }

    private static List<FieldDescriptor> orderedFields(Descriptor md) {
        return orderedFields(md, isEditionsFile(md.getFile()));
    }

    private static List<FieldDescriptor> orderedFields(Descriptor md, boolean isEditions) {
        List<FieldDescriptor> fds = md.getFields();
        int n = fds.size();
        boolean ascending = true;
        for (int i = 0; i < n; i++) {
            FieldDescriptor fd = fds.get(i);
            if (fd.getType() == FieldDescriptor.Type.GROUP && !isEditions) {
                throw new OmniBufException(
                    "omnibuf: proto2 groups are not supported: " + fd.getFullName()
                );
            }
            if (i > 0 && fd.getNumber() < fds.get(i - 1).getNumber()) {
                ascending = false;
            }
        }
        if (ascending) {
            return fds;
        }
        List<FieldDescriptor> sorted = new ArrayList<>(fds);
        sorted.sort(Comparator.comparingInt(FieldDescriptor::getNumber));
        return sorted;
    }

    private static SchemaMeta buildSchemaMeta(
            Descriptor md, int schemaDepth, Set<String> activeFullNames, boolean[] cyclicOut) {
        if (md == null) {
            return null;
        }
        if (!activeFullNames.add(md.getFullName())) {
            cyclicOut[0] = true;
            return null;
        }
        if (schemaDepth + 1 > OmniBufDecoder.MAX_CONTAINER_DEPTH) {
            activeFullNames.remove(md.getFullName());
            return null;
        }
        try {
            List<FieldDescriptor> fds = orderedFields(md);
            Map<Integer, String> fields = new LinkedHashMap<>(fds.size());
            Map<Integer, SchemaMeta> children = null;
            for (FieldDescriptor fd : fds) {
                int fnum = fd.getNumber();
                fields.put(fnum, fd.getName());
                Descriptor childDesc = null;
                if (fd.isMapField()) {
                    FieldDescriptor valFd = fd.getMessageType().findFieldByNumber(2);
                    if (valFd != null && isMessageLike(valFd)) {
                        childDesc = valFd.getMessageType();
                    }
                } else if (isMessageLike(fd)) {
                    childDesc = fd.getMessageType();
                }
                if (childDesc != null) {
                    SchemaMeta childMeta = buildSchemaMeta(
                        childDesc, schemaDepth + 2, activeFullNames, cyclicOut
                    );
                    if (childMeta != null && schemaDepth + 3 <= OmniBufDecoder.MAX_CONTAINER_DEPTH) {
                        if (children == null) {
                            children = new LinkedHashMap<>();
                        }
                        children.put(fnum, childMeta);
                    }
                }
            }
            return new SchemaMeta(md.getName(), fields, children);
        } finally {
            activeFullNames.remove(md.getFullName());
        }
    }

    private static SchemaMeta buildValueSchemaMeta(MessageOrBuilder msg, int schemaDepth) {
        if (msg == null || schemaDepth + 1 > OmniBufDecoder.MAX_CONTAINER_DEPTH) {
            return null;
        }
        Descriptor md = msg.getDescriptorForType();
        List<FieldDescriptor> fds = orderedFields(md);
        Map<Integer, String> fields = new LinkedHashMap<>(fds.size());
        Map<Integer, SchemaMeta> children = null;
        for (FieldDescriptor fd : fds) {
            int fnum = fd.getNumber();
            fields.put(fnum, fd.getName());
            if (schemaDepth + 3 > OmniBufDecoder.MAX_CONTAINER_DEPTH) {
                continue;
            }
            SchemaMeta childMeta = null;
            if (fd.isMapField()) {
                FieldDescriptor valFd = fd.getMessageType().findFieldByNumber(2);
                if (valFd != null && isMessageLike(valFd)
                        && msg.getField(fd) instanceof List<?> entries) {
                    for (int i = 0, n = entries.size(); i < n; i++) {
                        if (entries.get(i) instanceof Message entryMsg) {
                            Object v = entryMsg.getField(valFd);
                            if (v instanceof MessageOrBuilder subMsg) {
                                childMeta = SchemaMeta.merge(
                                    childMeta, buildValueSchemaMeta(subMsg, schemaDepth + 2)
                                );
                            }
                        }
                    }
                }
            } else if (isMessageLike(fd)) {
                if (fd.isRepeated()) {
                    if (msg.getField(fd) instanceof List<?> list) {
                        for (int i = 0, n = list.size(); i < n; i++) {
                            if (list.get(i) instanceof Message elem) {
                                childMeta = SchemaMeta.merge(
                                    childMeta, buildValueSchemaMeta(elem, schemaDepth + 2)
                                );
                            }
                        }
                    }
                } else if (msg.hasField(fd)) {
                    Object sub = msg.getField(fd);
                    if (sub instanceof MessageOrBuilder subMsg) {
                        childMeta = buildValueSchemaMeta(subMsg, schemaDepth + 2);
                    }
                }
            }
            if (childMeta != null) {
                if (children == null) {
                    children = new LinkedHashMap<>();
                }
                children.put(fnum, childMeta);
            }
        }
        return new SchemaMeta(md.getName(), fields, children);
    }

    // ========================================================================
    // Encoding
    // ========================================================================

    private static void encodeMessage(
            OmniBufEncoder enc, MessageOrBuilder msg, DescriptorPlan plan, boolean deterministic) {
        if (plan.isExtendable) {
            for (FieldDescriptor populated : msg.getAllFields().keySet()) {
                if (populated.isExtension()) {
                    throw new OmniBufException(
                        "omnibuf: protobuf extensions are not supported: " + populated.getFullName()
                    );
                }
            }
        }
        FieldDescriptor[] fds = plan.orderedFields;
        for (int i = 0, n = fds.length; i < n; i++) {
            FieldDescriptor fd = fds[i];
            if (fd.hasPresence() && !msg.hasField(fd)) {
                continue;
            }
            int fnum = fd.getNumber();
            Object val = msg.getField(fd);

            if (fd.isMapField()) {
                Descriptor entryDesc = fd.getMessageType();
                FieldDescriptor keyFd = entryDesc.findFieldByNumber(1);
                FieldDescriptor valFd = entryDesc.findFieldByNumber(2);
                int keyWT = scalarWireType(keyFd.getType());
                int valWT = isMessageLike(valFd)
                    ? WireType.WIRE_SUB_MSG
                    : scalarWireType(valFd.getType());

                List<?> rawEntries = (List<?>) val;
                Map<Object, Object> deduped = new LinkedHashMap<>(rawEntries.size());
                for (Object rawEntry : rawEntries) {
                    Message entryMsg = (Message) rawEntry;
                    deduped.put(entryMsg.getField(keyFd), entryMsg.getField(valFd));
                }

                int mapPos = enc.beginTypedMapFast(fnum, keyWT, valWT, deduped.size());
                for (Map.Entry<Object, Object> entry :
                        OmniBufEncoder.sortMapEntries(deduped, keyWT, deterministic)) {
                    writeProtoElement(enc, keyFd, entry.getKey(), deterministic);
                    writeProtoElement(enc, valFd, entry.getValue(), deterministic);
                }
                enc.endContainerAt(mapPos);
                continue;
            }

            if (fd.isRepeated()) {
                List<?> list = (List<?>) val;
                int count = list.size();
                if (isMessageLike(fd)) {
                    boolean largeArr = count > 0
                        && list.get(0) instanceof Message firstMsg
                        && firstMsg.getSerializedSize() > 16_383;
                    int arrPos = enc.beginTypedArrayFast(fnum, WireType.WIRE_SUB_MSG, count, largeArr);
                    for (int j = 0; j < count; j++) {
                        Object rawElem = list.get(j);
                        boolean largeElem = rawElem instanceof Message em && em.getSerializedSize() > 16_383;
                        int elemPos = enc.beginInlineSubMsgFast(largeElem);
                        if (rawElem instanceof MessageOrBuilder sub) {
                            encodeMessage(enc, sub, planForNested(sub), deterministic);
                        }
                        enc.endContainerAt(elemPos);
                    }
                    enc.endContainerAt(arrPos);
                } else {
                    int elemWT = scalarWireType(fd.getType());
                    int arrPos = enc.beginTypedArrayFast(fnum, elemWT, count);
                    for (int j = 0; j < count; j++) {
                        writeProtoElement(enc, fd, list.get(j), deterministic);
                    }
                    enc.endContainerAt(arrPos);
                }
                continue;
            }

            // Singular field
            writeSingularField(enc, fd, val, deterministic);
        }
    }

    private static void writeSingularField(
            OmniBufEncoder enc, FieldDescriptor fd, Object val, boolean deterministic) {
        int fnum = fd.getNumber();
        switch (fd.getType()) {
            case UINT32 -> enc.writeUint32(fnum, ((Number) val).intValue());
            case UINT64 -> enc.writeUint(fnum, ((Number) val).longValue());
            case INT32, SINT32 -> enc.writeSint32(fnum, ((Number) val).intValue());
            case INT64, SINT64 -> enc.writeSint(fnum, ((Number) val).longValue());
            case ENUM -> enc.writeSint32(fnum, ((EnumValueDescriptor) val).getNumber());
            case BOOL -> enc.writeBool(fnum, Boolean.TRUE.equals(val));
            case FIXED32 -> enc.writeFixed32(fnum, ((Number) val).intValue());
            case SFIXED32 -> enc.writeSfixed32(fnum, ((Number) val).intValue());
            case FLOAT -> enc.writeFloat32(fnum, ((Number) val).floatValue());
            case FIXED64 -> enc.writeFixed64(fnum, ((Number) val).longValue());
            case SFIXED64 -> enc.writeSfixed64(fnum, ((Number) val).longValue());
            case DOUBLE -> enc.writeFloat64(fnum, ((Number) val).doubleValue());
            case STRING -> enc.writeString(fnum, val == null ? "" : val.toString());
            case BYTES -> enc.writeBytes(fnum, val instanceof ByteString bs ? bs.toByteArray() : EMPTY_BYTES);
            case MESSAGE, GROUP -> {
                if (fd.getType() == FieldDescriptor.Type.GROUP && !isEditionsFile(fd.getFile())) {
                    throw new OmniBufException(
                        "omnibuf: proto2 groups are not supported: " + fd.getFullName()
                    );
                }
                if (val instanceof MessageOrBuilder sub) {
                    boolean largeSub = sub instanceof Message sm && sm.getSerializedSize() > 16_383;
                    int subPos = enc.beginSubMsgFast(fnum, largeSub);
                    encodeMessage(enc, sub, planForNested(sub), deterministic);
                    enc.endContainerAt(subPos);
                }
            }
            default -> throw new OmniBufException("omnibuf: unsupported protobuf type: " + fd.getType());
        }
    }

    private static void writeProtoElement(
            OmniBufEncoder enc, FieldDescriptor fd, Object val, boolean deterministic) {
        switch (fd.getType()) {
            case UINT32 -> enc.writeVarint(Integer.toUnsignedLong(((Number) val).intValue()));
            case UINT64 -> enc.writeVarint(((Number) val).longValue());
            case INT32, SINT32 -> enc.writeVarint(PrefixVarint.encodeZigZag32(((Number) val).intValue()));
            case INT64, SINT64 -> enc.writeVarint(PrefixVarint.encodeZigZag64(((Number) val).longValue()));
            case ENUM -> enc.writeVarint(PrefixVarint.encodeZigZag32(((EnumValueDescriptor) val).getNumber()));
            case BOOL -> enc.writeRawByte(Boolean.TRUE.equals(val) ? 1 : 0);
            case FIXED32, SFIXED32 -> enc.writeInlineFixed32(((Number) val).intValue());
            case FLOAT -> enc.writeInlineFixed32(Float.floatToRawIntBits(((Number) val).floatValue()));
            case FIXED64, SFIXED64 -> enc.writeInlineFixed64(((Number) val).longValue());
            case DOUBLE -> enc.writeInlineFixed64(Double.doubleToRawLongBits(((Number) val).doubleValue()));
            case STRING -> enc.writeStringElement(val == null ? "" : val.toString());
            case BYTES -> enc.writeBytesElement(val instanceof ByteString bs ? bs.toByteArray() : EMPTY_BYTES);
            case MESSAGE, GROUP -> {
                if (fd.getType() == FieldDescriptor.Type.GROUP && !isEditionsFile(fd.getFile())) {
                    throw new OmniBufException(
                        "omnibuf: proto2 groups are not supported: " + fd.getFullName()
                    );
                }
                int elemPos = enc.beginInlineSubMsgFast();
                if (val instanceof MessageOrBuilder sub) {
                    encodeMessage(enc, sub, planForNested(sub), deterministic);
                }
                enc.endContainerAt(elemPos);
            }
            default -> throw new OmniBufException("omnibuf: unsupported protobuf element type: " + fd.getType());
        }
    }

    // ========================================================================
    // Decoding
    // ========================================================================

    private static void decodeIntoBuilder(
            OmniBufDecoder dec, Message.Builder builder, DescriptorPlan plan, int depth) {
        if (depth > dec.maxNestingDepth()) {
            throw new OmniBufException("omnibuf: nesting depth exceeds MaxNestingDepth");
        }
        Descriptor md = plan.descriptor;
        boolean isEditions = plan.isEditions;
        Map<FieldDescriptor, Map<Object, Object>> mapAccumulators = null;

        while (dec.hasRemaining()) {
            long tag = dec.readTag();
            int fnum = (int) (tag >>> 4);
            int wt = (int) (tag & 0x0FL);
            if (fnum == 0) {
                dec.skipOrValidateUnknown(0, wt, depth);
                continue;
            }
            FieldDescriptor fd = md.findFieldByNumber(fnum);
            if (fd == null) {
                dec.skipOrValidateUnknown(fnum, wt, depth);
                continue;
            }
            if (fd.getType() == FieldDescriptor.Type.GROUP && !isEditions) {
                throw new OmniBufException(
                    "omnibuf: proto2 groups are not supported: " + fd.getFullName()
                );
            }

            if (fd.isMapField()) {
                if (wt != WireType.WIRE_MAP) {
                    dec.skipOrValidateUnknown(fnum, wt, depth);
                    continue;
                }
                Descriptor entryDesc = fd.getMessageType();
                FieldDescriptor keyFd = entryDesc.findFieldByNumber(1);
                FieldDescriptor valFd = entryDesc.findFieldByNumber(2);
                int expectedKeyWT = scalarWireType(keyFd.getType());
                int expectedValWT = isMessageLike(valFd)
                    ? WireType.WIRE_SUB_MSG
                    : scalarWireType(valFd.getType());

                long mapState = dec.beginMap(expectedKeyWT, expectedValWT, depth);
                if (mapState < 0L) {
                    continue;
                }
                int count = (int) mapState;
                int mapLimit = (int) (mapState >>> 32);

                if (mapAccumulators == null) {
                    mapAccumulators = new HashMap<>();
                }
                Map<Object, Object> acc = mapAccumulators.get(fd);
                if (acc == null) {
                    acc = new LinkedHashMap<>();
                    List<?> existing = (List<?>) builder.getField(fd);
                    for (Object rawEm : existing) {
                        Message em = (Message) rawEm;
                        acc.put(em.getField(keyFd), em.getField(valFd));
                    }
                    mapAccumulators.put(fd, acc);
                }

                Message.Builder sampleEntryBuilder =
                    isMessageLike(valFd)
                        ? builder.newBuilderForField(fd)
                        : null;
                for (int i = 0; i < count; i++) {
                    Object k = readProtoElement(dec, keyFd, null, depth + 1);
                    Object v = readProtoElement(dec, valFd, sampleEntryBuilder, depth + 1);
                    if (v != null) {
                        if (!acc.containsKey(k) && acc.size() >= dec.maxCollectionElements()) {
                            throw new OmniBufException("omnibuf: accumulated WireMap count exceeds maxCollectionElements");
                        }
                        acc.put(k, v);
                    }
                }
                dec.endContainer(mapLimit);
                continue;
            }

            if (fd.isRepeated()) {
                if (wt != WireType.WIRE_ARRAY) {
                    dec.skipOrValidateUnknown(fnum, wt, depth);
                    continue;
                }
                int expectedElemWT = isMessageLike(fd)
                    ? WireType.WIRE_SUB_MSG
                    : scalarWireType(fd.getType());
                long arrState = dec.beginArray(expectedElemWT, depth);
                if (arrState < 0L) {
                    continue;
                }
                int count = (int) arrState;
                int arrLimit = (int) (arrState >>> 32);
                if ((long) builder.getRepeatedFieldCount(fd) + count > dec.maxCollectionElements()) {
                    throw new OmniBufException("omnibuf: accumulated WireArray count exceeds maxCollectionElements");
                }
                for (int i = 0; i < count; i++) {
                    Object elem = readProtoElement(dec, fd, builder, depth + 1);
                    if (elem != null) {
                        builder.addRepeatedField(fd, elem);
                    }
                }
                dec.endContainer(arrLimit);
                continue;
            }

            // Singular field
            if (fd.getType() == FieldDescriptor.Type.BOOL) {
                if (wt != WireType.WIRE_BOOL_FALSE && wt != WireType.WIRE_BOOL_TRUE) {
                    dec.skipOrValidateUnknown(fnum, wt, depth);
                    continue;
                }
                builder.setField(fd, wt == WireType.WIRE_BOOL_TRUE);
                continue;
            }

            if (isMessageLike(fd)) {
                if (wt != WireType.WIRE_SUB_MSG) {
                    dec.skipOrValidateUnknown(fnum, wt, depth);
                    continue;
                }
                int subLimit = dec.readLengthPrefixedLimit(0);
                Message.Builder subBuilder = builder.hasField(fd)
                    ? ((Message) builder.getField(fd)).toBuilder()
                    : builder.newBuilderForField(fd);
                decodeIntoBuilder(dec, subBuilder, planForNested(subBuilder), depth + 1);
                dec.endContainer(subLimit);
                builder.setField(fd, subBuilder.buildPartial());
                continue;
            }

            int expectedWT = scalarWireType(fd.getType());
            if (wt != expectedWT) {
                dec.skipOrValidateUnknown(fnum, wt, depth);
                continue;
            }
            Object val = readSingularScalar(dec, fd);
            if (val != null) {
                builder.setField(fd, val);
            }
        }

        if (mapAccumulators != null) {
            for (Map.Entry<FieldDescriptor, Map<Object, Object>> mapEntry : mapAccumulators.entrySet()) {
                FieldDescriptor fd = mapEntry.getKey();
                Descriptor entryDesc = fd.getMessageType();
                FieldDescriptor keyFd = entryDesc.findFieldByNumber(1);
                FieldDescriptor valFd = entryDesc.findFieldByNumber(2);
                builder.clearField(fd);
                for (Map.Entry<Object, Object> kv : mapEntry.getValue().entrySet()) {
                    Message.Builder eb = builder.newBuilderForField(fd);
                    eb.setField(keyFd, kv.getKey());
                    eb.setField(valFd, kv.getValue());
                    builder.addRepeatedField(fd, eb.buildPartial());
                }
            }
        }
    }

    private static Object readSingularScalar(OmniBufDecoder dec, FieldDescriptor fd) {
        return switch (fd.getType()) {
            case UINT32 -> dec.readUint32();
            case UINT64 -> dec.readUint64();
            case INT32, SINT32 -> dec.readSint32();
            case INT64, SINT64 -> dec.readSint64();
            case ENUM -> {
                int rawNum = dec.readSint32();
                yield fd.legacyEnumFieldTreatedAsClosed()
                    ? fd.getEnumType().findValueByNumber(rawNum)
                    : fd.getEnumType().findValueByNumberCreatingIfUnknown(rawNum);
            }
            case FIXED32, SFIXED32 -> dec.readFixed32();
            case FLOAT -> dec.readFloat32();
            case FIXED64, SFIXED64 -> dec.readFixed64();
            case DOUBLE -> dec.readFloat64();
            case STRING -> dec.readString();
            case BYTES -> ByteString.copyFrom(dec.readBytes());
            default -> throw new OmniBufException("omnibuf: unexpected protobuf scalar: " + fd.getType());
        };
    }

    private static Object readProtoElement(
            OmniBufDecoder dec,
            FieldDescriptor fd,
            Message.Builder parentBuilder,
            int containerDepth) {
        return switch (fd.getType()) {
            case BOOL -> dec.readContainerBool();
            case MESSAGE, GROUP -> {
                if (fd.getType() == FieldDescriptor.Type.GROUP && !isEditionsFile(fd.getFile())) {
                    throw new OmniBufException(
                        "omnibuf: proto2 groups are not supported: " + fd.getFullName()
                    );
                }
                int elemLimit = dec.readLengthPrefixedLimit(0);
                Message.Builder subBuilder = parentBuilder != null
                    ? parentBuilder.newBuilderForField(fd)
                    : DynamicMessage.newBuilder(fd.getMessageType());
                decodeIntoBuilder(dec, subBuilder, planForNested(subBuilder), containerDepth + 1);
                dec.endContainer(elemLimit);
                yield subBuilder.buildPartial();
            }
            default -> readSingularScalar(dec, fd);
        };
    }

    private static int scalarWireType(FieldDescriptor.Type type) {
        return switch (type) {
            case UINT32, UINT64 -> WireType.WIRE_UINT;
            case INT32, INT64, SINT32, SINT64, ENUM -> WireType.WIRE_SINT;
            case BOOL -> WireType.WIRE_BOOL_FALSE;
            case FIXED32 -> WireType.WIRE_FIXED32;
            case SFIXED32 -> WireType.WIRE_SFIXED32;
            case FLOAT -> WireType.WIRE_FLOAT32;
            case FIXED64 -> WireType.WIRE_FIXED64;
            case SFIXED64 -> WireType.WIRE_SFIXED64;
            case DOUBLE -> WireType.WIRE_FLOAT64;
            case STRING -> WireType.WIRE_STRING;
            case BYTES -> WireType.WIRE_BYTES;
            case GROUP -> throw new OmniBufException("omnibuf: proto2 groups are not supported");
            default -> throw new OmniBufException("omnibuf: unsupported protobuf scalar: " + type);
        };
    }
}
