package com.omnibuf.connect;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.omnibuf.OmniBufException;
import com.omnibuf.SchemaMeta;
import com.omnibuf.UnmarshalOptions;
import com.omnibuf.annotation.OmniBufField;
import com.omnibuf.annotation.OmniBufMessage;
import com.omnibuf.annotation.WireKind;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConnectCodecTest {

    @OmniBufMessage(name = "ConnectPeerEvent")
    public record ConnectPeerEvent(
        @OmniBufField(id = 1, name = "asn") int asn,
        @OmniBufField(id = 2, name = "router_id") String routerId,
        @OmniBufField(id = 3, name = "rssi_dbm", wire = WireKind.SINT) int rssiDbm,
        @OmniBufField(id = 4, name = "tags") List<String> tags,
        @OmniBufField(id = 5, name = "labels") Map<String, String> labels
    ) {}

    @Test
    void constantsAndEncodingNames() {
        assertEquals("omnibuf", OmniBufConnectCodec.CODEC_NAME);
        assertEquals("x-omnibuf", OmniBufConnectCodec.SELF_DESCRIBING_CODEC_NAME);

        assertEquals("omnibuf", OmniBufConnectCodec.compact().encodingName());
        assertEquals("x-omnibuf", OmniBufConnectCodec.selfDescribing().encodingName());
        assertEquals("omnibuf", OmniBufConnectCodec.deterministic().encodingName());
    }

    @Test
    void compactAndSelfDescribingRoundTrip() {
        OmniBufConnectCodec compact = OmniBufConnectCodec.compact();
        ConnectPeerEvent original = new ConnectPeerEvent(
            64512,
            "edge-rtr-fra01",
            -62,
            List.of("bgp", "ipv6"),
            Map.of("site", "fra")
        );

        byte[] wire = compact.serialize(original);
        assertFalse(SchemaMeta.readField0(wire).isPresent());
        assertEquals(original, compact.deserialize(wire, ConnectPeerEvent.class));

        OmniBufConnectCodec sd = OmniBufConnectCodec.selfDescribing().withDeterministic(true);
        Map<String, String> orderA = new LinkedHashMap<>();
        orderA.put("z", "1");
        orderA.put("a", "2");
        Map<String, String> orderB = new LinkedHashMap<>();
        orderB.put("a", "2");
        orderB.put("z", "1");

        byte[] sdA = sd.serialize(new ConnectPeerEvent(100, "iad-01", -40, List.of(), orderA));
        byte[] sdB = sd.serialize(new ConnectPeerEvent(100, "iad-01", -40, List.of(), orderB));
        assertArrayEquals(sdA, sdB);

        Optional<SchemaMeta> meta = SchemaMeta.readField0(sdA);
        assertTrue(meta.isPresent());
        assertEquals("ConnectPeerEvent", meta.get().typeName());
        assertEquals("asn", meta.get().fields().get(1));
        assertEquals(orderB, sd.deserialize(sdA, ConnectPeerEvent.class).labels());
    }

    @Test
    void protobufAndLimits() {
        OmniBufConnectCodec codec = OmniBufConnectCodec.compact();
        FieldDescriptorProto original = FieldDescriptorProto.newBuilder()
            .setName("router_id")
            .setNumber(2)
            .setType(FieldDescriptorProto.Type.TYPE_STRING)
            .build();

        byte[] wire = codec.serialize(original);
        assertEquals(original, codec.deserialize(wire, FieldDescriptorProto.class));
        assertEquals(original, codec.deserialize(wire, FieldDescriptorProto.getDefaultInstance()));

        OmniBufConnectCodec tight = codec.withUnmarshalOptions(
            UnmarshalOptions.DEFAULT.withMaxMessageBytes(4)
        );
        assertThrows(OmniBufException.class, () -> tight.deserialize(wire, FieldDescriptorProto.class));
        assertThrows(OmniBufException.class, () -> tight.serialize(null));
        assertThrows(OmniBufException.class, () -> tight.deserialize(null, ConnectPeerEvent.class));
    }
}
