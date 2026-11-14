package com.omnibuf.conformance;

import com.omnibuf.annotation.OmniBufField;
import com.omnibuf.annotation.OmniBufMessage;
import com.omnibuf.annotation.WireKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Annotated Java classes and Java 21 {@code record} types used by {@code TIER_ANNOTATED_STRUCT}
 * conformance tests and benchmarks.
 */
public final class ConformanceModels {

    private ConformanceModels() {}

    @OmniBufMessage(name = "Location")
    public record AnnotatedLocation(
        @OmniBufField(id = 1, name = "region") String region,
        @OmniBufField(id = 2, name = "datacenter") String datacenter,
        @OmniBufField(id = 3, name = "city") String city,
        @OmniBufField(id = 4, name = "rack") int rack,
        @OmniBufField(id = 5, name = "latitude") float latitude,
        @OmniBufField(id = 6, name = "longitude") float longitude
    ) {}

    @OmniBufMessage(name = "Neighbor")
    public record AnnotatedNeighbor(
        @OmniBufField(id = 1, name = "peer_asn") int peerAsn,
        @OmniBufField(id = 2, name = "router") String router,
        @OmniBufField(id = 3, name = "latency_us") int latencyUs,
        @OmniBufField(id = 4, name = "established") boolean established
    ) {}

    @OmniBufMessage(name = "PeerUpdate")
    public static final class AnnotatedPeerUpdate {
        @OmniBufField(id = 1, name = "asn")
        public int asn;

        @OmniBufField(id = 2, name = "router_id")
        public String routerId = "";

        @OmniBufField(id = 3, name = "active")
        public boolean active;

        @OmniBufField(id = 4, name = "uptime_sec")
        public long uptimeSec;

        @OmniBufField(id = 5, name = "rssi_dbm", wire = WireKind.SINT)
        public int rssiDbm;

        @OmniBufField(id = 6, name = "cpu_load")
        public double cpuLoad;

        @OmniBufField(id = 7, name = "tags")
        public List<String> tags = new ArrayList<>();

        @OmniBufField(id = 8, name = "prefixes")
        public List<Integer> prefixes = new ArrayList<>();

        @OmniBufField(id = 9, name = "location")
        public AnnotatedLocation location;

        @OmniBufField(id = 10, name = "neighbors")
        public List<AnnotatedNeighbor> neighbors = new ArrayList<>();

        @OmniBufField(id = 11, name = "sequence")
        public long sequence;
    }

    @OmniBufMessage(name = "AllTypesSubMsg")
    public record AnnotatedAllTypesSubMsg(
        @OmniBufField(id = 1, name = "id") int id,
        @OmniBufField(id = 2, name = "label") String label,
        @OmniBufField(id = 3, name = "flag") boolean flag,
        @OmniBufField(id = 4, name = "score", wire = WireKind.SINT) long score
    ) {}

    @OmniBufMessage(name = "TestAllTypes")
    public static final class AnnotatedTestAllTypes {
        @OmniBufField(id = 1, name = "f1_uint32")
        public int f1Uint32;

        @OmniBufField(id = 2, name = "f2_uint64")
        public long f2Uint64;

        @OmniBufField(id = 3, name = "f3_sint32", wire = WireKind.SINT)
        public int f3Sint32;

        @OmniBufField(id = 4, name = "f4_sint64", wire = WireKind.SINT)
        public long f4Sint64;

        @OmniBufField(id = 5, name = "f5_bool")
        public boolean f5Bool;

        @OmniBufField(id = 6, name = "f6_string")
        public String f6String = "";

        @OmniBufField(id = 7, name = "f7_bytes")
        public byte[] f7Bytes = new byte[0];

        @OmniBufField(id = 8, name = "f8_sub_msg")
        public AnnotatedAllTypesSubMsg f8SubMsg;

        @OmniBufField(id = 9, name = "f9_string_arr")
        public List<String> f9StringArr = new ArrayList<>();

        @OmniBufField(id = 10, name = "f10_uint32_arr")
        public List<Integer> f10Uint32Arr = new ArrayList<>();

        @OmniBufField(id = 11, name = "f11_sub_msg_arr")
        public List<AnnotatedAllTypesSubMsg> f11SubMsgArr = new ArrayList<>();

        @OmniBufField(id = 12, name = "f12_map_str_uint")
        public Map<String, Long> f12MapStrUint = new LinkedHashMap<>();

        @OmniBufField(id = 13, name = "f13_float32")
        public float f13Float32;

        @OmniBufField(id = 14, name = "f14_float64")
        public double f14Float64;

        @OmniBufField(id = 15, name = "f15_fixed32", wire = WireKind.FIXED32)
        public int f15Fixed32;

        @OmniBufField(id = 16, name = "f16_fixed64", wire = WireKind.FIXED64)
        public long f16Fixed64;

        @OmniBufField(id = 20, name = "f20_overflow_str")
        public String f20OverflowStr = "";
    }

    @OmniBufMessage(name = "TreeNode")
    public static final class TreeNode {
        @OmniBufField(id = 1, name = "id")
        public int id;

        @OmniBufField(id = 2, name = "name")
        public String name = "";

        @OmniBufField(id = 3, name = "weight", wire = WireKind.SINT)
        public long weight;

        @OmniBufField(id = 4, name = "primary")
        public TreeNode primary;

        @OmniBufField(id = 5, name = "children")
        public List<TreeNode> children = new ArrayList<>();

        @OmniBufField(id = 6, name = "branches")
        public List<TreeNode> branches = new ArrayList<>();

        @OmniBufField(id = 7, name = "labels")
        public Map<String, Long> labels = new LinkedHashMap<>();
    }

    @OmniBufMessage(name = "NestedNIC")
    public record NestedNIC(
        @OmniBufField(id = 1, name = "if_index") int ifIndex,
        @OmniBufField(id = 2, name = "mac") String mac,
        @OmniBufField(id = 3, name = "ips") List<Integer> ips
    ) {}

    @OmniBufMessage(name = "NestedServer")
    public record NestedServer(
        @OmniBufField(id = 1, name = "server_id") int serverId,
        @OmniBufField(id = 2, name = "hostname") String hostname,
        @OmniBufField(id = 3, name = "nics") List<NestedNIC> nics
    ) {}

    @OmniBufMessage(name = "NestedRack")
    public record NestedRack(
        @OmniBufField(id = 1, name = "rack_id") int rackId,
        @OmniBufField(id = 2, name = "zone") String zone,
        @OmniBufField(id = 3, name = "servers") List<NestedServer> servers
    ) {}

    @OmniBufMessage(name = "NestedCluster")
    public record NestedCluster(
        @OmniBufField(id = 1, name = "cluster_name") String clusterName,
        @OmniBufField(id = 2, name = "racks") List<NestedRack> racks,
        @OmniBufField(id = 3, name = "epoch") long epoch
    ) {}
}
