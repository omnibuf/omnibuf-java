# List available recipes
default:
    @just --list

# Lint Protobuf schemas and regenerate Java sources via buf
proto:
    buf lint
    mvn -q dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/dependency
    mkdir -p target/classes
    javac --release 21 -cp "target/dependency/*" -d target/classes src/main/java/com/omnibuf/plugin/ProtocGenOmniBufJava.java
    rm -rf src/main/gen
    buf generate
    mvn -q process-classes

# Alias for proto code generation
generate: proto

# Compile main + test sources and package the Maven artifact
build:
    mvn -q test-compile package -DskipTests

# Run the JUnit 5 unit test suite
test:
    mvn test

# Run the cross-language 584-case OmniBuf conformance suite against ./bin/conformance-java
conformance runner="../omnibuf-go/bin/conformance-runner":
    mvn -q process-classes
    "{{runner}}" ./bin/conformance-java

# Run the Java 21 PeerUpdate benchmark suite and write testdata/benchmarks.json
bench:
    mvn -q process-classes
    java -cp "target/classes:target/dependency/*" com.omnibuf.bench.BenchmarkMain

# Lint Protobuf schemas with buf and compile all Java sources with -Xlint:all and failOnWarning
lint:
    buf lint
    buf format -d --exit-code
    mvn -q test-compile -Dmaven.compiler.showWarnings=true -Dmaven.compiler.failOnWarning=true

# Format Protobuf schemas in place with buf
fmt:
    buf format -w

# Full local verification pipeline: lint, build, JUnit tests, and 584-case conformance
ci: lint build test conformance
