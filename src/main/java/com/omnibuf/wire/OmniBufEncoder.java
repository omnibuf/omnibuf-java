package com.omnibuf.wire;

import com.omnibuf.OmniBufException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Single-pass binary encoder for OmniBuf packets with dynamic container stack growth.
 */
public final class OmniBufEncoder {

    private byte[] buf;
    private int pos;

    private int[] lenStack;
    private int[] countStack;
    private int depth;

    public OmniBufEncoder() {
        this(256);
    }

    public OmniBufEncoder(int initialCapacity) {
        this.buf = new byte[Math.max(32, initialCapacity)];
        this.lenStack = new int[16];
        this.countStack = new int[16];
        this.pos = 0;
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
        this.lenStack = new int[16];
        this.countStack = new int[16];
        this.pos = initialPos;
        this.depth = 0;
    }

    /**
     * If this encoder shares the same backing array as {@code raw} and future writes could
     * overlap {@code raw[rawOffset..rawOffset+rawLength]}, detaches onto a fresh array while
     * preserving {@code buf[0..pos]}.
     */
    public void detachIfAliased(byte[] raw, int rawOffset, int rawLength) {
        if (this.buf == raw && rawLength > 0) {
            int dstStart = 0;
            int dstEnd = this.buf.length;
            int rawStart = rawOffset;
            int rawEnd = rawOffset + rawLength;
            if (dstStart < rawEnd && rawStart < dstEnd) {
                this.buf = Arrays.copyOf(this.buf, Math.max(this.pos + rawLength + 64, 64));
            }
        }
    }

    public void reset() {
        this.pos = 0;
        this.depth = 0;
    }

    public int size() {
        return pos;
    }

    public byte[] toByteArray() {
        if (depth != 0) {
            throw new OmniBufException("omnibuf: unclosed container on encoder stack (depth=" + depth + ")");
        }
        return Arrays.copyOf(buf, pos);
    }

    private void ensureCapacity(int additional) {
        int needed = pos + additional;
        if (needed < 0 || needed > buf.length) {
            growBuffer(needed);
        }
    }

    private void growBuffer(int needed) {
        if (needed < 0) {
            throw new OmniBufException("omnibuf: packet exceeds maximum array size");
        }
        int newCap = buf.length;
        while (newCap < needed) {
            newCap = newCap >= (Integer.MAX_VALUE >>> 1) ? Integer.MAX_VALUE - 8 : (newCap << 1);
            if (newCap < needed && newCap == Integer.MAX_VALUE - 8) {
                newCap = needed;
                break;
            }
        }
        buf = Arrays.copyOf(buf, newCap);
    }

