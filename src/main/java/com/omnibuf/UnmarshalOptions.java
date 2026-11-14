package com.omnibuf;

import com.omnibuf.wire.WireType;

/**
 * Resource and structural limits enforced when decoding or validating OmniBuf packets.
 *
 * @param maxMessageBytes       maximum packet size in bytes (default 64 MiB)
 * @param maxCollectionElements maximum element or entry count per {@code WireArray} or {@code WireMap}
 *                              header (default 4,194,304)
 * @param maxNestingDepth       maximum container nesting depth across {@code WireSubMsg},
 *                              {@code WireArray}, {@code WireMap}, and {@code Field #0}
 *                              (default {@link WireType#MAX_CONTAINER_DEPTH} = 32)
 */
public record UnmarshalOptions(
    int maxMessageBytes,
    int maxCollectionElements,
    int maxNestingDepth
) {
    public static final int DEFAULT_MAX_MESSAGE_BYTES = 64 * 1024 * 1024;
    public static final int DEFAULT_MAX_COLLECTION_ELEMENTS = 4 * 1024 * 1024;
    public static final int DEFAULT_MAX_NESTING_DEPTH = WireType.MAX_CONTAINER_DEPTH;

    public static final UnmarshalOptions DEFAULT = new UnmarshalOptions(
        DEFAULT_MAX_MESSAGE_BYTES,
        DEFAULT_MAX_COLLECTION_ELEMENTS,
        DEFAULT_MAX_NESTING_DEPTH
    );

    public UnmarshalOptions {
        if (maxMessageBytes < 0) {
            throw new OmniBufException("omnibuf: maxMessageBytes must be non-negative: " + maxMessageBytes);
        }
        if (maxCollectionElements < 0) {
            throw new OmniBufException(
                "omnibuf: maxCollectionElements must be non-negative: " + maxCollectionElements
            );
        }
        if (maxNestingDepth < 0) {
            throw new OmniBufException("omnibuf: maxNestingDepth must be non-negative: " + maxNestingDepth);
        }
    }

    public UnmarshalOptions withMaxMessageBytes(int value) {
        return new UnmarshalOptions(value, this.maxCollectionElements, this.maxNestingDepth);
    }

    public UnmarshalOptions withMaxCollectionElements(int value) {
        return new UnmarshalOptions(this.maxMessageBytes, value, this.maxNestingDepth);
    }

    public UnmarshalOptions withMaxNestingDepth(int value) {
        return new UnmarshalOptions(this.maxMessageBytes, this.maxCollectionElements, value);
    }
}
