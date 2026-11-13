package com.omnibuf.wire;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Zero-copy O(1)-header-hop field locators, lazy readers, and in-place packet splicing
 * primitives used by {@code Opaque*} view types.
 */
public final class OmniBufOpaque {

    @FunctionalInterface
    public interface DirtyFieldWriter {
        void writeDirtyField(OmniBufEncoder encoder, int fieldNumber);
    }

    public record LocatedField(int valueOffset, int valueEnd, int wireType, boolean found) {
        public static final LocatedField NOT_FOUND = new LocatedField(0, 0, 0, false);
    }

    public record PacketView(byte[] buffer, int offset, int length) {
        public byte[] toByteArray() {
            if (offset == 0 && length == buffer.length) {
                return Arrays.copyOf(buffer, length);
            }
            return Arrays.copyOfRange(buffer, offset, offset + length);
        }
    }

    private static final byte[] EMPTY_BYTES = new byte[0];

    private OmniBufOpaque() {}

    /**
     * Scans all top-level field headers in {@code packet[offset..offset+length]} in O(1) time per
     * header and returns the last occurrence matching {@code targetField} and {@code wireMask}.
     * Returns {@link LocatedField#NOT_FOUND} if absent or if the packet framing is malformed.
     */
    public static LocatedField locateField(
            byte[] packet, int offset, int length, int targetField, int wireMask) {
        if (packet == null || offset < 0 || length < 0 || offset + length > packet.length) {
            return LocatedField.NOT_FOUND;
        }
        int pos = offset;
        int end = offset + length;
        int lastValStart = -1;
        int lastValEnd = -1;
        int lastWT = 0;
        long[] scratch = null;

        while (pos < end) {
            int fnum;
            int wt;
            int b0 = packet[pos] & 0xFF;
            if ((b0 & 1) != 0) {
                fnum = b0 >>> 5;
                wt = (b0 >>> 1) & 0x0F;
                pos += 1;
            } else if ((b0 & 2) != 0) {
                if (pos + 2 > end) {
                    return LocatedField.NOT_FOUND;
                }
                int u16 = Short.toUnsignedInt(PrefixVarint.getShortLE(packet, pos)) >>> 2;
                fnum = u16 >>> 4;
                wt = u16 & 0x0F;
                pos += 2;
            } else {
                if (scratch == null) {
                    scratch = new long[1];
                }
                int tn = PrefixVarint.tryDecode(packet, pos, end, scratch);
                if (tn == 0 || Long.compareUnsigned(scratch[0] >>> 4, WireType.MAX_FIELD_NUMBER) > 0) {
                    return LocatedField.NOT_FOUND;
                }
                fnum = (int) (scratch[0] >>> 4);
                wt = (int) (scratch[0] & 0x0FL);
                pos += tn;
            }
            if (fnum == 0 && wt != WireType.WIRE_SUB_MSG) {
                return LocatedField.NOT_FOUND;
            }

            int valStart = pos;
            int nextPos = skipValidatedHeader(packet, pos, end, wt);
            if (nextPos < 0) {
                return LocatedField.NOT_FOUND;
            }
            if (fnum == targetField && ((wireMask >>> wt) & 1) != 0) {
                lastValStart = valStart;
                lastValEnd = nextPos;
                lastWT = wt;
            }
            pos = nextPos;
        }

        if (lastValStart < 0) {
            return LocatedField.NOT_FOUND;
        }
        return new LocatedField(lastValStart, lastValEnd, lastWT, true);
    }

    public static LocatedField locateField(byte[] packet, int targetField, int wireMask) {
        return locateField(packet, 0, packet == null ? 0 : packet.length, targetField, wireMask);
    }

