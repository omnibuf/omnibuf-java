package com.omnibuf.conformance;

import com.google.protobuf.ByteString;
import com.omnibuf.MarshalOptions;
import com.omnibuf.OmniBufBlind;
import com.omnibuf.OmniBufException;
import com.omnibuf.OmniBufMapper;
import com.omnibuf.OmniBufProtobuf;
import com.omnibuf.annotation.OmniBufField;
import com.omnibuf.annotation.OmniBufMessage;
import com.omnibuf.conformance.ConformanceModels.AnnotatedAllTypesSubMsg;
import com.omnibuf.conformance.ConformanceModels.AnnotatedLocation;
import com.omnibuf.conformance.ConformanceModels.AnnotatedNeighbor;
import com.omnibuf.conformance.ConformanceModels.AnnotatedPeerUpdate;
import com.omnibuf.conformance.ConformanceModels.AnnotatedTestAllTypes;
import com.omnibuf.conformance.ConformanceModels.NestedCluster;
import com.omnibuf.conformance.ConformanceModels.TreeNode;
import com.omnibuf.pb.bench.Location;
import com.omnibuf.pb.bench.Neighbor;
import com.omnibuf.pb.bench.OmniBufLocation;
import com.omnibuf.pb.bench.OmniBufNeighbor;
import com.omnibuf.pb.bench.OmniBufPeerUpdate;
import com.omnibuf.pb.bench.OpaqueLocation;
import com.omnibuf.pb.bench.OpaquePeerUpdate;
import com.omnibuf.pb.bench.PeerUpdate;
import com.omnibuf.pb.bench.PeerUpdateOmniBuf;
import com.omnibuf.pb.conformance.AllTypesSubMsg;
import com.omnibuf.pb.conformance.OmniBufAllTypesSubMsg;
import com.omnibuf.pb.conformance.OmniBufTestAllTypes;
import com.omnibuf.pb.conformance.OpaqueAllTypesSubMsg;
import com.omnibuf.pb.conformance.OpaqueTestAllTypes;
import com.omnibuf.pb.conformance.TestAllTypes;
import com.omnibuf.pb.conformance.TestAllTypesOmniBuf;
import com.omnibuf.wire.OmniBufEncoder;
import com.omnibuf.wire.OmniBufOpaque;
import com.omnibuf.wire.PrefixVarint;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cross-language OmniBuf conformance adapter communicating over 4-byte little-endian
 * length-prefixed {@code System.in}/{@code System.out} frames across all 4 tiers.
 */
public final class ConformanceMain {

    private static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;

    private static final int WIRE_FORMAT_JSON = 2;

    private static final int TIER_PLUGIN = 1;
    private static final int TIER_PROTOBUF_TYPES = 2;
    private static final int TIER_ANNOTATED_STRUCT = 3;
    private static final int TIER_BLIND = 4;

    @OmniBufMessage(name = "ConformanceRequest")
    public static final class ConformanceRequestFrame {
        @OmniBufField(id = 1, name = "test_name")
        public String testName = "";

        @OmniBufField(id = 2, name = "message_type")
        public String messageType = "";

        @OmniBufField(id = 3, name = "tier")
        public int tier;

        @OmniBufField(id = 4, name = "requested_output_format")
        public int requestedOutputFormat;

        @OmniBufField(id = 5, name = "omnibuf_payload")
        public byte[] omnibufPayload = new byte[0];

        @OmniBufField(id = 6, name = "json_payload")
        public String jsonPayload = "";

        @OmniBufField(id = 7, name = "self_describing")
        public boolean selfDescribing;

        @OmniBufField(id = 8, name = "test_opaque_splice")
        public boolean testOpaqueSplice;

        @OmniBufField(id = 9, name = "test_opaque_splice_shrink")
        public boolean testOpaqueSpliceShrink;

        public String getMessageType() { return messageType; }
        public int getTier() { return tier; }
        public int getRequestedOutputFormat() { return requestedOutputFormat; }
        public byte[] getOmnibufPayload() { return omnibufPayload; }
        public boolean getSelfDescribing() { return selfDescribing; }
        public boolean getTestOpaqueSplice() { return testOpaqueSplice; }
        public boolean getTestOpaqueSpliceShrink() { return testOpaqueSpliceShrink; }
    }

