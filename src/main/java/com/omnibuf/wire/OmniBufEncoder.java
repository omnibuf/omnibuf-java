package com.omnibuf.wire;

import com.omnibuf.OmniBufException;
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

    private byte[] buf;
    private int pos;

    private int[] lenStack;
    private int[] countStack;
    private int stackDepth;
    private int depth;

    public OmniBufEncoder() {
        this(256);
    }

    public OmniBufEncoder(int initialCapacity) {
        if (initialCapacity < 0 || initialCapacity > MAX_ARRAY_SIZE) {
            throw new OmniBufException("omnibuf: invalid initial encoder capacity: " + initialCapacity);
        }
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
        }
    }

    public int size() {
        return pos;
    }

    public int capacity() {
        return buf.length;
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
        long needed = (long) pos + (long) additional;
        if (needed > buf.length) {
            growBuffer(needed);
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
            byte[] dst = this.buf;
            int lenBytePos = this.pos;
            int p = lenBytePos + 1;
            for (int i = 0; i < n; i++) {
                char c = s.charAt(i);
                if (c >= 0x80) {
                    this.pos = lenBytePos;
                    writeStringNonAscii(s, n);
                    return;
                }
                dst[p + i] = (byte) c;
            }
            dst[lenBytePos] = (byte) ((n << 1) | 1);
            this.pos = p + n;
            return;
        }
        ensureCapacity(9);
        writeTagUnchecked(fieldNumber, WireType.WIRE_STRING);
        writeStringNonAscii(s, n);
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
            byte[] dst = this.buf;
            int lenBytePos = this.pos;
            int p = lenBytePos + 1;
            for (int i = 0; i < n; i++) {
                char c = s.charAt(i);
                if (c >= 0x80) {
                    writeStringNonAscii(s, n);
                    return;
                }
                dst[p + i] = (byte) c;
            }
            dst[lenBytePos] = (byte) ((n << 1) | 1);
            this.pos = p + n;
            return;
        }
        writeStringNonAscii(s, n);
    }

    private void writeStringNonAscii(String s, int n) {
        if (n <= 42) {
            // Maximum UTF-8 expansion 3 * n <= 126 fits strictly in a 1-byte PrefixVarint
            ensureCapacity(1 + n * 3);
            int lenBytePos = this.pos;
            int endPos = encodeUtf8Into(s, n, this.buf, lenBytePos + 1);
            int utf8Len = endPos - (lenBytePos + 1);
            this.buf[lenBytePos] = (byte) ((utf8Len << 1) | 1);
            this.pos = endPos;
            return;
        }
        if (n >= 256) {
            byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
            if (mightContainQuestionMark(utf8)) {
                utf8LengthStrict(s, n);
            }
            int utf8Len = utf8.length;
            ensureCapacityLong((long) this.pos + 9L + (long) utf8Len);
            int payloadPos = PrefixVarint.write(this.buf, this.pos, utf8Len);
            System.arraycopy(utf8, 0, this.buf, payloadPos, utf8Len);
            this.pos = payloadPos + utf8Len;
            return;
        }
        int utf8Len = utf8LengthStrict(s, n);
        ensureCapacityLong((long) this.pos + 9L + (long) utf8Len);
        int payloadPos = PrefixVarint.write(this.buf, this.pos, utf8Len);
        this.pos = encodeUtf8Into(s, n, this.buf, payloadPos);
    }

    private static boolean mightContainQuestionMark(byte[] utf8) {
        int i = 0;
        int limit = utf8.length - 8;
        for (; i <= limit; i += 8) {
            long x = PrefixVarint.getLongLE(utf8, i) ^ 0x3F3F_3F3F_3F3F_3F3FL;
            if (((x - 0x0101_0101_0101_0101L) & ~x & 0x8080_8080_8080_8080L) != 0L) {
                return true;
            }
        }
        for (; i < utf8.length; i++) {
            if (utf8[i] == (byte) '?') {
                return true;
            }
        }
        return false;
    }

    private static int utf8LengthStrict(String s, int n) {
        long len = n;
        for (int i = 0; i < n; i++) {
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
                len += 2; // 2 UTF-16 chars (already counted as 2) -> 4 UTF-8 bytes
                i++;
            } else {
                throw new OmniBufException("omnibuf: unpaired UTF-16 surrogate in WireString");
            }
        }
        if (len > MAX_ARRAY_SIZE - 16) {
            throw new OmniBufException("omnibuf: UTF-8 string exceeds maximum array size");
        }
        return (int) len;
    }

    private static int encodeUtf8Into(String s, int n, byte[] dst, int p) {
        for (int i = 0; i < n; i++) {
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
        if (fieldNumber < 0 || fieldNumber > WireType.MAX_FIELD_NUMBER) {
            throw new OmniBufException("omnibuf: field number out of range: " + fieldNumber);
        }
        if (depth >= WireType.MAX_CONTAINER_DEPTH) {
            throw new OmniBufException(
                "omnibuf: container nesting depth exceeds MaxNestingDepth (" + WireType.MAX_CONTAINER_DEPTH + ")"
            );
        }
        ensureCapacity(11);
        if (fieldNumber == 0) {
            buf[pos++] = WireType.FIELD0_TAG_BYTE;
        } else {
            writeTagUnchecked(fieldNumber, WireType.WIRE_SUB_MSG);
        }
        int lenPos = this.pos;
        this.pos = lenPos + 2;
        this.depth++;
        return lenPos;
    }

    public int beginInlineSubMsgFast() {
        if (depth >= WireType.MAX_CONTAINER_DEPTH) {
            throw new OmniBufException(
                "omnibuf: container nesting depth exceeds MaxNestingDepth (" + WireType.MAX_CONTAINER_DEPTH + ")"
            );
        }
        ensureCapacity(2);
        int lenPos = this.pos;
        this.pos = lenPos + 2;
        this.depth++;
        return lenPos;
    }

    public int beginTypedArrayFast(int fieldNumber, int elemWireType, int count) {
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
        ensureCapacity(21);
        writeTagUnchecked(fieldNumber, WireType.WIRE_ARRAY);
        int lenPos = this.pos;
        buf[lenPos + 2] = (byte) elemWireType;
        this.pos = PrefixVarint.write(buf, lenPos + 3, Integer.toUnsignedLong(count));
        this.depth++;
        return lenPos;
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

    public void endContainerAt(int lenPos) {
        if (depth <= 0) {
            throw new OmniBufException("omnibuf: endContainerAt with no open container");
        }
        depth--;
        int payloadStart = lenPos + 2;
        int subLen = this.pos - payloadStart;
        if (subLen <= (int) PrefixVarint.MAX_2BYTE) {
            PrefixVarint.writeOverlong2Byte(buf, lenPos, subLen);
            return;
        }
        backpatchLargeContainer(lenPos, payloadStart, subLen);
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

    public void writeStringList(int fieldNumber, List<String> values) {
        int n = values == null ? 0 : values.size();
        int lenPos = beginTypedArrayFast(fieldNumber, WireType.WIRE_STRING, n);
        for (int i = 0; i < n; i++) {
            writeStringElement(values.get(i));
        }
        endContainerAt(lenPos);
    }

    public void writeBytesList(int fieldNumber, List<byte[]> values) {
        int n = values == null ? 0 : values.size();
        int lenPos = beginTypedArrayFast(fieldNumber, WireType.WIRE_BYTES, n);
        for (int i = 0; i < n; i++) {
            writeBytesElement(values.get(i));
        }
        endContainerAt(lenPos);
    }

    public void writeUint32List(int fieldNumber, List<Integer> values) {
        int n = values == null ? 0 : values.size();
        int lenPos = beginTypedArrayFast(fieldNumber, WireType.WIRE_UINT, n);
        if (n > 0) {
            ensureCapacityLong((long) pos + (long) n * 9L);
            byte[] dst = this.buf;
            int p = this.pos;
            for (int i = 0; i < n; i++) {
                Integer elem = values.get(i);
                p = PrefixVarint.write(dst, p, Integer.toUnsignedLong(elem == null ? 0 : elem));
            }
            this.pos = p;
        }
        endContainerAt(lenPos);
    }

    public void writeUint32Array(int fieldNumber, int[] values) {
        int n = values == null ? 0 : values.length;
        int lenPos = beginTypedArrayFast(fieldNumber, WireType.WIRE_UINT, n);
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
        int lenPos = beginTypedArrayFast(fieldNumber, WireType.WIRE_UINT, n);
        if (n > 0) {
            ensureCapacityLong((long) pos + (long) n * 9L);
            byte[] dst = this.buf;
            int p = this.pos;
            for (int i = 0; i < n; i++) {
                Long elem = values.get(i);
                p = PrefixVarint.write(dst, p, elem == null ? 0L : elem);
            }
            this.pos = p;
        }
        endContainerAt(lenPos);
    }

    public void writeSint32List(int fieldNumber, List<Integer> values) {
        int n = values == null ? 0 : values.size();
        int lenPos = beginTypedArrayFast(fieldNumber, WireType.WIRE_SINT, n);
        if (n > 0) {
            ensureCapacityLong((long) pos + (long) n * 9L);
            byte[] dst = this.buf;
            int p = this.pos;
            for (int i = 0; i < n; i++) {
                Integer elem = values.get(i);
                p = PrefixVarint.write(dst, p, PrefixVarint.encodeZigZag32(elem == null ? 0 : elem));
            }
            this.pos = p;
        }
        endContainerAt(lenPos);
    }

    public void writeSint64List(int fieldNumber, List<Long> values) {
        int n = values == null ? 0 : values.size();
        int lenPos = beginTypedArrayFast(fieldNumber, WireType.WIRE_SINT, n);
        if (n > 0) {
            ensureCapacityLong((long) pos + (long) n * 9L);
            byte[] dst = this.buf;
            int p = this.pos;
            for (int i = 0; i < n; i++) {
                Long elem = values.get(i);
                p = PrefixVarint.write(dst, p, PrefixVarint.encodeZigZag64(elem == null ? 0L : elem));
            }
            this.pos = p;
        }
        endContainerAt(lenPos);
    }

    public void writeBoolList(int fieldNumber, List<Boolean> values) {
        int n = values == null ? 0 : values.size();
        int lenPos = beginTypedArrayFast(fieldNumber, WireType.WIRE_BOOL_FALSE, n);
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
        int lenPos = beginTypedArrayFast(fieldNumber, elemWireType, n);
        if (n > 0) {
            ensureCapacityLong((long) pos + (long) n * 4L);
            byte[] dst = this.buf;
            int p = this.pos;
            for (int i = 0; i < n; i++) {
                Integer elem = values.get(i);
                PrefixVarint.setIntLE(dst, p, elem == null ? 0 : elem);
                p += 4;
            }
            this.pos = p;
        }
        endContainerAt(lenPos);
    }

    public void writeFixed64List(int fieldNumber, int elemWireType, List<Long> values) {
        int n = values == null ? 0 : values.size();
        int lenPos = beginTypedArrayFast(fieldNumber, elemWireType, n);
        if (n > 0) {
            ensureCapacityLong((long) pos + (long) n * 8L);
            byte[] dst = this.buf;
            int p = this.pos;
            for (int i = 0; i < n; i++) {
                Long elem = values.get(i);
                PrefixVarint.setLongLE(dst, p, elem == null ? 0L : elem);
                p += 8;
            }
            this.pos = p;
        }
        endContainerAt(lenPos);
    }

    public void writeFloat32List(int fieldNumber, List<Float> values) {
        int n = values == null ? 0 : values.size();
        int lenPos = beginTypedArrayFast(fieldNumber, WireType.WIRE_FLOAT32, n);
        if (n > 0) {
            ensureCapacityLong((long) pos + (long) n * 4L);
            byte[] dst = this.buf;
            int p = this.pos;
            for (int i = 0; i < n; i++) {
                Float elem = values.get(i);
                PrefixVarint.setIntLE(dst, p, Float.floatToRawIntBits(elem == null ? 0.0f : elem));
                p += 4;
            }
            this.pos = p;
        }
        endContainerAt(lenPos);
    }

    public void writeFloat64List(int fieldNumber, List<Double> values) {
        int n = values == null ? 0 : values.size();
        int lenPos = beginTypedArrayFast(fieldNumber, WireType.WIRE_FLOAT64, n);
        if (n > 0) {
            ensureCapacityLong((long) pos + (long) n * 8L);
            byte[] dst = this.buf;
            int p = this.pos;
            for (int i = 0; i < n; i++) {
                Double elem = values.get(i);
                PrefixVarint.setLongLE(dst, p, Double.doubleToRawLongBits(elem == null ? 0.0d : elem));
                p += 8;
            }
            this.pos = p;
        }
        endContainerAt(lenPos);
    }

    public void writeMapStringUint64(int fieldNumber, Map<String, Long> map, boolean deterministic) {
        int n = map == null ? 0 : map.size();
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
