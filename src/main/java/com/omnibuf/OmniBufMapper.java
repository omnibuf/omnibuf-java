package com.omnibuf;

import com.omnibuf.annotation.OmniBufField;
import com.omnibuf.annotation.OmniBufMessage;
import com.omnibuf.annotation.WireKind;
import com.omnibuf.wire.OmniBufDecoder;
import com.omnibuf.wire.OmniBufEncoder;
import com.omnibuf.wire.PrefixVarint;
import com.omnibuf.wire.Utf8Validator;
import com.omnibuf.wire.WireType;
import java.io.ByteArrayOutputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-performance reflection/VarHandle codec for Java classes and Java 21 {@code record} types
 * annotated with {@link OmniBufMessage} and {@link OmniBufField} ({@code TIER_ANNOTATED_STRUCT}).
 */
public final class OmniBufMapper {

    private static final int MAX_DIRECT_FIELD_SLOT = 32;
    private static final byte[] EMPTY_BYTES = new byte[0];
    private static final int[] EMPTY_INTS = new int[0];
    private static final long[] EMPTY_LONGS = new long[0];

    private static final ConcurrentHashMap<Class<?>, ClassCodec<?>> CODEC_CACHE =
        new ConcurrentHashMap<>();

    private static final ThreadLocal<OmniBufEncoder> ENCODER_POOL =
        ThreadLocal.withInitial(() -> new OmniBufEncoder(512));

    private OmniBufMapper() {}

    public static byte[] marshal(Object message) {
        return marshal(message, MarshalOptions.DEFAULT);
    }

    @SuppressWarnings("unchecked")
    public static <T> byte[] marshal(T message, MarshalOptions options) {
        if (message == null) {
            throw new OmniBufException("omnibuf: cannot marshal null message");
        }
        MarshalOptions opts = options == null ? MarshalOptions.DEFAULT : options;
        ClassCodec<T> codec = (ClassCodec<T>) getCodec(message.getClass());
        OmniBufEncoder enc = ENCODER_POOL.get();
        enc.reset();
        if (opts.selfDescribing()) {
            enc.writeRawBytes(codec.getField0Bytes());
        }
        codec.encode(enc, message, opts.deterministic());
        return enc.toByteArray();
    }

    public static <T> T unmarshal(byte[] data, Class<T> type) {
        if (data == null) {
            throw new OmniBufException("omnibuf: null packet buffer");
        }
        if (type == null) {
            throw new OmniBufException("omnibuf: null target class");
        }
        ClassCodec<T> codec = getCodec(type);
        return codec.decodeNew(data, 0, data.length, 0);
    }

    @SuppressWarnings("unchecked")
    public static <T> void unmarshal(byte[] data, T target) {
        if (data == null) {
            throw new OmniBufException("omnibuf: null packet buffer");
        }
        if (target == null) {
            throw new OmniBufException("omnibuf: null target instance");
        }
        if (target.getClass().isRecord()) {
            throw new OmniBufException(
                "omnibuf: Java records are immutable; use OmniBufMapper.unmarshal(data, RecordClass.class)"
            );
        }
        ClassCodec<T> codec = (ClassCodec<T>) getCodec(target.getClass());
        codec.resetInstance(target);
        codec.decodeInto(data, 0, data.length, target, 0);
    }

    public static SchemaMeta schemaMetaFor(Class<?> type) {
        return getCodec(type).getSchemaMeta();
    }

