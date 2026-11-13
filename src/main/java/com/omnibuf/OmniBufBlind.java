package com.omnibuf;

import com.omnibuf.wire.OmniBufDecoder;
import com.omnibuf.wire.PrefixVarint;
import com.omnibuf.wire.Utf8Validator;
import com.omnibuf.wire.WireType;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Schema-less ({@code TIER_BLIND}) decoder and canonical JSON formatter for OmniBuf packets.
 *
 * <p>Resolves human-readable field names from embedded Field #0 {@link SchemaMeta} headers when
 * present, falling back to {@code "field_<N>"} for unnamed fields.
 */
public final class OmniBufBlind {

    private static final byte[] EMPTY_BYTES = new byte[0];

    private record BlindSchema(SchemaMeta local, BlindSchema parent) {
        static BlindSchema push(BlindSchema inherited, SchemaMeta local) {
            if (local == null) {
                return inherited;
            }
            return new BlindSchema(local, inherited);
        }

        String resolveName(int fieldNum) {
            for (BlindSchema cur = this; cur != null; cur = cur.parent) {
                String name = cur.local.fields().get(fieldNum);
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
            return "field_" + fieldNum;
        }

        BlindSchema childSchema(int fieldNum) {
            BlindSchema parentChild = parent != null ? parent.childSchema(fieldNum) : null;
            SchemaMeta localChild = local != null ? local.children().get(fieldNum) : null;
            return push(parentChild, localChild);
        }
    }

    private OmniBufBlind() {}

    /**
     * Decodes {@code packet} without an external schema into an ordered {@code Map<String, Object>}.
     * Unsigned 64-bit values ({@code WireUint}, {@code WireFixed64}) are represented as
     * {@link UnsignedLong}.
     */
    public static Map<String, Object> decodeBlind(byte[] packet) {
        if (packet == null) {
            throw new OmniBufException("omnibuf: null packet buffer");
        }
        return decodeBlindSlice(packet, 0, packet.length, null, 0);
    }

    /**
     * Decodes {@code packet} into canonical deterministic JSON. Returns {@code "{}"} if the
     * packet is structurally valid but contains non-finite IEEE-754 {@code NaN} or {@code Infinity}
     * floats that have no RFC 8259 JSON representation.
     */
    public static String decodeBlindJson(byte[] packet) {
        return decodeBlindJson(packet, true);
    }

    public static String decodeBlindJson(byte[] packet, boolean fallbackOnNonFinite) {
        Map<String, Object> map = decodeBlind(packet);
        if (hasNonFiniteFloat(map)) {
            if (fallbackOnNonFinite) {
                return "{}";
            }
            throw new OmniBufException("omnibuf: non-finite float cannot be represented in JSON");
        }
        StringBuilder sb = new StringBuilder(Math.max(32, packet.length * 2));
        writeJsonValue(sb, map);
        return sb.toString();
    }