    @OmniBufMessage(name = "ConformanceResponse")
    public static final class ConformanceResponseFrame {
        @OmniBufField(id = 1, name = "parse_error")
        public String parseError = "";

        @OmniBufField(id = 2, name = "serialize_error")
        public String serializeError = "";

        @OmniBufField(id = 3, name = "runtime_error")
        public String runtimeError = "";

        @OmniBufField(id = 4, name = "skipped")
        public String skipped = "";

        @OmniBufField(id = 5, name = "omnibuf_payload")
        public byte[] omnibufPayload = new byte[0];

        @OmniBufField(id = 6, name = "json_payload")
        public String jsonPayload = "";

        public void setParseError(String v) { this.parseError = v == null ? "" : v; }
        public void setSerializeError(String v) { this.serializeError = v == null ? "" : v; }
        public void setRuntimeError(String v) { this.runtimeError = v == null ? "" : v; }
        public void setSkipped(String v) { this.skipped = v == null ? "" : v; }
        public void setOmnibufPayload(byte[] v) { this.omnibufPayload = v == null ? new byte[0] : v; }
        public void setJsonPayload(String v) { this.jsonPayload = v == null ? "" : v; }
    }

    private ConformanceMain() {}

    public static void main(String[] args) throws IOException {
        BufferedInputStream in = new BufferedInputStream(System.in, 65536);
        BufferedOutputStream out = new BufferedOutputStream(System.out, 65536);
        byte[] lenBuf = new byte[4];
        ConformanceRequestFrame req = new ConformanceRequestFrame();

        while (true) {
            int first = in.read();
            if (first < 0) {
                return; // Clean EOF
            }
            lenBuf[0] = (byte) first;
            readFully(in, lenBuf, 1, 3);
            int frameLen = PrefixVarint.getIntLE(lenBuf, 0);
            if (frameLen < 0 || frameLen > MAX_FRAME_BYTES) {
                throw new IOException("omnibuf: invalid conformance frame length: " + frameLen);
            }

            byte[] reqBytes = new byte[frameLen];
            readFully(in, reqBytes, 0, frameLen);

            OmniBufMapper.unmarshal(reqBytes, req);
            ConformanceResponseFrame resp = handleRequest(req);
            byte[] respBytes = OmniBufMapper.marshal(resp);

            PrefixVarint.setIntLE(lenBuf, 0, respBytes.length);
            out.write(lenBuf, 0, 4);
            out.write(respBytes);
            out.flush();
        }
    }

    private static void readFully(InputStream in, byte[] dst, int off, int len) throws IOException {
        int read = 0;
        while (read < len) {
            int n = in.read(dst, off + read, len - read);
            if (n < 0) {
                throw new EOFException("omnibuf: truncated conformance frame");
            }
            read += n;
        }
    }

    public static ConformanceResponseFrame handleRequest(ConformanceRequestFrame req) {
        ConformanceResponseFrame resp = new ConformanceResponseFrame();
        try {
            byte[] payload = req.getOmnibufPayload();
            switch (req.getTier()) {
                case TIER_BLIND -> handleBlind(req, payload, resp);
                case TIER_PLUGIN -> handlePlugin(req, payload, resp);
                case TIER_PROTOBUF_TYPES -> handleProtobufTypes(req, payload, resp);
                case TIER_ANNOTATED_STRUCT -> handleAnnotatedStruct(req, payload, resp);
                default -> resp.setRuntimeError("unsupported tier: " + req.getTier());
            }
        } catch (Throwable t) {
            resp.setRuntimeError(t.getMessage() != null ? t.getMessage() : t.toString());
        }
        return resp;
    }