    private void pushContainer(int lenPos, int initialCount) {
        if (depth == lenStack.length) {
            int nextCap = lenStack.length << 1;
            lenStack = Arrays.copyOf(lenStack, nextCap);
            countStack = Arrays.copyOf(countStack, nextCap);
        }
        lenStack[depth] = lenPos;
        countStack[depth] = initialCount;
        depth++;
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

    public void writeVarint(long v) {
        ensureCapacity(9);
        pos = PrefixVarint.write(buf, pos, v);
    }

    public void writeTag(int fieldNumber, int wireType) {
        if (fieldNumber > 0 && fieldNumber < 8) {
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
        } else if (fieldNumber > 0 && fieldNumber < 1024) {
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
        int n = s == null ? 0 : s.length();
        ensureCapacity(10 + n);
        writeTagUnchecked(fieldNumber, WireType.WIRE_STRING);
        writeStringElement(s);
    }

    public void writeBytes(int fieldNumber, byte[] data) {
        int len = data == null ? 0 : data.length;
        ensureCapacity(18 + len);
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
            buf[pos++] = 1; // PrefixVarint(0) = 0x01
            return;
        }
        int n = s.length();
        // Fast single-pass ASCII path avoiding second scan and intermediate byte[] allocation.
        if (n <= 127) {
            ensureCapacity(1 + n);
            int p = pos + 1;
            boolean ascii = true;
            int i = 0;
            for (; i + 4 <= n; i += 4) {
                char c0 = s.charAt(i);
                char c1 = s.charAt(i + 1);
                char c2 = s.charAt(i + 2);
                char c3 = s.charAt(i + 3);
                if ((c0 | c1 | c2 | c3) >= 0x80) {
                    ascii = false;
                    break;
                }
                buf[p + i] = (byte) c0;
                buf[p + i + 1] = (byte) c1;
                buf[p + i + 2] = (byte) c2;
                buf[p + i + 3] = (byte) c3;
            }
            if (ascii) {
                for (; i < n; i++) {
                    char c = s.charAt(i);
                    if (c >= 0x80) {
                        ascii = false;
                        break;
                    }
                    buf[p + i] = (byte) c;
                }
            }
            if (ascii) {
                buf[pos] = (byte) ((n << 1) | 1);
                pos = p + n;
                return;
            }
        }
        byte[] utf8 = Utf8Validator.encodeStrict(s);
        ensureCapacity(9 + utf8.length);
        pos = PrefixVarint.write(buf, pos, utf8.length);
        System.arraycopy(utf8, 0, buf, pos, utf8.length);
        pos += utf8.length;
    }

    public void writeBytesElement(byte[] data) {
        int len = data == null ? 0 : data.length;
        ensureCapacity(9 + len);
        pos = PrefixVarint.write(buf, pos, len);
        if (len > 0) {
            System.arraycopy(data, 0, buf, pos, len);
            pos += len;
        }
    }

    // ========================================================================
    // Single-Pass Containers (WireSubMsg, WireArray, WireMap)
    // ========================================================================

    public void beginSubMsg(int fieldNumber) {
        writeTag(fieldNumber, WireType.WIRE_SUB_MSG);
        ensureCapacity(2);
        pushContainer(pos, 0);
        buf[pos] = 0;
        buf[pos + 1] = 0;
        pos += 2;
    }

    public void endSubMsg() {
        if (depth <= 0) {
            throw new OmniBufException("omnibuf: endSubMsg on empty container stack");
        }
        depth--;
        backpatchReserved2ByteLen(lenStack[depth]);
    }

    public void beginSubMsgArr(int fieldNumber) {
        writeTag(fieldNumber, WireType.WIRE_ARRAY);
        ensureCapacity(4);
        pushContainer(pos, 0);
        buf[pos] = 0;
        buf[pos + 1] = 0;
        buf[pos + 2] = (byte) WireType.WIRE_SUB_MSG;
        buf[pos + 3] = 0; // Reserved 1B count slot
        pos += 4;
    }

    public void beginSubMsgElem() {
        if (depth <= 0) {
            throw new OmniBufException("omnibuf: beginSubMsgElem outside container");
        }
        countStack[depth - 1]++;
        ensureCapacity(2);
        pushContainer(pos, 0);
        buf[pos] = 0;
        buf[pos + 1] = 0;
        pos += 2;
    }

    public void endSubMsgElem() {
        if (depth <= 0) {
            throw new OmniBufException("omnibuf: endSubMsgElem on empty container stack");
        }
        depth--;
        backpatchReserved2ByteLen(lenStack[depth]);
    }

    public void endSubMsgArr() {
        if (depth <= 0) {
            throw new OmniBufException("omnibuf: endSubMsgArr on empty container stack");
        }
        depth--;
        int lenPos = lenStack[depth];
        int count = countStack[depth];
        patchReserved1ByteVarint(lenPos + 3, count);
        backpatchReserved2ByteLen(lenPos);
    }

    public void beginTypedArray(int fieldNumber, int elemWireType, int count) {
        if (!WireType.isValidArrayElemWireType(elemWireType)) {
            throw new OmniBufException("omnibuf: invalid array element wire type: " + elemWireType);
        }
        writeTag(fieldNumber, WireType.WIRE_ARRAY);
        ensureCapacity(12);
        pushContainer(pos, count);
        buf[pos] = 0;
        buf[pos + 1] = 0;
        buf[pos + 2] = (byte) elemWireType;
        pos = PrefixVarint.write(buf, pos + 3, Integer.toUnsignedLong(count));
    }

    public void endTypedArray() {
        if (depth <= 0) {
            throw new OmniBufException("omnibuf: endTypedArray on empty container stack");
        }
        depth--;
        backpatchReserved2ByteLen(lenStack[depth]);
    }

    public void beginTypedMap(int fieldNumber, int keyWireType, int valWireType, int count) {
        if (!WireType.isValidMapKeyWireType(keyWireType)) {
            throw new OmniBufException("omnibuf: invalid map key wire type: " + keyWireType);
        }
        if (!WireType.isValidMapValWireType(valWireType)) {
            throw new OmniBufException("omnibuf: invalid map value wire type: " + valWireType);
        }
        writeTag(fieldNumber, WireType.WIRE_MAP);
        ensureCapacity(13);
        pushContainer(pos, count);
        buf[pos] = 0;
        buf[pos + 1] = 0;
        buf[pos + 2] = (byte) keyWireType;
        buf[pos + 3] = (byte) valWireType;
        pos = PrefixVarint.write(buf, pos + 4, Integer.toUnsignedLong(count));
    }

    public void endTypedMap() {
        if (depth <= 0) {
            throw new OmniBufException("omnibuf: endTypedMap on empty container stack");
        }
        depth--;
        backpatchReserved2ByteLen(lenStack[depth]);
    }

    /**
     * Begins an inline sub-message element inside an open container (such as a WireMap value
     * with {@code valWireType == WIRE_SUB_MSG}) without writing a field tag.
     */
    public void beginInlineSubMsg() {
        ensureCapacity(2);
        pushContainer(pos, 0);
        buf[pos] = 0;
        buf[pos + 1] = 0;
        pos += 2;
    }

    public void endInlineSubMsg() {
        if (depth <= 0) {
            throw new OmniBufException("omnibuf: endInlineSubMsg on empty container stack");
        }
        depth--;
        backpatchReserved2ByteLen(lenStack[depth]);
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

    private void backpatchReserved2ByteLen(int lenPos) {
        int payloadStart = lenPos + 2;
        int subLen = pos - payloadStart;
        if (subLen <= PrefixVarint.MAX_2BYTE) {
            PrefixVarint.writeOverlong2Byte(buf, lenPos, subLen);
            return;
        }
        if (subLen <= PrefixVarint.MAX_4BYTE) {
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
    // High-Level Typed Array & Map Helpers
    // ========================================================================

    public void writeStringList(int fieldNumber, List<String> values) {
        int n = values == null ? 0 : values.size();
        beginTypedArray(fieldNumber, WireType.WIRE_STRING, n);
        for (int i = 0; i < n; i++) {
            writeStringElement(values.get(i));
        }
        endTypedArray();
    }

    public void writeBytesList(int fieldNumber, List<byte[]> values) {
        int n = values == null ? 0 : values.size();
        beginTypedArray(fieldNumber, WireType.WIRE_BYTES, n);
        for (int i = 0; i < n; i++) {
            writeBytesElement(values.get(i));
        }
        endTypedArray();
    }

    public void writeUint32List(int fieldNumber, List<Integer> values) {
        int n = values == null ? 0 : values.size();
        beginTypedArray(fieldNumber, WireType.WIRE_UINT, n);
        if (n > 0) {
            ensureCapacity(n * 9);
            for (int i = 0; i < n; i++) {
                Integer elem = values.get(i);
                pos = PrefixVarint.write(buf, pos, Integer.toUnsignedLong(elem == null ? 0 : elem));
            }
        }
        endTypedArray();
    }

    public void writeUint32Array(int fieldNumber, int[] values) {
        int n = values == null ? 0 : values.length;
        beginTypedArray(fieldNumber, WireType.WIRE_UINT, n);
        for (int i = 0; i < n; i++) {
            writeVarint(Integer.toUnsignedLong(values[i]));
        }
        endTypedArray();
    }

    public void writeUint64List(int fieldNumber, List<Long> values) {
        int n = values == null ? 0 : values.size();
        beginTypedArray(fieldNumber, WireType.WIRE_UINT, n);
        for (int i = 0; i < n; i++) {
            Long elem = values.get(i);
            writeVarint(elem == null ? 0L : elem);
        }
        endTypedArray();
    }

    public void writeSint32List(int fieldNumber, List<Integer> values) {
        int n = values == null ? 0 : values.size();
        beginTypedArray(fieldNumber, WireType.WIRE_SINT, n);
        for (int i = 0; i < n; i++) {
            Integer elem = values.get(i);
            writeVarint(PrefixVarint.encodeZigZag32(elem == null ? 0 : elem));
        }
        endTypedArray();
    }

    public void writeSint64List(int fieldNumber, List<Long> values) {
        int n = values == null ? 0 : values.size();
        beginTypedArray(fieldNumber, WireType.WIRE_SINT, n);
        for (int i = 0; i < n; i++) {
            Long elem = values.get(i);
            writeVarint(PrefixVarint.encodeZigZag64(elem == null ? 0L : elem));
        }
        endTypedArray();
    }

    public void writeBoolList(int fieldNumber, List<Boolean> values) {
        int n = values == null ? 0 : values.size();
        beginTypedArray(fieldNumber, WireType.WIRE_BOOL_FALSE, n);
        ensureCapacity(n);
        for (int i = 0; i < n; i++) {
            Boolean elem = values.get(i);
            buf[pos++] = (byte) (Boolean.TRUE.equals(elem) ? 1 : 0);
        }
        endTypedArray();
    }

    public void writeFixed32List(int fieldNumber, int elemWireType, List<Integer> values) {
        int n = values == null ? 0 : values.size();
        beginTypedArray(fieldNumber, elemWireType, n);
        ensureCapacity(n * 4);
        for (int i = 0; i < n; i++) {
            Integer elem = values.get(i);
            PrefixVarint.setIntLE(buf, pos, elem == null ? 0 : elem);
            pos += 4;
        }
        endTypedArray();
    }

    public void writeFixed64List(int fieldNumber, int elemWireType, List<Long> values) {
        int n = values == null ? 0 : values.size();
        beginTypedArray(fieldNumber, elemWireType, n);
        ensureCapacity(n * 8);
        for (int i = 0; i < n; i++) {
            Long elem = values.get(i);
            PrefixVarint.setLongLE(buf, pos, elem == null ? 0L : elem);
            pos += 8;
        }
        endTypedArray();
    }

    public void writeFloat32List(int fieldNumber, List<Float> values) {
        int n = values == null ? 0 : values.size();
        beginTypedArray(fieldNumber, WireType.WIRE_FLOAT32, n);
        ensureCapacity(n * 4);
        for (int i = 0; i < n; i++) {
            Float elem = values.get(i);
            PrefixVarint.setIntLE(buf, pos, Float.floatToRawIntBits(elem == null ? 0.0f : elem));
            pos += 4;
        }
        endTypedArray();
    }

    public void writeFloat64List(int fieldNumber, List<Double> values) {
        int n = values == null ? 0 : values.size();
        beginTypedArray(fieldNumber, WireType.WIRE_FLOAT64, n);
        ensureCapacity(n * 8);
        for (int i = 0; i < n; i++) {
            Double elem = values.get(i);
            PrefixVarint.setLongLE(buf, pos, Double.doubleToRawLongBits(elem == null ? 0.0d : elem));
            pos += 8;
        }
        endTypedArray();
    }

    public void writeMapStringUint64(int fieldNumber, Map<String, Long> map, boolean deterministic) {
        int n = map == null ? 0 : map.size();
        beginTypedMap(fieldNumber, WireType.WIRE_STRING, WireType.WIRE_UINT, n);
        if (n > 0) {
            Map<String, Long> iter;
            if (deterministic) {
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
        endTypedMap();
    }
}
