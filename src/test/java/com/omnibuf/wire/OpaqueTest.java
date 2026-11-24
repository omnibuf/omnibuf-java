package com.omnibuf.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.omnibuf.MarshalOptions;
import com.omnibuf.OmniBufException;
import com.omnibuf.SchemaMeta;
import com.omnibuf.pb.bench.OmniBufPeerUpdate;
import com.omnibuf.pb.bench.OpaquePeerUpdate;
import com.omnibuf.pb.bench.PeerUpdateOmniBuf;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpaqueTest {

    @Test
    void opaqueReadsFieldsPast128MiBOffsetBoundary() {
        OmniBufPeerUpdate peer = new OmniBufPeerUpdate()
            .setAsn(64512)
            .setRouterId("rtr-1")
            .setSequence(99L);
        byte[] encoded = PeerUpdateOmniBuf.marshal(peer, MarshalOptions.SELF_DESCRIBING);

        int offset = 134_217_600;
        byte[] slab = new byte[134_218_000];
        System.arraycopy(encoded, 0, slab, offset, encoded.length);
        assertTrue(offset + encoded.length > 0x07FF_FFFF);

        OpaquePeerUpdate view = new OpaquePeerUpdate(slab, offset, encoded.length);
        assertEquals(64512, view.getAsn());
        assertEquals("rtr-1", view.getRouterId());
        assertEquals(99L, view.getSequence());
    }

    @Test
    void opaqueThreeArgConstructorValidatesSliceBoundsImmediately() {
        RuntimeException thrown = assertThrows(
            RuntimeException.class,
            () -> new OpaquePeerUpdate(new byte[10], -5, 20)
        );
        assertTrue(
            thrown instanceof OmniBufException || thrown instanceof IndexOutOfBoundsException,
            () -> "Expected OmniBufException or IndexOutOfBoundsException, got: " + thrown
        );
    }

    @Test
    void opaqueEnforcesExactFixedVsSfixedWireTypeMatching() {
        OmniBufEncoder enc = new OmniBufEncoder();
        enc.writeSfixed32(1, -12345);
        enc.writeFixed32(2, 54321);
        enc.writeSfixed64(3, -9876543210L);
        enc.writeFixed64(4, 1234567890123L);
        enc.writeFixed32List(5, WireType.WIRE_SFIXED32, List.of(-1, -2));
        enc.writeFixed64List(6, WireType.WIRE_SFIXED64, List.of(-10L, -20L));
        byte[] pkt = enc.toByteArray();

        long[] idx = OmniBufOpaque.buildFieldIndex(pkt, 0, pkt.length, 8);

        // Exact matches succeed
        assertEquals(-12345, OmniBufOpaque.readSfixed32(pkt, 0, pkt.length, 1));
        assertEquals(-12345, OmniBufOpaque.readSfixed32Indexed(idx, pkt, 0, pkt.length, 1));
        assertEquals(54321, OmniBufOpaque.readFixed32(pkt, 0, pkt.length, 2));
        assertEquals(54321, OmniBufOpaque.readFixed32Indexed(idx, pkt, 0, pkt.length, 2));
        assertEquals(-9876543210L, OmniBufOpaque.readSfixed64(pkt, 0, pkt.length, 3));
        assertEquals(-9876543210L, OmniBufOpaque.readSfixed64Indexed(idx, pkt, 0, pkt.length, 3));
        assertEquals(1234567890123L, OmniBufOpaque.readFixed64(pkt, 0, pkt.length, 4));
        assertEquals(1234567890123L, OmniBufOpaque.readFixed64Indexed(idx, pkt, 0, pkt.length, 4));

        // Swapped fixed/sfixed scalar reads return default 0 (consistent with hasField == false)
        assertFalse(OmniBufOpaque.hasField(pkt, 0, pkt.length, 1, 1 << WireType.WIRE_FIXED32));
        assertEquals(0, OmniBufOpaque.readFixed32(pkt, 0, pkt.length, 1));
        assertEquals(0, OmniBufOpaque.readFixed32Indexed(idx, pkt, 0, pkt.length, 1));
        assertFalse(OmniBufOpaque.hasField(pkt, 0, pkt.length, 2, 1 << WireType.WIRE_SFIXED32));
        assertEquals(0, OmniBufOpaque.readSfixed32(pkt, 0, pkt.length, 2));
        assertEquals(0, OmniBufOpaque.readSfixed32Indexed(idx, pkt, 0, pkt.length, 2));
        assertFalse(OmniBufOpaque.hasField(pkt, 0, pkt.length, 3, 1 << WireType.WIRE_FIXED64));
        assertEquals(0L, OmniBufOpaque.readFixed64(pkt, 0, pkt.length, 3));
        assertEquals(0L, OmniBufOpaque.readFixed64Indexed(idx, pkt, 0, pkt.length, 3));
        assertFalse(OmniBufOpaque.hasField(pkt, 0, pkt.length, 4, 1 << WireType.WIRE_SFIXED64));
        assertEquals(0L, OmniBufOpaque.readSfixed64(pkt, 0, pkt.length, 4));
        assertEquals(0L, OmniBufOpaque.readSfixed64Indexed(idx, pkt, 0, pkt.length, 4));

        // Swapped fixed/sfixed list reads throw OmniBufException
        assertEquals(List.of(-1, -2), OmniBufOpaque.readSfixed32List(pkt, 0, pkt.length, 5));
        assertThrows(OmniBufException.class, () -> OmniBufOpaque.readFixed32List(pkt, 0, pkt.length, 5));
        assertEquals(List.of(-10L, -20L), OmniBufOpaque.readSfixed64List(pkt, 0, pkt.length, 6));
        assertThrows(OmniBufException.class, () -> OmniBufOpaque.readFixed64List(pkt, 0, pkt.length, 6));
    }

    @Test
    void opaqueScalarGettersThrowOnTruncatedFraming() {
        OmniBufEncoder enc = new OmniBufEncoder();
        enc.writeUint32(1, 64512);
        enc.writeTag(2, WireType.WIRE_STRING);
        enc.writeVarint(50); // truncated string claiming 50 bytes
        byte[] truncated = enc.toByteArray();

        OpaquePeerUpdate view = new OpaquePeerUpdate(truncated);
        assertThrows(OmniBufException.class, view::getAsn);
        assertThrows(OmniBufException.class, view::getRouterId);
        assertThrows(
            OmniBufException.class,
            () -> OmniBufOpaque.buildFieldIndex(truncated, 0, truncated.length, 16)
        );
    }

    @Test
    void spliceFieldsBitSetHandlesMaxFieldNumberWithoutMemoryAmplification() {
        int maxFieldNum = WireType.MAX_FIELD_NUMBER; // 536_870_911 (9-byte tag)
        OmniBufEncoder enc = new OmniBufEncoder();
        enc.writeUint32(maxFieldNum, 42);
        enc.writeUint32(70, 1);
        byte[] packet = enc.toByteArray();

        BitSet dirty = new BitSet();
        dirty.set(70);
        dirty.set(71);

        byte[] spliced = null;
        for (int i = 0; i < 16; i++) {
            spliced = OmniBufOpaque.spliceFields(
                packet,
                0,
                packet.length,
                dirty,
                71,
                (out, fnum) -> {
                    if (fnum == 70) {
                        out.writeUint32(70, 2);
                    } else if (fnum == 71) {
                        out.writeUint32(71, 3);
                    }
                },
                null
            );
        }

        assertNotNull(spliced);
        assertEquals(42, OmniBufOpaque.readUint32(spliced, 0, spliced.length, maxFieldNum));
        assertEquals(2, OmniBufOpaque.readUint32(spliced, 0, spliced.length, 70));
        assertEquals(3, OmniBufOpaque.readUint32(spliced, 0, spliced.length, 71));
    }

    @Test
    void accumulatedMaxCollectionElementsEnforcedAcrossConcatenatedSegments() {
        // 1) OmniBufOpaque concatenated WireArray segments (3 + 3 = 6 elements)
        OmniBufEncoder arrEnc = new OmniBufEncoder();
        arrEnc.writeUint32List(1, List.of(10, 20, 30));
        arrEnc.writeUint32List(1, List.of(40, 50, 60));
        arrEnc.writeStringList(2, List.of("a", "b", "c"));
        arrEnc.writeStringList(2, List.of("d", "e", "f"));
        arrEnc.writeFixed32List(3, WireType.WIRE_FIXED32, List.of(1, 2, 3));
        arrEnc.writeFixed32List(3, WireType.WIRE_FIXED32, List.of(4, 5, 6));
        byte[] arrPkt = arrEnc.toByteArray();

        assertThrows(OmniBufException.class, () -> OmniBufOpaque.readUint32List(arrPkt, 0, arrPkt.length, 1, 5));
        assertEquals(List.of(10, 20, 30, 40, 50, 60), OmniBufOpaque.readUint32List(arrPkt, 0, arrPkt.length, 1, 6));
        assertThrows(OmniBufException.class, () -> OmniBufOpaque.readStringList(arrPkt, 0, arrPkt.length, 2, 5));
        assertEquals(List.of("a", "b", "c", "d", "e", "f"), OmniBufOpaque.readStringList(arrPkt, 0, arrPkt.length, 2, 6));
        assertThrows(OmniBufException.class, () -> OmniBufOpaque.readFixed32List(arrPkt, 0, arrPkt.length, 3, 5));
        assertEquals(List.of(1, 2, 3, 4, 5, 6), OmniBufOpaque.readFixed32List(arrPkt, 0, arrPkt.length, 3, 6));

        // 2) OmniBufOpaque concatenated WireMap segments (2 + 2 = 4 distinct keys)
        OmniBufEncoder mapEnc = new OmniBufEncoder();
        mapEnc.writeMapStringUint64(4, Map.of("k1", 1L, "k2", 2L), true);
        mapEnc.writeMapStringUint64(4, Map.of("k3", 3L, "k4", 4L), true);
        // Overlapping keys across segments (2 + 2 = 2 distinct keys)
        mapEnc.writeMapStringUint64(5, Map.of("dup1", 1L, "dup2", 2L), true);
        mapEnc.writeMapStringUint64(5, Map.of("dup1", 10L, "dup2", 20L), true);
        byte[] mapPkt = mapEnc.toByteArray();

        assertThrows(OmniBufException.class, () -> OmniBufOpaque.readMapStringUint64(mapPkt, 0, mapPkt.length, 4, 3));
        assertEquals(
            Map.of("k1", 1L, "k2", 2L, "k3", 3L, "k4", 4L),
            OmniBufOpaque.readMapStringUint64(mapPkt, 0, mapPkt.length, 4, 4)
        );
        assertEquals(
            Map.of("dup1", 10L, "dup2", 20L),
            OmniBufOpaque.readMapStringUint64(mapPkt, 0, mapPkt.length, 5, 2)
        );

        assertThrows(
            OmniBufException.class,
            () -> OmniBufOpaque.readMap(
                mapPkt, 0, mapPkt.length, 4,
                WireType.WIRE_STRING, WireType.WIRE_UINT,
                OmniBufDecoder::readString, OmniBufDecoder::readVarint, 3
            )
        );
        assertEquals(
            Map.of("k1", 1L, "k2", 2L, "k3", 3L, "k4", 4L),
            OmniBufOpaque.readMap(
                mapPkt, 0, mapPkt.length, 4,
                WireType.WIRE_STRING, WireType.WIRE_UINT,
                OmniBufDecoder::readString, OmniBufDecoder::readVarint, 4
            )
        );

        // 3) SchemaMeta concatenated field #2 (fields) and field #3 (children) maps
        OmniBufEncoder schemaFieldsEnc = new OmniBufEncoder();
        schemaFieldsEnc.beginTypedMap(2, WireType.WIRE_UINT, WireType.WIRE_STRING, 2);
        schemaFieldsEnc.writeVarint(1);
        schemaFieldsEnc.writeStringElement("a");
        schemaFieldsEnc.writeVarint(2);
        schemaFieldsEnc.writeStringElement("b");
        schemaFieldsEnc.endTypedMap();
        schemaFieldsEnc.beginTypedMap(2, WireType.WIRE_UINT, WireType.WIRE_STRING, 2);
        schemaFieldsEnc.writeVarint(3);
        schemaFieldsEnc.writeStringElement("c");
        schemaFieldsEnc.writeVarint(4);
        schemaFieldsEnc.writeStringElement("d");
        schemaFieldsEnc.endTypedMap();
        byte[] schemaFieldsBody = schemaFieldsEnc.toByteArray();

        assertThrows(
            OmniBufException.class,
            () -> SchemaMeta.decode(schemaFieldsBody, 0, schemaFieldsBody.length, 1, 32, 3)
        );
        SchemaMeta decodedFields = SchemaMeta.decode(schemaFieldsBody, 0, schemaFieldsBody.length, 1, 32, 4);
        assertEquals(Map.of(1, "a", 2, "b", 3, "c", 4, "d"), decodedFields.fields());

        OmniBufEncoder schemaChildrenEnc = new OmniBufEncoder();
        schemaChildrenEnc.beginTypedMap(3, WireType.WIRE_UINT, WireType.WIRE_SUB_MSG, 2);
        schemaChildrenEnc.writeVarint(1);
        schemaChildrenEnc.beginInlineSubMsg();
        schemaChildrenEnc.writeString(1, "Child1");
        schemaChildrenEnc.endInlineSubMsg();
        schemaChildrenEnc.writeVarint(2);
        schemaChildrenEnc.beginInlineSubMsg();
        schemaChildrenEnc.writeString(1, "Child2");
        schemaChildrenEnc.endInlineSubMsg();
        schemaChildrenEnc.endTypedMap();
        schemaChildrenEnc.beginTypedMap(3, WireType.WIRE_UINT, WireType.WIRE_SUB_MSG, 2);
        schemaChildrenEnc.writeVarint(3);
        schemaChildrenEnc.beginInlineSubMsg();
        schemaChildrenEnc.writeString(1, "Child3");
        schemaChildrenEnc.endInlineSubMsg();
        schemaChildrenEnc.writeVarint(4);
        schemaChildrenEnc.beginInlineSubMsg();
        schemaChildrenEnc.writeString(1, "Child4");
        schemaChildrenEnc.endInlineSubMsg();
        schemaChildrenEnc.endTypedMap();
        byte[] schemaChildrenBody = schemaChildrenEnc.toByteArray();

        assertThrows(
            OmniBufException.class,
            () -> SchemaMeta.decode(schemaChildrenBody, 0, schemaChildrenBody.length, 1, 32, 3)
        );
        SchemaMeta decodedChildren = SchemaMeta.decode(schemaChildrenBody, 0, schemaChildrenBody.length, 1, 32, 4);
        assertEquals(4, decodedChildren.children().size());
    }

    @Test
    void opaqueConcurrentLazyFieldIndexVisibility() throws Exception {
        OmniBufPeerUpdate peer = new OmniBufPeerUpdate()
            .setAsn(65001)
            .setRouterId("shared-opaque")
            .setSequence(4242L);
        byte[] pkt = PeerUpdateOmniBuf.marshal(peer);
        OpaquePeerUpdate shared = new OpaquePeerUpdate(pkt);

        int workers = 16;
        List<Thread> threads = new ArrayList<>(workers);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < workers; i++) {
            Thread t = Thread.ofVirtual().start(() -> {
                try {
                    for (int iter = 0; iter < 50; iter++) {
                        assertEquals(65001, shared.getAsn());
                        assertEquals("shared-opaque", shared.getRouterId());
                        assertEquals(4242L, shared.getSequence());
                    }
                } catch (Throwable err) {
                    failures.add(err);
                }
            });
            threads.add(t);
        }
        for (Thread t : threads) {
            t.join();
        }
        assertTrue(failures.isEmpty(), () -> "Concurrent Opaque read failures: " + failures);
    }
}