    private static void handleBlind(
            ConformanceRequestFrame req, byte[] payload, ConformanceResponseFrame resp) {
        try {
            OmniBufBlind.decodeBlind(payload);
        } catch (OmniBufException e) {
            resp.setParseError(e.getMessage());
            return;
        }
        if (req.getRequestedOutputFormat() == WIRE_FORMAT_JSON) {
            resp.setJsonPayload(OmniBufBlind.decodeBlindJson(payload, true));
        }
    }

    private static void handlePlugin(
            ConformanceRequestFrame req, byte[] payload, ConformanceResponseFrame resp) {
        if (req.getTestOpaqueSplice() || req.getTestOpaqueSpliceShrink()) {
            try {
                checkSpliceAliasedPrefix(payload);
            } catch (RuntimeException e) {
                resp.setRuntimeError(e.getMessage());
                return;
            }
        }

        switch (req.getMessageType()) {
            case "omnibuf.bench.PeerUpdate" -> handlePluginPeerUpdate(req, payload, resp);
            case "omnibuf.conformance.TestAllTypes" -> handlePluginTestAllTypes(req, payload, resp);
            default -> resp.setSkipped("plugin tier generated for PeerUpdate and TestAllTypes");
        }
    }

    private static void handlePluginPeerUpdate(
            ConformanceRequestFrame req, byte[] payload, ConformanceResponseFrame resp) {
        // Pre-populate stale values to verify unmarshal resets absent fields cleanly.
        OmniBufPeerUpdate msg = new OmniBufPeerUpdate()
            .setAsn(999999)
            .setRouterId("stale-router")
            .setActive(true)
            .setTags(List.of("stale-tag"))
            .setPrefixes(List.of(999))
            .setLocation(new OmniBufLocation().setRegion("stale").setCity("stale-city").setRack(999))
            .setNeighbors(List.of(new OmniBufNeighbor().setPeerAsn(999).setRouter("stale-neighbor")))
            .setSequence(999999L);

        try {
            PeerUpdateOmniBuf.unmarshal(payload, msg);
        } catch (OmniBufException e) {
            resp.setParseError(e.getMessage());
            return;
        }

        OpaquePeerUpdate op = new OpaquePeerUpdate(payload);
        OpaqueLocation opLoc = op.getLocation();
        OmniBufLocation wantLoc = msg.getLocation();
        String gotCity = opLoc == null ? "" : opLoc.getCity();
        String wantCity = wantLoc == null ? "" : wantLoc.getCity();

        if (op.getAsn() != msg.getAsn()
                || !op.getRouterId().equals(msg.getRouterId())
                || op.getActive() != msg.getActive()
                || op.getUptimeSec() != msg.getUptimeSec()
                || op.getRssiDbm() != msg.getRssiDbm()
                || Double.doubleToRawLongBits(op.getCpuLoad()) != Double.doubleToRawLongBits(msg.getCpuLoad())
                || op.getTags().size() != msg.getTags().size()
                || op.getPrefixes().size() != msg.getPrefixes().size()
                || !gotCity.equals(wantCity)
                || op.getNeighbors().size() != msg.getNeighbors().size()
                || op.getSequence() != msg.getSequence()) {
            resp.setParseError("opaque PeerUpdate getter mismatch with decoded struct");
            return;
        }

        if (req.getTestOpaqueSplice()) {
            try {
                op.setAsn(1_000_000);
                op.setRouterId("edge-rtr-fra01-".repeat(20));
                op.setActive(!op.getActive());
                op.setUptimeSec(0xFFFF_FFFF_FFFF_FFFFL);
                op.setRssiDbm(-1_000_000);
                op.setCpuLoad(99.875);
                op.setTags(List.of("tag-grow-a", "tag-grow-b", "long-tag-".repeat(20)));
                op.setPrefixes(List.of(100000, 200000, 300000, 400000, 500000, 600000));
                op.setLocation(new OmniBufLocation()
                    .setRegion("eu-central-grown")
                    .setDatacenter("fra-dc-".repeat(25))
                    .setCity("Berlin")
                    .setRack(999999)
                    .setLatitude(52.52f)
                    .setLongitude(13.405f));
                op.setSequence(op.getSequence() + 1L);
                resp.setOmnibufPayload(op.marshal());
            } catch (OmniBufException e) {
                resp.setSerializeError(e.getMessage());
            }
            return;
        }

        if (req.getTestOpaqueSpliceShrink()) {
            try {
                op.setAsn(1);
                op.setRouterId("r");
                op.setActive(false);
                op.setUptimeSec(5L);
                op.setRssiDbm(-1);
                op.setCpuLoad(0.5);
                op.setTags(List.of("t"));
                op.setPrefixes(List.of(1));
                op.setLocation(new OmniBufLocation()
                    .setRegion("r")
                    .setDatacenter("d")
                    .setCity("c")
                    .setRack(1)
                    .setLatitude(1.0f)
                    .setLongitude(2.0f));
                op.setSequence(2L);
                resp.setOmnibufPayload(op.marshal());
            } catch (OmniBufException e) {
                resp.setSerializeError(e.getMessage());
            }
            return;
        }

        try {
            resp.setOmnibufPayload(
                PeerUpdateOmniBuf.marshal(msg, MarshalOptions.of(true, req.getSelfDescribing()))
            );
        } catch (OmniBufException e) {
            resp.setSerializeError(e.getMessage());
        }
    }

