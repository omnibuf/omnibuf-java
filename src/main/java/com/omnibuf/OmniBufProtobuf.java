package com.omnibuf;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;
import com.omnibuf.wire.CodecPool;
import com.omnibuf.wire.OmniBufDecoder;
import com.omnibuf.wire.OmniBufEncoder;
import com.omnibuf.wire.PrefixVarint;
import com.omnibuf.wire.WireType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Descriptor-driven OmniBuf codec for standard {@code protoc --java_out} {@link Message}
 * types ({@code TIER_PROTOBUF_TYPES}).
 */
public final class OmniBufProtobuf {

    private static final byte[] EMPTY_BYTES = new byte[0];

    private OmniBufProtobuf() {}

    public static byte[] marshal(MessageOrBuilder message) {
        return marshal(message, MarshalOptions.DEFAULT);
    }

    public static byte[] marshal(MessageOrBuilder message, MarshalOptions options) {
        if (message == null) {
            throw new OmniBufException("omnibuf: cannot marshal null protobuf message");
        }
        if (!message.isInitialized()) {
            throw new OmniBufException("omnibuf: protobuf message has uninitialized required fields");
        }
        MarshalOptions opts = options == null ? MarshalOptions.DEFAULT : options;
        OmniBufEncoder enc = CodecPool.acquireEncoder();
        try {
            if (opts.selfDescribing()) {
                boolean[] cyclicOut = new boolean[1];
                SchemaMeta meta = buildSchemaMeta(
                    message.getDescriptorForType(), 1, new HashSet<>(), cyclicOut
                );
                if (cyclicOut[0]) {
                    meta = buildValueSchemaMeta(message, 1);
                }
                if (meta != null) {
                    meta.writeField0(enc, opts.deterministic());
                }
            }
            encodeMessage(enc, message, opts.deterministic());
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
        validateDescriptorSupported(builder.getDescriptorForType());
        builder.clear();
        OmniBufDecoder dec = CodecPool.acquireDecoder(data, 0, data.length, options);
        try {
            decodeIntoBuilder(dec, builder, 0);
        } finally {
            CodecPool.releaseDecoder(dec);
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
        return buildSchemaMeta(descriptor, 1, new HashSet<>(), new boolean[1]);
    }

    private static void validateDescriptorSupported(Descriptor md) {
        for (FieldDescriptor fd : md.getFields()) {
            if (fd.getType() == FieldDescriptor.Type.GROUP) {
                throw new OmniBufException(
                    "omnibuf: proto2 groups are not supported: " + fd.getFullName()
                );
            }
        }
    }

    private static List<FieldDescriptor> orderedFields(Descriptor md) {
        List<FieldDescriptor> fds = md.getFields();
        int n = fds.size();
        boolean ascending = true;
        for (int i = 0; i < n; i++) {
            FieldDescriptor fd = fds.get(i);
            if (fd.getType() == FieldDescriptor.Type.GROUP) {
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
                    if (valFd != null && valFd.getType() == FieldDescriptor.Type.MESSAGE) {
                        childDesc = valFd.getMessageType();
                    }
                } else if (fd.getType() == FieldDescriptor.Type.MESSAGE) {
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

    @SuppressWarnings("unchecked")
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
                if (valFd != null && valFd.getType() == FieldDescriptor.Type.MESSAGE) {
                    List<Message> entries = (List<Message>) msg.getField(fd);
                    for (int i = 0, n = entries.size(); i < n; i++) {
                        Object v = entries.get(i).getField(valFd);
                        if (v instanceof MessageOrBuilder subMsg) {
                            childMeta = SchemaMeta.merge(
                                childMeta, buildValueSchemaMeta(subMsg, schemaDepth + 2)
                            );
                        }
                    }
                }
            } else if (fd.getType() == FieldDescriptor.Type.MESSAGE) {
                if (fd.isRepeated()) {
                    List<Message> list = (List<Message>) msg.getField(fd);
                    for (int i = 0, n = list.size(); i < n; i++) {
                        Message elem = list.get(i);
                        if (elem != null) {
                            childMeta = SchemaMeta.merge(
                                childMeta, buildValueSchemaMeta(elem, schemaDepth + 2)
                            );
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

    @SuppressWarnings("unchecked")
    private static void encodeMessage(
            OmniBufEncoder enc, MessageOrBuilder msg, boolean deterministic) {
        Descriptor md = msg.getDescriptorForType();
        if (md.isExtendable()) {
            for (FieldDescriptor populated : msg.getAllFields().keySet()) {
                if (populated.isExtension()) {
                    throw new OmniBufException(
                        "omnibuf: protobuf extensions are not supported: " + populated.getFullName()
                    );
                }
            }
        }
        List<FieldDescriptor> fds = orderedFields(md);
        for (int i = 0, n = fds.size(); i < n; i++) {
            FieldDescriptor fd = fds.get(i);
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
                int valWT = valFd.getType() == FieldDescriptor.Type.MESSAGE
                    ? WireType.WIRE_SUB_MSG
                    : scalarWireType(valFd.getType());

                List<Message> rawEntries = (List<Message>) val;
                Map<Object, Object> deduped = new LinkedHashMap<>(rawEntries.size());
                for (Message entryMsg : rawEntries) {
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
                List<Object> list = (List<Object>) val;
                int count = list.size();
                if (fd.getType() == FieldDescriptor.Type.MESSAGE) {
                    int arrPos = enc.beginTypedArrayFast(fnum, WireType.WIRE_SUB_MSG, count);
                    for (int j = 0; j < count; j++) {
                        int elemPos = enc.beginInlineSubMsgFast();
                        if (list.get(j) instanceof MessageOrBuilder sub) {
                            encodeMessage(enc, sub, deterministic);
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
            case MESSAGE -> {
                if (val instanceof MessageOrBuilder sub) {
                    int subPos = enc.beginSubMsgFast(fnum);
                    encodeMessage(enc, sub, deterministic);
                    enc.endContainerAt(subPos);
                }
            }
            case GROUP -> throw new OmniBufException(
                "omnibuf: proto2 groups are not supported: " + fd.getFullName()
            );
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
            case MESSAGE -> {
                int elemPos = enc.beginInlineSubMsgFast();
                if (val instanceof MessageOrBuilder sub) {
                    encodeMessage(enc, sub, deterministic);
                }
                enc.endContainerAt(elemPos);
            }
            case GROUP -> throw new OmniBufException(
                "omnibuf: proto2 groups are not supported: " + fd.getFullName()
            );
            default -> throw new OmniBufException("omnibuf: unsupported protobuf element type: " + fd.getType());
        }
    }

    // ========================================================================
    // Decoding
    // ========================================================================

    @SuppressWarnings("unchecked")
    private static void decodeIntoBuilder(
            OmniBufDecoder dec, Message.Builder builder, int depth) {
        if (depth > dec.maxNestingDepth()) {
            throw new OmniBufException("omnibuf: nesting depth exceeds MaxNestingDepth");
        }
        Descriptor md = builder.getDescriptorForType();
        validateDescriptorSupported(md);
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
            if (fd.getType() == FieldDescriptor.Type.GROUP) {
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
                int expectedValWT = valFd.getType() == FieldDescriptor.Type.MESSAGE
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
                    List<Message> existing = (List<Message>) builder.getField(fd);
                    for (Message em : existing) {
                        acc.put(em.getField(keyFd), em.getField(valFd));
                    }
                    mapAccumulators.put(fd, acc);
                }

                Message.Builder sampleEntryBuilder =
                    valFd.getType() == FieldDescriptor.Type.MESSAGE
                        ? builder.newBuilderForField(fd)
                        : null;
                for (int i = 0; i < count; i++) {
                    Object k = readProtoElement(dec, keyFd, null, depth + 1);
                    Object v = readProtoElement(dec, valFd, sampleEntryBuilder, depth + 1);
                    if (!acc.containsKey(k) && acc.size() >= dec.maxCollectionElements()) {
                        throw new OmniBufException("omnibuf: accumulated WireMap count exceeds maxCollectionElements");
                    }
                    acc.put(k, v);
                }
                dec.endContainer(mapLimit);
                continue;
            }

            if (fd.isRepeated()) {
                if (wt != WireType.WIRE_ARRAY) {
                    dec.skipOrValidateUnknown(fnum, wt, depth);
                    continue;
                }
                int expectedElemWT = fd.getType() == FieldDescriptor.Type.MESSAGE
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
                    builder.addRepeatedField(fd, elem);
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

            if (fd.getType() == FieldDescriptor.Type.MESSAGE) {
                if (wt != WireType.WIRE_SUB_MSG) {
                    dec.skipOrValidateUnknown(fnum, wt, depth);
                    continue;
                }
                int subLimit = dec.readLengthPrefixedLimit(0);
                Message.Builder subBuilder = builder.hasField(fd)
                    ? ((Message) builder.getField(fd)).toBuilder()
                    : builder.newBuilderForField(fd);
                decodeIntoBuilder(dec, subBuilder, depth + 1);
                dec.endContainer(subLimit);
                builder.setField(fd, subBuilder.build());
                continue;
            }

            int expectedWT = scalarWireType(fd.getType());
            if (wt != expectedWT) {
                dec.skipOrValidateUnknown(fnum, wt, depth);
                continue;
            }
            Object val = readSingularScalar(dec, fd);
            builder.setField(fd, val);
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
                    builder.addRepeatedField(fd, eb.build());
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
            case ENUM -> fd.getEnumType().findValueByNumberCreatingIfUnknown(dec.readSint32());
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
            case MESSAGE -> {
                int elemLimit = dec.readLengthPrefixedLimit(0);
                Message.Builder subBuilder = parentBuilder != null
                    ? parentBuilder.newBuilderForField(fd)
                    : DynamicMessage.newBuilder(fd.getMessageType());
                decodeIntoBuilder(dec, subBuilder, containerDepth + 1);
                dec.endContainer(elemLimit);
                yield subBuilder.build();
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