    private static Map<String, Object> decodeBlindSlice(
            byte[] data, int offset, int length, BlindSchema inherited, int depth) {
        if (depth > OmniBufDecoder.MAX_CONTAINER_DEPTH) {
            throw new OmniBufException(
                "omnibuf: blind decode depth " + depth + " exceeds MaxNestingDepth"
            );
        }

        int end = offset + length;

        // Pass 1: Scan top-level headers to validate framing and merge all Field #0 SchemaMeta blocks
        // (including late Field #0 headers appended via packet concatenation).
        SchemaMeta localMeta = null;
        int pos = offset;
        while (pos < end) {
            PrefixVarint.Decoded tagDec = PrefixVarint.decode(data, pos, end);
            long tag = tagDec.value();
            long fnum64 = tag >>> 4;
            if (Long.compareUnsigned(fnum64, WireType.MAX_FIELD_NUMBER) > 0) {
                throw new OmniBufException("omnibuf: field number out of range: " + fnum64);
            }
            pos += tagDec.bytesRead();
            int wt = (int) (tag & 0x0FL);
            if (fnum64 == 0L) {
                if (wt != WireType.WIRE_SUB_MSG) {
                    throw new OmniBufException("omnibuf: Field #0 requires WireSubMsg");
                }
                OmniBufDecoder.Slice s = OmniBufDecoder.readLengthSlice(data, pos, end, 0);
                SchemaMeta m = SchemaMeta.decode(data, s.offset(), s.length(), depth + 1);
                localMeta = SchemaMeta.merge(localMeta, m);
                pos = s.end();
            } else {
                int next = OmniBufDecoder.tryFastSkipValue(data, pos, end, wt);
                if (next < 0) {
                    throw new OmniBufException("omnibuf: truncated or malformed field at " + pos);
                }
                pos = next;
            }
        }

        BlindSchema activeSchema = BlindSchema.push(inherited, localMeta);

        // Pass 2: Decode values, checking Field #0 name collisions and wire-shape invariants.
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Integer> nameOwners = new HashMap<>();
        Map<Integer, Integer> fieldShapes = new HashMap<>();
        Map<String, ByteArrayOutputStream> subMsgBuffers = null;
        Map<String, OmniBufDecoder.Slice> singleSubSlices = null;
        Map<String, BlindSchema> subMsgSchemas = null;

        pos = offset;
        while (pos < end) {
            PrefixVarint.Decoded tagDec = PrefixVarint.decode(data, pos, end);
            long tag = tagDec.value();
            pos += tagDec.bytesRead();
            int fnum = (int) (tag >>> 4);
            int wt = (int) (tag & 0x0FL);

            if (fnum == 0) {
                OmniBufDecoder.Slice s = OmniBufDecoder.readLengthSlice(data, pos, end, 0);
                pos = s.end();
                continue;
            }

            String name = activeSchema != null ? activeSchema.resolveName(fnum) : ("field_" + fnum);
            Integer prevOwner = nameOwners.putIfAbsent(name, fnum);
            if (prevOwner != null && prevOwner != fnum) {
                throw new OmniBufException(
                    "omnibuf: Field #0 name collision on '" + name + "' between fields #"
                        + prevOwner + " and #" + fnum
                );
            }

            int shape = computeFieldShape(data, pos, end, wt);
            Integer prevShape = fieldShapes.putIfAbsent(fnum, shape);
            if (prevShape != null && prevShape != shape) {
                throw new OmniBufException(
                    "omnibuf: wire shape mismatch across concatenated field #" + fnum
                );
            }

            switch (wt) {
                case WireType.WIRE_UINT -> {
                    PrefixVarint.Decoded d = PrefixVarint.decode(data, pos, end);
                    pos += d.bytesRead();
                    out.put(name, UnsignedLong.fromLongBits(d.value()));
                }
                case WireType.WIRE_SINT -> {
                    PrefixVarint.Decoded d = PrefixVarint.decode(data, pos, end);
                    pos += d.bytesRead();
                    out.put(name, PrefixVarint.decodeZigZag64(d.value()));
                }
                case WireType.WIRE_BOOL_FALSE -> out.put(name, Boolean.FALSE);
                case WireType.WIRE_BOOL_TRUE -> out.put(name, Boolean.TRUE);
                case WireType.WIRE_FIXED32 -> {
                    if (pos + 4 > end) {
                        throw new OmniBufException("omnibuf: truncated WireFixed32");
                    }
                    out.put(name, Integer.toUnsignedLong(PrefixVarint.getIntLE(data, pos)));
                    pos += 4;
                }
                case WireType.WIRE_SFIXED32 -> {
                    if (pos + 4 > end) {
                        throw new OmniBufException("omnibuf: truncated WireSfixed32");
                    }
                    out.put(name, PrefixVarint.getIntLE(data, pos));
                    pos += 4;
                }
                case WireType.WIRE_FLOAT32 -> {
                    if (pos + 4 > end) {
                        throw new OmniBufException("omnibuf: truncated WireFloat32");
                    }
                    out.put(name, Float.intBitsToFloat(PrefixVarint.getIntLE(data, pos)));
                    pos += 4;
                }
                case WireType.WIRE_FIXED64 -> {
                    if (pos + 8 > end) {
                        throw new OmniBufException("omnibuf: truncated WireFixed64");
                    }
                    out.put(name, UnsignedLong.fromLongBits(PrefixVarint.getLongLE(data, pos)));
                    pos += 8;
                }
                case WireType.WIRE_SFIXED64 -> {
                    if (pos + 8 > end) {
                        throw new OmniBufException("omnibuf: truncated WireSfixed64");
                    }
                    out.put(name, PrefixVarint.getLongLE(data, pos));
                    pos += 8;
                }
                case WireType.WIRE_FLOAT64 -> {
                    if (pos + 8 > end) {
                        throw new OmniBufException("omnibuf: truncated WireFloat64");
                    }
                    out.put(name, Double.longBitsToDouble(PrefixVarint.getLongLE(data, pos)));
                    pos += 8;
                }
                case WireType.WIRE_STRING -> {
                    OmniBufDecoder.Slice s = OmniBufDecoder.readLengthSlice(data, pos, end, 0);
                    out.put(name, Utf8Validator.decodeStrict(data, s.offset(), s.length()));
                    pos = s.end();
                }
                case WireType.WIRE_BYTES -> {
                    OmniBufDecoder.Slice s = OmniBufDecoder.readLengthSlice(data, pos, end, 0);
                    out.put(
                        name,
                        s.length() == 0 ? EMPTY_BYTES : Arrays.copyOfRange(data, s.offset(), s.end())
                    );
                    pos = s.end();
                }
                case WireType.WIRE_SUB_MSG -> {
                    OmniBufDecoder.Slice s = OmniBufDecoder.readLengthSlice(data, pos, end, 0);
                    pos = s.end();
                    out.putIfAbsent(name, null);
                    if (singleSubSlices == null) {
                        singleSubSlices = new LinkedHashMap<>();
                        subMsgSchemas = new HashMap<>();
                    }
                    subMsgSchemas.put(name, activeSchema != null ? activeSchema.childSchema(fnum) : null);
                    if (subMsgBuffers != null && subMsgBuffers.containsKey(name)) {
                        subMsgBuffers.get(name).write(data, s.offset(), s.length());
                    } else if (singleSubSlices.containsKey(name)) {
                        OmniBufDecoder.Slice first = singleSubSlices.remove(name);
                        if (subMsgBuffers == null) {
                            subMsgBuffers = new LinkedHashMap<>();
                        }
                        ByteArrayOutputStream baos =
                            new ByteArrayOutputStream(first.length() + s.length() + 32);
                        baos.write(data, first.offset(), first.length());
                        baos.write(data, s.offset(), s.length());
                        subMsgBuffers.put(name, baos);
                    } else {
                        singleSubSlices.put(name, s);
                    }
                }
                case WireType.WIRE_ARRAY -> {
                    OmniBufDecoder.Slice s = OmniBufDecoder.readLengthSlice(data, pos, end, 2);
                    pos = s.end();
                    BlindSchema childSchema = activeSchema != null ? activeSchema.childSchema(fnum) : null;
                    @SuppressWarnings("unchecked")
                    List<Object> list = (List<Object>) out.get(name);
                    if (list == null) {
                        list = new ArrayList<>();
                        out.put(name, list);
                    }
                    decodeArrayBodyBlind(list, data, s.offset(), s.end(), childSchema, depth + 1);
                }
                case WireType.WIRE_MAP -> {
                    OmniBufDecoder.Slice s = OmniBufDecoder.readLengthSlice(data, pos, end, 3);
                    pos = s.end();
                    BlindSchema childSchema = activeSchema != null ? activeSchema.childSchema(fnum) : null;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) out.get(name);
                    if (map == null) {
                        map = new LinkedHashMap<>();
                        out.put(name, map);
                    }
                    decodeMapBodyBlind(map, data, s.offset(), s.end(), childSchema, depth + 1);
                }
                case WireType.WIRE_EXTENDED ->
                    throw new OmniBufException("omnibuf: unrecognized WireExtended field #" + fnum);
                default ->
                    throw new OmniBufException("omnibuf: unsupported wire type: " + wt);
            }
        }