    private static void handlePluginTestAllTypes(
            ConformanceRequestFrame req, byte[] payload, ConformanceResponseFrame resp) {
        Map<String, Long> staleMap = new LinkedHashMap<>();
        staleMap.put("stale", 999L);

        OmniBufTestAllTypes msg = new OmniBufTestAllTypes()
            .setF1Uint32(999999)
            .setF5Bool(true)
            .setF6String("stale-str")
            .setF7Bytes(new byte[] {(byte) 0xFF})
            .setF8SubMsg(new OmniBufAllTypesSubMsg()
                .setId(999)
                .setLabel("stale-sub")
                .setFlag(true)
                .setScore(-999L))
            .setF9StringArr(List.of("stale"))
            .setF10Uint32Arr(List.of(999))
            .setF11SubMsgArr(List.of(new OmniBufAllTypesSubMsg().setId(999).setLabel("stale-elem")))
            .setF12MapStrUint(staleMap)
            .setF20OverflowStr("stale-overflow");

        try {
            TestAllTypesOmniBuf.unmarshal(payload, msg);
        } catch (OmniBufException e) {
            resp.setParseError(e.getMessage());
            return;
        }

        OpaqueTestAllTypes op = new OpaqueTestAllTypes(payload);
        op.getF12MapStrUint();
        OpaqueAllTypesSubMsg opSub = op.getF8SubMsg();
        OmniBufAllTypesSubMsg wantSub = msg.getF8SubMsg();

        int opSubId = opSub == null ? 0 : opSub.getId();
        int wantSubId = wantSub == null ? 0 : wantSub.getId();
        String opSubLabel = opSub == null ? "" : opSub.getLabel();
        String wantSubLabel = wantSub == null ? "" : wantSub.getLabel();
        boolean opSubFlag = opSub != null && opSub.getFlag();
        boolean wantSubFlag = wantSub != null && wantSub.getFlag();
        long opSubScore = opSub == null ? 0L : opSub.getScore();
        long wantSubScore = wantSub == null ? 0L : wantSub.getScore();

        if (op.getF1Uint32() != msg.getF1Uint32()
                || op.getF2Uint64() != msg.getF2Uint64()
                || op.getF3Sint32() != msg.getF3Sint32()
                || op.getF4Sint64() != msg.getF4Sint64()
                || op.getF5Bool() != msg.getF5Bool()
                || !op.getF6String().equals(msg.getF6String())
                || !Arrays.equals(op.getF7Bytes(), msg.getF7Bytes())
                || opSubId != wantSubId
                || !opSubLabel.equals(wantSubLabel)
                || opSubFlag != wantSubFlag
                || opSubScore != wantSubScore
                || op.getF9StringArr().size() != msg.getF9StringArr().size()
                || op.getF10Uint32Arr().size() != msg.getF10Uint32Arr().size()
                || op.getF11SubMsgArr().size() != msg.getF11SubMsgArr().size()
                || Float.floatToRawIntBits(op.getF13Float32()) != Float.floatToRawIntBits(msg.getF13Float32())
                || Double.doubleToRawLongBits(op.getF14Float64()) != Double.doubleToRawLongBits(msg.getF14Float64())
                || op.getF15Fixed32() != msg.getF15Fixed32()
                || op.getF16Fixed64() != msg.getF16Fixed64()
                || !op.getF20OverflowStr().equals(msg.getF20OverflowStr())) {
            resp.setParseError("opaque TestAllTypes getter mismatch with decoded struct");
            return;
        }

        if (req.getTestOpaqueSplice()) {
            try {
                op.setF1Uint32(1_000_000);
                op.setF2Uint64(0xFFFF_FFFF_FFFF_FFFFL);
                op.setF3Sint32(-1_000_000);
                op.setF4Sint64(Long.MIN_VALUE);
                op.setF5Bool(!op.getF5Bool());
                op.setF6String("grown-str-".repeat(30));
                byte[] f7 = new byte[260];
                for (int i = 0; i < 260; i += 2) {
                    f7[i] = (byte) 0xAB;
                    f7[i + 1] = (byte) 0xCD;
                }
                op.setF7Bytes(f7);
                op.setF8SubMsg(new OmniBufAllTypesSubMsg()
                    .setId(888888)
                    .setLabel("sub-grown-".repeat(20))
                    .setFlag(true)
                    .setScore(-999999999L));
                op.setF9StringArr(List.of("arr-grow-1", "arr-grow-2", "arr-grow-3-".repeat(15)));
                op.setF10Uint32Arr(List.of(100000, 200000, 300000, 400000, 500000));
                op.setF11SubMsgArr(List.of(
                    new OmniBufAllTypesSubMsg()
                        .setId(100001)
                        .setLabel("elem0-".repeat(25))
                        .setFlag(true)
                        .setScore(-500000L),
                    new OmniBufAllTypesSubMsg()
                        .setId(100002)
                        .setLabel("elem1-grown")
                        .setFlag(false)
                        .setScore(500000L)
                ));
                Map<String, Long> grownMap = new LinkedHashMap<>();
                grownMap.put("k1", 1000000L);
                grownMap.put("k2", 0xFFFF_FFFF_FFFF_FFFFL);
                grownMap.put("k3_long_key", 424242L);
                op.setF12MapStrUint(grownMap);
                op.setF13Float32(-1234.5f);
                op.setF14Float64(3.141592653589793d);
                op.setF15Fixed32(0xDEADBEEF);
                op.setF16Fixed64(0xCAFEBABEDEADBEEFL);
                op.setF20OverflowStr("overflow20-".repeat(20));
                resp.setOmnibufPayload(op.marshal());
            } catch (OmniBufException e) {
                resp.setSerializeError(e.getMessage());
            }
            return;
        }

        if (req.getTestOpaqueSpliceShrink()) {
            try {
                op.setF1Uint32(1);
                op.setF2Uint64(2L);
                op.setF3Sint32(-1);
                op.setF4Sint64(-2L);
                op.setF5Bool(false);
                op.setF6String("s");
                op.setF7Bytes(new byte[] {0x01});
                op.setF8SubMsg(new OmniBufAllTypesSubMsg()
                    .setId(1)
                    .setLabel("x")
                    .setFlag(false)
                    .setScore(-1L));
                op.setF9StringArr(List.of("a"));
                op.setF10Uint32Arr(List.of(7));
                op.setF11SubMsgArr(List.of(
                    new OmniBufAllTypesSubMsg()
                        .setId(1)
                        .setLabel("e")
                        .setFlag(true)
                        .setScore(1L)
                ));
                Map<String, Long> shrunkMap = new LinkedHashMap<>();
                shrunkMap.put("k", 1L);
                op.setF12MapStrUint(shrunkMap);
                op.setF13Float32(0.5f);
                op.setF14Float64(-0.25d);
                op.setF15Fixed32(7);
                op.setF16Fixed64(9L);
                op.setF20OverflowStr("z");
                resp.setOmnibufPayload(op.marshal());
            } catch (OmniBufException e) {
                resp.setSerializeError(e.getMessage());
            }
            return;
        }

        try {
            resp.setOmnibufPayload(
                TestAllTypesOmniBuf.marshal(msg, MarshalOptions.of(true, req.getSelfDescribing()))
            );
        } catch (OmniBufException e) {
            resp.setSerializeError(e.getMessage());
        }
    }

