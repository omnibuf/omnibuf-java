package com.omnibuf.wire;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.omnibuf.OmniBufException;
import com.omnibuf.pb.bench.OmniBufLocation;
import com.omnibuf.pb.bench.OmniBufNeighbor;
import com.omnibuf.pb.bench.OmniBufPeerUpdate;
import com.omnibuf.pb.bench.OpaqueLocation;
import com.omnibuf.pb.bench.OpaquePeerUpdate;
import com.omnibuf.pb.bench.PeerUpdateOmniBuf;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class WireContainerAndOpaqueTest {

    @Test
    void encoderGrowsContainerStackAndEnforcesMaxNestingDepth() {
        // Depth 32 passes validation (root = 0, 32 nested submessages)
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

        // Depth 33 exceeds MAX_CONTAINER_DEPTH (32) and throws OmniBufException
        OmniBufEncoder enc33 = new OmniBufEncoder(128);
        for (int i = 0; i < OmniBufDecoder.MAX_CONTAINER_DEPTH + 1; i++) {
            enc33.beginSubMsg(1);
        }
        enc33.writeUint(2, 42L);
        for (int i = 0; i < OmniBufDecoder.MAX_CONTAINER_DEPTH + 1; i++) {
            enc33.endSubMsg();
        }
        byte[] tooDeep33 = enc33.toByteArray();
        assertThrows(
            OmniBufException.class,
            () -> OmniBufDecoder.validateMessage(tooDeep33, 0, tooDeep33.length, 0)
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
        byte[] spliced = view.marshal();

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
}