        if (singleSubSlices != null) {
            for (Map.Entry<String, OmniBufDecoder.Slice> entry : singleSubSlices.entrySet()) {
                String name = entry.getKey();
                OmniBufDecoder.Slice s = entry.getValue();
                out.put(
                    name,
                    decodeBlindSlice(data, s.offset(), s.length(), subMsgSchemas.get(name), depth + 1)
                );
            }
        }
        if (subMsgBuffers != null) {
            for (Map.Entry<String, ByteArrayOutputStream> entry : subMsgBuffers.entrySet()) {
                String name = entry.getKey();
                byte[] mergedBytes = entry.getValue().toByteArray();
                out.put(
                    name,
                    decodeBlindSlice(mergedBytes, 0, mergedBytes.length, subMsgSchemas.get(name), depth + 1)
                );
            }
        }

        return out;
    }

    private static int computeFieldShape(byte[] data, int pos, int end, int wt) {
        if (wt == WireType.WIRE_BOOL_TRUE) {
            return WireType.WIRE_BOOL_FALSE;
        }
        if (wt == WireType.WIRE_ARRAY) {
            OmniBufDecoder.Slice s = OmniBufDecoder.readLengthSlice(data, pos, end, 2);
            OmniBufDecoder.ArrayHeader hdr =
                OmniBufDecoder.parseArrayHeader(data, s.offset(), s.end());
            return WireType.WIRE_ARRAY | (hdr.elemWireType() << 8);
        }
        if (wt == WireType.WIRE_MAP) {
            OmniBufDecoder.Slice s = OmniBufDecoder.readLengthSlice(data, pos, end, 3);
            OmniBufDecoder.MapHeader hdr =
                OmniBufDecoder.parseMapHeader(data, s.offset(), s.end());
            return WireType.WIRE_MAP | (hdr.keyWireType() << 8) | (hdr.valWireType() << 16);
        }
        return wt;
    }