    private static int skipValidatedHeader(byte[] packet, int pos, int end, int wt) {
        if (wt == WireType.WIRE_ARRAY || wt == WireType.WIRE_MAP || wt == WireType.WIRE_EXTENDED) {
            int nextPos = OmniBufDecoder.tryFastSkipValue(packet, pos, end, wt);
            if (nextPos < 0) {
                return -1;
            }
            // Validate array/map header structural invariants without descending into elements.
            long[] lenOut = new long[1];
            int ln = PrefixVarint.tryDecode(packet, pos, end, lenOut);
            if (ln == 0) {
                return -1;
            }
            int bodyStart = pos + ln;
            try {
                if (wt == WireType.WIRE_ARRAY) {
                    OmniBufDecoder.parseArrayHeader(packet, bodyStart, nextPos);
                } else if (wt == WireType.WIRE_MAP) {
                    OmniBufDecoder.parseMapHeader(packet, bodyStart, nextPos);
                }
            } catch (RuntimeException e) {
                return -1;
            }
            return nextPos;
        }
        return OmniBufDecoder.tryFastSkipValue(packet, pos, end, wt);
    }

    // ========================================================================
    // Lazy Scalar Readers
    // ========================================================================

    public static long readUint64(byte[] packet, int offset, int length, int fieldNum) {
        LocatedField loc = locateField(packet, offset, length, fieldNum, 1 << WireType.WIRE_UINT);
        if (!loc.found()) {
            return 0L;
        }
        long[] out = new long[1];
        int n = PrefixVarint.tryDecode(packet, loc.valueOffset(), loc.valueEnd(), out);
        return n > 0 ? out[0] : 0L;
    }

    public static int readUint32(byte[] packet, int offset, int length, int fieldNum) {
        return (int) readUint64(packet, offset, length, fieldNum);
    }

    public static long readSint64(byte[] packet, int offset, int length, int fieldNum) {
        LocatedField loc = locateField(packet, offset, length, fieldNum, 1 << WireType.WIRE_SINT);
        if (!loc.found()) {
            return 0L;
        }
        long[] out = new long[1];
        int n = PrefixVarint.tryDecode(packet, loc.valueOffset(), loc.valueEnd(), out);
        return n > 0 ? PrefixVarint.decodeZigZag64(out[0]) : 0L;
    }

    public static int readSint32(byte[] packet, int offset, int length, int fieldNum) {
        return (int) readSint64(packet, offset, length, fieldNum);
    }

    public static boolean readBool(byte[] packet, int offset, int length, int fieldNum) {
        LocatedField loc = locateField(
            packet,
            offset,
            length,
            fieldNum,
            (1 << WireType.WIRE_BOOL_FALSE) | (1 << WireType.WIRE_BOOL_TRUE)
        );
        return loc.found() && loc.wireType() == WireType.WIRE_BOOL_TRUE;
    }

    public static int readFixed32(byte[] packet, int offset, int length, int fieldNum) {
        LocatedField loc = locateField(
            packet,
            offset,
            length,
            fieldNum,
            (1 << WireType.WIRE_FIXED32) | (1 << WireType.WIRE_SFIXED32)
        );
        if (!loc.found() || loc.valueEnd() - loc.valueOffset() < 4) {
            return 0;
        }
        return PrefixVarint.getIntLE(packet, loc.valueOffset());
    }

    public static long readFixed64(byte[] packet, int offset, int length, int fieldNum) {
        LocatedField loc = locateField(
            packet,
            offset,
            length,
            fieldNum,
            (1 << WireType.WIRE_FIXED64) | (1 << WireType.WIRE_SFIXED64)
        );
        if (!loc.found() || loc.valueEnd() - loc.valueOffset() < 8) {
            return 0L;
        }
        return PrefixVarint.getLongLE(packet, loc.valueOffset());
    }

    public static float readFloat32(byte[] packet, int offset, int length, int fieldNum) {
        LocatedField loc = locateField(packet, offset, length, fieldNum, 1 << WireType.WIRE_FLOAT32);
        if (!loc.found() || loc.valueEnd() - loc.valueOffset() < 4) {
            return 0.0f;
        }
        return Float.intBitsToFloat(PrefixVarint.getIntLE(packet, loc.valueOffset()));
    }

