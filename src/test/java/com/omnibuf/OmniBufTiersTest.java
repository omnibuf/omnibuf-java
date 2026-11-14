package com.omnibuf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.omnibuf.annotation.OmniBufField;
import com.omnibuf.annotation.OmniBufMessage;
import com.omnibuf.annotation.WireKind;
import com.omnibuf.conformance.ConformanceModels.AnnotatedLocation;
import com.omnibuf.conformance.ConformanceModels.AnnotatedNeighbor;
import com.omnibuf.conformance.ConformanceModels.AnnotatedPeerUpdate;
import com.omnibuf.pb.bench.Location;
import com.omnibuf.pb.bench.Neighbor;
import com.omnibuf.pb.bench.OmniBufLocation;
import com.omnibuf.pb.bench.OmniBufNeighbor;
import com.omnibuf.pb.bench.OmniBufPeerUpdate;
import com.omnibuf.pb.bench.PeerUpdate;
import com.omnibuf.pb.bench.PeerUpdateOmniBuf;
import com.omnibuf.wire.OmniBufEncoder;
import com.omnibuf.wire.WireType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OmniBufTiersTest {

    @OmniBufMessage(name = "TreeNode")
    public record TreeNode(
        @OmniBufField(id = 1, name = "label") String label,
        @OmniBufField(id = 2, name = "weight", wire = WireKind.SINT) int weight,
        @OmniBufField(id = 3, name = "children") List<TreeNode> children
    ) {}

    @OmniBufMessage(name = "ConfigBag")
    public static final class ConfigBag {
        @OmniBufField(id = 1, name = "service")
        public String service = "";

        @OmniBufField(id = 2, name = "labels")
        public Map<String, String> labels;

        @OmniBufField(id = 3, name = "ports")
        public List<Integer> ports = new ArrayList<>();
    }

    @Test
    void schemaMetaAndBlindJsonHandleUnsignedMaxShortestFloatsAndNonFiniteFallback() {
        SchemaMeta meta = new SchemaMeta(
            "TelemetrySample",
            Map.of(
                1, "max_u64",
                2, "min_f32",
                3, "raw_payload"
            ),
            Map.of()
        );

        OmniBufEncoder enc = new OmniBufEncoder(128);
        meta.writeField0(enc, true);
        enc.writeUint(1, 0xFFFF_FFFF_FFFF_FFFFL);
        enc.writeFloat32(2, Float.MIN_VALUE);
        enc.writeBytes(3, new byte[] {1, 2, 3});
        byte[] packet = enc.toByteArray();

        Optional<SchemaMeta> parsedMeta = SchemaMeta.readField0(packet);
        assertTrue(parsedMeta.isPresent());
        assertEquals("TelemetrySample", parsedMeta.get().typeName());
        assertEquals("max_u64", parsedMeta.get().fields().get(1));

        Map<String, Object> blind = OmniBufBlind.decodeBlind(packet);
        assertEquals(UnsignedLong.fromLongBits(0xFFFF_FFFF_FFFF_FFFFL), blind.get("max_u64"));
        assertEquals("18446744073709551615", blind.get("max_u64").toString());

        String json = OmniBufBlind.decodeBlindJson(packet);
        assertEquals(
            "{\"max_u64\":18446744073709551615,\"min_f32\":1e-45,\"raw_payload\":\"AQID\"}",
            json
        );

        // Public decodeBlindJson(byte[]) rejects NaN/Infinity by default; fallbackOnNonFinite=true returns "{}"
        OmniBufEncoder nanEnc = new OmniBufEncoder(32);
        nanEnc.writeFloat64(1, Double.NaN);
        byte[] nanPacket = nanEnc.toByteArray();
        assertThrows(OmniBufException.class, () -> OmniBufBlind.decodeBlindJson(nanPacket));
        assertThrows(OmniBufException.class, () -> OmniBufBlind.decodeBlindJson(nanPacket, false));
        assertEquals("{}", OmniBufBlind.decodeBlindJson(nanPacket, true));
    }

    @Test
    void blindDecoderRejectsField0NameCollisionAndConcatenatedShapeMismatch() {
        // Two different field numbers (#1 and #2) mapped to the same name "dup"
        SchemaMeta collidingMeta = new SchemaMeta(
            "Collide",
            Map.of(1, "dup", 2, "dup"),
            Map.of()
        );
        OmniBufEncoder cEnc = new OmniBufEncoder(64);
        collidingMeta.writeField0(cEnc, true);
        cEnc.writeUint(1, 10L);
        cEnc.writeUint(2, 20L);
        assertThrows(OmniBufException.class, () -> OmniBufBlind.decodeBlind(cEnc.toByteArray()));

        // Concatenated field #1 changing wire shape from WireUint to WireString
        OmniBufEncoder shapeEnc = new OmniBufEncoder(64);
        shapeEnc.writeUint(1, 10L);
        shapeEnc.writeString(1, "mismatch");
        assertThrows(OmniBufException.class, () -> OmniBufBlind.decodeBlind(shapeEnc.toByteArray()));

        // Unrecognized WireExtended is rejected in TIER_BLIND
        OmniBufEncoder extEnc = new OmniBufEncoder(32);
        extEnc.writeTag(5, WireType.WIRE_EXTENDED);
        extEnc.writeVarint(2L);
        extEnc.writeRawBytes(new byte[] {0x01, 0x7F});
        assertThrows(OmniBufException.class, () -> OmniBufBlind.decodeBlind(extEnc.toByteArray()));
    }

    @Test
    void mapperSupportsRecursiveJava21RecordsAndInstanceReset() {
        TreeNode root = new TreeNode(
            "root",
            -10,
            List.of(
                new TreeNode("left", 5, List.of()),
                new TreeNode("right", 12, List.of(new TreeNode("leaf", -1, List.of())))
            )
        );

        byte[] encoded = OmniBufMapper.marshal(root, MarshalOptions.SELF_DESCRIBING);
        TreeNode decoded = OmniBufMapper.unmarshal(encoded, TreeNode.class);
        assertEquals(root, decoded);

        // Verify blind JSON resolves recursive Field #0 names
        String json = OmniBufBlind.decodeBlindJson(encoded);
        assertTrue(json.contains("\"label\":\"root\""));
        assertTrue(json.contains("\"weight\":-10"));

        // Verify mutable POJO target reset clears stale fields when reusing an instance
        ConfigBag stale = new ConfigBag();
        stale.service = "stale-service";
        stale.labels = Map.of("old", "value");
        stale.ports = new ArrayList<>(List.of(8080, 9090));

        ConfigBag minimal = new ConfigBag();
        minimal.service = "fresh";
        byte[] minBytes = OmniBufMapper.marshal(minimal, MarshalOptions.DETERMINISTIC);

        OmniBufMapper.unmarshal(minBytes, stale);
        assertEquals("fresh", stale.service);
        assertTrue(stale.labels == null || stale.labels.isEmpty());
        assertTrue(stale.ports.isEmpty());
    }

    @Test
    void allThreeSchemaTiersEmitIdentical366ByteCanonicalPeerUpdate() {
        List<Integer> prefixes = List.of(
            167_772_160,
            172_032_000,
            (int) 3_232_235_520L,
            (int) 2_886_729_728L,
            134_744_072,
            134_743_040
        );

        OmniBufPeerUpdate pluginMsg = new OmniBufPeerUpdate()
            .setAsn(64512)
            .setRouterId("edge-rtr-fra01.core.backbone.net")
            .setActive(true)
            .setUptimeSec(1_849_203L)
            .setRssiDbm(-62)
            .setCpuLoad(0.42875d)
            .setTags(List.of("tier1", "ebgp", "eu-central", "ixp-de-cix"))
            .setPrefixes(prefixes)
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
                new OmniBufNeighbor().setPeerAsn(3356).setRouter("ae0.fra1.level3.net").setLatencyUs(850).setEstablished(true),
                new OmniBufNeighbor().setPeerAsn(1299).setRouter("ffm-b5-link.telia.net").setLatencyUs(920).setEstablished(true),
                new OmniBufNeighbor().setPeerAsn(174).setRouter("be2040.ccr41.fra03.atlas.cogentco.com").setLatencyUs(1120).setEstablished(true),
                new OmniBufNeighbor().setPeerAsn(2914).setRouter("ae-12.r24.frnkge08.de.bb.gin.ntt.net").setLatencyUs(980).setEstablished(false)
            ))
            .setSequence(9_876_543_210L);

        AnnotatedPeerUpdate annotatedMsg = new AnnotatedPeerUpdate();
        annotatedMsg.asn = 64512;
        annotatedMsg.routerId = "edge-rtr-fra01.core.backbone.net";
        annotatedMsg.active = true;
        annotatedMsg.uptimeSec = 1_849_203L;
        annotatedMsg.rssiDbm = -62;
        annotatedMsg.cpuLoad = 0.42875d;
        annotatedMsg.tags = List.of("tier1", "ebgp", "eu-central", "ixp-de-cix");
        annotatedMsg.prefixes = prefixes;
        annotatedMsg.location = new AnnotatedLocation(
            "eu-central-1", "FRA-Equinix-FR5", "Frankfurt", 42, 50.1109f, 8.6821f
        );
        annotatedMsg.neighbors = List.of(
            new AnnotatedNeighbor(3356, "ae0.fra1.level3.net", 850, true),
            new AnnotatedNeighbor(1299, "ffm-b5-link.telia.net", 920, true),
            new AnnotatedNeighbor(174, "be2040.ccr41.fra03.atlas.cogentco.com", 1120, true),
            new AnnotatedNeighbor(2914, "ae-12.r24.frnkge08.de.bb.gin.ntt.net", 980, false)
        );
        annotatedMsg.sequence = 9_876_543_210L;

        PeerUpdate protoMsg = PeerUpdate.newBuilder()
            .setAsn(64512)
            .setRouterId("edge-rtr-fra01.core.backbone.net")
            .setActive(true)
            .setUptimeSec(1_849_203L)
            .setRssiDbm(-62)
            .setCpuLoad(0.42875d)
            .addAllTags(List.of("tier1", "ebgp", "eu-central", "ixp-de-cix"))
            .addAllPrefixes(prefixes)
            .setLocation(
                Location.newBuilder()
                    .setRegion("eu-central-1")
                    .setDatacenter("FRA-Equinix-FR5")
                    .setCity("Frankfurt")
                    .setRack(42)
                    .setLatitude(50.1109f)
                    .setLongitude(8.6821f)
                    .build()
            )
            .addNeighbors(Neighbor.newBuilder().setPeerAsn(3356).setRouter("ae0.fra1.level3.net").setLatencyUs(850).setEstablished(true).build())
            .addNeighbors(Neighbor.newBuilder().setPeerAsn(1299).setRouter("ffm-b5-link.telia.net").setLatencyUs(920).setEstablished(true).build())
            .addNeighbors(Neighbor.newBuilder().setPeerAsn(174).setRouter("be2040.ccr41.fra03.atlas.cogentco.com").setLatencyUs(1120).setEstablished(true).build())
            .addNeighbors(Neighbor.newBuilder().setPeerAsn(2914).setRouter("ae-12.r24.frnkge08.de.bb.gin.ntt.net").setLatencyUs(980).setEstablished(false).build())
            .setSequence(9_876_543_210L)
            .build();

        byte[] bPlugin = PeerUpdateOmniBuf.marshal(pluginMsg, MarshalOptions.DETERMINISTIC);
        byte[] bAnnotated = OmniBufMapper.marshal(annotatedMsg, MarshalOptions.DETERMINISTIC);
        byte[] bProtobuf = OmniBufProtobuf.marshal(protoMsg, MarshalOptions.DETERMINISTIC);

        assertEquals(366, bPlugin.length);
        assertArrayEquals(bPlugin, bAnnotated);
        assertArrayEquals(bPlugin, bProtobuf);

        // Cross-decode between tiers
        PeerUpdate roundTripProto = OmniBufProtobuf.unmarshal(bPlugin, PeerUpdate.getDefaultInstance());
        assertEquals(protoMsg, roundTripProto);

        AnnotatedPeerUpdate roundTripAnnotated = OmniBufMapper.unmarshal(bProtobuf, AnnotatedPeerUpdate.class);
        assertNotNull(roundTripAnnotated.location);
        assertEquals("Frankfurt", roundTripAnnotated.location.city());
        assertEquals(9_876_543_210L, roundTripAnnotated.sequence);

        OmniBufPeerUpdate roundTripPlugin = PeerUpdateOmniBuf.unmarshal(
            new String(bAnnotated, StandardCharsets.ISO_8859_1).getBytes(StandardCharsets.ISO_8859_1)
        );
        assertEquals("edge-rtr-fra01.core.backbone.net", roundTripPlugin.getRouterId());
        assertEquals(4, roundTripPlugin.getNeighbors().size());
    }
}
