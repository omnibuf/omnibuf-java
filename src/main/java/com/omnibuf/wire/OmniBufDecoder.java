package com.omnibuf.wire;

import com.omnibuf.OmniBufException;
import com.omnibuf.SchemaMeta;
import java.util.Arrays;

/**
 * Binary decoder, container header parser, and strict wire validator for OmniBuf packets.
 */
public final class OmniBufDecoder {

    public static final int MAX_CONTAINER_DEPTH = WireType.MAX_CONTAINER_DEPTH;

    public record Slice(int offset, int length) {
        public int end() {
            return offset + length;
        }
    }

    public record ArrayHeader(int elemWireType, int count, int payloadStart, int payloadEnd) {}

    public record MapHeader(int keyWireType, int valWireType, int count, int payloadStart, int payloadEnd) {}

    private final byte[] buf;
    private int pos;
    private final int limit;

    public OmniBufDecoder(byte[] buf) {
        this(buf, 0, buf == null ? 0 : buf.length);
    }

    public OmniBufDecoder(byte[] buf, int offset, int length) {
        if (buf == null) {
            throw new OmniBufException("omnibuf: null packet buffer");
        }
        if (offset < 0 || length < 0 || offset + length > buf.length) {
            throw new OmniBufException("omnibuf: packet slice out of bounds");
        }
        this.buf = buf;
        this.pos = offset;
        this.limit = offset + length;
    }

    public byte[] buffer() {
        return buf;
    }

    public int position() {
        return pos;
    }

    public void position(int newPos) {
        if (newPos < 0 || newPos > limit) {
            throw new OmniBufException("omnibuf: cursor position out of bounds: " + newPos);
        }
        this.pos = newPos;
    }

    public int limit() {
        return limit;
    }

    public boolean hasRemaining() {
        return pos < limit;
    }

    public long readVarint() {
        if (pos >= limit) {
            throw new OmniBufException("omnibuf: unexpected EOF reading prefix-varint");
        }
        int b0 = buf[pos] & 0xFF;
        return switch (Integer.numberOfTrailingZeros(b0)) {
            case 0 -> {
                pos += 1;
                yield b0 >>> 1;
            }
            case 1 -> {
                if (pos + 2 > limit) {
                    throw new OmniBufException("omnibuf: truncated 2-byte prefix-varint");
                }
                long v = Short.toUnsignedLong(PrefixVarint.getShortLE(buf, pos)) >>> 2;
                pos += 2;
                yield v;
            }
            case 2 -> {
                if (pos + 4 > limit) {
                    throw new OmniBufException("omnibuf: truncated 4-byte prefix-varint");
                }
                long v = Integer.toUnsignedLong(PrefixVarint.getIntLE(buf, pos)) >>> 3;
                pos += 4;
                yield v;
            }
            case 32 -> {
                if (pos + 9 > limit) {
                    throw new OmniBufException("omnibuf: truncated 9-byte prefix-varint");
                }
                long v = PrefixVarint.getLongLE(buf, pos + 1);
                pos += 9;
                yield v;
            }
            default -> throw new OmniBufException(
                String.format("omnibuf: reserved prefix-varint marker 0x%02x", b0)
            );
        };
    }

    public long readTag() {
        long tag = readVarint();
        long fnum = tag >>> 4;
        if (Long.compareUnsigned(fnum, WireType.MAX_FIELD_NUMBER) > 0) {
            throw new OmniBufException("omnibuf: field number exceeds maximum: " + fnum);
        }
        if (fnum == 0L && ((int) (tag & 0x0FL)) != WireType.WIRE_SUB_MSG) {
            throw new OmniBufException("omnibuf: Field #0 requires WireSubMsg");
        }
        return tag;
    }

    public int readUint32() {
        return (int) readVarint();
    }

    public long readUint64() {
        return readVarint();
    }

    public int readSint32() {
        return PrefixVarint.decodeZigZag32(readVarint());
    }

    public long readSint64() {
        return PrefixVarint.decodeZigZag64(readVarint());
    }

    public int readFixed32() {
        if (pos + 4 > limit) {
            throw new OmniBufException("omnibuf: truncated fixed32");
        }
        int v = PrefixVarint.getIntLE(buf, pos);
        pos += 4;
        return v;
    }

    public long readFixed64() {
        if (pos + 8 > limit) {
            throw new OmniBufException("omnibuf: truncated fixed64");
        }
        long v = PrefixVarint.getLongLE(buf, pos);
        pos += 8;
        return v;
    }

    public float readFloat32() {
        return Float.intBitsToFloat(readFixed32());
    }

    public double readFloat64() {
        return Double.longBitsToDouble(readFixed64());
    }