    public static double readFloat64(byte[] packet, int offset, int length, int fieldNum) {
        LocatedField loc = locateField(packet, offset, length, fieldNum, 1 << WireType.WIRE_FLOAT64);
        if (!loc.found() || loc.valueEnd() - loc.valueOffset() < 8) {
            return 0.0d;
        }
        return Double.longBitsToDouble(PrefixVarint.getLongLE(packet, loc.valueOffset()));
    }

    public static String readString(byte[] packet, int offset, int length, int fieldNum) {
        LocatedField loc = locateField(packet, offset, length, fieldNum, 1 << WireType.WIRE_STRING);
        if (!loc.found()) {
            return "";
        }
        int p = loc.valueOffset();
        int b0 = packet[p] & 0xFF;
        if ((b0 & 1) != 0) {
            int strLen = b0 >>> 1;
            return Utf8Validator.decodeStrict(packet, p + 1, strLen);
        }
        OmniBufDecoder.Slice s = OmniBufDecoder.readLengthSlice(packet, p, loc.valueEnd(), 0);
        return Utf8Validator.decodeStrict(packet, s.offset(), s.length());
    }

    public static byte[] readBytes(byte[] packet, int offset, int length, int fieldNum) {
        LocatedField loc = locateField(packet, offset, length, fieldNum, 1 << WireType.WIRE_BYTES);
        if (!loc.found()) {
            return EMPTY_BYTES;
        }
        OmniBufDecoder.Slice s = OmniBufDecoder.readLengthSlice(packet, loc.valueOffset(), loc.valueEnd(), 0);
        if (s.length() == 0) {
            return EMPTY_BYTES;
        }
        return Arrays.copyOfRange(packet, s.offset(), s.end());
    }

    // ========================================================================
    // Lazy Sub-Message, Array, and Map Readers (with Concatenation Merge)
    // ========================================================================

    /**
     * Locates all occurrences of {@code WireSubMsg} field {@code fieldNum}. When present once,
     * returns a zero-copy {@link PacketView} into {@code packet}. When concatenated multiple
     * times, merges all segment payloads into a combined {@link PacketView}. Returns {@code null}
     * if absent.
     */
    public static PacketView readSubMsgView(byte[] packet, int offset, int length, int fieldNum) {
        int pos = offset;
        int end = offset + length;
        int firstStart = -1;
        int firstLen = 0;
        ByteArrayOutputStream merged = null;
        long[] scratch = null;

        while (pos < end) {
            int fnum;
            int wt;
            int b0 = packet[pos] & 0xFF;
            if ((b0 & 1) != 0) {
                fnum = b0 >>> 5;
                wt = (b0 >>> 1) & 0x0F;
                pos += 1;
            } else if ((b0 & 2) != 0) {
                if (pos + 2 > end) {
                    return null;
                }
                int u16 = Short.toUnsignedInt(PrefixVarint.getShortLE(packet, pos)) >>> 2;
                fnum = u16 >>> 4;
                wt = u16 & 0x0F;
                pos += 2;
            } else {
                if (scratch == null) {
                    scratch = new long[1];
                }
                int tn = PrefixVarint.tryDecode(packet, pos, end, scratch);
                if (tn == 0 || Long.compareUnsigned(scratch[0] >>> 4, WireType.MAX_FIELD_NUMBER) > 0) {
                    return null;
                }
                fnum = (int) (scratch[0] >>> 4);
                wt = (int) (scratch[0] & 0x0FL);
                pos += tn;
            }

            if (fnum == fieldNum && wt == WireType.WIRE_SUB_MSG) {
                OmniBufDecoder.Slice s = OmniBufDecoder.readLengthSlice(packet, pos, end, 0);
                if (firstStart < 0) {
                    firstStart = s.offset();
                    firstLen = s.length();
                } else {
                    if (merged == null) {
                        merged = new ByteArrayOutputStream(firstLen + s.length() + 32);
                        merged.write(packet, firstStart, firstLen);
                    }
                    merged.write(packet, s.offset(), s.length());
                }
                pos = s.end();
            } else {
                int nextPos = skipValidatedHeader(packet, pos, end, wt);
                if (nextPos < 0) {
                    return null;
                }
                pos = nextPos;
            }
        }

        if (firstStart < 0) {
            return null;
        }
        if (merged != null) {
            byte[] combined = merged.toByteArray();
            return new PacketView(combined, 0, combined.length);
        }
        return new PacketView(packet, firstStart, firstLen);
    }