    @SuppressWarnings("unchecked")
    private static <T> ClassCodec<T> getCodec(Class<T> type) {
        ClassCodec<?> existing = CODEC_CACHE.get(type);
        if (existing != null) {
            return (ClassCodec<T>) existing;
        }
        Map<Class<?>, ClassCodec<?>> building = new HashMap<>();
        ClassCodec<T> built = compileCodec(type, building);
        for (Map.Entry<Class<?>, ClassCodec<?>> entry : building.entrySet()) {
            CODEC_CACHE.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return (ClassCodec<T>) CODEC_CACHE.getOrDefault(type, built);
    }

    private enum FieldCategory {
        INT_SCALAR,
        LONG_SCALAR,
        BOOL_SCALAR,
        FLOAT_SCALAR,
        DOUBLE_SCALAR,
        STRING_SCALAR,
        BYTES_SCALAR,
        INT_ARRAY,
        LONG_ARRAY,
        LIST,
        MAP,
        SUB_MSG
    }

    private static final class FieldDescriptorSlot {
        final int index;
        final int fieldNumber;
        final String schemaName;
        final FieldCategory category;
        final Class<?> rawType;
        final int wireType;
        final int elemWireType;
        final int mapKeyWireType;
        final int mapValWireType;
        final Class<?> elemClass;
        ClassCodec<?> subCodec;
        final VarHandle varHandle;
        final MethodHandle getter;

        FieldDescriptorSlot(
                int index,
                int fieldNumber,
                String schemaName,
                FieldCategory category,
                Class<?> rawType,
                int wireType,
                int elemWireType,
                int mapKeyWireType,
                int mapValWireType,
                Class<?> elemClass,
                VarHandle varHandle,
                MethodHandle getter) {
            this.index = index;
            this.fieldNumber = fieldNumber;
            this.schemaName = schemaName;
            this.category = category;
            this.rawType = rawType;
            this.wireType = wireType;
            this.elemWireType = elemWireType;
            this.mapKeyWireType = mapKeyWireType;
            this.mapValWireType = mapValWireType;
            this.elemClass = elemClass;
            this.varHandle = varHandle;
            this.getter = getter;
        }

        Object readObject(Object instance) {
            try {
                return varHandle != null ? varHandle.get(instance) : getter.invoke(instance);
            } catch (Throwable t) {
                throw new OmniBufException("omnibuf: failed reading field " + schemaName, t);
            }
        }

        int readInt(Object instance) {
            if (varHandle != null && rawType == int.class) {
                return (int) varHandle.get(instance);
            }
            Object v = readObject(instance);
            return v == null ? 0 : ((Number) v).intValue();
        }

        long readLong(Object instance) {
            if (varHandle != null && rawType == long.class) {
                return (long) varHandle.get(instance);
            }
            Object v = readObject(instance);
            return v == null ? 0L : ((Number) v).longValue();
        }

        boolean readBoolean(Object instance) {
            if (varHandle != null && rawType == boolean.class) {
                return (boolean) varHandle.get(instance);
            }
            Object v = readObject(instance);
            return Boolean.TRUE.equals(v);
        }

        float readFloat(Object instance) {
            if (varHandle != null && rawType == float.class) {
                return (float) varHandle.get(instance);
            }
            Object v = readObject(instance);
            return v == null ? 0.0f : ((Number) v).floatValue();
        }

        double readDouble(Object instance) {
            if (varHandle != null && rawType == double.class) {
                return (double) varHandle.get(instance);
            }
            Object v = readObject(instance);
            return v == null ? 0.0d : ((Number) v).doubleValue();
        }

        void writeInt(Object target, int value) {
            if (rawType == int.class) {
                varHandle.set(target, value);
            } else {
                varHandle.set(target, Integer.valueOf(value));
            }
        }

        void writeLong(Object target, long value) {
            if (rawType == long.class) {
                varHandle.set(target, value);
            } else {
                varHandle.set(target, Long.valueOf(value));
            }
        }

        void writeBoolean(Object target, boolean value) {
            if (rawType == boolean.class) {
                varHandle.set(target, value);
            } else {
                varHandle.set(target, Boolean.valueOf(value));
            }
        }

        void writeFloat(Object target, float value) {
            if (rawType == float.class) {
                varHandle.set(target, value);
            } else {
                varHandle.set(target, Float.valueOf(value));
            }
        }

        void writeDouble(Object target, double value) {
            if (rawType == double.class) {
                varHandle.set(target, value);
            } else {
                varHandle.set(target, Double.valueOf(value));
            }
        }

        void writeObject(Object target, Object value) {
            varHandle.set(target, value);
        }

        Object defaultComponentValue() {
            return switch (category) {
                case INT_SCALAR -> 0;
                case LONG_SCALAR -> 0L;
                case BOOL_SCALAR -> Boolean.FALSE;
                case FLOAT_SCALAR -> 0.0f;
                case DOUBLE_SCALAR -> 0.0d;
                case STRING_SCALAR -> "";
                case BYTES_SCALAR -> EMPTY_BYTES;
                case INT_ARRAY -> EMPTY_INTS;
                case LONG_ARRAY -> EMPTY_LONGS;
                case LIST -> new ArrayList<>();
                case MAP -> new LinkedHashMap<>();
                case SUB_MSG -> null;
            };
        }

        boolean matchesWireType(int wt) {
            if (category == FieldCategory.BOOL_SCALAR) {
                return wt == WireType.WIRE_BOOL_FALSE || wt == WireType.WIRE_BOOL_TRUE;
            }
            return wt == wireType;
        }
    }

    private static final class ClassCodec<T> {
        final Class<T> type;
        final String typeName;
        final boolean isRecord;
        MethodHandle constructorHandle;
        FieldDescriptorSlot[] orderedFields;
        FieldDescriptorSlot[] directByNum;
        Map<Integer, FieldDescriptorSlot> overflowByNum;
        volatile SchemaMeta cachedMeta;
        volatile byte[] cachedField0;

        ClassCodec(Class<T> type, String typeName, boolean isRecord) {
            this.type = type;
            this.typeName = typeName;
            this.isRecord = isRecord;
        }

        SchemaMeta getSchemaMeta() {
            SchemaMeta m = cachedMeta;
            if (m == null) {
                synchronized (this) {
                    m = cachedMeta;
                    if (m == null) {
                        m = buildSchemaMeta(0, new HashSet<>());
                        cachedMeta = m;
                    }
                }
            }
            return m;
        }

        byte[] getField0Bytes() {
            byte[] b = cachedField0;
            if (b == null) {
                synchronized (this) {
                    b = cachedField0;
                    if (b == null) {
                        b = getSchemaMeta().encodeField0(true);
                        cachedField0 = b;
                    }
                }
            }
            return b;
        }

        private SchemaMeta buildSchemaMeta(int depth, Set<Class<?>> active) {
            if (depth > OmniBufDecoder.MAX_CONTAINER_DEPTH || !active.add(type)) {
                return null;
            }
            try {
                Map<Integer, String> fieldNames = new LinkedHashMap<>(orderedFields.length);
                Map<Integer, SchemaMeta> childMetas = null;
                for (FieldDescriptorSlot slot : orderedFields) {
                    fieldNames.put(slot.fieldNumber, slot.schemaName);
                    if (slot.subCodec != null) {
                        int nextDepth = (slot.wireType == WireType.WIRE_ARRAY || slot.wireType == WireType.WIRE_MAP)
                            ? depth + 2
                            : depth + 1;
                        SchemaMeta child = slot.subCodec.buildSchemaMeta(nextDepth, active);
                        if (child != null) {
                            if (childMetas == null) {
                                childMetas = new LinkedHashMap<>();
                            }
                            childMetas.put(slot.fieldNumber, child);
                        }
                    }
                }
                return new SchemaMeta(typeName, fieldNames, childMetas);
            } finally {
                active.remove(type);
            }
        }

        FieldDescriptorSlot lookupSlot(int fieldNum) {
            if (fieldNum > 0 && fieldNum <= MAX_DIRECT_FIELD_SLOT) {
                return directByNum[fieldNum];
            }
            return overflowByNum != null ? overflowByNum.get(fieldNum) : null;
        }

        @SuppressWarnings("unchecked")
        T newBlankMutableInstance() {
            try {
                T inst = (T) constructorHandle.invoke();
                resetInstance(inst);
                return inst;
            } catch (Throwable t) {
                throw new OmniBufException("omnibuf: failed instantiating " + type.getName(), t);
            }
        }

        void resetInstance(T target) {
            for (FieldDescriptorSlot slot : orderedFields) {
                switch (slot.category) {
                    case INT_SCALAR -> slot.writeInt(target, 0);
                    case LONG_SCALAR -> slot.writeLong(target, 0L);
                    case BOOL_SCALAR -> slot.writeBoolean(target, false);
                    case FLOAT_SCALAR -> slot.writeFloat(target, 0.0f);
                    case DOUBLE_SCALAR -> slot.writeDouble(target, 0.0d);
                    case STRING_SCALAR -> slot.writeObject(target, "");
                    case BYTES_SCALAR -> slot.writeObject(target, EMPTY_BYTES);
                    case INT_ARRAY -> slot.writeObject(target, EMPTY_INTS);
                    case LONG_ARRAY -> slot.writeObject(target, EMPTY_LONGS);
                    case LIST -> slot.writeObject(target, new ArrayList<>());
                    case MAP -> slot.writeObject(target, new LinkedHashMap<>());
                    case SUB_MSG -> slot.writeObject(target, null);
                }
            }
        }

        void encode(OmniBufEncoder enc, Object instance, boolean deterministic) {
            for (FieldDescriptorSlot slot : orderedFields) {
                encodeField(enc, instance, slot, deterministic);
            }
        }

        @SuppressWarnings("unchecked")
        private void encodeField(
                OmniBufEncoder enc, Object instance, FieldDescriptorSlot slot, boolean deterministic) {
            int fnum = slot.fieldNumber;
            switch (slot.category) {
                case INT_SCALAR -> {
                    int v = slot.readInt(instance);
                    switch (slot.wireType) {
                        case WireType.WIRE_UINT -> enc.writeUint32(fnum, v);
                        case WireType.WIRE_SINT -> enc.writeSint32(fnum, v);
                        case WireType.WIRE_FIXED32 -> enc.writeFixed32(fnum, v);
                        case WireType.WIRE_SFIXED32 -> enc.writeSfixed32(fnum, v);
                        default -> throw new OmniBufException("omnibuf: unsupported int wire type");
                    }
                }
                case LONG_SCALAR -> {
                    long v = slot.readLong(instance);
                    switch (slot.wireType) {
                        case WireType.WIRE_UINT -> enc.writeUint(fnum, v);
                        case WireType.WIRE_SINT -> enc.writeSint(fnum, v);
                        case WireType.WIRE_FIXED64 -> enc.writeFixed64(fnum, v);
                        case WireType.WIRE_SFIXED64 -> enc.writeSfixed64(fnum, v);
                        default -> throw new OmniBufException("omnibuf: unsupported long wire type");
                    }
                }
                case BOOL_SCALAR -> enc.writeBool(fnum, slot.readBoolean(instance));
                case FLOAT_SCALAR -> enc.writeFloat32(fnum, slot.readFloat(instance));
                case DOUBLE_SCALAR -> enc.writeFloat64(fnum, slot.readDouble(instance));
                case STRING_SCALAR -> {
                    String s = (String) slot.readObject(instance);
                    enc.writeString(fnum, s == null ? "" : s);
                }
                case BYTES_SCALAR -> {
                    byte[] b = (byte[]) slot.readObject(instance);
                    enc.writeBytes(fnum, b == null ? EMPTY_BYTES : b);
                }
                case INT_ARRAY -> {
                    int[] arr = (int[]) slot.readObject(instance);
                    int n = arr == null ? 0 : arr.length;
                    enc.beginTypedArray(fnum, slot.elemWireType, n);
                    for (int i = 0; i < n; i++) {
                        writeIntArrayElem(enc, slot.elemWireType, arr[i]);
                    }
                    enc.endTypedArray();
                }
                case LONG_ARRAY -> {
                    long[] arr = (long[]) slot.readObject(instance);
                    int n = arr == null ? 0 : arr.length;
                    enc.beginTypedArray(fnum, slot.elemWireType, n);
                    for (int i = 0; i < n; i++) {
                        writeLongArrayElem(enc, slot.elemWireType, arr[i]);
                    }
                    enc.endTypedArray();
                }
                case SUB_MSG -> {
                    Object sub = slot.readObject(instance);
                    if (sub != null) {
                        enc.beginSubMsg(fnum);
                        slot.subCodec.encode(enc, sub, deterministic);
                        enc.endSubMsg();
                    }
                }
                case LIST -> {
                    List<Object> list = (List<Object>) slot.readObject(instance);
                    if (slot.elemWireType == WireType.WIRE_SUB_MSG) {
                        int n = list == null ? 0 : list.size();
                        enc.beginSubMsgArr(fnum);
                        for (int i = 0; i < n; i++) {
                            enc.beginSubMsgElem();
                            Object elem = list.get(i);
                            if (elem != null) {
                                slot.subCodec.encode(enc, elem, deterministic);
                            }
                            enc.endSubMsgElem();
                        }
                        enc.endSubMsgArr();
                    } else {
                        encodeScalarList(enc, fnum, slot.elemWireType, list);
                    }
                }
                case MAP -> {
                    Map<Object, Object> map = (Map<Object, Object>) slot.readObject(instance);
                    encodeMapField(enc, slot, map, deterministic);
                }
            }
        }

        T decodeNew(byte[] data, int offset, int length, int depth) {
            if (isRecord) {
                return decodeRecord(data, offset, length, depth);
            }
            T inst = newBlankMutableInstance();
            decodeInto(data, offset, length, inst, depth);
            return inst;
        }

        @SuppressWarnings("unchecked")
        private T decodeRecord(byte[] data, int offset, int length, int depth) {
            if (depth > OmniBufDecoder.MAX_CONTAINER_DEPTH) {
                throw new OmniBufException("omnibuf: nesting depth exceeds MaxNestingDepth");
            }
            Object[] args = new Object[orderedFields.length];
            for (int i = 0; i < orderedFields.length; i++) {
                args[i] = orderedFields[i].defaultComponentValue();
            }
            Map<Integer, ByteArrayOutputStream> recordSubStreams = null;
            Map<Integer, OmniBufDecoder.Slice> recordSubSingle = null;
            Map<Integer, List<Number>> primArrayAccum = null;

            OmniBufDecoder dec = new OmniBufDecoder(data, offset, length);
            while (dec.hasRemaining()) {
                long tag = dec.readTag();
                int fnum = (int) (tag >>> 4);
                int wt = (int) (tag & 0x0FL);
                if (fnum == 0) {
                    dec.skipOrValidateUnknown(0, wt, depth);
                    continue;
                }
                FieldDescriptorSlot slot = lookupSlot(fnum);
                if (slot == null || !slot.matchesWireType(wt)) {
                    dec.skipOrValidateUnknown(fnum, wt, depth);
                    continue;
                }
                int idx = slot.index;
                switch (slot.category) {
                    case INT_SCALAR -> args[idx] = decodeIntScalar(dec, wt);
                    case LONG_SCALAR -> args[idx] = decodeLongScalar(dec, wt);
                    case BOOL_SCALAR -> args[idx] = (wt == WireType.WIRE_BOOL_TRUE);
                    case FLOAT_SCALAR -> args[idx] = dec.readFloat32();
                    case DOUBLE_SCALAR -> args[idx] = dec.readFloat64();
                    case STRING_SCALAR -> args[idx] = dec.readString();
                    case BYTES_SCALAR -> args[idx] = dec.readBytes();
                    case INT_ARRAY, LONG_ARRAY -> {
                        if (primArrayAccum == null) {
                            primArrayAccum = new HashMap<>();
                        }
                        List<Number> acc = primArrayAccum.computeIfAbsent(idx, k -> new ArrayList<>());
                        decodePrimitiveArrayIntoList(dec, slot, acc, depth);
                    }
                    case LIST -> {
                        List<Object> list = (List<Object>) args[idx];
                        decodeListInto(dec, slot, list, depth);
                    }
                    case MAP -> {
                        Map<Object, Object> map = (Map<Object, Object>) args[idx];
                        decodeMapInto(dec, slot, map, depth);
                    }
                    case SUB_MSG -> {
                        OmniBufDecoder.Slice s = dec.readLengthPrefixedSlice(0);
                        if (recordSubSingle == null) {
                            recordSubSingle = new HashMap<>();
                        }
                        if (recordSubStreams != null && recordSubStreams.containsKey(idx)) {
                            recordSubStreams.get(idx).write(data, s.offset(), s.length());
                        } else if (recordSubSingle.containsKey(idx)) {
                            OmniBufDecoder.Slice first = recordSubSingle.remove(idx);
                            if (recordSubStreams == null) {
                                recordSubStreams = new HashMap<>();
                            }
                            ByteArrayOutputStream baos =
                                new ByteArrayOutputStream(first.length() + s.length() + 32);
                            baos.write(data, first.offset(), first.length());
                            baos.write(data, s.offset(), s.length());
                            recordSubStreams.put(idx, baos);
                        } else {
                            recordSubSingle.put(idx, s);
                        }
                    }
                }
            }

            if (primArrayAccum != null) {
                for (Map.Entry<Integer, List<Number>> entry : primArrayAccum.entrySet()) {
                    int idx = entry.getKey();
                    List<Number> nums = entry.getValue();
                    if (orderedFields[idx].category == FieldCategory.INT_ARRAY) {
                        int[] arr = new int[nums.size()];
                        for (int i = 0; i < arr.length; i++) {
                            arr[i] = nums.get(i).intValue();
                        }
                        args[idx] = arr;
                    } else {
                        long[] arr = new long[nums.size()];
                        for (int i = 0; i < arr.length; i++) {
                            arr[i] = nums.get(i).longValue();
                        }
                        args[idx] = arr;
                    }
                }
            }
            if (recordSubSingle != null) {
                for (Map.Entry<Integer, OmniBufDecoder.Slice> entry : recordSubSingle.entrySet()) {
                    int idx = entry.getKey();
                    OmniBufDecoder.Slice s = entry.getValue();
                    args[idx] = orderedFields[idx].subCodec.decodeNew(data, s.offset(), s.length(), depth + 1);
                }
            }
            if (recordSubStreams != null) {
                for (Map.Entry<Integer, ByteArrayOutputStream> entry : recordSubStreams.entrySet()) {
                    int idx = entry.getKey();
                    byte[] merged = entry.getValue().toByteArray();
                    args[idx] = orderedFields[idx].subCodec.decodeNew(merged, 0, merged.length, depth + 1);
                }
            }

            try {
                return (T) constructorHandle.invokeWithArguments(args);
            } catch (OmniBufException e) {
                throw e;
            } catch (Throwable t) {
                throw new OmniBufException("omnibuf: failed constructing record " + type.getName(), t);
            }
        }

        @SuppressWarnings("unchecked")
        void decodeInto(byte[] data, int offset, int length, Object target, int depth) {
            if (depth > OmniBufDecoder.MAX_CONTAINER_DEPTH) {
                throw new OmniBufException("omnibuf: nesting depth exceeds MaxNestingDepth");
            }
            Map<Integer, ByteArrayOutputStream> recordSubStreams = null;
            Map<Integer, OmniBufDecoder.Slice> recordSubSingle = null;

            OmniBufDecoder dec = new OmniBufDecoder(data, offset, length);
            while (dec.hasRemaining()) {
                long tag = dec.readTag();
                int fnum = (int) (tag >>> 4);
                int wt = (int) (tag & 0x0FL);
                if (fnum == 0) {
                    dec.skipOrValidateUnknown(0, wt, depth);
                    continue;
                }
                FieldDescriptorSlot slot = lookupSlot(fnum);
                if (slot == null || !slot.matchesWireType(wt)) {
                    dec.skipOrValidateUnknown(fnum, wt, depth);
                    continue;
                }
                switch (slot.category) {
                    case INT_SCALAR -> slot.writeInt(target, decodeIntScalar(dec, wt));
                    case LONG_SCALAR -> slot.writeLong(target, decodeLongScalar(dec, wt));
                    case BOOL_SCALAR -> slot.writeBoolean(target, wt == WireType.WIRE_BOOL_TRUE);
                    case FLOAT_SCALAR -> slot.writeFloat(target, dec.readFloat32());
                    case DOUBLE_SCALAR -> slot.writeDouble(target, dec.readFloat64());
                    case STRING_SCALAR -> slot.writeObject(target, dec.readString());
                    case BYTES_SCALAR -> slot.writeObject(target, dec.readBytes());
                    case INT_ARRAY -> {
                        int[] prev = (int[]) slot.readObject(target);
                        List<Number> acc = new ArrayList<>(prev == null ? 4 : prev.length + 4);
                        if (prev != null) {
                            for (int v : prev) {
                                acc.add(v);
                            }
                        }
                        decodePrimitiveArrayIntoList(dec, slot, acc, depth);
                        int[] next = new int[acc.size()];
                        for (int i = 0; i < next.length; i++) {
                            next[i] = acc.get(i).intValue();
                        }
                        slot.writeObject(target, next);
                    }
                    case LONG_ARRAY -> {
                        long[] prev = (long[]) slot.readObject(target);
                        List<Number> acc = new ArrayList<>(prev == null ? 4 : prev.length + 4);
                        if (prev != null) {
                            for (long v : prev) {
                                acc.add(v);
                            }
                        }
                        decodePrimitiveArrayIntoList(dec, slot, acc, depth);
                        long[] next = new long[acc.size()];
                        for (int i = 0; i < next.length; i++) {
                            next[i] = acc.get(i).longValue();
                        }
                        slot.writeObject(target, next);
                    }
                    case LIST -> {
                        List<Object> list = (List<Object>) slot.readObject(target);
                        if (list == null) {
                            list = new ArrayList<>();
                            slot.writeObject(target, list);
                        }
                        decodeListInto(dec, slot, list, depth);
                    }
                    case MAP -> {
                        Map<Object, Object> map = (Map<Object, Object>) slot.readObject(target);
                        if (map == null) {
                            map = new LinkedHashMap<>();
                            slot.writeObject(target, map);
                        }
                        decodeMapInto(dec, slot, map, depth);
                    }
                    case SUB_MSG -> {
                        OmniBufDecoder.Slice s = dec.readLengthPrefixedSlice(0);
                        if (slot.subCodec.isRecord) {
                            int idx = slot.index;
                            if (recordSubSingle == null) {
                                recordSubSingle = new HashMap<>();
                            }
                            if (recordSubStreams != null && recordSubStreams.containsKey(idx)) {
                                recordSubStreams.get(idx).write(data, s.offset(), s.length());
                            } else if (recordSubSingle.containsKey(idx)) {
                                OmniBufDecoder.Slice first = recordSubSingle.remove(idx);
                                if (recordSubStreams == null) {
                                    recordSubStreams = new HashMap<>();
                                }
                                ByteArrayOutputStream baos =
                                    new ByteArrayOutputStream(first.length() + s.length() + 32);
                                baos.write(data, first.offset(), first.length());
                                baos.write(data, s.offset(), s.length());
                                recordSubStreams.put(idx, baos);
                            } else {
                                recordSubSingle.put(idx, s);
                            }
                        } else {
                            Object sub = slot.readObject(target);
                            if (sub == null) {
                                sub = slot.subCodec.newBlankMutableInstance();
                                slot.writeObject(target, sub);
                            }
                            slot.subCodec.decodeInto(data, s.offset(), s.length(), sub, depth + 1);
                        }
                    }
                }
            }

            if (recordSubSingle != null) {
                for (Map.Entry<Integer, OmniBufDecoder.Slice> entry : recordSubSingle.entrySet()) {
                    FieldDescriptorSlot slot = orderedFields[entry.getKey()];
                    OmniBufDecoder.Slice s = entry.getValue();
                    slot.writeObject(target, slot.subCodec.decodeNew(data, s.offset(), s.length(), depth + 1));
                }
            }
            if (recordSubStreams != null) {
                for (Map.Entry<Integer, ByteArrayOutputStream> entry : recordSubStreams.entrySet()) {
                    FieldDescriptorSlot slot = orderedFields[entry.getKey()];
                    byte[] merged = entry.getValue().toByteArray();
                    slot.writeObject(target, slot.subCodec.decodeNew(merged, 0, merged.length, depth + 1));
                }
            }
        }
    }

    // ========================================================================
    // Shared Element / Map Encoding & Decoding Helpers
    // ========================================================================

    private static int decodeIntScalar(OmniBufDecoder dec, int wt) {
        return switch (wt) {
            case WireType.WIRE_UINT -> dec.readUint32();
            case WireType.WIRE_SINT -> dec.readSint32();
            case WireType.WIRE_FIXED32, WireType.WIRE_SFIXED32 -> dec.readFixed32();
            default -> throw new OmniBufException("omnibuf: unexpected int scalar wire type: " + wt);
        };
    }

    private static long decodeLongScalar(OmniBufDecoder dec, int wt) {
        return switch (wt) {
            case WireType.WIRE_UINT -> dec.readUint64();
            case WireType.WIRE_SINT -> dec.readSint64();
            case WireType.WIRE_FIXED64, WireType.WIRE_SFIXED64 -> dec.readFixed64();
            default -> throw new OmniBufException("omnibuf: unexpected long scalar wire type: " + wt);
        };
    }

    private static void writeIntArrayElem(OmniBufEncoder enc, int elemWT, int v) {
        switch (elemWT) {
            case WireType.WIRE_UINT -> enc.writeVarint(Integer.toUnsignedLong(v));
            case WireType.WIRE_SINT -> enc.writeVarint(PrefixVarint.encodeZigZag32(v));
            case WireType.WIRE_FIXED32, WireType.WIRE_SFIXED32 -> {
                byte[] tmp = new byte[4];
                PrefixVarint.setIntLE(tmp, 0, v);
                enc.writeRawBytes(tmp, 0, 4);
            }
            default -> throw new OmniBufException("omnibuf: unsupported int[] element wire type");
        }
    }

    private static void writeLongArrayElem(OmniBufEncoder enc, int elemWT, long v) {
        switch (elemWT) {
            case WireType.WIRE_UINT -> enc.writeVarint(v);
            case WireType.WIRE_SINT -> enc.writeVarint(PrefixVarint.encodeZigZag64(v));
            case WireType.WIRE_FIXED64, WireType.WIRE_SFIXED64 -> {
                byte[] tmp = new byte[8];
                PrefixVarint.setLongLE(tmp, 0, v);
                enc.writeRawBytes(tmp, 0, 8);
            }
            default -> throw new OmniBufException("omnibuf: unsupported long[] element wire type");
        }
    }

    private static void encodeScalarList(
            OmniBufEncoder enc, int fnum, int elemWT, List<Object> list) {
        int n = list == null ? 0 : list.size();
        enc.beginTypedArray(fnum, elemWT, n);
        for (int i = 0; i < n; i++) {
            writeInlineValue(enc, elemWT, null, list.get(i), false);
        }
        enc.endTypedArray();
    }

    @SuppressWarnings("unchecked")
    private static void encodeMapField(
            OmniBufEncoder enc,
            FieldDescriptorSlot slot,
            Map<Object, Object> map,
            boolean deterministic) {
        int n = map == null ? 0 : map.size();
        enc.beginTypedMap(slot.fieldNumber, slot.mapKeyWireType, slot.mapValWireType, n);
        if (n > 0) {
            Iterable<Map.Entry<Object, Object>> entries;
            if (deterministic && n > 1) {
                List<Map.Entry<Object, Object>> sorted = new ArrayList<>(map.entrySet());
                Comparator<Object> keyCmp = mapKeyComparator(slot.mapKeyWireType);
                sorted.sort((a, b) -> keyCmp.compare(a.getKey(), b.getKey()));
                entries = sorted;
            } else {
                entries = map.entrySet();
            }
            for (Map.Entry<Object, Object> entry : entries) {
                writeInlineValue(enc, slot.mapKeyWireType, null, entry.getKey(), deterministic);
                writeInlineValue(enc, slot.mapValWireType, slot.subCodec, entry.getValue(), deterministic);
            }
        }
        enc.endTypedMap();
    }

    private static Comparator<Object> mapKeyComparator(int keyWT) {
        return switch (keyWT) {
            case WireType.WIRE_STRING ->
                (a, b) -> Utf8Validator.compareUtf8(a == null ? "" : a.toString(), b == null ? "" : b.toString());
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

    private static void writeInlineValue(
            OmniBufEncoder enc,
            int wt,
            ClassCodec<?> subCodec,
            Object val,
            boolean deterministic) {
        switch (wt) {
            case WireType.WIRE_UINT -> {
                if (val instanceof Integer iv) {
                    enc.writeVarint(Integer.toUnsignedLong(iv));
                } else if (val instanceof Number nv) {
                    enc.writeVarint(nv.longValue());
                } else {
                    enc.writeVarint(0L);
                }
            }
            case WireType.WIRE_SINT -> {
                if (val instanceof Integer iv) {
                    enc.writeVarint(PrefixVarint.encodeZigZag32(iv));
                } else if (val instanceof Number nv) {
                    enc.writeVarint(PrefixVarint.encodeZigZag64(nv.longValue()));
                } else {
                    enc.writeVarint(0L);
                }
            }
            case WireType.WIRE_BOOL_FALSE -> enc.writeRawByte(Boolean.TRUE.equals(val) ? 1 : 0);
            case WireType.WIRE_FIXED32, WireType.WIRE_SFIXED32 -> {
                int v = val instanceof Number nv ? nv.intValue() : 0;
                byte[] tmp = new byte[4];
                PrefixVarint.setIntLE(tmp, 0, v);
                enc.writeRawBytes(tmp, 0, 4);
            }
            case WireType.WIRE_FLOAT32 -> {
                float v = val instanceof Number nv ? nv.floatValue() : 0.0f;
                byte[] tmp = new byte[4];
                PrefixVarint.setIntLE(tmp, 0, Float.floatToRawIntBits(v));
                enc.writeRawBytes(tmp, 0, 4);
            }
            case WireType.WIRE_FIXED64, WireType.WIRE_SFIXED64 -> {
                long v = val instanceof Number nv ? nv.longValue() : 0L;
                byte[] tmp = new byte[8];
                PrefixVarint.setLongLE(tmp, 0, v);
                enc.writeRawBytes(tmp, 0, 8);
            }
            case WireType.WIRE_FLOAT64 -> {
                double v = val instanceof Number nv ? nv.doubleValue() : 0.0d;
                byte[] tmp = new byte[8];
                PrefixVarint.setLongLE(tmp, 0, Double.doubleToRawLongBits(v));
                enc.writeRawBytes(tmp, 0, 8);
            }
            case WireType.WIRE_STRING -> enc.writeStringElement(val == null ? "" : val.toString());
            case WireType.WIRE_BYTES -> enc.writeBytesElement(val instanceof byte[] b ? b : EMPTY_BYTES);
            case WireType.WIRE_SUB_MSG -> {
                enc.beginInlineSubMsg();
                if (val != null && subCodec != null) {
                    subCodec.encode(enc, val, deterministic);
                }
                enc.endInlineSubMsg();
            }
            default -> throw new OmniBufException("omnibuf: unsupported inline wire type: " + wt);
        }
    }

    private static void decodePrimitiveArrayIntoList(
            OmniBufDecoder dec, FieldDescriptorSlot slot, List<Number> target, int depth) {
        if (depth + 1 > OmniBufDecoder.MAX_CONTAINER_DEPTH) {
            throw new OmniBufException("omnibuf: WireArray exceeds MaxNestingDepth");
        }
        OmniBufDecoder.Slice body = dec.readLengthPrefixedSlice(2);
        byte[] buf = dec.buffer();
        OmniBufDecoder.ArrayHeader hdr =
            OmniBufDecoder.parseArrayHeader(buf, body.offset(), body.end());
        if (hdr.elemWireType() != slot.elemWireType) {
            OmniBufDecoder.validateValue(buf, body.offset() - PrefixVarint.encodedLength(body.length()), body.end(), WireType.WIRE_ARRAY, depth);
            return;
        }
        OmniBufDecoder elemDec =
            new OmniBufDecoder(buf, hdr.payloadStart(), hdr.payloadEnd() - hdr.payloadStart());
        for (int i = 0; i < hdr.count(); i++) {
            if (slot.category == FieldCategory.INT_ARRAY) {
                target.add(decodeIntScalar(elemDec, slot.elemWireType));
            } else {
                target.add(decodeLongScalar(elemDec, slot.elemWireType));
            }
        }
        if (elemDec.hasRemaining()) {
            throw new OmniBufException("omnibuf: WireArray has trailing payload bytes");
        }
    }

    private static void decodeListInto(
            OmniBufDecoder dec, FieldDescriptorSlot slot, List<Object> target, int depth) {
        if (depth + 1 > OmniBufDecoder.MAX_CONTAINER_DEPTH) {
            throw new OmniBufException("omnibuf: WireArray exceeds MaxNestingDepth");
        }
        int arrayFieldValuePos = dec.position();
        OmniBufDecoder.Slice body = dec.readLengthPrefixedSlice(2);
        byte[] buf = dec.buffer();
        OmniBufDecoder.ArrayHeader hdr =
            OmniBufDecoder.parseArrayHeader(buf, body.offset(), body.end());
        if (hdr.elemWireType() != slot.elemWireType) {
            OmniBufDecoder.validateValue(buf, arrayFieldValuePos, dec.limit(), WireType.WIRE_ARRAY, depth);
            return;
        }
        OmniBufDecoder elemDec =
            new OmniBufDecoder(buf, hdr.payloadStart(), hdr.payloadEnd() - hdr.payloadStart());
        for (int i = 0; i < hdr.count(); i++) {
            target.add(readInlineValue(elemDec, slot.elemWireType, slot.elemClass, slot.subCodec, depth + 1));
        }
        if (elemDec.hasRemaining()) {
            throw new OmniBufException("omnibuf: WireArray has trailing payload bytes");
        }
    }

    private static void decodeMapInto(
            OmniBufDecoder dec, FieldDescriptorSlot slot, Map<Object, Object> target, int depth) {
        if (depth + 1 > OmniBufDecoder.MAX_CONTAINER_DEPTH) {
            throw new OmniBufException("omnibuf: WireMap exceeds MaxNestingDepth");
        }
        int mapFieldValuePos = dec.position();
        OmniBufDecoder.Slice body = dec.readLengthPrefixedSlice(3);
        byte[] buf = dec.buffer();
        OmniBufDecoder.MapHeader hdr =
            OmniBufDecoder.parseMapHeader(buf, body.offset(), body.end());
        if (hdr.keyWireType() != slot.mapKeyWireType || hdr.valWireType() != slot.mapValWireType) {
            OmniBufDecoder.validateValue(buf, mapFieldValuePos, dec.limit(), WireType.WIRE_MAP, depth);
            return;
        }
        Class<?> keyClass = mapKeyBoxClass(slot.mapKeyWireType);
        OmniBufDecoder entryDec =
            new OmniBufDecoder(buf, hdr.payloadStart(), hdr.payloadEnd() - hdr.payloadStart());
        for (int i = 0; i < hdr.count(); i++) {
            Object k = readInlineValue(entryDec, slot.mapKeyWireType, keyClass, null, depth + 1);
            Object v = readInlineValue(
                entryDec, slot.mapValWireType, slot.elemClass, slot.subCodec, depth + 1
            );
            target.put(k, v);
        }
        if (entryDec.hasRemaining()) {
            throw new OmniBufException("omnibuf: WireMap has trailing payload bytes");
        }
    }

    private static Class<?> mapKeyBoxClass(int keyWT) {
        return switch (keyWT) {
            case WireType.WIRE_STRING -> String.class;
            case WireType.WIRE_BOOL_FALSE -> Boolean.class;
            case WireType.WIRE_FIXED32, WireType.WIRE_SFIXED32 -> Integer.class;
            default -> Long.class;
        };
    }

    private static Object readInlineValue(
            OmniBufDecoder dec,
            int wt,
            Class<?> targetClass,
            ClassCodec<?> subCodec,
            int containerDepth) {
        return switch (wt) {
            case WireType.WIRE_UINT ->
                targetClass == Integer.class ? dec.readUint32() : dec.readUint64();
            case WireType.WIRE_SINT ->
                targetClass == Integer.class ? dec.readSint32() : dec.readSint64();
            case WireType.WIRE_BOOL_FALSE -> dec.readContainerBool();
            case WireType.WIRE_FIXED32, WireType.WIRE_SFIXED32 ->
                targetClass == Long.class ? Integer.toUnsignedLong(dec.readFixed32()) : dec.readFixed32();
            case WireType.WIRE_FLOAT32 -> dec.readFloat32();
            case WireType.WIRE_FIXED64, WireType.WIRE_SFIXED64 -> dec.readFixed64();
            case WireType.WIRE_FLOAT64 -> dec.readFloat64();
            case WireType.WIRE_STRING -> dec.readString();
            case WireType.WIRE_BYTES -> dec.readBytes();
            case WireType.WIRE_SUB_MSG -> {
                OmniBufDecoder.Slice s = dec.readLengthPrefixedSlice(0);
                yield subCodec.decodeNew(dec.buffer(), s.offset(), s.length(), containerDepth + 1);
            }
            default -> throw new OmniBufException("omnibuf: unsupported container value wire type: " + wt);
        };
    }

    // ========================================================================
    // Reflection / MethodHandle Metadata Compilation
    // ========================================================================

    private static <T> ClassCodec<T> compileCodec(
            Class<T> type, Map<Class<?>, ClassCodec<?>> building) {
        @SuppressWarnings("unchecked")
        ClassCodec<T> inProgress = (ClassCodec<T>) building.get(type);
        if (inProgress != null) {
            return inProgress;
        }

        OmniBufMessage msgAnn = type.getAnnotation(OmniBufMessage.class);
        String typeName = (msgAnn != null && !msgAnn.name().isEmpty())
            ? msgAnn.name()
            : type.getSimpleName();
        boolean isRecord = type.isRecord();

        ClassCodec<T> codec = new ClassCodec<>(type, typeName, isRecord);
        building.put(type, codec);

        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(type, MethodHandles.lookup());
            List<FieldDescriptorSlot> slots = new ArrayList<>();
            Set<Integer> seenFieldNums = new HashSet<>();

            if (isRecord) {
                RecordComponent[] comps = type.getRecordComponents();
                Class<?>[] paramTypes = new Class<?>[comps.length];
                for (int i = 0; i < comps.length; i++) {
                    RecordComponent rc = comps[i];
                    paramTypes[i] = rc.getType();
                    OmniBufField ann = rc.getAnnotation(OmniBufField.class);
                    if (ann == null) {
                        Field backingField = type.getDeclaredField(rc.getName());
                        ann = backingField.getAnnotation(OmniBufField.class);
                    }
                    if (ann == null) {
                        throw new OmniBufException(
                            "omnibuf: record component " + type.getSimpleName() + "." + rc.getName()
                                + " is missing @OmniBufField"
                        );
                    }
                    MethodHandle getter = lookup.unreflect(rc.getAccessor());
                    FieldDescriptorSlot slot = buildSlot(
                        slots.size(),
                        rc.getName(),
                        rc.getType(),
                        rc.getGenericType(),
                        ann,
                        null,
                        getter,
                        seenFieldNums,
                        building
                    );
                    slots.add(slot);
                }
                Constructor<T> canonical = type.getDeclaredConstructor(paramTypes);
                canonical.setAccessible(true);
                codec.constructorHandle = lookup.unreflectConstructor(canonical);
            } else {
                Constructor<T> ctor = type.getDeclaredConstructor();
                ctor.setAccessible(true);
                codec.constructorHandle = lookup.unreflectConstructor(ctor)
                    .asType(MethodType.methodType(Object.class));

                for (Field f : collectInstanceFields(type)) {
                    OmniBufField ann = f.getAnnotation(OmniBufField.class);
                    if (ann == null) {
                        continue;
                    }
                    VarHandle vh = lookup.unreflectVarHandle(f);
                    FieldDescriptorSlot slot = buildSlot(
                        slots.size(),
                        f.getName(),
                        f.getType(),
                        f.getGenericType(),
                        ann,
                        vh,
                        null,
                        seenFieldNums,
                        building
                    );
                    slots.add(slot);
                }
            }

            codec.orderedFields = slots.toArray(FieldDescriptorSlot[]::new);
            codec.directByNum = new FieldDescriptorSlot[MAX_DIRECT_FIELD_SLOT + 1];
            for (FieldDescriptorSlot slot : codec.orderedFields) {
                if (slot.fieldNumber <= MAX_DIRECT_FIELD_SLOT) {
                    codec.directByNum[slot.fieldNumber] = slot;
                } else {
                    if (codec.overflowByNum == null) {
                        codec.overflowByNum = new HashMap<>();
                    }
                    codec.overflowByNum.put(slot.fieldNumber, slot);
                }
            }
            return codec;
        } catch (OmniBufException e) {
            throw e;
        } catch (Throwable t) {
            throw new OmniBufException("omnibuf: failed compiling codec for " + type.getName(), t);
        }
    }

    private static List<Field> collectInstanceFields(Class<?> type) {
        List<Field> out = new ArrayList<>();
        for (Class<?> cur = type; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
            for (Field f : cur.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    private static FieldDescriptorSlot buildSlot(
            int index,
            String javaName,
            Class<?> rawType,
            Type genericType,
            OmniBufField ann,
            VarHandle varHandle,
            MethodHandle getter,
            Set<Integer> seenFieldNums,
            Map<Class<?>, ClassCodec<?>> building) {
        int id = ann.id();
        if (id <= 0 || id > WireType.MAX_FIELD_NUMBER) {
            throw new OmniBufException("omnibuf: invalid @OmniBufField id " + id + " on " + javaName);
        }
        if (!seenFieldNums.add(id)) {
            throw new OmniBufException("omnibuf: duplicate @OmniBufField id " + id + " on " + javaName);
        }
        String schemaName = ann.name().isEmpty() ? camelToSnake(javaName) : ann.name();

        if (rawType == int.class || rawType == Integer.class) {
            int wt = resolveScalarWire(rawType, ann.wire());
            return new FieldDescriptorSlot(
                index, id, schemaName, FieldCategory.INT_SCALAR, rawType, wt, 0, 0, 0, null, varHandle, getter
            );
        }
        if (rawType == long.class || rawType == Long.class) {
            int wt = resolveScalarWire(rawType, ann.wire());
            return new FieldDescriptorSlot(
                index, id, schemaName, FieldCategory.LONG_SCALAR, rawType, wt, 0, 0, 0, null, varHandle, getter
            );
        }
        if (rawType == boolean.class || rawType == Boolean.class) {
            return new FieldDescriptorSlot(
                index,
                id,
                schemaName,
                FieldCategory.BOOL_SCALAR,
                rawType,
                WireType.WIRE_BOOL_FALSE,
                0,
                0,
                0,
                null,
                varHandle,
                getter
            );
        }
        if (rawType == float.class || rawType == Float.class) {
            return new FieldDescriptorSlot(
                index,
                id,
                schemaName,
                FieldCategory.FLOAT_SCALAR,
                rawType,
                WireType.WIRE_FLOAT32,
                0,
                0,
                0,
                null,
                varHandle,
                getter
            );
        }
        if (rawType == double.class || rawType == Double.class) {
            return new FieldDescriptorSlot(
                index,
                id,
                schemaName,
                FieldCategory.DOUBLE_SCALAR,
                rawType,
                WireType.WIRE_FLOAT64,
                0,
                0,
                0,
                null,
                varHandle,
                getter
            );
        }
        if (rawType == String.class) {
            return new FieldDescriptorSlot(
                index,
                id,
                schemaName,
                FieldCategory.STRING_SCALAR,
                rawType,
                WireType.WIRE_STRING,
                0,
                0,
                0,
                null,
                varHandle,
                getter
            );
        }
        if (rawType == byte[].class) {
            return new FieldDescriptorSlot(
                index,
                id,
                schemaName,
                FieldCategory.BYTES_SCALAR,
                rawType,
                WireType.WIRE_BYTES,
                0,
                0,
                0,
                null,
                varHandle,
                getter
            );
        }
        if (rawType == int[].class) {
            int elemWT = resolveScalarWire(int.class, ann.wire());
            return new FieldDescriptorSlot(
                index,
                id,
                schemaName,
                FieldCategory.INT_ARRAY,
                rawType,
                WireType.WIRE_ARRAY,
                elemWT,
                0,
                0,
                Integer.class,
                varHandle,
                getter
            );
        }
        if (rawType == long[].class) {
            int elemWT = resolveScalarWire(long.class, ann.wire());
            return new FieldDescriptorSlot(
                index,
                id,
                schemaName,
                FieldCategory.LONG_ARRAY,
                rawType,
                WireType.WIRE_ARRAY,
                elemWT,
                0,
                0,
                Long.class,
                varHandle,
                getter
            );
        }
        if (List.class.isAssignableFrom(rawType)) {
            Class<?> elemClass = extractTypeArgument(genericType, 0, javaName);
            if (isScalarOrBytesClass(elemClass)) {
                int elemWT = resolveContainerElementWire(elemClass, ann.wire());
                return new FieldDescriptorSlot(
                    index,
                    id,
                    schemaName,
                    FieldCategory.LIST,
                    rawType,
                    WireType.WIRE_ARRAY,
                    elemWT,
                    0,
                    0,
                    elemClass,
                    varHandle,
                    getter
                );
            }
            FieldDescriptorSlot slot = new FieldDescriptorSlot(
                index,
                id,
                schemaName,
                FieldCategory.LIST,
                rawType,
                WireType.WIRE_ARRAY,
                WireType.WIRE_SUB_MSG,
                0,
                0,
                elemClass,
                varHandle,
                getter
            );
            slot.subCodec = compileCodec(elemClass, building);
            return slot;
        }
        if (Map.class.isAssignableFrom(rawType)) {
            Class<?> keyClass = extractTypeArgument(genericType, 0, javaName);
            Class<?> valClass = extractTypeArgument(genericType, 1, javaName);
            int keyWT = resolveContainerElementWire(keyClass, ann.keyWire());
            if (!WireType.isValidMapKeyWireType(keyWT)) {
                throw new OmniBufException("omnibuf: invalid map key wire type on " + javaName);
            }
            int valWT;
            ClassCodec<?> subCodec = null;
            if (isScalarOrBytesClass(valClass)) {
                WireKind vk = ann.valueWire() != WireKind.AUTO ? ann.valueWire() : ann.wire();
                valWT = resolveContainerElementWire(valClass, vk);
            } else {
                valWT = WireType.WIRE_SUB_MSG;
                subCodec = compileCodec(valClass, building);
            }
            FieldDescriptorSlot slot = new FieldDescriptorSlot(
                index,
                id,
                schemaName,
                FieldCategory.MAP,
                rawType,
                WireType.WIRE_MAP,
                0,
                keyWT,
                valWT,
                valClass,
                varHandle,
                getter
            );
            slot.subCodec = subCodec;
            return slot;
        }

        // Nested sub-message class or record
        FieldDescriptorSlot slot = new FieldDescriptorSlot(
            index,
            id,
            schemaName,
            FieldCategory.SUB_MSG,
            rawType,
            WireType.WIRE_SUB_MSG,
            0,
            0,
            0,
            rawType,
            varHandle,
            getter
        );
        slot.subCodec = compileCodec(rawType, building);
        return slot;
    }

    private static boolean isScalarOrBytesClass(Class<?> c) {
        return c == Integer.class
            || c == Long.class
            || c == Boolean.class
            || c == Float.class
            || c == Double.class
            || c == String.class
            || c == byte[].class;
    }

    private static int resolveScalarWire(Class<?> c, WireKind kind) {
        if (c == int.class || c == Integer.class) {
            return switch (kind) {
                case AUTO, UINT -> WireType.WIRE_UINT;
                case SINT -> WireType.WIRE_SINT;
                case FIXED32 -> WireType.WIRE_FIXED32;
                case SFIXED32 -> WireType.WIRE_SFIXED32;
                default -> throw new OmniBufException("omnibuf: incompatible WireKind " + kind + " for int");
            };
        }
        if (c == long.class || c == Long.class) {
            return switch (kind) {
                case AUTO, UINT -> WireType.WIRE_UINT;
                case SINT -> WireType.WIRE_SINT;
                case FIXED64 -> WireType.WIRE_FIXED64;
                case SFIXED64 -> WireType.WIRE_SFIXED64;
                default -> throw new OmniBufException("omnibuf: incompatible WireKind " + kind + " for long");
            };
        }
        throw new OmniBufException("omnibuf: unsupported scalar type: " + c);
    }

    private static int resolveContainerElementWire(Class<?> c, WireKind kind) {
        if (c == Integer.class || c == Long.class) {
            return resolveScalarWire(c, kind);
        }
        if (c == Boolean.class) {
            return WireType.WIRE_BOOL_FALSE;
        }
        if (c == Float.class) {
            return WireType.WIRE_FLOAT32;
        }
        if (c == Double.class) {
            return WireType.WIRE_FLOAT64;
        }
        if (c == String.class) {
            return WireType.WIRE_STRING;
        }
        if (c == byte[].class) {
            return WireType.WIRE_BYTES;
        }
        throw new OmniBufException("omnibuf: unsupported container element type: " + c);
    }

    private static Class<?> extractTypeArgument(Type genericType, int argIndex, String fieldName) {
        if (!(genericType instanceof ParameterizedType pt)) {
            throw new OmniBufException("omnibuf: raw generic collection on field " + fieldName);
        }
        Type arg = pt.getActualTypeArguments()[argIndex];
        if (arg instanceof Class<?> clz) {
            return clz;
        }
        throw new OmniBufException("omnibuf: unsupported type parameter on field " + fieldName + ": " + arg);
    }

    public static String camelToSnake(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0 && s.charAt(i - 1) != '_'
                        && (!Character.isUpperCase(s.charAt(i - 1))
                            || (i + 1 < s.length() && Character.isLowerCase(s.charAt(i + 1))))) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
