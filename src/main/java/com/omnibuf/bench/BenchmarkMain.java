package com.omnibuf.bench;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.omnibuf.OmniBufMapper;
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
import java.io.BufferedReader;
import java.io.InputStreamReader;
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
 * Reproducible Java 21 benchmark runner on the canonical 11-field {@code PeerUpdate} workload.
 *
 * <p>Performs JVM C2 JIT warmup iterations before timed measurement, accumulates outputs into a
 * {@code volatile} blackhole to prevent dead-code elimination, prints console tables, and writes
 * structured results to {@code testdata/benchmarks.json}.
 */
public final class BenchmarkMain {

    private static final long WARMUP_NANOS = 400_000_000L;
    private static final long MEASURE_NANOS = 600_000_000L;
    private static final long INITIAL_SEQ = 9_876_543_210L;
    private static final long MUTATED_SEQ = 9_876_543_211L;

    /** Public volatile sink preventing C2 dead-code elimination across benchmark loops. */
    public static volatile long blackhole = 0L;

    private BenchmarkMain() {}

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
    // Canonical SamplePeerUpdate Builders
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

    private static OmniBufPeerUpdate buildGeneratedSample() {
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
                    .setDatacenter("FRA-Equinix-FR5")
                    .setCity("Frankfurt")
                    .setRack(42)
                    .setLatitude(50.1109f)
                    .setLongitude(8.6821f)
            )
            .setNeighbors(new ArrayList<>(List.of(
                new OmniBufNeighbor()
                    .setPeerAsn(3356)
                    .setRouter("ae0.fra1.level3.net")
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
            )))
            .setSequence(INITIAL_SEQ);
    }

    private static AnnotatedPeerUpdate buildAnnotatedSample() {
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
            "FRA-Equinix-FR5",
            "Frankfurt",
            42,
            50.1109f,
            8.6821f
        );
        msg.neighbors = new ArrayList<>(List.of(
            new AnnotatedNeighbor(3356, "ae0.fra1.level3.net", 850, true),
            new AnnotatedNeighbor(1299, "ffm-b5-link.telia.net", 920, true),
            new AnnotatedNeighbor(174, "be2040.ccr41.fra03.atlas.cogentco.com", 1120, true),
            new AnnotatedNeighbor(2914, "ae-12.r24.frnkge08.de.bb.gin.ntt.net", 980, false)
        ));
        msg.sequence = INITIAL_SEQ;
        return msg;
    }

    private static PeerUpdate buildProtobufSample() {
        return PeerUpdate.newBuilder()
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
                    .setDatacenter("FRA-Equinix-FR5")
                    .setCity("Frankfurt")
                    .setRack(42)
                    .setLatitude(50.1109f)
                    .setLongitude(8.6821f)
                    .build()
            )
            .addNeighbors(
                Neighbor.newBuilder()
                    .setPeerAsn(3356)
                    .setRouter("ae0.fra1.level3.net")
                    .setLatencyUs(850)
                    .setEstablished(true)
                    .build()
            )
            .addNeighbors(
                Neighbor.newBuilder()
                    .setPeerAsn(1299)
                    .setRouter("ffm-b5-link.telia.net")
                    .setLatencyUs(920)
                    .setEstablished(true)
                    .build()
            )
            .addNeighbors(
                Neighbor.newBuilder()
                    .setPeerAsn(174)
                    .setRouter("be2040.ccr41.fra03.atlas.cogentco.com")
                    .setLatencyUs(1120)
                    .setEstablished(true)
                    .build()
            )
            .addNeighbors(
                Neighbor.newBuilder()
                    .setPeerAsn(2914)
                    .setRouter("ae-12.r24.frnkge08.de.bb.gin.ntt.net")
                    .setLatencyUs(980)
                    .setEstablished(false)
                    .build()
            )
            .setSequence(INITIAL_SEQ)
            .build();
    }

    private static JacksonPeerUpdate buildJacksonSample() {
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
            "FRA-Equinix-FR5",
            "Frankfurt",
            42L,
            50.1109f,
            8.6821f
        );
        msg.neighbors = List.of(
            new JacksonNeighbor(3356L, "ae0.fra1.level3.net", 850L, true),
            new JacksonNeighbor(1299L, "ffm-b5-link.telia.net", 920L, true),
            new JacksonNeighbor(174L, "be2040.ccr41.fra03.atlas.cogentco.com", 1120L, true),
            new JacksonNeighbor(2914L, "ae-12.r24.frnkge08.de.bb.gin.ntt.net", 980L, false)
        );
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
            LongSupplier op) {
        // Phase 1: JVM C2 JIT warmup
        long warmupAcc = 0L;
        long warmupDeadline = System.nanoTime() + WARMUP_NANOS;
        while (System.nanoTime() < warmupDeadline) {
            for (int i = 0; i < 256; i++) {
                warmupAcc ^= op.getAsLong();
            }
        }
        blackhole ^= warmupAcc;

        // Calibrate batch size so System.nanoTime() overhead is negligible
        long calStart = System.nanoTime();
        long calAcc = 0L;
        int calOps = 1024;
        for (int i = 0; i < calOps; i++) {
            calAcc ^= op.getAsLong();
        }
        long calElapsed = Math.max(1L, System.nanoTime() - calStart);
        blackhole ^= calAcc;
        int batchSize = (int) Math.clamp((10_000_000L * calOps) / calElapsed, 64L, 16_384L);

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
        } while (elapsed < MEASURE_NANOS);
        blackhole ^= acc;

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
        OmniBufPeerUpdate genSample = buildGeneratedSample();
        AnnotatedPeerUpdate annotatedSample = buildAnnotatedSample();
        PeerUpdate protoSample = buildProtobufSample();
        JacksonPeerUpdate jacksonSample = buildJacksonSample();

        ObjectMapper jsonMapper = new ObjectMapper();
        ObjectMapper msgpackMapper = new ObjectMapper(new MessagePackFactory());

        byte[] omnibufBytes = PeerUpdateOmniBuf.marshal(genSample);
        byte[] annotatedBytes = OmniBufMapper.marshal(annotatedSample);
        byte[] protoBytes = protoSample.toByteArray();
        byte[] msgpackBytes = msgpackMapper.writeValueAsBytes(jacksonSample);
        byte[] jsonBytes = jsonMapper.writeValueAsBytes(jacksonSample);

        // Verify canonical byte sizes and bit-identical OmniBuf wire output
        if (omnibufBytes.length != 366) {
            throw new IllegalStateException(
                "Expected OmniBuf PeerUpdate size 366B, got " + omnibufBytes.length
            );
        }
        if (annotatedBytes.length != 366 || !Arrays.equals(omnibufBytes, annotatedBytes)) {
            throw new IllegalStateException(
                "Annotated PeerUpdate bytes did not match canonical 366B OmniBuf encoding"
            );
        }
        if (protoBytes.length != 341) {
            throw new IllegalStateException(
                "Expected Protobuf PeerUpdate size 341B, got " + protoBytes.length
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
            "OmniBuf-Java Benchmark Suite (%s / %s-%s / %s)%n",
            runtime,
            platform,
            arch,
            cpu
        );
        System.out.println("Workload: Canonical PeerUpdate (366B OmniBuf, 341B Protobuf)");
        System.out.println("============================================================================");

        // 1. Encode
        List<BenchmarkResultEntry> encodeResults = sortResults(List.of(
            runBenchmark(
                "OmniBuf (Generated)",
                omnibufBytes.length,
                true,
                true,
                () -> PeerUpdateOmniBuf.marshal(genSample).length
            ),
            runBenchmark(
                "OmniBuf (@OmniBufField)",
                annotatedBytes.length,
                true,
                false,
                () -> OmniBufMapper.marshal(annotatedSample).length
            ),
            runBenchmark(
                "Protobuf (protobuf-java)",
                protoBytes.length,
                false,
                false,
                () -> protoSample.toByteArray().length
            ),
            runBenchmark(
                "MessagePack (Jackson)",
                msgpackBytes.length,
                false,
                false,
                () -> {
                    try {
                        return msgpackMapper.writeValueAsBytes(jacksonSample).length;
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
                () -> {
                    try {
                        return jsonMapper.writeValueAsBytes(jacksonSample).length;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            )
        ));
        BenchmarkCategory encodeCat = new BenchmarkCategory(
            "encode",
            "Full Message Encode Speed (PeerUpdate)",
            "ns",
            encodeResults
        );
        printCategoryTable(encodeCat);

        // 2. Decode
        List<BenchmarkResultEntry> decodeResults = sortResults(List.of(
            runBenchmark(
                "OmniBuf (Generated)",
                null,
                true,
                true,
                () -> PeerUpdateOmniBuf.unmarshal(omnibufBytes).getSequence()
            ),
            runBenchmark(
                "OmniBuf (@OmniBufField)",
                null,
                true,
                false,
                () -> OmniBufMapper.unmarshal(annotatedBytes, AnnotatedPeerUpdate.class).sequence
            ),
            runBenchmark(
                "Protobuf (protobuf-java)",
                null,
                false,
                false,
                () -> {
                    try {
                        return PeerUpdate.parseFrom(protoBytes).getSequence();
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
                () -> {
                    try {
                        return msgpackMapper.readValue(msgpackBytes, JacksonPeerUpdate.class).sequence;
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
                () -> {
                    try {
                        return jsonMapper.readValue(jsonBytes, JacksonPeerUpdate.class).sequence;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            )
        ));
        BenchmarkCategory decodeCat = new BenchmarkCategory(
            "decode",
            "Full Message Decode Speed (PeerUpdate)",
            "ns",
            decodeResults
        );
        printCategoryTable(decodeCat);

        // 3. Read a Single Nested Field (peer.location.city)
        List<BenchmarkResultEntry> lookupResults = sortResults(List.of(
            runBenchmark(
                "OmniBuf (Generated)",
                null,
                true,
                true,
                () -> {
                    OpaqueLocation loc = new OpaquePeerUpdate(omnibufBytes).getLocation();
                    String city = loc != null ? loc.getCity() : "";
                    if (city.length() != 9) {
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
                () -> {
                    try {
                        String city = PeerUpdate.parseFrom(protoBytes).getLocation().getCity();
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
                () -> {
                    try {
                        String city = msgpackMapper
                            .readValue(msgpackBytes, JacksonPeerUpdate.class)
                            .location
                            .city;
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
                () -> {
                    try {
                        String city = jsonMapper
                            .readValue(jsonBytes, JacksonPeerUpdate.class)
                            .location
                            .city;
                        return city.charAt(0);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            )
        ));
        BenchmarkCategory lookupCat = new BenchmarkCategory(
            "lookup_city",
            "Read a Single Nested Field (peer.location.city)",
            "ns",
            lookupResults
        );
        printCategoryTable(lookupCat);

        // 4. Selective Single-Field Mutation (peer.sequence)
        List<BenchmarkResultEntry> mutateResults = sortResults(List.of(
            runBenchmark(
                "OmniBuf (Generated)",
                null,
                true,
                true,
                () -> new OpaquePeerUpdate(omnibufBytes).setSequence(MUTATED_SEQ).marshal().length
            ),
            runBenchmark(
                "Protobuf (protobuf-java)",
                null,
                false,
                false,
                () -> {
                    try {
                        return PeerUpdate.parseFrom(protoBytes)
                            .toBuilder()
                            .setSequence(MUTATED_SEQ)
                            .build()
                            .toByteArray()
                            .length;
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
                () -> {
                    try {
                        JacksonPeerUpdate m =
                            msgpackMapper.readValue(msgpackBytes, JacksonPeerUpdate.class);
                        m.sequence = MUTATED_SEQ;
                        return msgpackMapper.writeValueAsBytes(m).length;
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
                () -> {
                    try {
                        JacksonPeerUpdate m = jsonMapper.readValue(jsonBytes, JacksonPeerUpdate.class);
                        m.sequence = MUTATED_SEQ;
                        return jsonMapper.writeValueAsBytes(m).length;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            )
        ));
        BenchmarkCategory mutateCat = new BenchmarkCategory(
            "mutate_sequence",
            "Selective Single-Field Mutation (peer.sequence)",
            "ns",
            mutateResults
        );
        printCategoryTable(mutateCat);

        BenchmarkReport report = new BenchmarkReport(
            "java",
            runtime,
            platform,
            arch,
            cpu,
            "PeerUpdate",
            List.of(encodeCat, decodeCat, lookupCat, mutateCat)
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