    public boolean readContainerBool() {
        if (pos >= limit) {
            throw new OmniBufException("omnibuf: truncated bool in container");
        }
        int b = buf[pos++] & 0xFF;
        if (b > 1) {
            throw new OmniBufException("omnibuf: invalid boolean padding bits: " + b);
        }
        return b == 1;
    }

    public Slice readLengthPrefixedSlice(int minLen) {
        long byteLen = readVarint();
        if (Long.compareUnsigned(byteLen, minLen) < 0
                || Long.compareUnsigned(byteLen, limit - pos) > 0) {
            throw new OmniBufException("omnibuf: invalid length prefix " + byteLen + " at " + pos);
        }
        int len = (int) byteLen;
        int start = pos;
        pos += len;
        return new Slice(start, len);
    }

    public String readString() {
        Slice s = readLengthPrefixedSlice(0);
        return Utf8Validator.decodeStrict(buf, s.offset(), s.length());
    }

    public byte[] readBytes() {
        Slice s = readLengthPrefixedSlice(0);
        if (s.length() == 0) {
            return EMPTY_BYTES;
        }
        return Arrays.copyOfRange(buf, s.offset(), s.end());
    }

    public void skipOrValidateUnknown(int fieldNumber, int wireType, int depth) {
        if (fieldNumber == 0) {
            if (wireType != WireType.WIRE_SUB_MSG) {
                throw new OmniBufException("omnibuf: Field #0 requires WireSubMsg");
            }
            Slice s = readLengthPrefixedSlice(0);
            SchemaMeta.decode(buf, s.offset(), s.length(), depth + 1);
            return;
        }
        pos = validateValue(buf, pos, limit, wireType, depth);
    }

    // ========================================================================
    // Static Header Parsers & Structural Validators
    // ========================================================================

    public static Slice readLengthSlice(byte[] buf, int pos, int limit, int minLen) {
        PrefixVarint.Decoded d = PrefixVarint.decode(buf, pos, limit);
        int next = pos + d.bytesRead();
        if (Long.compareUnsigned(d.value(), minLen) < 0
                || Long.compareUnsigned(d.value(), limit - next) > 0) {
            throw new OmniBufException("omnibuf: length-prefixed payload out of bounds at " + pos);
        }
        return new Slice(next, (int) d.value());
    }

    public static ArrayHeader parseArrayHeader(byte[] buf, int bodyStart, int bodyEnd) {
        if (bodyEnd - bodyStart < 2) {
            throw new OmniBufException("omnibuf: WireArray body shorter than 2 bytes");
        }
        int elemType = buf[bodyStart] & 0xFF;
        if (!WireType.isValidArrayElemWireType(elemType)) {
            throw new OmniBufException("omnibuf: invalid WireArray element type: " + elemType);
        }
        PrefixVarint.Decoded countVar = PrefixVarint.decode(buf, bodyStart + 1, bodyEnd);
        int payloadStart = bodyStart + 1 + countVar.bytesRead();
        int remaining = bodyEnd - payloadStart;
        if (Long.compareUnsigned(countVar.value(), remaining) > 0) {
            throw new OmniBufException("omnibuf: WireArray count exceeds remaining payload bytes");
        }
        int count = (int) countVar.value();
        int fixedWidth = WireType.fixedElementWidth(elemType);
        if (fixedWidth > 0) {
            if (remaining % fixedWidth != 0 || count != remaining / fixedWidth) {
                throw new OmniBufException("omnibuf: WireArray fixed-width count/byte-length mismatch");
            }
        } else if (count == 0 && remaining != 0) {
            throw new OmniBufException("omnibuf: empty WireArray has trailing payload bytes");
        }
        return new ArrayHeader(elemType, count, payloadStart, bodyEnd);
    }

    public static MapHeader parseMapHeader(byte[] buf, int bodyStart, int bodyEnd) {
        if (bodyEnd - bodyStart < 3) {
            throw new OmniBufException("omnibuf: WireMap body shorter than 3 bytes");
        }
        int keyWT = buf[bodyStart] & 0xFF;
        int valWT = buf[bodyStart + 1] & 0xFF;
        if (!WireType.isValidMapKeyWireType(keyWT) || !WireType.isValidMapValWireType(valWT)) {
            throw new OmniBufException(
                "omnibuf: invalid WireMap key/value wire types: " + keyWT + "/" + valWT
            );
        }
        PrefixVarint.Decoded countVar = PrefixVarint.decode(buf, bodyStart + 2, bodyEnd);
        int payloadStart = bodyStart + 2 + countVar.bytesRead();
        int remaining = bodyEnd - payloadStart;
        if (countVar.value() == 0L) {
            if (remaining != 0) {
                throw new OmniBufException("omnibuf: empty WireMap has trailing payload bytes");
            }
        } else if (Long.compareUnsigned(countVar.value(), remaining / 2) > 0) {
            throw new OmniBufException("omnibuf: WireMap count exceeds remaining key/value capacity");
        }
        return new MapHeader(keyWT, valWT, (int) countVar.value(), payloadStart, bodyEnd);
    }

