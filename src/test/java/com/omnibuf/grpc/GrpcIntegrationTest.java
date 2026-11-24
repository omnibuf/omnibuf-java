package com.omnibuf.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.omnibuf.MarshalOptions;
import com.omnibuf.OmniBufException;
import com.omnibuf.SchemaMeta;
import com.omnibuf.UnmarshalOptions;
import com.omnibuf.annotation.OmniBufField;
import com.omnibuf.annotation.OmniBufMessage;
import com.omnibuf.annotation.WireKind;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class GrpcIntegrationTest {

    @OmniBufMessage(name = "PeerPingRequest")
    public record PeerPingRequest(
        @OmniBufField(id = 1, name = "asn") int asn,
        @OmniBufField(id = 2, name = "router_id") String routerId,
        @OmniBufField(id = 3, name = "rssi_dbm", wire = WireKind.SINT) int rssiDbm,
        @OmniBufField(id = 4, name = "tags") List<String> tags
    ) {}

    @OmniBufMessage(name = "PeerPingResponse")
    public record PeerPingResponse(
        @OmniBufField(id = 1, name = "accepted") boolean accepted,
        @OmniBufField(id = 2, name = "echo_router") String echoRouter,
        @OmniBufField(id = 3, name = "adjusted_dbm", wire = WireKind.SINT) int adjustedDbm,
        @OmniBufField(id = 4, name = "labels") Map<String, String> labels
    ) {}

    @Test
    void constantsAndCodecNames() {
        assertEquals("omnibuf", OmniBufGrpcMarshaller.CODEC_NAME);
        assertEquals("x-omnibuf", OmniBufGrpcMarshaller.SELF_DESCRIBING_CODEC_NAME);
        assertEquals("application/grpc+omnibuf", OmniBufGrpcMarshaller.CONTENT_TYPE_GRPC);

        OmniBufGrpcMarshaller<PeerPingRequest> compact =
            OmniBufGrpcMarshaller.forClass(PeerPingRequest.class);
        assertEquals("omnibuf", compact.codecName());
        assertEquals("x-omnibuf", compact.withSelfDescribing(true).codecName());
    }

    @Test
    void inProcessUnaryRpcWithAnnotatedRecords() throws Exception {
        MethodDescriptor<PeerPingRequest, PeerPingResponse> pingMethod =
            MethodDescriptor.<PeerPingRequest, PeerPingResponse>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName("omnibuf.test.PeerService/Ping")
                .setRequestMarshaller(OmniBufGrpcMarshaller.forClass(PeerPingRequest.class))
                .setResponseMarshaller(
                    OmniBufGrpcMarshaller.forClass(PeerPingResponse.class)
                        .withSelfDescribing(true)
                        .withDeterministic(true)
                )
                .build();

        String serverName = InProcessServerBuilder.generateName();
        ServerServiceDefinition service = ServerServiceDefinition.builder("omnibuf.test.PeerService")
            .addMethod(
                pingMethod,
                ServerCalls.asyncUnaryCall((request, responseObserver) -> {
                    PeerPingResponse reply = new PeerPingResponse(
                        request.asn() == 64512,
                        request.routerId() + ":ack",
                        request.rssiDbm() + 3,
                        Map.of("site", "fra", "tags", String.join(",", request.tags()))
                    );
                    responseObserver.onNext(reply);
                    responseObserver.onCompleted();
                })
            )
            .build();

        Server server = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(service)
            .build()
            .start();
        ManagedChannel channel = InProcessChannelBuilder.forName(serverName)
            .directExecutor()
            .build();
        try {
            PeerPingRequest req = new PeerPingRequest(64512, "edge-rtr-fra01", -62, List.of("bgp", "ipv6"));
            PeerPingResponse resp = ClientCalls.blockingUnaryCall(
                channel,
                pingMethod,
                io.grpc.CallOptions.DEFAULT,
                req
            );
            assertTrue(resp.accepted());
            assertEquals("edge-rtr-fra01:ack", resp.echoRouter());
            assertEquals(-59, resp.adjustedDbm());
            assertEquals("fra", resp.labels().get("site"));
            assertEquals("bgp,ipv6", resp.labels().get("tags"));
        } finally {
            channel.shutdownNow();
            server.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void inProcessUnaryRpcWithProtobufMessages() throws Exception {
        MethodDescriptor<FieldDescriptorProto, FieldDescriptorProto> echoMethod =
            MethodDescriptor.<FieldDescriptorProto, FieldDescriptorProto>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName("omnibuf.test.ProtoService/Echo")
                .setRequestMarshaller(
                    OmniBufGrpcMarshaller.forProtobuf(FieldDescriptorProto.getDefaultInstance())
                )
                .setResponseMarshaller(
                    OmniBufGrpcMarshaller.forClass(FieldDescriptorProto.class)
                )
                .build();

        String serverName = InProcessServerBuilder.generateName();
        ServerServiceDefinition service = ServerServiceDefinition.builder("omnibuf.test.ProtoService")
            .addMethod(
                echoMethod,
                ServerCalls.asyncUnaryCall((request, responseObserver) -> {
                    FieldDescriptorProto reply = request.toBuilder()
                        .setName(request.getName() + "_v2")
                        .build();
                    responseObserver.onNext(reply);
                    responseObserver.onCompleted();
                })
            )
            .build();

        Server server = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(service)
            .build()
            .start();
        ManagedChannel channel = InProcessChannelBuilder.forName(serverName)
            .directExecutor()
            .build();
        try {
            FieldDescriptorProto req = FieldDescriptorProto.newBuilder()
                .setName("router_id")
                .setNumber(2)
                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                .build();
            FieldDescriptorProto resp = ClientCalls.blockingUnaryCall(
                channel,
                echoMethod,
                io.grpc.CallOptions.DEFAULT,
                req
            );
            assertEquals("router_id_v2", resp.getName());
            assertEquals(2, resp.getNumber());
            assertEquals(FieldDescriptorProto.Type.TYPE_STRING, resp.getType());
        } finally {
            channel.shutdownNow();
            server.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void selfDescribingMetadataAndLimits() throws Exception {
        OmniBufGrpcMarshaller<PeerPingRequest> sd = OmniBufGrpcMarshaller.forClass(
            PeerPingRequest.class,
            MarshalOptions.DETERMINISTIC_SELF_DESCRIBING,
            UnmarshalOptions.DEFAULT.withMaxMessageBytes(12)
        );

        PeerPingRequest req = new PeerPingRequest(64512, "edge-rtr-fra01-long", -62, List.of("bgp"));
        byte[] wire;
        try (InputStream in = sd.stream(req)) {
            wire = in.readAllBytes();
        }
        Optional<SchemaMeta> meta = SchemaMeta.readField0(wire);
        assertTrue(meta.isPresent());
        assertEquals("PeerPingRequest", meta.get().typeName());

        assertThrows(OmniBufException.class, () -> sd.parse(new ByteArrayInputStream(wire)));
        assertThrows(OmniBufException.class, () -> sd.stream(null));
        assertThrows(OmniBufException.class, () -> sd.parse(null));
    }
}
