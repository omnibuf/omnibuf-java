package com.omnibuf.wire;

import com.omnibuf.OmniBufException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;

/**
 * Strict UTF-8 validation and conversion utilities for {@code WireString}.
 *
 * <p>Unlike {@code new String(bytes, StandardCharsets.UTF_8)} and {@code String.getBytes(UTF_8)},
 * which silently substitute {@code U+FFFD} or {@code '?'} on malformed sequences and unpaired
 * UTF-16 surrogates, this class rejects every malformed byte sequence and unpaired surrogate
 * with {@link OmniBufException}.
 */
public final class Utf8Validator {

    private static final ThreadLocal<CharsetDecoder> STRICT_DECODER = ThreadLocal.withInitial(() ->
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
    );

    private Utf8Validator() {}

    /**
     * Returns {@code true} iff {@code buf[off..off+len]} is valid UTF-8 per RFC 3629
     * (rejecting overlong encodings, UTF-16 surrogates {@code U+D800..U+DFFF}, and codepoints
     * above {@code U+10FFFF}).
     */
    public static boolean isValidUtf8(byte[] buf, int off, int len) {
        if (buf == null || off < 0 || len < 0 || len > buf.length - off) {
            return false;
        }
        if (len == 0 || isPureAscii(buf, off, len)) {
            return true;
        }
        int end = off + len;
        int i = off;
        while (i + 8 <= end && (PrefixVarint.getLongLE(buf, i) & 0x8080_8080_8080_8080L) == 0L) {
            i += 8;
        }
        return isValidUtf8Slow(buf, i, end);
    }

    private static boolean isValidUtf8Slow(byte[] buf, int pos, int end) {
        int i = pos;
        while (i < end) {
            int b0 = buf[i++];
            if (b0 >= 0) {
                continue;
            }
            int u0 = b0 & 0xFF;
            if (u0 < 0xC2) {
                // 0x80..0xBF lone continuation or 0xC0..0xC1 overlong 2-byte sequence
                return false;
            }
            if (u0 <= 0xDF) {
                if (i >= end) {
                    return false;
                }
                int b1 = buf[i++] & 0xFF;
                if ((b1 & 0xC0) != 0x80) {
                    return false;
                }
            } else if (u0 <= 0xEF) {
                if (i + 1 >= end) {
                    return false;
                }
                int b1 = buf[i++] & 0xFF;
                int b2 = buf[i++] & 0xFF;
                if ((b1 & 0xC0) != 0x80 || (b2 & 0xC0) != 0x80) {
                    return false;
                }
                if (u0 == 0xE0 && b1 < 0xA0) {
                    // Overlong 3-byte sequence
                    return false;
                }
                if (u0 == 0xED && b1 >= 0xA0) {
                    // UTF-16 surrogate half U+D800..U+DFFF
                    return false;
                }
            } else if (u0 <= 0xF4) {
                if (i + 2 >= end) {
                    return false;
                }
                int b1 = buf[i++] & 0xFF;
                int b2 = buf[i++] & 0xFF;
                int b3 = buf[i++] & 0xFF;
                if ((b1 & 0xC0) != 0x80 || (b2 & 0xC0) != 0x80 || (b3 & 0xC0) != 0x80) {
                    return false;
                }
                if (u0 == 0xF0 && b1 < 0x90) {
                    // Overlong 4-byte sequence
                    return false;
                }
                if (u0 == 0xF4 && b1 > 0x8F) {
                    // Codepoint > U+10FFFF
                    return false;
                }
            } else {
                // 0xF5..0xFF invalid leading byte
                return false;
            }
        }
        return true;
    }

    /**
     * Validates and decodes {@code buf[off..off+len]} into a Java {@link String}.
     * Throws {@link OmniBufException} if the slice is not valid UTF-8.
     */
    public static String decodeStrict(byte[] buf, int off, int len) {
        if (buf == null || off < 0 || len < 0 || len > buf.length - off) {
            throw new OmniBufException("omnibuf: string slice out of bounds");
        }
        if (len == 0) {
            return "";
        }
        return decodeStrictUnchecked(buf, off, len);
    }

