package com.omnibuf.wire;

import com.omnibuf.OmniBufException;
import com.omnibuf.SchemaMeta;
import com.omnibuf.UnmarshalOptions;
import java.io.ByteArrayOutputStream;
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
    }

    private static final byte[] EMPTY_BYTES = new byte[0];
    private static final int MAX_PACKED_OFFSET = 0x07FF_FFFF;

    private OmniBufOpaque() {}

    private static void checkSliceBounds(byte[] packet, int offset, int length) {
        if (packet == null || offset < 0 || length < 0 || length > packet.length - offset) {
            throw new OmniBufException("omnibuf: packet slice out of bounds");
        }
    }

    /**
     * Zero-allocation field locator for packets up to 128 MB ({@code offset + length <= 0x07FF_FFFF}).
     * Packs {@code (((long) valStart) << 32) | (((long) valEnd) << 4) | (wt & 0x0FL)}, or returns
     * {@code -1L} if absent or malformed.
     */
    private static long locateFieldPacked(
            byte[] packet, int offset, int length, int targetField, int wireMask) {
        if (packet == null || offset < 0 || length < 0 || length > packet.length - offset) {
            return -1L;
        }
        int end = offset + length;
        if (end > MAX_PACKED_OFFSET) {
            return locateFieldPackedLarge(packet, offset, length, targetField, wireMask);
        }
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
                if (pos + 2 > end) {
                    return -1L;
                }
                int tag = Short.toUnsignedInt(PrefixVarint.getShortLE(packet, pos)) >>> 2;
                fnum = tag >>> 4;
                wt = tag & 0x0F;
                pos += 2;
            } else {
                long tagPacked = decodeWideTag(packet, pos, end);
                if (tagPacked < 0L) {
                    return -1L;
                }
                pos = (int) (tagPacked >>> 36);
                fnum = (int) ((tagPacked >>> 4) & 0xFFFF_FFFFL);
                wt = (int) (tagPacked & 0x0FL);
            }
            if (fnum == 0) {
                int nextPos = trySkipField0(packet, pos, end, wt);
                if (nextPos < 0) {
                    return -1L;
                }
                pos = nextPos;
                continue;
            }

            int valStart = pos;
            int nextPos = skipValidatedHeader(packet, pos, end, wt);
            if (nextPos < 0) {
                return -1L;
            }
            if (fnum == targetField && ((wireMask >>> wt) & 1) != 0) {
                found = (((long) valStart) << 32) | (((long) nextPos) << 4) | (wt & 0x0FL);
            }
            pos = nextPos;
        }
        return found;
    }

    private static long locateFieldPackedLarge(
            byte[] packet, int offset, int length, int targetField, int wireMask) {
        LocatedField loc = locateFieldGeneral(packet, offset, length, targetField, wireMask);
        if (!loc.found() || loc.valueEnd() > MAX_PACKED_OFFSET) {
            return -1L;
        }
        return (((long) loc.valueOffset()) << 32)
            | (((long) loc.valueEnd()) << 4)
            | (loc.wireType() & 0x0FL);
    }

    /**
     * Decodes a 4-byte or 9-byte Prefix-Varint tag at {@code packet[pos..end]} without heap
     * allocation, returning {@code (((long) nextPos) << 36) | (fnum << 4) | wt}, or {@code -1L}
     * on truncation, reserved marker, or {@code fnum > MAX_FIELD_NUMBER}.
     */
    private static long decodeWideTag(byte[] packet, int pos, int end) {
        int b0 = packet[pos] & 0xFF;
        long tag;
        int nextPos;
        if ((b0 & 4) != 0) {
            nextPos = pos + 4;
            if (nextPos > end) {
                return -1L;
            }
            tag = Integer.toUnsignedLong(PrefixVarint.getIntLE(packet, pos)) >>> 3;
        } else if (b0 == 0) {
            nextPos = pos + 9;
            if (nextPos > end) {
                return -1L;
            }
            tag = PrefixVarint.getLongLE(packet, pos + 1);
        } else {
            return -1L;
        }
        long fnum = tag >>> 4;
        if (Long.compareUnsigned(fnum, WireType.MAX_FIELD_NUMBER) > 0) {
            return -1L;
        }
        return (((long) nextPos) << 36) | (tag & 0x0FFF_FFFF_FFFFL);
    }

    /**
     * Scans all top-level field headers in {@code packet[offset..offset+length]} in O(1) time per
     * header and returns the last occurrence matching {@code targetField} and {@code wireMask}.
     * Returns {@link LocatedField#NOT_FOUND} if absent or if the packet framing is malformed.
     */
    public static LocatedField locateField(
            byte[] packet, int offset, int length, int targetField, int wireMask) {
        if (packet != null && offset >= 0 && length >= 0 && length <= packet.length - offset) {
            int end = offset + length;
            if (end <= MAX_PACKED_OFFSET) {
                long packed = locateFieldPacked(packet, offset, length, targetField, wireMask);
                if (packed < 0L) {
                    return LocatedField.NOT_FOUND;
                }
                int valStart = (int) (packed >>> 32);
                int valEnd = ((int) packed) >>> 4;
                int wt = ((int) packed) & 0x0F;
                return new LocatedField(valStart, valEnd, wt, true);
            }
        }
        return locateFieldGeneral(packet, offset, length, targetField, wireMask);
    }

    private static LocatedField locateFieldGeneral(
            byte[] packet, int offset, int length, int targetField, int wireMask) {
        if (packet == null || offset < 0 || length < 0 || length > packet.length - offset) {
            return LocatedField.NOT_FOUND;
        }
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
                if (pos + 2 > end) {
                    return LocatedField.NOT_FOUND;
                }
                int u16 = Short.toUnsignedInt(PrefixVarint.getShortLE(packet, pos)) >>> 2;
                fnum = u16 >>> 4;
                wt = u16 & 0x0F;
                pos += 2;
            } else {
                long[] scratch = new long[1];
                int tn = PrefixVarint.tryDecode(packet, pos, end, scratch);
                if (tn == 0 || Long.compareUnsigned(scratch[0] >>> 4, WireType.MAX_FIELD_NUMBER) > 0) {
                    return LocatedField.NOT_FOUND;
                }
                fnum = (int) (scratch[0] >>> 4);
                wt = (int) (scratch[0] & 0x0FL);
                pos += tn;
            }
            if (fnum == 0) {
                int nextPos = trySkipField0(packet, pos, end, wt);
                if (nextPos < 0) {
                    return LocatedField.NOT_FOUND;
                }
                pos = nextPos;
                continue;
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
        return switch (wt) {
            case WireType.WIRE_UINT, WireType.WIRE_SINT -> PrefixVarint.skipVarint(packet, pos, end);
            case WireType.WIRE_BOOL_FALSE, WireType.WIRE_BOOL_TRUE -> pos <= end ? pos : -1;
            case WireType.WIRE_FIXED32, WireType.WIRE_SFIXED32, WireType.WIRE_FLOAT32 ->
                pos + 4 <= end ? pos + 4 : -1;
            case WireType.WIRE_FIXED64, WireType.WIRE_SFIXED64, WireType.WIRE_FLOAT64 ->
                pos + 8 <= end ? pos + 8 : -1;
            case WireType.WIRE_STRING, WireType.WIRE_BYTES, WireType.WIRE_SUB_MSG -> {
                if (pos >= end) {
                    yield -1;
                }
                int b0 = packet[pos] & 0xFF;
                if ((b0 & 1) != 0) {
                    int next = pos + 1 + (b0 >>> 1);
                    yield next <= end ? next : -1;
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

    private static int trySkipField0(byte[] packet, int pos, int end, int wt) {
        if (wt != WireType.WIRE_SUB_MSG) {
            return -1;
        }
        long packed = PrefixVarint.tryReadLengthSlicePacked(packet, pos, end, 0);
        if (packed < 0L) {
            return -1;
        }
        int start = (int) (packed >>> 32);
        int subEnd = (int) packed;
        try {
            SchemaMeta.decode(
                packet,
                start,
                subEnd - start,
                1,
                OmniBufDecoder.MAX_CONTAINER_DEPTH,
                UnmarshalOptions.DEFAULT_MAX_COLLECTION_ELEMENTS
            );
            return subEnd;
        } catch (OmniBufException e) {
            return -1;
        }
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
                if (pos + 2 > end) {
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
                pos = (int) (tagPacked >>> 36);
                fnum = (int) ((tagPacked >>> 4) & 0xFFFF_FFFFL);
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
        int valEnd = ((int) packed) >>> 4;
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
        int valEnd = ((int) packed) >>> 4;
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
        long packed = locateFieldPacked(
            packet,
            offset,
            length,
            fieldNum,
            (1 << WireType.WIRE_FIXED32) | (1 << WireType.WIRE_SFIXED32)
        );
        if (packed < 0L) {
            return 0;
        }
        int valStart = (int) (packed >>> 32);
        int valEnd = ((int) packed) >>> 4;
        return valEnd - valStart >= 4 ? PrefixVarint.getIntLE(packet, valStart) : 0;
    }

    public static long readFixed64(byte[] packet, int offset, int length, int fieldNum) {
        long packed = locateFieldPacked(
            packet,
            offset,
            length,
            fieldNum,
            (1 << WireType.WIRE_FIXED64) | (1 << WireType.WIRE_SFIXED64)
        );
        if (packed < 0L) {
            return 0L;
        }
        int valStart = (int) (packed >>> 32);
        int valEnd = ((int) packed) >>> 4;
        return valEnd - valStart >= 8 ? PrefixVarint.getLongLE(packet, valStart) : 0L;
    }

    public static float readFloat32(byte[] packet, int offset, int length, int fieldNum) {
        long packed = locateFieldPacked(packet, offset, length, fieldNum, 1 << WireType.WIRE_FLOAT32);
        if (packed < 0L) {
            return 0.0f;
        }
        int valStart = (int) (packed >>> 32);
        int valEnd = ((int) packed) >>> 4;
        return valEnd - valStart >= 4 ? Float.intBitsToFloat(PrefixVarint.getIntLE(packet, valStart)) : 0.0f;
    }

    public static double readFloat64(byte[] packet, int offset, int length, int fieldNum) {
        long packed = locateFieldPacked(packet, offset, length, fieldNum, 1 << WireType.WIRE_FLOAT64);
        if (packed < 0L) {
            return 0.0d;
        }
        int valStart = (int) (packed >>> 32);
        int valEnd = ((int) packed) >>> 4;
        return valEnd - valStart >= 8 ? Double.longBitsToDouble(PrefixVarint.getLongLE(packet, valStart)) : 0.0d;
    }

    public static String readString(byte[] packet, int offset, int length, int fieldNum) {
        long packed = locateFieldPacked(packet, offset, length, fieldNum, 1 << WireType.WIRE_STRING);
        if (packed < 0L) {
            return "";
        }
        int p = (int) (packed >>> 32);
        int valEnd = ((int) packed) >>> 4;
        int b0 = packet[p] & 0xFF;
        if ((b0 & 1) != 0) {
            int strLen = b0 >>> 1;
            return Utf8Validator.decodeStrict(packet, p + 1, strLen);
        }
        long slicePacked = PrefixVarint.readLengthSlicePacked(packet, p, valEnd, 0);
        int start = (int) (slicePacked >>> 32);
        int end = (int) slicePacked;
        return Utf8Validator.decodeStrict(packet, start, end - start);
    }

    public static byte[] readBytes(byte[] packet, int offset, int length, int fieldNum) {
        long packed = locateFieldPacked(packet, offset, length, fieldNum, 1 << WireType.WIRE_BYTES);
        if (packed < 0L) {
            return EMPTY_BYTES;
        }
        int p = (int) (packed >>> 32);
        int valEnd = ((int) packed) >>> 4;
        long slicePacked = PrefixVarint.readLengthSlicePacked(packet, p, valEnd, 0);
        int start = (int) (slicePacked >>> 32);
        int end = (int) slicePacked;
        return start == end ? EMPTY_BYTES : Arrays.copyOfRange(packet, start, end);
    }

    /**
     * Returns {@code true} iff {@code packet[offset..offset+length]} contains at least one
     * top-level occurrence of {@code fieldNum} matching {@code wireMask}.
     */
    public static boolean hasField(byte[] packet, int offset, int length, int fieldNum, int wireMask) {
        if (packet != null && offset >= 0 && length >= 0 && length <= packet.length - offset) {
            int end = offset + length;
            if (end <= MAX_PACKED_OFFSET) {
                return locateFieldPacked(packet, offset, length, fieldNum, wireMask) >= 0L;
            }
        }
        return locateFieldGeneral(packet, offset, length, fieldNum, wireMask).found();
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
                if (pos + 2 > end) {
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
                pos = (int) (tagPacked >>> 36);
                fnum = (int) ((tagPacked >>> 4) & 0xFFFF_FFFFL);
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
                if (pos + 2 > end) {
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
                pos = (int) (tagPacked >>> 36);
                fnum = (int) ((tagPacked >>> 4) & 0xFFFF_FFFFL);
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
        ByteArrayOutputStream merged = new ByteArrayOutputStream(firstLen + secondLen + 32);
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
                if (pos + 2 > end) {
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
                pos = (int) (tagPacked >>> 36);
                fnum = (int) ((tagPacked >>> 4) & 0xFFFF_FFFFL);
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
                if (pos + 2 > end) {
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
                pos = (int) (tagPacked >>> 36);
                fnum = (int) ((tagPacked >>> 4) & 0xFFFF_FFFFL);
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
                OmniBufDecoder.validateArrayBody(packet, hdr, 1);
                continue;
            }
            int p = hdr.payloadStart();
            int end = hdr.payloadEnd();
            for (int i = 0; i < hdr.count(); i++) {
                long packed = PrefixVarint.readLengthSlicePacked(packet, p, end, 0);
                int sStart = (int) (packed >>> 32);
                int sEnd = (int) packed;
                out.add(Utf8Validator.decodeStrict(packet, sStart, sEnd - sStart));
                p = sEnd;
            }
            if (p != end) {
                throw new OmniBufException("omnibuf: WireArray has trailing payload bytes");
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
                OmniBufDecoder.validateArrayBody(packet, hdr, 1);
                continue;
            }
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
                OmniBufDecoder.validateArrayBody(packet, hdr, 1);
                continue;
            }
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
                OmniBufDecoder.validateArrayBody(packet, hdr, 1);
                continue;
            }
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
                OmniBufDecoder.validateArrayBody(packet, hdr, 1);
                continue;
            }
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
                OmniBufDecoder.validateArrayBody(packet, hdr, 1);
                continue;
            }
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
                OmniBufDecoder.validateArrayBody(packet, hdr, 1);
                continue;
            }
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
                OmniBufDecoder.validateArrayBody(packet, hdr, 1);
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
                OmniBufDecoder.validateArrayBody(packet, hdr, 1);
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
                OmniBufDecoder.validateArrayBody(packet, hdr, 1);
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
                OmniBufDecoder.validateArrayBody(packet, hdr, 1);
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
                OmniBufDecoder.validateArrayBody(packet, hdr, 1);
                continue;
            }
            int p = hdr.payloadStart();
            int end = hdr.payloadEnd();
            for (int i = 0; i < hdr.count(); i++) {
                long packed = PrefixVarint.readLengthSlicePacked(packet, p, end, 0);
                int sStart = (int) (packed >>> 32);
                int sEnd = (int) packed;
                out.add(new PacketView(packet, sStart, sEnd - sStart));
                p = sEnd;
            }
            if (p != end) {
                throw new OmniBufException("omnibuf: WireArray has trailing payload bytes");
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
                OmniBufDecoder.validateMapBody(packet, hdr, 1);
                continue;
            }
            int p = hdr.payloadStart();
            int end = hdr.payloadEnd();
            for (int i = 0; i < hdr.count(); i++) {
                long packed = PrefixVarint.readLengthSlicePacked(packet, p, end, 0);
                int kStart = (int) (packed >>> 32);
                int kEnd = (int) packed;
                String k = Utf8Validator.decodeStrict(packet, kStart, kEnd - kStart);
                p = kEnd;
                PrefixVarint.Decoded vd = PrefixVarint.decode(packet, p, end);
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
     * {@code expectedKeyWT -> expectedValWT}, validating trailing bytes and mismatched containers.
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
                    OmniBufDecoder.parseMapHeader(packet, body.offset(), body.end());
                if (hdr.keyWireType() != expectedKeyWT || hdr.valWireType() != expectedValWT) {
                    OmniBufDecoder.validateMapBody(packet, hdr, 1);
                    continue;
                }
                int saved = dec.setSlice(hdr.payloadStart(), hdr.payloadEnd());
                for (int i = 0; i < hdr.count(); i++) {
                    K k = keyReader.read(dec);
                    V v = valReader.read(dec);
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
                if (pos + 2 > end) {
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
                pos = (int) (tagPacked >>> 36);
                fnum = (int) ((tagPacked >>> 4) & 0xFFFF_FFFFL);
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
                if (pos + 2 > end) {
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
                pos = (int) (tagPacked >>> 36);
                fnum = (int) ((tagPacked >>> 4) & 0xFFFF_FFFFL);
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
            } else if (fnum > 0) {
                emitted.set(fnum);
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
