package com.omnibuf.bench;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.flatbuffers.FlatBufferBuilder;
import com.google.flatbuffers.Table;
import com.omnibuf.OmniBufMapper;
import com.omnibuf.OmniBufProtobuf;
import com.omnibuf.conformance.ConformanceModels.AnnotatedLocation;
import com.omnibuf.conformance.ConformanceModels.AnnotatedNeighbor;
import com.omnibuf.conformance.ConformanceModels.AnnotatedPeerUpdate;
import com.omnibuf.pb.bench.Location;
import com.omnibuf.pb.bench.Neighbor;
import com.omnibuf.pb.bench.OmniBufLocation;
import com.omnibuf.pb.bench.OmniBufNeighbor;
import com.omnibuf.pb.bench.OmniBufPeerUpdate;
import com.omnibuf.pb.bench.OpaqueLocation;
import com.omnibuf.pb.bench.OpaquePeerUpdate;
import com.omnibuf.pb.bench.PeerUpdate;
import com.omnibuf.pb.bench.PeerUpdateOmniBuf;
import com.omnibuf.wire.PrefixVarint;
import com.omnibuf.wire.Utf8Validator;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;
import org.msgpack.jackson.dataformat.MessagePackFactory;

/**
 * Reproducible Java 21 benchmark runner on the canonical 11-field {@code PeerUpdate} workload
 * and large/embedded-growth stress workloads.
 *
 * <p>Performs upfront wire-byte and full 11-field checksum parity assertions across all codecs,
 * executes JVM C2 JIT warmup before timed measurement, escapes serialized {@code byte[]} outputs
 * into {@link #BYTE_SINK} and {@link #SINK} so C2 cannot scalar-replace allocations, prints
 * console tables, and writes structured results to {@code testdata/benchmarks.json} (unless invoked
 * with {@code --smoke}).
 */
public final class BenchmarkMain {

    private static final long FULL_WARMUP_NANOS = 400_000_000L;
    private static final long FULL_MEASURE_NANOS = 600_000_000L;
    private static final long SMOKE_WARMUP_NANOS = 2_500_000L;
    private static final long SMOKE_MEASURE_NANOS = 4_000_000L;

    private static final long INITIAL_SEQ = 9_876_543_210L;
    private static final int LARGE_DC_BYTES = 20_480;
    private static final int LARGE_ROUTER_BYTES = 18_432;

    /** Public volatile scalar sink preventing C2 dead-code elimination across benchmark loops. */
    public static volatile long SINK = 0L;

    /** Public volatile array sink preventing C2 escape-analysis scalar replacement of byte[]. */
    public static volatile byte[] BYTE_SINK;

    private BenchmarkMain() {}

    private static long consumeBytes(byte[] out) {
        BYTE_SINK = out;
        int len = out.length;
        long v = len == 0 ? 0L : ((out[0] & 0xFFL) ^ ((out[len - 1] & 0xFFL) << 8) ^ len);
        SINK ^= v;
        return v;
    }

    // ========================================================================
    // Jackson POJOs for JSON and MessagePack comparison
    // ========================================================================

    public static final class JacksonLocation {
        @JsonProperty("region")
        public String region;

        @JsonProperty("datacenter")
        public String datacenter;

        @JsonProperty("city")
        public String city;

        @JsonProperty("rack")
        public long rack;

        @JsonProperty("latitude")
        public float latitude;

        @JsonProperty("longitude")
        public float longitude;

        public JacksonLocation() {}

