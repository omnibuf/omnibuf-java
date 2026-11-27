package com.omnibuf.wire;

import com.omnibuf.OmniBufException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Single-pass binary encoder for OmniBuf packets with stackless fast-path container framing,
 * overflow-safe capacity growth, and strict container nesting depth enforcement.
 */
public final class OmniBufEncoder {

    private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;
    private static final int CHAR_SCRATCH_SIZE = 2048;

    final boolean isPooledSlot;
    boolean pooledInUse;

    private byte[] buf;
    private int pos;

    private int[] lenStack;
    private int[] countStack;
    private int stackDepth;
    private int depth;

    private char[] charScratch;
    private CharBuffer charScratchBuf;
    private ByteBuffer cachedByteBuf;
    private CharsetEncoder utf8Encoder;

    public OmniBufEncoder() {
        this(256, false);
    }

    public OmniBufEncoder(int initialCapacity) {
        this(initialCapacity, false);
    }

    OmniBufEncoder(int initialCapacity, boolean isPooledSlot) {
        if (initialCapacity < 0 || initialCapacity > MAX_ARRAY_SIZE) {
            throw new OmniBufException("omnibuf: invalid initial encoder capacity: " + initialCapacity);
        }
        this.isPooledSlot = isPooledSlot;
        this.buf = new byte[Math.max(32, initialCapacity)];
        this.pos = 0;
        this.stackDepth = 0;
        this.depth = 0;
    }

    /**
     * Wraps an existing destination buffer starting at {@code initialPos} (preserving
     * {@code existingBuf[0..initialPos]} as an existing prefix).
     */
    public OmniBufEncoder(byte[] existingBuf, int initialPos) {
        if (existingBuf == null || initialPos < 0 || initialPos > existingBuf.length) {
            throw new OmniBufException("omnibuf: invalid existing buffer or initialPos");
        }
        this.isPooledSlot = false;
        this.buf = existingBuf;
        this.pos = initialPos;
        this.stackDepth = 0;
        this.depth = 0;
    }

    /**
     * If this encoder shares the same backing array as {@code raw} and future writes could
     * overlap {@code raw[rawOffset..rawOffset+rawLength]}, detaches onto a fresh array while
     * preserving {@code buf[0..pos]}.
     */
    public void detachIfAliased(byte[] raw, int rawOffset, int rawLength) {
        if (raw != null && (rawOffset < 0 || rawLength < 0 || rawLength > raw.length - rawOffset)) {
            throw new OmniBufException("omnibuf: raw slice out of bounds");
        }
        if (this.buf == raw && rawLength > 0) {
            long dstEnd = this.buf.length;
            long rawEnd = (long) rawOffset + (long) rawLength;
            if (rawEnd > 0L && rawOffset < dstEnd) {
                long nextCap = Math.max((long) this.pos + (long) rawLength + 64L, 64L);
                if (nextCap > MAX_ARRAY_SIZE) {
                    throw new OmniBufException("omnibuf: aliased splice exceeds maximum array size");
                }
                this.buf = Arrays.copyOf(this.buf, (int) nextCap);
                this.cachedByteBuf = null;
            }
        }
    }

    public void reset() {
        this.pos = 0;
        this.stackDepth = 0;
        this.depth = 0;
    }

    public void resetAndTrim(int maxRetainedCapacity, int shrunkCapacity) {
        this.pos = 0;
        this.stackDepth = 0;
        this.depth = 0;
        if (this.buf.length > maxRetainedCapacity) {
            this.buf = new byte[Math.max(32, shrunkCapacity)];
            this.cachedByteBuf = null;
        }
    }

    public int size() {
        return pos;
    }

    public int capacity() {
        return buf.length;
    }

    public byte[] ensureFree(int additional) {
        byte[] b = this.buf;
        if (b.length - this.pos < additional) {
            return growAndReturn(additional);
        }
        return b;
    }

    private byte[] growAndReturn(int additional) {
        ensureCapacity(additional);
        return this.buf;
    }

    public int rawPosition() {
        return this.pos;
    }

    public void setRawPosition(int newPos) {
        this.pos = newPos;
    }

    public byte[] toByteArray() {
        if (depth != 0 || stackDepth != 0) {
            throw new OmniBufException("omnibuf: unclosed container on encoder (depth=" + depth + ")");
        }
        return Arrays.copyOf(buf, pos);
    }

    private void ensureCapacity(int additional) {
        if (additional < 0) {
            throw new OmniBufException("omnibuf: negative capacity request: " + additional);
        }
        if (buf.length - pos < additional) {
            growBuffer((long) pos + (long) additional);
        }
    }

    private void ensureCapacityLong(long totalNeeded) {
        if (totalNeeded < 0L || totalNeeded > buf.length) {
            growBuffer(totalNeeded);
        }
    }

    private void growBuffer(long totalNeeded) {
        if (totalNeeded < 0L || totalNeeded > MAX_ARRAY_SIZE) {
            throw new OmniBufException("omnibuf: packet exceeds maximum array size");
        }
        int needed = (int) totalNeeded;
        long newCap = Math.max(buf.length, 32);
        while (newCap < needed) {
            newCap = Math.min(newCap << 1, (long) MAX_ARRAY_SIZE);
            if (newCap < needed && newCap == MAX_ARRAY_SIZE) {
                break;
            }
        }
        buf = Arrays.copyOf(buf, (int) newCap);
        cachedByteBuf = null;
    }

    private ByteBuffer prepareByteBuf(int writePos) {
        ByteBuffer bb = this.cachedByteBuf;
        if (bb == null) {
            bb = ByteBuffer.wrap(this.buf);
            this.cachedByteBuf = bb;
        }
        bb.limit(this.buf.length).position(writePos);
        return bb;
    }

    private void pushContainer(int lenPos, int initialCount) {
        if (lenStack == null) {
            lenStack = new int[16];
            countStack = new int[16];
        } else if (stackDepth == lenStack.length) {
            int nextCap = lenStack.length << 1;
            lenStack = Arrays.copyOf(lenStack, nextCap);
            countStack = Arrays.copyOf(countStack, nextCap);
        }
        lenStack[stackDepth] = lenPos;
        countStack[stackDepth] = initialCount;
        stackDepth++;
    }