    private static void checkSpliceAliasedPrefix(byte[] packet) {
        OmniBufOpaque.DirtyFieldWriter appendDirty = (enc, fnum) -> enc.writeUint(fnum, 1L << 40);
        byte[] spliced = OmniBufOpaque.spliceFields(packet, 1L << 1, 2, appendDirty);

        for (int prefixLen : new int[] {0, 3, 32}) {
            byte[] prefix = new byte[prefixLen];
            Arrays.fill(prefix, (byte) 0xA5);

            byte[] storage = new byte[prefixLen + packet.length + 512];
            System.arraycopy(prefix, 0, storage, 0, prefixLen);
            System.arraycopy(packet, 0, storage, prefixLen, packet.length);

            byte[] want = new byte[prefixLen + spliced.length];
            System.arraycopy(prefix, 0, want, 0, prefixLen);
            System.arraycopy(spliced, 0, want, prefixLen, spliced.length);

            OmniBufEncoder aliasedTarget = new OmniBufEncoder(storage, prefixLen);
            byte[] got = OmniBufOpaque.spliceFields(
                storage, prefixLen, packet.length, 1L << 1, 2, appendDirty, aliasedTarget
            );
            if (!Arrays.equals(got, want)) {
                throw new IllegalStateException(
                    "opaque splice with " + prefixLen + "-byte aliased prefix changed prefix or output"
                );
            }
            byte[] rawAfter = Arrays.copyOfRange(storage, prefixLen, prefixLen + packet.length);
            if (!Arrays.equals(rawAfter, packet)) {
                throw new IllegalStateException(
                    "opaque splice with " + prefixLen + "-byte aliased prefix modified source packet"
                );
            }
        }
    }