    private static void decodeArrayBodyBlind(
            List<Object> target,
            byte[] data,
            int bodyStart,
            int bodyEnd,
            BlindSchema childSchema,
            int depth) {
        if (depth > OmniBufDecoder.MAX_CONTAINER_DEPTH) {
            throw new OmniBufException(
                "omnibuf: WireArray nesting depth " + depth + " exceeds MaxNestingDepth"
            );
        }
        OmniBufDecoder.ArrayHeader hdr = OmniBufDecoder.parseArrayHeader(data, bodyStart, bodyEnd);
        int elemWT = hdr.elemWireType();
        if (elemWT == WireType.WIRE_EXTENDED) {
            throw new OmniBufException("omnibuf: unsupported WireExtended in WireArray");
        }
        int count = hdr.count();
        int p = hdr.payloadStart();
        int end = hdr.payloadEnd();

        for (int i = 0; i < count; i++) {
            p = switch (elemWT) {
                case WireType.WIRE_UINT -> {
                    PrefixVarint.Decoded d = PrefixVarint.decode(data, p, end);
                    target.add(UnsignedLong.fromLongBits(d.value()));
                    yield p + d.bytesRead();
                }
                case WireType.WIRE_SINT -> {
                    PrefixVarint.Decoded d = PrefixVarint.decode(data, p, end);
                    target.add(PrefixVarint.decodeZigZag64(d.value()));
                    yield p + d.bytesRead();
                }
                case WireType.WIRE_BOOL_FALSE -> {
                    int b = data[p] & 0xFF;
                    if (b > 1) {
                        throw new OmniBufException("omnibuf: invalid boolean byte in WireArray: " + b);
                    }
                    target.add(b == 1);
                    yield p + 1;
                }
                case WireType.WIRE_FIXED32 -> {
                    target.add(Integer.toUnsignedLong(PrefixVarint.getIntLE(data, p)));
                    yield p + 4;
                }
                case WireType.WIRE_SFIXED32 -> {
                    target.add(PrefixVarint.getIntLE(data, p));
                    yield p + 4;
                }
                case WireType.WIRE_FLOAT32 -> {
                    target.add(Float.intBitsToFloat(PrefixVarint.getIntLE(data, p)));
                    yield p + 4;
                }
                case WireType.WIRE_FIXED64 -> {
                    target.add(UnsignedLong.fromLongBits(PrefixVarint.getLongLE(data, p)));
                    yield p + 8;
                }
                case WireType.WIRE_SFIXED64 -> {
                    target.add(PrefixVarint.getLongLE(data, p));
                    yield p + 8;
                }
                case WireType.WIRE_FLOAT64 -> {
                    target.add(Double.longBitsToDouble(PrefixVarint.getLongLE(data, p)));
                    yield p + 8;
                }
                case WireType.WIRE_STRING -> {
                    OmniBufDecoder.Slice s = OmniBufDecoder.readLengthSlice(data, p, end, 0);
                    target.add(Utf8Validator.decodeStrict(data, s.offset(), s.length()));
                    yield s.end();
                }
                case WireType.WIRE_BYTES -> {
                    OmniBufDecoder.Slice s = OmniBufDecoder.readLengthSlice(data, p, end, 0);
                    target.add(s.length() == 0 ? EMPTY_BYTES : Arrays.copyOfRange(data, s.offset(), s.end()));
                    yield s.end();
                }
                case WireType.WIRE_SUB_MSG -> {
                    OmniBufDecoder.Slice s = OmniBufDecoder.readLengthSlice(data, p, end, 0);
                    target.add(decodeBlindSlice(data, s.offset(), s.length(), childSchema, depth + 1));
                    yield s.end();
                }
                case WireType.WIRE_ARRAY -> {
                    OmniBufDecoder.Slice s = OmniBufDecoder.readLengthSlice(data, p, end, 2);
                    List<Object> nested = new ArrayList<>();
                    decodeArrayBodyBlind(nested, data, s.offset(), s.end(), childSchema, depth + 1);
                    target.add(nested);
                    yield s.end();
                }
                case WireType.WIRE_MAP -> {
                    OmniBufDecoder.Slice s = OmniBufDecoder.readLengthSlice(data, p, end, 3);
                    Map<String, Object> nested = new LinkedHashMap<>();
                    decodeMapBodyBlind(nested, data, s.offset(), s.end(), childSchema, depth + 1);
                    target.add(nested);
                    yield s.end();
                }
                default -> throw new OmniBufException("omnibuf: invalid WireArray element type: " + elemWT);
            };
        }
        if (p != end) {
            throw new OmniBufException("omnibuf: WireArray has trailing payload bytes");
        }
    }