        public JacksonLocation(
                String region,
                String datacenter,
                String city,
                long rack,
                float latitude,
                float longitude) {
            this.region = region;
            this.datacenter = datacenter;
            this.city = city;
            this.rack = rack;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    public static final class JacksonNeighbor {
        @JsonProperty("peer_asn")
        public long peerAsn;

        @JsonProperty("router")
        public String router;

        @JsonProperty("latency_us")
        public long latencyUs;

        @JsonProperty("established")
        public boolean established;

        public JacksonNeighbor() {}

        public JacksonNeighbor(long peerAsn, String router, long latencyUs, boolean established) {
            this.peerAsn = peerAsn;
            this.router = router;
            this.latencyUs = latencyUs;
            this.established = established;
        }
    }

    public static final class JacksonPeerUpdate {
        @JsonProperty("asn")
        public long asn;

        @JsonProperty("router_id")
        public String routerId;

        @JsonProperty("active")
        public boolean active;

        @JsonProperty("uptime_sec")
        public long uptimeSec;

        @JsonProperty("rssi_dbm")
        public int rssiDbm;

        @JsonProperty("cpu_load")
        public double cpuLoad;

        @JsonProperty("tags")
        public List<String> tags;

        @JsonProperty("prefixes")
        public List<Long> prefixes;

        @JsonProperty("location")
        public JacksonLocation location;

        @JsonProperty("neighbors")
        public List<JacksonNeighbor> neighbors;

        @JsonProperty("sequence")
        public long sequence;

        public JacksonPeerUpdate() {}
    }

    // ========================================================================
    // FlatBuffers (com.google.flatbuffers:flatbuffers-java) Model & Codec
    // ========================================================================

    public static final class FlatLocation {
        public String region = "";
        public String datacenter = "";
        public String city = "";
        public int rack;
        public float latitude;
        public float longitude;

        public FlatLocation() {}

        public FlatLocation(
                String region,
                String datacenter,
                String city,
                int rack,
                float latitude,
                float longitude) {
            this.region = region;
            this.datacenter = datacenter;
            this.city = city;
            this.rack = rack;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    public static final class FlatNeighbor {
        public int peerAsn;
        public String router = "";
        public int latencyUs;
        public boolean established;

        public FlatNeighbor() {}

        public FlatNeighbor(int peerAsn, String router, int latencyUs, boolean established) {
            this.peerAsn = peerAsn;
            this.router = router;
            this.latencyUs = latencyUs;
            this.established = established;
        }
    }

    public static final class FlatPeerUpdate {
        public int asn;
        public String routerId = "";
        public boolean active;
        public long uptimeSec;
        public int rssiDbm;
        public double cpuLoad;
        public List<String> tags = List.of();
        public List<Integer> prefixes = List.of();
        public FlatLocation location;
        public List<FlatNeighbor> neighbors = List.of();
        public long sequence;
    }

    private static final class FbTableReader extends Table {
        void init(int pos, ByteBuffer bb) {
            __reset(pos, bb);
        }

        int fieldOffset(int vtableOffset) {
            return __offset(vtableOffset);
        }

        int indirect(int offset) {
            return __indirect(offset);
        }

        int vectorLen(int offset) {
            return __vector_len(offset);
        }

        int vectorStart(int offset) {
            return __vector(offset);
        }

        int pos() {
            return bb_pos;
        }
    }

    private static String readFlatStringStrict(byte[] raw, int fieldPos) {
        int strPos = fieldPos + PrefixVarint.getIntLE(raw, fieldPos);
        int byteLen = PrefixVarint.getIntLE(raw, strPos);
        return Utf8Validator.decodeStrict(raw, strPos + 4, byteLen);
    }

    private static byte[] encodeFlatBuffers(FlatBufferBuilder b, FlatPeerUpdate p) {
        b.clear();

        FlatLocation loc = p.location != null ? p.location : new FlatLocation();
        int regOff = b.createString(loc.region);
        int dcOff = b.createString(loc.datacenter);
        int cityOff = b.createString(loc.city);

        b.startTable(6);
        b.addOffset(0, regOff, 0);
        b.addOffset(1, dcOff, 0);
        b.addOffset(2, cityOff, 0);
        b.addInt(3, loc.rack, 0);
        b.addFloat(4, loc.latitude, 0.0d);
        b.addFloat(5, loc.longitude, 0.0d);
        int locOff = b.endTable();

        int nCount = p.neighbors.size();
        int[] neighOffs = new int[nCount];
        for (int i = nCount - 1; i >= 0; i--) {
            FlatNeighbor nb = p.neighbors.get(i);
            int rtrOff = b.createString(nb.router);
            b.startTable(4);
            b.addInt(0, nb.peerAsn, 0);
            b.addOffset(1, rtrOff, 0);
            b.addInt(2, nb.latencyUs, 0);
            b.addBoolean(3, nb.established, false);
            neighOffs[i] = b.endTable();
        }
        b.startVector(4, nCount, 4);
        for (int i = nCount - 1; i >= 0; i--) {
            b.addOffset(neighOffs[i]);
        }
        int neighborsVecOff = b.endVector();

        int tCount = p.tags.size();
        int[] tagOffs = new int[tCount];
        for (int i = tCount - 1; i >= 0; i--) {
            tagOffs[i] = b.createString(p.tags.get(i));
        }
        b.startVector(4, tCount, 4);
        for (int i = tCount - 1; i >= 0; i--) {
            b.addOffset(tagOffs[i]);
        }
        int tagsVecOff = b.endVector();

        int pCount = p.prefixes.size();
        b.startVector(4, pCount, 4);
        for (int i = pCount - 1; i >= 0; i--) {
            b.addInt(p.prefixes.get(i));
        }
        int prefixesVecOff = b.endVector();

        int routerIdOff = b.createString(p.routerId);

        b.startTable(11);
        b.addInt(0, p.asn, 0);
        b.addOffset(1, routerIdOff, 0);
        b.addBoolean(2, p.active, false);
        b.addLong(3, p.uptimeSec, 0L);
        b.addInt(4, p.rssiDbm, 0);
        b.addDouble(5, p.cpuLoad, 0.0d);
        b.addOffset(6, tagsVecOff, 0);
        b.addOffset(7, prefixesVecOff, 0);
        b.addOffset(8, locOff, 0);
        b.addOffset(9, neighborsVecOff, 0);
        b.addLong(10, p.sequence, 0L);
        int root = b.endTable();

        b.finish(root);
        return b.sizedByteArray();
    }

    private static FlatPeerUpdate decodeFlatBuffers(byte[] raw) {
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int rootPos = bb.getInt(0);
        FbTableReader tab = new FbTableReader();
        tab.init(rootPos, bb);

        FlatPeerUpdate out = new FlatPeerUpdate();
        int o;
        if ((o = tab.fieldOffset(4)) != 0) {
            out.asn = bb.getInt(o + tab.pos());
        }
        if ((o = tab.fieldOffset(6)) != 0) {
            out.routerId = readFlatStringStrict(raw, o + tab.pos());
        }
        if ((o = tab.fieldOffset(8)) != 0) {
            out.active = bb.get(o + tab.pos()) != 0;
        }
        if ((o = tab.fieldOffset(10)) != 0) {
            out.uptimeSec = bb.getLong(o + tab.pos());
        }
        if ((o = tab.fieldOffset(12)) != 0) {
            out.rssiDbm = bb.getInt(o + tab.pos());
        }
        if ((o = tab.fieldOffset(14)) != 0) {
            out.cpuLoad = bb.getDouble(o + tab.pos());
        }
        if ((o = tab.fieldOffset(16)) != 0) {
            int vecLen = tab.vectorLen(o);
            int vecStart = tab.vectorStart(o);
            List<String> tags = new ArrayList<>(vecLen);
            for (int i = 0; i < vecLen; i++) {
                tags.add(readFlatStringStrict(raw, vecStart + i * 4));
            }
            out.tags = tags;
        }
        if ((o = tab.fieldOffset(18)) != 0) {
            int vecLen = tab.vectorLen(o);
            int vecStart = tab.vectorStart(o);
            List<Integer> prefs = new ArrayList<>(vecLen);
            for (int i = 0; i < vecLen; i++) {
                prefs.add(bb.getInt(vecStart + i * 4));
            }
            out.prefixes = prefs;
        }
        if ((o = tab.fieldOffset(20)) != 0) {
            int locPos = tab.indirect(o + tab.pos());
            FbTableReader locTab = new FbTableReader();
            locTab.init(locPos, bb);
            FlatLocation loc = new FlatLocation();
            int lo;
            if ((lo = locTab.fieldOffset(4)) != 0) {
                loc.region = readFlatStringStrict(raw, lo + locTab.pos());
            }
            if ((lo = locTab.fieldOffset(6)) != 0) {
                loc.datacenter = readFlatStringStrict(raw, lo + locTab.pos());
            }
            if ((lo = locTab.fieldOffset(8)) != 0) {
                loc.city = readFlatStringStrict(raw, lo + locTab.pos());
            }
            if ((lo = locTab.fieldOffset(10)) != 0) {
                loc.rack = bb.getInt(lo + locTab.pos());
            }
            if ((lo = locTab.fieldOffset(12)) != 0) {
                loc.latitude = bb.getFloat(lo + locTab.pos());
            }
            if ((lo = locTab.fieldOffset(14)) != 0) {
                loc.longitude = bb.getFloat(lo + locTab.pos());
            }
            out.location = loc;
        }
        if ((o = tab.fieldOffset(22)) != 0) {
            int vecLen = tab.vectorLen(o);
            int vecStart = tab.vectorStart(o);
            List<FlatNeighbor> neighs = new ArrayList<>(vecLen);
            FbTableReader nTab = new FbTableReader();
            for (int i = 0; i < vecLen; i++) {
                int elemOff = vecStart + i * 4;
                int nPos = tab.indirect(elemOff);
                nTab.init(nPos, bb);
                FlatNeighbor nb = new FlatNeighbor();
                int no;
                if ((no = nTab.fieldOffset(4)) != 0) {
                    nb.peerAsn = bb.getInt(no + nTab.pos());
                }
                if ((no = nTab.fieldOffset(6)) != 0) {
                    nb.router = readFlatStringStrict(raw, no + nTab.pos());
                }
                if ((no = nTab.fieldOffset(8)) != 0) {
                    nb.latencyUs = bb.getInt(no + nTab.pos());
                }
                if ((no = nTab.fieldOffset(10)) != 0) {
                    nb.established = bb.get(no + nTab.pos()) != 0;
                }
                neighs.add(nb);
            }
            out.neighbors = neighs;
        }
        if ((o = tab.fieldOffset(24)) != 0) {
            out.sequence = bb.getLong(o + tab.pos());
        }
        return out;
    }

    private static String lookupFlatBuffersCity(byte[] raw) {
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int rootPos = bb.getInt(0);
        FbTableReader tab = new FbTableReader();
        tab.init(rootPos, bb);
        int o = tab.fieldOffset(20);
        if (o == 0) {
            return "";
        }
        int locPos = tab.indirect(o + tab.pos());
        FbTableReader locTab = new FbTableReader();
        locTab.init(locPos, bb);
        int co = locTab.fieldOffset(8);
        if (co == 0) {
            return "";
        }
        return readFlatStringStrict(raw, co + locTab.pos());
    }

    // ========================================================================
    // Full 11-Field Checksum Helpers (Preventing Partial-Decode Shortcuts)
    // ========================================================================

    private static long mixLong(long h, long v) {
        long x = h ^ v;
        x ^= (x >>> 33);
        x *= 0xff51afd7ed558ccdL;
        x ^= (x >>> 33);
        return x;
    }

    private static long mixString(long h, String s) {
        if (s == null) {
            return mixLong(h, 0L);
        }
        h = mixLong(h, s.length());
        for (int i = 0, n = s.length(); i < n; i++) {
            h = h * 31L + s.charAt(i);
        }
        return h;
    }

    private static long checksumGenerated(OmniBufPeerUpdate m) {
        long h = 0x9e3779b97f4a7c15L;
        h = mixLong(h, Integer.toUnsignedLong(m.getAsn()));
        h = mixString(h, m.getRouterId());
        h = mixLong(h, m.getActive() ? 1L : 0L);
        h = mixLong(h, m.getUptimeSec());
        h = mixLong(h, m.getRssiDbm());
        h = mixLong(h, Double.doubleToRawLongBits(m.getCpuLoad()));
        List<String> tags = m.getTags();
        h = mixLong(h, tags.size());
        for (int i = 0, n = tags.size(); i < n; i++) {
            h = mixString(h, tags.get(i));
        }
        List<Integer> prefs = m.getPrefixes();
        h = mixLong(h, prefs.size());
        for (int i = 0, n = prefs.size(); i < n; i++) {
            h = mixLong(h, Integer.toUnsignedLong(prefs.get(i)));
        }
        OmniBufLocation loc = m.getLocation();
        if (loc != null) {
            h = mixString(h, loc.getRegion());
            h = mixString(h, loc.getDatacenter());
            h = mixString(h, loc.getCity());
            h = mixLong(h, Integer.toUnsignedLong(loc.getRack()));
            h = mixLong(h, Float.floatToRawIntBits(loc.getLatitude()));
            h = mixLong(h, Float.floatToRawIntBits(loc.getLongitude()));
        }
        List<OmniBufNeighbor> neighs = m.getNeighbors();
        h = mixLong(h, neighs.size());
        for (int i = 0, n = neighs.size(); i < n; i++) {
            OmniBufNeighbor nb = neighs.get(i);
            h = mixLong(h, Integer.toUnsignedLong(nb.getPeerAsn()));
            h = mixString(h, nb.getRouter());
            h = mixLong(h, Integer.toUnsignedLong(nb.getLatencyUs()));
            h = mixLong(h, nb.getEstablished() ? 1L : 0L);
        }
        h = mixLong(h, m.getSequence());
        return h;
    }

    private static long checksumAnnotated(AnnotatedPeerUpdate m) {
        long h = 0x9e3779b97f4a7c15L;
        h = mixLong(h, Integer.toUnsignedLong(m.asn));
        h = mixString(h, m.routerId);
        h = mixLong(h, m.active ? 1L : 0L);
        h = mixLong(h, m.uptimeSec);
        h = mixLong(h, m.rssiDbm);
        h = mixLong(h, Double.doubleToRawLongBits(m.cpuLoad));
        h = mixLong(h, m.tags.size());
        for (int i = 0, n = m.tags.size(); i < n; i++) {
            h = mixString(h, m.tags.get(i));
        }
        h = mixLong(h, m.prefixes.size());
        for (int i = 0, n = m.prefixes.size(); i < n; i++) {
            h = mixLong(h, Integer.toUnsignedLong(m.prefixes.get(i)));
        }
        AnnotatedLocation loc = m.location;
        if (loc != null) {
            h = mixString(h, loc.region());
            h = mixString(h, loc.datacenter());
            h = mixString(h, loc.city());
            h = mixLong(h, Integer.toUnsignedLong(loc.rack()));
            h = mixLong(h, Float.floatToRawIntBits(loc.latitude()));
            h = mixLong(h, Float.floatToRawIntBits(loc.longitude()));
        }
        h = mixLong(h, m.neighbors.size());
        for (int i = 0, n = m.neighbors.size(); i < n; i++) {
            AnnotatedNeighbor nb = m.neighbors.get(i);
            h = mixLong(h, Integer.toUnsignedLong(nb.peerAsn()));
            h = mixString(h, nb.router());
            h = mixLong(h, Integer.toUnsignedLong(nb.latencyUs()));
            h = mixLong(h, nb.established() ? 1L : 0L);
        }
        h = mixLong(h, m.sequence);
        return h;
    }

    private static long checksumProtobuf(PeerUpdate m) {
        long h = 0x9e3779b97f4a7c15L;
        h = mixLong(h, Integer.toUnsignedLong(m.getAsn()));
        h = mixString(h, m.getRouterId());
        h = mixLong(h, m.getActive() ? 1L : 0L);
        h = mixLong(h, m.getUptimeSec());
        h = mixLong(h, m.getRssiDbm());
        h = mixLong(h, Double.doubleToRawLongBits(m.getCpuLoad()));
        int tagCount = m.getTagsCount();
        h = mixLong(h, tagCount);
        for (int i = 0; i < tagCount; i++) {
            h = mixString(h, m.getTags(i));
        }
        int prefCount = m.getPrefixesCount();
        h = mixLong(h, prefCount);
        for (int i = 0; i < prefCount; i++) {
            h = mixLong(h, Integer.toUnsignedLong(m.getPrefixes(i)));
        }
        if (m.hasLocation()) {
            Location loc = m.getLocation();
            h = mixString(h, loc.getRegion());
            h = mixString(h, loc.getDatacenter());
            h = mixString(h, loc.getCity());
            h = mixLong(h, Integer.toUnsignedLong(loc.getRack()));
            h = mixLong(h, Float.floatToRawIntBits(loc.getLatitude()));
            h = mixLong(h, Float.floatToRawIntBits(loc.getLongitude()));
        }
        int neighCount = m.getNeighborsCount();
        h = mixLong(h, neighCount);
        for (int i = 0; i < neighCount; i++) {
            Neighbor nb = m.getNeighbors(i);
            h = mixLong(h, Integer.toUnsignedLong(nb.getPeerAsn()));
            h = mixString(h, nb.getRouter());
            h = mixLong(h, Integer.toUnsignedLong(nb.getLatencyUs()));
            h = mixLong(h, nb.getEstablished() ? 1L : 0L);
        }
        h = mixLong(h, m.getSequence());
        return h;
    }

    private static long checksumFlatBuffers(FlatPeerUpdate m) {
        long h = 0x9e3779b97f4a7c15L;
        h = mixLong(h, Integer.toUnsignedLong(m.asn));
        h = mixString(h, m.routerId);
        h = mixLong(h, m.active ? 1L : 0L);
        h = mixLong(h, m.uptimeSec);
        h = mixLong(h, m.rssiDbm);
        h = mixLong(h, Double.doubleToRawLongBits(m.cpuLoad));
        h = mixLong(h, m.tags.size());
        for (int i = 0, n = m.tags.size(); i < n; i++) {
            h = mixString(h, m.tags.get(i));
        }
        h = mixLong(h, m.prefixes.size());
        for (int i = 0, n = m.prefixes.size(); i < n; i++) {
            h = mixLong(h, Integer.toUnsignedLong(m.prefixes.get(i)));
        }
        FlatLocation loc = m.location;
        if (loc != null) {
            h = mixString(h, loc.region);
            h = mixString(h, loc.datacenter);
            h = mixString(h, loc.city);
            h = mixLong(h, Integer.toUnsignedLong(loc.rack));
            h = mixLong(h, Float.floatToRawIntBits(loc.latitude));
            h = mixLong(h, Float.floatToRawIntBits(loc.longitude));
        }
        h = mixLong(h, m.neighbors.size());
        for (int i = 0, n = m.neighbors.size(); i < n; i++) {
            FlatNeighbor nb = m.neighbors.get(i);
            h = mixLong(h, Integer.toUnsignedLong(nb.peerAsn));
            h = mixString(h, nb.router);
            h = mixLong(h, Integer.toUnsignedLong(nb.latencyUs));
            h = mixLong(h, nb.established ? 1L : 0L);
        }
        h = mixLong(h, m.sequence);
        return h;
    }

    private static long checksumJackson(JacksonPeerUpdate m) {
        long h = 0x9e3779b97f4a7c15L;
        h = mixLong(h, m.asn & 0xFFFF_FFFFL);
        h = mixString(h, m.routerId);
        h = mixLong(h, m.active ? 1L : 0L);
        h = mixLong(h, m.uptimeSec);
        h = mixLong(h, m.rssiDbm);
        h = mixLong(h, Double.doubleToRawLongBits(m.cpuLoad));
        h = mixLong(h, m.tags.size());
        for (int i = 0, n = m.tags.size(); i < n; i++) {
            h = mixString(h, m.tags.get(i));
        }
        h = mixLong(h, m.prefixes.size());
        for (int i = 0, n = m.prefixes.size(); i < n; i++) {
            h = mixLong(h, m.prefixes.get(i) & 0xFFFF_FFFFL);
        }
        JacksonLocation loc = m.location;
        if (loc != null) {
            h = mixString(h, loc.region);
            h = mixString(h, loc.datacenter);
            h = mixString(h, loc.city);
            h = mixLong(h, loc.rack & 0xFFFF_FFFFL);
            h = mixLong(h, Float.floatToRawIntBits(loc.latitude));
            h = mixLong(h, Float.floatToRawIntBits(loc.longitude));
        }
        h = mixLong(h, m.neighbors.size());
        for (int i = 0, n = m.neighbors.size(); i < n; i++) {
            JacksonNeighbor nb = m.neighbors.get(i);
            h = mixLong(h, nb.peerAsn & 0xFFFF_FFFFL);
            h = mixString(h, nb.router);
            h = mixLong(h, nb.latencyUs & 0xFFFF_FFFFL);
            h = mixLong(h, nb.established ? 1L : 0L);
        }
        h = mixLong(h, m.sequence);
        return h;
    }

    private static void assertChecksumEqual(String label, long expected, long got) {
        if (expected != got) {
            throw new IllegalStateException(
                "11-field checksum parity mismatch for " + label
                    + ": expected=0x" + Long.toHexString(expected)
                    + ", got=0x" + Long.toHexString(got)
            );
        }
    }

    // ========================================================================
    // Benchmark JSON Output Schema (matching testdata/benchmarks.json)
    // ========================================================================

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BenchmarkResultEntry(
        String name,
        long nsPerOp,
        long opsPerSec,
        Integer wireBytes,
        Boolean highlight,
        Boolean baseline
    ) {}

    public record BenchmarkCategory(
        String id,
        String title,
        String unit,
        List<BenchmarkResultEntry> results
    ) {}

    public record BenchmarkReport(
        String language,
        String runtime,
        String platform,
        String arch,
        String cpu,
        String workload,
        List<BenchmarkCategory> categories
    ) {}

    // ========================================================================
    // Sample Builders (Canonical, Large Embedded >16 KiB, and 32-Neighbor)
    // ========================================================================

    private static final List<Integer> SAMPLE_PREFIXES_U32 = List.of(
        167_772_160,
        172_032_000,
        (int) 3_232_235_520L,
        (int) 2_886_729_728L,
        134_744_072,
        134_743_040
    );

    private static final List<Long> SAMPLE_PREFIXES_LONG = List.of(
        167_772_160L,
        172_032_000L,
        3_232_235_520L,
        2_886_729_728L,
        134_744_072L,
        134_743_040L
    );

    private static OmniBufPeerUpdate buildGeneratedSample(
            String dcOverride, String firstNeighborRouterOverride, int neighborRepeat) {
        List<OmniBufNeighbor> baseNeighbors = List.of(
            new OmniBufNeighbor()
                .setPeerAsn(3356)
                .setRouter(firstNeighborRouterOverride != null ? firstNeighborRouterOverride : "ae0.fra1.level3.net")
                .setLatencyUs(850)
                .setEstablished(true),
            new OmniBufNeighbor()
                .setPeerAsn(1299)
                .setRouter("ffm-b5-link.telia.net")
                .setLatencyUs(920)
                .setEstablished(true),
            new OmniBufNeighbor()
                .setPeerAsn(174)
                .setRouter("be2040.ccr41.fra03.atlas.cogentco.com")
                .setLatencyUs(1120)
                .setEstablished(true),
            new OmniBufNeighbor()
                .setPeerAsn(2914)
                .setRouter("ae-12.r24.frnkge08.de.bb.gin.ntt.net")
                .setLatencyUs(980)
                .setEstablished(false)
        );
        List<OmniBufNeighbor> neighbors = new ArrayList<>(baseNeighbors.size() * neighborRepeat);
        for (int r = 0; r < neighborRepeat; r++) {
            neighbors.addAll(baseNeighbors);
        }
        return new OmniBufPeerUpdate()
            .setAsn(64512)
            .setRouterId("edge-rtr-fra01.core.backbone.net")
            .setActive(true)
            .setUptimeSec(1_849_203L)
            .setRssiDbm(-62)
            .setCpuLoad(0.42875d)
            .setTags(new ArrayList<>(List.of("tier1", "ebgp", "eu-central", "ixp-de-cix")))
            .setPrefixes(new ArrayList<>(SAMPLE_PREFIXES_U32))
            .setLocation(
                new OmniBufLocation()
                    .setRegion("eu-central-1")
                    .setDatacenter(dcOverride != null ? dcOverride : "FRA-Equinix-FR5")
                    .setCity("Frankfurt")
                    .setRack(42)
                    .setLatitude(50.1109f)
                    .setLongitude(8.6821f)
            )
            .setNeighbors(neighbors)
            .setSequence(INITIAL_SEQ);
    }

    private static AnnotatedPeerUpdate buildAnnotatedSample(
            String dcOverride, String firstNeighborRouterOverride, int neighborRepeat) {
        List<AnnotatedNeighbor> baseNeighbors = List.of(
            new AnnotatedNeighbor(
                3356,
                firstNeighborRouterOverride != null ? firstNeighborRouterOverride : "ae0.fra1.level3.net",
                850,
                true
            ),
            new AnnotatedNeighbor(1299, "ffm-b5-link.telia.net", 920, true),
            new AnnotatedNeighbor(174, "be2040.ccr41.fra03.atlas.cogentco.com", 1120, true),
            new AnnotatedNeighbor(2914, "ae-12.r24.frnkge08.de.bb.gin.ntt.net", 980, false)
        );
        List<AnnotatedNeighbor> neighbors = new ArrayList<>(baseNeighbors.size() * neighborRepeat);
        for (int r = 0; r < neighborRepeat; r++) {
            neighbors.addAll(baseNeighbors);
        }
        AnnotatedPeerUpdate msg = new AnnotatedPeerUpdate();
        msg.asn = 64512;
        msg.routerId = "edge-rtr-fra01.core.backbone.net";
        msg.active = true;
        msg.uptimeSec = 1_849_203L;
        msg.rssiDbm = -62;
        msg.cpuLoad = 0.42875d;
        msg.tags = new ArrayList<>(List.of("tier1", "ebgp", "eu-central", "ixp-de-cix"));
        msg.prefixes = new ArrayList<>(SAMPLE_PREFIXES_U32);
        msg.location = new AnnotatedLocation(
            "eu-central-1",
            dcOverride != null ? dcOverride : "FRA-Equinix-FR5",
            "Frankfurt",
            42,
            50.1109f,
            8.6821f
        );
        msg.neighbors = neighbors;
        msg.sequence = INITIAL_SEQ;
        return msg;
    }

    private static PeerUpdate buildProtobufSample(
            String dcOverride, String firstNeighborRouterOverride, int neighborRepeat) {
        List<Neighbor> baseNeighbors = List.of(
            Neighbor.newBuilder()
                .setPeerAsn(3356)
                .setRouter(firstNeighborRouterOverride != null ? firstNeighborRouterOverride : "ae0.fra1.level3.net")
                .setLatencyUs(850)
                .setEstablished(true)
                .build(),
            Neighbor.newBuilder()
                .setPeerAsn(1299)
                .setRouter("ffm-b5-link.telia.net")
                .setLatencyUs(920)
                .setEstablished(true)
                .build(),
            Neighbor.newBuilder()
                .setPeerAsn(174)
                .setRouter("be2040.ccr41.fra03.atlas.cogentco.com")
                .setLatencyUs(1120)
                .setEstablished(true)
                .build(),
            Neighbor.newBuilder()
                .setPeerAsn(2914)
                .setRouter("ae-12.r24.frnkge08.de.bb.gin.ntt.net")
                .setLatencyUs(980)
                .setEstablished(false)
                .build()
        );
        PeerUpdate.Builder builder = PeerUpdate.newBuilder()
            .setAsn(64512)
            .setRouterId("edge-rtr-fra01.core.backbone.net")
            .setActive(true)
            .setUptimeSec(1_849_203L)
            .setRssiDbm(-62)
            .setCpuLoad(0.42875d)
            .addAllTags(List.of("tier1", "ebgp", "eu-central", "ixp-de-cix"))
            .addAllPrefixes(SAMPLE_PREFIXES_U32)
            .setLocation(
                Location.newBuilder()
                    .setRegion("eu-central-1")
                    .setDatacenter(dcOverride != null ? dcOverride : "FRA-Equinix-FR5")
                    .setCity("Frankfurt")
                    .setRack(42)
                    .setLatitude(50.1109f)
                    .setLongitude(8.6821f)
                    .build()
            )
            .setSequence(INITIAL_SEQ);
        for (int r = 0; r < neighborRepeat; r++) {
            builder.addAllNeighbors(baseNeighbors);
        }
        return builder.build();
    }

    private static FlatPeerUpdate buildFlatSample(
            String dcOverride, String firstNeighborRouterOverride, int neighborRepeat) {
        List<FlatNeighbor> baseNeighbors = List.of(
            new FlatNeighbor(
                3356,
                firstNeighborRouterOverride != null ? firstNeighborRouterOverride : "ae0.fra1.level3.net",
                850,
                true
            ),
            new FlatNeighbor(1299, "ffm-b5-link.telia.net", 920, true),
            new FlatNeighbor(174, "be2040.ccr41.fra03.atlas.cogentco.com", 1120, true),
            new FlatNeighbor(2914, "ae-12.r24.frnkge08.de.bb.gin.ntt.net", 980, false)
        );
        List<FlatNeighbor> neighbors = new ArrayList<>(baseNeighbors.size() * neighborRepeat);
        for (int r = 0; r < neighborRepeat; r++) {
            for (FlatNeighbor nb : baseNeighbors) {
                neighbors.add(new FlatNeighbor(nb.peerAsn, nb.router, nb.latencyUs, nb.established));
            }
        }
        FlatPeerUpdate msg = new FlatPeerUpdate();
        msg.asn = 64512;
        msg.routerId = "edge-rtr-fra01.core.backbone.net";
        msg.active = true;
        msg.uptimeSec = 1_849_203L;
        msg.rssiDbm = -62;
        msg.cpuLoad = 0.42875d;
        msg.tags = new ArrayList<>(List.of("tier1", "ebgp", "eu-central", "ixp-de-cix"));
        msg.prefixes = new ArrayList<>(SAMPLE_PREFIXES_U32);
        msg.location = new FlatLocation(
            "eu-central-1",
            dcOverride != null ? dcOverride : "FRA-Equinix-FR5",
            "Frankfurt",
            42,
            50.1109f,
            8.6821f
        );
        msg.neighbors = neighbors;
        msg.sequence = INITIAL_SEQ;
        return msg;
    }

    private static JacksonPeerUpdate buildJacksonSample(
            String dcOverride, String firstNeighborRouterOverride, int neighborRepeat) {
        List<JacksonNeighbor> baseNeighbors = List.of(
            new JacksonNeighbor(
                3356L,
                firstNeighborRouterOverride != null ? firstNeighborRouterOverride : "ae0.fra1.level3.net",
                850L,
                true
            ),
            new JacksonNeighbor(1299L, "ffm-b5-link.telia.net", 920L, true),
            new JacksonNeighbor(174L, "be2040.ccr41.fra03.atlas.cogentco.com", 1120L, true),
            new JacksonNeighbor(2914L, "ae-12.r24.frnkge08.de.bb.gin.ntt.net", 980L, false)
        );
        List<JacksonNeighbor> neighbors = new ArrayList<>(baseNeighbors.size() * neighborRepeat);
        for (int r = 0; r < neighborRepeat; r++) {
            for (JacksonNeighbor nb : baseNeighbors) {
                neighbors.add(new JacksonNeighbor(nb.peerAsn, nb.router, nb.latencyUs, nb.established));
            }
        }
        JacksonPeerUpdate msg = new JacksonPeerUpdate();
        msg.asn = 64512L;
        msg.routerId = "edge-rtr-fra01.core.backbone.net";
        msg.active = true;
        msg.uptimeSec = 1_849_203L;
        msg.rssiDbm = -62;
        msg.cpuLoad = 0.42875d;
        msg.tags = List.of("tier1", "ebgp", "eu-central", "ixp-de-cix");
        msg.prefixes = SAMPLE_PREFIXES_LONG;
        msg.location = new JacksonLocation(
            "eu-central-1",
            dcOverride != null ? dcOverride : "FRA-Equinix-FR5",
            "Frankfurt",
            42L,
            50.1109f,
            8.6821f
        );
        msg.neighbors = neighbors;
        msg.sequence = INITIAL_SEQ;
        return msg;
    }

    // ========================================================================
    // Harness & Environment Detection
    // ========================================================================

    private static BenchmarkResultEntry runBenchmark(
            String name,
            Integer wireBytes,
            boolean highlight,
            boolean baseline,
            boolean smokeMode,
            LongSupplier op) {
        long warmupNanos = smokeMode ? SMOKE_WARMUP_NANOS : FULL_WARMUP_NANOS;
        long measureNanos = smokeMode ? SMOKE_MEASURE_NANOS : FULL_MEASURE_NANOS;
        int warmupInner = smokeMode ? 8 : 256;

        // Phase 1: JVM C2 JIT warmup
        long warmupAcc = 0L;
        long warmupDeadline = System.nanoTime() + warmupNanos;
        while (System.nanoTime() < warmupDeadline) {
            for (int i = 0; i < warmupInner; i++) {
                warmupAcc ^= op.getAsLong();
            }
        }
        SINK ^= warmupAcc;

        // Calibrate batch size so System.nanoTime() overhead is negligible
        long calStart = System.nanoTime();
        long calAcc = 0L;
        int calOps = smokeMode ? 8 : 256;
        for (int i = 0; i < calOps; i++) {
            calAcc ^= op.getAsLong();
        }
        long calElapsed = Math.max(1L, System.nanoTime() - calStart);
        SINK ^= calAcc;
        long targetBatchNs = smokeMode ? 500_000L : 10_000_000L;
        int batchSize = (int) Math.clamp((targetBatchNs * calOps) / calElapsed, 1L, 16_384L);

        // Phase 2: Timed measurement
        long totalOps = 0L;
        long acc = 0L;
        long start = System.nanoTime();
        long elapsed;
        do {
            for (int i = 0; i < batchSize; i++) {
                acc ^= op.getAsLong();
            }
            totalOps += batchSize;
            elapsed = System.nanoTime() - start;
        } while (elapsed < measureNanos);
        SINK ^= acc;

        double exactNs = (double) elapsed / (double) totalOps;
        long nsPerOp = Math.max(1L, Math.round(exactNs));
        long opsPerSec = Math.round(1_000_000_000.0d / exactNs);

        return new BenchmarkResultEntry(
            name,
            nsPerOp,
            opsPerSec,
            wireBytes,
            highlight ? Boolean.TRUE : null,
            baseline ? Boolean.TRUE : null
        );
    }

    private static List<BenchmarkResultEntry> sortResults(List<BenchmarkResultEntry> raw) {
        List<BenchmarkResultEntry> copy = new ArrayList<>(raw);
        copy.sort(Comparator.comparingLong(BenchmarkResultEntry::nsPerOp));
        return copy;
    }

    private static void printCategoryTable(BenchmarkCategory category) {
        System.out.println();
        System.out.println("### " + category.title());
        boolean showBytes = category.results().stream().anyMatch(r -> r.wireBytes() != null);
        if (showBytes) {
            System.out.printf(
                Locale.ROOT,
                "  %-28s %12s %16s %12s%n",
                "Codec",
                "ns/op",
                "ops/sec",
                "Wire Bytes"
            );
            System.out.println("  " + "-".repeat(72));
            for (BenchmarkResultEntry r : category.results()) {
                System.out.printf(
                    Locale.ROOT,
                    "  %-28s %,12d %,16d %10d B%n",
                    r.name(),
                    r.nsPerOp(),
                    r.opsPerSec(),
                    r.wireBytes()
                );
            }
        } else {
            System.out.printf(
                Locale.ROOT,
                "  %-28s %12s %16s%n",
                "Codec",
                "ns/op",
                "ops/sec"
            );
            System.out.println("  " + "-".repeat(58));
            for (BenchmarkResultEntry r : category.results()) {
                System.out.printf(
                    Locale.ROOT,
                    "  %-28s %,12d %,16d%n",
                    r.name(),
                    r.nsPerOp(),
                    r.opsPerSec()
                );
            }
        }
    }

    private static String detectPlatform() {
        String os = System.getProperty("os.name", "unknown").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return "darwin";
        }
        if (os.contains("linux")) {
            return "linux";
        }
        if (os.contains("win")) {
            return "windows";
        }
        return os;
    }

    private static String detectArch() {
        String arch = System.getProperty("os.arch", "unknown").toLowerCase(Locale.ROOT);
        if (arch.equals("aarch64") || arch.equals("arm64")) {
            return "arm64";
        }
        if (arch.equals("x86_64") || arch.equals("amd64")) {
            return "amd64";
        }
        return arch;
    }

    private static String detectCpuModel(String platform, String arch) {
        if ("darwin".equals(platform)) {
            try {
                Process proc = new ProcessBuilder("sysctl", "-n", "machdep.cpu.brand_string")
                    .redirectErrorStream(true)
                    .start();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line = reader.readLine();
                    proc.waitFor();
                    if (line != null && !line.isBlank()) {
                        return line.trim();
                    }
                }
            } catch (Exception ignored) {
                // Fall back below
            }
        } else if ("linux".equals(platform)) {
            try {
                Path cpuInfo = Path.of("/proc/cpuinfo");
                if (Files.isReadable(cpuInfo)) {
                    for (String line : Files.readAllLines(cpuInfo, StandardCharsets.UTF_8)) {
                        if (line.startsWith("model name")) {
                            int idx = line.indexOf(':');
                            if (idx >= 0) {
                                return line.substring(idx + 1).trim();
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                // Fall back below
            }
        }
        return arch;
    }

    public static void main(String[] args) throws Exception {
        boolean smokeMode = Arrays.asList(args).contains("--smoke");

        // --------------------------------------------------------------------
        // Canonical 11-Field PeerUpdate Setup & Parity Verification
        // --------------------------------------------------------------------
        OmniBufPeerUpdate genSample = buildGeneratedSample(null, null, 1);
        AnnotatedPeerUpdate annotatedSample = buildAnnotatedSample(null, null, 1);
        PeerUpdate protoSample = buildProtobufSample(null, null, 1);
        FlatPeerUpdate flatSample = buildFlatSample(null, null, 1);
        JacksonPeerUpdate jacksonSample = buildJacksonSample(null, null, 1);

        ObjectMapper jsonMapper = new ObjectMapper();
        ObjectMapper msgpackMapper = new ObjectMapper(new MessagePackFactory());
        FlatBufferBuilder fbBuilder = new FlatBufferBuilder(1024);

        byte[] omnibufBytes = PeerUpdateOmniBuf.marshal(genSample);
        byte[] annotatedBytes = OmniBufMapper.marshal(annotatedSample);
        byte[] omniProtoBytes = OmniBufProtobuf.marshal(protoSample);
        byte[] protoBytes = protoSample.toByteArray();
        byte[] flatBytes = encodeFlatBuffers(fbBuilder, flatSample);
        byte[] msgpackBytes = msgpackMapper.writeValueAsBytes(jacksonSample);
        byte[] jsonBytes = jsonMapper.writeValueAsBytes(jacksonSample);

        if (omnibufBytes.length != 366) {
            throw new IllegalStateException("Expected OmniBuf PeerUpdate size 366B, got " + omnibufBytes.length);
        }
        if (!Arrays.equals(omnibufBytes, annotatedBytes) || !Arrays.equals(omnibufBytes, omniProtoBytes)) {
            throw new IllegalStateException("OmniBuf tiers did not produce bit-identical 366B output");
        }
        if (protoBytes.length != 341) {
            throw new IllegalStateException("Expected Protobuf PeerUpdate size 341B, got " + protoBytes.length);
        }
        if (flatBytes.length != 600) {
            throw new IllegalStateException("Expected FlatBuffers PeerUpdate size 600B, got " + flatBytes.length);
        }

        // Upfront 11-field decode checksum parity assertion across all codecs
        long canonicalChecksum = checksumGenerated(PeerUpdateOmniBuf.unmarshal(omnibufBytes));
        assertChecksumEqual(
            "OmniBuf (@OmniBufField)",
            canonicalChecksum,
            checksumAnnotated(OmniBufMapper.unmarshal(annotatedBytes, AnnotatedPeerUpdate.class))
        );
        assertChecksumEqual(
            "OmniBuf (Protobuf)",
            canonicalChecksum,
            checksumProtobuf(OmniBufProtobuf.unmarshal(omniProtoBytes, PeerUpdate.getDefaultInstance()))
        );
        assertChecksumEqual(
            "Protobuf (protobuf-java)",
            canonicalChecksum,
            checksumProtobuf(PeerUpdate.parseFrom(protoBytes))
        );
        assertChecksumEqual(
            "FlatBuffers",
            canonicalChecksum,
            checksumFlatBuffers(decodeFlatBuffers(flatBytes))
        );
        assertChecksumEqual(
            "MessagePack (Jackson)",
            canonicalChecksum,
            checksumJackson(msgpackMapper.readValue(msgpackBytes, JacksonPeerUpdate.class))
        );
        assertChecksumEqual(
            "JSON (Jackson)",
            canonicalChecksum,
            checksumJackson(jsonMapper.readValue(jsonBytes, JacksonPeerUpdate.class))
        );
        if (!"Frankfurt".equals(lookupFlatBuffersCity(flatBytes))) {
            throw new IllegalStateException("FlatBuffers zero-copy city lookup mismatch");
        }

        // Upfront mutate_sequence parity assertion across all codecs
        {
            OpaquePeerUpdate opView = new OpaquePeerUpdate(omnibufBytes);
            OpaqueLocation opLoc = opView.getLocation();
            if (opLoc != null && "Frankfurt".equals(opLoc.getCity())) {
                opView.setSequence(opView.getSequence() + 1L);
            }
            byte[] opMutBytes = opView.marshal();
            OmniBufPeerUpdate opMutDecoded = PeerUpdateOmniBuf.unmarshal(opMutBytes);
            if (opMutDecoded.getSequence() != INITIAL_SEQ + 1L) {
                throw new IllegalStateException("Opaque mutate_sequence failed");
            }
            long expectedMutChecksum = checksumGenerated(opMutDecoded);

            FlatPeerUpdate fm = decodeFlatBuffers(flatBytes);
            if (fm.location != null && "Frankfurt".equals(fm.location.city)) {
                fm.sequence = fm.sequence + 1L;
            }
            assertChecksumEqual(
                "FlatBuffers mutate_sequence",
                expectedMutChecksum,
                checksumFlatBuffers(decodeFlatBuffers(encodeFlatBuffers(fbBuilder, fm)))
            );

            PeerUpdate pm = PeerUpdate.parseFrom(protoBytes);
            PeerUpdate.Builder pb = pm.toBuilder();
            if ("Frankfurt".equals(pm.getLocation().getCity())) {
                pb.setSequence(pm.getSequence() + 1L);
            }
            assertChecksumEqual(
                "Protobuf mutate_sequence",
                expectedMutChecksum,
                checksumProtobuf(PeerUpdate.parseFrom(pb.build().toByteArray()))
            );

            JacksonPeerUpdate mm = msgpackMapper.readValue(msgpackBytes, JacksonPeerUpdate.class);
            if (mm.location != null && "Frankfurt".equals(mm.location.city)) {
                mm.sequence = mm.sequence + 1L;
            }
            assertChecksumEqual(
                "MessagePack mutate_sequence",
                expectedMutChecksum,
                checksumJackson(msgpackMapper.readValue(msgpackMapper.writeValueAsBytes(mm), JacksonPeerUpdate.class))
            );

            JacksonPeerUpdate jm = jsonMapper.readValue(jsonBytes, JacksonPeerUpdate.class);
            if (jm.location != null && "Frankfurt".equals(jm.location.city)) {
                jm.sequence = jm.sequence + 1L;
            }
            assertChecksumEqual(
                "JSON mutate_sequence",
                expectedMutChecksum,
                checksumJackson(jsonMapper.readValue(jsonMapper.writeValueAsBytes(jm), JacksonPeerUpdate.class))
            );
        }

        // --------------------------------------------------------------------
        // Stress Category 5 Setup: Large Embedded Message Encode (>16 KiB)
        // --------------------------------------------------------------------
        String largeDc = "d".repeat(LARGE_DC_BYTES);
        String largeRouter = "n".repeat(LARGE_ROUTER_BYTES);

        OmniBufPeerUpdate largeGenSample = buildGeneratedSample(largeDc, largeRouter, 1);
        AnnotatedPeerUpdate largeAnnotatedSample = buildAnnotatedSample(largeDc, largeRouter, 1);
        PeerUpdate largeProtoSample = buildProtobufSample(largeDc, largeRouter, 1);
        FlatPeerUpdate largeFlatSample = buildFlatSample(largeDc, largeRouter, 1);
        JacksonPeerUpdate largeJacksonSample = buildJacksonSample(largeDc, largeRouter, 1);
        FlatBufferBuilder largeFbBuilder = new FlatBufferBuilder(65_536);

        byte[] largeOmnibufBytes = PeerUpdateOmniBuf.marshal(largeGenSample);
        byte[] largeAnnotatedBytes = OmniBufMapper.marshal(largeAnnotatedSample);
        byte[] largeOmniProtoBytes = OmniBufProtobuf.marshal(largeProtoSample);
        byte[] largeProtoBytes = largeProtoSample.toByteArray();
        byte[] largeFlatBytes = encodeFlatBuffers(largeFbBuilder, largeFlatSample);
        byte[] largeMsgpackBytes = msgpackMapper.writeValueAsBytes(largeJacksonSample);
        byte[] largeJsonBytes = jsonMapper.writeValueAsBytes(largeJacksonSample);

        if (largeOmnibufBytes.length != 39_256
                || !Arrays.equals(largeOmnibufBytes, largeAnnotatedBytes)
                || !Arrays.equals(largeOmnibufBytes, largeOmniProtoBytes)) {
            throw new IllegalStateException(
                "Large nested OmniBuf wire output mismatch: length=" + largeOmnibufBytes.length
            );
        }
        long largeChecksum = checksumGenerated(PeerUpdateOmniBuf.unmarshal(largeOmnibufBytes));
        assertChecksumEqual(
            "Large FlatBuffers",
            largeChecksum,
            checksumFlatBuffers(decodeFlatBuffers(largeFlatBytes))
        );
        assertChecksumEqual(
            "Large Protobuf",
            largeChecksum,
            checksumProtobuf(PeerUpdate.parseFrom(largeProtoBytes))
        );

        // --------------------------------------------------------------------
        // Stress Category 6 Setup: Embedded Message Growth (32 Neighbors)
        // --------------------------------------------------------------------
        OmniBufPeerUpdate manyGenSample = buildGeneratedSample(null, null, 8);
        PeerUpdate manyProtoSample = buildProtobufSample(null, null, 8);
        FlatPeerUpdate manyFlatSample = buildFlatSample(null, null, 8);
        JacksonPeerUpdate manyJacksonSample = buildJacksonSample(null, null, 8);
        FlatBufferBuilder growFbBuilder = new FlatBufferBuilder(32_768);

        byte[] manyOmnibufBytes = PeerUpdateOmniBuf.marshal(manyGenSample);
        byte[] manyProtoBytes = manyProtoSample.toByteArray();
        byte[] manyFlatBytes = encodeFlatBuffers(growFbBuilder, manyFlatSample);
        byte[] manyMsgpackBytes = msgpackMapper.writeValueAsBytes(manyJacksonSample);
        byte[] manyJsonBytes = jsonMapper.writeValueAsBytes(manyJacksonSample);

        {
            OpaquePeerUpdate growView = new OpaquePeerUpdate(manyOmnibufBytes);
            OpaqueLocation growLoc = growView.getLocation();
            if (growLoc != null && "Frankfurt".equals(growLoc.getCity())) {
                growView.setLocation(
                    new OmniBufLocation()
                        .setRegion(growLoc.getRegion())
                        .setDatacenter(largeDc)
                        .setCity(growLoc.getCity())
                        .setRack(growLoc.getRack())
                        .setLatitude(growLoc.getLatitude())
                        .setLongitude(growLoc.getLongitude())
                );
            }
            OmniBufPeerUpdate grownDecoded = PeerUpdateOmniBuf.unmarshal(growView.marshal());
            if (grownDecoded.getNeighbors().size() != 32
                    || grownDecoded.getLocation().getDatacenter().length() != LARGE_DC_BYTES) {
                throw new IllegalStateException("Opaque mutate_embedded_grow parity check failed");
            }
            long expectedGrowChecksum = checksumGenerated(grownDecoded);

            FlatPeerUpdate fg = decodeFlatBuffers(manyFlatBytes);
            if (fg.location != null && "Frankfurt".equals(fg.location.city)) {
                fg.location.datacenter = largeDc;
            }
            assertChecksumEqual(
                "FlatBuffers mutate_embedded_grow",
                expectedGrowChecksum,
                checksumFlatBuffers(decodeFlatBuffers(encodeFlatBuffers(growFbBuilder, fg)))
            );

            PeerUpdate pg = PeerUpdate.parseFrom(manyProtoBytes);
            PeerUpdate.Builder pgb = pg.toBuilder();
            if ("Frankfurt".equals(pg.getLocation().getCity())) {
                pgb.setLocation(pg.getLocation().toBuilder().setDatacenter(largeDc).build());
            }
            assertChecksumEqual(
                "Protobuf mutate_embedded_grow",
                expectedGrowChecksum,
                checksumProtobuf(PeerUpdate.parseFrom(pgb.build().toByteArray()))
            );
        }

        String runtime = "OpenJDK " + Runtime.version().version().stream()
            .map(String::valueOf)
            .reduce((a, b) -> a + "." + b)
            .orElse("21");
        String platform = detectPlatform();
        String arch = detectArch();
        String cpu = detectCpuModel(platform, arch);

        System.out.println("============================================================================");
        System.out.printf(
            Locale.ROOT,
            "OmniBuf-Java Benchmark Suite%s (%s / %s-%s / %s)%n",
            smokeMode ? " [SMOKE]" : "",
            runtime,
            platform,
            arch,
            cpu
        );
        System.out.println("Workload: Canonical PeerUpdate (366B OmniBuf, 341B Protobuf, 600B FlatBuffers)");
        System.out.println("============================================================================");

        // 1. Full Message Encode Speed
        List<BenchmarkResultEntry> encodeResults = sortResults(List.of(
            runBenchmark(
                "OmniBuf (Generated)",
                omnibufBytes.length,
                true,
                true,
                smokeMode,
                () -> consumeBytes(PeerUpdateOmniBuf.marshal(genSample))
            ),
            runBenchmark(
                "OmniBuf (@OmniBufField)",
                annotatedBytes.length,
                true,
                false,
                smokeMode,
                () -> consumeBytes(OmniBufMapper.marshal(annotatedSample))
            ),
            runBenchmark(
                "OmniBuf (Protobuf)",
                omniProtoBytes.length,
                true,
                false,
                smokeMode,
                () -> consumeBytes(OmniBufProtobuf.marshal(protoSample))
            ),
            runBenchmark(
                "Protobuf (protobuf-java)",
                protoBytes.length,
                false,
                false,
                smokeMode,
                () -> consumeBytes(protoSample.toByteArray())
            ),
            runBenchmark(
                "FlatBuffers",
                flatBytes.length,
                false,
                false,
                smokeMode,
                () -> consumeBytes(encodeFlatBuffers(fbBuilder, flatSample))
            ),
            runBenchmark(
                "MessagePack (Jackson)",
                msgpackBytes.length,
                false,
                false,
                smokeMode,
                () -> {
                    try {
                        return consumeBytes(msgpackMapper.writeValueAsBytes(jacksonSample));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            ),
            runBenchmark(
                "JSON (Jackson)",
                jsonBytes.length,
                false,
                false,
                smokeMode,
                () -> {
                    try {
                        return consumeBytes(jsonMapper.writeValueAsBytes(jacksonSample));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            )
        ));
        BenchmarkCategory encodeCat = new BenchmarkCategory(
            "encode",
            "Full Message Encode Speed",
            "ns",
            encodeResults
        );
        printCategoryTable(encodeCat);

        // 2. Full Message Decode Speed (all 11 fields consumed via checksum into SINK)
        List<BenchmarkResultEntry> decodeResults = sortResults(List.of(
            runBenchmark(
                "OmniBuf (Generated)",
                null,
                true,
                true,
                smokeMode,
                () -> checksumGenerated(PeerUpdateOmniBuf.unmarshal(omnibufBytes))
            ),
            runBenchmark(
                "OmniBuf (@OmniBufField)",
                null,
                true,
                false,
                smokeMode,
                () -> checksumAnnotated(OmniBufMapper.unmarshal(annotatedBytes, AnnotatedPeerUpdate.class))
            ),
            runBenchmark(
                "OmniBuf (Protobuf)",
                null,
                true,
                false,
                smokeMode,
                () -> checksumProtobuf(OmniBufProtobuf.unmarshal(omniProtoBytes, PeerUpdate.getDefaultInstance()))
            ),
            runBenchmark(
                "Protobuf (protobuf-java)",
                null,
                false,
                false,
                smokeMode,
                () -> {
                    try {
                        return checksumProtobuf(PeerUpdate.parseFrom(protoBytes));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            ),
            runBenchmark(
                "FlatBuffers",
                null,
                false,
                false,
                smokeMode,
                () -> checksumFlatBuffers(decodeFlatBuffers(flatBytes))
            ),
            runBenchmark(
                "MessagePack (Jackson)",
                null,
                false,
                false,
                smokeMode,
                () -> {
                    try {
                        return checksumJackson(msgpackMapper.readValue(msgpackBytes, JacksonPeerUpdate.class));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            ),
            runBenchmark(
                "JSON (Jackson)",
                null,
                false,
                false,
                smokeMode,
                () -> {
                    try {
                        return checksumJackson(jsonMapper.readValue(jsonBytes, JacksonPeerUpdate.class));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            )
        ));
        BenchmarkCategory decodeCat = new BenchmarkCategory(
            "decode",
            "Full Message Decode Speed",
            "ns",
            decodeResults
        );
        printCategoryTable(decodeCat);

        // 3. Read a Single Nested Field
        List<BenchmarkResultEntry> lookupResults = sortResults(List.of(
            runBenchmark(
                "OmniBuf (Generated)",
                null,
                true,
                true,
                smokeMode,
                () -> {
                    OpaqueLocation loc = new OpaquePeerUpdate(omnibufBytes).getLocation();
                    String city = loc != null ? loc.getCity() : "";
                    if (!"Frankfurt".equals(city)) {
                        throw new IllegalStateException("Expected Frankfurt");
                    }
                    return city.charAt(0);
                }
            ),
            runBenchmark(
                "FlatBuffers",
                null,
                false,
                false,
                smokeMode,
                () -> {
                    String city = lookupFlatBuffersCity(flatBytes);
                    if (!"Frankfurt".equals(city)) {
                        throw new IllegalStateException("Expected Frankfurt");
                    }
                    return city.charAt(0);
                }
            ),
            runBenchmark(
                "Protobuf (protobuf-java)",
                null,
                false,
                false,
                smokeMode,
                () -> {
                    try {
                        String city = PeerUpdate.parseFrom(protoBytes).getLocation().getCity();
                        if (!"Frankfurt".equals(city)) {
                            throw new IllegalStateException("Expected Frankfurt");
                        }
                        return city.charAt(0);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            ),
            runBenchmark(
                "MessagePack (Jackson)",
                null,
                false,
                false,
                smokeMode,
                () -> {
                    try {
                        String city = msgpackMapper
                            .readValue(msgpackBytes, JacksonPeerUpdate.class)
                            .location
                            .city;
                        if (!"Frankfurt".equals(city)) {
                            throw new IllegalStateException("Expected Frankfurt");
                        }
                        return city.charAt(0);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            ),
            runBenchmark(
                "JSON (Jackson)",
                null,
                false,
                false,
                smokeMode,
                () -> {
                    try {
                        String city = jsonMapper
                            .readValue(jsonBytes, JacksonPeerUpdate.class)
                            .location
                            .city;
                        if (!"Frankfurt".equals(city)) {
                            throw new IllegalStateException("Expected Frankfurt");
                        }
                        return city.charAt(0);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            )
        ));
        BenchmarkCategory lookupCat = new BenchmarkCategory(
            "lookup_city",
            "Read a Single Nested Field",
            "ns",
            lookupResults
        );
        printCategoryTable(lookupCat);

        // 4. Selective Single-Field Mutation
        List<BenchmarkResultEntry> mutateResults = sortResults(List.of(
            runBenchmark(
                "OmniBuf (Generated)",
                null,
                true,
                true,
                smokeMode,
                () -> {
                    OpaquePeerUpdate view = new OpaquePeerUpdate(omnibufBytes);
                    OpaqueLocation loc = view.getLocation();
                    if (loc != null && "Frankfurt".equals(loc.getCity())) {
                        view.setSequence(view.getSequence() + 1L);
                    }
                    return consumeBytes(view.marshal());
                }
            ),
            runBenchmark(
                "FlatBuffers",
                null,
                false,
                false,
                smokeMode,
                () -> {
                    FlatPeerUpdate m = decodeFlatBuffers(flatBytes);
                    if (m.location != null && "Frankfurt".equals(m.location.city)) {
                        m.sequence = m.sequence + 1L;
                    }
                    return consumeBytes(encodeFlatBuffers(fbBuilder, m));
                }
            ),
            runBenchmark(
                "Protobuf (protobuf-java)",
                null,
                false,
                false,
                smokeMode,
                () -> {
                    try {
                        PeerUpdate parsed = PeerUpdate.parseFrom(protoBytes);
                        PeerUpdate.Builder b = parsed.toBuilder();
                        if ("Frankfurt".equals(parsed.getLocation().getCity())) {
                            b.setSequence(parsed.getSequence() + 1L);
                        }
                        return consumeBytes(b.build().toByteArray());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            ),
            runBenchmark(
                "MessagePack (Jackson)",
                null,
                false,
                false,
                smokeMode,
                () -> {
                    try {
                        JacksonPeerUpdate m =
                            msgpackMapper.readValue(msgpackBytes, JacksonPeerUpdate.class);
                        if (m.location != null && "Frankfurt".equals(m.location.city)) {
                            m.sequence = m.sequence + 1L;
                        }
                        return consumeBytes(msgpackMapper.writeValueAsBytes(m));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            ),
            runBenchmark(
                "JSON (Jackson)",
                null,
                false,
                false,
                smokeMode,
                () -> {
                    try {
                        JacksonPeerUpdate m = jsonMapper.readValue(jsonBytes, JacksonPeerUpdate.class);
                        if (m.location != null && "Frankfurt".equals(m.location.city)) {
                            m.sequence = m.sequence + 1L;
                        }
                        return consumeBytes(jsonMapper.writeValueAsBytes(m));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            )
        ));
        BenchmarkCategory mutateCat = new BenchmarkCategory(
            "mutate_sequence",
            "Selective Single-Field Mutation",
            "ns",
            mutateResults
        );
        printCategoryTable(mutateCat);

        // 5. Large Embedded Message Encode
        List<BenchmarkResultEntry> largeEncodeResults = sortResults(List.of(
            runBenchmark(
                "OmniBuf (Generated)",
                largeOmnibufBytes.length,
                true,
                true,
                smokeMode,
                () -> consumeBytes(PeerUpdateOmniBuf.marshal(largeGenSample))
            ),
            runBenchmark(
                "OmniBuf (@OmniBufField)",
                largeAnnotatedBytes.length,
                true,
                false,
                smokeMode,
                () -> consumeBytes(OmniBufMapper.marshal(largeAnnotatedSample))
            ),
            runBenchmark(
                "OmniBuf (Protobuf)",
                largeOmniProtoBytes.length,
                true,
                false,
                smokeMode,
                () -> consumeBytes(OmniBufProtobuf.marshal(largeProtoSample))
            ),
            runBenchmark(
                "Protobuf (protobuf-java)",
                largeProtoBytes.length,
                false,
                false,
                smokeMode,
                () -> consumeBytes(largeProtoSample.toByteArray())
            ),
            runBenchmark(
                "FlatBuffers",
                largeFlatBytes.length,
                false,
                false,
                smokeMode,
                () -> consumeBytes(encodeFlatBuffers(largeFbBuilder, largeFlatSample))
            ),
            runBenchmark(
                "MessagePack (Jackson)",
                largeMsgpackBytes.length,
                false,
                false,
                smokeMode,
                () -> {
                    try {
                        return consumeBytes(msgpackMapper.writeValueAsBytes(largeJacksonSample));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            ),
            runBenchmark(
                "JSON (Jackson)",
                largeJsonBytes.length,
                false,
                false,
                smokeMode,
                () -> {
                    try {
                        return consumeBytes(jsonMapper.writeValueAsBytes(largeJacksonSample));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            )
        ));
        BenchmarkCategory largeEncodeCat = new BenchmarkCategory(
            "encode_nested_shift",
            "Large Embedded Message Encode",
            "ns",
            largeEncodeResults
        );
        printCategoryTable(largeEncodeCat);

        // 6. Embedded Message Growth
        List<BenchmarkResultEntry> growResults = sortResults(List.of(
            runBenchmark(
                "OmniBuf (Generated)",
                null,
                true,
                true,
                smokeMode,
                () -> {
                    OpaquePeerUpdate view = new OpaquePeerUpdate(manyOmnibufBytes);
                    OpaqueLocation loc = view.getLocation();
                    if (loc != null && "Frankfurt".equals(loc.getCity())) {
                        view.setLocation(
                            new OmniBufLocation()
                                .setRegion(loc.getRegion())
                                .setDatacenter(largeDc)
                                .setCity(loc.getCity())
                                .setRack(loc.getRack())
                                .setLatitude(loc.getLatitude())
                                .setLongitude(loc.getLongitude())
                        );
                    }
                    return consumeBytes(view.marshal());
                }
            ),
            runBenchmark(
                "FlatBuffers",
                null,
                false,
                false,
                smokeMode,
                () -> {
                    FlatPeerUpdate m = decodeFlatBuffers(manyFlatBytes);
                    if (m.location != null && "Frankfurt".equals(m.location.city)) {
                        m.location.datacenter = largeDc;
                    }
                    return consumeBytes(encodeFlatBuffers(growFbBuilder, m));
                }
            ),
            runBenchmark(
                "Protobuf (protobuf-java)",
                null,
                false,
                false,
                smokeMode,
                () -> {
                    try {
                        PeerUpdate parsed = PeerUpdate.parseFrom(manyProtoBytes);
                        PeerUpdate.Builder b = parsed.toBuilder();
                        if ("Frankfurt".equals(parsed.getLocation().getCity())) {
                            b.setLocation(parsed.getLocation().toBuilder().setDatacenter(largeDc).build());
                        }
                        return consumeBytes(b.build().toByteArray());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            ),
            runBenchmark(
                "MessagePack (Jackson)",
                null,
                false,
                false,
                smokeMode,
                () -> {
                    try {
                        JacksonPeerUpdate m =
                            msgpackMapper.readValue(manyMsgpackBytes, JacksonPeerUpdate.class);
                        if (m.location != null && "Frankfurt".equals(m.location.city)) {
                            m.location.datacenter = largeDc;
                        }
                        return consumeBytes(msgpackMapper.writeValueAsBytes(m));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            ),
            runBenchmark(
                "JSON (Jackson)",
                null,
                false,
                false,
                smokeMode,
                () -> {
                    try {
                        JacksonPeerUpdate m =
                            jsonMapper.readValue(manyJsonBytes, JacksonPeerUpdate.class);
                        if (m.location != null && "Frankfurt".equals(m.location.city)) {
                            m.location.datacenter = largeDc;
                        }
                        return consumeBytes(jsonMapper.writeValueAsBytes(m));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            )
        ));
        BenchmarkCategory growCat = new BenchmarkCategory(
            "mutate_embedded_grow",
            "Embedded Message Growth",
            "ns",
            growResults
        );
        printCategoryTable(growCat);

        if (smokeMode) {
            System.out.println();
            System.out.println(
                "Smoke verification passed across all 6 categories (skipped writing testdata/benchmarks.json)."
            );
            return;
        }

        BenchmarkReport report = new BenchmarkReport(
            "java",
            runtime,
            platform,
            arch,
            cpu,
            "PeerUpdate",
            List.of(encodeCat, decodeCat, lookupCat, mutateCat, largeEncodeCat, growCat)
        );

        Path outPath = Path.of("testdata", "benchmarks.json");
        Files.createDirectories(outPath.getParent());
        ObjectMapper prettyJson = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        String jsonText = prettyJson.writeValueAsString(report) + "\n";
        Files.writeString(outPath, jsonText, StandardCharsets.UTF_8);

        System.out.println();
        System.out.println("Wrote structured benchmark results to " + outPath.toAbsolutePath());
    }
}