    public void writeRawBytes(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return;
        }
        ensureCapacity(raw.length);
        System.arraycopy(raw, 0, buf, pos, raw.length);
        pos += raw.length;
    }

    public void writeRawBytes(byte[] raw, int off, int len) {
        if (raw == null || off < 0 || len < 0 || len > raw.length - off) {
            throw new OmniBufException("omnibuf: raw byte slice out of bounds");
        }
        if (len == 0) {
            return;
        }
        ensureCapacity(len);
        System.arraycopy(raw, off, buf, pos, len);
        pos += len;
    }

    public void writeRawByte(int b) {
        ensureCapacity(1);
        buf[pos++] = (byte) b;
    }

    public void writeInlineFixed32(int v) {
        ensureCapacity(4);
        PrefixVarint.setIntLE(buf, pos, v);
        pos += 4;
    }

    public void writeInlineFixed64(long v) {
        ensureCapacity(8);
        PrefixVarint.setLongLE(buf, pos, v);
        pos += 8;
    }

    public void writeVarint(long v) {
        ensureCapacity(9);
        pos = PrefixVarint.write(buf, pos, v);
    }

    public void writeTag(int fieldNumber, int wireType) {
        if (fieldNumber > 0 && fieldNumber < 8 && (wireType & ~0x0F) == 0) {
            ensureCapacity(1);
            buf[pos++] = (byte) ((((fieldNumber << 4) | wireType) << 1) | 1);
            return;
        }
        long tag = WireType.makeTag(fieldNumber, wireType);
        ensureCapacity(9);
        pos = PrefixVarint.write(buf, pos, tag);
    }

    private void writeTagUnchecked(int fieldNumber, int wireType) {
        if (fieldNumber > 0 && fieldNumber < 8) {
            buf[pos++] = (byte) ((((fieldNumber << 4) | wireType) << 1) | 1);
        } else if (fieldNumber >= 8 && fieldNumber < 1024) {
            PrefixVarint.setShortLE(buf, pos, (short) ((((fieldNumber << 4) | wireType) << 2) | 2));
            pos += 2;
        } else {
            pos = PrefixVarint.write(buf, pos, WireType.makeTag(fieldNumber, wireType));
        }
    }

    public void writeUint(int fieldNumber, long v) {
        ensureCapacity(18);
        writeTagUnchecked(fieldNumber, WireType.WIRE_UINT);
        pos = PrefixVarint.write(buf, pos, v);
    }

    public void writeUint32(int fieldNumber, int v) {
        writeUint(fieldNumber, Integer.toUnsignedLong(v));
    }

    public void writeSint(int fieldNumber, long v) {
        ensureCapacity(18);
        writeTagUnchecked(fieldNumber, WireType.WIRE_SINT);
        pos = PrefixVarint.write(buf, pos, PrefixVarint.encodeZigZag64(v));
    }

    public void writeSint32(int fieldNumber, int v) {
        ensureCapacity(18);
        writeTagUnchecked(fieldNumber, WireType.WIRE_SINT);
        pos = PrefixVarint.write(buf, pos, PrefixVarint.encodeZigZag32(v));
    }

    public void writeBool(int fieldNumber, boolean v) {
        ensureCapacity(9);
        writeTagUnchecked(fieldNumber, v ? WireType.WIRE_BOOL_TRUE : WireType.WIRE_BOOL_FALSE);
    }

    public void writeFixed32(int fieldNumber, int v) {
        ensureCapacity(13);
        writeTagUnchecked(fieldNumber, WireType.WIRE_FIXED32);
        PrefixVarint.setIntLE(buf, pos, v);
        pos += 4;
    }

    public void writeSfixed32(int fieldNumber, int v) {
        ensureCapacity(13);
        writeTagUnchecked(fieldNumber, WireType.WIRE_SFIXED32);
        PrefixVarint.setIntLE(buf, pos, v);
        pos += 4;
    }

    public void writeFixed64(int fieldNumber, long v) {
        ensureCapacity(17);
        writeTagUnchecked(fieldNumber, WireType.WIRE_FIXED64);
        PrefixVarint.setLongLE(buf, pos, v);
        pos += 8;
    }

    public void writeSfixed64(int fieldNumber, long v) {
        ensureCapacity(17);
        writeTagUnchecked(fieldNumber, WireType.WIRE_SFIXED64);
        PrefixVarint.setLongLE(buf, pos, v);
        pos += 8;
    }

    public void writeFloat32(int fieldNumber, float v) {
        ensureCapacity(13);
        writeTagUnchecked(fieldNumber, WireType.WIRE_FLOAT32);
        PrefixVarint.setIntLE(buf, pos, Float.floatToRawIntBits(v));
        pos += 4;
    }

    public void writeFloat64(int fieldNumber, double v) {
        ensureCapacity(17);
        writeTagUnchecked(fieldNumber, WireType.WIRE_FLOAT64);
        PrefixVarint.setLongLE(buf, pos, Double.doubleToRawLongBits(v));
        pos += 8;
    }

    public void writeString1BTag(byte encodedTag, String s) {
        if (s == null || s.isEmpty()) {
            byte[] dst = ensureFree(2);
            int p = this.pos;
            dst[p] = encodedTag;
            dst[p + 1] = 1;
            this.pos = p + 2;
            return;
        }
        int n = s.length();
        if (n <= 127) {
            byte[] dst = ensureFree(2 + n);
            dst[this.pos++] = encodedTag;
            writeShortAsciiOrFallback(s, n);
            return;
        }
        byte[] dst = ensureFree(10);
        dst[this.pos++] = encodedTag;
        writeLargeStringStreamed(s, n);
    }

    public void writeString2BTag(short encodedTagLE, String s) {
        if (s == null || s.isEmpty()) {
            byte[] dst = ensureFree(3);
            int p = this.pos;
            PrefixVarint.setShortLE(dst, p, encodedTagLE);
            dst[p + 2] = 1;
            this.pos = p + 3;
            return;
        }
        int n = s.length();
        if (n <= 127) {
            byte[] dst = ensureFree(3 + n);
            int p = this.pos;
            PrefixVarint.setShortLE(dst, p, encodedTagLE);
            this.pos = p + 2;
            writeShortAsciiOrFallback(s, n);
            return;
        }
        byte[] dst = ensureFree(11);
        int p = this.pos;
        PrefixVarint.setShortLE(dst, p, encodedTagLE);
        this.pos = p + 2;
        writeLargeStringStreamed(s, n);
    }

    public void writeString(int fieldNumber, String s) {
        if (s == null || s.isEmpty()) {
            ensureCapacity(10);
            writeTagUnchecked(fieldNumber, WireType.WIRE_STRING);
            buf[pos++] = 1;
            return;
        }
        int n = s.length();
        if (n <= 127) {
            ensureCapacity(10 + n);
            writeTagUnchecked(fieldNumber, WireType.WIRE_STRING);
            writeShortAsciiOrFallback(s, n);
            return;
        }
        ensureCapacity(9);
        writeTagUnchecked(fieldNumber, WireType.WIRE_STRING);
        writeLargeStringStreamed(s, n);
    }

    public void writeBytes(int fieldNumber, byte[] data) {
        int len = data == null ? 0 : data.length;
        ensureCapacityLong((long) pos + 18L + (long) len);
        writeTagUnchecked(fieldNumber, WireType.WIRE_BYTES);
        pos = PrefixVarint.write(buf, pos, len);
        if (len > 0) {
            System.arraycopy(data, 0, buf, pos, len);
            pos += len;
        }
    }

    public void writeStringElement(String s) {
        if (s == null || s.isEmpty()) {
            ensureCapacity(1);
            buf[pos++] = 1;
            return;
        }
        int n = s.length();
        if (n <= 127) {
            ensureCapacity(1 + n);
            writeShortAsciiOrFallback(s, n);
            return;
        }
        writeLargeStringStreamed(s, n);
    }

    private void writeShortAsciiOrFallback(String s, int n) {
        int lenBytePos = this.pos;
        int nextP = writeShortAsciiInto(this.buf, lenBytePos, s, n);
        if (nextP >= 0) {
            this.pos = nextP;
            return;
        }
        writeStringNonAsciiTail(s, n, lenBytePos, 1, ~nextP);
    }

    private static int writeShortAsciiInto(byte[] dst, int lenBytePos, String s, int n) {
        int p = lenBytePos + 1;
        int i = 0;
        for (; i + 8 <= n; i += 8) {
            int c0 = s.charAt(i);
            int c1 = s.charAt(i + 1);
            int c2 = s.charAt(i + 2);
            int c3 = s.charAt(i + 3);
            int c4 = s.charAt(i + 4);
            int c5 = s.charAt(i + 5);
            int c6 = s.charAt(i + 6);
            int c7 = s.charAt(i + 7);
            if (((c0 | c1 | c2 | c3 | c4 | c5 | c6 | c7) & 0xFF80) != 0) {
                return ~i;
            }
            long word = (long) c0
                | ((long) c1 << 8)
                | ((long) c2 << 16)
                | ((long) c3 << 24)
                | ((long) c4 << 32)
                | ((long) c5 << 40)
                | ((long) c6 << 48)
                | ((long) c7 << 56);
            PrefixVarint.setLongLE(dst, p + i, word);
        }
        if (i + 4 <= n) {
            int c0 = s.charAt(i);
            int c1 = s.charAt(i + 1);
            int c2 = s.charAt(i + 2);
            int c3 = s.charAt(i + 3);
            if (((c0 | c1 | c2 | c3) & 0xFF80) != 0) {
                return ~i;
            }
            PrefixVarint.setIntLE(dst, p + i, c0 | (c1 << 8) | (c2 << 16) | (c3 << 24));
            i += 4;
        }
        for (; i < n; i++) {
            int c = s.charAt(i);
            if (c >= 0x80) {
                return ~i;
            }
            dst[p + i] = (byte) c;
        }
        dst[lenBytePos] = (byte) ((n << 1) | 1);
        return p + n;
    }

    private void writeLargeStringStreamed(String s, int n) {
        int reservedHdr = n > (int) PrefixVarint.MAX_4BYTE ? 9 : (n >= 16_384 ? 4 : 2);
        ensureCapacityLong((long) this.pos + (long) reservedHdr + (long) n);
        int lenPos = this.pos;
        int payloadStart = lenPos + reservedHdr;

        char[] scratch = this.charScratch;
        CharBuffer cb = this.charScratchBuf;
        CharsetEncoder enc = this.utf8Encoder;
        if (scratch == null) {
            scratch = new char[CHAR_SCRATCH_SIZE];
            cb = CharBuffer.wrap(scratch);
            enc = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
            this.charScratch = scratch;
            this.charScratchBuf = cb;
            this.utf8Encoder = enc;
        } else {
            enc.reset();
        }

        ByteBuffer bb = prepareByteBuf(payloadStart);
        int srcOff = 0;
        while (srcOff < n) {
            int chunk = Math.min(CHAR_SCRATCH_SIZE, n - srcOff);
            int srcEnd = srcOff + chunk;
            if (srcEnd < n && Character.isHighSurrogate(s.charAt(srcEnd - 1))) {
                if (Character.isLowSurrogate(s.charAt(srcEnd))) {
                    chunk--;
                    srcEnd--;
                } else {
                    throw new OmniBufException("omnibuf: unpaired UTF-16 surrogate in WireString");
                }
            }
            s.getChars(srcOff, srcEnd, scratch, 0);
            cb.position(0).limit(chunk);
            boolean endOfInput = (srcEnd == n);
            for (;;) {
                CoderResult cr = enc.encode(cb, bb, endOfInput);
                if (cr.isUnderflow()) {
                    if (cb.hasRemaining()) {
                        throw new OmniBufException("omnibuf: unpaired UTF-16 surrogate in WireString");
                    }
                    break;
                }
                if (cr.isOverflow()) {
                    this.pos = bb.position();
                    int remChars = (n - srcOff) - cb.position();
                    ensureCapacityLong((long) this.pos + Math.max((long) remChars * 3L, 64L));
                    bb = prepareByteBuf(this.pos);
                    continue;
                }
                throw new OmniBufException("omnibuf: unpaired UTF-16 surrogate in WireString");
            }
            srcOff = srcEnd;
        }

        for (;;) {
            CoderResult cr = enc.flush(bb);
            if (cr.isUnderflow()) {
                break;
            }
            if (cr.isOverflow()) {
                this.pos = bb.position();
                ensureCapacity(16);
                bb = prepareByteBuf(this.pos);
                continue;
            }
            throw new OmniBufException("omnibuf: unpaired UTF-16 surrogate in WireString");
        }

        int endPos = bb.position();
        int utf8Len = endPos - payloadStart;
        this.pos = endPos;
        if (reservedHdr == 2) {
            if (utf8Len <= (int) PrefixVarint.MAX_2BYTE) {
                PrefixVarint.setShortLE(this.buf, lenPos, (short) ((utf8Len << 2) | 2));
                return;
            }
            int actualHdr = PrefixVarint.encodedLength(utf8Len);
            int delta = actualHdr - 2;
            ensureCapacity(delta);
            System.arraycopy(this.buf, payloadStart, this.buf, lenPos + actualHdr, utf8Len);
            PrefixVarint.write(this.buf, lenPos, utf8Len);
            this.pos += delta;
            return;
        }
        if (reservedHdr == 4) {
            if (utf8Len <= (int) PrefixVarint.MAX_4BYTE) {
                PrefixVarint.setIntLE(this.buf, lenPos, (utf8Len << 3) | 4);
                return;
            }
            ensureCapacity(5);
            System.arraycopy(this.buf, payloadStart, this.buf, lenPos + 9, utf8Len);
            PrefixVarint.write(this.buf, lenPos, utf8Len);
            this.pos += 5;
            return;
        }
        PrefixVarint.write(this.buf, lenPos, utf8Len);
    }

    private void writeStringNonAsciiTail(
            String s, int n, int lenPos, int reservedHdr, int asciiPrefixLen) {
        long tailUtf8 = utf8LengthStrictFrom(s, asciiPrefixLen, n);
        long totalUtf8Long = (long) asciiPrefixLen + tailUtf8;
        if (totalUtf8Long > MAX_ARRAY_SIZE - 16) {
            throw new OmniBufException("omnibuf: UTF-8 string exceeds maximum array size");
        }
        int utf8Len = (int) totalUtf8Long;
        int actualHdr = PrefixVarint.encodedLength(utf8Len);
        this.pos = lenPos;
        ensureCapacityLong((long) lenPos + (long) actualHdr + (long) utf8Len);
        byte[] dst = this.buf;
        if (actualHdr != reservedHdr && asciiPrefixLen > 0) {
            System.arraycopy(dst, lenPos + reservedHdr, dst, lenPos + actualHdr, asciiPrefixLen);
        }
        PrefixVarint.write(dst, lenPos, utf8Len);
        this.pos = encodeUtf8RangeInto(s, asciiPrefixLen, n, dst, lenPos + actualHdr + asciiPrefixLen);
    }

    private static long utf8LengthStrictFrom(String s, int start, int n) {
        long len = n - start;
        for (int i = start; i < n; i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                continue;
            }
            if (c < 0x800) {
                len += 1;
            } else if (c < 0xD800 || c > 0xDFFF) {
                len += 2;
            } else if (c <= 0xDBFF && i + 1 < n) {
                char low = s.charAt(i + 1);
                if (low < 0xDC00 || low > 0xDFFF) {
                    throw new OmniBufException("omnibuf: unpaired UTF-16 surrogate in WireString");
                }
                len += 2;
                i++;
            } else {
                throw new OmniBufException("omnibuf: unpaired UTF-16 surrogate in WireString");
            }
        }
        return len;
    }

    private static int encodeUtf8RangeInto(String s, int start, int n, byte[] dst, int p) {
        for (int i = start; i < n; i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                dst[p++] = (byte) c;
            } else if (c < 0x800) {
                dst[p++] = (byte) (0xC0 | (c >>> 6));
                dst[p++] = (byte) (0x80 | (c & 0x3F));
            } else if (c < 0xD800 || c > 0xDFFF) {
                dst[p++] = (byte) (0xE0 | (c >>> 12));
                dst[p++] = (byte) (0x80 | ((c >>> 6) & 0x3F));
                dst[p++] = (byte) (0x80 | (c & 0x3F));
            } else if (c <= 0xDBFF && i + 1 < n) {
                char low = s.charAt(i + 1);
                if (low < 0xDC00 || low > 0xDFFF) {
                    throw new OmniBufException("omnibuf: unpaired UTF-16 surrogate in WireString");
                }
                int cp = 0x10000 + (((c - 0xD800) << 10) | (low - 0xDC00));
                dst[p++] = (byte) (0xF0 | (cp >>> 18));
                dst[p++] = (byte) (0x80 | ((cp >>> 12) & 0x3F));
                dst[p++] = (byte) (0x80 | ((cp >>> 6) & 0x3F));
                dst[p++] = (byte) (0x80 | (cp & 0x3F));
                i++;
            } else {
                throw new OmniBufException("omnibuf: unpaired UTF-16 surrogate in WireString");
            }
        }
        return p;
    }

    public void writeBytesElement(byte[] data) {
        int len = data == null ? 0 : data.length;
        ensureCapacityLong((long) pos + 9L + (long) len);
        pos = PrefixVarint.write(buf, pos, len);
        if (len > 0) {
            System.arraycopy(data, 0, buf, pos, len);
            pos += len;
        }
    }

    // ========================================================================
    // Stackless Single-Pass Containers (Fast Path for Codecs & Mapper)
    // ========================================================================

    public int beginSubMsgFast(int fieldNumber) {
        return beginSubMsgFast(fieldNumber, false);
    }

    public int beginSubMsgFast(int fieldNumber, boolean largeHint) {
        if (fieldNumber < 0 || fieldNumber > WireType.MAX_FIELD_NUMBER) {
            throw new OmniBufException("omnibuf: field number out of range: " + fieldNumber);
        }
        if (depth >= WireType.MAX_CONTAINER_DEPTH) {
            throw new OmniBufException(
                "omnibuf: container nesting depth exceeds MaxNestingDepth (" + WireType.MAX_CONTAINER_DEPTH + ")"
            );
        }
        ensureCapacity(13);
        if (fieldNumber == 0) {
            buf[pos++] = WireType.FIELD0_TAG_BYTE;
        } else {
            writeTagUnchecked(fieldNumber, WireType.WIRE_SUB_MSG);
        }
        int lenPos = this.pos;
        this.depth++;
        if (largeHint) {
            this.pos = lenPos + 4;
            return ~lenPos;
        }
        this.pos = lenPos + 2;
        return lenPos;
    }

    public int beginInlineSubMsgFast() {
        return beginInlineSubMsgFast(false);
    }

    public int beginInlineSubMsgFast(boolean largeHint) {
        if (depth >= WireType.MAX_CONTAINER_DEPTH) {
            throw new OmniBufException(
                "omnibuf: container nesting depth exceeds MaxNestingDepth (" + WireType.MAX_CONTAINER_DEPTH + ")"
            );
        }
        if (largeHint) {
            ensureCapacity(4);
            int lenPos = this.pos;
            this.pos = lenPos + 4;
            this.depth++;
            return ~lenPos;
        }
        ensureCapacity(2);
        int lenPos = this.pos;
        this.pos = lenPos + 2;
        this.depth++;
        return lenPos;
    }

    public int beginTypedArrayFast(int fieldNumber, int elemWireType, int count) {
        return beginTypedArrayFast(fieldNumber, elemWireType, count, false);
    }

    public int beginTypedArrayFast(
            int fieldNumber, int elemWireType, int count, boolean largeHint) {
        if (fieldNumber <= 0 || fieldNumber > WireType.MAX_FIELD_NUMBER) {
            throw new OmniBufException("omnibuf: invalid array field number: " + fieldNumber);
        }
        if (!WireType.isValidArrayElemWireType(elemWireType)) {
            throw new OmniBufException("omnibuf: invalid array element wire type: " + elemWireType);
        }
        if (count < 0) {
            throw new OmniBufException("omnibuf: negative array element count: " + count);
        }
        if (depth >= WireType.MAX_CONTAINER_DEPTH) {
            throw new OmniBufException(
                "omnibuf: container nesting depth exceeds MaxNestingDepth (" + WireType.MAX_CONTAINER_DEPTH + ")"
            );
        }
        ensureCapacity(23);
        writeTagUnchecked(fieldNumber, WireType.WIRE_ARRAY);
        int lenPos = this.pos;
        int hdrSize = largeHint ? 4 : 2;
        buf[lenPos + hdrSize] = (byte) elemWireType;
        this.pos = PrefixVarint.write(buf, lenPos + hdrSize + 1, Integer.toUnsignedLong(count));
        this.depth++;
        return largeHint ? ~lenPos : lenPos;
    }

    public int beginTypedMapFast(int fieldNumber, int keyWireType, int valWireType, int count) {
        if (fieldNumber <= 0 || fieldNumber > WireType.MAX_FIELD_NUMBER) {
            throw new OmniBufException("omnibuf: invalid map field number: " + fieldNumber);
        }
        if (!WireType.isValidMapKeyWireType(keyWireType)) {
            throw new OmniBufException("omnibuf: invalid map key wire type: " + keyWireType);
        }
        if (!WireType.isValidMapValWireType(valWireType)) {
            throw new OmniBufException("omnibuf: invalid map value wire type: " + valWireType);
        }
        if (count < 0) {
            throw new OmniBufException("omnibuf: negative map entry count: " + count);
        }
        if (depth >= WireType.MAX_CONTAINER_DEPTH) {
            throw new OmniBufException(
                "omnibuf: container nesting depth exceeds MaxNestingDepth (" + WireType.MAX_CONTAINER_DEPTH + ")"
            );
        }
        ensureCapacity(22);
        writeTagUnchecked(fieldNumber, WireType.WIRE_MAP);
        int lenPos = this.pos;
        buf[lenPos + 2] = (byte) keyWireType;
        buf[lenPos + 3] = (byte) valWireType;
        this.pos = PrefixVarint.write(buf, lenPos + 4, Integer.toUnsignedLong(count));
        this.depth++;
        return lenPos;
    }

    public void endContainerAt(int token) {
        if (depth <= 0) {
            throw new OmniBufException("omnibuf: endContainerAt with no open container");
        }
        depth--;
        if (token >= 0) {
            int payloadStart = token + 2;
            int subLen = this.pos - payloadStart;
            if (subLen <= (int) PrefixVarint.MAX_2BYTE) {
                PrefixVarint.writeOverlong2Byte(buf, token, subLen);
                return;
            }
            backpatchLargeContainer(token, payloadStart, subLen);
            return;
        }
        int lenPos = ~token;
        int payloadStart = lenPos + 4;
        int subLen = this.pos - payloadStart;
        if (subLen > (int) PrefixVarint.MAX_2BYTE && subLen <= (int) PrefixVarint.MAX_4BYTE) {
            PrefixVarint.setIntLE(buf, lenPos, (subLen << 3) | 4);
            return;
        }
        backpatchHintedContainerFallback(lenPos, payloadStart, subLen);
    }

    private void backpatchLargeContainer(int lenPos, int payloadStart, int subLen) {
        if (subLen <= (int) PrefixVarint.MAX_4BYTE) {
            ensureCapacity(2);
            System.arraycopy(buf, payloadStart, buf, lenPos + 4, subLen);
            PrefixVarint.write(buf, lenPos, subLen);
            pos += 2;
            return;
        }
        ensureCapacity(7);
        System.arraycopy(buf, payloadStart, buf, lenPos + 9, subLen);
        PrefixVarint.write(buf, lenPos, subLen);
        pos += 7;
    }

    private void backpatchHintedContainerFallback(int lenPos, int payloadStart, int subLen) {
        if (subLen <= (int) PrefixVarint.MAX_2BYTE) {
            System.arraycopy(buf, payloadStart, buf, lenPos + 2, subLen);
            PrefixVarint.writeOverlong2Byte(buf, lenPos, subLen);
            pos -= 2;
            return;
        }
        ensureCapacity(5);
        System.arraycopy(buf, payloadStart, buf, lenPos + 9, subLen);
        PrefixVarint.write(buf, lenPos, subLen);
        pos += 5;
    }

    // ========================================================================
    // Stack-Based Single-Pass Containers (General Public API)
    // ========================================================================

    public void beginSubMsg(int fieldNumber) {
        int lenPos = beginSubMsgFast(fieldNumber);
        pushContainer(lenPos, 0);
    }

    public void endSubMsg() {
        if (stackDepth <= 0) {
            throw new OmniBufException("omnibuf: endSubMsg on empty container stack");
        }
        stackDepth--;
        endContainerAt(lenStack[stackDepth]);
    }

    public void beginSubMsgArr(int fieldNumber) {
        if (fieldNumber <= 0 || fieldNumber > WireType.MAX_FIELD_NUMBER) {
            throw new OmniBufException("omnibuf: invalid array field number: " + fieldNumber);
        }
        if (depth >= WireType.MAX_CONTAINER_DEPTH) {
            throw new OmniBufException(
                "omnibuf: container nesting depth exceeds MaxNestingDepth (" + WireType.MAX_CONTAINER_DEPTH + ")"
            );
        }
        ensureCapacity(13);
        writeTagUnchecked(fieldNumber, WireType.WIRE_ARRAY);
        int lenPos = this.pos;
        this.depth++;
        pushContainer(lenPos, 0);
        buf[lenPos + 2] = (byte) WireType.WIRE_SUB_MSG;
        buf[lenPos + 3] = 0; // Reserved 1B count slot
        this.pos = lenPos + 4;
    }

    public void beginSubMsgElem() {
        if (stackDepth <= 0) {
            throw new OmniBufException("omnibuf: beginSubMsgElem outside container");
        }
        int lenPos = beginInlineSubMsgFast();
        countStack[stackDepth - 1]++;
        pushContainer(lenPos, 0);
    }

    public void endSubMsgElem() {
        if (stackDepth <= 0) {
            throw new OmniBufException("omnibuf: endSubMsgElem on empty container stack");
        }
        stackDepth--;
        endContainerAt(lenStack[stackDepth]);
    }

    public void endSubMsgArr() {
        if (stackDepth <= 0) {
            throw new OmniBufException("omnibuf: endSubMsgArr on empty container stack");
        }
        stackDepth--;
        int lenPos = lenStack[stackDepth];
        int count = countStack[stackDepth];
        patchReserved1ByteVarint(lenPos + 3, count);
        endContainerAt(lenPos);
    }

    public void beginTypedArray(int fieldNumber, int elemWireType, int count) {
        int lenPos = beginTypedArrayFast(fieldNumber, elemWireType, count);
        pushContainer(lenPos, count);
    }

    public void endTypedArray() {
        if (stackDepth <= 0) {
            throw new OmniBufException("omnibuf: endTypedArray on empty container stack");
        }
        stackDepth--;
        endContainerAt(lenStack[stackDepth]);
    }

    public void beginTypedMap(int fieldNumber, int keyWireType, int valWireType, int count) {
        int lenPos = beginTypedMapFast(fieldNumber, keyWireType, valWireType, count);
        pushContainer(lenPos, count);
    }

    public void endTypedMap() {
        if (stackDepth <= 0) {
            throw new OmniBufException("omnibuf: endTypedMap on empty container stack");
        }
        stackDepth--;
        endContainerAt(lenStack[stackDepth]);
    }

    /**
     * Begins an inline sub-message element inside an open container (such as a WireMap value
     * with {@code valWireType == WIRE_SUB_MSG}) without writing a field tag.
     */
    public void beginInlineSubMsg() {
        int lenPos = beginInlineSubMsgFast();
        pushContainer(lenPos, 0);
    }

    public void endInlineSubMsg() {
        if (stackDepth <= 0) {
            throw new OmniBufException("omnibuf: endInlineSubMsg on empty container stack");
        }
        stackDepth--;
        endContainerAt(lenStack[stackDepth]);
    }

    private void patchReserved1ByteVarint(int slotPos, long v) {
        if (Long.compareUnsigned(v, PrefixVarint.MAX_1BYTE) <= 0) {
            buf[slotPos] = (byte) ((v << 1) | 1L);
            return;
        }
        int needed = PrefixVarint.encodedLength(v);
        int delta = needed - 1;
        ensureCapacity(delta);
        System.arraycopy(buf, slotPos + 1, buf, slotPos + needed, pos - (slotPos + 1));
        PrefixVarint.write(buf, slotPos, v);
        pos += delta;
    }

    // ========================================================================
    // High-Level Typed Array & Map Helpers
    // ========================================================================

    private static boolean isLargeStringListHint(List<String> values, int n, String first) {
        if (n > 16_380) {
            return true;
        }
        int l0 = first == null ? 0 : first.length();
        if (l0 > 16_383) {
            return true;
        }
        String last = values.get(n - 1);
        int lLast = last == null ? 0 : last.length();
        if (lLast > 16_383) {
            return true;
        }
        String mid = values.get(n >>> 1);
        int lMid = mid == null ? 0 : mid.length();
        if (lMid > 16_383) {
            return true;
        }
        return (long) (l0 + lMid + lLast + 3) * (long) n > 49_149L;
    }

    private int writeStringElementSlow(int currentPos, String s) {
        this.pos = currentPos;
        writeStringElement(s);
        return this.pos;
    }

    private int writeStringNonAsciiFrom(int lenBytePos, String s, int slen, int asciiPrefixLen) {
        this.pos = lenBytePos;
        writeStringNonAsciiTail(s, slen, lenBytePos, 1, asciiPrefixLen);
        return this.pos;
    }

    public void writeStringList(int fieldNumber, List<String> values) {
        int n = values == null ? 0 : values.size();
        if (n == 0) {
            int lenPos = beginTypedArrayFast(fieldNumber, WireType.WIRE_STRING, 0, false);
            endContainerAt(lenPos);
            return;
        }
        String s = values.get(0);
        boolean largeHint = n > 64
            ? isLargeStringListHint(values, n, s)
            : (s != null && s.length() > 16_383);
        int lenPos = beginTypedArrayFast(fieldNumber, WireType.WIRE_STRING, n, largeHint);
        byte[] dst = this.buf;
        int p = this.pos;
        int cap = dst.length;
        for (int i = 0; ; ) {
            int slen;
            if (s != null && (slen = s.length()) > 0 && slen <= 127 && cap - p >= 1 + slen) {
                int nextP = writeShortAsciiInto(dst, p, s, slen);
                if (nextP >= 0) {
                    p = nextP;
                } else {
                    p = writeStringNonAsciiFrom(p, s, slen, ~nextP);
                    dst = this.buf;
                    cap = dst.length;
                }
            } else {
                p = writeStringElementSlow(p, s);
                dst = this.buf;
                cap = dst.length;
            }
            if (++i >= n) {
                break;
            }
            s = values.get(i);
        }
        this.pos = p;
        endContainerAt(lenPos);
    }

    public void writeBytesList(int fieldNumber, List<byte[]> values) {
        int n = values == null ? 0 : values.size();
        if (n == 0) {
            int lenPos = beginTypedArrayFast(fieldNumber, WireType.WIRE_BYTES, 0, false);
            endContainerAt(lenPos);
            return;
        }
        long exact = 0L;
        for (int i = 0; i < n; i++) {
            byte[] b = values.get(i);
            int blen = b == null ? 0 : b.length;
            exact += (long) PrefixVarint.encodedLength(Integer.toUnsignedLong(blen)) + (long) blen;
        }
        int hdrExtra = 1 + PrefixVarint.encodedLength(Integer.toUnsignedLong(n));
        boolean largeHint = (exact + hdrExtra) > PrefixVarint.MAX_2BYTE;
        int lenPos = beginTypedArrayFast(fieldNumber, WireType.WIRE_BYTES, n, largeHint);
        ensureCapacityLong((long) this.pos + exact);
        byte[] dst = this.buf;
        int p = this.pos;
        for (int i = 0; i < n; i++) {
            byte[] b = values.get(i);
            int blen = b == null ? 0 : b.length;
            p = PrefixVarint.write(dst, p, Integer.toUnsignedLong(blen));
            if (blen > 0) {
                System.arraycopy(b, 0, dst, p, blen);
                p += blen;
            }
        }
        this.pos = p;
        endContainerAt(lenPos);
    }

    public void writeUint32List(int fieldNumber, List<Integer> values) {
        int n = values == null ? 0 : values.size();
        int lenPos = beginTypedArrayFast(fieldNumber, WireType.WIRE_UINT, n, n > 16_380);
        if (n > 0) {
            ensureCapacityLong((long) pos + (long) n * 9L);
            byte[] dst = this.buf;
            int p = this.pos;
            if (values instanceof IntArrayList il) {
                int[] raw = il.rawArray();
                for (int i = 0; i < n; i++) {
                    p = PrefixVarint.write(dst, p, Integer.toUnsignedLong(raw[i]));
                }
            } else {
                for (int i = 0; i < n; i++) {
                    Integer elem = values.get(i);
                    p = PrefixVarint.write(dst, p, Integer.toUnsignedLong(elem == null ? 0 : elem));
                }
            }
            this.pos = p;
        }
        endContainerAt(lenPos);
    }

    public void writeUint32Array(int fieldNumber, int[] values) {
        int n = values == null ? 0 : values.length;
        int lenPos = beginTypedArrayFast(fieldNumber, WireType.WIRE_UINT, n, n > 16_380);
        if (n > 0) {
            ensureCapacityLong((long) pos + (long) n * 9L);
            byte[] dst = this.buf;
            int p = this.pos;
            for (int i = 0; i < n; i++) {
                p = PrefixVarint.write(dst, p, Integer.toUnsignedLong(values[i]));
            }
            this.pos = p;
        }
        endContainerAt(lenPos);
    }

    public void writeUint64List(int fieldNumber, List<Long> values) {
        int n = values == null ? 0 : values.size();
        int lenPos = beginTypedArrayFast(fieldNumber, WireType.WIRE_UINT, n, n > 16_380);
        if (n > 0) {
            ensureCapacityLong((long) pos + (long) n * 9L);
            byte[] dst = this.buf;
            int p = this.pos;
            if (values instanceof LongArrayList ll) {
                long[] raw = ll.rawArray();
                for (int i = 0; i < n; i++) {
                    p = PrefixVarint.write(dst, p, raw[i]);
                }
            } else {
                for (int i = 0; i < n; i++) {
                    Long elem = values.get(i);
                    p = PrefixVarint.write(dst, p, elem == null ? 0L : elem);
                }
            }
            this.pos = p;
        }
        endContainerAt(lenPos);
    }

    public void writeSint32List(int fieldNumber, List<Integer> values) {
        int n = values == null ? 0 : values.size();
        int lenPos = beginTypedArrayFast(fieldNumber, WireType.WIRE_SINT, n, n > 16_380);
        if (n > 0) {
            ensureCapacityLong((long) pos + (long) n * 9L);
            byte[] dst = this.buf;
            int p = this.pos;
            if (values instanceof IntArrayList il) {
                int[] raw = il.rawArray();
                for (int i = 0; i < n; i++) {
                    p = PrefixVarint.write(dst, p, PrefixVarint.encodeZigZag32(raw[i]));
                }
            } else {
                for (int i = 0; i < n; i++) {
                    Integer elem = values.get(i);
                    p = PrefixVarint.write(dst, p, PrefixVarint.encodeZigZag32(elem == null ? 0 : elem));
                }
            }
            this.pos = p;
        }
        endContainerAt(lenPos);
    }

    public void writeSint64List(int fieldNumber, List<Long> values) {
        int n = values == null ? 0 : values.size();
        int lenPos = beginTypedArrayFast(fieldNumber, WireType.WIRE_SINT, n, n > 16_380);
        if (n > 0) {
            ensureCapacityLong((long) pos + (long) n * 9L);
            byte[] dst = this.buf;
            int p = this.pos;
            if (values instanceof LongArrayList ll) {
                long[] raw = ll.rawArray();
                for (int i = 0; i < n; i++) {
                    p = PrefixVarint.write(dst, p, PrefixVarint.encodeZigZag64(raw[i]));
                }
            } else {
                for (int i = 0; i < n; i++) {
                    Long elem = values.get(i);
                    p = PrefixVarint.write(dst, p, PrefixVarint.encodeZigZag64(elem == null ? 0L : elem));
                }
            }
            this.pos = p;
        }
        endContainerAt(lenPos);
    }

    public void writeBoolList(int fieldNumber, List<Boolean> values) {
        int n = values == null ? 0 : values.size();
        int lenPos = beginTypedArrayFast(fieldNumber, WireType.WIRE_BOOL_FALSE, n, n > 16_380);
        if (n > 0) {
            ensureCapacity(n);
            byte[] dst = this.buf;
            int p = this.pos;
            for (int i = 0; i < n; i++) {
                Boolean elem = values.get(i);
                dst[p + i] = (byte) (Boolean.TRUE.equals(elem) ? 1 : 0);
            }
            this.pos = p + n;
        }
        endContainerAt(lenPos);
    }

    public void writeFixed32List(int fieldNumber, int elemWireType, List<Integer> values) {
        int n = values == null ? 0 : values.size();
        int lenPos = beginTypedArrayFast(fieldNumber, elemWireType, n, n >= 4_096);
        if (n > 0) {
            ensureCapacityLong((long) pos + (long) n * 4L);
            byte[] dst = this.buf;
            int p = this.pos;
            if (values instanceof IntArrayList il) {
                int[] raw = il.rawArray();
                for (int i = 0; i < n; i++) {
                    PrefixVarint.setIntLE(dst, p, raw[i]);
                    p += 4;
                }
            } else {
                for (int i = 0; i < n; i++) {
                    Integer elem = values.get(i);
                    PrefixVarint.setIntLE(dst, p, elem == null ? 0 : elem);
                    p += 4;
                }
            }
            this.pos = p;
        }
        endContainerAt(lenPos);
    }

    public void writeFixed64List(int fieldNumber, int elemWireType, List<Long> values) {
        int n = values == null ? 0 : values.size();
        int lenPos = beginTypedArrayFast(fieldNumber, elemWireType, n, n >= 2_048);
        if (n > 0) {
            ensureCapacityLong((long) pos + (long) n * 8L);
            byte[] dst = this.buf;
            int p = this.pos;
            if (values instanceof LongArrayList ll) {
                long[] raw = ll.rawArray();
                for (int i = 0; i < n; i++) {
                    PrefixVarint.setLongLE(dst, p, raw[i]);
                    p += 8;
                }
            } else {
                for (int i = 0; i < n; i++) {
                    Long elem = values.get(i);
                    PrefixVarint.setLongLE(dst, p, elem == null ? 0L : elem);
                    p += 8;
                }
            }
            this.pos = p;
        }
        endContainerAt(lenPos);
    }

    public void writeFloat32List(int fieldNumber, List<Float> values) {
        int n = values == null ? 0 : values.size();
        int lenPos = beginTypedArrayFast(fieldNumber, WireType.WIRE_FLOAT32, n, n >= 4_096);
        if (n > 0) {
            ensureCapacityLong((long) pos + (long) n * 4L);
            byte[] dst = this.buf;
            int p = this.pos;
            if (values instanceof FloatArrayList fl) {
                float[] raw = fl.rawArray();
                for (int i = 0; i < n; i++) {
                    PrefixVarint.setIntLE(dst, p + (i << 2), Float.floatToRawIntBits(raw[i]));
                }
            } else {
                for (int i = 0; i < n; i++) {
                    Float elem = values.get(i);
                    PrefixVarint.setIntLE(dst, p + (i << 2), Float.floatToRawIntBits(elem == null ? 0.0f : elem));
                }
            }
            this.pos = p + (n << 2);
        }
        endContainerAt(lenPos);
    }

    public void writeFloat64List(int fieldNumber, List<Double> values) {
        int n = values == null ? 0 : values.size();
        int lenPos = beginTypedArrayFast(fieldNumber, WireType.WIRE_FLOAT64, n, n >= 2_048);
        if (n > 0) {
            ensureCapacityLong((long) pos + (long) n * 8L);
            byte[] dst = this.buf;
            int p = this.pos;
            if (values instanceof DoubleArrayList dl) {
                double[] raw = dl.rawArray();
                for (int i = 0; i < n; i++) {
                    PrefixVarint.setLongLE(dst, p + (i << 3), Double.doubleToRawLongBits(raw[i]));
                }
            } else {
                for (int i = 0; i < n; i++) {
                    Double elem = values.get(i);
                    PrefixVarint.setLongLE(dst, p + (i << 3), Double.doubleToRawLongBits(elem == null ? 0.0d : elem));
                }
            }
            this.pos = p + (n << 3);
        }
        endContainerAt(lenPos);
    }

    public void writeMapStringUint64(int fieldNumber, Map<String, Long> map, boolean deterministic) {
        int n = map == null ? 0 : map.size();
        if (n > 0) {
            for (String key : map.keySet()) {
                if (key == null) {
                    throw new OmniBufException("omnibuf: null WireMap key is not allowed");
                }
            }
        }
        int lenPos = beginTypedMapFast(fieldNumber, WireType.WIRE_STRING, WireType.WIRE_UINT, n);
        if (n > 0) {
            Map<String, Long> iter;
            if (deterministic && n > 1) {
                TreeMap<String, Long> sorted = new TreeMap<>(Utf8Validator.UTF8_ORDER);
                sorted.putAll(map);
                iter = sorted;
            } else {
                iter = map;
            }
            for (Map.Entry<String, Long> entry : iter.entrySet()) {
                writeStringElement(entry.getKey());
                Long val = entry.getValue();
                writeVarint(val == null ? 0L : val);
            }
        }
        endContainerAt(lenPos);
    }

    /**
     * Returns {@code map.entrySet()} sorted in canonical OmniBuf deterministic key order when
     * {@code deterministic && map.size() > 1}, or {@code map.entrySet()} directly otherwise.
     */
    public static <K, V> Iterable<Map.Entry<K, V>> sortMapEntries(
            Map<K, V> map, int keyWireType, boolean deterministic) {
        for (K key : map.keySet()) {
            if (key == null) {
                throw new OmniBufException("omnibuf: null WireMap key is not allowed");
            }
        }
        if (!deterministic || map.size() <= 1) {
            return map.entrySet();
        }
        List<Map.Entry<K, V>> sorted = new ArrayList<>(map.entrySet());
        Comparator<Object> cmp = canonicalMapKeyComparator(keyWireType);
        sorted.sort((a, b) -> cmp.compare(a.getKey(), b.getKey()));
        return sorted;
    }

    public static Comparator<Object> canonicalMapKeyComparator(int keyWireType) {
        return switch (keyWireType) {
            case WireType.WIRE_STRING ->
                (a, b) -> Utf8Validator.compareUtf8(
                    a == null ? "" : a.toString(),
                    b == null ? "" : b.toString()
                );
            case WireType.WIRE_BOOL_FALSE ->
                (a, b) -> Boolean.compare(Boolean.TRUE.equals(a), Boolean.TRUE.equals(b));
            case WireType.WIRE_UINT, WireType.WIRE_FIXED64 ->
                (a, b) -> {
                    long la = a instanceof Integer ia
                        ? Integer.toUnsignedLong(ia)
                        : (a instanceof Number na ? na.longValue() : 0L);
                    long lb = b instanceof Integer ib
                        ? Integer.toUnsignedLong(ib)
                        : (b instanceof Number nb ? nb.longValue() : 0L);
                    return Long.compareUnsigned(la, lb);
                };
            case WireType.WIRE_FIXED32 ->
                (a, b) -> Integer.compareUnsigned(
                    a instanceof Number na ? na.intValue() : 0,
                    b instanceof Number nb ? nb.intValue() : 0
                );
            case WireType.WIRE_SINT, WireType.WIRE_SFIXED64 ->
                (a, b) -> {
                    long la = a instanceof Enum<?> ea
                        ? ea.ordinal()
                        : (a instanceof Number na ? na.longValue() : 0L);
                    long lb = b instanceof Enum<?> eb
                        ? eb.ordinal()
                        : (b instanceof Number nb ? nb.longValue() : 0L);
                    return Long.compare(la, lb);
                };
            case WireType.WIRE_SFIXED32 ->
                (a, b) -> Integer.compare(
                    a instanceof Number na ? na.intValue() : 0,
                    b instanceof Number nb ? nb.intValue() : 0
                );
            default -> (a, b) -> 0;
        };
    }
}