    private static List<OmniBufDecoder.Slice> collectContainerBodies(
            byte[] packet, int offset, int length, int fieldNum, int targetWT) {
        List<OmniBufDecoder.Slice> slices = null;
        int pos = offset;
        int end = offset + length;
        long[] scratch = null;
        int minLen = targetWT == WireType.WIRE_MAP ? 3 : 2;

        while (pos < end) {
            int fnum;
            int wt;
            int b0 = packet[pos] & 0xFF;
            if ((b0 & 1) != 0) {
                fnum = b0 >>> 5;
                wt = (b0 >>> 1) & 0x0F;
                pos += 1;
            } else if ((b0 & 2) != 0) {
                if (pos + 2 > end) {
                    return Collections.emptyList();
                }
                int u16 = Short.toUnsignedInt(PrefixVarint.getShortLE(packet, pos)) >>> 2;
                fnum = u16 >>> 4;
                wt = u16 & 0x0F;
                pos += 2;
            } else {
                if (scratch == null) {
                    scratch = new long[1];
                }
                int tn = PrefixVarint.tryDecode(packet, pos, end, scratch);
                if (tn == 0 || Long.compareUnsigned(scratch[0] >>> 4, WireType.MAX_FIELD_NUMBER) > 0) {
                    return Collections.emptyList();
                }
                fnum = (int) (scratch[0] >>> 4);
                wt = (int) (scratch[0] & 0x0FL);
                pos += tn;
            }

            if (fnum == fieldNum && wt == targetWT) {
                OmniBufDecoder.Slice s = OmniBufDecoder.readLengthSlice(packet, pos, end, minLen);
                if (slices == null) {
                    slices = new ArrayList<>(2);
                }
                slices.add(s);
                pos = s.end();
            } else {
                int nextPos = skipValidatedHeader(packet, pos, end, wt);
                if (nextPos < 0) {
                    return Collections.emptyList();
                }
                pos = nextPos;
            }
        }
        return slices == null ? Collections.emptyList() : slices;
    }

