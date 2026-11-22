package com.omnibuf.wire;

import com.omnibuf.OmniBufException;
import com.omnibuf.SchemaMeta;
import com.omnibuf.UnmarshalOptions;
import java.util.Arrays;

/**
 * Binary decoder, single-cursor container limit reader, and strict wire validator for OmniBuf packets.
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

    final boolean isPooledSlot;
    boolean pooledInUse;

    private byte[] buf;
    private int pos;
    private int limit;
    private int maxMessageBytes;
    private int maxCollectionElements;
    private int maxNestingDepth;

    public OmniBufDecoder() {
        this(false);
    }

    OmniBufDecoder(boolean isPooledSlot) {
        this.isPooledSlot = isPooledSlot;
        this.buf = EMPTY_BYTES;
        this.pos = 0;
        this.limit = 0;
        this.maxMessageBytes = UnmarshalOptions.DEFAULT_MAX_MESSAGE_BYTES;
        this.maxCollectionElements = UnmarshalOptions.DEFAULT_MAX_COLLECTION_ELEMENTS;
        this.maxNestingDepth = UnmarshalOptions.DEFAULT_MAX_NESTING_DEPTH;
    }

    public OmniBufDecoder(byte[] buf) {
        this(buf, 0, buf == null ? 0 : buf.length, UnmarshalOptions.DEFAULT);
    }

    public OmniBufDecoder(byte[] buf, int offset, int length) {
        this(buf, offset, length, UnmarshalOptions.DEFAULT);
    }

    public OmniBufDecoder(byte[] buf, int offset, int length, UnmarshalOptions options) {
        this.isPooledSlot = false;
        reset(buf, offset, length, options);
    }

    public void reset(byte[] buf, int offset, int length) {
        reset(buf, offset, length, UnmarshalOptions.DEFAULT);
    }

    public void reset(byte[] buf, int offset, int length, UnmarshalOptions options) {
        if (buf == null) {
            throw new OmniBufException("omnibuf: null packet buffer");
        }
        if (offset < 0 || length < 0 || length > buf.length - offset) {
            throw new OmniBufException("omnibuf: packet slice out of bounds");
        }
        UnmarshalOptions opts = options == null ? UnmarshalOptions.DEFAULT : options;
        if (length > opts.maxMessageBytes()) {
            throw new OmniBufException(
                "omnibuf: packet length " + length + " exceeds maxMessageBytes (" + opts.maxMessageBytes() + ")"
            );
        }
        this.buf = buf;
        this.pos = offset;
        this.limit = offset + length;
        this.maxMessageBytes = opts.maxMessageBytes();
        this.maxCollectionElements = opts.maxCollectionElements();
        this.maxNestingDepth = opts.maxNestingDepth();
    }

    public void clearBuffer() {
        this.buf = EMPTY_BYTES;
        this.pos = 0;
        this.limit = 0;
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

    public int maxMessageBytes() {
        return maxMessageBytes;
    }

    public int maxCollectionElements() {
        return maxCollectionElements;
    }

    public int maxNestingDepth() {
        return maxNestingDepth;
    }

    public int setSlice(int newPos, int newLimit) {
        if (newPos < 0 || newLimit < newPos || newLimit > buf.length) {
            throw new OmniBufException("omnibuf: slice bounds out of range");
        }
        int saved = this.limit;
        this.pos = newPos;
        this.limit = newLimit;
        return saved;
    }

    public void restoreLimit(int savedLimit) {
        this.limit = savedLimit;
    }

    public static OmniBufDecoder forSubSlice(
            byte[] buf, int start, int end, int maxNestingDepth, int maxCollectionElements) {
        OmniBufDecoder dec = new OmniBufDecoder(false);
        dec.buf = buf;
        dec.pos = start;
        dec.limit = end;
        dec.maxNestingDepth = maxNestingDepth;
        dec.maxCollectionElements = maxCollectionElements;
        return dec;
    }

    public static void throwMaxNestingDepth() {
        throw new OmniBufException("omnibuf: nesting depth exceeds MaxNestingDepth");
    }

    public static void throwMaxCollectionElements() {
        throw new OmniBufException("omnibuf: accumulated WireArray count exceeds maxCollectionElements");
    }

    public static void throwTrailingContainerBytes() {
        throw new OmniBufException("omnibuf: container has trailing payload bytes");
    }

    public static void throwTruncatedFixed32() {
        throw new OmniBufException("omnibuf: truncated fixed32");
    }

    public static void throwTruncatedFixed64() {
        throw new OmniBufException("omnibuf: truncated fixed64");
    }

    public static void throwInvalidBool(int b) {
        throw new OmniBufException("omnibuf: invalid boolean padding bits: " + b);
    }

    public static void throwField0RequiresSubMsg() {
        throw new OmniBufException("omnibuf: Field #0 requires WireSubMsg");
    }

    public static long throwBadVarint(byte[] buf, int pos, int lim) {
        PrefixVarint.decode(buf, pos, lim);
        throw new OmniBufException("omnibuf: invalid prefix-varint at " + pos);
    }

    public static long readVarint9OrThrow(byte[] buf, int pos, int lim) {
        if (pos < lim && buf[pos] == 0 && lim - pos >= 9) {
            return PrefixVarint.getLongLE(buf, pos + 1);
        }
        return throwBadVarint(buf, pos, lim);
    }

    public static long readVarint4Or9(byte[] buf, int pos, int lim) {
        if (pos < lim) {
            int b0 = buf[pos] & 0xFF;
            if ((b0 & 7) == 4 && lim - pos >= 4) {
                return Integer.toUnsignedLong(PrefixVarint.getIntLE(buf, pos)) >>> 3;
            }
            if (b0 == 0 && lim - pos >= 9) {
                return PrefixVarint.getLongLE(buf, pos + 1);
            }
        }
        return throwBadVarint(buf, pos, lim);
    }

    public static long readStringSpan(byte[] buf, int pos, int lim) {
        if (pos < lim) {
            int b0 = buf[pos] & 0xFF;
            if ((b0 & 1) != 0) {
                int len = b0 >>> 1;
                int start = pos + 1;
                if (lim - start >= len) {
                    return (((long) start) << 32) | ((start + len) & 0xFFFF_FFFFL);
                }
            }
        }
        return PrefixVarint.readLengthSlicePacked(buf, pos, lim, 0);
    }

    public static long readLengthSpanMin2(byte[] buf, int pos, int lim) {
        if (pos < lim) {
            int b0 = buf[pos] & 0xFF;
            if ((b0 & 1) != 0) {
                int len = b0 >>> 1;
                int start = pos + 1;
                if (len >= 2 && lim - start >= len) {
                    return (((long) start) << 32) | ((start + len) & 0xFFFF_FFFFL);
                }
            }
        }
        return PrefixVarint.readLengthSlicePacked(buf, pos, lim, 2);
    }

    public static long readTagSlowSlice(byte[] buf, int pos, int lim) {
        PrefixVarint.Decoded d = PrefixVarint.decode(buf, pos, lim);
        long tag = d.value();
        long fnum = tag >>> 4;
        if (Long.compareUnsigned(fnum, WireType.MAX_FIELD_NUMBER) > 0) {
            throw new OmniBufException("omnibuf: field number exceeds maximum: " + fnum);
        }
        if (fnum == 0L && ((int) (tag & 0x0FL)) != WireType.WIRE_SUB_MSG) {
            throwField0RequiresSubMsg();
        }
        return tag;
    }

    public static long readArrayHeaderPacked(
            byte[] buf,
            int bodyStart,
            int bodyEnd,
            int expectedElemWT,
            int depth,
            int maxNestingDepth,
            int maxCollectionElements) {
        if (depth < maxNestingDepth && (buf[bodyStart] & 0xFF) == expectedElemWT) {
            int b0 = buf[bodyStart + 1] & 0xFF;
            if ((b0 & 1) != 0) {
                int count = b0 >>> 1;
                int payloadStart = bodyStart + 2;
                int remaining = bodyEnd - payloadStart;
                if (count > 0
                        && count <= remaining
                        && count <= maxCollectionElements
                        && WireType.fixedElementWidth(expectedElemWT) == 0) {
                    return (((long) payloadStart) << 32) | (count & 0xFFFF_FFFFL);
                }
            }
        }
        return readArrayHeaderPackedSlow(
            buf, bodyStart, bodyEnd, expectedElemWT, depth, maxNestingDepth, maxCollectionElements
        );
    }

    private static long readArrayHeaderPackedSlow(
            byte[] buf,
            int bodyStart,
            int bodyEnd,
            int expectedElemWT,
            int depth,
            int maxNestingDepth,
            int maxCollectionElements) {
        if (depth + 1 > maxNestingDepth) {
            throw new OmniBufException("omnibuf: WireArray exceeds MaxNestingDepth");
        }
        int elemType = buf[bodyStart] & 0xFF;
        if (!WireType.isValidArrayElemWireType(elemType)) {
            throw new OmniBufException("omnibuf: invalid WireArray element type: " + elemType);
        }
        int cPos = bodyStart + 1;
        long countLong;
        int payloadStart;
        int b0 = buf[cPos] & 0xFF;
        if ((b0 & 1) != 0) {
            countLong = b0 >>> 1;
            payloadStart = cPos + 1;
        } else {
            PrefixVarint.Decoded d = PrefixVarint.decode(buf, cPos, bodyEnd);
            countLong = d.value();
            payloadStart = cPos + d.bytesRead();
        }
        int remaining = bodyEnd - payloadStart;
        if (Long.compareUnsigned(countLong, remaining) > 0) {
            throw new OmniBufException("omnibuf: WireArray count exceeds remaining payload bytes");
        }
        if (Long.compareUnsigned(countLong, maxCollectionElements) > 0) {
            throw new OmniBufException(
                "omnibuf: WireArray count " + countLong
                    + " exceeds maxCollectionElements (" + maxCollectionElements + ")"
            );
        }
        int count = (int) countLong;
        int fixedWidth = WireType.fixedElementWidth(elemType);
        if (fixedWidth > 0) {
            if (remaining % fixedWidth != 0 || count != remaining / fixedWidth) {
                throw new OmniBufException("omnibuf: WireArray fixed-width count/byte-length mismatch");
            }
        } else if (count == 0 && remaining != 0) {
            throw new OmniBufException("omnibuf: empty WireArray has trailing payload bytes");
        }
        if (elemType != expectedElemWT) {
            throw new OmniBufException(
                "omnibuf: WireArray element type mismatch: expected "
                    + WireType.nameOf(expectedElemWT) + ", got " + WireType.nameOf(elemType)
            );
        }
        return (((long) payloadStart) << 32) | (count & 0xFFFF_FFFFL);
    }

    public static int skipOrValidateUnknownSlice(
            byte[] buf,
            int pos,
            int lim,
            int fieldNumber,
            int wireType,
            int depth,
            int maxNestingDepth,
            int maxCollectionElements) {
        if (fieldNumber == 0) {
            if (wireType != WireType.WIRE_SUB_MSG) {
                throwField0RequiresSubMsg();
            }
            long packed = PrefixVarint.readLengthSlicePacked(buf, pos, lim, 0);
            int start = (int) (packed >>> 32);
            int end = (int) packed;
            SchemaMeta.decode(buf, start, end - start, depth + 1, maxNestingDepth, maxCollectionElements);
            return end;
        }
        return validateValue(buf, pos, lim, wireType, depth, maxNestingDepth, maxCollectionElements);
    }

    public static int readUint32SliceInto(byte[] b, int p, int lim, IntArrayList dst, int count) {
        if (count > 0) {
            dst.ensureAdditional(count);
            int[] out = dst.rawArray();
            int outIdx = dst.size();
            for (int i = 0; i < count; i++) {
                if (p < lim) {
                    int b0 = b[p] & 0xFF;
                    if ((b0 & 1) != 0) {
                        out[outIdx + i] = b0 >>> 1;
                        p += 1;
                        continue;
                    }
                    if ((b0 & 2) != 0 && lim - p >= 2) {
                        out[outIdx + i] = Short.toUnsignedInt(PrefixVarint.getShortLE(b, p)) >>> 2;
                        p += 2;
                        continue;
                    }
                    if ((b0 & 4) != 0 && lim - p >= 4) {
                        out[outIdx + i] = PrefixVarint.getIntLE(b, p) >>> 3;
                        p += 4;
                        continue;
                    }
                    if (b0 == 0 && lim - p >= 9) {
                        out[outIdx + i] = (int) PrefixVarint.getLongLE(b, p + 1);
                        p += 9;
                        continue;
                    }
                }
                throwBadVarint(b, p, lim);
            }
            dst.setSizeUnchecked(outIdx + count);
        }
        return p;
    }

    public static int readSint32SliceInto(byte[] b, int p, int lim, IntArrayList dst, int count) {
        if (count > 0) {
            dst.ensureAdditional(count);
            int[] out = dst.rawArray();
            int outIdx = dst.size();
            for (int i = 0; i < count; i++) {
                if (p < lim) {
                    int b0 = b[p] & 0xFF;
                    if ((b0 & 1) != 0) {
                        out[outIdx + i] = PrefixVarint.decodeZigZag32(b0 >>> 1);
                        p += 1;
                        continue;
                    }
                    if ((b0 & 2) != 0 && lim - p >= 2) {
                        out[outIdx + i] = PrefixVarint.decodeZigZag32(
                            Short.toUnsignedLong(PrefixVarint.getShortLE(b, p)) >>> 2
                        );
                        p += 2;
                        continue;
                    }
                    if ((b0 & 4) != 0 && lim - p >= 4) {
                        out[outIdx + i] = PrefixVarint.decodeZigZag32(
                            Integer.toUnsignedLong(PrefixVarint.getIntLE(b, p)) >>> 3
                        );
                        p += 4;
                        continue;
                    }
                    if (b0 == 0 && lim - p >= 9) {
                        out[outIdx + i] = PrefixVarint.decodeZigZag32(PrefixVarint.getLongLE(b, p + 1));
                        p += 9;
                        continue;
                    }
                }
                throwBadVarint(b, p, lim);
            }
            dst.setSizeUnchecked(outIdx + count);
        }
        return p;
    }

    public static int readFixed32SliceInto(byte[] b, int p, int lim, IntArrayList dst, int count) {
        if (count > 0) {
            if ((long) count * 4L > (long) (lim - p)) {
                throw new OmniBufException("omnibuf: truncated fixed32 array");
            }
            dst.ensureAdditional(count);
            int[] out = dst.rawArray();
            int outIdx = dst.size();
            for (int i = 0; i < count; i++) {
                out[outIdx + i] = PrefixVarint.getIntLE(b, p);
                p += 4;
            }
            dst.setSizeUnchecked(outIdx + count);
        }
        return p;
    }

    public static int readUint64SliceInto(byte[] b, int p, int lim, LongArrayList dst, int count) {
        if (count > 0) {
            dst.ensureAdditional(count);
            long[] out = dst.rawArray();
            int outIdx = dst.size();
            for (int i = 0; i < count; i++) {
                if (p < lim) {
                    int b0 = b[p] & 0xFF;
                    if ((b0 & 1) != 0) {
                        out[outIdx + i] = b0 >>> 1;
                        p += 1;
                        continue;
                    }
                    if ((b0 & 2) != 0 && lim - p >= 2) {
                        out[outIdx + i] = Short.toUnsignedLong(PrefixVarint.getShortLE(b, p)) >>> 2;
                        p += 2;
                        continue;
                    }
                    if ((b0 & 4) != 0 && lim - p >= 4) {
                        out[outIdx + i] = Integer.toUnsignedLong(PrefixVarint.getIntLE(b, p)) >>> 3;
                        p += 4;
                        continue;
                    }
                    if (b0 == 0 && lim - p >= 9) {
                        out[outIdx + i] = PrefixVarint.getLongLE(b, p + 1);
                        p += 9;
                        continue;
                    }
                }
                throwBadVarint(b, p, lim);
            }
            dst.setSizeUnchecked(outIdx + count);
        }
        return p;
    }

    public static int readSint64SliceInto(byte[] b, int p, int lim, LongArrayList dst, int count) {
        if (count > 0) {
            dst.ensureAdditional(count);
            long[] out = dst.rawArray();
            int outIdx = dst.size();
            for (int i = 0; i < count; i++) {
                if (p < lim) {
                    int b0 = b[p] & 0xFF;
                    if ((b0 & 1) != 0) {
                        out[outIdx + i] = PrefixVarint.decodeZigZag64(b0 >>> 1);
                        p += 1;
                        continue;
                    }
                    if ((b0 & 2) != 0 && lim - p >= 2) {
                        out[outIdx + i] = PrefixVarint.decodeZigZag64(
                            Short.toUnsignedLong(PrefixVarint.getShortLE(b, p)) >>> 2
                        );
                        p += 2;
                        continue;
                    }
                    if ((b0 & 4) != 0 && lim - p >= 4) {
                        out[outIdx + i] = PrefixVarint.decodeZigZag64(
                            Integer.toUnsignedLong(PrefixVarint.getIntLE(b, p)) >>> 3
                        );
                        p += 4;
                        continue;
                    }
                    if (b0 == 0 && lim - p >= 9) {
                        out[outIdx + i] = PrefixVarint.decodeZigZag64(PrefixVarint.getLongLE(b, p + 1));
                        p += 9;
                        continue;
                    }
                }
                throwBadVarint(b, p, lim);
            }
            dst.setSizeUnchecked(outIdx + count);
        }
        return p;
    }

    public static int readFixed64SliceInto(byte[] b, int p, int lim, LongArrayList dst, int count) {
        if (count > 0) {
            if ((long) count * 8L > (long) (lim - p)) {
                throw new OmniBufException("omnibuf: truncated fixed64 array");
            }
            dst.ensureAdditional(count);
            long[] out = dst.rawArray();
            int outIdx = dst.size();
            for (int i = 0; i < count; i++) {
                out[outIdx + i] = PrefixVarint.getLongLE(b, p);
                p += 8;
            }
            dst.setSizeUnchecked(outIdx + count);
        }
        return p;
    }

    public boolean hasRemaining() {
        return pos < limit;
    }

    public long readVarint() {
        int p = this.pos;
        int lim = this.limit;
        if (p < lim) {
            int b0 = buf[p] & 0xFF;
            if ((b0 & 1) != 0) {
                this.pos = p + 1;
                return b0 >>> 1;
            }
            if ((b0 & 2) != 0 && lim - p >= 2) {
                long v = Short.toUnsignedLong(PrefixVarint.getShortLE(buf, p)) >>> 2;
                this.pos = p + 2;
                return v;
            }
        }
        return readVarintSlow(p, lim);
    }

    private long readVarintSlow(int p, int lim) {
        if (p >= lim) {
            throw new OmniBufException("omnibuf: unexpected EOF reading prefix-varint");
        }
        int b0 = buf[p] & 0xFF;
        if ((b0 & 2) != 0) {
            throw new OmniBufException("omnibuf: truncated 2-byte prefix-varint");
        }
        if ((b0 & 4) != 0) {
            if (lim - p < 4) {
                throw new OmniBufException("omnibuf: truncated 4-byte prefix-varint");
            }
            long v = Integer.toUnsignedLong(PrefixVarint.getIntLE(buf, p)) >>> 3;
            this.pos = p + 4;
            return v;
        }
        if (b0 == 0) {
            if (lim - p < 9) {
                throw new OmniBufException("omnibuf: truncated 9-byte prefix-varint");
            }
            long v = PrefixVarint.getLongLE(buf, p + 1);
            this.pos = p + 9;
            return v;
        }
        throw new OmniBufException(
            String.format("omnibuf: reserved prefix-varint marker 0x%02x", b0)
        );
    }

    public long readTag() {
        int p = this.pos;
        int lim = this.limit;
        if (p < lim) {
            int b0 = buf[p] & 0xFF;
            if ((b0 & 1) != 0) {
                int tag = b0 >>> 1;
                if (tag >= 16 || tag == WireType.WIRE_SUB_MSG) {
                    this.pos = p + 1;
                    return tag;
                }
            } else if ((b0 & 2) != 0 && lim - p >= 2) {
                int tag = Short.toUnsignedInt(PrefixVarint.getShortLE(buf, p)) >>> 2;
                if (tag >= 16 || tag == WireType.WIRE_SUB_MSG) {
                    this.pos = p + 2;
                    return tag;
                }
            }
        }
        return readTagSlow();
    }

    private long readTagSlow() {
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
        int p = this.pos;
        if (this.limit - p < 4) {
            throwTruncatedFixed32();
        }
        int v = PrefixVarint.getIntLE(buf, p);
        this.pos = p + 4;
        return v;
    }

    public long readFixed64() {
        int p = this.pos;
        if (this.limit - p < 8) {
            throwTruncatedFixed64();
        }
        long v = PrefixVarint.getLongLE(buf, p);
        this.pos = p + 8;
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

    /**
     * Reads a length-prefixed payload header ({@code byteLen >= minLen}), sets {@code this.limit}
     * to the end of that payload, and returns the previous {@code limit} for {@link #endContainer(int)}.
     */
    public int readLengthPrefixedLimit(int minLen) {
        int p = this.pos;
        int lim = this.limit;
        if (p < lim) {
            int b0 = buf[p] & 0xFF;
            if ((b0 & 1) != 0) {
                int len = b0 >>> 1;
                int start = p + 1;
                if (len >= minLen && lim - start >= len) {
                    this.pos = start;
                    this.limit = start + len;
                    return lim;
                }
            } else if ((b0 & 2) != 0 && lim - p >= 2) {
                int len = Short.toUnsignedInt(PrefixVarint.getShortLE(buf, p)) >>> 2;
                int start = p + 2;
                if (len >= minLen && lim - start >= len) {
                    this.pos = start;
                    this.limit = start + len;
                    return lim;
                }
            }
        }
        return readLengthPrefixedLimitSlow(minLen);
    }

    private int readLengthPrefixedLimitSlow(int minLen) {
        long byteLen = readVarint();
        int start = this.pos;
        int lim = this.limit;
        if (Long.compareUnsigned(byteLen, minLen) < 0
                || Long.compareUnsigned(byteLen, lim - start) > 0) {
            throw new OmniBufException("omnibuf: invalid length prefix " + byteLen + " at " + start);
        }
        this.limit = start + (int) byteLen;
        return lim;
    }

    public void endContainer(int savedLimit) {
        if (this.pos != this.limit) {
            throw new OmniBufException("omnibuf: container has trailing payload bytes");
        }
        this.limit = savedLimit;
    }

    /**
     * Zero-allocation {@code WireArray} opener on the current cursor.
     *
     * <p>If the wire element type matches {@code expectedElemWT}, advances {@code pos} to the first
     * element, sets {@code limit} to {@code bodyEnd}, and returns packed
     * {@code (((long) savedLimit) << 32) | (count & 0xFFFF_FFFFL)}.
     * If the wire element type does not match {@code expectedElemWT}, deeply validates the array,
     * advances {@code pos} past {@code bodyEnd}, and returns {@code -1L}.
     */
    public long beginArray(int expectedElemWT, int depth) {
        if (depth + 1 > this.maxNestingDepth) {
            throw new OmniBufException("omnibuf: WireArray exceeds MaxNestingDepth");
        }
        int savedLimit = readLengthPrefixedLimit(2);
        int bodyStart = this.pos;
        int bodyEnd = this.limit;
        int elemType = buf[bodyStart] & 0xFF;
        if (!WireType.isValidArrayElemWireType(elemType)) {
            this.limit = savedLimit;
            throw new OmniBufException("omnibuf: invalid WireArray element type: " + elemType);
        }
        this.pos = bodyStart + 1;
        long countLong = readVarint();
        int payloadStart = this.pos;
        int remaining = bodyEnd - payloadStart;
        if (Long.compareUnsigned(countLong, remaining) > 0) {
            this.limit = savedLimit;
            throw new OmniBufException("omnibuf: WireArray count exceeds remaining payload bytes");
        }
        if (Long.compareUnsigned(countLong, this.maxCollectionElements) > 0) {
            this.limit = savedLimit;
            throw new OmniBufException(
                "omnibuf: WireArray count " + countLong + " exceeds maxCollectionElements (" + maxCollectionElements + ")"
            );
        }
        int count = (int) countLong;
        int fixedWidth = WireType.fixedElementWidth(elemType);
        if (fixedWidth > 0) {
            if (remaining % fixedWidth != 0 || count != remaining / fixedWidth) {
                this.limit = savedLimit;
                throw new OmniBufException("omnibuf: WireArray fixed-width count/byte-length mismatch");
            }
        } else if (count == 0 && remaining != 0) {
            this.limit = savedLimit;
            throw new OmniBufException("omnibuf: empty WireArray has trailing payload bytes");
        }
        if (elemType != expectedElemWT) {
            this.limit = savedLimit;
            throw new OmniBufException(
                "omnibuf: WireArray element type mismatch: expected "
                    + WireType.nameOf(expectedElemWT) + ", got " + WireType.nameOf(elemType)
            );
        }
        return (((long) savedLimit) << 32) | (count & 0xFFFF_FFFFL);
    }

    /**
     * Zero-allocation {@code WireMap} opener on the current cursor.
     */
    public long beginMap(int expectedKeyWT, int expectedValWT, int depth) {
        if (depth + 1 > this.maxNestingDepth) {
            throw new OmniBufException("omnibuf: WireMap exceeds MaxNestingDepth");
        }
        int savedLimit = readLengthPrefixedLimit(3);
        int bodyStart = this.pos;
        int bodyEnd = this.limit;
        int keyWT = buf[bodyStart] & 0xFF;
        int valWT = buf[bodyStart + 1] & 0xFF;
        if (!WireType.isValidMapKeyWireType(keyWT) || !WireType.isValidMapValWireType(valWT)) {
            this.limit = savedLimit;
            throw new OmniBufException(
                "omnibuf: invalid WireMap key/value wire types: " + keyWT + "/" + valWT
            );
        }
        this.pos = bodyStart + 2;
        long countLong = readVarint();
        int payloadStart = this.pos;
        int remaining = bodyEnd - payloadStart;
        if (countLong == 0L) {
            if (remaining != 0) {
                this.limit = savedLimit;
                throw new OmniBufException("omnibuf: empty WireMap has trailing payload bytes");
            }
        } else if (Long.compareUnsigned(countLong, remaining / 2) > 0) {
            this.limit = savedLimit;
            throw new OmniBufException("omnibuf: WireMap count exceeds remaining key/value capacity");
        }
        if (Long.compareUnsigned(countLong, this.maxCollectionElements) > 0) {
            this.limit = savedLimit;
            throw new OmniBufException(
                "omnibuf: WireMap count " + countLong + " exceeds maxCollectionElements (" + maxCollectionElements + ")"
            );
        }
        int count = (int) countLong;
        if (keyWT != expectedKeyWT || valWT != expectedValWT) {
            this.limit = savedLimit;
            throw new OmniBufException(
                "omnibuf: WireMap key/value type mismatch: expected "
                    + WireType.nameOf(expectedKeyWT) + "/" + WireType.nameOf(expectedValWT)
                    + ", got " + WireType.nameOf(keyWT) + "/" + WireType.nameOf(valWT)
            );
        }
        return (((long) savedLimit) << 32) | (count & 0xFFFF_FFFFL);
    }

    public Slice readLengthPrefixedSlice(int minLen) {
        int savedLimit = readLengthPrefixedLimit(minLen);
        int start = this.pos;
        int len = this.limit - start;
        this.pos = this.limit;
        this.limit = savedLimit;
        return new Slice(start, len);
    }

    public String readString() {
        int p = this.pos;
        int lim = this.limit;
        if (p < lim) {
            int b0 = buf[p] & 0xFF;
            if ((b0 & 1) != 0) {
                int len = b0 >>> 1;
                int start = p + 1;
                if (lim - start >= len) {
                    this.pos = start + len;
                    return len == 0 ? "" : Utf8Validator.decodeStrictUnchecked(buf, start, len);
                }
            }
        }
        return readStringSlow();
    }

    private String readStringSlow() {
        int savedLimit = readLengthPrefixedLimit(0);
        int start = this.pos;
        int len = this.limit - start;
        this.pos = this.limit;
        this.limit = savedLimit;
        return len == 0 ? "" : Utf8Validator.decodeStrictUnchecked(buf, start, len);
    }

    public void readUint32Into(IntArrayList dst, int count) {
        if (count <= 0) {
            return;
        }
        dst.ensureAdditional(count);
        int[] out = dst.rawArray();
        int outIdx = dst.size();
        byte[] b = this.buf;
        int p = this.pos;
        int lim = this.limit;
        for (int i = 0; i < count; i++) {
            if (p < lim) {
                int b0 = b[p] & 0xFF;
                if ((b0 & 1) != 0) {
                    out[outIdx + i] = b0 >>> 1;
                    p += 1;
                    continue;
                }
                if ((b0 & 2) != 0 && lim - p >= 2) {
                    out[outIdx + i] = Short.toUnsignedInt(PrefixVarint.getShortLE(b, p)) >>> 2;
                    p += 2;
                    continue;
                }
                if ((b0 & 4) != 0 && lim - p >= 4) {
                    out[outIdx + i] = PrefixVarint.getIntLE(b, p) >>> 3;
                    p += 4;
                    continue;
                }
                if (b0 == 0 && lim - p >= 9) {
                    out[outIdx + i] = (int) PrefixVarint.getLongLE(b, p + 1);
                    p += 9;
                    continue;
                }
            }
            this.pos = p;
            out[outIdx + i] = (int) readVarint();
            p = this.pos;
        }
        this.pos = p;
        dst.setSizeUnchecked(outIdx + count);
    }

    public void readSint32Into(IntArrayList dst, int count) {
        if (count <= 0) {
            return;
        }
        dst.ensureAdditional(count);
        int[] out = dst.rawArray();
        int outIdx = dst.size();
        byte[] b = this.buf;
        int p = this.pos;
        int lim = this.limit;
        for (int i = 0; i < count; i++) {
            long raw;
            if (p < lim) {
                int b0 = b[p] & 0xFF;
                if ((b0 & 1) != 0) {
                    raw = b0 >>> 1;
                    p += 1;
                    out[outIdx + i] = PrefixVarint.decodeZigZag32(raw);
                    continue;
                }
                if ((b0 & 2) != 0 && lim - p >= 2) {
                    raw = Short.toUnsignedLong(PrefixVarint.getShortLE(b, p)) >>> 2;
                    p += 2;
                    out[outIdx + i] = PrefixVarint.decodeZigZag32(raw);
                    continue;
                }
                if ((b0 & 4) != 0 && lim - p >= 4) {
                    raw = Integer.toUnsignedLong(PrefixVarint.getIntLE(b, p)) >>> 3;
                    p += 4;
                    out[outIdx + i] = PrefixVarint.decodeZigZag32(raw);
                    continue;
                }
                if (b0 == 0 && lim - p >= 9) {
                    raw = PrefixVarint.getLongLE(b, p + 1);
                    p += 9;
                    out[outIdx + i] = PrefixVarint.decodeZigZag32(raw);
                    continue;
                }
            }
            this.pos = p;
            out[outIdx + i] = PrefixVarint.decodeZigZag32(readVarint());
            p = this.pos;
        }
        this.pos = p;
        dst.setSizeUnchecked(outIdx + count);
    }

    public void readFixed32Into(IntArrayList dst, int count) {
        if (count <= 0) {
            return;
        }
        if ((long) count * 4L > (long) (this.limit - this.pos)) {
            throw new OmniBufException("omnibuf: truncated fixed32 array");
        }
        dst.ensureAdditional(count);
        int[] out = dst.rawArray();
        int outIdx = dst.size();
        byte[] b = this.buf;
        int p = this.pos;
        for (int i = 0; i < count; i++) {
            out[outIdx + i] = PrefixVarint.getIntLE(b, p);
            p += 4;
        }
        this.pos = p;
        dst.setSizeUnchecked(outIdx + count);
    }

    public void readUint64Into(LongArrayList dst, int count) {
        if (count <= 0) {
            return;
        }
        dst.ensureAdditional(count);
        long[] out = dst.rawArray();
        int outIdx = dst.size();
        byte[] b = this.buf;
        int p = this.pos;
        int lim = this.limit;
        for (int i = 0; i < count; i++) {
            if (p < lim) {
                int b0 = b[p] & 0xFF;
                if ((b0 & 1) != 0) {
                    out[outIdx + i] = b0 >>> 1;
                    p += 1;
                    continue;
                }
                if ((b0 & 2) != 0 && lim - p >= 2) {
                    out[outIdx + i] = Short.toUnsignedLong(PrefixVarint.getShortLE(b, p)) >>> 2;
                    p += 2;
                    continue;
                }
                if ((b0 & 4) != 0 && lim - p >= 4) {
                    out[outIdx + i] = Integer.toUnsignedLong(PrefixVarint.getIntLE(b, p)) >>> 3;
                    p += 4;
                    continue;
                }
                if (b0 == 0 && lim - p >= 9) {
                    out[outIdx + i] = PrefixVarint.getLongLE(b, p + 1);
                    p += 9;
                    continue;
                }
            }
            this.pos = p;
            out[outIdx + i] = readVarint();
            p = this.pos;
        }
        this.pos = p;
        dst.setSizeUnchecked(outIdx + count);
    }

    public void readSint64Into(LongArrayList dst, int count) {
        if (count <= 0) {
            return;
        }
        dst.ensureAdditional(count);
        long[] out = dst.rawArray();
        int outIdx = dst.size();
        byte[] b = this.buf;
        int p = this.pos;
        int lim = this.limit;
        for (int i = 0; i < count; i++) {
            long raw;
            if (p < lim) {
                int b0 = b[p] & 0xFF;
                if ((b0 & 1) != 0) {
                    raw = b0 >>> 1;
                    p += 1;
                    out[outIdx + i] = PrefixVarint.decodeZigZag64(raw);
                    continue;
                }
                if ((b0 & 2) != 0 && lim - p >= 2) {
                    raw = Short.toUnsignedLong(PrefixVarint.getShortLE(b, p)) >>> 2;
                    p += 2;
                    out[outIdx + i] = PrefixVarint.decodeZigZag64(raw);
                    continue;
                }
                if ((b0 & 4) != 0 && lim - p >= 4) {
                    raw = Integer.toUnsignedLong(PrefixVarint.getIntLE(b, p)) >>> 3;
                    p += 4;
                    out[outIdx + i] = PrefixVarint.decodeZigZag64(raw);
                    continue;
                }
                if (b0 == 0 && lim - p >= 9) {
                    raw = PrefixVarint.getLongLE(b, p + 1);
                    p += 9;
                    out[outIdx + i] = PrefixVarint.decodeZigZag64(raw);
                    continue;
                }
            }
            this.pos = p;
            out[outIdx + i] = PrefixVarint.decodeZigZag64(readVarint());
            p = this.pos;
        }
        this.pos = p;
        dst.setSizeUnchecked(outIdx + count);
    }

    public void readFixed64Into(LongArrayList dst, int count) {
        if (count <= 0) {
            return;
        }
        if ((long) count * 8L > (long) (this.limit - this.pos)) {
            throw new OmniBufException("omnibuf: truncated fixed64 array");
        }
        dst.ensureAdditional(count);
        long[] out = dst.rawArray();
        int outIdx = dst.size();
        byte[] b = this.buf;
        int p = this.pos;
        for (int i = 0; i < count; i++) {
            out[outIdx + i] = PrefixVarint.getLongLE(b, p);
            p += 8;
        }
        this.pos = p;
        dst.setSizeUnchecked(outIdx + count);
    }

    public byte[] readBytes() {
        int savedLimit = readLengthPrefixedLimit(0);
        int start = this.pos;
        int end = this.limit;
        this.pos = end;
        this.limit = savedLimit;
        return start == end ? EMPTY_BYTES : Arrays.copyOfRange(buf, start, end);
    }

    public void skipOrValidateUnknown(int fieldNumber, int wireType, int depth) {
        if (fieldNumber == 0) {
            if (wireType != WireType.WIRE_SUB_MSG) {
                throw new OmniBufException("omnibuf: Field #0 requires WireSubMsg");
            }
            int savedLimit = readLengthPrefixedLimit(0);
            int start = this.pos;
            int len = this.limit - start;
            this.pos = this.limit;
            this.limit = savedLimit;
            SchemaMeta.decode(buf, start, len, depth + 1, maxNestingDepth, maxCollectionElements);
            return;
        }
        pos = validateValue(buf, pos, limit, wireType, depth, maxNestingDepth, maxCollectionElements);
    }

    // ========================================================================
    // Static Header Parsers & Structural Validators
    // ========================================================================

    public static Slice readLengthSlice(byte[] buf, int pos, int limit, int minLen) {
        long packed = PrefixVarint.readLengthSlicePacked(buf, pos, limit, minLen);
        int start = (int) (packed >>> 32);
        int end = (int) packed;
        return new Slice(start, end - start);
    }

    public static boolean isValidArrayHeader(byte[] buf, int bodyStart, int bodyEnd) {
        if (bodyEnd - bodyStart < 2) {
            return false;
        }
        int elemType = buf[bodyStart] & 0xFF;
        if (!WireType.isValidArrayElemWireType(elemType)) {
            return false;
        }
        int cPos = bodyStart + 1;
        int b0 = buf[cPos] & 0xFF;
        long countLong;
        int payloadStart;
        if ((b0 & 1) != 0) {
            countLong = b0 >>> 1;
            payloadStart = cPos + 1;
        } else if ((b0 & 2) != 0) {
            if (bodyEnd - cPos < 2) {
                return false;
            }
            countLong = Short.toUnsignedLong(PrefixVarint.getShortLE(buf, cPos)) >>> 2;
            payloadStart = cPos + 2;
        } else if ((b0 & 4) != 0) {
            if (bodyEnd - cPos < 4) {
                return false;
            }
            countLong = Integer.toUnsignedLong(PrefixVarint.getIntLE(buf, cPos)) >>> 3;
            payloadStart = cPos + 4;
        } else if (b0 == 0) {
            if (bodyEnd - cPos < 9) {
                return false;
            }
            countLong = PrefixVarint.getLongLE(buf, cPos + 1);
            payloadStart = cPos + 9;
        } else {
            return false;
        }
        int remaining = bodyEnd - payloadStart;
        if (Long.compareUnsigned(countLong, remaining) > 0) {
            return false;
        }
        int count = (int) countLong;
        int fixedWidth = WireType.fixedElementWidth(elemType);
        if (fixedWidth > 0) {
            return remaining % fixedWidth == 0 && count == remaining / fixedWidth;
        }
        return count != 0 || remaining == 0;
    }

    public static boolean isValidMapHeader(byte[] buf, int bodyStart, int bodyEnd) {
        if (bodyEnd - bodyStart < 3) {
            return false;
        }
        int keyWT = buf[bodyStart] & 0xFF;
        int valWT = buf[bodyStart + 1] & 0xFF;
        if (!WireType.isValidMapKeyWireType(keyWT) || !WireType.isValidMapValWireType(valWT)) {
            return false;
        }
        int cPos = bodyStart + 2;
        int b0 = buf[cPos] & 0xFF;
        long countLong;
        int payloadStart;
        if ((b0 & 1) != 0) {
            countLong = b0 >>> 1;
            payloadStart = cPos + 1;
        } else if ((b0 & 2) != 0) {
            if (bodyEnd - cPos < 2) {
                return false;
            }
            countLong = Short.toUnsignedLong(PrefixVarint.getShortLE(buf, cPos)) >>> 2;
            payloadStart = cPos + 2;
        } else if ((b0 & 4) != 0) {
            if (bodyEnd - cPos < 4) {
                return false;
            }
            countLong = Integer.toUnsignedLong(PrefixVarint.getIntLE(buf, cPos)) >>> 3;
            payloadStart = cPos + 4;
        } else if (b0 == 0) {
            if (bodyEnd - cPos < 9) {
                return false;
            }
            countLong = PrefixVarint.getLongLE(buf, cPos + 1);
            payloadStart = cPos + 9;
        } else {
            return false;
        }
        int remaining = bodyEnd - payloadStart;
        if (countLong == 0L) {
            return remaining == 0;
        }
        return Long.compareUnsigned(countLong, remaining / 2) <= 0;
    }

    public static ArrayHeader parseArrayHeader(byte[] buf, int bodyStart, int bodyEnd) {
        return parseArrayHeader(buf, bodyStart, bodyEnd, UnmarshalOptions.DEFAULT_MAX_COLLECTION_ELEMENTS);
    }

    public static ArrayHeader parseArrayHeader(
            byte[] buf, int bodyStart, int bodyEnd, int maxCollectionElements) {
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
        if (Long.compareUnsigned(countVar.value(), maxCollectionElements) > 0) {
            throw new OmniBufException(
                "omnibuf: WireArray count " + countVar.value()
                    + " exceeds maxCollectionElements (" + maxCollectionElements + ")"
            );
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
        return parseMapHeader(buf, bodyStart, bodyEnd, UnmarshalOptions.DEFAULT_MAX_COLLECTION_ELEMENTS);
    }

    public static MapHeader parseMapHeader(
            byte[] buf, int bodyStart, int bodyEnd, int maxCollectionElements) {
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
        if (Long.compareUnsigned(countVar.value(), maxCollectionElements) > 0) {
            throw new OmniBufException(
                "omnibuf: WireMap count " + countVar.value()
                    + " exceeds maxCollectionElements (" + maxCollectionElements + ")"
            );
        }
        return new MapHeader(keyWT, valWT, (int) countVar.value(), payloadStart, bodyEnd);
    }

    /**
     * Zero-allocation O(1) value skipper returning the next offset, or {@code -1} on structural/bounds error.
     */
    public static int tryFastSkipValue(byte[] buf, int pos, int limit, int wireType) {
        if (buf == null || pos < 0 || limit < 0 || pos > limit || limit > buf.length) {
            return -1;
        }
        return switch (wireType) {
            case WireType.WIRE_UINT, WireType.WIRE_SINT -> PrefixVarint.skipVarint(buf, pos, limit);
            case WireType.WIRE_BOOL_FALSE, WireType.WIRE_BOOL_TRUE -> pos;
            case WireType.WIRE_FIXED32, WireType.WIRE_SFIXED32, WireType.WIRE_FLOAT32 ->
                limit - pos >= 4 ? pos + 4 : -1;
            case WireType.WIRE_FIXED64, WireType.WIRE_SFIXED64, WireType.WIRE_FLOAT64 ->
                limit - pos >= 8 ? pos + 8 : -1;
            case WireType.WIRE_STRING, WireType.WIRE_BYTES, WireType.WIRE_SUB_MSG -> {
                if (pos >= limit) {
                    yield -1;
                }
                int b0 = buf[pos] & 0xFF;
                if ((b0 & 1) != 0) {
                    int len = b0 >>> 1;
                    yield limit - pos - 1 >= len ? pos + 1 + len : -1;
                }
                long packed = PrefixVarint.tryReadLengthSlicePacked(buf, pos, limit, 0);
                yield packed < 0L ? -1 : (int) packed;
            }
            case WireType.WIRE_ARRAY -> {
                long packed = PrefixVarint.tryReadLengthSlicePacked(buf, pos, limit, 2);
                yield packed < 0L ? -1 : (int) packed;
            }
            case WireType.WIRE_MAP -> {
                long packed = PrefixVarint.tryReadLengthSlicePacked(buf, pos, limit, 3);
                yield packed < 0L ? -1 : (int) packed;
            }
            case WireType.WIRE_EXTENDED -> {
                long packed = PrefixVarint.tryReadLengthSlicePacked(buf, pos, limit, 1);
                yield packed < 0L ? -1 : (int) packed;
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
        return validateValue(
            buf,
            pos,
            limit,
            wireType,
            depth,
            MAX_CONTAINER_DEPTH,
            UnmarshalOptions.DEFAULT_MAX_COLLECTION_ELEMENTS
        );
    }

    public static int validateValue(
            byte[] buf,
            int pos,
            int limit,
            int wireType,
            int depth,
            int maxNestingDepth,
            int maxCollectionElements) {
        if (depth > maxNestingDepth) {
            throw new OmniBufException(
                "omnibuf: nesting depth " + depth + " exceeds MaxNestingDepth (" + maxNestingDepth + ")"
            );
        }
        if (buf == null || pos < 0 || limit < 0 || pos > limit || limit > buf.length) {
            throw new OmniBufException("omnibuf: offset out of bounds: " + pos);
        }
        return switch (wireType) {
            case WireType.WIRE_UINT, WireType.WIRE_SINT -> {
                int next = PrefixVarint.skipVarint(buf, pos, limit);
                if (next < 0) {
                    PrefixVarint.decode(buf, pos, limit);
                }
                yield next;
            }
            case WireType.WIRE_BOOL_FALSE, WireType.WIRE_BOOL_TRUE -> pos;
            case WireType.WIRE_FIXED32, WireType.WIRE_SFIXED32, WireType.WIRE_FLOAT32 -> {
                if (limit - pos < 4) {
                    throw new OmniBufException("omnibuf: truncated 32-bit scalar at " + pos);
                }
                yield pos + 4;
            }
            case WireType.WIRE_FIXED64, WireType.WIRE_SFIXED64, WireType.WIRE_FLOAT64 -> {
                if (limit - pos < 8) {
                    throw new OmniBufException("omnibuf: truncated 64-bit scalar at " + pos);
                }
                yield pos + 8;
            }
            case WireType.WIRE_STRING -> {
                long packed = PrefixVarint.readLengthSlicePacked(buf, pos, limit, 0);
                int start = (int) (packed >>> 32);
                int end = (int) packed;
                if (!Utf8Validator.isValidUtf8(buf, start, end - start)) {
                    throw new OmniBufException("omnibuf: invalid UTF-8 in WireString at " + pos);
                }
                yield end;
            }
            case WireType.WIRE_BYTES -> (int) PrefixVarint.readLengthSlicePacked(buf, pos, limit, 0);
            case WireType.WIRE_SUB_MSG -> {
                long packed = PrefixVarint.readLengthSlicePacked(buf, pos, limit, 0);
                int start = (int) (packed >>> 32);
                int end = (int) packed;
                validateMessageSlice(buf, start, end - start, depth + 1, maxNestingDepth, maxCollectionElements);
                yield end;
            }
            case WireType.WIRE_ARRAY -> {
                if (depth + 1 > maxNestingDepth) {
                    throw new OmniBufException("omnibuf: WireArray exceeds MaxNestingDepth");
                }
                long packed = PrefixVarint.readLengthSlicePacked(buf, pos, limit, 2);
                int start = (int) (packed >>> 32);
                int end = (int) packed;
                ArrayHeader hdr = parseArrayHeader(buf, start, end, maxCollectionElements);
                validateArrayPayloadRaw(
                    buf, hdr.elemWireType(), hdr.count(), hdr.payloadStart(), end,
                    depth + 1, maxNestingDepth, maxCollectionElements
                );
                yield end;
            }
            case WireType.WIRE_MAP -> {
                if (depth + 1 > maxNestingDepth) {
                    throw new OmniBufException("omnibuf: WireMap exceeds MaxNestingDepth");
                }
                long packed = PrefixVarint.readLengthSlicePacked(buf, pos, limit, 3);
                int start = (int) (packed >>> 32);
                int end = (int) packed;
                MapHeader hdr = parseMapHeader(buf, start, end, maxCollectionElements);
                int p = hdr.payloadStart();
                for (int i = 0; i < hdr.count(); i++) {
                    p = validateContainerEntry(
                        buf, p, hdr.payloadEnd(), hdr.keyWireType(),
                        depth + 1, maxNestingDepth, maxCollectionElements
                    );
                    p = validateContainerEntry(
                        buf, p, hdr.payloadEnd(), hdr.valWireType(),
                        depth + 1, maxNestingDepth, maxCollectionElements
                    );
                }
                if (p != hdr.payloadEnd()) {
                    throw new OmniBufException("omnibuf: WireMap has trailing payload bytes");
                }
                yield end;
            }
            case WireType.WIRE_EXTENDED -> (int) PrefixVarint.readLengthSlicePacked(buf, pos, limit, 1);
            default -> throw new OmniBufException("omnibuf: invalid wire type: " + wireType);
        };
    }

    public static void validateArrayBody(byte[] buf, ArrayHeader hdr, int arrayDepth) {
        validateArrayPayloadRaw(
            buf,
            hdr.elemWireType(),
            hdr.count(),
            hdr.payloadStart(),
            hdr.payloadEnd(),
            arrayDepth,
            MAX_CONTAINER_DEPTH,
            UnmarshalOptions.DEFAULT_MAX_COLLECTION_ELEMENTS
        );
    }

    public static void validateMapBody(byte[] buf, MapHeader hdr, int mapDepth) {
        int p = hdr.payloadStart();
        int end = hdr.payloadEnd();
        for (int i = 0; i < hdr.count(); i++) {
            p = validateContainerEntry(
                buf, p, end, hdr.keyWireType(),
                mapDepth, MAX_CONTAINER_DEPTH, UnmarshalOptions.DEFAULT_MAX_COLLECTION_ELEMENTS
            );
            p = validateContainerEntry(
                buf, p, end, hdr.valWireType(),
                mapDepth, MAX_CONTAINER_DEPTH, UnmarshalOptions.DEFAULT_MAX_COLLECTION_ELEMENTS
            );
        }
        if (p != end) {
            throw new OmniBufException("omnibuf: WireMap has trailing payload bytes");
        }
    }

    private static void validateArrayPayloadRaw(
            byte[] buf,
            int elemWT,
            int count,
            int p,
            int end,
            int arrayDepth,
            int maxNestingDepth,
            int maxCollectionElements) {
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
            p = validateContainerEntry(
                buf, p, end, elemWT, arrayDepth, maxNestingDepth, maxCollectionElements
            );
        }
        if (p != end) {
            throw new OmniBufException("omnibuf: WireArray has trailing payload bytes");
        }
    }

    private static int validateContainerEntry(
            byte[] buf,
            int pos,
            int limit,
            int wt,
            int depth,
            int maxNestingDepth,
            int maxCollectionElements) {
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
        return validateValue(buf, pos, limit, wt, depth, maxNestingDepth, maxCollectionElements);
    }

    public static void validateMessage(byte[] buf, int offset, int length, int depth) {
        validateMessage(buf, offset, length, depth, UnmarshalOptions.DEFAULT);
    }

    public static void validateMessage(
            byte[] buf, int offset, int length, int depth, UnmarshalOptions options) {
        if (buf == null || offset < 0 || length < 0 || length > buf.length - offset) {
            throw new OmniBufException("omnibuf: packet slice out of bounds");
        }
        UnmarshalOptions opts = options == null ? UnmarshalOptions.DEFAULT : options;
        if (length > opts.maxMessageBytes()) {
            throw new OmniBufException(
                "omnibuf: packet length " + length + " exceeds maxMessageBytes (" + opts.maxMessageBytes() + ")"
            );
        }
        validateMessageSlice(
            buf, offset, length, depth, opts.maxNestingDepth(), opts.maxCollectionElements()
        );
    }

    public static void validateMessage(
            byte[] buf,
            int offset,
            int length,
            int depth,
            int maxNestingDepth,
            int maxCollectionElements) {
        if (buf == null || offset < 0 || length < 0 || length > buf.length - offset) {
            throw new OmniBufException("omnibuf: packet slice out of bounds");
        }
        validateMessageSlice(buf, offset, length, depth, maxNestingDepth, maxCollectionElements);
    }

    /**
     * Deeply validates wire framing and strict UTF-8 invariants of {@code buf[offset..offset+length]}.
     */
    public static void validateStructuralMessage(byte[] buf, int offset, int length, int depth) {
        validateMessage(buf, offset, length, depth, UnmarshalOptions.DEFAULT);
    }

    public static void validateStructuralMessage(
            byte[] buf, int offset, int length, int depth, UnmarshalOptions options) {
        validateMessage(buf, offset, length, depth, options);
    }

    private static void validateMessageSlice(
            byte[] buf,
            int offset,
            int length,
            int depth,
            int maxNestingDepth,
            int maxCollectionElements) {
        if (depth > maxNestingDepth) {
            throw new OmniBufException(
                "omnibuf: nesting depth " + depth + " exceeds MaxNestingDepth (" + maxNestingDepth + ")"
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
                long packed = PrefixVarint.readLengthSlicePacked(buf, pos, end, 0);
                int start = (int) (packed >>> 32);
                int subEnd = (int) packed;
                SchemaMeta.decode(
                    buf, start, subEnd - start, depth + 1, maxNestingDepth, maxCollectionElements
                );
                pos = subEnd;
            } else {
                pos = validateValue(
                    buf, pos, end, wt, depth, maxNestingDepth, maxCollectionElements
                );
            }
        }
    }

    private static final byte[] EMPTY_BYTES = new byte[0];
}