    private static void handleProtobufTypes(
            ConformanceRequestFrame req, byte[] payload, ConformanceResponseFrame resp) {
        MarshalOptions opts = MarshalOptions.of(true, req.getSelfDescribing());
        switch (req.getMessageType()) {
            case "omnibuf.bench.PeerUpdate" -> {
                PeerUpdate.Builder builder = PeerUpdate.newBuilder()
                    .setAsn(999999)
                    .setRouterId("stale-router")
                    .setActive(true)
                    .addTags("stale-tag")
                    .addPrefixes(999)
                    .setLocation(Location.newBuilder().setCity("stale-city").build())
                    .addNeighbors(Neighbor.newBuilder().setPeerAsn(999).build())
                    .setSequence(999999L);
                try {
                    OmniBufProtobuf.unmarshal(payload, builder);
                } catch (OmniBufException e) {
                    resp.setParseError(e.getMessage());
                    return;
                }
                try {
                    resp.setOmnibufPayload(OmniBufProtobuf.marshal(builder.build(), opts));
                } catch (OmniBufException e) {
                    resp.setSerializeError(e.getMessage());
                }
            }
            case "omnibuf.conformance.TestAllTypes" -> {
                TestAllTypes.Builder builder = TestAllTypes.newBuilder()
                    .setF1Uint32(999999)
                    .setF5Bool(true)
                    .setF6String("stale-str")
                    .setF7Bytes(ByteString.copyFrom(new byte[] {(byte) 0xFF}))
                    .setF8SubMsg(AllTypesSubMsg.newBuilder().setId(999).setLabel("stale").build())
                    .addF9StringArr("stale")
                    .addF10Uint32Arr(999)
                    .addF11SubMsgArr(AllTypesSubMsg.newBuilder().setId(999).build())
                    .putF12MapStrUint("stale", 999L)
                    .setF20OverflowStr("stale-overflow");
                try {
                    OmniBufProtobuf.unmarshal(payload, builder);
                } catch (OmniBufException e) {
                    resp.setParseError(e.getMessage());
                    return;
                }
                try {
                    resp.setOmnibufPayload(OmniBufProtobuf.marshal(builder.build(), opts));
                } catch (OmniBufException e) {
                    resp.setSerializeError(e.getMessage());
                }
            }
            default -> resp.setSkipped("protobuf types tier exercises PeerUpdate and TestAllTypes");
        }
    }

