package com.omnibuf;

import com.omnibuf.wire.CodecPool;
import com.omnibuf.wire.OmniBufDecoder;
import com.omnibuf.wire.OmniBufEncoder;
import com.omnibuf.wire.PrefixVarint;
import com.omnibuf.wire.Utf8Validator;
import com.omnibuf.wire.WireType;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Self-describing schema metadata stored in OmniBuf Field #0 ({@code Tag = 0x19}).
 *
 * <pre>
 * message SchemaMeta {
 *   string type_name                  = 1; // WireString (10)
 *   map&lt;uint32, string&gt; fields        = 2; // WireMap (14): WireUint -&gt; WireString
 *   map&lt;uint32, SchemaMeta&gt; children  = 3; // WireMap (14): WireUint -&gt; WireSubMsg
 * }
 * </pre>
 */
public final class SchemaMeta {

    private final String typeName;
    private final Map<Integer, String> fields;
    private final Map<Integer, SchemaMeta> children;

    public SchemaMeta(String typeName, Map<Integer, String> fields, Map<Integer, SchemaMeta> children) {
        this.typeName = typeName == null ? "" : typeName;
        this.fields = fields == null || fields.isEmpty()
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        this.children = children == null || children.isEmpty()
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<>(children));
    }

    public String typeName() {
        return typeName;
    }

    public Map<Integer, String> fields() {
        return fields;
    }

    public Map<Integer, SchemaMeta> children() {
        return children;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof SchemaMeta other)) {
            return false;
        }
        return typeName.equals(other.typeName)
            && fields.equals(other.fields)
            && children.equals(other.children);
    }

    @Override
    public int hashCode() {
        int result = typeName.hashCode();
        result = 31 * result + fields.hashCode();
        result = 31 * result + children.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "SchemaMeta{typeName='" + typeName + "', fields=" + fields + ", children=" + children + "}";
    }

    /**
     * Merges {@code base} and {@code override} following concatenation last-write-wins semantics.
     */
    public static SchemaMeta merge(SchemaMeta base, SchemaMeta override) {
        if (base == null) {
            return override;
        }
        if (override == null) {
            return base;
        }
        String mergedType = !override.typeName.isEmpty() ? override.typeName : base.typeName;
        Map<Integer, String> mergedFields = new LinkedHashMap<>(base.fields);
        mergedFields.putAll(override.fields);

        Map<Integer, SchemaMeta> mergedChildren = new LinkedHashMap<>(base.children);
        for (Map.Entry<Integer, SchemaMeta> entry : override.children.entrySet()) {
            Integer fnum = entry.getKey();
            SchemaMeta existing = mergedChildren.get(fnum);
            mergedChildren.put(fnum, existing == null ? entry.getValue() : merge(existing, entry.getValue()));
        }
        return new SchemaMeta(mergedType, mergedFields, mergedChildren);
    }

    /**
     * Encodes this {@link SchemaMeta} as a standalone Field #0 header ({@code 0x19 + len + body}).
     */
    public byte[] encodeField0(boolean deterministic) {
        OmniBufEncoder enc = CodecPool.acquireEncoder();
        try {
            writeField0(enc, deterministic);
            return enc.toByteArray();
        } finally {
            CodecPool.releaseEncoder(enc);
        }
    }

    /**
     * Writes Field #0 ({@code tag = (0 << 4) | WIRE_SUB_MSG}) into {@code enc}.
     */
    public void writeField0(OmniBufEncoder enc, boolean deterministic) {
        enc.beginSubMsg(0);
        writeBody(enc, deterministic);
        enc.endSubMsg();
    }

    private void writeBody(OmniBufEncoder enc, boolean deterministic) {
        if (!typeName.isEmpty()) {
            enc.writeString(1, typeName);
        }
        if (!fields.isEmpty()) {
            Map<Integer, String> iter = deterministic ? new TreeMap<>(fields) : fields;
            enc.beginTypedMap(2, WireType.WIRE_UINT, WireType.WIRE_STRING, iter.size());
            for (Map.Entry<Integer, String> entry : iter.entrySet()) {
                int k = entry.getKey();
                if (k <= 0 || k > WireType.MAX_FIELD_NUMBER) {
                    throw new OmniBufException("omnibuf: invalid SchemaMeta field number: " + k);
                }
                enc.writeVarint(Integer.toUnsignedLong(k));
                enc.writeStringElement(entry.getValue());
            }
            enc.endTypedMap();
        }
        if (!children.isEmpty()) {
            Map<Integer, SchemaMeta> iter = deterministic ? new TreeMap<>(children) : children;
            enc.beginTypedMap(3, WireType.WIRE_UINT, WireType.WIRE_SUB_MSG, iter.size());
            for (Map.Entry<Integer, SchemaMeta> entry : iter.entrySet()) {
                int k = entry.getKey();
                if (k <= 0 || k > WireType.MAX_FIELD_NUMBER) {
                    throw new OmniBufException("omnibuf: invalid SchemaMeta child field number: " + k);
                }
                enc.writeVarint(Integer.toUnsignedLong(k));
                enc.beginInlineSubMsg();
                entry.getValue().writeBody(enc, deterministic);
                enc.endInlineSubMsg();
            }
            enc.endTypedMap();
        }
    }

    /**
     * Scans {@code packet} for all top-level Field #0 {@link SchemaMeta} blocks and returns
     * their merged schema, or {@link Optional#empty()} if Field #0 is absent or packet is malformed.
     */
    public static Optional<SchemaMeta> readField0(byte[] packet) {
        if (packet == null || packet.length == 0) {
            return Optional.empty();
        }
        try {
            SchemaMeta merged = null;
            int pos = 0;
            int end = packet.length;
            while (pos < end) {
                PrefixVarint.Decoded tagDec = PrefixVarint.decode(packet, pos, end);
                long tag = tagDec.value();
                long fnum = tag >>> 4;
                if (Long.compareUnsigned(fnum, WireType.MAX_FIELD_NUMBER) > 0) {
                    return Optional.empty();
                }
                int wt = (int) (tag & 0x0FL);
                pos += tagDec.bytesRead();
                if (fnum == 0L) {
                    if (wt != WireType.WIRE_SUB_MSG) {
                        return Optional.empty();
                    }
                    OmniBufDecoder.Slice s = OmniBufDecoder.readLengthSlice(packet, pos, end, 0);
                    SchemaMeta m = decode(packet, s.offset(), s.length(), 1);
                    merged = merge(merged, m);
                    pos = s.end();
                } else {
                    int next = OmniBufDecoder.tryFastSkipValue(packet, pos, end, wt);
                    if (next < 0) {
                        return Optional.empty();
                    }
                    pos = next;
                }
            }
            return Optional.ofNullable(merged);
        } catch (OmniBufException e) {
            return Optional.empty();
        }
    }

    /**
     * Decodes a {@link SchemaMeta} body slice at {@code data[offset..offset+length]}.
     */
    public static SchemaMeta decode(byte[] data, int offset, int length, int depth) {
        return decode(
            data,
            offset,
            length,
            depth,
            OmniBufDecoder.MAX_CONTAINER_DEPTH,
            UnmarshalOptions.DEFAULT_MAX_COLLECTION_ELEMENTS
        );
    }

    public static SchemaMeta decode(
            byte[] data,
            int offset,
            int length,
            int depth,
            int maxNestingDepth,
            int maxCollectionElements) {
        if (data == null || offset < 0 || length < 0 || length > data.length - offset) {
            throw new OmniBufException("omnibuf: SchemaMeta slice out of bounds");
        }
        if (depth > maxNestingDepth) {
            throw new OmniBufException(
                "omnibuf: SchemaMeta nesting depth " + depth + " exceeds MaxNestingDepth (" + maxNestingDepth + ")"
            );
        }
        String typeName = "";
        Map<Integer, String> fields = null;
        Map<Integer, SchemaMeta> children = null;

        int pos = offset;
        int end = offset + length;
        while (pos < end) {
            PrefixVarint.Decoded tagDec = PrefixVarint.decode(data, pos, end);
            long tag = tagDec.value();
            long fnum64 = tag >>> 4;
            if (Long.compareUnsigned(fnum64, WireType.MAX_FIELD_NUMBER) > 0) {
                throw new OmniBufException("omnibuf: invalid tag in Field #0 SchemaMeta");
            }
            pos += tagDec.bytesRead();
            int fnum = (int) fnum64;
            int wt = (int) (tag & 0x0FL);

            switch (fnum) {
                case 0 -> throw new OmniBufException("omnibuf: invalid recursive Field #0 inside SchemaMeta");
                case 1 -> {
                    if (wt != WireType.WIRE_STRING) {
                        throw new OmniBufException("omnibuf: SchemaMeta field #1 must be WireString");
                    }
                    OmniBufDecoder.Slice s = OmniBufDecoder.readLengthSlice(data, pos, end, 0);
                    typeName = Utf8Validator.decodeStrict(data, s.offset(), s.length());
                    pos = s.end();
                }
                case 2 -> {
                    if (wt != WireType.WIRE_MAP) {
                        throw new OmniBufException("omnibuf: SchemaMeta field #2 must be WireMap");
                    }
                    if (depth + 1 > maxNestingDepth) {
                        throw new OmniBufException("omnibuf: SchemaMeta fields map exceeds MaxNestingDepth");
                    }
                    OmniBufDecoder.Slice mapSlice = OmniBufDecoder.readLengthSlice(data, pos, end, 3);
                    pos = mapSlice.end();
                    OmniBufDecoder.MapHeader hdr = OmniBufDecoder.parseMapHeader(
                        data, mapSlice.offset(), mapSlice.end(), maxCollectionElements
                    );
                    if (hdr.keyWireType() != WireType.WIRE_UINT
                            || hdr.valWireType() != WireType.WIRE_STRING) {
                        throw new OmniBufException("omnibuf: SchemaMeta fields map requires uint->string");
                    }
                    if (fields == null) {
                        fields = new LinkedHashMap<>(Math.min(hdr.count(), 4096));
                    }
                    int p = hdr.payloadStart();
                    int mapEnd = hdr.payloadEnd();
                    for (int i = 0; i < hdr.count(); i++) {
                        PrefixVarint.Decoded kd = PrefixVarint.decode(data, p, mapEnd);
                        long k64 = kd.value();
                        if (k64 == 0L || Long.compareUnsigned(k64, WireType.MAX_FIELD_NUMBER) > 0) {
                            throw new OmniBufException("omnibuf: invalid field number in SchemaMeta: " + k64);
                        }
                        p += kd.bytesRead();
                        OmniBufDecoder.Slice ns = OmniBufDecoder.readLengthSlice(data, p, mapEnd, 0);
                        String fieldName = Utf8Validator.decodeStrict(data, ns.offset(), ns.length());
                        int key = (int) k64;
                        if (!fields.containsKey(key) && fields.size() >= maxCollectionElements) {
                            throw new OmniBufException(
                                "omnibuf: accumulated WireMap count exceeds maxCollectionElements"
                            );
                        }
                        fields.put(key, fieldName);
                        p = ns.end();
                    }
                    if (p != mapEnd) {
                        throw new OmniBufException("omnibuf: SchemaMeta fields map has trailing bytes");
                    }
                }
                case 3 -> {
                    if (wt != WireType.WIRE_MAP) {
                        throw new OmniBufException("omnibuf: SchemaMeta field #3 must be WireMap");
                    }
                    if (depth + 1 > maxNestingDepth) {
                        throw new OmniBufException("omnibuf: SchemaMeta children map exceeds MaxNestingDepth");
                    }
                    OmniBufDecoder.Slice mapSlice = OmniBufDecoder.readLengthSlice(data, pos, end, 3);
                    pos = mapSlice.end();
                    OmniBufDecoder.MapHeader hdr = OmniBufDecoder.parseMapHeader(
                        data, mapSlice.offset(), mapSlice.end(), maxCollectionElements
                    );
                    if (hdr.keyWireType() != WireType.WIRE_UINT
                            || hdr.valWireType() != WireType.WIRE_SUB_MSG) {
                        throw new OmniBufException("omnibuf: SchemaMeta children map requires uint->submsg");
                    }
                    // Within a single WireMap (Spec §6.6), duplicate keys replace the earlier entry;
                    // across multiple Field #3 occurrences or multiple Field #0 blocks (Spec §8.2),
                    // child SchemaMeta entries merge recursively.
                    Map<Integer, SchemaMeta> mapChildren =
                        hdr.count() == 0 ? null : new LinkedHashMap<>(Math.min(hdr.count(), 4096));
                    int p = hdr.payloadStart();
                    int mapEnd = hdr.payloadEnd();
                    for (int i = 0; i < hdr.count(); i++) {
                        PrefixVarint.Decoded kd = PrefixVarint.decode(data, p, mapEnd);
                        long k64 = kd.value();
                        if (k64 == 0L || Long.compareUnsigned(k64, WireType.MAX_FIELD_NUMBER) > 0) {
                            throw new OmniBufException("omnibuf: invalid child field number in SchemaMeta: " + k64);
                        }
                        p += kd.bytesRead();
                        OmniBufDecoder.Slice cs = OmniBufDecoder.readLengthSlice(data, p, mapEnd, 0);
                        SchemaMeta childMeta = decode(
                            data, cs.offset(), cs.length(), depth + 2, maxNestingDepth, maxCollectionElements
                        );
                        mapChildren.put((int) k64, childMeta);
                        p = cs.end();
                    }
                    if (p != mapEnd) {
                        throw new OmniBufException("omnibuf: SchemaMeta children map has trailing bytes");
                    }
                    if (mapChildren != null) {
                        if (children == null) {
                            children = mapChildren;
                        } else {
                            for (Map.Entry<Integer, SchemaMeta> entry : mapChildren.entrySet()) {
                                int key = entry.getKey();
                                if (!children.containsKey(key) && children.size() >= maxCollectionElements) {
                                    throw new OmniBufException(
                                        "omnibuf: accumulated WireMap count exceeds maxCollectionElements"
                                    );
                                }
                                children.put(key, merge(children.get(key), entry.getValue()));
                            }
                        }
                    }
                }
                default -> pos = OmniBufDecoder.validateValue(
                    data, pos, end, wt, depth, maxNestingDepth, maxCollectionElements
                );
            }
        }
        return new SchemaMeta(typeName, fields, children);
    }
}