    public static List<String> readStringList(byte[] packet, int offset, int length, int fieldNum) {
        List<OmniBufDecoder.Slice> bodies =
            collectContainerBodies(packet, offset, length, fieldNum, WireType.WIRE_ARRAY);
        if (bodies.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        for (OmniBufDecoder.Slice body : bodies) {
            OmniBufDecoder.ArrayHeader hdr =
                OmniBufDecoder.parseArrayHeader(packet, body.offset(), body.end());
            if (hdr.elemWireType() != WireType.WIRE_STRING) {
                continue;
            }
            int p = hdr.payloadStart();
            int end = hdr.payloadEnd();
            for (int i = 0; i < hdr.count(); i++) {
                OmniBufDecoder.Slice s = OmniBufDecoder.readLengthSlice(packet, p, end, 0);
                out.add(Utf8Validator.decodeStrict(packet, s.offset(), s.length()));
                p = s.end();
            }
        }
        return out;
    }

    public static List<byte[]> readBytesList(byte[] packet, int offset, int length, int fieldNum) {
        List<OmniBufDecoder.Slice> bodies =
            collectContainerBodies(packet, offset, length, fieldNum, WireType.WIRE_ARRAY);
        if (bodies.isEmpty()) {
            return Collections.emptyList();
        }
        List<byte[]> out = new ArrayList<>();
        for (OmniBufDecoder.Slice body : bodies) {
            OmniBufDecoder.ArrayHeader hdr =
                OmniBufDecoder.parseArrayHeader(packet, body.offset(), body.end());
            if (hdr.elemWireType() != WireType.WIRE_BYTES) {
                continue;
            }
            int p = hdr.payloadStart();
            int end = hdr.payloadEnd();
            for (int i = 0; i < hdr.count(); i++) {
                OmniBufDecoder.Slice s = OmniBufDecoder.readLengthSlice(packet, p, end, 0);
                out.add(s.length() == 0 ? EMPTY_BYTES : Arrays.copyOfRange(packet, s.offset(), s.end()));
                p = s.end();
            }
        }
        return out;
    }

    public static List<Integer> readUint32List(byte[] packet, int offset, int length, int fieldNum) {
        List<OmniBufDecoder.Slice> bodies =
            collectContainerBodies(packet, offset, length, fieldNum, WireType.WIRE_ARRAY);
        if (bodies.isEmpty()) {
            return Collections.emptyList();
        }
        List<Integer> out = new ArrayList<>();
        for (OmniBufDecoder.Slice body : bodies) {
            OmniBufDecoder.ArrayHeader hdr =
                OmniBufDecoder.parseArrayHeader(packet, body.offset(), body.end());
            if (hdr.elemWireType() != WireType.WIRE_UINT) {
                continue;
            }
            int p = hdr.payloadStart();
            int end = hdr.payloadEnd();
            for (int i = 0; i < hdr.count(); i++) {
                PrefixVarint.Decoded d = PrefixVarint.decode(packet, p, end);
                out.add((int) d.value());
                p += d.bytesRead();
            }
        }
        return out;
    }

    public static List<Long> readUint64List(byte[] packet, int offset, int length, int fieldNum) {
        List<OmniBufDecoder.Slice> bodies =
            collectContainerBodies(packet, offset, length, fieldNum, WireType.WIRE_ARRAY);
        if (bodies.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> out = new ArrayList<>();
        for (OmniBufDecoder.Slice body : bodies) {
            OmniBufDecoder.ArrayHeader hdr =
                OmniBufDecoder.parseArrayHeader(packet, body.offset(), body.end());
            if (hdr.elemWireType() != WireType.WIRE_UINT) {
                continue;
            }
            int p = hdr.payloadStart();
            int end = hdr.payloadEnd();
            for (int i = 0; i < hdr.count(); i++) {
                PrefixVarint.Decoded d = PrefixVarint.decode(packet, p, end);
                out.add(d.value());
                p += d.bytesRead();
            }
        }
        return out;
    }

    public static List<Integer> readSint32List(byte[] packet, int offset, int length, int fieldNum) {
        List<OmniBufDecoder.Slice> bodies =
            collectContainerBodies(packet, offset, length, fieldNum, WireType.WIRE_ARRAY);
        if (bodies.isEmpty()) {
            return Collections.emptyList();
        }
        List<Integer> out = new ArrayList<>();
        for (OmniBufDecoder.Slice body : bodies) {
            OmniBufDecoder.ArrayHeader hdr =
                OmniBufDecoder.parseArrayHeader(packet, body.offset(), body.end());
            if (hdr.elemWireType() != WireType.WIRE_SINT) {
                continue;
            }
            int p = hdr.payloadStart();
            int end = hdr.payloadEnd();
            for (int i = 0; i < hdr.count(); i++) {
                PrefixVarint.Decoded d = PrefixVarint.decode(packet, p, end);
                out.add(PrefixVarint.decodeZigZag32(d.value()));
                p += d.bytesRead();
            }
        }
        return out;
    }

    public static List<Long> readSint64List(byte[] packet, int offset, int length, int fieldNum) {
        List<OmniBufDecoder.Slice> bodies =
            collectContainerBodies(packet, offset, length, fieldNum, WireType.WIRE_ARRAY);
        if (bodies.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> out = new ArrayList<>();
        for (OmniBufDecoder.Slice body : bodies) {
            OmniBufDecoder.ArrayHeader hdr =
                OmniBufDecoder.parseArrayHeader(packet, body.offset(), body.end());
            if (hdr.elemWireType() != WireType.WIRE_SINT) {
                continue;
            }
            int p = hdr.payloadStart();
            int end = hdr.payloadEnd();
            for (int i = 0; i < hdr.count(); i++) {
                PrefixVarint.Decoded d = PrefixVarint.decode(packet, p, end);
                out.add(PrefixVarint.decodeZigZag64(d.value()));
                p += d.bytesRead();
            }
        }
        return out;
    }

    public static List<Boolean> readBoolList(byte[] packet, int offset, int length, int fieldNum) {
        List<OmniBufDecoder.Slice> bodies =
            collectContainerBodies(packet, offset, length, fieldNum, WireType.WIRE_ARRAY);
        if (bodies.isEmpty()) {
            return Collections.emptyList();
        }
        List<Boolean> out = new ArrayList<>();
        for (OmniBufDecoder.Slice body : bodies) {
            OmniBufDecoder.ArrayHeader hdr =
                OmniBufDecoder.parseArrayHeader(packet, body.offset(), body.end());
            if (hdr.elemWireType() != WireType.WIRE_BOOL_FALSE) {
                continue;
            }
            int p = hdr.payloadStart();
            for (int i = 0; i < hdr.count(); i++) {
                out.add(packet[p + i] == 1);
            }
        }
        return out;
    }

    public static List<Integer> readFixed32List(byte[] packet, int offset, int length, int fieldNum) {
        List<OmniBufDecoder.Slice> bodies =
            collectContainerBodies(packet, offset, length, fieldNum, WireType.WIRE_ARRAY);
        if (bodies.isEmpty()) {
            return Collections.emptyList();
        }
        List<Integer> out = new ArrayList<>();
        for (OmniBufDecoder.Slice body : bodies) {
            OmniBufDecoder.ArrayHeader hdr =
                OmniBufDecoder.parseArrayHeader(packet, body.offset(), body.end());
            if (hdr.elemWireType() != WireType.WIRE_FIXED32
                    && hdr.elemWireType() != WireType.WIRE_SFIXED32) {
                continue;
            }
            int p = hdr.payloadStart();
            for (int i = 0; i < hdr.count(); i++) {
                out.add(PrefixVarint.getIntLE(packet, p + i * 4));
            }
        }
        return out;
    }

    public static List<Long> readFixed64List(byte[] packet, int offset, int length, int fieldNum) {
        List<OmniBufDecoder.Slice> bodies =
            collectContainerBodies(packet, offset, length, fieldNum, WireType.WIRE_ARRAY);
        if (bodies.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> out = new ArrayList<>();
        for (OmniBufDecoder.Slice body : bodies) {
            OmniBufDecoder.ArrayHeader hdr =
                OmniBufDecoder.parseArrayHeader(packet, body.offset(), body.end());
            if (hdr.elemWireType() != WireType.WIRE_FIXED64
                    && hdr.elemWireType() != WireType.WIRE_SFIXED64) {
                continue;
            }
            int p = hdr.payloadStart();
            for (int i = 0; i < hdr.count(); i++) {
                out.add(PrefixVarint.getLongLE(packet, p + i * 8));
            }
        }
        return out;
    }

    public static List<Float> readFloat32List(byte[] packet, int offset, int length, int fieldNum) {
        List<OmniBufDecoder.Slice> bodies =
            collectContainerBodies(packet, offset, length, fieldNum, WireType.WIRE_ARRAY);
        if (bodies.isEmpty()) {
            return Collections.emptyList();
        }
        List<Float> out = new ArrayList<>();
        for (OmniBufDecoder.Slice body : bodies) {
            OmniBufDecoder.ArrayHeader hdr =
                OmniBufDecoder.parseArrayHeader(packet, body.offset(), body.end());
            if (hdr.elemWireType() != WireType.WIRE_FLOAT32) {
                continue;
            }
            int p = hdr.payloadStart();
            for (int i = 0; i < hdr.count(); i++) {
                out.add(Float.intBitsToFloat(PrefixVarint.getIntLE(packet, p + i * 4)));
            }
        }
        return out;
    }

    public static List<Double> readFloat64List(byte[] packet, int offset, int length, int fieldNum) {
        List<OmniBufDecoder.Slice> bodies =
            collectContainerBodies(packet, offset, length, fieldNum, WireType.WIRE_ARRAY);
        if (bodies.isEmpty()) {
            return Collections.emptyList();
        }
        List<Double> out = new ArrayList<>();
        for (OmniBufDecoder.Slice body : bodies) {
            OmniBufDecoder.ArrayHeader hdr =
                OmniBufDecoder.parseArrayHeader(packet, body.offset(), body.end());
            if (hdr.elemWireType() != WireType.WIRE_FLOAT64) {
                continue;
            }
            int p = hdr.payloadStart();
            for (int i = 0; i < hdr.count(); i++) {
                out.add(Double.longBitsToDouble(PrefixVarint.getLongLE(packet, p + i * 8)));
            }
        }
        return out;
    }

    public static List<PacketView> readSubMsgList(byte[] packet, int offset, int length, int fieldNum) {
        List<OmniBufDecoder.Slice> bodies =
            collectContainerBodies(packet, offset, length, fieldNum, WireType.WIRE_ARRAY);
        if (bodies.isEmpty()) {
            return Collections.emptyList();
        }
        List<PacketView> out = new ArrayList<>();
        for (OmniBufDecoder.Slice body : bodies) {
            OmniBufDecoder.ArrayHeader hdr =
                OmniBufDecoder.parseArrayHeader(packet, body.offset(), body.end());
            if (hdr.elemWireType() != WireType.WIRE_SUB_MSG) {
                continue;
            }
            int p = hdr.payloadStart();
            int end = hdr.payloadEnd();
            for (int i = 0; i < hdr.count(); i++) {
                OmniBufDecoder.Slice s = OmniBufDecoder.readLengthSlice(packet, p, end, 0);
                out.add(new PacketView(packet, s.offset(), s.length()));
                p = s.end();
            }
        }
        return out;
    }

    public static Map<String, Long> readMapStringUint64(
            byte[] packet, int offset, int length, int fieldNum) {
        List<OmniBufDecoder.Slice> bodies =
            collectContainerBodies(packet, offset, length, fieldNum, WireType.WIRE_MAP);
        if (bodies.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Long> out = new LinkedHashMap<>();
        for (OmniBufDecoder.Slice body : bodies) {
            OmniBufDecoder.MapHeader hdr =
                OmniBufDecoder.parseMapHeader(packet, body.offset(), body.end());
            if (hdr.keyWireType() != WireType.WIRE_STRING || hdr.valWireType() != WireType.WIRE_UINT) {
                continue;
            }
            int p = hdr.payloadStart();
            int end = hdr.payloadEnd();
            for (int i = 0; i < hdr.count(); i++) {
                OmniBufDecoder.Slice ks = OmniBufDecoder.readLengthSlice(packet, p, end, 0);
                String k = Utf8Validator.decodeStrict(packet, ks.offset(), ks.length());
                p = ks.end();
                PrefixVarint.Decoded vd = PrefixVarint.decode(packet, p, end);
                out.put(k, vd.value());
                p += vd.bytesRead();
            }
        }
        return out;
    }

    // ========================================================================
    // Selective Field Splicing (spliceFields)
    // ========================================================================

    /**
     * Splices modified fields identified by {@code dirtyMask} into {@code packet[offset..offset+length]}
     * without re-parsing or re-serializing untouched fields. Preserves any prefix already written
     * into {@code target} and safely detaches if {@code target} shares the same backing array.
     */
    public static byte[] spliceFields(
            byte[] packet,
            int offset,
            int length,
            long dirtyMask,
            int maxField,
            DirtyFieldWriter appendDirty,
            OmniBufEncoder target) {
        OmniBufEncoder enc = target != null ? target : new OmniBufEncoder(length + 64);
        if (dirtyMask == 0L) {
            enc.detachIfAliased(packet, offset, length);
            enc.writeRawBytes(packet, offset, length);
            return enc.toByteArray();
        }
        enc.detachIfAliased(packet, offset, length);

        int pos = offset;
        int end = offset + length;
        int cleanStart = offset;
        int validEnd = offset;
        long emitted = 0L;
        long[] scratch = null;

        while (pos < end) {
            int fieldStart = pos;
            int fnum;
            int wt;
            int b0 = packet[pos] & 0xFF;
            if ((b0 & 1) != 0) {
                fnum = b0 >>> 5;
                wt = (b0 >>> 1) & 0x0F;
                pos += 1;
            } else if ((b0 & 2) != 0 && pos + 2 <= end) {
                int u16 = Short.toUnsignedInt(PrefixVarint.getShortLE(packet, pos)) >>> 2;
                fnum = u16 >>> 4;
                wt = u16 & 0x0F;
                pos += 2;
            } else {
                if (scratch == null) {
                    scratch = new long[1];
                }
                int tn = PrefixVarint.tryDecode(packet, pos, end, scratch);
                if (tn == 0 || Long.compareUnsigned(scratch[0] >>> 4, WireType.MAX_FIELD_NUMBER) > 0) {
                    break;
                }
                fnum = (int) (scratch[0] >>> 4);
                wt = (int) (scratch[0] & 0x0FL);
                pos += tn;
            }

            if (fnum == 0 && wt != WireType.WIRE_SUB_MSG) {
                break;
            }

            int nextPos = skipValidatedHeader(packet, pos, end, wt);
            if (nextPos < 0) {
                break;
            }
            pos = nextPos;
            int fieldEnd = pos;
            validEnd = fieldEnd;

            long bit = (fnum > 0 && fnum < 64) ? (1L << fnum) : 0L;
            if ((dirtyMask & bit) != 0L) {
                if (cleanStart < fieldStart) {
                    enc.writeRawBytes(packet, cleanStart, fieldStart - cleanStart);
                }
                if ((emitted & bit) == 0L) {
                    appendDirty.writeDirtyField(enc, fnum);
                    emitted |= bit;
                }
                cleanStart = fieldEnd;
            } else {
                emitted |= bit;
            }
        }

        if (cleanStart < validEnd) {
            enc.writeRawBytes(packet, cleanStart, validEnd - cleanStart);
        }

        long rem = dirtyMask & ~emitted;
        if (rem != 0L) {
            int limit = Math.min(maxField, 63);
            for (int fnum = 1; fnum <= limit; fnum++) {
                if ((rem & (1L << fnum)) != 0L) {
                    appendDirty.writeDirtyField(enc, fnum);
                }
            }
        }

        return enc.toByteArray();
    }

    public static byte[] spliceFields(
            byte[] packet, long dirtyMask, int maxField, DirtyFieldWriter appendDirty) {
        return spliceFields(
            packet,
            0,
            packet == null ? 0 : packet.length,
            dirtyMask,
            maxField,
            appendDirty,
            null
        );
    }
}
