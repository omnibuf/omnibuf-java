# omnibuf-java

[![OmniBuf Spec: v1 DRAFT-1](https://img.shields.io/badge/OmniBuf%20Spec-v1%20DRAFT--1-FF4F18)](https://omnibuf.com/spec)

Java 21 implementation of the [OmniBuf v1 DRAFT-1 wire format](https://omnibuf.com/spec). Documentation and benchmarks live at [omnibuf.com/java](https://omnibuf.com/java).

## Install

```xml
<dependency>
  <groupId>com.omnibuf</groupId>
  <artifactId>omnibuf-java</artifactId>
  <version>0.1.6</version>
</dependency>
```

## Quick usage

```java
import com.omnibuf.MarshalOptions;
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

byte[] wire = OmniBufMapper.marshal(
    new SensorReading("rack-12-top", -45, true),
    MarshalOptions.SELF_DESCRIBING
);
SensorReading decoded = OmniBufMapper.unmarshal(wire, SensorReading.class);
```

## Details

- Requires Java 21+. Uses `VarHandle` little-endian access, `Integer.numberOfTrailingZeros` prefix-varints, and single-pass container backpatching.
- `OmniBufMapper` maps `@OmniBufMessage` and `@OmniBufField` POJOs and Java `record` types via `MethodHandle` accessors.
- `OmniBufProtobuf` encodes and decodes standard `com.google.protobuf.Message` types.
- `protoc-gen-omnibuf-java` generates static codecs and lazy `Opaque*` views with single-field wire splicing.
- `OmniBufBlind.decodeBlindJson` inspects arbitrary packets and resolves inline `Field #0` schema metadata.
- Ships with `Automatic-Module-Name: com.omnibuf`. Under JPMS, named modules using `OmniBufMapper` must open their message packages to `com.omnibuf` for reflective lookup; generated codecs do not require this.

## License

Apache-2.0. See [LICENSE](./LICENSE).
