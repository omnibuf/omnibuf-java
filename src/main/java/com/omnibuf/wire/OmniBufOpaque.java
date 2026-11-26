package com.omnibuf.wire;

import com.omnibuf.OmniBufException;
import com.omnibuf.SchemaMeta;
import com.omnibuf.UnmarshalOptions;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
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

    @FunctionalInterface
    public interface OpaqueElementReader<T> {
        T read(OmniBufDecoder decoder);
    }

    @FunctionalInterface
    public interface SubMsgFactory<T> {
        T create(byte[] buf, int offset, int length);
    }

    public record LocatedField(int valueOffset, int valueEnd, int wireType, boolean found) {
        public static final LocatedField NOT_FOUND = new LocatedField(0, 0, 0, false);
    }

    public record PacketView(byte[] buffer, int offset, int length) {
        public PacketView {
            checkSliceBounds(buffer, offset, length);
        }

        public byte[] toByteArray() {
            if (offset == 0 && length == buffer.length) {
                return Arrays.copyOf(buffer, length);
            }
            return Arrays.copyOfRange(buffer, offset, offset + length);
        }

        public ByteBuffer asByteBuffer() {
            return ByteBuffer.wrap(buffer, offset, length).asReadOnlyBuffer();
        }
    }

    private static final byte[] EMPTY_BYTES = new byte[0];
    private static final long[] EMPTY_FIELD_INDEX = new long[0];
    private static final int MAX_PACKED_LEN = 0x07FF_FFFF;
    private static final long LARGE_VALUE_SENTINEL = -2L;
    private static final long MULTI_SEGMENT_SENTINEL = -3L;

    private OmniBufOpaque() {}

    public static void checkSliceBounds(byte[] packet, int offset, int length) {
        if (packet == null || offset < 0 || length < 0 || length > packet.length - offset) {
            throw new OmniBufException("omnibuf: packet slice out of bounds");
        }
    }

    /**
     * Zero-allocation field locator for packets at any valid array offset up to {@code Integer.MAX_VALUE}.
     * Packs {@code (((long) valStart) << 32) | (((long) valLen) << 4) | (wt & 0x0FL)} when
     * {@code valLen <= MAX_PACKED_LEN}, returns {@link #LARGE_VALUE_SENTINEL} if the matching
     * field's payload length exceeds {@code MAX_PACKED_LEN}, or {@code -1L} if absent.
     * Throws {@link OmniBufException} if the packet slice bounds, framing, or Field #0 are malformed.
     */
    private static long locateFieldPacked(
            byte[] packet, int offset, int length, int targetField, int wireMask) {
        checkSliceBounds(packet, offset, length);
        int end = offset + length;
        int pos = offset;
        long found = -1L;

        while (pos < end) {
            int fnum;
            int wt;
            int b0 = packet[pos] & 0xFF;
            if ((b0 & 1) != 0) {
                int tag = b0 >>> 1;
                fnum = tag >>> 4;
                wt = tag & 0x0F;
                pos += 1;
            } else if ((b0 & 2) != 0) {
                if (end - pos < 2) {
                    throw new OmniBufException("omnibuf: truncated 2-byte tag in packet");
                }
                int tag = Short.toUnsignedInt(PrefixVarint.getShortLE(packet, pos)) >>> 2;
                fnum = tag >>> 4;
                wt = tag & 0x0F;
                pos += 2;
            } else {
                long tagPacked = decodeWideTag(packet, pos, end);
                if (tagPacked < 0L) {
                    throw new OmniBufException("omnibuf: malformed or truncated tag in packet");
                }
                pos += (int) (tagPacked >>> 33);
                fnum = (int) ((tagPacked >>> 4) & 0x1FFF_FFFFL);
                wt = (int) (tagPacked & 0x0FL);
            }
            if (fnum == 0) {
                pos = validateField0(packet, pos, end, wt);
                continue;
            }

            int valStart = pos;
            int nextPos = skipValidatedHeader(packet, pos, end, wt);
            if (nextPos < 0) {
                throw new OmniBufException("omnibuf: malformed or truncated field payload in packet");
            }
            if (fnum == targetField && ((wireMask >>> wt) & 1) != 0) {
                int valLen = nextPos - valStart;
                if (valLen <= MAX_PACKED_LEN) {
                    found = (((long) valStart) << 32) | (((long) valLen) << 4) | (wt & 0x0FL);
                } else {
                    found = LARGE_VALUE_SENTINEL;
                }
            }
            pos = nextPos;
        }
        return found;
    }

    /**
     * Scans {@code packet[offset..offset+length]} once and populates a field-offset directory for
     * field numbers {@code 1..maxField}. Throws {@link OmniBufException} on malformed framing or
     * invalid Field #0.
     */
    public static long[] buildFieldIndex(byte[] packet, int offset, int length, int maxField) {
        checkSliceBounds(packet, offset, length);
        if (maxField <= 0) {
            validateTopLevelHeaders(packet, offset, offset + length);
            return EMPTY_FIELD_INDEX;
        }
        long[] index = new long[maxField + 1];
        Arrays.fill(index, -1L);
        int end = offset + length;
        int pos = offset;

        while (pos < end) {
            int fnum;
            int wt;
            int b0 = packet[pos] & 0xFF;
            if ((b0 & 1) != 0) {
                int tag = b0 >>> 1;
                fnum = tag >>> 4;
                wt = tag & 0x0F;
                pos += 1;
            } else if ((b0 & 2) != 0) {
                if (end - pos < 2) {
                    throw new OmniBufException("omnibuf: truncated 2-byte tag in packet");
                }
                int tag = Short.toUnsignedInt(PrefixVarint.getShortLE(packet, pos)) >>> 2;
                fnum = tag >>> 4;
                wt = tag & 0x0F;
                pos += 2;
            } else {
                long tagPacked = decodeWideTag(packet, pos, end);
                if (tagPacked < 0L) {
                    throw new OmniBufException("omnibuf: malformed or truncated tag in packet");
                }
                pos += (int) (tagPacked >>> 33);
                fnum = (int) ((tagPacked >>> 4) & 0x1FFF_FFFFL);
                wt = (int) (tagPacked & 0x0FL);
            }
            if (fnum == 0) {
                pos = validateField0(packet, pos, end, wt);
                continue;
            }

            int valStart = pos;
            int nextPos = skipValidatedHeader(packet, pos, end, wt);
            if (nextPos < 0) {
                throw new OmniBufException("omnibuf: malformed or truncated field payload in packet");
            }
            if (fnum <= maxField) {
                long prev = index[fnum];
                if (prev != -1L && (wt == WireType.WIRE_SUB_MSG || wt == WireType.WIRE_ARRAY || wt == WireType.WIRE_MAP)) {
                    index[fnum] = MULTI_SEGMENT_SENTINEL;
                } else {
                    int valLen = nextPos - valStart;
                    if (valLen <= MAX_PACKED_LEN) {
                        index[fnum] = (((long) valStart) << 32) | (((long) valLen) << 4) | (wt & 0x0FL);
                    } else {
                        index[fnum] = LARGE_VALUE_SENTINEL;
                    }
                }
            }
            pos = nextPos;
        }
        return index;
    }

    private static long lookupIndexedPacked(
            long[] index, byte[] packet, int offset, int length, int fieldNum, int wireMask) {
        if (fieldNum <= 0 || fieldNum >= index.length) {
            return fieldNum > 0 && index != EMPTY_FIELD_INDEX
                ? locateFieldPacked(packet, offset, length, fieldNum, wireMask)
                : -1L;
        }
        long packed = index[fieldNum];
        if (packed == -1L) {
            return -1L;
        }
        if (packed == LARGE_VALUE_SENTINEL || packed == MULTI_SEGMENT_SENTINEL) {
            return locateFieldPacked(packet, offset, length, fieldNum, wireMask);
        }
        int wt = ((int) packed) & 0x0F;
        if (((wireMask >>> wt) & 1) != 0) {
            return packed;
        }
        // An earlier occurrence of fieldNum may have had a matching wire type before a mismatched override.
        return locateFieldPacked(packet, offset, length, fieldNum, wireMask);
    }

    /**
     * Decodes a 4-byte or 9-byte Prefix-Varint tag at {@code packet[pos..end]} without heap
     * allocation, returning {@code (((long) tagBytes) << 33) | (fnum << 4) | wt}, or {@code -1L}
     * on truncation, reserved marker, or {@code fnum > MAX_FIELD_NUMBER}.
     */
    private static long decodeWideTag(byte[] packet, int pos, int end) {
        int b0 = packet[pos] & 0xFF;
        long tag;
        int tn;
        if ((b0 & 4) != 0) {
            if (end - pos < 4) {
                return -1L;
            }
            tn = 4;
            tag = Integer.toUnsignedLong(PrefixVarint.getIntLE(packet, pos)) >>> 3;
        } else if (b0 == 0) {
            if (end - pos < 9) {
                return -1L;
            }
            tn = 9;
            tag = PrefixVarint.getLongLE(packet, pos + 1);
        } else {
            return -1L;
        }
        long fnum = tag >>> 4;
        if (Long.compareUnsigned(fnum, WireType.MAX_FIELD_NUMBER) > 0) {
            return -1L;
        }
        return (((long) tn) << 33) | (tag & 0x1_FFFF_FFFFL);
    }

    /**
     * Scans all top-level field headers in {@code packet[offset..offset+length]} in O(1) time per
     * header and returns the last occurrence matching {@code targetField} and {@code wireMask}.
     * Returns {@link LocatedField#NOT_FOUND} if absent, or throws {@link OmniBufException} if the
     * packet framing is malformed.
     */
    public static LocatedField locateField(
            byte[] packet, int offset, int length, int targetField, int wireMask) {
        long packed = locateFieldPacked(packet, offset, length, targetField, wireMask);
        if (packed >= 0L) {
            int valStart = (int) (packed >>> 32);
            int valLen = ((int) packed) >>> 4;
            int wt = ((int) packed) & 0x0F;
            return new LocatedField(valStart, valStart + valLen, wt, true);
        }
        if (packed == LARGE_VALUE_SENTINEL) {
            return locateFieldGeneral(packet, offset, length, targetField, wireMask);
        }
        return LocatedField.NOT_FOUND;
    }

    private static LocatedField locateFieldGeneral(
            byte[] packet, int offset, int length, int targetField, int wireMask) {
        checkSliceBounds(packet, offset, length);
        int pos = offset;
        int end = offset + length;
        int lastValStart = -1;
        int lastValEnd = -1;
        int lastWT = 0;

        while (pos < end) {
            int fnum;
            int wt;
            int b0 = packet[pos] & 0xFF;
            if ((b0 & 1) != 0) {
                fnum = b0 >>> 5;
                wt = (b0 >>> 1) & 0x0F;
                pos += 1;
            } else if ((b0 & 2) != 0) {
                if (end - pos < 2) {
                    throw new OmniBufException("omnibuf: truncated 2-byte tag in packet");
                }
                int u16 = Short.toUnsignedInt(PrefixVarint.getShortLE(packet, pos)) >>> 2;
                fnum = u16 >>> 4;
                wt = u16 & 0x0F;
                pos += 2;
            } else {
                long tagPacked = decodeWideTag(packet, pos, end);
                if (tagPacked < 0L) {
                    throw new OmniBufException("omnibuf: malformed or truncated tag in packet");
                }
                pos += (int) (tagPacked >>> 33);
                fnum = (int) ((tagPacked >>> 4) & 0x1FFF_FFFFL);
                wt = (int) (tagPacked & 0x0FL);
            }
            if (fnum == 0) {
                pos = validateField0(packet, pos, end, wt);
                continue;
            }

            int valStart = pos;
            int nextPos = skipValidatedHeader(packet, pos, end, wt);
            if (nextPos < 0) {
                throw new OmniBufException("omnibuf: malformed or truncated field payload in packet");
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
        return switch (wt) {
            case WireType.WIRE_UINT, WireType.WIRE_SINT -> PrefixVarint.skipVarint(packet, pos, end);
            case WireType.WIRE_BOOL_FALSE, WireType.WIRE_BOOL_TRUE -> pos <= end ? pos : -1;
            case WireType.WIRE_FIXED32, WireType.WIRE_SFIXED32, WireType.WIRE_FLOAT32 ->
                end - pos >= 4 ? pos + 4 : -1;
            case WireType.WIRE_FIXED64, WireType.WIRE_SFIXED64, WireType.WIRE_FLOAT64 ->
                end - pos >= 8 ? pos + 8 : -1;
            case WireType.WIRE_STRING, WireType.WIRE_BYTES, WireType.WIRE_SUB_MSG -> {
                if (pos >= end) {
                    yield -1;
                }
                int b0 = packet[pos] & 0xFF;
                if ((b0 & 1) != 0) {
                    int len = b0 >>> 1;
                    yield end - pos - 1 >= len ? pos + 1 + len : -1;
                }
                if ((b0 & 3) == 2 && end - pos >= 2) {
                    int len = Short.toUnsignedInt(PrefixVarint.getShortLE(packet, pos)) >>> 2;
                    yield end - pos - 2 >= len ? pos + 2 + len : -1;
                }
                long packed = PrefixVarint.tryReadLengthSlicePacked(packet, pos, end, 0);
                yield packed < 0L ? -1 : (int) packed;
            }
            case WireType.WIRE_ARRAY -> {
                long packed = PrefixVarint.tryReadLengthSlicePacked(packet, pos, end, 2);
                if (packed < 0L) {
                    yield -1;
                }
                int bodyStart = (int) (packed >>> 32);
                int bodyEnd = (int) packed;
                yield OmniBufDecoder.isValidArrayHeader(packet, bodyStart, bodyEnd) ? bodyEnd : -1;
            }
            case WireType.WIRE_MAP -> {
                long packed = PrefixVarint.tryReadLengthSlicePacked(packet, pos, end, 3);
                if (packed < 0L) {
                    yield -1;
                }
                int bodyStart = (int) (packed >>> 32);
                int bodyEnd = (int) packed;
                yield OmniBufDecoder.isValidMapHeader(packet, bodyStart, bodyEnd) ? bodyEnd : -1;
            }
            case WireType.WIRE_EXTENDED -> {
                long packed = PrefixVarint.tryReadLengthSlicePacked(packet, pos, end, 1);
                yield packed < 0L ? -1 : (int) packed;
            }
            default -> -1;
        };
    }

    private static int validateField0(byte[] packet, int pos, int end, int wt) {
        if (wt != WireType.WIRE_SUB_MSG) {
            throw new OmniBufException("omnibuf: Field #0 requires WireSubMsg");
        }
        long packed = PrefixVarint.readLengthSlicePacked(packet, pos, end, 0);
        int start = (int) (packed >>> 32);
        int subEnd = (int) packed;
        SchemaMeta.decode(
            packet,
            start,
            subEnd - start,
            1,
            OmniBufDecoder.MAX_CONTAINER_DEPTH,
            UnmarshalOptions.DEFAULT_MAX_COLLECTION_ELEMENTS
        );
        return subEnd;
    }

    private static void validateTopLevelHeaders(byte[] packet, int pos, int end) {
        while (pos < end) {
            int fnum;
            int wt;
            int b0 = packet[pos] & 0xFF;
            if ((b0 & 1) != 0) {
                int tag = b0 >>> 1;
                fnum = tag >>> 4;
                wt = tag & 0x0F;
                pos += 1;
            } else if ((b0 & 2) != 0) {
                if (end - pos < 2) {
                    throw new OmniBufException("omnibuf: truncated 2-byte tag in packet");
                }
                int u16 = Short.toUnsignedInt(PrefixVarint.getShortLE(packet, pos)) >>> 2;
                fnum = u16 >>> 4;
                wt = u16 & 0x0F;
                pos += 2;
            } else {
                long tagPacked = decodeWideTag(packet, pos, end);
                if (tagPacked < 0L) {
                    throw new OmniBufException("omnibuf: malformed or truncated tag in packet");
                }
                pos += (int) (tagPacked >>> 33);
                fnum = (int) ((tagPacked >>> 4) & 0x1FFF_FFFFL);
                wt = (int) (tagPacked & 0x0FL);
            }
            if (fnum == 0) {
                pos = validateField0(packet, pos, end, wt);
                continue;
            }
            int nextPos = skipValidatedHeader(packet, pos, end, wt);
            if (nextPos < 0) {
                throw new OmniBufException("omnibuf: malformed or truncated field payload in packet");
            }
            pos = nextPos;
        }
    }

    // ========================================================================
    // Lazy Scalar Readers
    // ========================================================================

    public static long readUint64(byte[] packet, int offset, int length, int fieldNum) {
        long packed = locateFieldPacked(packet, offset, length, fieldNum, 1 << WireType.WIRE_UINT);
        if (packed < 0L) {
            return 0L;
        }
        int valStart = (int) (packed >>> 32);
        int valEnd = valStart + (((int) packed) >>> 4);
        return PrefixVarint.decodeValueOrZero(packet, valStart, valEnd);
    }

    public static int readUint32(byte[] packet, int offset, int length, int fieldNum) {
        return (int) readUint64(packet, offset, length, fieldNum);
    }

    public static long readSint64(byte[] packet, int offset, int length, int fieldNum) {
        long packed = locateFieldPacked(packet, offset, length, fieldNum, 1 << WireType.WIRE_SINT);
        if (packed < 0L) {
            return 0L;
        }
        int valStart = (int) (packed >>> 32);
        int valEnd = valStart + (((int) packed) >>> 4);
        return PrefixVarint.decodeZigZag64(PrefixVarint.decodeValueOrZero(packet, valStart, valEnd));
    }

    public static int readSint32(byte[] packet, int offset, int length, int fieldNum) {
        return (int) readSint64(packet, offset, length, fieldNum);
    }

    public static boolean readBool(byte[] packet, int offset, int length, int fieldNum) {
        long packed = locateFieldPacked(
            packet,
            offset,
            length,
            fieldNum,
            (1 << WireType.WIRE_BOOL_FALSE) | (1 << WireType.WIRE_BOOL_TRUE)
        );
        return packed >= 0L && (((int) packed) & 0x0F) == WireType.WIRE_BOOL_TRUE;
    }

    public static int readFixed32(byte[] packet, int offset, int length, int fieldNum) {
        long packed = locateFieldPacked(packet, offset, length, fieldNum, 1 << WireType.WIRE_FIXED32);
        if (packed < 0L) {
            return 0;
        }
        int valStart = (int) (packed >>> 32);
        int valLen = ((int) packed) >>> 4;
        return valLen >= 4 ? PrefixVarint.getIntLE(packet, valStart) : 0;
    }

    public static int readSfixed32(byte[] packet, int offset, int length, int fieldNum) {
        long packed = locateFieldPacked(packet, offset, length, fieldNum, 1 << WireType.WIRE_SFIXED32);
        if (packed < 0L) {
            return 0;
        }
        int valStart = (int) (packed >>> 32);
        int valLen = ((int) packed) >>> 4;
        return valLen >= 4 ? PrefixVarint.getIntLE(packet, valStart) : 0;
    }

    public static long readFixed64(byte[] packet, int offset, int length, int fieldNum) {
        long packed = locateFieldPacked(packet, offset, length, fieldNum, 1 << WireType.WIRE_FIXED64);
        if (packed < 0L) {
            return 0L;
        }
        int valStart = (int) (packed >>> 32);
        int valLen = ((int) packed) >>> 4;
        return valLen >= 8 ? PrefixVarint.getLongLE(packet, valStart) : 0L;
    }

    public static long readSfixed64(byte[] packet, int offset, int length, int fieldNum) {
        long packed = locateFieldPacked(packet, offset, length, fieldNum, 1 << WireType.WIRE_SFIXED64);
        if (packed < 0L) {
            return 0L;
        }
        int valStart = (int) (packed >>> 32);
        int valLen = ((int) packed) >>> 4;
        return valLen >= 8 ? PrefixVarint.getLongLE(packet, valStart) : 0L;
    }

    public static float readFloat32(byte[] packet, int offset, int length, int fieldNum) {
        long packed = locateFieldPacked(packet, offset, length, fieldNum, 1 << WireType.WIRE_FLOAT32);
        if (packed < 0L) {
            return 0.0f;
        }
        int valStart = (int) (packed >>> 32);
        int valLen = ((int) packed) >>> 4;
        return valLen >= 4 ? Float.intBitsToFloat(PrefixVarint.getIntLE(packet, valStart)) : 0.0f;
    }

    public static double readFloat64(byte[] packet, int offset, int length, int fieldNum) {
        long packed = locateFieldPacked(packet, offset, length, fieldNum, 1 << WireType.WIRE_FLOAT64);
        if (packed < 0L) {
            return 0.0d;
        }
        int valStart = (int) (packed >>> 32);
        int valLen = ((int) packed) >>> 4;
        return valLen >= 8 ? Double.longBitsToDouble(PrefixVarint.getLongLE(packet, valStart)) : 0.0d;
    }

    public static String readString(byte[] packet, int offset, int length, int fieldNum) {
        long packed = locateFieldPacked(packet, offset, length, fieldNum, 1 << WireType.WIRE_STRING);
        int p;
        int valEnd;
        if (packed >= 0L) {
            p = (int) (packed >>> 32);
            valEnd = p + (((int) packed) >>> 4);
        } else if (packed == LARGE_VALUE_SENTINEL) {
            LocatedField loc = locateFieldGeneral(packet, offset, length, fieldNum, 1 << WireType.WIRE_STRING);
            if (!loc.found()) {
                return "";
            }
            p = loc.valueOffset();
            valEnd = loc.valueEnd();
        } else {
            return "";
        }
        int b0 = packet[p] & 0xFF;
        if ((b0 & 1) != 0) {
            int strLen = b0 >>> 1;
            return Utf8Validator.decodeStrictUnchecked(packet, p + 1, strLen);
        }
        long slicePacked = PrefixVarint.readLengthSlicePacked(packet, p, valEnd, 0);
        int start = (int) (slicePacked >>> 32);
        int end = (int) slicePacked;
        return Utf8Validator.decodeStrictUnchecked(packet, start, end - start);
    }

    public static byte[] readBytes(byte[] packet, int offset, int length, int fieldNum) {
        long packed = locateFieldPacked(packet, offset, length, fieldNum, 1 << WireType.WIRE_BYTES);
        int p;
        int valEnd;
        if (packed >= 0L) {
            p = (int) (packed >>> 32);
            valEnd = p + (((int) packed) >>> 4);
        } else if (packed == LARGE_VALUE_SENTINEL) {
            LocatedField loc = locateFieldGeneral(packet, offset, length, fieldNum, 1 << WireType.WIRE_BYTES);
            if (!loc.found()) {
                return EMPTY_BYTES;
            }
            p = loc.valueOffset();
            valEnd = loc.valueEnd();
        } else {
            return EMPTY_BYTES;
        }
        long slicePacked = PrefixVarint.readLengthSlicePacked(packet, p, valEnd, 0);
        int start = (int) (slicePacked >>> 32);
        int end = (int) slicePacked;
        return start == end ? EMPTY_BYTES : Arrays.copyOfRange(packet, start, end);
    }

    public static PacketView readBytesView(byte[] packet, int offset, int length, int fieldNum) {
        long packed = locateFieldPacked(packet, offset, length, fieldNum, 1 << WireType.WIRE_BYTES);
        int p;
        int valEnd;
        if (packed >= 0L) {
            p = (int) (packed >>> 32);
            valEnd = p + (((int) packed) >>> 4);
        } else if (packed == LARGE_VALUE_SENTINEL) {
            LocatedField loc = locateFieldGeneral(packet, offset, length, fieldNum, 1 << WireType.WIRE_BYTES);
            if (!loc.found()) {
                return new PacketView(EMPTY_BYTES, 0, 0);
            }
            p = loc.valueOffset();
            valEnd = loc.valueEnd();
        } else {
            return new PacketView(EMPTY_BYTES, 0, 0);
        }
        long slicePacked = PrefixVarint.readLengthSlicePacked(packet, p, valEnd, 0);
        int start = (int) (slicePacked >>> 32);
        int end = (int) slicePacked;
        return new PacketView(packet, start, end - start);
    }

    public static long readUint64Indexed(long[] index, byte[] packet, int offset, int length, int fieldNum) {
        long packed = lookupIndexedPacked(index, packet, offset, length, fieldNum, 1 << WireType.WIRE_UINT);
        if (packed < 0L) {
            return 0L;
        }
        int valStart = (int) (packed >>> 32);
        int valEnd = valStart + (((int) packed) >>> 4);
        return PrefixVarint.decodeValueOrZero(packet, valStart, valEnd);
    }

    public static int readUint32Indexed(long[] index, byte[] packet, int offset, int length, int fieldNum) {
        return (int) readUint64Indexed(index, packet, offset, length, fieldNum);
    }

    public static long readSint64Indexed(long[] index, byte[] packet, int offset, int length, int fieldNum) {
        long packed = lookupIndexedPacked(index, packet, offset, length, fieldNum, 1 << WireType.WIRE_SINT);
        if (packed < 0L) {
            return 0L;
        }
        int valStart = (int) (packed >>> 32);
        int valEnd = valStart + (((int) packed) >>> 4);
        return PrefixVarint.decodeZigZag64(PrefixVarint.decodeValueOrZero(packet, valStart, valEnd));
    }

    public static int readSint32Indexed(long[] index, byte[] packet, int offset, int length, int fieldNum) {
        return (int) readSint64Indexed(index, packet, offset, length, fieldNum);
    }

    public static boolean readBoolIndexed(long[] index, byte[] packet, int offset, int length, int fieldNum) {
        long packed = lookupIndexedPacked(
            index,
            packet,
            offset,
            length,
            fieldNum,
            (1 << WireType.WIRE_BOOL_FALSE) | (1 << WireType.WIRE_BOOL_TRUE)
        );
        return packed >= 0L && (((int) packed) & 0x0F) == WireType.WIRE_BOOL_TRUE;
    }

    public static int readFixed32Indexed(long[] index, byte[] packet, int offset, int length, int fieldNum) {
        long packed = lookupIndexedPacked(index, packet, offset, length, fieldNum, 1 << WireType.WIRE_FIXED32);
        if (packed < 0L) {
            return 0;
        }
        int valStart = (int) (packed >>> 32);
        int valLen = ((int) packed) >>> 4;
        return valLen >= 4 ? PrefixVarint.getIntLE(packet, valStart) : 0;
    }

    public static int readSfixed32Indexed(long[] index, byte[] packet, int offset, int length, int fieldNum) {
        long packed = lookupIndexedPacked(index, packet, offset, length, fieldNum, 1 << WireType.WIRE_SFIXED32);
        if (packed < 0L) {
            return 0;
        }
        int valStart = (int) (packed >>> 32);
        int valLen = ((int) packed) >>> 4;
        return valLen >= 4 ? PrefixVarint.getIntLE(packet, valStart) : 0;
    }

    public static long readFixed64Indexed(long[] index, byte[] packet, int offset, int length, int fieldNum) {
        long packed = lookupIndexedPacked(index, packet, offset, length, fieldNum, 1 << WireType.WIRE_FIXED64);
        if (packed < 0L) {
            return 0L;
        }
        int valStart = (int) (packed >>> 32);
        int valLen = ((int) packed) >>> 4;
        return valLen >= 8 ? PrefixVarint.getLongLE(packet, valStart) : 0L;
    }

    public static long readSfixed64Indexed(long[] index, byte[] packet, int offset, int length, int fieldNum) {
        long packed = lookupIndexedPacked(index, packet, offset, length, fieldNum, 1 << WireType.WIRE_SFIXED64);
        if (packed < 0L) {
            return 0L;
        }
        int valStart = (int) (packed >>> 32);
        int valLen = ((int) packed) >>> 4;
        return valLen >= 8 ? PrefixVarint.getLongLE(packet, valStart) : 0L;
    }

    public static float readFloat32Indexed(long[] index, byte[] packet, int offset, int length, int fieldNum) {
        long packed = lookupIndexedPacked(index, packet, offset, length, fieldNum, 1 << WireType.WIRE_FLOAT32);
        if (packed < 0L) {
            return 0.0f;
        }
        int valStart = (int) (packed >>> 32);
        int valLen = ((int) packed) >>> 4;
        return valLen >= 4 ? Float.intBitsToFloat(PrefixVarint.getIntLE(packet, valStart)) : 0.0f;
    }

    public static double readFloat64Indexed(long[] index, byte[] packet, int offset, int length, int fieldNum) {
        long packed = lookupIndexedPacked(index, packet, offset, length, fieldNum, 1 << WireType.WIRE_FLOAT64);
        if (packed < 0L) {
            return 0.0d;
        }
        int valStart = (int) (packed >>> 32);
        int valLen = ((int) packed) >>> 4;
        return valLen >= 8 ? Double.longBitsToDouble(PrefixVarint.getLongLE(packet, valStart)) : 0.0d;
    }

    public static String readStringIndexed(long[] index, byte[] packet, int offset, int length, int fieldNum) {
        long packed = lookupIndexedPacked(index, packet, offset, length, fieldNum, 1 << WireType.WIRE_STRING);
        if (packed == LARGE_VALUE_SENTINEL) {
            return readString(packet, offset, length, fieldNum);
        }
        if (packed < 0L) {
            return "";
        }
        int p = (int) (packed >>> 32);
        int valEnd = p + (((int) packed) >>> 4);
        int b0 = packet[p] & 0xFF;
        if ((b0 & 1) != 0) {
            int strLen = b0 >>> 1;
            return Utf8Validator.decodeStrictUnchecked(packet, p + 1, strLen);
        }
        long slicePacked = PrefixVarint.readLengthSlicePacked(packet, p, valEnd, 0);
        int start = (int) (slicePacked >>> 32);
        int end = (int) slicePacked;
        return Utf8Validator.decodeStrictUnchecked(packet, start, end - start);
    }

    public static byte[] readBytesIndexed(long[] index, byte[] packet, int offset, int length, int fieldNum) {
        long packed = lookupIndexedPacked(index, packet, offset, length, fieldNum, 1 << WireType.WIRE_BYTES);
        if (packed == LARGE_VALUE_SENTINEL) {
            return readBytes(packet, offset, length, fieldNum);
        }
        if (packed < 0L) {
            return EMPTY_BYTES;
        }
        int p = (int) (packed >>> 32);
        int valEnd = p + (((int) packed) >>> 4);
        long slicePacked = PrefixVarint.readLengthSlicePacked(packet, p, valEnd, 0);
        int start = (int) (slicePacked >>> 32);
        int end = (int) slicePacked;
        return start == end ? EMPTY_BYTES : Arrays.copyOfRange(packet, start, end);
    }

    public static PacketView readBytesViewIndexed(long[] index, byte[] packet, int offset, int length, int fieldNum) {
        long packed = lookupIndexedPacked(index, packet, offset, length, fieldNum, 1 << WireType.WIRE_BYTES);
        if (packed == LARGE_VALUE_SENTINEL) {
            return readBytesView(packet, offset, length, fieldNum);
        }
        if (packed < 0L) {
            return new PacketView(EMPTY_BYTES, 0, 0);
        }
        int p = (int) (packed >>> 32);
        int valEnd = p + (((int) packed) >>> 4);
        long slicePacked = PrefixVarint.readLengthSlicePacked(packet, p, valEnd, 0);
        int start = (int) (slicePacked >>> 32);
        int end = (int) slicePacked;
        return new PacketView(packet, start, end - start);
    }

    public static PacketView readSubMsgViewIndexed(long[] index, byte[] packet, int offset, int length, int fieldNum) {
        if (fieldNum > 0 && fieldNum < index.length) {
            long packed = index[fieldNum];
            if (packed == -1L) {
                return null;
            }
            if (packed >= 0L && (((int) packed) & 0x0F) == WireType.WIRE_SUB_MSG) {
                int p = (int) (packed >>> 32);
                int valEnd = p + (((int) packed) >>> 4);
                long slicePacked = PrefixVarint.readLengthSlicePacked(packet, p, valEnd, 0);
                int start = (int) (slicePacked >>> 32);
                int end = (int) slicePacked;
                return new PacketView(packet, start, end - start);
            }
        }
        return readSubMsgView(packet, offset, length, fieldNum);
    }

    /**
     * Returns {@code true} iff {@code packet[offset..offset+length]} contains at least one
     * top-level occurrence of {@code fieldNum} matching {@code wireMask}.
     */
    public static boolean hasField(byte[] packet, int offset, int length, int fieldNum, int wireMask) {
        long packed = locateFieldPacked(packet, offset, length, fieldNum, wireMask);
        return packed >= 0L || packed == LARGE_VALUE_SENTINEL;
    }

    /**
     * Scans {@code packet[offset..offset+length]} and returns the field number from
     * {@code oneofFieldNumbers} that appeared last with a matching wire type in
     * {@code oneofWireMasks}, or {@code 0} if none of the oneof members are present.
     */
    public static int locateActiveOneof(
            byte[] packet,
            int offset,
            int length,
            int[] oneofFieldNumbers,
            int[] oneofWireMasks) {
        checkSliceBounds(packet, offset, length);
        int pos = offset;
        int end = offset + length;
        int activeField = 0;

        while (pos < end) {
            int fnum;
            int wt;
            int b0 = packet[pos] & 0xFF;
            if ((b0 & 1) != 0) {
                int tag = b0 >>> 1;
                fnum = tag >>> 4;
                wt = tag & 0x0F;
                pos += 1;
            } else if ((b0 & 2) != 0) {
                if (end - pos < 2) {
                    throw new OmniBufException("omnibuf: truncated 2-byte tag in packet");
                }
                int u16 = Short.toUnsignedInt(PrefixVarint.getShortLE(packet, pos)) >>> 2;
                fnum = u16 >>> 4;
                wt = u16 & 0x0F;
                pos += 2;
            } else {
                long tagPacked = decodeWideTag(packet, pos, end);
                if (tagPacked < 0L) {
                    throw new OmniBufException("omnibuf: malformed or truncated tag in packet");
                }
                pos += (int) (tagPacked >>> 33);
                fnum = (int) ((tagPacked >>> 4) & 0x1FFF_FFFFL);
                wt = (int) (tagPacked & 0x0FL);
            }
            if (fnum == 0) {
                pos = validateField0(packet, pos, end, wt);
                continue;
            }
            int nextPos = skipValidatedHeader(packet, pos, end, wt);
            if (nextPos < 0) {
                throw new OmniBufException("omnibuf: malformed or truncated field payload in packet");
            }
            for (int i = 0; i < oneofFieldNumbers.length; i++) {
                if (oneofFieldNumbers[i] == fnum && ((oneofWireMasks[i] >>> wt) & 1) != 0) {
                    activeField = fnum;
                    break;
                }
            }
            pos = nextPos;
        }
        return activeField;
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
        checkSliceBounds(packet, offset, length);
        int pos = offset;
        int end = offset + length;
        int firstStart = -1;
        int firstLen = 0;

        while (pos < end) {
            int fnum;
            int wt;
            int b0 = packet[pos] & 0xFF;
            if ((b0 & 1) != 0) {
                int tag = b0 >>> 1;
                fnum = tag >>> 4;
                wt = tag & 0x0F;
                pos += 1;
            } else if ((b0 & 2) != 0) {
                if (end - pos < 2) {
                    throw new OmniBufException("omnibuf: truncated 2-byte tag in readSubMsgView");
                }
                int u16 = Short.toUnsignedInt(PrefixVarint.getShortLE(packet, pos)) >>> 2;
                fnum = u16 >>> 4;
                wt = u16 & 0x0F;
                pos += 2;
            } else {
                long tagPacked = decodeWideTag(packet, pos, end);
                if (tagPacked < 0L) {
                    throw new OmniBufException("omnibuf: malformed or truncated tag in readSubMsgView");
                }
                pos += (int) (tagPacked >>> 33);
                fnum = (int) ((tagPacked >>> 4) & 0x1FFF_FFFFL);
                wt = (int) (tagPacked & 0x0FL);
            }
            if (fnum == 0) {
                pos = validateField0(packet, pos, end, wt);
                continue;
            }

            if (fnum == fieldNum && wt == WireType.WIRE_SUB_MSG) {
                long slicePacked = PrefixVarint.readLengthSlicePacked(packet, pos, end, 0);
                int sStart = (int) (slicePacked >>> 32);
                int sEnd = (int) slicePacked;
                int sLen = sEnd - sStart;
                pos = sEnd;
                if (firstStart < 0) {
                    firstStart = sStart;
                    firstLen = sLen;
                } else {
                    return readSubMsgViewMerged(
                        packet, pos, end, fieldNum, firstStart, firstLen, sStart, sLen
                    );
                }
            } else {
                int nextPos = skipValidatedHeader(packet, pos, end, wt);
                if (nextPos < 0) {
                    throw new OmniBufException("omnibuf: malformed or truncated field in readSubMsgView");
                }
                pos = nextPos;
            }
        }

        return firstStart < 0 ? null : new PacketView(packet, firstStart, firstLen);
    }

    /**
     * Locates occurrences of a {@code oneof} {@code WireSubMsg} member {@code fieldNum}, resetting
     * any previously accumulated slices whenever an intervening sibling member from
     * {@code oneofFieldNumbers} / {@code oneofWireMasks} appears on the wire.
     */
    public static PacketView readOneofSubMsgView(
            byte[] packet,
            int offset,
            int length,
            int fieldNum,
            int[] oneofFieldNumbers,
            int[] oneofWireMasks) {
        checkSliceBounds(packet, offset, length);
        int pos = offset;
        int end = offset + length;
        int firstStart = -1;
        int firstLen = 0;
        ByteArrayOutputStream merged = null;
        long mergedLen = 0L;

        while (pos < end) {
            int fnum;
            int wt;
            int b0 = packet[pos] & 0xFF;
            if ((b0 & 1) != 0) {
                int tag = b0 >>> 1;
                fnum = tag >>> 4;
                wt = tag & 0x0F;
                pos += 1;
            } else if ((b0 & 2) != 0) {
                if (end - pos < 2) {
                    throw new OmniBufException("omnibuf: truncated 2-byte tag in readOneofSubMsgView");
                }
                int u16 = Short.toUnsignedInt(PrefixVarint.getShortLE(packet, pos)) >>> 2;
                fnum = u16 >>> 4;
                wt = u16 & 0x0F;
                pos += 2;
            } else {
                long tagPacked = decodeWideTag(packet, pos, end);
                if (tagPacked < 0L) {
                    throw new OmniBufException("omnibuf: malformed or truncated tag in readOneofSubMsgView");
                }
                pos += (int) (tagPacked >>> 33);
                fnum = (int) ((tagPacked >>> 4) & 0x1FFF_FFFFL);
                wt = (int) (tagPacked & 0x0FL);
            }
            if (fnum == 0) {
                pos = validateField0(packet, pos, end, wt);
                continue;
            }

            if (fnum == fieldNum && wt == WireType.WIRE_SUB_MSG) {
                long slicePacked = PrefixVarint.readLengthSlicePacked(packet, pos, end, 0);
                int sStart = (int) (slicePacked >>> 32);
                int sEnd = (int) slicePacked;
                int sLen = sEnd - sStart;
                pos = sEnd;
                if (merged != null) {
                    OmniBufDecoder.validateMessage(packet, sStart, sLen, 1);
                    mergedLen += sLen;
                    if (mergedLen > UnmarshalOptions.DEFAULT_MAX_MESSAGE_BYTES) {
                        throw new OmniBufException(
                            "omnibuf: merged WireSubMsg length exceeds maxMessageBytes ("
                                + UnmarshalOptions.DEFAULT_MAX_MESSAGE_BYTES + ")"
                        );
                    }
                    merged.write(packet, sStart, sLen);
                } else if (firstStart < 0) {
                    firstStart = sStart;
                    firstLen = sLen;
                } else {
                    OmniBufDecoder.validateMessage(packet, firstStart, firstLen, 1);
                    OmniBufDecoder.validateMessage(packet, sStart, sLen, 1);
                    mergedLen = (long) firstLen + (long) sLen;
                    if (mergedLen > UnmarshalOptions.DEFAULT_MAX_MESSAGE_BYTES) {
                        throw new OmniBufException(
                            "omnibuf: merged WireSubMsg length exceeds maxMessageBytes ("
                                + UnmarshalOptions.DEFAULT_MAX_MESSAGE_BYTES + ")"
                        );
                    }
                    int initialCap = (int) Math.min(mergedLen + 32L, UnmarshalOptions.DEFAULT_MAX_MESSAGE_BYTES);
                    merged = new ByteArrayOutputStream(initialCap);
                    merged.write(packet, firstStart, firstLen);
                    merged.write(packet, sStart, sLen);
                }
            } else {
                int nextPos = skipValidatedHeader(packet, pos, end, wt);
                if (nextPos < 0) {
                    throw new OmniBufException("omnibuf: malformed or truncated field in readOneofSubMsgView");
                }
                for (int i = 0; i < oneofFieldNumbers.length; i++) {
                    if (oneofFieldNumbers[i] == fnum
                            && fnum != fieldNum
                            && ((oneofWireMasks[i] >>> wt) & 1) != 0) {
                        firstStart = -1;
                        firstLen = 0;
                        merged = null;
                        mergedLen = 0L;
                        break;
                    }
                }
                pos = nextPos;
            }
        }

        if (merged != null) {
            byte[] combined = merged.toByteArray();
            return new PacketView(combined, 0, combined.length);
        }
        return firstStart < 0 ? null : new PacketView(packet, firstStart, firstLen);
    }

    private static PacketView readSubMsgViewMerged(
            byte[] packet,
            int pos,
            int end,
            int fieldNum,
            int firstStart,
            int firstLen,
            int secondStart,
            int secondLen) {
        OmniBufDecoder.validateMessage(packet, firstStart, firstLen, 1);
        OmniBufDecoder.validateMessage(packet, secondStart, secondLen, 1);
        long totalLen = (long) firstLen + (long) secondLen;
        if (totalLen > UnmarshalOptions.DEFAULT_MAX_MESSAGE_BYTES) {
            throw new OmniBufException(
                "omnibuf: merged WireSubMsg length exceeds maxMessageBytes ("
                    + UnmarshalOptions.DEFAULT_MAX_MESSAGE_BYTES + ")"
            );
        }
        int initialCap = (int) Math.min(totalLen + 32L, UnmarshalOptions.DEFAULT_MAX_MESSAGE_BYTES);
        ByteArrayOutputStream merged = new ByteArrayOutputStream(initialCap);
        merged.write(packet, firstStart, firstLen);
        merged.write(packet, secondStart, secondLen);

        while (pos < end) {
            int fnum;
            int wt;
            int b0 = packet[pos] & 0xFF;
            if ((b0 & 1) != 0) {
                int tag = b0 >>> 1;
                fnum = tag >>> 4;
                wt = tag & 0x0F;
                pos += 1;
            } else if ((b0 & 2) != 0) {
                if (end - pos < 2) {
                    throw new OmniBufException("omnibuf: truncated 2-byte tag in readSubMsgView");
                }
                int u16 = Short.toUnsignedInt(PrefixVarint.getShortLE(packet, pos)) >>> 2;
                fnum = u16 >>> 4;
                wt = u16 & 0x0F;
                pos += 2;
            } else {
                long tagPacked = decodeWideTag(packet, pos, end);
                if (tagPacked < 0L) {
                    throw new OmniBufException("omnibuf: malformed or truncated tag in readSubMsgView");
                }
                pos += (int) (tagPacked >>> 33);
                fnum = (int) ((tagPacked >>> 4) & 0x1FFF_FFFFL);
                wt = (int) (tagPacked & 0x0FL);
            }
            if (fnum == 0) {
                pos = validateField0(packet, pos, end, wt);
                continue;
            }

            if (fnum == fieldNum && wt == WireType.WIRE_SUB_MSG) {
                long slicePacked = PrefixVarint.readLengthSlicePacked(packet, pos, end, 0);
                int sStart = (int) (slicePacked >>> 32);
                int sEnd = (int) slicePacked;
                int sLen = sEnd - sStart;
                OmniBufDecoder.validateMessage(packet, sStart, sLen, 1);
                totalLen += sLen;
                if (totalLen > UnmarshalOptions.DEFAULT_MAX_MESSAGE_BYTES) {
                    throw new OmniBufException(
                        "omnibuf: merged WireSubMsg length exceeds maxMessageBytes ("
                            + UnmarshalOptions.DEFAULT_MAX_MESSAGE_BYTES + ")"
                    );
                }
                merged.write(packet, sStart, sLen);
                pos = sEnd;
            } else {
                int nextPos = skipValidatedHeader(packet, pos, end, wt);
                if (nextPos < 0) {
                    throw new OmniBufException("omnibuf: malformed or truncated field in readSubMsgView");
                }
                pos = nextPos;
            }
        }

        byte[] combined = merged.toByteArray();
        return new PacketView(combined, 0, combined.length);
    }

    private static List<OmniBufDecoder.Slice> collectContainerBodies(
            byte[] packet, int offset, int length, int fieldNum, int targetWT) {
        checkSliceBounds(packet, offset, length);
        List<OmniBufDecoder.Slice> slices = null;
        int pos = offset;
        int end = offset + length;
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
                if (end - pos < 2) {
                    throw new OmniBufException("omnibuf: truncated 2-byte tag in container scan");
                }
                int u16 = Short.toUnsignedInt(PrefixVarint.getShortLE(packet, pos)) >>> 2;
                fnum = u16 >>> 4;
                wt = u16 & 0x0F;
                pos += 2;
            } else {
                long tagPacked = decodeWideTag(packet, pos, end);
                if (tagPacked < 0L) {
                    throw new OmniBufException("omnibuf: malformed or truncated tag in container scan");
                }
                pos += (int) (tagPacked >>> 33);
                fnum = (int) ((tagPacked >>> 4) & 0x1FFF_FFFFL);
                wt = (int) (tagPacked & 0x0FL);
            }
            if (fnum == 0) {
                pos = validateField0(packet, pos, end, wt);
                continue;
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
                    throw new OmniBufException("omnibuf: malformed or truncated field in container scan");
                }
                pos = nextPos;
            }
        }
        return slices == null ? Collections.emptyList() : slices;
    }

    private static OmniBufException arrayElemMismatch(int expectedWT, int actualWT) {
        return new OmniBufException(
            "omnibuf: WireArray element type mismatch: expected "
                + WireType.nameOf(expectedWT) + ", got " + WireType.nameOf(actualWT)
        );
    }

    private static OmniBufException mapTypeMismatch(int expKeyWT, int expValWT, int actKeyWT, int actValWT) {
        return new OmniBufException(
            "omnibuf: WireMap key/value type mismatch: expected "
                + WireType.nameOf(expKeyWT) + "/" + WireType.nameOf(expValWT)
                + ", got " + WireType.nameOf(actKeyWT) + "/" + WireType.nameOf(actValWT)
        );
    }

    private static void checkMaxCollectionElements(int maxCollectionElements) {
        if (maxCollectionElements < 0) {
            throw new OmniBufException(
                "omnibuf: maxCollectionElements must be non-negative: " + maxCollectionElements
            );
        }
    }

    private static void checkArrayAccumulatedCount(int currentSize, int nextCount, int maxCollectionElements) {
        if ((long) currentSize + nextCount > maxCollectionElements) {
            throw new OmniBufException("omnibuf: accumulated WireArray count exceeds maxCollectionElements");
        }
    }

    private static <K> void checkMapAccumulatedEntry(Map<K, ?> out, K key, int maxCollectionElements) {
        if (!out.containsKey(key) && out.size() >= maxCollectionElements) {
            throw new OmniBufException("omnibuf: accumulated WireMap count exceeds maxCollectionElements");
        }
    }

    public static List<String> readStringList(byte[] packet, int offset, int length, int fieldNum) {
        return readStringList(packet, offset, length, fieldNum, UnmarshalOptions.DEFAULT_MAX_COLLECTION_ELEMENTS);
    }

    public static List<String> readStringList(
            byte[] packet, int offset, int length, int fieldNum, int maxCollectionElements) {
        checkMaxCollectionElements(maxCollectionElements);
        List<OmniBufDecoder.Slice> bodies =
            collectContainerBodies(packet, offset, length, fieldNum, WireType.WIRE_ARRAY);
        if (bodies.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        for (OmniBufDecoder.Slice body : bodies) {
            OmniBufDecoder.ArrayHeader hdr =
                OmniBufDecoder.parseArrayHeader(packet, body.offset(), body.end(), maxCollectionElements);
            if (hdr.elemWireType() != WireType.WIRE_STRING) {
                throw arrayElemMismatch(WireType.WIRE_STRING, hdr.elemWireType());
            }
            checkArrayAccumulatedCount(out.size(), hdr.count(), maxCollectionElements);
            int p = hdr.payloadStart();
            int end = hdr.payloadEnd();
            for (int i = 0; i < hdr.count(); i++) {
                long packed = PrefixVarint.readLengthSlicePacked(packet, p, end, 0);
                int sStart = (int) (packed >>> 32);
                int sEnd = (int) packed;
                out.add(Utf8Validator.decodeStrictUnchecked(packet, sStart, sEnd - sStart));
                p = sEnd;
            }
            if (p != end) {
                throw new OmniBufException("omnibuf: WireArray has trailing payload bytes");
            }
        }
        return out;
    }

    public static List<byte[]> readBytesList(byte[] packet, int offset, int length, int fieldNum) {
        return readBytesList(packet, offset, length, fieldNum, UnmarshalOptions.DEFAULT_MAX_COLLECTION_ELEMENTS);
    }

    public static List<byte[]> readBytesList(
            byte[] packet, int offset, int length, int fieldNum, int maxCollectionElements) {
        checkMaxCollectionElements(maxCollectionElements);
        List<OmniBufDecoder.Slice> bodies =
            collectContainerBodies(packet, offset, length, fieldNum, WireType.WIRE_ARRAY);
        if (bodies.isEmpty()) {
            return Collections.emptyList();
        }
        List<byte[]> out = new ArrayList<>();
        for (OmniBufDecoder.Slice body : bodies) {
            OmniBufDecoder.ArrayHeader hdr =
                OmniBufDecoder.parseArrayHeader(packet, body.offset(), body.end(), maxCollectionElements);
            if (hdr.elemWireType() != WireType.WIRE_BYTES) {
                throw arrayElemMismatch(WireType.WIRE_BYTES, hdr.elemWireType());
            }
            checkArrayAccumulatedCount(out.size(), hdr.count(), maxCollectionElements);
            int p = hdr.payloadStart();
            int end = hdr.payloadEnd();
            for (int i = 0; i < hdr.count(); i++) {
                long packed = PrefixVarint.readLengthSlicePacked(packet, p, end, 0);
                int sStart = (int) (packed >>> 32);
                int sEnd = (int) packed;
                out.add(sStart == sEnd ? EMPTY_BYTES : Arrays.copyOfRange(packet, sStart, sEnd));
                p = sEnd;
            }
            if (p != end) {
                throw new OmniBufException("omnibuf: WireArray has trailing payload bytes");
            }
        }
        return out;
    }

    public static List<Integer> readUint32List(byte[] packet, int offset, int length, int fieldNum) {
        return readUint32List(packet, offset, length, fieldNum, UnmarshalOptions.DEFAULT_MAX_COLLECTION_ELEMENTS);
    }

    public static List<Integer> readUint32List(
            byte[] packet, int offset, int length, int fieldNum, int maxCollectionElements) {
        checkMaxCollectionElements(maxCollectionElements);
        List<OmniBufDecoder.Slice> bodies =
            collectContainerBodies(packet, offset, length, fieldNum, WireType.WIRE_ARRAY);
        if (bodies.isEmpty()) {
            return Collections.emptyList();
        }
        List<Integer> out = new ArrayList<>();
        for (OmniBufDecoder.Slice body : bodies) {
            OmniBufDecoder.ArrayHeader hdr =
                OmniBufDecoder.parseArrayHeader(packet, body.offset(), body.end(), maxCollectionElements);
            if (hdr.elemWireType() != WireType.WIRE_UINT) {
                throw arrayElemMismatch(WireType.WIRE_UINT, hdr.elemWireType());
            }
            checkArrayAccumulatedCount(out.size(), hdr.count(), maxCollectionElements);
            int p = hdr.payloadStart();
            int end = hdr.payloadEnd();
            for (int i = 0; i < hdr.count(); i++) {
                PrefixVarint.Decoded d = PrefixVarint.decode(packet, p, end);
                out.add((int) d.value());
                p += d.bytesRead();
            }
            if (p != end) {
                throw new OmniBufException("omnibuf: WireArray has trailing payload bytes");
            }
        }
        return out;
    }

    public static List<Long> readUint64List(byte[] packet, int offset, int length, int fieldNum) {
        return readUint64List(packet, offset, length, fieldNum, UnmarshalOptions.DEFAULT_MAX_COLLECTION_ELEMENTS);
    }

    public static List<Long> readUint64List(
            byte[] packet, int offset, int length, int fieldNum, int maxCollectionElements) {
        checkMaxCollectionElements(maxCollectionElements);
        List<OmniBufDecoder.Slice> bodies =
            collectContainerBodies(packet, offset, length, fieldNum, WireType.WIRE_ARRAY);
        if (bodies.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> out = new ArrayList<>();
        for (OmniBufDecoder.Slice body : bodies) {
            OmniBufDecoder.ArrayHeader hdr =
                OmniBufDecoder.parseArrayHeader(packet, body.offset(), body.end(), maxCollectionElements);
            if (hdr.elemWireType() != WireType.WIRE_UINT) {
                throw arrayElemMismatch(WireType.WIRE_UINT, hdr.elemWireType());
            }
            checkArrayAccumulatedCount(out.size(), hdr.count(), maxCollectionElements);
            int p = hdr.payloadStart();
            int end = hdr.payloadEnd();
            for (int i = 0; i < hdr.count(); i++) {
                PrefixVarint.Decoded d = PrefixVarint.decode(packet, p, end);
                out.add(d.value());
                p += d.bytesRead();
            }
            if (p != end) {
                throw new OmniBufException("omnibuf: WireArray has trailing payload bytes");
            }
        }
        return out;
    }

    public static List<Integer> readSint32List(byte[] packet, int offset, int length, int fieldNum) {
        return readSint32List(packet, offset, length, fieldNum, UnmarshalOptions.DEFAULT_MAX_COLLECTION_ELEMENTS);
    }

    public static List<Integer> readSint32List(
            byte[] packet, int offset, int length, int fieldNum, int maxCollectionElements) {
        checkMaxCollectionElements(maxCollectionElements);
        List<OmniBufDecoder.Slice> bodies =
            collectContainerBodies(packet, offset, length, fieldNum, WireType.WIRE_ARRAY);
        if (bodies.isEmpty()) {
            return Collections.emptyList();
        }
        List<Integer> out = new ArrayList<>();
        for (OmniBufDecoder.Slice body : bodies) {
            OmniBufDecoder.ArrayHeader hdr =
                OmniBufDecoder.parseArrayHeader(packet, body.offset(), body.end(), maxCollectionElements);
            if (hdr.elemWireType() != WireType.WIRE_SINT) {
                throw arrayElemMismatch(WireType.WIRE_SINT, hdr.elemWireType());
            }
            checkArrayAccumulatedCount(out.size(), hdr.count(), maxCollectionElements);
            int p = hdr.payloadStart();
            int end = hdr.payloadEnd();
            for (int i = 0; i < hdr.count(); i++) {
                PrefixVarint.Decoded d = PrefixVarint.decode(packet, p, end);
                out.add(PrefixVarint.decodeZigZag32(d.value()));
                p += d.bytesRead();
            }
            if (p != end) {
                throw new OmniBufException("omnibuf: WireArray has trailing payload bytes");
            }
        }
        return out;
    }

    public static List<Long> readSint64List(byte[] packet, int offset, int length, int fieldNum) {
        return readSint64List(packet, offset, length, fieldNum, UnmarshalOptions.DEFAULT_MAX_COLLECTION_ELEMENTS);
    }

    public static List<Long> readSint64List(
            byte[] packet, int offset, int length, int fieldNum, int maxCollectionElements) {
        checkMaxCollectionElements(maxCollectionElements);
        List<OmniBufDecoder.Slice> bodies =
            collectContainerBodies(packet, offset, length, fieldNum, WireType.WIRE_ARRAY);
        if (bodies.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> out = new ArrayList<>();
        for (OmniBufDecoder.Slice body : bodies) {
            OmniBufDecoder.ArrayHeader hdr =
                OmniBufDecoder.parseArrayHeader(packet, body.offset(), body.end(), maxCollectionElements);
            if (hdr.elemWireType() != WireType.WIRE_SINT) {
                throw arrayElemMismatch(WireType.WIRE_SINT, hdr.elemWireType());
            }
            checkArrayAccumulatedCount(out.size(), hdr.count(), maxCollectionElements);
            int p = hdr.payloadStart();
            int end = hdr.payloadEnd();
            for (int i = 0; i < hdr.count(); i++) {
                PrefixVarint.Decoded d = PrefixVarint.decode(packet, p, end);
                out.add(PrefixVarint.decodeZigZag64(d.value()));
                p += d.bytesRead();
            }
            if (p != end) {
                throw new OmniBufException("omnibuf: WireArray has trailing payload bytes");
            }
        }
        return out;
    }

    public static List<Boolean> readBoolList(byte[] packet, int offset, int length, int fieldNum) {
        return readBoolList(packet, offset, length, fieldNum, UnmarshalOptions.DEFAULT_MAX_COLLECTION_ELEMENTS);
    }

    public static List<Boolean> readBoolList(
            byte[] packet, int offset, int length, int fieldNum, int maxCollectionElements) {
        checkMaxCollectionElements(maxCollectionElements);
        List<OmniBufDecoder.Slice> bodies =
            collectContainerBodies(packet, offset, length, fieldNum, WireType.WIRE_ARRAY);
        if (bodies.isEmpty()) {
            return Collections.emptyList();
        }
        List<Boolean> out = new ArrayList<>();
        for (OmniBufDecoder.Slice body : bodies) {
            OmniBufDecoder.ArrayHeader hdr =
                OmniBufDecoder.parseArrayHeader(packet, body.offset(), body.end(), maxCollectionElements);
            if (hdr.elemWireType() != WireType.WIRE_BOOL_FALSE) {
                throw arrayElemMismatch(WireType.WIRE_BOOL_FALSE, hdr.elemWireType());
            }
            checkArrayAccumulatedCount(out.size(), hdr.count(), maxCollectionElements);
            int p = hdr.payloadStart();
            for (int i = 0; i < hdr.count(); i++) {
                int b = packet[p + i] & 0xFF;
                if (b > 1) {
                    throw new OmniBufException("omnibuf: invalid boolean byte in WireArray: " + b);
                }
                out.add(b == 1);
            }
        }
        return out;
    }

    public static List<Integer> readFixed32List(byte[] packet, int offset, int length, int fieldNum) {
        return readFixed32List(packet, offset, length, fieldNum, UnmarshalOptions.DEFAULT_MAX_COLLECTION_ELEMENTS);
    }

    public static List<Integer> readFixed32List(
            byte[] packet, int offset, int length, int fieldNum, int maxCollectionElements) {
        return readFixed32ListWithWireType(
            packet, offset, length, fieldNum, WireType.WIRE_FIXED32, maxCollectionElements
        );
    }

    public static List<Integer> readSfixed32List(byte[] packet, int offset, int length, int fieldNum) {
        return readSfixed32List(packet, offset, length, fieldNum, UnmarshalOptions.DEFAULT_MAX_COLLECTION_ELEMENTS);
    }

    public static List<Integer> readSfixed32List(
            byte[] packet, int offset, int length, int fieldNum, int maxCollectionElements) {
        return readFixed32ListWithWireType(
            packet, offset, length, fieldNum, WireType.WIRE_SFIXED32, maxCollectionElements
        );
    }

    private static List<Integer> readFixed32ListWithWireType(
            byte[] packet, int offset, int length, int fieldNum, int expectedElemWT, int maxCollectionElements) {
        checkMaxCollectionElements(maxCollectionElements);
        List<OmniBufDecoder.Slice> bodies =
            collectContainerBodies(packet, offset, length, fieldNum, WireType.WIRE_ARRAY);
        if (bodies.isEmpty()) {
            return Collections.emptyList();
        }
        List<Integer> out = new ArrayList<>();
        for (OmniBufDecoder.Slice body : bodies) {
            OmniBufDecoder.ArrayHeader hdr =
                OmniBufDecoder.parseArrayHeader(packet, body.offset(), body.end(), maxCollectionElements);
            if (hdr.elemWireType() != expectedElemWT) {
                throw arrayElemMismatch(expectedElemWT, hdr.elemWireType());
            }
            checkArrayAccumulatedCount(out.size(), hdr.count(), maxCollectionElements);
            int p = hdr.payloadStart();
            for (int i = 0; i < hdr.count(); i++) {
                out.add(PrefixVarint.getIntLE(packet, p + i * 4));
            }
        }
        return out;
    }

    public static List<Long> readFixed64List(byte[] packet, int offset, int length, int fieldNum) {
        return readFixed64List(packet, offset, length, fieldNum, UnmarshalOptions.DEFAULT_MAX_COLLECTION_ELEMENTS);
    }

    public static List<Long> readFixed64List(
            byte[] packet, int offset, int length, int fieldNum, int maxCollectionElements) {
        return readFixed64ListWithWireType(
            packet, offset, length, fieldNum, WireType.WIRE_FIXED64, maxCollectionElements
        );
    }

    public static List<Long> readSfixed64List(byte[] packet, int offset, int length, int fieldNum) {
        return readSfixed64List(packet, offset, length, fieldNum, UnmarshalOptions.DEFAULT_MAX_COLLECTION_ELEMENTS);
    }

    public static List<Long> readSfixed64List(
            byte[] packet, int offset, int length, int fieldNum, int maxCollectionElements) {
        return readFixed64ListWithWireType(
            packet, offset, length, fieldNum, WireType.WIRE_SFIXED64, maxCollectionElements
        );
    }

    private static List<Long> readFixed64ListWithWireType(
            byte[] packet, int offset, int length, int fieldNum, int expectedElemWT, int maxCollectionElements) {
        checkMaxCollectionElements(maxCollectionElements);
        List<OmniBufDecoder.Slice> bodies =
            collectContainerBodies(packet, offset, length, fieldNum, WireType.WIRE_ARRAY);
        if (bodies.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> out = new ArrayList<>();
        for (OmniBufDecoder.Slice body : bodies) {
            OmniBufDecoder.ArrayHeader hdr =
                OmniBufDecoder.parseArrayHeader(packet, body.offset(), body.end(), maxCollectionElements);
            if (hdr.elemWireType() != expectedElemWT) {
                throw arrayElemMismatch(expectedElemWT, hdr.elemWireType());
            }
            checkArrayAccumulatedCount(out.size(), hdr.count(), maxCollectionElements);
            int p = hdr.payloadStart();
            for (int i = 0; i < hdr.count(); i++) {
                out.add(PrefixVarint.getLongLE(packet, p + i * 8));
            }
        }
        return out;
    }

    public static List<Float> readFloat32List(byte[] packet, int offset, int length, int fieldNum) {
        return readFloat32List(packet, offset, length, fieldNum, UnmarshalOptions.DEFAULT_MAX_COLLECTION_ELEMENTS);
    }

    public static List<Float> readFloat32List(
            byte[] packet, int offset, int length, int fieldNum, int maxCollectionElements) {
        checkMaxCollectionElements(maxCollectionElements);
        List<OmniBufDecoder.Slice> bodies =
            collectContainerBodies(packet, offset, length, fieldNum, WireType.WIRE_ARRAY);
        if (bodies.isEmpty()) {
            return Collections.emptyList();
        }
        List<Float> out = new ArrayList<>();
        for (OmniBufDecoder.Slice body : bodies) {
            OmniBufDecoder.ArrayHeader hdr =
                OmniBufDecoder.parseArrayHeader(packet, body.offset(), body.end(), maxCollectionElements);
            if (hdr.elemWireType() != WireType.WIRE_FLOAT32) {
                throw arrayElemMismatch(WireType.WIRE_FLOAT32, hdr.elemWireType());
            }
            checkArrayAccumulatedCount(out.size(), hdr.count(), maxCollectionElements);
            int p = hdr.payloadStart();
            for (int i = 0; i < hdr.count(); i++) {
                out.add(Float.intBitsToFloat(PrefixVarint.getIntLE(packet, p + i * 4)));
            }
        }
        return out;
    }

    public static List<Double> readFloat64List(byte[] packet, int offset, int length, int fieldNum) {
        return readFloat64List(packet, offset, length, fieldNum, UnmarshalOptions.DEFAULT_MAX_COLLECTION_ELEMENTS);
    }

    public static List<Double> readFloat64List(
            byte[] packet, int offset, int length, int fieldNum, int maxCollectionElements) {
        checkMaxCollectionElements(maxCollectionElements);
        List<OmniBufDecoder.Slice> bodies =
            collectContainerBodies(packet, offset, length, fieldNum, WireType.WIRE_ARRAY);
        if (bodies.isEmpty()) {
            return Collections.emptyList();
        }
        List<Double> out = new ArrayList<>();
        for (OmniBufDecoder.Slice body : bodies) {
            OmniBufDecoder.ArrayHeader hdr =
                OmniBufDecoder.parseArrayHeader(packet, body.offset(), body.end(), maxCollectionElements);
            if (hdr.elemWireType() != WireType.WIRE_FLOAT64) {
                throw arrayElemMismatch(WireType.WIRE_FLOAT64, hdr.elemWireType());
            }
            checkArrayAccumulatedCount(out.size(), hdr.count(), maxCollectionElements);
            int p = hdr.payloadStart();
            for (int i = 0; i < hdr.count(); i++) {
                out.add(Double.longBitsToDouble(PrefixVarint.getLongLE(packet, p + i * 8)));
            }
        }
        return out;
    }

    public static List<PacketView> readSubMsgList(byte[] packet, int offset, int length, int fieldNum) {
        return readSubMsgList(packet, offset, length, fieldNum, UnmarshalOptions.DEFAULT_MAX_COLLECTION_ELEMENTS);
    }

    public static List<PacketView> readSubMsgList(
            byte[] packet, int offset, int length, int fieldNum, int maxCollectionElements) {
        return readSubMsgListMapped(packet, offset, length, fieldNum, maxCollectionElements, PacketView::new);
    }

    public static <T> List<T> readSubMsgListMapped(
            byte[] packet, int offset, int length, int fieldNum, SubMsgFactory<T> factory) {
        return readSubMsgListMapped(
            packet, offset, length, fieldNum, UnmarshalOptions.DEFAULT_MAX_COLLECTION_ELEMENTS, factory
        );
    }

    public static <T> List<T> readSubMsgListMapped(
            byte[] packet,
            int offset,
            int length,
            int fieldNum,
            int maxCollectionElements,
            SubMsgFactory<T> factory) {
        checkMaxCollectionElements(maxCollectionElements);
        List<OmniBufDecoder.Slice> bodies =
            collectContainerBodies(packet, offset, length, fieldNum, WireType.WIRE_ARRAY);
        if (bodies.isEmpty()) {
            return Collections.emptyList();
        }
        ArrayList<T> out = null;
        for (OmniBufDecoder.Slice body : bodies) {
            OmniBufDecoder.ArrayHeader hdr =
                OmniBufDecoder.parseArrayHeader(packet, body.offset(), body.end(), maxCollectionElements);
            if (hdr.elemWireType() != WireType.WIRE_SUB_MSG) {
                throw arrayElemMismatch(WireType.WIRE_SUB_MSG, hdr.elemWireType());
            }
            int count = hdr.count();
            int curSize = out == null ? 0 : out.size();
            checkArrayAccumulatedCount(curSize, count, maxCollectionElements);
            if (out == null) {
                out = new ArrayList<>(Math.min(count, 65_536));
            } else if (count > 0) {
                out.ensureCapacity(curSize + count);
            }
            int p = hdr.payloadStart();
            int end = hdr.payloadEnd();
            for (int i = 0; i < count; i++) {
                long packed = OmniBufDecoder.readSubMsgSpan(packet, p, end);
                int sStart = (int) (packed >>> 32);
                int sEnd = (int) packed;
                out.add(factory.create(packet, sStart, sEnd - sStart));
                p = sEnd;
            }
            if (p != end) {
                throw new OmniBufException("omnibuf: WireArray has trailing payload bytes");
            }
        }
        return out == null || out.isEmpty() ? Collections.emptyList() : out;
    }

    public static Map<String, Long> readMapStringUint64(
            byte[] packet, int offset, int length, int fieldNum) {
        return readMapStringUint64(packet, offset, length, fieldNum, UnmarshalOptions.DEFAULT_MAX_COLLECTION_ELEMENTS);
    }

    public static Map<String, Long> readMapStringUint64(
            byte[] packet, int offset, int length, int fieldNum, int maxCollectionElements) {
        checkMaxCollectionElements(maxCollectionElements);
        List<OmniBufDecoder.Slice> bodies =
            collectContainerBodies(packet, offset, length, fieldNum, WireType.WIRE_MAP);
        if (bodies.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Long> out = new LinkedHashMap<>();
        for (OmniBufDecoder.Slice body : bodies) {
            OmniBufDecoder.MapHeader hdr =
                OmniBufDecoder.parseMapHeader(packet, body.offset(), body.end(), maxCollectionElements);
            if (hdr.keyWireType() != WireType.WIRE_STRING || hdr.valWireType() != WireType.WIRE_UINT) {
                throw mapTypeMismatch(WireType.WIRE_STRING, WireType.WIRE_UINT, hdr.keyWireType(), hdr.valWireType());
            }
            int p = hdr.payloadStart();
            int end = hdr.payloadEnd();
            for (int i = 0; i < hdr.count(); i++) {
                long packed = PrefixVarint.readLengthSlicePacked(packet, p, end, 0);
                int kStart = (int) (packed >>> 32);
                int kEnd = (int) packed;
                String k = Utf8Validator.decodeStrictUnchecked(packet, kStart, kEnd - kStart);
                p = kEnd;
                PrefixVarint.Decoded vd = PrefixVarint.decode(packet, p, end);
                checkMapAccumulatedEntry(out, k, maxCollectionElements);
                out.put(k, vd.value());
                p += vd.bytesRead();
            }
            if (p != end) {
                throw new OmniBufException("omnibuf: WireMap has trailing payload bytes");
            }
        }
        return out;
    }

    /**
     * Reads and merges all {@code WireMap} occurrences for {@code fieldNum} matching
     * {@code expectedKeyWT -> expectedValWT}, throwing {@link OmniBufException} on type mismatch.
     */
    public static <K, V> Map<K, V> readMap(
            byte[] packet,
            int offset,
            int length,
            int fieldNum,
            int expectedKeyWT,
            int expectedValWT,
            OpaqueElementReader<K> keyReader,
            OpaqueElementReader<V> valReader) {
        return readMap(
            packet,
            offset,
            length,
            fieldNum,
            expectedKeyWT,
            expectedValWT,
            keyReader,
            valReader,
            UnmarshalOptions.DEFAULT_MAX_COLLECTION_ELEMENTS
        );
    }

    /**
     * Reads and merges all {@code WireMap} occurrences for {@code fieldNum} matching
     * {@code expectedKeyWT -> expectedValWT}, enforcing {@code maxCollectionElements} across
     * all merged map segments.
     */
    public static <K, V> Map<K, V> readMap(
            byte[] packet,
            int offset,
            int length,
            int fieldNum,
            int expectedKeyWT,
            int expectedValWT,
            OpaqueElementReader<K> keyReader,
            OpaqueElementReader<V> valReader,
            int maxCollectionElements) {
        checkMaxCollectionElements(maxCollectionElements);
        List<OmniBufDecoder.Slice> bodies =
            collectContainerBodies(packet, offset, length, fieldNum, WireType.WIRE_MAP);
        if (bodies.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<K, V> out = new LinkedHashMap<>();
        OmniBufDecoder dec = CodecPool.acquireDecoder(packet, offset, length);
        try {
            for (OmniBufDecoder.Slice body : bodies) {
                OmniBufDecoder.MapHeader hdr =
                    OmniBufDecoder.parseMapHeader(packet, body.offset(), body.end(), maxCollectionElements);
                if (hdr.keyWireType() != expectedKeyWT || hdr.valWireType() != expectedValWT) {
                    throw mapTypeMismatch(expectedKeyWT, expectedValWT, hdr.keyWireType(), hdr.valWireType());
                }
                int saved = dec.setSlice(hdr.payloadStart(), hdr.payloadEnd());
                for (int i = 0; i < hdr.count(); i++) {
                    K k = keyReader.read(dec);
                    V v = valReader.read(dec);
                    checkMapAccumulatedEntry(out, k, maxCollectionElements);
                    out.put(k, v);
                }
                dec.endContainer(saved);
            }
            return out;
        } finally {
            CodecPool.releaseDecoder(dec);
        }
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
        checkSliceBounds(packet, offset, length);
        if (target == null) {
            if (dirtyMask == 0L) {
                validateTopLevelHeaders(packet, offset, offset + length);
                if (length == 0) {
                    return EMPTY_BYTES;
                }
                return offset == 0 && length == packet.length
                    ? Arrays.copyOf(packet, length)
                    : Arrays.copyOfRange(packet, offset, offset + length);
            }
            OmniBufEncoder pooled = CodecPool.acquireEncoder();
            try {
                return spliceFieldsInto(packet, offset, length, dirtyMask, maxField, appendDirty, pooled);
            } finally {
                CodecPool.releaseEncoder(pooled);
            }
        }
        if (dirtyMask == 0L) {
            validateTopLevelHeaders(packet, offset, offset + length);
            target.detachIfAliased(packet, offset, length);
            target.writeRawBytes(packet, offset, length);
            return target.toByteArray();
        }
        target.detachIfAliased(packet, offset, length);
        return spliceFieldsInto(packet, offset, length, dirtyMask, maxField, appendDirty, target);
    }

    /**
     * Splices modified fields identified by {@code dirtyBits} (supporting arbitrary field numbers
     * {@code >= 64}) into {@code packet[offset..offset+length]}.
     */
    public static byte[] spliceFields(
            byte[] packet,
            int offset,
            int length,
            BitSet dirtyBits,
            int maxField,
            DirtyFieldWriter appendDirty,
            OmniBufEncoder target) {
        checkSliceBounds(packet, offset, length);
        boolean noDirty = dirtyBits == null || dirtyBits.isEmpty();
        if (target == null) {
            if (noDirty) {
                validateTopLevelHeaders(packet, offset, offset + length);
                if (length == 0) {
                    return EMPTY_BYTES;
                }
                return offset == 0 && length == packet.length
                    ? Arrays.copyOf(packet, length)
                    : Arrays.copyOfRange(packet, offset, offset + length);
            }
            OmniBufEncoder pooled = CodecPool.acquireEncoder();
            try {
                return spliceFieldsIntoBitSet(packet, offset, length, dirtyBits, maxField, appendDirty, pooled);
            } finally {
                CodecPool.releaseEncoder(pooled);
            }
        }
        if (noDirty) {
            validateTopLevelHeaders(packet, offset, offset + length);
            target.detachIfAliased(packet, offset, length);
            target.writeRawBytes(packet, offset, length);
            return target.toByteArray();
        }
        target.detachIfAliased(packet, offset, length);
        return spliceFieldsIntoBitSet(packet, offset, length, dirtyBits, maxField, appendDirty, target);
    }

    private static byte[] spliceFieldsInto(
            byte[] packet,
            int offset,
            int length,
            long dirtyMask,
            int maxField,
            DirtyFieldWriter appendDirty,
            OmniBufEncoder enc) {
        int pos = offset;
        int end = offset + length;
        int cleanStart = offset;
        long emitted = 0L;

        while (pos < end) {
            int fieldStart = pos;
            int fnum;
            int wt;
            int b0 = packet[pos] & 0xFF;
            if ((b0 & 1) != 0) {
                int tag = b0 >>> 1;
                fnum = tag >>> 4;
                wt = tag & 0x0F;
                pos += 1;
            } else if ((b0 & 2) != 0) {
                if (end - pos < 2) {
                    throw new OmniBufException("omnibuf: truncated 2-byte tag in spliceFields");
                }
                int u16 = Short.toUnsignedInt(PrefixVarint.getShortLE(packet, pos)) >>> 2;
                fnum = u16 >>> 4;
                wt = u16 & 0x0F;
                pos += 2;
            } else {
                long tagPacked = decodeWideTag(packet, pos, end);
                if (tagPacked < 0L) {
                    throw new OmniBufException("omnibuf: malformed or truncated tag in spliceFields");
                }
                pos += (int) (tagPacked >>> 33);
                fnum = (int) ((tagPacked >>> 4) & 0x1FFF_FFFFL);
                wt = (int) (tagPacked & 0x0FL);
            }

            int nextPos;
            if (fnum == 0) {
                nextPos = validateField0(packet, pos, end, wt);
            } else {
                nextPos = skipValidatedHeader(packet, pos, end, wt);
                if (nextPos < 0) {
                    throw new OmniBufException("omnibuf: malformed or truncated field payload in spliceFields");
                }
            }
            pos = nextPos;
            int fieldEnd = pos;

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

        if (cleanStart < end) {
            enc.writeRawBytes(packet, cleanStart, end - cleanStart);
        }

        long rem = dirtyMask & ~emitted;
        if (rem != 0L) {
            int limit = Math.min(maxField, 63);
            while (rem != 0L) {
                int fnum = Long.numberOfTrailingZeros(rem);
                if (fnum > limit) {
                    break;
                }
                if (fnum > 0) {
                    appendDirty.writeDirtyField(enc, fnum);
                }
                rem &= (rem - 1L);
            }
        }

        return enc.toByteArray();
    }

    private static byte[] spliceFieldsIntoBitSet(
            byte[] packet,
            int offset,
            int length,
            BitSet dirtyBits,
            int maxField,
            DirtyFieldWriter appendDirty,
            OmniBufEncoder enc) {
        int pos = offset;
        int end = offset + length;
        int cleanStart = offset;
        BitSet emitted = new BitSet(Math.min(maxField + 1, 1024));

        while (pos < end) {
            int fieldStart = pos;
            int fnum;
            int wt;
            int b0 = packet[pos] & 0xFF;
            if ((b0 & 1) != 0) {
                int tag = b0 >>> 1;
                fnum = tag >>> 4;
                wt = tag & 0x0F;
                pos += 1;
            } else if ((b0 & 2) != 0) {
                if (end - pos < 2) {
                    throw new OmniBufException("omnibuf: truncated 2-byte tag in spliceFields");
                }
                int u16 = Short.toUnsignedInt(PrefixVarint.getShortLE(packet, pos)) >>> 2;
                fnum = u16 >>> 4;
                wt = u16 & 0x0F;
                pos += 2;
            } else {
                long tagPacked = decodeWideTag(packet, pos, end);
                if (tagPacked < 0L) {
                    throw new OmniBufException("omnibuf: malformed or truncated tag in spliceFields");
                }
                pos += (int) (tagPacked >>> 33);
                fnum = (int) ((tagPacked >>> 4) & 0x1FFF_FFFFL);
                wt = (int) (tagPacked & 0x0FL);
            }

            int nextPos;
            if (fnum == 0) {
                nextPos = validateField0(packet, pos, end, wt);
            } else {
                nextPos = skipValidatedHeader(packet, pos, end, wt);
                if (nextPos < 0) {
                    throw new OmniBufException("omnibuf: malformed or truncated field payload in spliceFields");
                }
            }
            pos = nextPos;
            int fieldEnd = pos;

            if (fnum > 0 && dirtyBits.get(fnum)) {
                if (cleanStart < fieldStart) {
                    enc.writeRawBytes(packet, cleanStart, fieldStart - cleanStart);
                }
                if (!emitted.get(fnum)) {
                    appendDirty.writeDirtyField(enc, fnum);
                    emitted.set(fnum);
                }
                cleanStart = fieldEnd;
            }
        }

        if (cleanStart < end) {
            enc.writeRawBytes(packet, cleanStart, end - cleanStart);
        }

        for (int fnum = dirtyBits.nextSetBit(1);
                fnum >= 0 && fnum <= maxField;
                fnum = dirtyBits.nextSetBit(fnum + 1)) {
            if (!emitted.get(fnum)) {
                appendDirty.writeDirtyField(enc, fnum);
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

    public static byte[] spliceFields(
            byte[] packet, BitSet dirtyBits, int maxField, DirtyFieldWriter appendDirty) {
        return spliceFields(
            packet,
            0,
            packet == null ? 0 : packet.length,
            dirtyBits,
            maxField,
            appendDirty,
            null
        );
    }

    /**
     * Deeply validates the structural wire framing of {@code packet[offset..offset+length]}
     * (including recursive {@code WireSubMsg}, {@code WireArray}, {@code WireMap}, {@code Field #0},
     * boolean padding bits, trailing container bytes, and {@code MaxNestingDepth}) before splicing
     * modified fields. Throws {@link OmniBufException} if the input packet is structurally corrupt.
     */
    public static byte[] spliceFieldsValidated(
            byte[] packet,
            int offset,
            int length,
            long dirtyMask,
            int maxField,
            DirtyFieldWriter appendDirty,
            OmniBufEncoder target) {
        checkSliceBounds(packet, offset, length);
        OmniBufDecoder.validateStructuralMessage(packet, offset, length, 0);
        return spliceFields(packet, offset, length, dirtyMask, maxField, appendDirty, target);
    }

    public static byte[] spliceFieldsValidated(
            byte[] packet,
            int offset,
            int length,
            BitSet dirtyBits,
            int maxField,
            DirtyFieldWriter appendDirty,
            OmniBufEncoder target) {
        checkSliceBounds(packet, offset, length);
        OmniBufDecoder.validateStructuralMessage(packet, offset, length, 0);
        return spliceFields(packet, offset, length, dirtyBits, maxField, appendDirty, target);
    }

    public static byte[] spliceFieldsValidated(
            byte[] packet, long dirtyMask, int maxField, DirtyFieldWriter appendDirty) {
        return spliceFieldsValidated(
            packet,
            0,
            packet == null ? 0 : packet.length,
            dirtyMask,
            maxField,
            appendDirty,
            null
        );
    }

    public static byte[] spliceFieldsValidated(
            byte[] packet, BitSet dirtyBits, int maxField, DirtyFieldWriter appendDirty) {
        return spliceFieldsValidated(
            packet,
            0,
            packet == null ? 0 : packet.length,
            dirtyBits,
            maxField,
            appendDirty,
            null
        );
    }
}