    /**
     * Fast O(1) value skipper returning the next offset, or {@code -1} on structural/bounds error.
     */
    public static int tryFastSkipValue(byte[] buf, int pos, int limit, int wireType) {
        if (pos < 0 || pos > limit) {
            return -1;
        }
        return switch (wireType) {
            case WireType.WIRE_UINT, WireType.WIRE_SINT -> {
                if (pos >= limit) {
                    yield -1;
                }
                int b0 = buf[pos] & 0xFF;
                int w = switch (Integer.numberOfTrailingZeros(b0)) {
                    case 0 -> 1;
                    case 1 -> 2;
                    case 2 -> 4;
                    case 32 -> 9;
                    default -> -1;
                };
                if (w < 0 || pos + w > limit) {
                    yield -1;
                }
                yield pos + w;
            }
            case WireType.WIRE_BOOL_FALSE, WireType.WIRE_BOOL_TRUE -> pos;
            case WireType.WIRE_FIXED32, WireType.WIRE_SFIXED32, WireType.WIRE_FLOAT32 ->
                pos + 4 <= limit ? pos + 4 : -1;
            case WireType.WIRE_FIXED64, WireType.WIRE_SFIXED64, WireType.WIRE_FLOAT64 ->
                pos + 8 <= limit ? pos + 8 : -1;
            case WireType.WIRE_STRING,
                 WireType.WIRE_BYTES,
                 WireType.WIRE_SUB_MSG,
                 WireType.WIRE_ARRAY,
                 WireType.WIRE_MAP,
                 WireType.WIRE_EXTENDED -> {
                int minLen = switch (wireType) {
                    case WireType.WIRE_MAP -> 3;
                    case WireType.WIRE_ARRAY -> 2;
                    case WireType.WIRE_EXTENDED -> 1;
                    default -> 0;
                };
                long[] lenOut = new long[1];
                int ln = PrefixVarint.tryDecode(buf, pos, limit, lenOut);
                if (ln == 0) {
                    yield -1;
                }
                int next = pos + ln;
                long bLen = lenOut[0];
                if (Long.compareUnsigned(bLen, minLen) < 0
                        || Long.compareUnsigned(bLen, limit - next) > 0) {
                    yield -1;
                }
                yield next + (int) bLen;
            }
            default -> -1;
        };
    }

    /**
     * Deeply validates a field value at {@code buf[pos..limit]} and returns the offset
     * immediately after the value. Enforces {@link #MAX_CONTAINER_DEPTH}, strict UTF-8
     * on {@code WireString}, boolean padding bits, container headers, and trailing-byte checks.
     */
    public static int validateValue(byte[] buf, int pos, int limit, int wireType, int depth) {
        if (depth > MAX_CONTAINER_DEPTH) {
            throw new OmniBufException(
                "omnibuf: nesting depth " + depth + " exceeds MaxNestingDepth (" + MAX_CONTAINER_DEPTH + ")"
            );
        }
        if (pos < 0 || pos > limit) {
            throw new OmniBufException("omnibuf: offset out of bounds: " + pos);
        }
        return switch (wireType) {
            case WireType.WIRE_UINT, WireType.WIRE_SINT -> {
                PrefixVarint.Decoded d = PrefixVarint.decode(buf, pos, limit);
                yield pos + d.bytesRead();
            }
            case WireType.WIRE_BOOL_FALSE, WireType.WIRE_BOOL_TRUE -> pos;
            case WireType.WIRE_FIXED32, WireType.WIRE_SFIXED32, WireType.WIRE_FLOAT32 -> {
                if (pos + 4 > limit) {
                    throw new OmniBufException("omnibuf: truncated 32-bit scalar at " + pos);
                }
                yield pos + 4;
            }
            case WireType.WIRE_FIXED64, WireType.WIRE_SFIXED64, WireType.WIRE_FLOAT64 -> {
                if (pos + 8 > limit) {
                    throw new OmniBufException("omnibuf: truncated 64-bit scalar at " + pos);
                }
                yield pos + 8;
            }
            case WireType.WIRE_STRING -> {
                Slice s = readLengthSlice(buf, pos, limit, 0);
                if (!Utf8Validator.isValidUtf8(buf, s.offset(), s.length())) {
                    throw new OmniBufException("omnibuf: invalid UTF-8 in WireString at " + pos);
                }
                yield s.end();
            }
            case WireType.WIRE_BYTES -> readLengthSlice(buf, pos, limit, 0).end();
            case WireType.WIRE_SUB_MSG -> {
                Slice s = readLengthSlice(buf, pos, limit, 0);
                validateMessage(buf, s.offset(), s.length(), depth + 1);
                yield s.end();
            }
            case WireType.WIRE_ARRAY -> {
                if (depth + 1 > MAX_CONTAINER_DEPTH) {
                    throw new OmniBufException("omnibuf: WireArray exceeds MaxNestingDepth");
                }
                Slice s = readLengthSlice(buf, pos, limit, 2);
                ArrayHeader hdr = parseArrayHeader(buf, s.offset(), s.end());
                validateArrayPayload(buf, hdr, depth + 1);
                yield s.end();
            }
            case WireType.WIRE_MAP -> {
                if (depth + 1 > MAX_CONTAINER_DEPTH) {
                    throw new OmniBufException("omnibuf: WireMap exceeds MaxNestingDepth");
                }
                Slice s = readLengthSlice(buf, pos, limit, 3);
                MapHeader hdr = parseMapHeader(buf, s.offset(), s.end());
                int p = hdr.payloadStart();
                for (int i = 0; i < hdr.count(); i++) {
                    p = validateContainerEntry(buf, p, hdr.payloadEnd(), hdr.keyWireType(), depth + 1);
                    p = validateContainerEntry(buf, p, hdr.payloadEnd(), hdr.valWireType(), depth + 1);
                }
                if (p != hdr.payloadEnd()) {
                    throw new OmniBufException("omnibuf: WireMap has trailing payload bytes");
                }
                yield s.end();
            }
            case WireType.WIRE_EXTENDED -> {
                // Requires ByteLen >= 1 so the 1-byte extension subtype header is present.
                Slice s = readLengthSlice(buf, pos, limit, 1);
                yield s.end();
            }
            default -> throw new OmniBufException("omnibuf: invalid wire type: " + wireType);
        };
    }

