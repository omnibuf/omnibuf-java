# OmniBuf for Java 21

[![CI](https://github.com/omnibuf/omnibuf-java/actions/workflows/ci.yml/badge.svg)](https://github.com/omnibuf/omnibuf-java/actions/workflows/ci.yml)
[![Wire Spec: v0.1](https://img.shields.io/badge/spec-v0.1-111111?style=flat&labelColor=111111&color=FF4F18)](https://omnibuf.com/spec)
[![Website](https://img.shields.io/badge/website-omnibuf.com-111111?style=flat&labelColor=111111&color=D4FF33)](https://omnibuf.com/java)

Java 21 implementation of **[OmniBuf Wire Specification v0.1](https://omnibuf.com/spec)**, runtime codecs, and `buf` code generator plugin (`protoc-gen-omnibuf-java`).

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
| **Full Message Encode Speed** | **315 ns/op** | 662 ns/op | 364 ns/op | 1,021 ns/op | 1,559 ns/op | 2,401 ns/op |
| **Full Message Decode Speed** | **1,045 ns/op** | 1,056 ns/op | 1,520 ns/op | 1,061 ns/op | 2,950 ns/op | 4,346 ns/op |
| **Read a Single Nested Field** | 127 ns/op | n/a | 1,036 ns/op | **24 ns/op** | 2,607 ns/op | 3,991 ns/op |
| **Selective Single-Field Mutation** | **294 ns/op** | n/a | 1,454 ns/op | 1,453 ns/op | 4,166 ns/op | 6,453 ns/op |
| **Large Embedded Message Encode** | 5,044 ns/op | 5,211 ns/op | **4,384 ns/op** | 77,382 ns/op | 62,896 ns/op | 116,427 ns/op |
| **Embedded Message Growth** | **3,020 ns/op** | n/a | 24,464 ns/op | 46,102 ns/op | 48,786 ns/op | 87,759 ns/op |

`BenchmarkMain` evaluates six categories across `OmniBuf (Generated)`, `OmniBuf (@OmniBufField)`, `OmniBuf (Protobuf)`, `Protobuf (protobuf-java)`, `FlatBuffers`, `MessagePack (Jackson)`, and `JSON (Jackson)`, adding `Large Embedded Message Encode` and `Embedded Message Growth` stress workloads. Run `just bench --smoke` for a fast parity check across all six categories.

## Development Recipes

```bash
just proto        # Lint schemas and regenerate src/main/gen via buf
just build        # Compile and package runtime JAR and shaded protoc-gen JAR in target/
just test         # Run JUnit 5 test suite
just conformance  # Run 758-case cross-language conformance runner
just bench        # Run PeerUpdate benchmarks and write testdata/benchmarks.json
just lint         # Run buf lint, buf format check, and javac -Xlint:all failOnWarning
just ci           # Run lint, build, test, and conformance
```

## License

MIT

## Reflection and modules

The annotated mapper accesses constructors and annotated fields through private
method-handle lookup and reflection. Named Java modules must open the message
package to the OmniBuf module. Generated codecs avoid this reflective access
requirement. Treat message classes as trusted application code: their
constructors and accessors can execute during serialization.