    private static void decodeMapBodyBlind(
            Map<String, Object> target,
            byte[] data,
            int bodyStart,
            int bodyEnd,
            BlindSchema childSchema,
            int depth) {
        if (depth > OmniBufDecoder.MAX_CONTAINER_DEPTH) {
            throw new OmniBufException(
                "omnibuf: WireMap nesting depth " + depth + " exceeds MaxNestingDepth"
            );
        }
        OmniBufDecoder.MapHeader hdr = OmniBufDecoder.parseMapHeader(data, bodyStart, bodyEnd);
        int keyWT = hdr.keyWireType();
        int valWT = hdr.valWireType();
        int p = hdr.payloadStart();
        int end = hdr.payloadEnd();

        for (int i = 0; i < hdr.count(); i++) {
            String key;
            switch (keyWT) {
                case WireType.WIRE_STRING -> {
                    OmniBufDecoder.Slice ks = OmniBufDecoder.readLengthSlice(data, p, end, 0);
                    key = Utf8Validator.decodeStrict(data, ks.offset(), ks.length());
                    p = ks.end();
                }
                case WireType.WIRE_UINT -> {
                    PrefixVarint.Decoded kd = PrefixVarint.decode(data, p, end);
                    key = Long.toUnsignedString(kd.value());
                    p += kd.bytesRead();
                }
                case WireType.WIRE_SINT -> {
                    PrefixVarint.Decoded kd = PrefixVarint.decode(data, p, end);
                    key = Long.toString(PrefixVarint.decodeZigZag64(kd.value()));
                    p += kd.bytesRead();
                }
                case WireType.WIRE_BOOL_FALSE -> {
                    if (p >= end) {
                        throw new OmniBufException("omnibuf: truncated WireMap boolean key");
                    }
                    int b = data[p++] & 0xFF;
                    if (b > 1) {
                        throw new OmniBufException("omnibuf: invalid WireMap boolean key byte: " + b);
                    }
                    key = b == 1 ? "true" : "false";
                }
                case WireType.WIRE_FIXED32 -> {
                    if (p + 4 > end) {
                        throw new OmniBufException("omnibuf: truncated WireMap fixed32 key");
                    }
                    key = Integer.toUnsignedString(PrefixVarint.getIntLE(data, p));
                    p += 4;
                }
                case WireType.WIRE_SFIXED32 -> {
                    if (p + 4 > end) {
                        throw new OmniBufException("omnibuf: truncated WireMap sfixed32 key");
                    }
                    key = Integer.toString(PrefixVarint.getIntLE(data, p));
                    p += 4;
                }
                case WireType.WIRE_FIXED64 -> {
                    if (p + 8 > end) {
                        throw new OmniBufException("omnibuf: truncated WireMap fixed64 key");
                    }
                    key = Long.toUnsignedString(PrefixVarint.getLongLE(data, p));
                    p += 8;
                }
                case WireType.WIRE_SFIXED64 -> {
                    if (p + 8 > end) {
                        throw new OmniBufException("omnibuf: truncated WireMap sfixed64 key");
                    }
                    key = Long.toString(PrefixVarint.getLongLE(data, p));
                    p += 8;
                }
                default -> throw new OmniBufException("omnibuf: invalid WireMap key type: " + keyWT);
            }

            Object val;
            switch (valWT) {
                case WireType.WIRE_UINT -> {
                    PrefixVarint.Decoded vd = PrefixVarint.decode(data, p, end);
                    val = UnsignedLong.fromLongBits(vd.value());
                    p += vd.bytesRead();
                }
                case WireType.WIRE_SINT -> {
                    PrefixVarint.Decoded vd = PrefixVarint.decode(data, p, end);
                    val = PrefixVarint.decodeZigZag64(vd.value());
                    p += vd.bytesRead();
                }
                case WireType.WIRE_BOOL_FALSE -> {
                    if (p >= end) {
                        throw new OmniBufException("omnibuf: truncated WireMap boolean value");
                    }
                    int b = data[p++] & 0xFF;
                    if (b > 1) {
                        throw new OmniBufException("omnibuf: invalid WireMap boolean value byte: " + b);
                    }
                    val = b == 1;
                }
                case WireType.WIRE_FIXED32 -> {
                    if (p + 4 > end) {
                        throw new OmniBufException("omnibuf: truncated WireMap fixed32 value");
                    }
                    val = Integer.toUnsignedLong(PrefixVarint.getIntLE(data, p));
                    p += 4;
                }
                case WireType.WIRE_SFIXED32 -> {
                    if (p + 4 > end) {
                        throw new OmniBufException("omnibuf: truncated WireMap sfixed32 value");
                    }
                    val = PrefixVarint.getIntLE(data, p);
                    p += 4;
                }
                case WireType.WIRE_FLOAT32 -> {
                    if (p + 4 > end) {
                        throw new OmniBufException("omnibuf: truncated WireMap float32 value");
                    }
                    val = Float.intBitsToFloat(PrefixVarint.getIntLE(data, p));
                    p += 4;
                }
                case WireType.WIRE_FIXED64 -> {
                    if (p + 8 > end) {
                        throw new OmniBufException("omnibuf: truncated WireMap fixed64 value");
                    }
                    val = UnsignedLong.fromLongBits(PrefixVarint.getLongLE(data, p));
                    p += 8;
                }
                case WireType.WIRE_SFIXED64 -> {
                    if (p + 8 > end) {
                        throw new OmniBufException("omnibuf: truncated WireMap sfixed64 value");
                    }
                    val = PrefixVarint.getLongLE(data, p);
                    p += 8;
                }
                case WireType.WIRE_FLOAT64 -> {
                    if (p + 8 > end) {
                        throw new OmniBufException("omnibuf: truncated WireMap float64 value");
                    }
                    val = Double.longBitsToDouble(PrefixVarint.getLongLE(data, p));
                    p += 8;
                }
                case WireType.WIRE_STRING -> {
                    OmniBufDecoder.Slice vs = OmniBufDecoder.readLengthSlice(data, p, end, 0);
                    val = Utf8Validator.decodeStrict(data, vs.offset(), vs.length());
                    p = vs.end();
                }
                case WireType.WIRE_BYTES -> {
                    OmniBufDecoder.Slice vs = OmniBufDecoder.readLengthSlice(data, p, end, 0);
                    val = vs.length() == 0 ? EMPTY_BYTES : Arrays.copyOfRange(data, vs.offset(), vs.end());
                    p = vs.end();
                }
                case WireType.WIRE_SUB_MSG -> {
                    OmniBufDecoder.Slice vs = OmniBufDecoder.readLengthSlice(data, p, end, 0);
                    val = decodeBlindSlice(data, vs.offset(), vs.length(), childSchema, depth + 1);
                    p = vs.end();
                }
                case WireType.WIRE_ARRAY -> {
                    OmniBufDecoder.Slice vs = OmniBufDecoder.readLengthSlice(data, p, end, 2);
                    List<Object> nested = new ArrayList<>();
                    decodeArrayBodyBlind(nested, data, vs.offset(), vs.end(), childSchema, depth + 1);
                    val = nested;
                    p = vs.end();
                }
                case WireType.WIRE_MAP -> {
                    OmniBufDecoder.Slice vs = OmniBufDecoder.readLengthSlice(data, p, end, 3);
                    Map<String, Object> nested = new LinkedHashMap<>();
                    decodeMapBodyBlind(nested, data, vs.offset(), vs.end(), childSchema, depth + 1);
                    val = nested;
                    p = vs.end();
                }
                case WireType.WIRE_EXTENDED ->
                    throw new OmniBufException("omnibuf: unsupported WireExtended in WireMap value");
                default -> throw new OmniBufException("omnibuf: invalid WireMap value type: " + valWT);
            }
            target.put(key, val);
        }
        if (p != end) {
            throw new OmniBufException("omnibuf: WireMap has trailing payload bytes");
        }
    }