    private static void validateArrayPayload(byte[] buf, ArrayHeader hdr, int arrayDepth) {
        int elemWT = hdr.elemWireType();
        int p = hdr.payloadStart();
        int end = hdr.payloadEnd();
        int count = hdr.count();
        if (elemWT == WireType.WIRE_BOOL_FALSE) {
            for (int i = 0; i < count; i++) {
                int b = buf[p + i] & 0xFF;
                if (b > 1) {
                    throw new OmniBufException("omnibuf: invalid boolean byte in WireArray: " + b);
                }
            }
            return;
        }
        if (WireType.fixedElementWidth(elemWT) > 0) {
            return;
        }
        for (int i = 0; i < count; i++) {
            p = validateContainerEntry(buf, p, end, elemWT, arrayDepth);
        }
        if (p != end) {
            throw new OmniBufException("omnibuf: WireArray has trailing payload bytes");
        }
    }

    private static int validateContainerEntry(byte[] buf, int pos, int limit, int wt, int depth) {
        if (wt == WireType.WIRE_BOOL_FALSE) {
            if (pos >= limit) {
                throw new OmniBufException("omnibuf: truncated boolean in container");
            }
            int b = buf[pos] & 0xFF;
            if (b > 1) {
                throw new OmniBufException("omnibuf: invalid boolean byte in container: " + b);
            }
            return pos + 1;
        }
        return validateValue(buf, pos, limit, wt, depth);
    }

    public static void validateMessage(byte[] buf, int offset, int length, int depth) {
        if (depth > MAX_CONTAINER_DEPTH) {
            throw new OmniBufException(
                "omnibuf: nesting depth " + depth + " exceeds MaxNestingDepth (" + MAX_CONTAINER_DEPTH + ")"
            );
        }
        int pos = offset;
        int end = offset + length;
        while (pos < end) {
            PrefixVarint.Decoded tagDec = PrefixVarint.decode(buf, pos, end);
            long tag = tagDec.value();
            long fnum = tag >>> 4;
            if (Long.compareUnsigned(fnum, WireType.MAX_FIELD_NUMBER) > 0) {
                throw new OmniBufException("omnibuf: field number out of range: " + fnum);
            }
            pos += tagDec.bytesRead();
            int wt = (int) (tag & 0x0FL);
            if (fnum == 0L) {
                if (wt != WireType.WIRE_SUB_MSG) {
                    throw new OmniBufException("omnibuf: Field #0 requires WireSubMsg");
                }
                Slice s = readLengthSlice(buf, pos, end, 0);
                SchemaMeta.decode(buf, s.offset(), s.length(), depth + 1);
                pos = s.end();
            } else {
                pos = validateValue(buf, pos, end, wt, depth);
            }
        }
    }

    private static final byte[] EMPTY_BYTES = new byte[0];
}
