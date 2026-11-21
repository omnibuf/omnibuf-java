package com.omnibuf.wire;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.omnibuf.OmniBufException;
import com.omnibuf.SchemaMeta;
import com.omnibuf.pb.bench.OmniBufLocation;
import com.omnibuf.pb.bench.OmniBufNeighbor;
import com.omnibuf.pb.bench.OmniBufPeerUpdate;
import com.omnibuf.pb.bench.OpaqueLocation;
import com.omnibuf.pb.bench.OpaquePeerUpdate;
import com.omnibuf.pb.bench.PeerUpdateOmniBuf;
import com.omnibuf.pb.conformance.OmniBufTestAllTypes;
import com.omnibuf.pb.conformance.OpaqueTestAllTypes;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class WireContainerAndOpaqueTest {

    @Test
    void encoderAndDecoderEnforceMaxNestingDepth32() {
        // Depth 32 passes both encoder and decoder validation (root = 0, 32 nested submessages)
        OmniBufEncoder enc32 = new OmniBufEncoder(128);
        for (int i = 0; i < OmniBufDecoder.MAX_CONTAINER_DEPTH; i++) {
            enc32.beginSubMsg(1);
        }
        enc32.writeUint(2, 42L);
        for (int i = 0; i < OmniBufDecoder.MAX_CONTAINER_DEPTH; i++) {
            enc32.endSubMsg();
        }
        byte[] valid32 = enc32.toByteArray();
        assertDoesNotThrow(() -> OmniBufDecoder.validateMessage(valid32, 0, valid32.length, 0));

        // Encoder itself rejects depth 33
        OmniBufEncoder enc33 = new OmniBufEncoder(128);
        for (int i = 0; i < OmniBufDecoder.MAX_CONTAINER_DEPTH; i++) {
            enc33.beginSubMsg(1);
        }
        assertThrows(OmniBufException.class, () -> enc33.beginSubMsg(1));

        // Wrapping valid32 raw bytes inside one outer WireSubMsg creates a 33-deep packet
        OmniBufEncoder rawOuter = new OmniBufEncoder(valid32.length + 16);
        rawOuter.writeTag(1, WireType.WIRE_SUB_MSG);
        rawOuter.writeVarint(valid32.length);
        rawOuter.writeRawBytes(valid32);
        byte[] tooDeep33 = rawOuter.toByteArray();
        assertThrows(
            OmniBufException.class,
            () -> OmniBufDecoder.validateMessage(tooDeep33, 0, tooDeep33.length, 0)
        );
    }

    @Test
    void encoderFastContainerHeadersValidateArguments() {
        OmniBufEncoder enc = new OmniBufEncoder();
        // Negative count rejected
        assertThrows(
            OmniBufException.class,
            () -> enc.beginTypedArrayFast(0x3B, WireType.WIRE_UINT, -1)
        );
        // Invalid element wire type rejected
        assertThrows(
            OmniBufException.class,
            () -> enc.beginTypedArrayFast(0x3B, WireType.WIRE_BOOL_TRUE, 1)
        );
        // Sub-message key wire type in map rejected
        assertThrows(
            OmniBufException.class,
            () -> enc.beginTypedMapFast(0x3D, WireType.WIRE_SUB_MSG, WireType.WIRE_UINT, 1)
        );
        // Negative map count rejected
        assertThrows(
            OmniBufException.class,
            () -> enc.beginTypedMapFast(0x3D, WireType.WIRE_STRING, WireType.WIRE_UINT, -1)
        );
    }

    @Test
    void codecPoolSupportsNestedReentrancyAndTrimsOversizedBuffers() {
        OmniBufEncoder outerEnc = CodecPool.acquireEncoder();
        OmniBufEncoder innerEnc = CodecPool.acquireEncoder();
        assertNotSame(outerEnc, innerEnc);
        outerEnc.writeUint(1, 100L);
        innerEnc.writeUint(1, 200L);
        byte[] innerBytes = innerEnc.toByteArray();
        CodecPool.releaseEncoder(innerEnc);
        byte[] outerBytes = outerEnc.toByteArray();
        CodecPool.releaseEncoder(outerEnc);
        assertEquals(200L, OmniBufOpaque.readUint64(innerBytes, 0, innerBytes.length, 1));
        assertEquals(100L, OmniBufOpaque.readUint64(outerBytes, 0, outerBytes.length, 1));

        // Subsequent single checkout reuses pooled instance
        OmniBufEncoder reused = CodecPool.acquireEncoder();
        assertSame(outerEnc, reused);
        // Grow buffer past MAX_POOLED_ENCODER_CAPACITY (64 KiB), then release and verify trim
        reused.writeBytes(1, new byte[CodecPool.MAX_POOLED_ENCODER_CAPACITY + 8192]);
        assertTrue(reused.capacity() > CodecPool.MAX_POOLED_ENCODER_CAPACITY);
        CodecPool.releaseEncoder(reused);

        OmniBufEncoder trimmed = CodecPool.acquireEncoder();
        assertTrue(trimmed.capacity() <= CodecPool.MAX_POOLED_ENCODER_CAPACITY);
        CodecPool.releaseEncoder(trimmed);

        // Nested decoder re-entrancy
        OmniBufDecoder outerDec = CodecPool.acquireDecoder(outerBytes, 0, outerBytes.length);
        OmniBufDecoder innerDec = CodecPool.acquireDecoder(innerBytes, 0, innerBytes.length);
        assertNotSame(outerDec, innerDec);
        CodecPool.releaseDecoder(innerDec);
        CodecPool.releaseDecoder(outerDec);
    }

    @Test
    void sliceBoundsChecksRejectSigned32BitIntegerOverflow() {
        byte[] small = new byte[] {0x01, 0x02, 0x03, 0x04};
        org.junit.jupiter.api.Assertions.assertFalse(
            Utf8Validator.isValidUtf8(small, 2, Integer.MAX_VALUE)
        );
        assertThrows(
            OmniBufException.class,
            () -> Utf8Validator.decodeStrict(small, 2, Integer.MAX_VALUE)
        );
        assertThrows(
            OmniBufException.class,
            () -> OmniBufDecoder.validateMessage(small, 2, Integer.MAX_VALUE, 0)
        );
        org.junit.jupiter.api.Assertions.assertFalse(
            OmniBufOpaque.locateField(small, 2, Integer.MAX_VALUE, 1, 1).found()
        );
        assertThrows(
            OmniBufException.class,
            () -> OmniBufOpaque.readSubMsgView(small, 2, Integer.MAX_VALUE, 1)
        );
        assertThrows(
            OmniBufException.class,
            () -> OmniBufOpaque.readStringList(small, 2, Integer.MAX_VALUE, 1)
        );
        assertThrows(
            OmniBufException.class,
            () -> SchemaMeta.decode(small, 2, Integer.MAX_VALUE, 1)
        );
    }

    @Test
    void encoderPreservesPrefixWhenDetachingAliasedBuffer() {
        byte[] shared = new byte[] {0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88};
        OmniBufEncoder enc = new OmniBufEncoder(shared, 4);
        enc.detachIfAliased(shared, 4, 4);
        enc.writeRawBytes(shared, 4, 4);
        byte[] result = enc.toByteArray();
        assertArrayEquals(
            new byte[] {0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88},
            result
        );
    }

    @Test
    void opaqueRejectsInvalidBoolElementTrailingContainerBytesAndCorruptSplices() {
        // 1. Field #10 (f10_rep_bool) WireArray of booleans with illegal byte 0x02:
        // tag = (10<<4)|13 = 173 -> 2-byte Prefix-Varint: (173 << 2) | 0x02 = 694 = 0x02B6 -> B6 02
        OmniBufEncoder boolEnc = new OmniBufEncoder();
        boolEnc.writeTag(10, WireType.WIRE_ARRAY);
        boolEnc.writeVarint(3); // ByteLen = 3: ElemWT(2), Count=1(0x03), elem=0x02
        boolEnc.writeRawBytes(new byte[] {WireType.WIRE_BOOL_FALSE, 0x03, 0x02});
        byte[] badBoolPacket = boolEnc.toByteArray();

        assertThrows(
            OmniBufException.class,
            () -> OmniBufOpaque.readBoolList(badBoolPacket, 0, badBoolPacket.length, 10)
        );
        OpaqueTestAllTypes badBoolOpaque = new OpaqueTestAllTypes(badBoolPacket);
        assertThrows(OmniBufException.class, badBoolOpaque::getF10Uint32Arr);
        badBoolOpaque.setF1Uint32(99);
        assertThrows(OmniBufException.class, badBoolOpaque::marshalValidated);
        assertThrows(OmniBufException.class, badBoolOpaque::tryMarshal);

        // 2. Trailing garbage bytes inside variable-width WireArray (String, Uint32) and WireMap
        OmniBufEncoder trailingStrArr = new OmniBufEncoder();
        trailingStrArr.writeTag(7, WireType.WIRE_ARRAY);
        // ElemWT=WIRE_STRING(9), Count=1(0x03), item="a"(len=1->0x03,'a'=0x61), plus trailing 0x7F
        trailingStrArr.writeVarint(5);
        trailingStrArr.writeRawBytes(new byte[] {WireType.WIRE_STRING, 0x03, 0x03, 0x61, 0x7F});
        byte[] badStrArr = trailingStrArr.toByteArray();
        assertThrows(
            OmniBufException.class,
            () -> OmniBufOpaque.readStringList(badStrArr, 0, badStrArr.length, 7)
        );

        OmniBufEncoder trailingUintArr = new OmniBufEncoder();
        trailingUintArr.writeTag(8, WireType.WIRE_ARRAY);
        // ElemWT=WIRE_UINT(0), Count=1(0x03), item=5(0x0B), plus trailing 0x7F
        trailingUintArr.writeVarint(4);
        trailingUintArr.writeRawBytes(new byte[] {WireType.WIRE_UINT, 0x03, 0x0B, 0x7F});
        byte[] badUintArr = trailingUintArr.toByteArray();
        assertThrows(
            OmniBufException.class,
            () -> OmniBufOpaque.readUint32List(badUintArr, 0, badUintArr.length, 8)
        );

        OmniBufEncoder trailingMap = new OmniBufEncoder();
        trailingMap.writeTag(12, WireType.WIRE_MAP);
        // KeyWT=WIRE_STRING(9), ValWT=WIRE_UINT(0), Count=1(0x03), key="k"(0x03,0x6B), val=1(0x03), trailing 0x55
        trailingMap.writeVarint(7);
        trailingMap.writeRawBytes(
            new byte[] {WireType.WIRE_STRING, WireType.WIRE_UINT, 0x03, 0x03, 0x6B, 0x03, 0x55}
        );
        byte[] badMap = trailingMap.toByteArray();
        assertThrows(
            OmniBufException.class,
            () -> OmniBufOpaque.readMapStringUint64(badMap, 0, badMap.length, 12)
        );

        // 3. Corrupt Field #0 wire type (raw tag 0x01 -> fnum=0, wt=WIRE_UINT(0) instead of WIRE_SUB_MSG)
        byte[] badF0Packet = new byte[] {0x01, 0x03, 0x21, 0x55};
        org.junit.jupiter.api.Assertions.assertFalse(
            OmniBufOpaque.locateField(badF0Packet, 1, 1 << WireType.WIRE_UINT).found()
        );
        OpaquePeerUpdate badF0View = new OpaquePeerUpdate(badF0Packet);
        assertThrows(OmniBufException.class, badF0View::getLocation);
        assertThrows(OmniBufException.class, badF0View::getTags);
        badF0View.setSequence(10L);
        assertThrows(OmniBufException.class, badF0View::marshal);
        assertThrows(OmniBufException.class, badF0View::marshalValidated);

        // 4. Truncated tail in packet must fail splice rather than silently truncating
        OmniBufPeerUpdate validPeer = new OmniBufPeerUpdate().setAsn(64512).setSequence(10L);
        byte[] validBytes = validPeer.marshal();
        ByteArrayOutputStream corruptTail = new ByteArrayOutputStream();
        corruptTail.writeBytes(validBytes);
        corruptTail.write(0x39); // Field #1 WireString tag (1<<4 | 9 = 25 -> 0x33? 0x39 is (1<<4)|12 WireSubMsg)
        corruptTail.write(0x15); // Claims 10 bytes follow, but EOF!
        byte[] truncatedPacket = corruptTail.toByteArray();
        OpaquePeerUpdate truncatedView = new OpaquePeerUpdate(truncatedPacket);
        truncatedView.setSequence(11L);
        assertThrows(OmniBufException.class, truncatedView::marshal);
        assertThrows(OmniBufException.class, truncatedView::marshalValidated);

        // 5. Repeated WireSubMsg segments in readSubMsgView must validate individually before concatenating:
        // Segment 1 ends with incomplete 2-byte varint lead byte 0x02; Segment 2 starts with 0x01.
        // Raw byte fusion [0x21, 0x02] + [0x01] would form tag=Field#1 WireUint + 2-byte varint!
        OmniBufEncoder splitCorrupt = new OmniBufEncoder();
        splitCorrupt.writeTag(9, WireType.WIRE_SUB_MSG);
        splitCorrupt.writeVarint(2);
        splitCorrupt.writeRawBytes(new byte[] {0x21, 0x02}); // Field #1 WireUint + truncated 2B varint
        splitCorrupt.writeTag(9, WireType.WIRE_SUB_MSG);
        splitCorrupt.writeVarint(1);
        splitCorrupt.writeRawBytes(new byte[] {0x01});
        byte[] splitCorruptBytes = splitCorrupt.toByteArray();
        assertThrows(
            OmniBufException.class,
            () -> OmniBufOpaque.readSubMsgView(splitCorruptBytes, 0, splitCorruptBytes.length, 9)
        );
    }

    @Test
    void rejectsEmptyContainerTrailingBytesAndInvalidBooleanPaddingBits() {
        // Field #1 WireArray (tag = (1<<4)|13 = 29 -> 0x3B), ByteLen = 3 (0x07),
        // ElemWT = WireUint(0), Count = 0 (0x01), plus illegal trailing byte 0x99
        byte[] trailingEmptyArr = new byte[] {0x3B, 0x07, 0x00, 0x01, (byte) 0x99};
        assertThrows(
            OmniBufException.class,
            () -> OmniBufDecoder.validateMessage(trailingEmptyArr, 0, trailingEmptyArr.length, 0)
        );

        // Field #1 WireMap (tag = (1<<4)|14 = 30 -> 0x3D), ByteLen = 4 (0x09),
        // KeyWT = WireUint(0), ValWT = WireUint(0), Count = 0 (0x01), plus illegal trailing byte 0x99
        byte[] trailingEmptyMap = new byte[] {0x3D, 0x09, 0x00, 0x00, 0x01, (byte) 0x99};
        assertThrows(
            OmniBufException.class,
            () -> OmniBufDecoder.validateMessage(trailingEmptyMap, 0, trailingEmptyMap.length, 0)
        );

        // Field #1 WireArray of booleans (ElemWT = WireBoolFalse = 2), Count = 1 (0x03),
        // element byte = 0x02 (invalid boolean padding bits)
        byte[] badBoolArr = new byte[] {0x3B, 0x07, 0x02, 0x03, 0x02};
        assertThrows(
            OmniBufException.class,
            () -> OmniBufDecoder.validateMessage(badBoolArr, 0, badBoolArr.length, 0)
        );
    }

    @Test
    void skipsValidWireExtendedFieldInKnownSchemaDecoders() {
        // Field #1 uint = 7, followed by Field #99 WireExtended (15) with 1B subtype 0x2A + payload
        OmniBufEncoder enc = new OmniBufEncoder();
        enc.writeUint(1, 64512L);
        enc.writeTag(99, WireType.WIRE_EXTENDED);
        byte[] extPayload = new byte[] {0x2A, 0x01, 0x02, 0x03};
        enc.writeVarint(extPayload.length);
        enc.writeRawBytes(extPayload);
        enc.writeString(2, "rtr-ext-test");
        byte[] packet = enc.toByteArray();

        assertDoesNotThrow(() -> OmniBufDecoder.validateMessage(packet, 0, packet.length, 0));
        OmniBufPeerUpdate decoded = PeerUpdateOmniBuf.unmarshal(packet);
        assertEquals(64512, decoded.getAsn());
        assertEquals("rtr-ext-test", decoded.getRouterId());
    }

    @Test
    void opaqueLazyLookupAndInPlaceSplicePreserveUntouchedFields() {
        OmniBufPeerUpdate peer = new OmniBufPeerUpdate()
            .setAsn(64512)
            .setRouterId("edge-rtr-fra01.core.backbone.net")
            .setActive(true)
            .setUptimeSec(1_849_203L)
            .setRssiDbm(-62)
            .setCpuLoad(0.42875d)
            .setTags(List.of("tier1", "ebgp"))
            .setPrefixes(List.of(167_772_160, (int) 3_232_235_520L))
            .setLocation(
                new OmniBufLocation()
                    .setRegion("eu-central-1")
                    .setDatacenter("FRA-Equinix-FR5")
                    .setCity("Frankfurt")
                    .setRack(42)
                    .setLatitude(50.1109f)
                    .setLongitude(8.6821f)
            )
            .setNeighbors(List.of(
                new OmniBufNeighbor()
                    .setPeerAsn(3356)
                    .setRouter("ae0.fra1.level3.net")
                    .setLatencyUs(850)
                    .setEstablished(true)
            ))
            .setSequence(9_876_543_210L);

        byte[] original = peer.marshal();

        // Append an unknown field #77 so we can verify Opaque splice preserves unknown bytes verbatim
        OmniBufEncoder withUnknown = new OmniBufEncoder(original.length + 32);
        withUnknown.writeRawBytes(original);
        withUnknown.writeBytes(77, "preserved-unknown-payload".getBytes(StandardCharsets.UTF_8));
        byte[] packet = withUnknown.toByteArray();

        OpaquePeerUpdate view = new OpaquePeerUpdate(packet);
        assertEquals(64512, view.getAsn());
        assertEquals(-62, view.getRssiDbm());
        OpaqueLocation loc = view.getLocation();
        assertNotNull(loc);
        assertEquals("Frankfurt", loc.getCity());
        assertEquals(42, loc.getRack());

        // Shrink sequence (from 9B varint 9876543210 to 1B varint 7) and grow routerId
        view.setSequence(7L);
        view.setRouterId("edge-rtr-fra01-mutated-longer-hostname.core.backbone.net");
        byte[] spliced = view.marshalValidated();

        OpaquePeerUpdate splicedView = new OpaquePeerUpdate(spliced);
        assertEquals(7L, splicedView.getSequence());
        assertEquals("edge-rtr-fra01-mutated-longer-hostname.core.backbone.net", splicedView.getRouterId());
        assertEquals("Frankfurt", splicedView.getLocation().getCity());
        assertEquals(List.of("tier1", "ebgp"), splicedView.getTags());

        // Unknown field #77 must still be intact in the spliced packet
        OmniBufOpaque.LocatedField unk =
            OmniBufOpaque.locateField(spliced, 77, 1 << WireType.WIRE_BYTES);
        assertTrue(unk.found());
        byte[] unkBytes = OmniBufOpaque.readBytes(spliced, 0, spliced.length, 77);
        assertEquals("preserved-unknown-payload", new String(unkBytes, StandardCharsets.UTF_8));
        assertEquals(
            0,
            Arrays.compare(
                "preserved-unknown-payload".getBytes(StandardCharsets.UTF_8),
                unkBytes
            )
        );
    }

    @Test
    void opaqueZeroCopyBytesViewAndReadOnlyByteBufferShareBackingPacket() {
        byte[] rawPayload = new byte[] {0x10, 0x20, 0x30, 0x40, 0x50};
        byte[] pkt = new OmniBufTestAllTypes()
            .setF1Uint32(77)
            .setF6String("zero-copy-java")
            .setF7Bytes(rawPayload)
            .marshal();

        OpaqueTestAllTypes view = new OpaqueTestAllTypes(pkt);
        // Trigger lazy fieldIndex on second and subsequent reads
        assertEquals(77, view.getF1Uint32());
        assertEquals("zero-copy-java", view.getF6String());

        OmniBufOpaque.PacketView f7View = view.getF7BytesView();
        assertSame(pkt, f7View.buffer());
        assertEquals(rawPayload.length, f7View.length());
        assertArrayEquals(rawPayload, f7View.toByteArray());

        ByteBuffer roBuf = view.getF7BytesBuffer();
        assertTrue(roBuf.isReadOnly());
        assertEquals(rawPayload.length, roBuf.remaining());
        assertThrows(ReadOnlyBufferException.class, () -> roBuf.put((byte) 1));
        byte[] fromBuf = new byte[roBuf.remaining()];
        roBuf.get(fromBuf);
        assertArrayEquals(rawPayload, fromBuf);
    }
}