    /**
     * Validates and decodes {@code buf[off..off+len]} when caller has already verified
     * {@code buf != null && off >= 0 && len >= 0 && off + len <= buf.length}.
     */
    public static String decodeStrictUnchecked(byte[] buf, int off, int len) {
        if (len == 0) {
            return "";
        }
        // Fast path for pure ASCII strings (common for keys, router IDs, labels).
        if (isPureAscii(buf, off, len)) {
            return newAsciiString(buf, off, len);
        }
        return decodeUtf8NonAscii(buf, off, len);
    }

    @SuppressWarnings("deprecation")
    private static String newAsciiString(byte[] buf, int off, int len) {
        return new String(buf, 0, off, len);
    }

    private static String decodeUtf8NonAscii(byte[] buf, int off, int len) {
        CharsetDecoder decoder = STRICT_DECODER.get();
        decoder.reset();
        try {
            CharBuffer chars = decoder.decode(ByteBuffer.wrap(buf, off, len));
            return chars.toString();
        } catch (CharacterCodingException e) {
            throw new OmniBufException("omnibuf: invalid UTF-8 in WireString", e);
        }
    }

    private static boolean isPureAscii(byte[] buf, int off, int len) {
        int end = off + len;
        if (len >= 8) {
            int i = off;
            do {
                if ((PrefixVarint.getLongLE(buf, i) & 0x8080_8080_8080_8080L) != 0L) {
                    return false;
                }
                i += 8;
            } while (i + 8 <= end);
            return i == end || (PrefixVarint.getLongLE(buf, end - 8) & 0x8080_8080_8080_8080L) == 0L;
        }
        if (len >= 4) {
            return ((PrefixVarint.getIntLE(buf, off) | PrefixVarint.getIntLE(buf, end - 4)) & 0x8080_8080) == 0;
        }
        int m = buf[off];
        if (len > 1) {
            m |= buf[off + 1] | buf[end - 1];
        }
        return m >= 0;
    }

    /**
     * Encodes a Java {@link String} into UTF-8 bytes, rejecting unpaired UTF-16 surrogates
     * with {@link OmniBufException}.
     */
    public static byte[] encodeStrict(String s) {
        if (s == null || s.isEmpty()) {
            return EMPTY_BYTES;
        }
        int n = s.length();
        // Fast check for ASCII or valid surrogates.
        boolean ascii = true;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c >= 0x80) {
                ascii = false;
                if (Character.isSurrogate(c)) {
                    if (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) {
                        i++;
                    } else {
                        throw new OmniBufException("omnibuf: unpaired UTF-16 surrogate in WireString");
                    }
                }
            }
        }
        if (ascii) {
            byte[] out = new byte[n];
            for (int i = 0; i < n; i++) {
                out[i] = (byte) s.charAt(i);
            }
            return out;
        }
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Comparator ordering Java strings by exact UTF-8 byte / Unicode codepoint order
     * (unlike {@link String#compareTo}, which orders UTF-16 surrogates below {@code U+E000..U+FFFF}).
     */
    public static final Comparator<String> UTF8_ORDER = Utf8Validator::compareUtf8;

    public static int compareUtf8(String a, String b) {
        if (a == b) {
            return 0;
        }
        int na = a.length();
        int nb = b.length();
        int ia = 0;
        int ib = 0;
        while (ia < na && ib < nb) {
            char ca = a.charAt(ia);
            char cb = b.charAt(ib);
            if (ca == cb) {
                ia++;
                ib++;
                continue;
            }
            if (ca < 0xD800 && cb < 0xD800) {
                return ca - cb;
            }
            int cpa = Character.codePointAt(a, ia);
            int cpb = Character.codePointAt(b, ib);
            if (cpa != cpb) {
                return Integer.compare(cpa, cpb);
            }
            ia += Character.charCount(cpa);
            ib += Character.charCount(cpb);
        }
        return Integer.compare(na - ia, nb - ib);
    }

    private static final byte[] EMPTY_BYTES = new byte[0];
}