    private static void handleAnnotatedStruct(
            ConformanceRequestFrame req, byte[] payload, ConformanceResponseFrame resp) {
        MarshalOptions opts = MarshalOptions.of(true, req.getSelfDescribing());
        switch (req.getMessageType()) {
            case "omnibuf.bench.PeerUpdate" -> {
                AnnotatedPeerUpdate msg = new AnnotatedPeerUpdate();
                msg.asn = 999999;
                msg.routerId = "stale-router";
                msg.active = true;
                msg.tags.add("stale-tag");
                msg.prefixes.add(999);
                msg.location = new AnnotatedLocation("stale", "stale", "stale-city", 999, 1.0f, 2.0f);
                msg.neighbors.add(new AnnotatedNeighbor(999, "stale-neighbor", 10, true));
                msg.sequence = 999999L;
                try {
                    OmniBufMapper.unmarshal(payload, msg);
                } catch (OmniBufException e) {
                    resp.setParseError(e.getMessage());
                    return;
                }
                try {
                    resp.setOmnibufPayload(OmniBufMapper.marshal(msg, opts));
                } catch (OmniBufException e) {
                    resp.setSerializeError(e.getMessage());
                }
            }
            case "omnibuf.conformance.TreeNode" -> {
                TreeNode msg = new TreeNode();
                msg.id = 999;
                msg.name = "stale";
                msg.primary = new TreeNode();
                msg.children.add(new TreeNode());
                msg.labels.put("stale", 999L);
                try {
                    OmniBufMapper.unmarshal(payload, msg);
                } catch (OmniBufException e) {
                    resp.setParseError(e.getMessage());
                    return;
                }
                try {
                    resp.setOmnibufPayload(OmniBufMapper.marshal(msg, opts));
                } catch (OmniBufException e) {
                    resp.setSerializeError(e.getMessage());
                }
            }
            case "omnibuf.conformance.NestedCluster" -> {
                NestedCluster msg;
                try {
                    msg = OmniBufMapper.unmarshal(payload, NestedCluster.class);
                } catch (OmniBufException e) {
                    resp.setParseError(e.getMessage());
                    return;
                }
                try {
                    resp.setOmnibufPayload(OmniBufMapper.marshal(msg, opts));
                } catch (OmniBufException e) {
                    resp.setSerializeError(e.getMessage());
                }
            }
            default -> {
                AnnotatedTestAllTypes msg = new AnnotatedTestAllTypes();
                msg.f1Uint32 = 999999;
                msg.f5Bool = true;
                msg.f6String = "stale-str";
                msg.f7Bytes = new byte[] {(byte) 0xFF};
                msg.f8SubMsg = new AnnotatedAllTypesSubMsg(999, "stale-sub", true, -999L);
                msg.f9StringArr.add("stale");
                msg.f10Uint32Arr.add(999);
                msg.f11SubMsgArr.add(new AnnotatedAllTypesSubMsg(999, "stale-elem", false, 0L));
                msg.f12MapStrUint.put("stale", 999L);
                msg.f20OverflowStr = "stale-overflow";
                try {
                    OmniBufMapper.unmarshal(payload, msg);
                } catch (OmniBufException e) {
                    resp.setParseError(e.getMessage());
                    return;
                }
                try {
                    resp.setOmnibufPayload(OmniBufMapper.marshal(msg, opts));
                } catch (OmniBufException e) {
                    resp.setSerializeError(e.getMessage());
                }
            }
        }
    }
}
