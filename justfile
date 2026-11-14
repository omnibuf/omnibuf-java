# List available recipes
default:
    @just --list

# Lint Protobuf schemas and regenerate Java sources via buf
proto:
    buf lint
    mvn -q package -Dmaven.test.skip=true
    rm -rf src/main/gen
    buf generate
    mvn -q process-test-classes

# Alias for proto code generation
generate: proto

# Compile main + test sources and package the clean runtime JAR + standalone protoc plugin JAR
build:
    mvn -q package process-test-classes -DskipTests

# Run the JUnit 5 unit test suite
test:
    mvn test

# Run the cross-language 758-case OmniBuf conformance suite against ./bin/conformance-java
conformance runner="../omnibuf-go/bin/conformance-runner":
    mvn -q process-test-classes
    "{{runner}}" ./bin/conformance-java

# Run the Java 21 PeerUpdate benchmark suite and write testdata/benchmarks.json
bench *args="":
    mvn -q process-test-classes
    java -cp "target/test-classes:target/classes:target/dependency/*" com.omnibuf.bench.BenchmarkMain {{args}}

# Lint Protobuf schemas with buf and compile all Java sources with -Xlint:all and failOnWarning
lint:
    buf lint
    buf format -d --exit-code
    mvn -q test-compile -Dmaven.compiler.showWarnings=true -Dmaven.compiler.failOnWarning=true

# Format Protobuf schemas in place with buf
fmt:
    buf format -w

# Full local verification pipeline: lint, build, JUnit tests, and 758-case conformance
ci: lint build test conformance