    // ========================================================================
    // Unsigned-Aware Deterministic JSON Serializer
    // ========================================================================

    private static boolean hasNonFiniteFloat(Object value) {
        if (value instanceof Float f) {
            return Float.isNaN(f) || Float.isInfinite(f);
        }
        if (value instanceof Double d) {
            return Double.isNaN(d) || Double.isInfinite(d);
        }
        if (value instanceof Map<?, ?> m) {
            for (Object v : m.values()) {
                if (hasNonFiniteFloat(v)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof List<?> list) {
            for (Object v : list) {
                if (hasNonFiniteFloat(v)) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    private static void writeJsonValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
            return;
        }
        if (value instanceof UnsignedLong u64) {
            sb.append(u64.toString());
            return;
        }
        if (value instanceof Float f) {
            sb.append(formatFloat32Json(f));
            return;
        }
        if (value instanceof Double d) {
            sb.append(formatFloat64Json(d));
            return;
        }
        if (value instanceof Number n) {
            sb.append(n.toString());
            return;
        }
        if (value instanceof Boolean b) {
            sb.append(b.booleanValue() ? "true" : "false");
            return;
        }
        if (value instanceof String s) {
            writeJsonString(sb, s);
            return;
        }
        if (value instanceof byte[] bytes) {
            sb.append('"').append(Base64.getEncoder().encodeToString(bytes)).append('"');
            return;
        }
        if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeJsonValue(sb, list.get(i));
            }
            sb.append(']');
            return;
        }
        if (value instanceof Map<?, ?> rawMap) {
            TreeMap<String, Object> sorted = new TreeMap<>(Utf8Validator.UTF8_ORDER);
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeJsonString(sb, entry.getKey());
                sb.append(':');
                writeJsonValue(sb, entry.getValue());
            }
            sb.append('}');
            return;
        }
        writeJsonString(sb, value.toString());
    }

