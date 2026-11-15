package com.omnibuf;

import com.omnibuf.annotation.OmniBufField;
import com.omnibuf.annotation.OmniBufMessage;
import com.omnibuf.annotation.WireKind;
import com.omnibuf.wire.CodecPool;
import com.omnibuf.wire.IntArrayList;
import com.omnibuf.wire.LongArrayList;
import com.omnibuf.wire.OmniBufDecoder;
import com.omnibuf.wire.OmniBufEncoder;
import com.omnibuf.wire.PrefixVarint;
import com.omnibuf.wire.WireType;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
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
 * High-performance exact-{@link MethodHandle} codec for Java classes and Java 21 {@code record}
 * types annotated with {@link OmniBufMessage} and {@link OmniBufField} ({@code TIER_ANNOTATED_STRUCT}).
 */
public final class OmniBufMapper {

    private static final int MAX_DIRECT_FIELD_SLOT = 32;
    private static final byte[] EMPTY_BYTES = new byte[0];
    private static final int[] EMPTY_INTS = new int[0];
    private static final long[] EMPTY_LONGS = new long[0];

    private static final ConcurrentHashMap<Class<?>, ClassCodec<?>> CODEC_CACHE =
        new ConcurrentHashMap<>();

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
        OmniBufEncoder enc = CodecPool.acquireEncoder();
        try {
            if (opts.selfDescribing()) {
                codec.writeSelfDescribingField0(enc, message, opts.deterministic());
            }
            codec.encode(enc, message, opts.deterministic());
            return enc.toByteArray();
        } finally {
            CodecPool.releaseEncoder(enc);
        }
    }

    public static <T> T unmarshal(byte[] data, Class<T> type) {
        return unmarshal(data, type, UnmarshalOptions.DEFAULT);
    }

    public static <T> T unmarshal(byte[] data, Class<T> type, UnmarshalOptions options) {
        if (data == null) {
            throw new OmniBufException("omnibuf: null packet buffer");
        }
        if (type == null) {
            throw new OmniBufException("omnibuf: null target class");
        }
        ClassCodec<T> codec = getCodec(type);
        OmniBufDecoder dec = CodecPool.acquireDecoder(data, 0, data.length, options);
        try {
            return codec.decodeNewFrom(dec, 0);
        } finally {
            CodecPool.releaseDecoder(dec);
        }
    }

    public static <T> void unmarshal(byte[] data, T target) {
        unmarshal(data, target, UnmarshalOptions.DEFAULT);
    }

    @SuppressWarnings("unchecked")
    public static <T> void unmarshal(byte[] data, T target, UnmarshalOptions options) {
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
        OmniBufDecoder dec = CodecPool.acquireDecoder(data, 0, data.length, options);
        try {
            codec.resetInstance(target);
            codec.decodeFrom(dec, target, 0);
        } finally {
            CodecPool.releaseDecoder(dec);
        }
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
        ENUM_SCALAR,
        INT_ARRAY,
        LONG_ARRAY,
        LIST,
        MAP,
        SUB_MSG
    }

    private static final class AccessorHandles {
        final MethodHandle objGetter;
        final MethodHandle intGetter;
        final MethodHandle longGetter;
        final MethodHandle boolGetter;
        final MethodHandle floatGetter;
        final MethodHandle doubleGetter;

        final MethodHandle objSetter;
        final MethodHandle intSetter;
        final MethodHandle longSetter;
        final MethodHandle boolSetter;
        final MethodHandle floatSetter;
        final MethodHandle doubleSetter;

        AccessorHandles(Class<?> rawType, MethodHandle rawGetter, MethodHandle rawSetter) {
            this.objGetter = rawGetter.asType(MethodType.methodType(Object.class, Object.class));
            this.intGetter = rawType == int.class
                ? rawGetter.asType(MethodType.methodType(int.class, Object.class))
                : null;
            this.longGetter = rawType == long.class
                ? rawGetter.asType(MethodType.methodType(long.class, Object.class))
                : null;
            this.boolGetter = rawType == boolean.class
                ? rawGetter.asType(MethodType.methodType(boolean.class, Object.class))
                : null;
            this.floatGetter = rawType == float.class
                ? rawGetter.asType(MethodType.methodType(float.class, Object.class))
                : null;
            this.doubleGetter = rawType == double.class
                ? rawGetter.asType(MethodType.methodType(double.class, Object.class))
                : null;

            if (rawSetter != null) {
                this.objSetter = rawSetter.asType(MethodType.methodType(void.class, Object.class, Object.class));
                this.intSetter = rawType == int.class
                    ? rawSetter.asType(MethodType.methodType(void.class, Object.class, int.class))
                    : null;
                this.longSetter = rawType == long.class
                    ? rawSetter.asType(MethodType.methodType(void.class, Object.class, long.class))
                    : null;
                this.boolSetter = rawType == boolean.class
                    ? rawSetter.asType(MethodType.methodType(void.class, Object.class, boolean.class))
                    : null;
                this.floatSetter = rawType == float.class
                    ? rawSetter.asType(MethodType.methodType(void.class, Object.class, float.class))
                    : null;
                this.doubleSetter = rawType == double.class
                    ? rawSetter.asType(MethodType.methodType(void.class, Object.class, double.class))
                    : null;
            } else {
                this.objSetter = null;
                this.intSetter = null;
                this.longSetter = null;
                this.boolSetter = null;
                this.floatSetter = null;
                this.doubleSetter = null;
            }
        }
    }

    private static final class FieldDescriptorSlot {
        int slotIndex;
        final int recordComponentIndex;
        final int fieldNumber;
        final String schemaName;
        final FieldCategory category;
        final Class<?> rawType;
        final int wireType;
        final int elemWireType;
        final int mapKeyWireType;
        final int mapValWireType;
        final Class<?> mapKeyClass;
        final Class<?> elemClass;
        final Object[] enumConstants;
        final byte tag1B;
        final short tag2B;
        final boolean canUsePrimitiveList;
        ClassCodec<?> subCodec;
        final AccessorHandles handles;

        FieldDescriptorSlot(
                int recordComponentIndex,
                int fieldNumber,
                String schemaName,
                FieldCategory category,
                Class<?> rawType,
                int wireType,
                int elemWireType,
                int mapKeyWireType,
                int mapValWireType,
                Class<?> mapKeyClass,
                Class<?> elemClass,
                AccessorHandles handles) {
            this.recordComponentIndex = recordComponentIndex;
            this.fieldNumber = fieldNumber;
            this.schemaName = schemaName;
            this.category = category;
            this.rawType = rawType;
            this.wireType = wireType;
            this.elemWireType = elemWireType;
            this.mapKeyWireType = mapKeyWireType;
            this.mapValWireType = mapValWireType;
            this.mapKeyClass = mapKeyClass;
            this.elemClass = elemClass;
            this.enumConstants = rawType.isEnum() ? rawType.getEnumConstants() : null;
            this.tag1B = (fieldNumber > 0 && fieldNumber < 8)
                ? (byte) ((((fieldNumber << 4) | wireType) << 1) | 1)
                : 0;
            this.tag2B = (fieldNumber >= 8 && fieldNumber < 1024)
                ? (short) ((((fieldNumber << 4) | wireType) << 2) | 2)
                : 0;
            this.canUsePrimitiveList = category == FieldCategory.LIST
                && rawType.isAssignableFrom(IntArrayList.class);
            this.handles = handles;
        }

        Object readObject(Object instance) {
            try {
                return handles.objGetter.invokeExact(instance);
            } catch (Throwable t) {
                throw new OmniBufException("omnibuf: failed reading field " + schemaName, t);
            }
        }

        int readInt(Object instance) {
            try {
                if (handles.intGetter != null) {
                    return (int) handles.intGetter.invokeExact(instance);
                }
                Object v = handles.objGetter.invokeExact(instance);
                return v == null ? 0 : ((Number) v).intValue();
            } catch (Throwable t) {
                throw new OmniBufException("omnibuf: failed reading int field " + schemaName, t);
            }
        }

        long readLong(Object instance) {
            try {
                if (handles.longGetter != null) {
                    return (long) handles.longGetter.invokeExact(instance);
                }
                Object v = handles.objGetter.invokeExact(instance);
                return v == null ? 0L : ((Number) v).longValue();
            } catch (Throwable t) {
                throw new OmniBufException("omnibuf: failed reading long field " + schemaName, t);
            }
        }

        boolean readBoolean(Object instance) {
            try {
                if (handles.boolGetter != null) {
                    return (boolean) handles.boolGetter.invokeExact(instance);
                }
                Object v = handles.objGetter.invokeExact(instance);
                return Boolean.TRUE.equals(v);
            } catch (Throwable t) {
                throw new OmniBufException("omnibuf: failed reading boolean field " + schemaName, t);
            }
        }

        float readFloat(Object instance) {
            try {
                if (handles.floatGetter != null) {
                    return (float) handles.floatGetter.invokeExact(instance);
                }
                Object v = handles.objGetter.invokeExact(instance);
                return v == null ? 0.0f : ((Number) v).floatValue();
            } catch (Throwable t) {
                throw new OmniBufException("omnibuf: failed reading float field " + schemaName, t);
            }
        }

        double readDouble(Object instance) {
            try {
                if (handles.doubleGetter != null) {
                    return (double) handles.doubleGetter.invokeExact(instance);
                }
                Object v = handles.objGetter.invokeExact(instance);
                return v == null ? 0.0d : ((Number) v).doubleValue();
            } catch (Throwable t) {
                throw new OmniBufException("omnibuf: failed reading double field " + schemaName, t);
            }
        }

        void writeInt(Object target, int value) {
            try {
                if (handles.intSetter != null) {
                    handles.intSetter.invokeExact(target, value);
                } else {
                    handles.objSetter.invokeExact(target, (Object) Integer.valueOf(value));
                }
            } catch (Throwable t) {
                throw new OmniBufException("omnibuf: failed writing int field " + schemaName, t);
            }
        }

        void writeLong(Object target, long value) {
            try {
                if (handles.longSetter != null) {
                    handles.longSetter.invokeExact(target, value);
                } else {
                    handles.objSetter.invokeExact(target, (Object) Long.valueOf(value));
                }
            } catch (Throwable t) {
                throw new OmniBufException("omnibuf: failed writing long field " + schemaName, t);
            }
        }

        void writeBoolean(Object target, boolean value) {
            try {
                if (handles.boolSetter != null) {
                    handles.boolSetter.invokeExact(target, value);
                } else {
                    handles.objSetter.invokeExact(target, (Object) Boolean.valueOf(value));
                }
            } catch (Throwable t) {
                throw new OmniBufException("omnibuf: failed writing boolean field " + schemaName, t);
            }
        }

        void writeFloat(Object target, float value) {
            try {
                if (handles.floatSetter != null) {
                    handles.floatSetter.invokeExact(target, value);
                } else {
                    handles.objSetter.invokeExact(target, (Object) Float.valueOf(value));
                }
            } catch (Throwable t) {
                throw new OmniBufException("omnibuf: failed writing float field " + schemaName, t);
            }
        }

        void writeDouble(Object target, double value) {
            try {
                if (handles.doubleSetter != null) {
                    handles.doubleSetter.invokeExact(target, value);
                } else {
                    handles.objSetter.invokeExact(target, (Object) Double.valueOf(value));
                }
            } catch (Throwable t) {
                throw new OmniBufException("omnibuf: failed writing double field " + schemaName, t);
            }
        }

        void writeObject(Object target, Object value) {
            try {
                handles.objSetter.invokeExact(target, value);
            } catch (Throwable t) {
                throw new OmniBufException("omnibuf: failed writing field " + schemaName, t);
            }
        }

        Object decodeEnumScalar(OmniBufDecoder dec) {
            int ord = dec.readSint32();
            if (enumConstants == null || ord < 0 || ord >= enumConstants.length) {
                throw new OmniBufException(
                    "omnibuf: unknown enum ordinal " + ord + " for " + rawType.getSimpleName()
                );
            }
            return enumConstants[ord];
        }

        Object defaultEnumValue() {
            return enumConstants != null && enumConstants.length > 0 ? enumConstants[0] : null;
        }

        Object immutableRecordDefault() {
            return switch (category) {
                case INT_SCALAR -> 0;
                case LONG_SCALAR -> 0L;
                case BOOL_SCALAR -> Boolean.FALSE;
                case FLOAT_SCALAR -> 0.0f;
                case DOUBLE_SCALAR -> 0.0d;
                case STRING_SCALAR -> "";
                case BYTES_SCALAR -> EMPTY_BYTES;
                case ENUM_SCALAR -> defaultEnumValue();
                case INT_ARRAY -> EMPTY_INTS;
                case LONG_ARRAY -> EMPTY_LONGS;
                case LIST, MAP, SUB_MSG -> null;
            };
        }

        boolean matchesWireType(int wt) {
            if (category == FieldCategory.BOOL_SCALAR) {
                return wt == WireType.WIRE_BOOL_FALSE || wt == WireType.WIRE_BOOL_TRUE;
            }
            return wt == wireType;
        }
    }

    /**
     * Per-slice incremental builder used when an immutable Java {@code record} sub-message appears
     * multiple times on the wire (via packet concatenation or repeated field overrides). Validates
     * every slice boundary independently without concatenating raw unvalidated byte slices.
     */
    private static final class RecordBuildState<T> {
        private final ClassCodec<T> codec;
        private final Object[] args;
        private Map<Integer, List<Number>> primArrayAccum;
        private Map<Integer, RecordBuildState<?>> subRecordStates;

        RecordBuildState(ClassCodec<T> codec) {
            this.codec = codec;
            this.args = codec.defaultRecordTemplate.clone();
        }

        RecordBuildState(ClassCodec<T> codec, Object existingRecord) {
            this.codec = codec;
            int arity = codec.recordComponentsByIndex.length;
            this.args = new Object[arity];
            for (int i = 0; i < arity; i++) {
                FieldDescriptorSlot slot = codec.recordComponentsByIndex[i];
                Object val = slot.readObject(existingRecord);
                if (slot.category == FieldCategory.LIST && val instanceof List<?> prevList) {
                    this.args[i] = new ArrayList<Object>(prevList);
                } else if (slot.category == FieldCategory.MAP && val instanceof Map<?, ?> prevMap) {
                    this.args[i] = new LinkedHashMap<Object, Object>(prevMap);
                } else {
                    this.args[i] = val;
                }
            }
        }

        @SuppressWarnings("unchecked")
        void applySlice(OmniBufDecoder dec, int depth) {
            if (depth > dec.maxNestingDepth()) {
                throw new OmniBufException("omnibuf: nesting depth exceeds MaxNestingDepth");
            }
            while (dec.hasRemaining()) {
                long tag = dec.readTag();
                int fnum = (int) (tag >>> 4);
                int wt = (int) (tag & 0x0FL);
                if (fnum == 0) {
                    dec.skipOrValidateUnknown(0, wt, depth);
                    continue;
                }
                FieldDescriptorSlot slot = codec.lookupSlot(fnum);
                if (slot == null || !slot.matchesWireType(wt)) {
                    dec.skipOrValidateUnknown(fnum, wt, depth);
                    continue;
                }
                int compIdx = slot.recordComponentIndex;
                switch (slot.category) {
                    case INT_SCALAR -> args[compIdx] = decodeIntScalar(dec, wt);
                    case LONG_SCALAR -> args[compIdx] = decodeLongScalar(dec, wt);
                    case BOOL_SCALAR -> args[compIdx] = (wt == WireType.WIRE_BOOL_TRUE);
                    case FLOAT_SCALAR -> args[compIdx] = dec.readFloat32();
                    case DOUBLE_SCALAR -> args[compIdx] = dec.readFloat64();
                    case STRING_SCALAR -> args[compIdx] = dec.readString();
                    case BYTES_SCALAR -> args[compIdx] = dec.readBytes();
                    case ENUM_SCALAR -> args[compIdx] = slot.decodeEnumScalar(dec);
                    case INT_ARRAY -> {
                        if (primArrayAccum == null) {
                            primArrayAccum = new HashMap<>();
                        }
                        List<Number> acc = primArrayAccum.get(compIdx);
                        if (acc == null) {
                            acc = new ArrayList<>();
                            if (args[compIdx] instanceof int[] prev && prev.length > 0) {
                                for (int v : prev) {
                                    acc.add(v);
                                }
                            }
                            primArrayAccum.put(compIdx, acc);
                        }
                        decodePrimitiveArrayIntoList(dec, slot, acc, depth);
                    }
                    case LONG_ARRAY -> {
                        if (primArrayAccum == null) {
                            primArrayAccum = new HashMap<>();
                        }
                        List<Number> acc = primArrayAccum.get(compIdx);
                        if (acc == null) {
                            acc = new ArrayList<>();
                            if (args[compIdx] instanceof long[] prev && prev.length > 0) {
                                for (long v : prev) {
                                    acc.add(v);
                                }
                            }
                            primArrayAccum.put(compIdx, acc);
                        }
                        decodePrimitiveArrayIntoList(dec, slot, acc, depth);
                    }
                    case LIST -> {
                        List<Object> list = (List<Object>) args[compIdx];
                        args[compIdx] = decodeListInto(dec, slot, list, depth);
                    }
                    case MAP -> {
                        Map<Object, Object> map = (Map<Object, Object>) args[compIdx];
                        args[compIdx] = decodeMapInto(dec, slot, map, depth);
                    }
                    case SUB_MSG -> {
                        int subSaved = dec.readLengthPrefixedLimit(0);
                        if (slot.subCodec.isRecord) {
                            if (subRecordStates == null) {
                                subRecordStates = new HashMap<>();
                            }
                            RecordBuildState<?> childState = subRecordStates.get(compIdx);
                            if (childState == null) {
                                Object prevRec = args[compIdx];
                                args[compIdx] = null;
                                childState = prevRec != null
                                    ? slot.subCodec.newRecordStateFromInstance(prevRec)
                                    : slot.subCodec.newBlankRecordState();
                                subRecordStates.put(compIdx, childState);
                            }
                            childState.applySlice(dec, depth + 1);
                        } else {
                            Object sub = args[compIdx];
                            if (sub == null) {
                                sub = slot.subCodec.newBlankMutableInstance();
                                args[compIdx] = sub;
                            }
                            slot.subCodec.decodeFrom(dec, sub, depth + 1);
                        }
                        dec.endContainer(subSaved);
                    }
                }
            }
        }

        T build() {
            if (subRecordStates != null) {
                for (Map.Entry<Integer, RecordBuildState<?>> entry : subRecordStates.entrySet()) {
                    args[entry.getKey()] = entry.getValue().build();
                }
            }
            if (primArrayAccum != null) {
                for (Map.Entry<Integer, List<Number>> entry : primArrayAccum.entrySet()) {
                    int compIdx = entry.getKey();
                    List<Number> nums = entry.getValue();
                    if (codec.recordComponentsByIndex[compIdx].category == FieldCategory.INT_ARRAY) {
                        int[] arr = new int[nums.size()];
                        for (int i = 0; i < arr.length; i++) {
                            arr[i] = nums.get(i).intValue();
                        }
                        args[compIdx] = arr;
                    } else {
                        long[] arr = new long[nums.size()];
                        for (int i = 0; i < arr.length; i++) {
                            arr[i] = nums.get(i).longValue();
                        }
                        args[compIdx] = arr;
                    }
                }
            }
            return codec.instantiateRecord(args);
        }
    }

    private static final class ClassCodec<T> {
        final Class<T> type;
        final String typeName;
        final boolean isRecord;
        MethodHandle constructorHandle;
        MethodHandle recordSpreaderHandle;
        FieldDescriptorSlot[] recordComponentsByIndex;
        Object[] defaultRecordTemplate;
        boolean hasCollectionComponents;
        boolean cleanFreshConstructor;
        FieldDescriptorSlot[] orderedFields;
        FieldDescriptorSlot[] largeCandidateSlots;
        FieldDescriptorSlot[] directByNum;
        Map<Integer, FieldDescriptorSlot> overflowByNum;
        volatile SchemaMeta cachedMeta;
        volatile byte[] cachedField0;
        volatile boolean cyclicSchema;

        ClassCodec(Class<T> type, String typeName, boolean isRecord) {
            this.type = type;
            this.typeName = typeName;
            this.isRecord = isRecord;
        }

        RecordBuildState<T> newBlankRecordState() {
            return new RecordBuildState<>(this);
        }

        RecordBuildState<T> newRecordStateFromInstance(Object existing) {
            return new RecordBuildState<>(this, existing);
        }

        SchemaMeta getSchemaMeta() {
            SchemaMeta m = cachedMeta;
            if (m == null) {
                synchronized (this) {
                    m = cachedMeta;
                    if (m == null) {
                        boolean[] cyclicOut = new boolean[1];
                        m = buildSchemaMeta(1, new HashSet<>(), cyclicOut);
                        cyclicSchema = cyclicOut[0];
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

        void writeSelfDescribingField0(OmniBufEncoder enc, T message, boolean deterministic) {
            SchemaMeta staticMeta = getSchemaMeta();
            if (cyclicSchema) {
                SchemaMeta dynMeta = buildValueSchemaMeta(message, 1);
                if (dynMeta != null) {
                    dynMeta.writeField0(enc, deterministic);
                }
                return;
            }
            if (deterministic) {
                enc.writeRawBytes(getField0Bytes());
            } else if (staticMeta != null) {
                staticMeta.writeField0(enc, false);
            }
        }

        private SchemaMeta buildSchemaMeta(int schemaDepth, Set<Class<?>> active, boolean[] cyclicOut) {
            if (!active.add(type)) {
                cyclicOut[0] = true;
                return null;
            }
            if (schemaDepth + 1 > OmniBufDecoder.MAX_CONTAINER_DEPTH) {
                active.remove(type);
                return null;
            }
            try {
                Map<Integer, String> fieldNames = new LinkedHashMap<>(orderedFields.length);
                Map<Integer, SchemaMeta> childMetas = null;
                for (FieldDescriptorSlot slot : orderedFields) {
                    fieldNames.put(slot.fieldNumber, slot.schemaName);
                    if (slot.subCodec != null) {
                        SchemaMeta child = slot.subCodec.buildSchemaMeta(schemaDepth + 2, active, cyclicOut);
                        if (child != null && schemaDepth + 3 <= OmniBufDecoder.MAX_CONTAINER_DEPTH) {
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

        SchemaMeta buildValueSchemaMeta(Object instance, int schemaDepth) {
            if (instance == null || schemaDepth + 1 > OmniBufDecoder.MAX_CONTAINER_DEPTH) {
                return null;
            }
            Map<Integer, String> fieldNames = new LinkedHashMap<>(orderedFields.length);
            Map<Integer, SchemaMeta> childMetas = null;
            for (FieldDescriptorSlot slot : orderedFields) {
                fieldNames.put(slot.fieldNumber, slot.schemaName);
                if (slot.subCodec == null || schemaDepth + 3 > OmniBufDecoder.MAX_CONTAINER_DEPTH) {
                    continue;
                }
                Object raw = slot.readObject(instance);
                if (raw == null) {
                    continue;
                }
                SchemaMeta child = null;
                if (slot.category == FieldCategory.SUB_MSG) {
                    child = slot.subCodec.buildValueSchemaMeta(raw, schemaDepth + 2);
                } else if (slot.category == FieldCategory.LIST && raw instanceof List<?> list && !list.isEmpty()) {
                    for (int i = 0, n = list.size(); i < n; i++) {
                        Object elem = list.get(i);
                        if (elem != null) {
                            child = SchemaMeta.merge(
                                child, slot.subCodec.buildValueSchemaMeta(elem, schemaDepth + 2)
                            );
                        }
                    }
                } else if (slot.category == FieldCategory.MAP && raw instanceof Map<?, ?> map && !map.isEmpty()) {
                    for (Object elem : map.values()) {
                        if (elem != null) {
                            child = SchemaMeta.merge(
                                child, slot.subCodec.buildValueSchemaMeta(elem, schemaDepth + 2)
                            );
                        }
                    }
                }
                if (child != null) {
                    if (childMetas == null) {
                        childMetas = new LinkedHashMap<>();
                    }
                    childMetas.put(slot.fieldNumber, child);
                }
            }
            return new SchemaMeta(typeName, fieldNames, childMetas);
        }

        FieldDescriptorSlot lookupSlot(int fieldNum) {
            if (fieldNum > 0 && fieldNum <= MAX_DIRECT_FIELD_SLOT) {
                return directByNum[fieldNum];
            }
            return overflowByNum != null ? overflowByNum.get(fieldNum) : null;
        }

        @SuppressWarnings("unchecked")
        T instantiateRecord(Object[] args) {
            if (hasCollectionComponents) {
                for (int i = 0; i < recordComponentsByIndex.length; i++) {
                    if (args[i] == null) {
                        FieldCategory cat = recordComponentsByIndex[i].category;
                        if (cat == FieldCategory.LIST) {
                            args[i] = new ArrayList<>(0);
                        } else if (cat == FieldCategory.MAP) {
                            args[i] = new LinkedHashMap<>(4);
                        }
                    }
                }
            }
            try {
                return (T) recordSpreaderHandle.invokeExact(args);
            } catch (OmniBufException e) {
                throw e;
            } catch (Throwable t) {
                throw new OmniBufException("omnibuf: failed constructing record " + type.getName(), t);
            }
        }

        @SuppressWarnings("unchecked")
        T newBlankMutableInstance() {
            try {
                T inst = (T) constructorHandle.invokeExact();
                if (!cleanFreshConstructor) {
                    resetInstance(inst);
                }
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
                    case ENUM_SCALAR -> slot.writeObject(target, slot.defaultEnumValue());
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

        boolean isLargeInstance(Object inst, int depthLeft) {
            if (inst == null || depthLeft < 0 || largeCandidateSlots.length == 0) {
                return false;
            }
            for (FieldDescriptorSlot s : largeCandidateSlots) {
                switch (s.category) {
                    case STRING_SCALAR -> {
                        String str = (String) s.readObject(inst);
                        if (str != null && str.length() > 16_383) {
                            return true;
                        }
                    }
                    case BYTES_SCALAR -> {
                        byte[] b = (byte[]) s.readObject(inst);
                        if (b != null && b.length > 16_383) {
                            return true;
                        }
                    }
                    case SUB_MSG -> {
                        if (depthLeft > 0) {
                            Object sub = s.readObject(inst);
                            if (sub != null && s.subCodec.isLargeInstance(sub, depthLeft - 1)) {
                                return true;
                            }
                        }
                    }
                    case LIST -> {
                        if (depthLeft > 0) {
                            List<?> list = (List<?>) s.readObject(inst);
                            if (list != null
                                    && !list.isEmpty()
                                    && s.subCodec.isLargeInstance(list.get(0), depthLeft - 1)) {
                                return true;
                            }
                        }
                    }
                    default -> {}
                }
            }
            return false;
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
                    if (fnum < 8) {
                        enc.writeString1BTag(slot.tag1B, s);
                    } else if (fnum < 1024) {
                        enc.writeString2BTag(slot.tag2B, s);
                    } else {
                        enc.writeString(fnum, s == null ? "" : s);
                    }
                }
                case BYTES_SCALAR -> {
                    byte[] b = (byte[]) slot.readObject(instance);
                    enc.writeBytes(fnum, b == null ? EMPTY_BYTES : b);
                }
                case ENUM_SCALAR -> {
                    Object ev = slot.readObject(instance);
                    enc.writeSint32(fnum, ev instanceof Enum<?> e ? e.ordinal() : 0);
                }
                case INT_ARRAY -> {
                    int[] arr = (int[]) slot.readObject(instance);
                    if (slot.elemWireType == WireType.WIRE_UINT) {
                        enc.writeUint32Array(fnum, arr);
                    } else {
                        int n = arr == null ? 0 : arr.length;
                        int arrPos = enc.beginTypedArrayFast(fnum, slot.elemWireType, n);
                        for (int i = 0; i < n; i++) {
                            writeIntArrayElem(enc, slot.elemWireType, arr[i]);
                        }
                        enc.endContainerAt(arrPos);
                    }
                }
                case LONG_ARRAY -> {
                    long[] arr = (long[]) slot.readObject(instance);
                    int n = arr == null ? 0 : arr.length;
                    int arrPos = enc.beginTypedArrayFast(fnum, slot.elemWireType, n);
                    for (int i = 0; i < n; i++) {
                        writeLongArrayElem(enc, slot.elemWireType, arr[i]);
                    }
                    enc.endContainerAt(arrPos);
                }
                case SUB_MSG -> {
                    Object sub = slot.readObject(instance);
                    if (sub != null) {
                        int subPos = enc.beginSubMsgFast(fnum, slot.subCodec.isLargeInstance(sub, 1));
                        slot.subCodec.encode(enc, sub, deterministic);
                        enc.endContainerAt(subPos);
                    }
                }
                case LIST -> {
                    List<Object> list = (List<Object>) slot.readObject(instance);
                    if (slot.elemWireType == WireType.WIRE_SUB_MSG) {
                        int n = list == null ? 0 : list.size();
                        boolean largeArr = n > 0 && slot.subCodec.isLargeInstance(list.get(0), 1);
                        int arrPos = enc.beginTypedArrayFast(fnum, WireType.WIRE_SUB_MSG, n, largeArr);
                        for (int i = 0; i < n; i++) {
                            Object elem = list.get(i);
                            int elemPos = enc.beginInlineSubMsgFast(slot.subCodec.isLargeInstance(elem, 1));
                            if (elem != null) {
                                slot.subCodec.encode(enc, elem, deterministic);
                            }
                            enc.endContainerAt(elemPos);
                        }
                        enc.endContainerAt(arrPos);
                    } else {
                        encodeScalarList(enc, fnum, slot.elemWireType, slot.elemClass, list);
                    }
                }
                case MAP -> {
                    Map<Object, Object> map = (Map<Object, Object>) slot.readObject(instance);
                    encodeMapField(enc, slot, map, deterministic);
                }
            }
        }

        T decodeNewFrom(OmniBufDecoder dec, int depth) {
            if (isRecord) {
                return decodeRecordFrom(dec, depth);
            }
            T inst = newBlankMutableInstance();
            decodeFrom(dec, inst, depth);
            return inst;
        }

        @SuppressWarnings("unchecked")
        private T decodeRecordFrom(OmniBufDecoder dec, int depth) {
            if (depth > dec.maxNestingDepth()) {
                throw new OmniBufException("omnibuf: nesting depth exceeds MaxNestingDepth");
            }
            Object[] args = defaultRecordTemplate.clone();
            FieldDescriptorSlot singleSubSlot = null;
            int singleSubStart = 0;
            int singleSubEnd = 0;
            Map<Integer, RecordBuildState<?>> subRecordStates = null;
            Map<Integer, List<Number>> primArrayAccum = null;

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
                int compIdx = slot.recordComponentIndex;
                switch (slot.category) {
                    case INT_SCALAR -> args[compIdx] = decodeIntScalar(dec, wt);
                    case LONG_SCALAR -> args[compIdx] = decodeLongScalar(dec, wt);
                    case BOOL_SCALAR -> args[compIdx] = (wt == WireType.WIRE_BOOL_TRUE);
                    case FLOAT_SCALAR -> args[compIdx] = dec.readFloat32();
                    case DOUBLE_SCALAR -> args[compIdx] = dec.readFloat64();
                    case STRING_SCALAR -> args[compIdx] = dec.readString();
                    case BYTES_SCALAR -> args[compIdx] = dec.readBytes();
                    case ENUM_SCALAR -> args[compIdx] = slot.decodeEnumScalar(dec);
                    case INT_ARRAY, LONG_ARRAY -> {
                        if (primArrayAccum == null) {
                            primArrayAccum = new HashMap<>();
                        }
                        List<Number> acc = primArrayAccum.computeIfAbsent(compIdx, k -> new ArrayList<>());
                        decodePrimitiveArrayIntoList(dec, slot, acc, depth);
                    }
                    case LIST -> {
                        List<Object> list = (List<Object>) args[compIdx];
                        args[compIdx] = decodeListInto(dec, slot, list, depth);
                    }
                    case MAP -> {
                        Map<Object, Object> map = (Map<Object, Object>) args[compIdx];
                        args[compIdx] = decodeMapInto(dec, slot, map, depth);
                    }
                    case SUB_MSG -> {
                        if (!slot.subCodec.isRecord) {
                            int subSaved = dec.readLengthPrefixedLimit(0);
                            Object sub = args[compIdx];
                            if (sub == null) {
                                sub = slot.subCodec.newBlankMutableInstance();
                                args[compIdx] = sub;
                            }
                            slot.subCodec.decodeFrom(dec, sub, depth + 1);
                            dec.endContainer(subSaved);
                        } else if (singleSubSlot == null && subRecordStates == null) {
                            int subSaved = dec.readLengthPrefixedLimit(0);
                            singleSubSlot = slot;
                            singleSubStart = dec.position();
                            singleSubEnd = dec.limit();
                            dec.position(singleSubEnd);
                            dec.restoreLimit(subSaved);
                        } else {
                            if (subRecordStates == null) {
                                subRecordStates = new HashMap<>();
                            }
                            if (singleSubSlot != null) {
                                int savedPos = dec.position();
                                int savedLim = dec.setSlice(singleSubStart, singleSubEnd);
                                RecordBuildState<?> firstState = singleSubSlot.subCodec.newBlankRecordState();
                                firstState.applySlice(dec, depth + 1);
                                dec.endContainer(savedLim);
                                dec.position(savedPos);
                                subRecordStates.put(singleSubSlot.recordComponentIndex, firstState);
                                singleSubSlot = null;
                            }
                            int subSaved = dec.readLengthPrefixedLimit(0);
                            RecordBuildState<?> st = subRecordStates.computeIfAbsent(
                                compIdx, k -> slot.subCodec.newBlankRecordState()
                            );
                            st.applySlice(dec, depth + 1);
                            dec.endContainer(subSaved);
                        }
                    }
                }
            }

            if (primArrayAccum != null) {
                for (Map.Entry<Integer, List<Number>> entry : primArrayAccum.entrySet()) {
                    int compIdx = entry.getKey();
                    List<Number> nums = entry.getValue();
                    if (recordComponentsByIndex[compIdx].category == FieldCategory.INT_ARRAY) {
                        int[] arr = new int[nums.size()];
                        for (int i = 0; i < arr.length; i++) {
                            arr[i] = nums.get(i).intValue();
                        }
                        args[compIdx] = arr;
                    } else {
                        long[] arr = new long[nums.size()];
                        for (int i = 0; i < arr.length; i++) {
                            arr[i] = nums.get(i).longValue();
                        }
                        args[compIdx] = arr;
                    }
                }
            }

            if (singleSubSlot != null) {
                int savedPos = dec.position();
                int savedLim = dec.setSlice(singleSubStart, singleSubEnd);
                args[singleSubSlot.recordComponentIndex] =
                    singleSubSlot.subCodec.decodeRecordFrom(dec, depth + 1);
                dec.endContainer(savedLim);
                dec.position(savedPos);
            } else if (subRecordStates != null) {
                for (Map.Entry<Integer, RecordBuildState<?>> entry : subRecordStates.entrySet()) {
                    args[entry.getKey()] = entry.getValue().build();
                }
            }

            return instantiateRecord(args);
        }

        @SuppressWarnings("unchecked")
        void decodeFrom(OmniBufDecoder dec, Object target, int depth) {
            if (depth > dec.maxNestingDepth()) {
                throw new OmniBufException("omnibuf: nesting depth exceeds MaxNestingDepth");
            }
            FieldDescriptorSlot singleRecSlot = null;
            int singleRecStart = 0;
            int singleRecEnd = 0;
            Map<Integer, RecordBuildState<?>> recordSubStates = null;

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
                    case ENUM_SCALAR -> slot.writeObject(target, slot.decodeEnumScalar(dec));
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
                        List<Object> updated = decodeListInto(dec, slot, list, depth);
                        if (updated != list) {
                            slot.writeObject(target, updated);
                        }
                    }
                    case MAP -> {
                        Map<Object, Object> map = (Map<Object, Object>) slot.readObject(target);
                        Map<Object, Object> updated = decodeMapInto(dec, slot, map, depth);
                        if (updated != map) {
                            slot.writeObject(target, updated);
                        }
                    }
                    case SUB_MSG -> {
                        if (slot.subCodec.isRecord) {
                            Object existingRec = slot.readObject(target);
                            if (existingRec == null && singleRecSlot == null && recordSubStates == null) {
                                int subSaved = dec.readLengthPrefixedLimit(0);
                                singleRecSlot = slot;
                                singleRecStart = dec.position();
                                singleRecEnd = dec.limit();
                                dec.position(singleRecEnd);
                                dec.restoreLimit(subSaved);
                            } else {
                                if (recordSubStates == null) {
                                    recordSubStates = new HashMap<>();
                                }
                                if (singleRecSlot != null) {
                                    int savedPos = dec.position();
                                    int savedLim = dec.setSlice(singleRecStart, singleRecEnd);
                                    RecordBuildState<?> firstState = singleRecSlot.subCodec.newBlankRecordState();
                                    firstState.applySlice(dec, depth + 1);
                                    dec.endContainer(savedLim);
                                    dec.position(savedPos);
                                    recordSubStates.put(singleRecSlot.slotIndex, firstState);
                                    singleRecSlot = null;
                                }
                                RecordBuildState<?> st = recordSubStates.get(slot.slotIndex);
                                if (st == null) {
                                    st = existingRec != null
                                        ? slot.subCodec.newRecordStateFromInstance(existingRec)
                                        : slot.subCodec.newBlankRecordState();
                                    recordSubStates.put(slot.slotIndex, st);
                                }
                                int subSaved = dec.readLengthPrefixedLimit(0);
                                st.applySlice(dec, depth + 1);
                                dec.endContainer(subSaved);
                            }
                        } else {
                            int subSaved = dec.readLengthPrefixedLimit(0);
                            Object sub = slot.readObject(target);
                            if (sub == null) {
                                sub = slot.subCodec.newBlankMutableInstance();
                                slot.writeObject(target, sub);
                            }
                            slot.subCodec.decodeFrom(dec, sub, depth + 1);
                            dec.endContainer(subSaved);
                        }
                    }
                }
            }

            if (singleRecSlot != null) {
                int savedPos = dec.position();
                int savedLim = dec.setSlice(singleRecStart, singleRecEnd);
                singleRecSlot.writeObject(target, singleRecSlot.subCodec.decodeRecordFrom(dec, depth + 1));
                dec.endContainer(savedLim);
                dec.position(savedPos);
            } else if (recordSubStates != null) {
                for (Map.Entry<Integer, RecordBuildState<?>> entry : recordSubStates.entrySet()) {
                    orderedFields[entry.getKey()].writeObject(target, entry.getValue().build());
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
            case WireType.WIRE_FIXED32, WireType.WIRE_SFIXED32 -> enc.writeInlineFixed32(v);
            default -> throw new OmniBufException("omnibuf: unsupported int[] element wire type");
        }
    }

    private static void writeLongArrayElem(OmniBufEncoder enc, int elemWT, long v) {
        switch (elemWT) {
            case WireType.WIRE_UINT -> enc.writeVarint(v);
            case WireType.WIRE_SINT -> enc.writeVarint(PrefixVarint.encodeZigZag64(v));
            case WireType.WIRE_FIXED64, WireType.WIRE_SFIXED64 -> enc.writeInlineFixed64(v);
            default -> throw new OmniBufException("omnibuf: unsupported long[] element wire type");
        }
    }

    @SuppressWarnings("unchecked")
    private static void encodeScalarList(
            OmniBufEncoder enc, int fnum, int elemWT, Class<?> elemClass, List<Object> list) {
        if (elemClass != null && elemClass.isEnum()) {
            int n = list == null ? 0 : list.size();
            int arrPos = enc.beginTypedArrayFast(fnum, WireType.WIRE_SINT, n);
            for (int i = 0; i < n; i++) {
                Object elem = list.get(i);
                int ord = elem instanceof Enum<?> ev ? ev.ordinal() : 0;
                enc.writeVarint(PrefixVarint.encodeZigZag32(ord));
            }
            enc.endContainerAt(arrPos);
            return;
        }
        switch (elemWT) {
            case WireType.WIRE_STRING -> enc.writeStringList(fnum, (List<String>) (List<?>) list);
            case WireType.WIRE_BYTES -> enc.writeBytesList(fnum, (List<byte[]>) (List<?>) list);
            case WireType.WIRE_UINT -> {
                if (elemClass == Integer.class) {
                    enc.writeUint32List(fnum, (List<Integer>) (List<?>) list);
                } else {
                    enc.writeUint64List(fnum, (List<Long>) (List<?>) list);
                }
            }
            case WireType.WIRE_SINT -> {
                if (elemClass == Integer.class) {
                    enc.writeSint32List(fnum, (List<Integer>) (List<?>) list);
                } else {
                    enc.writeSint64List(fnum, (List<Long>) (List<?>) list);
                }
            }
            case WireType.WIRE_BOOL_FALSE -> enc.writeBoolList(fnum, (List<Boolean>) (List<?>) list);
            case WireType.WIRE_FIXED32, WireType.WIRE_SFIXED32 ->
                enc.writeFixed32List(fnum, elemWT, (List<Integer>) (List<?>) list);
            case WireType.WIRE_FIXED64, WireType.WIRE_SFIXED64 ->
                enc.writeFixed64List(fnum, elemWT, (List<Long>) (List<?>) list);
            case WireType.WIRE_FLOAT32 -> enc.writeFloat32List(fnum, (List<Float>) (List<?>) list);
            case WireType.WIRE_FLOAT64 -> enc.writeFloat64List(fnum, (List<Double>) (List<?>) list);
            default -> {
                int n = list == null ? 0 : list.size();
                int arrPos = enc.beginTypedArrayFast(fnum, elemWT, n);
                for (int i = 0; i < n; i++) {
                    writeInlineValue(enc, elemWT, null, list.get(i), false);
                }
                enc.endContainerAt(arrPos);
            }
        }
    }

    private static void encodeMapField(
            OmniBufEncoder enc,
            FieldDescriptorSlot slot,
            Map<Object, Object> map,
            boolean deterministic) {
        int n = map == null ? 0 : map.size();
        int mapPos = enc.beginTypedMapFast(slot.fieldNumber, slot.mapKeyWireType, slot.mapValWireType, n);
        if (n > 0) {
            for (Map.Entry<Object, Object> entry :
                    OmniBufEncoder.sortMapEntries(map, slot.mapKeyWireType, deterministic)) {
                writeInlineValue(enc, slot.mapKeyWireType, null, entry.getKey(), deterministic);
                writeInlineValue(enc, slot.mapValWireType, slot.subCodec, entry.getValue(), deterministic);
            }
        }
        enc.endContainerAt(mapPos);
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
                if (val instanceof Enum<?> ev) {
                    enc.writeVarint(PrefixVarint.encodeZigZag32(ev.ordinal()));
                } else if (val instanceof Integer iv) {
                    enc.writeVarint(PrefixVarint.encodeZigZag32(iv));
                } else if (val instanceof Number nv) {
                    enc.writeVarint(PrefixVarint.encodeZigZag64(nv.longValue()));
                } else {
                    enc.writeVarint(0L);
                }
            }
            case WireType.WIRE_BOOL_FALSE -> enc.writeRawByte(Boolean.TRUE.equals(val) ? 1 : 0);
            case WireType.WIRE_FIXED32, WireType.WIRE_SFIXED32 ->
                enc.writeInlineFixed32(val instanceof Number nv ? nv.intValue() : 0);
            case WireType.WIRE_FLOAT32 ->
                enc.writeInlineFixed32(Float.floatToRawIntBits(val instanceof Number nv ? nv.floatValue() : 0.0f));
            case WireType.WIRE_FIXED64, WireType.WIRE_SFIXED64 ->
                enc.writeInlineFixed64(val instanceof Number nv ? nv.longValue() : 0L);
            case WireType.WIRE_FLOAT64 ->
                enc.writeInlineFixed64(Double.doubleToRawLongBits(val instanceof Number nv ? nv.doubleValue() : 0.0d));
            case WireType.WIRE_STRING -> enc.writeStringElement(val == null ? "" : val.toString());
            case WireType.WIRE_BYTES -> enc.writeBytesElement(val instanceof byte[] b ? b : EMPTY_BYTES);
            case WireType.WIRE_SUB_MSG -> {
                int elemPos = enc.beginInlineSubMsgFast();
                if (val != null && subCodec != null) {
                    subCodec.encode(enc, val, deterministic);
                }
                enc.endContainerAt(elemPos);
            }
            default -> throw new OmniBufException("omnibuf: unsupported inline wire type: " + wt);
        }
    }

    private static void decodePrimitiveArrayIntoList(
            OmniBufDecoder dec, FieldDescriptorSlot slot, List<Number> target, int depth) {
        long arrState = dec.beginArray(slot.elemWireType, depth);
        if (arrState < 0L) {
            return;
        }
        int count = (int) arrState;
        int arrLimit = (int) (arrState >>> 32);
        if ((long) target.size() + count > dec.maxCollectionElements()) {
            throw new OmniBufException("omnibuf: accumulated WireArray count exceeds maxCollectionElements");
        }
        for (int i = 0; i < count; i++) {
            if (slot.category == FieldCategory.INT_ARRAY) {
                target.add(decodeIntScalar(dec, slot.elemWireType));
            } else {
                target.add(decodeLongScalar(dec, slot.elemWireType));
            }
        }
        dec.endContainer(arrLimit);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> decodeListInto(
            OmniBufDecoder dec, FieldDescriptorSlot slot, List<Object> target, int depth) {
        long arrState = dec.beginArray(slot.elemWireType, depth);
        if (arrState < 0L) {
            return target == null ? new ArrayList<>(0) : target;
        }
        int count = (int) arrState;
        int arrLimit = (int) (arrState >>> 32);
        int prevSize = target == null ? 0 : target.size();
        if ((long) prevSize + count > dec.maxCollectionElements()) {
            throw new OmniBufException("omnibuf: accumulated WireArray count exceeds maxCollectionElements");
        }
        int elemWT = slot.elemWireType;
        if (slot.canUsePrimitiveList) {
            if (slot.elemClass == Integer.class
                    && (elemWT == WireType.WIRE_UINT
                        || elemWT == WireType.WIRE_SINT
                        || elemWT == WireType.WIRE_FIXED32
                        || elemWT == WireType.WIRE_SFIXED32)) {
                IntArrayList il;
                if ((Object) target instanceof IntArrayList existing) {
                    il = existing;
                } else if (prevSize == 0) {
                    il = new IntArrayList(Math.min(count, 4096));
                } else {
                    il = new IntArrayList(prevSize + Math.min(count, 4096));
                    for (int i = 0; i < prevSize; i++) {
                        Object v = target.get(i);
                        il.addInt(v instanceof Number nv ? nv.intValue() : 0);
                    }
                }
                switch (elemWT) {
                    case WireType.WIRE_UINT -> dec.readUint32Into(il, count);
                    case WireType.WIRE_SINT -> dec.readSint32Into(il, count);
                    default -> dec.readFixed32Into(il, count);
                }
                dec.endContainer(arrLimit);
                return (List<Object>) (List<?>) il;
            }
            if (slot.elemClass == Long.class
                    && (elemWT == WireType.WIRE_UINT
                        || elemWT == WireType.WIRE_SINT
                        || elemWT == WireType.WIRE_FIXED64
                        || elemWT == WireType.WIRE_SFIXED64)) {
                LongArrayList ll;
                if ((Object) target instanceof LongArrayList existing) {
                    ll = existing;
                } else if (prevSize == 0) {
                    ll = new LongArrayList(Math.min(count, 4096));
                } else {
                    ll = new LongArrayList(prevSize + Math.min(count, 4096));
                    for (int i = 0; i < prevSize; i++) {
                        Object v = target.get(i);
                        ll.addLong(v instanceof Number nv ? nv.longValue() : 0L);
                    }
                }
                switch (elemWT) {
                    case WireType.WIRE_UINT -> dec.readUint64Into(ll, count);
                    case WireType.WIRE_SINT -> dec.readSint64Into(ll, count);
                    default -> dec.readFixed64Into(ll, count);
                }
                dec.endContainer(arrLimit);
                return (List<Object>) (List<?>) ll;
            }
        }
        List<Object> list = target;
        if (list == null) {
            list = new ArrayList<>(Math.min(count, 4096));
        } else if (!(list instanceof ArrayList<?>)) {
            ArrayList<Object> copy = new ArrayList<>(prevSize + Math.min(count, 4096));
            copy.addAll(list);
            list = copy;
        } else if (list.isEmpty() && count > 10 && count <= 4096) {
            ((ArrayList<?>) list).ensureCapacity(count);
        }
        if (elemWT == WireType.WIRE_STRING) {
            for (int i = 0; i < count; i++) {
                list.add(dec.readString());
            }
        } else if (elemWT == WireType.WIRE_SUB_MSG) {
            ClassCodec<?> subCodec = slot.subCodec;
            for (int i = 0; i < count; i++) {
                int elemLimit = dec.readLengthPrefixedLimit(0);
                list.add(subCodec.decodeNewFrom(dec, depth + 2));
                dec.endContainer(elemLimit);
            }
        } else {
            for (int i = 0; i < count; i++) {
                list.add(readInlineValue(dec, elemWT, slot.elemClass, slot.subCodec, depth + 1));
            }
        }
        dec.endContainer(arrLimit);
        return list;
    }

    private static Map<Object, Object> decodeMapInto(
            OmniBufDecoder dec, FieldDescriptorSlot slot, Map<Object, Object> target, int depth) {
        long mapState = dec.beginMap(slot.mapKeyWireType, slot.mapValWireType, depth);
        if (mapState < 0L) {
            return target == null ? new LinkedHashMap<>(4) : target;
        }
        int count = (int) mapState;
        int mapLimit = (int) (mapState >>> 32);
        int capHint = Math.min(count, 4096);
        Map<Object, Object> map = target != null
            ? target
            : new LinkedHashMap<>(Math.max(4, capHint + (capHint >>> 1) + 1));
        Class<?> keyClass = slot.mapKeyClass;
        Class<?> valClass = slot.elemClass;
        int maxElems = dec.maxCollectionElements();
        for (int i = 0; i < count; i++) {
            Object k = readInlineValue(dec, slot.mapKeyWireType, keyClass, null, depth + 1);
            Object v = readInlineValue(
                dec, slot.mapValWireType, valClass, slot.subCodec, depth + 1
            );
            if (!map.containsKey(k) && map.size() >= maxElems) {
                throw new OmniBufException("omnibuf: accumulated WireMap count exceeds maxCollectionElements");
            }
            map.put(k, v);
        }
        dec.endContainer(mapLimit);
        return map;
    }

    private static Object readInlineValue(
            OmniBufDecoder dec,
            int wt,
            Class<?> targetClass,
            ClassCodec<?> subCodec,
            int containerDepth) {
        return switch (wt) {
            case WireType.WIRE_UINT -> {
                if (targetClass == Integer.class) {
                    yield Integer.valueOf(dec.readUint32());
                }
                yield Long.valueOf(dec.readUint64());
            }
            case WireType.WIRE_SINT -> {
                if (targetClass != null && targetClass.isEnum()) {
                    int ord = dec.readSint32();
                    Object[] consts = targetClass.getEnumConstants();
                    if (consts == null || ord < 0 || ord >= consts.length) {
                        throw new OmniBufException(
                            "omnibuf: unknown enum ordinal " + ord + " for " + targetClass.getSimpleName()
                        );
                    }
                    yield consts[ord];
                }
                if (targetClass == Integer.class) {
                    yield Integer.valueOf(dec.readSint32());
                }
                yield Long.valueOf(dec.readSint64());
            }
            case WireType.WIRE_BOOL_FALSE -> dec.readContainerBool();
            case WireType.WIRE_FIXED32, WireType.WIRE_SFIXED32 -> dec.readFixed32();
            case WireType.WIRE_FLOAT32 -> dec.readFloat32();
            case WireType.WIRE_FIXED64, WireType.WIRE_SFIXED64 -> dec.readFixed64();
            case WireType.WIRE_FLOAT64 -> dec.readFloat64();
            case WireType.WIRE_STRING -> dec.readString();
            case WireType.WIRE_BYTES -> dec.readBytes();
            case WireType.WIRE_SUB_MSG -> {
                int elemLimit = dec.readLengthPrefixedLimit(0);
                Object sub = subCodec.decodeNewFrom(dec, containerDepth + 1);
                dec.endContainer(elemLimit);
                yield sub;
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
        if (type.isPrimitive() || type.isArray() || type.isInterface() || type.isEnum()) {
            throw new OmniBufException("omnibuf: invalid message type " + type.getName());
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
                FieldDescriptorSlot[] byCompIdx = new FieldDescriptorSlot[comps.length];
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
                    AccessorHandles handles = new AccessorHandles(rc.getType(), getter, null);
                    FieldDescriptorSlot slot = buildSlot(
                        i,
                        rc.getName(),
                        rc.getType(),
                        rc.getGenericType(),
                        ann,
                        handles,
                        seenFieldNums,
                        building
                    );
                    byCompIdx[i] = slot;
                    slots.add(slot);
                }
                codec.recordComponentsByIndex = byCompIdx;
                Constructor<T> canonical = type.getDeclaredConstructor(paramTypes);
                canonical.setAccessible(true);
                codec.constructorHandle = lookup.unreflectConstructor(canonical);
                codec.recordSpreaderHandle = codec.constructorHandle
                    .asSpreader(Object[].class, paramTypes.length)
                    .asType(MethodType.methodType(Object.class, Object[].class));
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
                    f.setAccessible(true);
                    MethodHandle rawGetter = lookup.unreflectGetter(f);
                    MethodHandle rawSetter = lookup.unreflectSetter(f);
                    AccessorHandles handles = new AccessorHandles(f.getType(), rawGetter, rawSetter);
                    FieldDescriptorSlot slot = buildSlot(
                        -1,
                        f.getName(),
                        f.getType(),
                        f.getGenericType(),
                        ann,
                        handles,
                        seenFieldNums,
                        building
                    );
                    slots.add(slot);
                }
            }

            // Always sort orderedFields by ascending wire field number so encoding and SchemaMeta
            // emit canonical ascending field numbers regardless of Java declaration order.
            slots.sort(Comparator.comparingInt(s -> s.fieldNumber));
            for (int i = 0; i < slots.size(); i++) {
                slots.get(i).slotIndex = i;
            }

            codec.orderedFields = slots.toArray(FieldDescriptorSlot[]::new);
            List<FieldDescriptorSlot> largeCands = new ArrayList<>();
            for (FieldDescriptorSlot slot : codec.orderedFields) {
                if (slot.category == FieldCategory.STRING_SCALAR
                        || slot.category == FieldCategory.BYTES_SCALAR
                        || slot.category == FieldCategory.SUB_MSG
                        || (slot.category == FieldCategory.LIST && slot.elemWireType == WireType.WIRE_SUB_MSG)) {
                    largeCands.add(slot);
                }
            }
            codec.largeCandidateSlots = largeCands.toArray(FieldDescriptorSlot[]::new);
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

            if (isRecord) {
                int arity = codec.recordComponentsByIndex.length;
                Object[] tmpl = new Object[arity];
                boolean hasColls = false;
                for (int i = 0; i < arity; i++) {
                    FieldDescriptorSlot s = codec.recordComponentsByIndex[i];
                    tmpl[i] = s.immutableRecordDefault();
                    if (s.category == FieldCategory.LIST || s.category == FieldCategory.MAP) {
                        hasColls = true;
                    }
                }
                codec.defaultRecordTemplate = tmpl;
                codec.hasCollectionComponents = hasColls;
            } else {
                Object probe = codec.constructorHandle.invokeExact();
                codec.cleanFreshConstructor = isFreshInstanceClean(probe, codec.orderedFields);
            }

            return codec;
        } catch (OmniBufException e) {
            throw e;
        } catch (Throwable t) {
            throw new OmniBufException("omnibuf: failed compiling codec for " + type.getName(), t);
        }
    }

    private static boolean isFreshInstanceClean(Object probe, FieldDescriptorSlot[] slots) {
        for (FieldDescriptorSlot slot : slots) {
            switch (slot.category) {
                case INT_SCALAR -> {
                    if (slot.readInt(probe) != 0 || (slot.rawType != int.class && slot.readObject(probe) == null)) {
                        return false;
                    }
                }
                case LONG_SCALAR -> {
                    if (slot.readLong(probe) != 0L || (slot.rawType != long.class && slot.readObject(probe) == null)) {
                        return false;
                    }
                }
                case BOOL_SCALAR -> {
                    if (slot.readBoolean(probe) || (slot.rawType != boolean.class && slot.readObject(probe) == null)) {
                        return false;
                    }
                }
                case FLOAT_SCALAR -> {
                    if (Float.floatToRawIntBits(slot.readFloat(probe)) != 0
                            || (slot.rawType != float.class && slot.readObject(probe) == null)) {
                        return false;
                    }
                }
                case DOUBLE_SCALAR -> {
                    if (Double.doubleToRawLongBits(slot.readDouble(probe)) != 0L
                            || (slot.rawType != double.class && slot.readObject(probe) == null)) {
                        return false;
                    }
                }
                case STRING_SCALAR -> {
                    if (!"".equals(slot.readObject(probe))) {
                        return false;
                    }
                }
                case BYTES_SCALAR -> {
                    if (!(slot.readObject(probe) instanceof byte[] b) || b.length != 0) {
                        return false;
                    }
                }
                case ENUM_SCALAR -> {
                    if (slot.readObject(probe) != slot.defaultEnumValue()) {
                        return false;
                    }
                }
                case INT_ARRAY -> {
                    if (!(slot.readObject(probe) instanceof int[] a) || a.length != 0) {
                        return false;
                    }
                }
                case LONG_ARRAY -> {
                    if (!(slot.readObject(probe) instanceof long[] a) || a.length != 0) {
                        return false;
                    }
                }
                case LIST -> {
                    if (!(slot.readObject(probe) instanceof ArrayList<?> al) || !al.isEmpty()) {
                        return false;
                    }
                }
                case MAP -> {
                    if (!(slot.readObject(probe) instanceof LinkedHashMap<?, ?> lhm) || !lhm.isEmpty()) {
                        return false;
                    }
                }
                case SUB_MSG -> {
                    if (slot.readObject(probe) != null) {
                        return false;
                    }
                }
            }
        }
        return true;
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

    private static void ensureNonMapFieldOptions(OmniBufField ann, String javaName) {
        if (ann.keyWire() != WireKind.AUTO || ann.valueWire() != WireKind.AUTO) {
            throw new OmniBufException(
                "omnibuf: keyWire and valueWire are only supported on Map fields (" + javaName + ")"
            );
        }
    }

    private static FieldDescriptorSlot buildSlot(
            int recordComponentIndex,
            String javaName,
            Class<?> rawType,
            Type genericType,
            OmniBufField ann,
            AccessorHandles handles,
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

        if (!Map.class.isAssignableFrom(rawType)) {
            ensureNonMapFieldOptions(ann, javaName);
        }

        if (rawType == int.class || rawType == Integer.class) {
            int wt = resolveScalarWire(rawType, ann.wire(), javaName);
            return new FieldDescriptorSlot(
                recordComponentIndex, id, schemaName, FieldCategory.INT_SCALAR, rawType,
                wt, 0, 0, 0, null, null, handles
            );
        }
        if (rawType == long.class || rawType == Long.class) {
            int wt = resolveScalarWire(rawType, ann.wire(), javaName);
            return new FieldDescriptorSlot(
                recordComponentIndex, id, schemaName, FieldCategory.LONG_SCALAR, rawType,
                wt, 0, 0, 0, null, null, handles
            );
        }
        if (rawType == boolean.class || rawType == Boolean.class) {
            int wt = resolveScalarWire(rawType, ann.wire(), javaName);
            return new FieldDescriptorSlot(
                recordComponentIndex, id, schemaName, FieldCategory.BOOL_SCALAR, rawType,
                wt, 0, 0, 0, null, null, handles
            );
        }
        if (rawType == float.class || rawType == Float.class) {
            int wt = resolveScalarWire(rawType, ann.wire(), javaName);
            return new FieldDescriptorSlot(
                recordComponentIndex, id, schemaName, FieldCategory.FLOAT_SCALAR, rawType,
                wt, 0, 0, 0, null, null, handles
            );
        }
        if (rawType == double.class || rawType == Double.class) {
            int wt = resolveScalarWire(rawType, ann.wire(), javaName);
            return new FieldDescriptorSlot(
                recordComponentIndex, id, schemaName, FieldCategory.DOUBLE_SCALAR, rawType,
                wt, 0, 0, 0, null, null, handles
            );
        }
        if (rawType == String.class) {
            int wt = resolveScalarWire(rawType, ann.wire(), javaName);
            return new FieldDescriptorSlot(
                recordComponentIndex, id, schemaName, FieldCategory.STRING_SCALAR, rawType,
                wt, 0, 0, 0, null, null, handles
            );
        }
        if (rawType == byte[].class) {
            int wt = resolveScalarWire(rawType, ann.wire(), javaName);
            return new FieldDescriptorSlot(
                recordComponentIndex, id, schemaName, FieldCategory.BYTES_SCALAR, rawType,
                wt, 0, 0, 0, null, null, handles
            );
        }
        if (rawType.isEnum()) {
            int wt = resolveScalarWire(rawType, ann.wire(), javaName);
            return new FieldDescriptorSlot(
                recordComponentIndex, id, schemaName, FieldCategory.ENUM_SCALAR, rawType,
                wt, 0, 0, 0, null, null, handles
            );
        }
        if (rawType == int[].class) {
            int elemWT = resolveScalarWire(int.class, ann.wire(), javaName);
            return new FieldDescriptorSlot(
                recordComponentIndex, id, schemaName, FieldCategory.INT_ARRAY, rawType,
                WireType.WIRE_ARRAY, elemWT, 0, 0, null, Integer.class, handles
            );
        }
        if (rawType == long[].class) {
            int elemWT = resolveScalarWire(long.class, ann.wire(), javaName);
            return new FieldDescriptorSlot(
                recordComponentIndex, id, schemaName, FieldCategory.LONG_ARRAY, rawType,
                WireType.WIRE_ARRAY, elemWT, 0, 0, null, Long.class, handles
            );
        }
        if (List.class.isAssignableFrom(rawType)) {
            Class<?> elemClass = extractTypeArgument(genericType, 0, javaName);
            if (isScalarBytesOrEnumClass(elemClass)) {
                int elemWT = resolveContainerElementWire(elemClass, ann.wire(), javaName);
                return new FieldDescriptorSlot(
                    recordComponentIndex, id, schemaName, FieldCategory.LIST, rawType,
                    WireType.WIRE_ARRAY, elemWT, 0, 0, null, elemClass, handles
                );
            }
            if (ann.wire() != WireKind.AUTO && ann.wire() != WireKind.SUB_MSG) {
                throw new OmniBufException(
                    "omnibuf: incompatible WireKind " + ann.wire() + " for sub-message List on " + javaName
                );
            }
            FieldDescriptorSlot slot = new FieldDescriptorSlot(
                recordComponentIndex, id, schemaName, FieldCategory.LIST, rawType,
                WireType.WIRE_ARRAY, WireType.WIRE_SUB_MSG, 0, 0, null, elemClass, handles
            );
            slot.subCodec = compileCodec(elemClass, building);
            return slot;
        }
        if (Map.class.isAssignableFrom(rawType)) {
            if (ann.wire() != WireKind.AUTO
                    && ann.valueWire() != WireKind.AUTO
                    && ann.wire() != ann.valueWire()) {
                throw new OmniBufException(
                    "omnibuf: conflicting wire (" + ann.wire() + ") and valueWire (" + ann.valueWire()
                        + ") on Map field " + javaName
                );
            }
            Class<?> keyClass = extractTypeArgument(genericType, 0, javaName);
            Class<?> valClass = extractTypeArgument(genericType, 1, javaName);
            int keyWT = resolveContainerElementWire(keyClass, ann.keyWire(), javaName);
            if (!WireType.isValidMapKeyWireType(keyWT)) {
                throw new OmniBufException("omnibuf: invalid map key wire type on " + javaName);
            }
            WireKind vk = ann.valueWire() != WireKind.AUTO ? ann.valueWire() : ann.wire();
            int valWT;
            ClassCodec<?> subCodec = null;
            if (isScalarBytesOrEnumClass(valClass)) {
                valWT = resolveContainerElementWire(valClass, vk, javaName);
            } else {
                if (vk != WireKind.AUTO && vk != WireKind.SUB_MSG) {
                    throw new OmniBufException(
                        "omnibuf: incompatible value WireKind " + vk + " for sub-message Map on " + javaName
                    );
                }
                valWT = WireType.WIRE_SUB_MSG;
                subCodec = compileCodec(valClass, building);
            }
            FieldDescriptorSlot slot = new FieldDescriptorSlot(
                recordComponentIndex, id, schemaName, FieldCategory.MAP, rawType,
                WireType.WIRE_MAP, 0, keyWT, valWT, keyClass, valClass, handles
            );
            slot.subCodec = subCodec;
            return slot;
        }

        // Nested sub-message class or record
        if (ann.wire() != WireKind.AUTO && ann.wire() != WireKind.SUB_MSG) {
            throw new OmniBufException(
                "omnibuf: incompatible WireKind " + ann.wire() + " for sub-message field " + javaName
            );
        }
        FieldDescriptorSlot slot = new FieldDescriptorSlot(
            recordComponentIndex, id, schemaName, FieldCategory.SUB_MSG, rawType,
            WireType.WIRE_SUB_MSG, 0, 0, 0, null, rawType, handles
        );
        slot.subCodec = compileCodec(rawType, building);
        return slot;
    }

    private static boolean isScalarBytesOrEnumClass(Class<?> c) {
        return c == Integer.class
            || c == Long.class
            || c == Boolean.class
            || c == Float.class
            || c == Double.class
            || c == String.class
            || c == byte[].class
            || c.isEnum();
    }

    private static int resolveScalarWire(Class<?> c, WireKind kind, String fieldName) {
        if (c == int.class || c == Integer.class) {
            return switch (kind) {
                case AUTO, UINT -> WireType.WIRE_UINT;
                case SINT -> WireType.WIRE_SINT;
                case FIXED32 -> WireType.WIRE_FIXED32;
                case SFIXED32 -> WireType.WIRE_SFIXED32;
                default -> throw new OmniBufException(
                    "omnibuf: incompatible WireKind " + kind + " for int on " + fieldName
                );
            };
        }
        if (c == long.class || c == Long.class) {
            return switch (kind) {
                case AUTO, UINT -> WireType.WIRE_UINT;
                case SINT -> WireType.WIRE_SINT;
                case FIXED64 -> WireType.WIRE_FIXED64;
                case SFIXED64 -> WireType.WIRE_SFIXED64;
                default -> throw new OmniBufException(
                    "omnibuf: incompatible WireKind " + kind + " for long on " + fieldName
                );
            };
        }
        if (c == boolean.class || c == Boolean.class) {
            if (kind == WireKind.AUTO || kind == WireKind.BOOL) {
                return WireType.WIRE_BOOL_FALSE;
            }
            throw new OmniBufException(
                "omnibuf: incompatible WireKind " + kind + " for boolean on " + fieldName
            );
        }
        if (c == float.class || c == Float.class) {
            if (kind == WireKind.AUTO || kind == WireKind.FLOAT32) {
                return WireType.WIRE_FLOAT32;
            }
            throw new OmniBufException(
                "omnibuf: incompatible WireKind " + kind + " for float on " + fieldName
            );
        }
        if (c == double.class || c == Double.class) {
            if (kind == WireKind.AUTO || kind == WireKind.FLOAT64) {
                return WireType.WIRE_FLOAT64;
            }
            throw new OmniBufException(
                "omnibuf: incompatible WireKind " + kind + " for double on " + fieldName
            );
        }
        if (c == String.class) {
            if (kind == WireKind.AUTO || kind == WireKind.STRING) {
                return WireType.WIRE_STRING;
            }
            throw new OmniBufException(
                "omnibuf: incompatible WireKind " + kind + " for String on " + fieldName
            );
        }
        if (c == byte[].class) {
            if (kind == WireKind.AUTO || kind == WireKind.BYTES) {
                return WireType.WIRE_BYTES;
            }
            throw new OmniBufException(
                "omnibuf: incompatible WireKind " + kind + " for byte[] on " + fieldName
            );
        }
        if (c.isEnum()) {
            if (kind == WireKind.AUTO || kind == WireKind.SINT) {
                return WireType.WIRE_SINT;
            }
            throw new OmniBufException(
                "omnibuf: incompatible WireKind " + kind + " for Enum " + c.getSimpleName() + " on " + fieldName
            );
        }
        throw new OmniBufException("omnibuf: unsupported scalar type " + c + " on " + fieldName);
    }

    private static int resolveContainerElementWire(Class<?> c, WireKind kind, String fieldName) {
        if (isScalarBytesOrEnumClass(c)) {
            return resolveScalarWire(c, kind, fieldName);
        }
        throw new OmniBufException("omnibuf: unsupported container element type " + c + " on " + fieldName);
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
