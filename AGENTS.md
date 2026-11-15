# AGENTS.md: OmniBuf Java 21 Guide

Repository structure, build commands, and wire-format invariants for contributors and coding agents working inside `omnibuf-java`.

## Toolchain

`mise.toml` pins all required local tools:

- `java = "temurin-21"`
- `maven = "latest"`
- `just = "1.58.0"`
- `protobuf = "35.1"`
- `buf = "1.75.0"`

Run `mise trust` once inside this directory. All schema linting and code generation run through `buf` (`buf.yaml` v2 and `buf.gen.yaml` v2). Do not invoke raw `protoc` CLI commands directly.

## Common Commands

```bash
just proto        # Lint .proto files and regenerate src/main/gen via buf generate
just lint         # Run buf lint, buf format check, and javac -Xlint:all failOnWarning
just test         # Run JUnit 5 test suite
just conformance  # Run all 758 cross-language conformance cases via ./bin/conformance-java
just bench        # Run Java 21 PeerUpdate benchmarks and write testdata/benchmarks.json (pass --smoke for quick check)
just ci           # Full pipeline: lint, build, test, and conformance
```

## Package Layout

- `com.omnibuf`: Public runtime entry points (`OmniBufMapper`, `OmniBufProtobuf`, `OmniBufBlind`, `SchemaMeta`, `MarshalOptions`, `UnmarshalOptions`, `UnsignedLong`, `OmniBufException`).
- `com.omnibuf.annotation`: `@OmniBufMessage`, `@OmniBufField`, and `WireKind` for `TIER_ANNOTATED_STRUCT` classes and Java 21 `record` types.
- `com.omnibuf.wire`: Low-level wire primitives (`WireType`, `PrefixVarint`, `Utf8Validator`, `OmniBufEncoder`, `OmniBufDecoder`, `OmniBufOpaque`, `CodecPool`, `IntArrayList`, `LongArrayList`).
- `com.omnibuf.plugin`: `ProtocGenOmniBufJava` (`CodeGeneratorRequest` to `CodeGeneratorResponse` generator packaged into standalone `target/omnibuf-java-*-protoc-gen.jar` and invoked by `./bin/protoc-gen-omnibuf-java` from `buf.gen.yaml`).
- `src/test/java/com/omnibuf/conformance`: Stdin/stdout 4-byte LE framed conformance harness (`ConformanceMain`, invoked by `./bin/conformance-java`).
- `src/test/java/com/omnibuf/bench`: Reproducible `PeerUpdate` benchmark runner (`BenchmarkMain`).
- `src/main/gen`: Checked-in generated Java test/benchmark schema sources produced by `buf generate` and compiled in test scope.

## Critical Wire Invariants

1. **Prefix-Varint layout**: Encoders write minimal 1B/2B/4B/9B Prefix-Varints for tags, scalars, and element counts, and use 2-byte reserved Prefix-Varint slots (`PrefixVarint.writeOverlong2Byte`) for single-pass container length backpatching. Decoders reject any reserved leading byte where `(b0 & 0x07) == 0 && b0 != 0`.
2. **Nesting depth**: Enforce `WireType.MAX_CONTAINER_DEPTH = 32` across `WireSubMsg`, `WireArray`, `WireMap`, and recursive `Field #0` `SchemaMeta` children.
3. **Strict UTF-8**: `Utf8Validator` uses `CodingErrorAction.REPORT` and RFC 3629 byte checks. Malformed UTF-8 bytes and unpaired UTF-16 surrogates throw `OmniBufException`. Map sorting for `WireString` keys uses `Utf8Validator.UTF8_ORDER` (Unicode codepoint / UTF-8 byte order, not UTF-16 `String.compareTo`).
4. **Container framing**: Reject trailing bytes inside `WireArray` and `WireMap` payloads, including `count == 0` containers. Reject container boolean bytes outside `{0, 1}`.
5. **Blind JSON**: `OmniBufBlind.decodeBlindJson` formats `WireUint` and `WireFixed64` with unsigned 64-bit decimal strings (`UnsignedLong`), formats 32-bit floats using shortest IEEE-754 decimal strings, formats `-0.0` as `"-0.0"`, encodes `WireBytes` as Base64, throws `OmniBufException` on `NaN` or `Infinity` by default, and returns `"{}"` when `fallbackOnNonFinite = true`.
