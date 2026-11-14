# OmniBuf for Java 21

[![CI](https://github.com/omnibuf/omnibuf-java/actions/workflows/ci.yml/badge.svg)](https://github.com/omnibuf/omnibuf-java/actions/workflows/ci.yml)

Java 21 implementation of the OmniBuf binary wire format, runtime codecs, and `buf` code generator plugin (`protoc-gen-omnibuf-java`).

Passes all **758 / 758** cross-language conformance cases (`0 skipped, 0 failed`) across every integration tier.

## Features

- **Prefix-Varint & Little-Endian `VarHandle` Primitives**: Trailing-zero dispatch (`Integer.numberOfTrailingZeros`) with unaligned little-endian `VarHandle` reads and writes.
- **Single-Pass Container Encoding**: `OmniBufEncoder` writes nested sub-messages, typed arrays, and maps in one forward pass via 2-byte reserved length backpatching.
- **Zero-Copy Opaque Views & Byte Splicing**: Generated `Opaque*` wrappers read single nested fields directly from `byte[]` slices and splice updated fields while keeping untouched and unknown field bytes intact.
- **Four Integration Tiers**:
  - Generated static codecs and `Opaque*` views via `protoc-gen-omnibuf-java`.
  - `@OmniBufMessage` and `@OmniBufField` mapper (`OmniBufMapper`) for POJOs and Java 21 `record` types.
  - Descriptor-driven `OmniBufProtobuf` support for standard `com.google.protobuf.Message` classes.
  - Schema-less `OmniBufBlind` inspection and canonical deterministic JSON formatting with inline `Field #0` `SchemaMeta` resolution.

## Requirements & Setup

Tools are pinned in `mise.toml` (`java = "temurin-21"`, `maven = "latest"`, `just = "1.58.0"`, `protobuf = "35.1"`, `buf = "1.75.0"`):

```bash
mise trust
mise install
just ci
```

## Code Generation with `buf`

Schema linting and Java code generation use `buf.yaml` and `buf.gen.yaml` (v2):

```yaml
version: v2
clean: true
plugins:
  - protoc_builtin: java
    out: src/main/gen
  - local: ./bin/protoc-gen-omnibuf-java
    out: src/main/gen
```

Run `just proto` (which executes `buf lint` and `buf generate`) to emit both standard `protobuf-java` classes and OmniBuf `OmniBuf<Msg>`, `<Msg>OmniBuf`, and `Opaque<Msg>` sources into `src/main/gen`.

## Quick Start

### Generated Codec & Opaque View

```java
import com.omnibuf.pb.bench.OmniBufLocation;
import com.omnibuf.pb.bench.OmniBufPeerUpdate;
import com.omnibuf.pb.bench.OpaquePeerUpdate;
import com.omnibuf.pb.bench.PeerUpdateOmniBuf;

OmniBufPeerUpdate msg = new OmniBufPeerUpdate()
    .setAsn(64512)
    .setRouterId("edge-rtr-fra01.core.backbone.net")
    .setActive(true)
    .setLocation(new OmniBufLocation().setCity("Frankfurt").setRack(42))
    .setSequence(9_876_543_210L);

byte[] wire = PeerUpdateOmniBuf.marshal(msg);

// Lazy nested lookup directly on wire bytes
OpaquePeerUpdate view = new OpaquePeerUpdate(wire);
String city = view.getLocation().getCity(); // "Frankfurt"

// Splice a single field update into a validated packet
byte[] updated = view.setSequence(9_876_543_211L).marshalValidated();
```

### Annotated Java 21 Records & Classes

```java
import com.omnibuf.MarshalOptions;
import com.omnibuf.OmniBufBlind;
import com.omnibuf.OmniBufMapper;
import com.omnibuf.annotation.OmniBufField;
import com.omnibuf.annotation.OmniBufMessage;
import com.omnibuf.annotation.WireKind;

@OmniBufMessage(name = "SensorReading")
public record SensorReading(
    @OmniBufField(id = 1, name = "sensor_id") String sensorId,
    @OmniBufField(id = 2, name = "delta_mk", wire = WireKind.SINT) int deltaMk,
    @OmniBufField(id = 3, name = "healthy") boolean healthy
) {}

byte[] packet = OmniBufMapper.marshal(
    new SensorReading("rack-12-top", -45, true),
    MarshalOptions.SELF_DESCRIBING
);

SensorReading decoded = OmniBufMapper.unmarshal(packet, SensorReading.class);
String blindJson = OmniBufBlind.decodeBlindJson(packet);
```

### Standard `protobuf-java` Messages

```java
import com.omnibuf.OmniBufProtobuf;
import com.omnibuf.pb.bench.PeerUpdate;

byte[] packet = OmniBufProtobuf.marshal(protoPeer);
PeerUpdate restored = OmniBufProtobuf.unmarshal(packet, PeerUpdate.getDefaultInstance());
```

## Benchmarks

Measured on Apple M1 Pro (`darwin-arm64`, OpenJDK 21.0.13) using the canonical 11-field `PeerUpdate` workload (`just bench`, structured output in `testdata/benchmarks.json`):

| Category | OmniBuf (Generated) | OmniBuf (@OmniBufField) | Protobuf (protobuf-java) | FlatBuffers | JSON (Jackson) | MessagePack (Jackson) |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: |
| **Wire Size** | **366 B** | **366 B** | 341 B | 600 B | 803 B | 626 B |
| **Full Message Encode Speed** | **343 ns/op** | 627 ns/op | 347 ns/op | 1,030 ns/op | 1,784 ns/op | 2,508 ns/op |
| **Full Message Decode Speed** | 1,110 ns/op | 1,114 ns/op | 1,578 ns/op | **1,034 ns/op** | 3,087 ns/op | 4,614 ns/op |
| **Read a Single Nested Field** | 106 ns/op | n/a | 1,085 ns/op | **23 ns/op** | 2,569 ns/op | 4,151 ns/op |
| **Selective Single-Field Mutation** | **314 ns/op** | n/a | 1,499 ns/op | 1,479 ns/op | 4,443 ns/op | 6,832 ns/op |
| **Large Embedded Message Encode** | 8,634 ns/op | 8,990 ns/op | **4,569 ns/op** | 77,469 ns/op | 64,010 ns/op | 118,690 ns/op |
| **Embedded Message Growth** | **4,774 ns/op** | n/a | 25,025 ns/op | 46,960 ns/op | 51,229 ns/op | 90,798 ns/op |

`BenchmarkMain` evaluates six categories across `OmniBuf (Generated)`, `OmniBuf (@OmniBufField)`, `OmniBuf (Protobuf)`, `Protobuf (protobuf-java)`, `FlatBuffers`, `MessagePack (Jackson)`, and `JSON (Jackson)`, adding `Large Embedded Message Encode` and `Embedded Message Growth` stress workloads. Run `just bench --smoke` for a fast parity check across all six categories.

## Development Recipes

```bash
just proto        # Lint schemas and regenerate src/main/gen via buf
just build        # Compile and package target/omnibuf-java-0.1.0.jar and shaded protoc-gen JAR
just test         # Run JUnit 5 test suite
just conformance  # Run 758-case cross-language conformance runner
just bench        # Run PeerUpdate benchmarks and write testdata/benchmarks.json
just lint         # Run buf lint, buf format check, and javac -Xlint:all failOnWarning
just ci           # Run lint, build, test, and conformance
```

## License

MIT
