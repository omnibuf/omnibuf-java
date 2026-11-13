package com.omnibuf;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;
import com.omnibuf.wire.OmniBufDecoder;
import com.omnibuf.wire.OmniBufEncoder;
import com.omnibuf.wire.PrefixVarint;
import com.omnibuf.wire.Utf8Validator;
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
        OmniBufEncoder enc = new OmniBufEncoder(256);
        if (opts.selfDescribing()) {
            SchemaMeta meta = buildSchemaMeta(message.getDescriptorForType(), 0, new HashSet<>());
            if (meta != null) {
                meta.writeField0(enc, opts.deterministic());
            }
        }
        encodeMessage(enc, message, opts.deterministic());
        return enc.toByteArray();
    }

    public static void unmarshal(byte[] data, Message.Builder builder) {
        if (data == null) {
            throw new OmniBufException("omnibuf: null packet buffer");
        }
        if (builder == null) {
            throw new OmniBufException("omnibuf: null protobuf Message.Builder");
        }
        builder.clear();
        decodeIntoBuilder(data, 0, data.length, builder, 0);
    }

    @SuppressWarnings("unchecked")
    public static <M extends Message> M unmarshal(byte[] data, M defaultInstance) {
        if (defaultInstance == null) {
            throw new OmniBufException("omnibuf: null protobuf defaultInstance");
        }
        Message.Builder builder = defaultInstance.newBuilderForType();
        unmarshal(data, builder);
        return (M) builder.build();
    }

    public static SchemaMeta schemaMetaFor(Descriptor descriptor) {
        return buildSchemaMeta(descriptor, 0, new HashSet<>());
    }

    private static SchemaMeta buildSchemaMeta(
            Descriptor md, int depth, Set<String> activeFullNames) {
        if (md == null
                || depth > OmniBufDecoder.MAX_CONTAINER_DEPTH
                || !activeFullNames.add(md.getFullName())) {
            return null;
        }
        try {
            List<FieldDescriptor> fds = md.getFields();
            Map<Integer, String> fields = new LinkedHashMap<>(fds.size());
            Map<Integer, SchemaMeta> children = null;
            for (FieldDescriptor fd : fds) {
                int fnum = fd.getNumber();
                fields.put(fnum, fd.getName());
                Descriptor childDesc = null;
                int childDepth = depth + 1;
                if (fd.isMapField()) {
                    FieldDescriptor valFd = fd.getMessageType().findFieldByNumber(2);
                    if (valFd != null && valFd.getType() == FieldDescriptor.Type.MESSAGE) {
                        childDesc = valFd.getMessageType();
                        childDepth = depth + 2;
                    }
                } else if (fd.getType() == FieldDescriptor.Type.MESSAGE) {
                    childDesc = fd.getMessageType();
                    if (fd.isRepeated()) {
                        childDepth = depth + 2;
                    }
                }
                if (childDesc != null) {
                    SchemaMeta childMeta = buildSchemaMeta(childDesc, childDepth, activeFullNames);
                    if (childMeta != null) {
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

    // ========================================================================
    // Encoding
    // ========================================================================

    @SuppressWarnings("unchecked")
    private static void encodeMessage(
            OmniBufEncoder enc, MessageOrBuilder msg, boolean deterministic) {
        Descriptor md = msg.getDescriptorForType();
        for (FieldDescriptor fd : md.getFields()) {
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

                enc.beginTypedMap(fnum, keyWT, valWT, deduped.size());
                Iterable<Map.Entry<Object, Object>> iter;
                if (deterministic && deduped.size() > 1) {
                    List<Map.Entry<Object, Object>> sorted = new ArrayList<>(deduped.entrySet());
                    Comparator<Object> cmp = mapKeyComparator(keyWT);
                    sorted.sort((a, b) -> cmp.compare(a.getKey(), b.getKey()));
                    iter = sorted;
                } else {
                    iter = deduped.entrySet();
                }
                for (Map.Entry<Object, Object> entry : iter) {
                    writeProtoElement(enc, keyFd, entry.getKey(), deterministic);
                    writeProtoElement(enc, valFd, entry.getValue(), deterministic);
                }
                enc.endTypedMap();
                continue;
            }

            if (fd.isRepeated()) {
                List<Object> list = (List<Object>) val;
                if (fd.getType() == FieldDescriptor.Type.MESSAGE) {
                    enc.beginSubMsgArr(fnum);
                    for (Object elem : list) {
                        enc.beginSubMsgElem();
                        if (elem instanceof MessageOrBuilder sub) {
                            encodeMessage(enc, sub, deterministic);
                        }
                        enc.endSubMsgElem();
                    }
                    enc.endSubMsgArr();
                } else {
                    int elemWT = scalarWireType(fd.getType());
                    enc.beginTypedArray(fnum, elemWT, list.size());
                    for (Object elem : list) {
                        writeProtoElement(enc, fd, elem, deterministic);
                    }
                    enc.endTypedArray();
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
                    enc.beginSubMsg(fnum);
                    encodeMessage(enc, sub, deterministic);
                    enc.endSubMsg();
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
            case FIXED32, SFIXED32 -> {
                byte[] tmp = new byte[4];
                PrefixVarint.setIntLE(tmp, 0, ((Number) val).intValue());
                enc.writeRawBytes(tmp, 0, 4);
            }
            case FLOAT -> {
                byte[] tmp = new byte[4];
                PrefixVarint.setIntLE(tmp, 0, Float.floatToRawIntBits(((Number) val).floatValue()));
                enc.writeRawBytes(tmp, 0, 4);
            }
            case FIXED64, SFIXED64 -> {
                byte[] tmp = new byte[8];
                PrefixVarint.setLongLE(tmp, 0, ((Number) val).longValue());
                enc.writeRawBytes(tmp, 0, 8);
            }
            case DOUBLE -> {
                byte[] tmp = new byte[8];
                PrefixVarint.setLongLE(tmp, 0, Double.doubleToRawLongBits(((Number) val).doubleValue()));
                enc.writeRawBytes(tmp, 0, 8);
            }
            case STRING -> enc.writeStringElement(val == null ? "" : val.toString());
            case BYTES -> enc.writeBytesElement(val instanceof ByteString bs ? bs.toByteArray() : EMPTY_BYTES);
            case MESSAGE -> {
                enc.beginInlineSubMsg();
                if (val instanceof MessageOrBuilder sub) {
                    encodeMessage(enc, sub, deterministic);
                }
                enc.endInlineSubMsg();
            }
            default -> throw new OmniBufException("omnibuf: unsupported protobuf element type: " + fd.getType());
        }
    }

    // ========================================================================
    // Decoding
    // ========================================================================

    @SuppressWarnings("unchecked")
    private static void decodeIntoBuilder(
            byte[] data, int offset, int length, Message.Builder builder, int depth) {
        if (depth > OmniBufDecoder.MAX_CONTAINER_DEPTH) {
            throw new OmniBufException("omnibuf: nesting depth exceeds MaxNestingDepth");
        }
        Descriptor md = builder.getDescriptorForType();
        Map<FieldDescriptor, Map<Object, Object>> mapAccumulators = null;

        OmniBufDecoder dec = new OmniBufDecoder(data, offset, length);
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

            if (fd.isMapField()) {
                if (wt != WireType.WIRE_MAP) {
                    dec.skipOrValidateUnknown(fnum, wt, depth);
                    continue;
                }
                if (depth + 1 > OmniBufDecoder.MAX_CONTAINER_DEPTH) {
                    throw new OmniBufException("omnibuf: WireMap exceeds MaxNestingDepth");
                }
                int mapValuePos = dec.position();
                OmniBufDecoder.Slice body = dec.readLengthPrefixedSlice(3);
                OmniBufDecoder.MapHeader hdr =
                    OmniBufDecoder.parseMapHeader(data, body.offset(), body.end());

                Descriptor entryDesc = fd.getMessageType();
                FieldDescriptor keyFd = entryDesc.findFieldByNumber(1);
                FieldDescriptor valFd = entryDesc.findFieldByNumber(2);
                int expectedKeyWT = scalarWireType(keyFd.getType());
                int expectedValWT = valFd.getType() == FieldDescriptor.Type.MESSAGE
                    ? WireType.WIRE_SUB_MSG
                    : scalarWireType(valFd.getType());

                if (hdr.keyWireType() != expectedKeyWT || hdr.valWireType() != expectedValWT) {
                    OmniBufDecoder.validateValue(data, mapValuePos, dec.limit(), WireType.WIRE_MAP, depth);
                    continue;
                }

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

                OmniBufDecoder entryDec = new OmniBufDecoder(
                    data, hdr.payloadStart(), hdr.payloadEnd() - hdr.payloadStart()
                );
                Message.Builder sampleEntryBuilder =
                    valFd.getType() == FieldDescriptor.Type.MESSAGE
                        ? builder.newBuilderForField(fd)
                        : null;
                for (int i = 0; i < hdr.count(); i++) {
                    Object k = readProtoElement(entryDec, keyFd, null, depth + 1);
                    Object v = readProtoElement(entryDec, valFd, sampleEntryBuilder, depth + 1);
                    acc.put(k, v);
                }
                if (entryDec.hasRemaining()) {
                    throw new OmniBufException("omnibuf: WireMap has trailing payload bytes");
                }
                continue;
            }

            if (fd.isRepeated()) {
                if (wt != WireType.WIRE_ARRAY) {
                    dec.skipOrValidateUnknown(fnum, wt, depth);
                    continue;
                }
                if (depth + 1 > OmniBufDecoder.MAX_CONTAINER_DEPTH) {
                    throw new OmniBufException("omnibuf: WireArray exceeds MaxNestingDepth");
                }
                int arrayValuePos = dec.position();
                OmniBufDecoder.Slice body = dec.readLengthPrefixedSlice(2);
                OmniBufDecoder.ArrayHeader hdr =
                    OmniBufDecoder.parseArrayHeader(data, body.offset(), body.end());
                int expectedElemWT = fd.getType() == FieldDescriptor.Type.MESSAGE
                    ? WireType.WIRE_SUB_MSG
                    : scalarWireType(fd.getType());
                if (hdr.elemWireType() != expectedElemWT) {
                    OmniBufDecoder.validateValue(data, arrayValuePos, dec.limit(), WireType.WIRE_ARRAY, depth);
                    continue;
                }
                OmniBufDecoder elemDec = new OmniBufDecoder(
                    data, hdr.payloadStart(), hdr.payloadEnd() - hdr.payloadStart()
                );
                for (int i = 0; i < hdr.count(); i++) {
                    Object elem = readProtoElement(elemDec, fd, builder, depth + 1);
                    builder.addRepeatedField(fd, elem);
                }
                if (elemDec.hasRemaining()) {
                    throw new OmniBufException("omnibuf: WireArray has trailing payload bytes");
                }
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
                OmniBufDecoder.Slice s = dec.readLengthPrefixedSlice(0);
                Message.Builder subBuilder = builder.hasField(fd)
                    ? ((Message) builder.getField(fd)).toBuilder()
                    : builder.newBuilderForField(fd);
                decodeIntoBuilder(data, s.offset(), s.length(), subBuilder, depth + 1);
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
                OmniBufDecoder.Slice s = dec.readLengthPrefixedSlice(0);
                Message.Builder subBuilder = parentBuilder != null
                    ? parentBuilder.newBuilderForField(fd)
                    : com.google.protobuf.DynamicMessage.newBuilder(fd.getMessageType());
                decodeIntoBuilder(dec.buffer(), s.offset(), s.length(), subBuilder, containerDepth + 1);
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
            default -> throw new OmniBufException("omnibuf: unsupported protobuf scalar: " + type);
        };
    }

    private static Comparator<Object> mapKeyComparator(int keyWT) {
        return switch (keyWT) {
            case WireType.WIRE_STRING ->
                (a, b) -> Utf8Validator.compareUtf8(a.toString(), b.toString());
            case WireType.WIRE_BOOL_FALSE ->
                (a, b) -> Boolean.compare(Boolean.TRUE.equals(a), Boolean.TRUE.equals(b));
            case WireType.WIRE_UINT, WireType.WIRE_FIXED64 ->
                (a, b) -> Long.compareUnsigned(((Number) a).longValue(), ((Number) b).longValue());
            case WireType.WIRE_FIXED32 ->
                (a, b) -> Integer.compareUnsigned(((Number) a).intValue(), ((Number) b).intValue());
            case WireType.WIRE_SINT, WireType.WIRE_SFIXED64 ->
                (a, b) -> Long.compare(((Number) a).longValue(), ((Number) b).longValue());
            case WireType.WIRE_SFIXED32 ->
                (a, b) -> Integer.compare(((Number) a).intValue(), ((Number) b).intValue());
            default -> (a, b) -> 0;
        };
    }
}
