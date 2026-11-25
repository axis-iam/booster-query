import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    base
    id("com.gradleup.shadow") version "9.6.1"
}

// This project produces a local test fixture only; it has no publishing plugin.
val dockerJavaVersion = "3.7.1"
val httpClientVersion = "5.6.5"
val httpCoreVersion = "5.4.4"
val shadedPrefix = "com/github/dockerjava/zerodep/shaded/org/apache/hc/"

repositories {
    mavenCentral()
}

val originalTransport by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
val patchedComponents by configurations.creating {
    isCanBeConsumed = false
    exclude(group = "org.slf4j")
}
val patchedTransport by configurations.creating {
    isCanBeResolved = false
}

dependencies {
    originalTransport("com.github.docker-java:docker-java-transport-zerodep:$dockerJavaVersion")
    patchedComponents("org.apache.httpcomponents.client5:httpclient5:$httpClientVersion")
    patchedComponents("org.apache.httpcomponents.core5:httpcore5:$httpCoreVersion")
    patchedComponents("org.apache.httpcomponents.core5:httpcore5-h2:$httpCoreVersion")

    // Preserve the original transport's external dependencies without exporting its old JAR.
    patchedTransport("com.github.docker-java:docker-java-transport:$dockerJavaVersion")
    patchedTransport("net.java.dev.jna:jna:5.18.1")
    patchedTransport("org.slf4j:slf4j-api:1.7.36")
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// Relocate only the replacement components so docker-java bytecode stays unchanged.
val patchedHttpComponents = tasks.register<ShadowJar>("patchedHttpComponents") {
    archiveBaseName.set("patched-httpcomponents")
    archiveClassifier.set("")
    configurations = listOf(patchedComponents)
    relocate("org.apache.hc", "com.github.dockerjava.zerodep.shaded.org.apache.hc")
    exclude("module-info.class", "META-INF/versions/**/module-info.class")
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    append("META-INF/LICENSE")
    append("META-INF/NOTICE")
    append("META-INF/DEPENDENCIES")
}

val patchedTransportJar = tasks.register<Jar>("patchedTransportJar") {
    dependsOn(patchedHttpComponents)
    archiveBaseName.set("docker-java-transport-zerodep")
    archiveVersion.set("$dockerJavaVersion-patched-hc$httpClientVersion")
    duplicatesStrategy = DuplicatesStrategy.FAIL
    from({ zipTree(originalTransport.singleFile) }) {
        exclude("$shadedPrefix**")
        exclude("META-INF/versions/**/$shadedPrefix**")
        exclude("META-INF/maven/org.apache.httpcomponents.*/**")
        exclude("org/publicsuffix/list/**")
        exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
        exclude("module-info.class", "META-INF/versions/**/module-info.class")
        eachFile {
            if (path in listOf("META-INF/LICENSE", "META-INF/NOTICE", "META-INF/DEPENDENCIES")) {
                path = path.replace("META-INF/", "META-INF/licenses/docker-java/")
            }
        }
    }
    from(patchedHttpComponents.map { zipTree(it.archiveFile) }) {
        exclude("META-INF/MANIFEST.MF")
    }
    manifest {
        attributes(
            "Implementation-Title" to "BoosterQuery test-only patched docker-java transport",
            "Implementation-Version" to "$dockerJavaVersion-patched-hc$httpClientVersion",
            "BoosterQuery-HttpClient-Version" to httpClientVersion,
            "BoosterQuery-HttpCore-Version" to httpCoreVersion
        )
    }
}

val verifyPatchedTransport = tasks.register("verifyPatchedTransport") {
    group = "verification"
    description = "Checks patched component versions and preserves original docker-java bytecode."
    dependsOn(patchedTransportJar)
    inputs.files(originalTransport, patchedComponents, patchedTransportJar, patchedHttpComponents)
    doLast {
        ZipFile(originalTransport.singleFile).use { original ->
            ZipFile(patchedHttpComponents.get().archiveFile.get().asFile).use { components ->
                ZipFile(patchedTransportJar.get().archiveFile.get().asFile).use { patched ->
                    fun bytes(zip: ZipFile, name: String): ByteArray {
                        val entry = checkNotNull(zip.getEntry(name)) { "Missing transport entry: $name" }
                        return zip.getInputStream(entry).use { it.readBytes() }
                    }
                    val apacheClasses = components.entries().asSequence()
                        .filter { it.name.startsWith(shadedPrefix) && it.name.endsWith(".class") }
                        .map { it.name }.toSet()
                    check(apacheClasses.isNotEmpty()) { "No patched Apache classes were bundled" }
                    val actualClasses = patched.entries().asSequence()
                        .filter { it.name.startsWith(shadedPrefix) && it.name.endsWith(".class") }
                        .map { it.name }.toSet()
                    check(actualClasses == apacheClasses) { "Unexpected Apache classes in patched transport" }
                    for (name in apacheClasses) {
                        check(bytes(components, name).contentEquals(bytes(patched, name))) {
                            "Patched Apache bytecode mismatch: $name"
                        }
                    }
                    val dockerClasses = original.entries().asSequence()
                        .filter { it.name.endsWith(".class") && !it.name.startsWith(shadedPrefix) }
                        .map { it.name }.toList()
                    check(dockerClasses.isNotEmpty()) { "No original docker-java classes were found" }
                    for (name in dockerClasses) {
                        check(bytes(original, name).contentEquals(bytes(patched, name))) {
                            "Original docker-java bytecode changed: $name"
                        }
                    }
                    for ((group, artifact, version) in listOf(
                        Triple("org.apache.httpcomponents.client5", "httpclient5", httpClientVersion),
                        Triple("org.apache.httpcomponents.core5", "httpcore5", httpCoreVersion),
                        Triple("org.apache.httpcomponents.core5", "httpcore5-h2", httpCoreVersion)
                    )) {
                        val properties = Properties().apply {
                            bytes(patched, "META-INF/maven/$group/$artifact/pom.properties")
                                .inputStream().use { load(it) }
                        }
                        check(properties.getProperty("version") == version) {
                            "Unexpected embedded version for $artifact: ${properties.getProperty("version")}"
                        }
                    }
                    check(patched.entries().asSequence().none {
                        it.name.startsWith("org/apache/hc/") || it.name.startsWith("org/slf4j/")
                            || it.name.matches(Regex("META-INF/versions/.*/$shadedPrefix.*"))
                    }) { "Unexpected unrelocated, duplicate logging, or multi-release classes" }
                    logger.lifecycle(
                        "Verified {} patched Apache classes and {} unchanged docker-java classes.",
                        apacheClasses.size, dockerClasses.size
                    )
                }
            }
        }
    }
}

artifacts {
    add(patchedTransport.name, patchedTransportJar)
}
tasks.assemble { dependsOn(patchedTransportJar) }
tasks.check { dependsOn(verifyPatchedTransport) }
