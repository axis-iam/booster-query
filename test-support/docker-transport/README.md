# Patched Docker transport for project tests

Testcontainers 2.0.5 constructs docker-java's `ZerodepDockerHttpClient` directly.
Its upstream `docker-java-transport-zerodep:3.7.1` contains relocated HttpClient
5.5.1 and HttpCore 5.3.6 classes. Changing ordinary Gradle dependency constraints
cannot replace those embedded classes.

This isolated Gradle project builds a local replacement from Maven Central
artifacts:

| Input | Version | Use |
|-------|---------|-----|
| docker-java-transport-zerodep | 3.7.1 | Preserve Docker transport implementation |
| httpclient5 | 5.6.5 | Replace embedded HTTP client |
| httpcore5 / httpcore5-h2 | 5.4.4 | Replace embedded HTTP core, matching the HttpClient parent POM |

The build removes the original Apache classes, component metadata and public
suffix data, relocates the replacement components to the original package
prefix, and packages them with unchanged docker-java classes. Replacement Maven
metadata, licenses, notices and resources are retained; original transport
license resources are retained under `META-INF/licenses/docker-java/`.
SLF4J, JNA and the docker-java transport API remain external dependencies.
Archive entry order and timestamps are normalized for reproducibility.

`verifyPatchedTransport` verifies every bundled Apache class against the
replacement archive, every original docker-java class byte for byte, and all
three embedded Maven component versions. The root `verifyTestTransportClasspath`
task requires exactly one patched transport on the test runtime and rejects
Docker tooling on the production runtime. Both tests and dependency scanning
depend on these checks.

```bash
./gradlew verifyTestTransportClasspath
./gradlew build verifyIntegrationTests jacocoTestCoverageVerification
./gradlew dependencyCheckAnalyze
```

No publishing plugin is applied here. The replacement is used by project tests
only and is absent from BoosterQuery's published POM and library JAR. Security
scanning continues to inspect both production and test runtime dependencies with
the existing CVSS threshold and without suppressing these findings.

When upstream publishes a transport with patched embedded components, replace
this project dependency with that upstream version, remove this subproject and
its verification hooks, then rerun the same security and database gates.