    private static final MathContext ONE_SIG_DIGIT = new MathContext(1, RoundingMode.HALF_EVEN);

    private static String formatFloat32Json(float f) {
        int bits = Float.floatToRawIntBits(f);
        if (f == 0.0f) {
            return bits < 0 ? "-0" : "0";
        }
        String s = Float.toString(f);
        int eIdx = s.indexOf('E');
        if (eIdx < 0) {
            if (s.endsWith(".0")) {
                return s.substring(0, s.length() - 2);
            }
            return s;
        }
        int sigStart = s.charAt(0) == '-' ? 1 : 0;
        if (eIdx - sigStart == 3 && s.charAt(sigStart + 1) == '.') {
            String cand = new BigDecimal(s).round(ONE_SIG_DIGIT).toString().toLowerCase(Locale.ROOT);
            if (Float.floatToRawIntBits(Float.parseFloat(cand)) == bits) {
                return cand;
            }
        }
        return s.toLowerCase(Locale.ROOT);
    }

    private static String formatFloat64Json(double d) {
        long bits = Double.doubleToRawLongBits(d);
        if (d == 0.0d) {
            return bits < 0L ? "-0" : "0";
        }
        String s = Double.toString(d);
        int eIdx = s.indexOf('E');
        if (eIdx < 0) {
            if (s.endsWith(".0")) {
                return s.substring(0, s.length() - 2);
            }
            return s;
        }
        int sigStart = s.charAt(0) == '-' ? 1 : 0;
        if (eIdx - sigStart == 3 && s.charAt(sigStart + 1) == '.') {
            String cand = new BigDecimal(s).round(ONE_SIG_DIGIT).toString().toLowerCase(Locale.ROOT);
            if (Double.doubleToRawLongBits(Double.parseDouble(cand)) == bits) {
                return cand;
            }
        }
        return s.toLowerCase(Locale.ROOT);
    }

    private static void writeJsonString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
