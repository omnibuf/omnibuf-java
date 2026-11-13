package com.omnibuf.wire;

import com.omnibuf.OmniBufException;

/**
 * OmniBuf 4-bit wire type constants, tag layout constants, and validation helpers.
 */
public final class WireType {

    public static final int WIRE_UINT = 0;
    public static final int WIRE_SINT = 1;
    public static final int WIRE_BOOL_FALSE = 2;
    public static final int WIRE_BOOL_TRUE = 3;
    public static final int WIRE_FIXED32 = 4;
    public static final int WIRE_FIXED64 = 5;
    public static final int WIRE_SFIXED32 = 6;
    public static final int WIRE_SFIXED64 = 7;
    public static final int WIRE_FLOAT32 = 8;
    public static final int WIRE_FLOAT64 = 9;
    public static final int WIRE_STRING = 10;
    public static final int WIRE_BYTES = 11;
    public static final int WIRE_SUB_MSG = 12;
    public static final int WIRE_ARRAY = 13;
    public static final int WIRE_MAP = 14;
    public static final int WIRE_EXTENDED = 15;

    /** Maximum legal field number ((1 << 29) - 1 = 536870911). */
    public static final int MAX_FIELD_NUMBER = (1 << 29) - 1;

    /** Maximum nesting depth across WireSubMsg, WireArray, WireMap, and Field #0 containers. */
    public static final int MAX_CONTAINER_DEPTH = 32;

    /** Pre-encoded 1-byte Prefix-Varint tag for Field #0 SchemaMeta (((0 << 4) | 12) << 1 | 1 = 0x19). */
    public static final byte FIELD0_TAG_BYTE = 0x19;

    private WireType() {}

    public static long makeTag(int fieldNumber, int wireType) {
        if (fieldNumber < 0 || fieldNumber > MAX_FIELD_NUMBER) {
            throw new OmniBufException("omnibuf: field number out of range: " + fieldNumber);
        }
        if (fieldNumber == 0 && wireType != WIRE_SUB_MSG) {
            throw new OmniBufException("omnibuf: field 0 requires WireSubMsg");
        }
        if ((wireType & ~0x0F) != 0) {
            throw new OmniBufException("omnibuf: invalid wire type: " + wireType);
        }
        return (Integer.toUnsignedLong(fieldNumber) << 4) | (wireType & 0x0FL);
    }

    /**
     * Returns the fixed payload byte width inside a WireArray for fixed-width element types,
     * or 0 for variable-width element types.
     */
    public static int fixedElementWidth(int elemWireType) {
        return switch (elemWireType) {
            case WIRE_BOOL_FALSE -> 1;
            case WIRE_FIXED32, WIRE_SFIXED32, WIRE_FLOAT32 -> 4;
            case WIRE_FIXED64, WIRE_SFIXED64, WIRE_FLOAT64 -> 8;
            default -> 0;
        };
    }

    public static boolean isValidArrayElemWireType(int wt) {
        return wt >= 0 && wt <= 15 && wt != WIRE_BOOL_TRUE;
    }

    public static boolean isValidMapKeyWireType(int wt) {
        return switch (wt) {
            case WIRE_UINT,
                 WIRE_SINT,
                 WIRE_BOOL_FALSE,
                 WIRE_FIXED32,
                 WIRE_FIXED64,
                 WIRE_SFIXED32,
                 WIRE_SFIXED64,
                 WIRE_STRING -> true;
            default -> false;
        };
    }

    public static boolean isValidMapValWireType(int wt) {
        return wt >= 0 && wt <= 15 && wt != WIRE_BOOL_TRUE;
    }

    public static String nameOf(int wt) {
        return switch (wt) {
            case WIRE_UINT -> "WireUint";
            case WIRE_SINT -> "WireSint";
            case WIRE_BOOL_FALSE -> "WireBoolFalse";
            case WIRE_BOOL_TRUE -> "WireBoolTrue";
            case WIRE_FIXED32 -> "WireFixed32";
            case WIRE_FIXED64 -> "WireFixed64";
            case WIRE_SFIXED32 -> "WireSfixed32";
            case WIRE_SFIXED64 -> "WireSfixed64";
            case WIRE_FLOAT32 -> "WireFloat32";
            case WIRE_FLOAT64 -> "WireFloat64";
            case WIRE_STRING -> "WireString";
            case WIRE_BYTES -> "WireBytes";
            case WIRE_SUB_MSG -> "WireSubMsg";
            case WIRE_ARRAY -> "WireArray";
            case WIRE_MAP -> "WireMap";
            case WIRE_EXTENDED -> "WireExtended";
            default -> "WireUnknown(" + wt + ")";
        };
    }
}
