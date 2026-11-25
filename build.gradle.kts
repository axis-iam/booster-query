import javax.xml.parsers.DocumentBuilderFactory
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java-library")
    id("maven-publish")
    id("signing")
    id("jacoco")
    // 13.0.0 sends an empty API key when using anonymous NVD updates.
    id("org.owasp.dependencycheck") version "12.2.2"
    id("com.github.spotbugs") version "6.5.6"
    kotlin("jvm") version "2.4.21"
}

group = "com.chaosguide"
version = "1.0.3"

// ===== Lock Java 25 compilation target =====
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    withJavadocJar()
    withSourcesJar()
}

// ===== JaCoCo test coverage report =====
jacoco {
    toolVersion = "0.8.14"
}

tasks.test {
    dependsOn("verifyTestTransportClasspath")
    useJUnitPlatform()
    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> {
        excludes = listOf("net.sf.jsqlparser.*")
    }
    testLogging {
        showStandardStreams = true
    }
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.82".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                minimum = "0.70".toBigDecimal()
            }
        }
        rule {
            element = "PACKAGE"
            includes = listOf("com.chaosguide.boosterquery.rewrite")
            limit {
                counter = "BRANCH"
                minimum = "0.56".toBigDecimal()
            }
        }
    }
}

// CI and release builds require both databases to execute, while local builds may skip Docker.
tasks.register("verifyIntegrationTests") {
    group = "verification"
    description = "Requires successful MySQL and PostgreSQL integration tests without skips."
    dependsOn(tasks.test)
    val reportDirectory = layout.buildDirectory.dir("test-results/test")
    doLast {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val suites = listOf(
            "com.chaosguide.boosterquery.integration.mysql.MysqlIntegrationTest",
            "com.chaosguide.boosterquery.integration.postgres.PgsqlIntegrationTest"
        )
        for (suite in suites) {
            val report = reportDirectory.get().file("TEST-$suite.xml").asFile
            check(report.isFile) { "Missing database integration test report: $suite" }
            val result = factory.newDocumentBuilder().parse(report).documentElement
            check(result.getAttribute("tests").toInt() > 0) { "No database integration tests ran: $suite" }
            for (attribute in listOf("skipped", "failures", "errors")) {
                check(result.getAttribute(attribute).toInt() == 0) {
                    "Database integration tests must have no $attribute: $suite"
                }
            }
        }
    }
}

// ===== OWASP Dependency-Check: CVE scanning =====
dependencyCheck {
    // Fail build on HIGH+ severity CVEs (CVSS >= 7.0)
    failBuildOnCVSS = 7.0f
    failOnError = true
    scanConfigurations = listOf("runtimeClasspath", "testRuntimeClasspath")
    skipTestGroups = false

    // Output formats
    formats = listOf("HTML", "JSON", "SARIF")
    outputDirectory.set(layout.buildDirectory.dir("reports/dependency-check"))

    // Suppress known false positives (if any)
    suppressionFile = file("dependency-check-suppressions.xml").takeIf { it.exists() }?.absolutePath

    // Analyzers configuration
    analyzers.apply {
        assemblyEnabled = false  // .NET assemblies
        nuspecEnabled = false    // NuGet packages
        nodePackage { enabled = false }
        nodeAudit { enabled = false }
    }

    // Use official bulk feeds for anonymous scans; an API key enables API updates.
    val configuredNvdKey = providers.gradleProperty("nvdApiKey").orNull?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv("NVD_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }
    nvd.apply {
        apiKey = configuredNvdKey
        datafeedUrl = providers.gradleProperty("nvdDatafeedUrl").orNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (configuredNvdKey == null) {
                "https://nvd.nist.gov/feeds/json/cve/2.0/nvdcve-2.0-{0}.json.gz"
            } else {
                null
            }
    }
}

tasks.named("dependencyCheckAnalyze") {
    dependsOn("verifyTestTransportClasspath")
}

tasks.register("verifyTestTransportClasspath") {
    group = "verification"
    description = "Requires the patched test transport and excludes it from production dependencies."
    dependsOn(":test-support:docker-transport:verifyPatchedTransport")
    doLast {
        val patchedJarName = "docker-java-transport-zerodep-3.7.1-patched-hc5.6.5.jar"
        val testFiles = configurations.testRuntimeClasspath.get().files
        check(testFiles.count { it.name == patchedJarName } == 1) {
            "The test runtime must contain exactly one patched Docker transport"
        }
        check(testFiles.none {
            it.name.startsWith("docker-java-transport-zerodep-") && it.name != patchedJarName
        }) { "An unpatched Docker transport remains on the test runtime" }
        check(configurations.runtimeClasspath.get().files.none {
            it.name.startsWith("docker-java-") || it.name.startsWith("testcontainers")
        }) { "Docker test tooling must not enter the production runtime" }
    }
}

// ===== SpotBugs: Static code analysis =====
spotbugs {
    ignoreFailures = false
    effort = com.github.spotbugs.snom.Effort.MAX
    reportLevel = com.github.spotbugs.snom.Confidence.MEDIUM
    excludeFilter = file("spotbugs-exclude.xml").takeIf { it.exists() }
}

tasks.named<com.github.spotbugs.snom.SpotBugsTask>("spotbugsMain") {
    reports.create("html") {
        required = true
        outputLocation = file("${layout.buildDirectory.get()}/reports/spotbugs/spotbugs-main.html")
    }
    reports.create("xml") { required = false }
}

tasks.named<com.github.spotbugs.snom.SpotBugsTask>("spotbugsTest") {
    // Test fixtures deliberately contain reflection and conversion edge cases.
    // Keep their findings visible while production findings block the build.
    ignoreFailures = true
    reports.create("html") {
        required = true
        outputLocation = file("${layout.buildDirectory.get()}/reports/spotbugs/spotbugs-test.html")
    }
    reports.create("xml") { required = false }
}

// ===== Javadoc: UTF-8 encoding + lenient mode =====
tasks.withType<Javadoc> {
    options.encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:none", true)
}

// ===== Java compiler: -parameters flag for constructor parameter name reflection =====
tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
    options.compilerArgs.add("-Xlint:unchecked")
}

// ===== Kotlin is used only for data-class mapping tests =====
kotlin {
    compilerOptions {
        javaParameters.set(true)
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

// ===== JAR manifest: JPMS Automatic-Module-Name =====
tasks.withType<Jar> {
    manifest {
        attributes("Automatic-Module-Name" to "com.chaosguide.boosterquery")
    }
}

// ===== Publishing =====
// Disable Gradle Module Metadata so all consumers (Maven + Gradle) use POM only, preventing BOM leakage via .module
tasks.withType<GenerateModuleMetadata> {
    enabled = false
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "booster-query"

            pom {
                name.set("BoosterQuery")
                description.set("Spring Data JPA native SQL enhancement library with smart SQL rewriting, result mapping, and parameter binding")
                url.set("https://github.com/axis-iam/booster-query")

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("chaosguide")
                        name.set("ChaosGuide")
                        url.set("https://github.com/axis-iam")
                    }
                }
                scm {
                    url.set("https://github.com/axis-iam/booster-query")
                    connection.set("scm:git:git://github.com/axis-iam/booster-query.git")
                    developerConnection.set("scm:git:ssh://github.com/axis-iam/booster-query.git")
                }

                // Strip dependencyManagement from POM to prevent forcing consumers to import Spring Boot BOM
                withXml {
                    val root = asNode()
                    root.children().filterIsInstance<groovy.util.Node>()
                        .filter { it.name().toString().contains("dependencyManagement") }
                        .forEach { root.remove(it) }
                }
            }
        }
    }

    repositories {
        maven {
            name = "CentralPortal"
            val releasesRepoUrl = uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")
            val snapshotsRepoUrl = uri("https://central.sonatype.com/repository/maven-snapshots/")
            url = if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl
            credentials {
                username = findProperty("centralPortalUsername") as String?
                    ?: System.getenv("CENTRAL_PORTAL_USERNAME") ?: ""
                password = findProperty("centralPortalPassword") as String?
                    ?: System.getenv("CENTRAL_PORTAL_PASSWORD") ?: ""
            }
        }
    }
}

// ===== GPG signing =====
signing {
    // CI 环境使用环境变量中的 GPG 密钥
    val signingKey = findProperty("signingKey") as String? ?: System.getenv("GPG_SIGNING_KEY")
    val signingPassword = findProperty("signingPassword") as String? ?: System.getenv("GPG_SIGNING_PASSWORD")
    if (signingKey != null && signingPassword != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
    }
    sign(publishing.publications["maven"])
}

// 本地开发时 signing 不是必须的
tasks.withType<Sign>().configureEach {
    onlyIf { project.hasProperty("signingKey") || System.getenv("GPG_SIGNING_KEY") != null }
}

repositories {
    mavenCentral()
}

// ===== Dependencies =====
val springBootVersion = "4.0.10"

configurations.testImplementation {
    exclude(group = "com.github.docker-java", module = "docker-java-transport-zerodep")
}

dependencies {
    // BOM for internal version alignment only, not propagated to consumers
    implementation(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))

    // Public API types → api scope → POM compile scope (explicit version, no BOM propagation)
    api("org.springframework.boot:spring-boot-starter-data-jpa:$springBootVersion")

    // Internal implementation → implementation scope → POM runtime scope (not compile-visible to consumers)
    implementation("org.springframework.boot:spring-boot-autoconfigure:$springBootVersion")
    implementation("com.github.jsqlparser:jsqlparser:5.3")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.3")

    // Compile-visible but not transitive (already provided by spring-boot-starter-data-jpa)
    compileOnly("jakarta.persistence:jakarta.persistence-api")
    compileOnly("org.jspecify:jspecify:1.0.0")

    // Micrometer is optional; metrics use no-op recording when absent.
    compileOnly("io.micrometer:micrometer-core")
    testImplementation("io.micrometer:micrometer-core")

    // Spring Boot Configuration Processor for IDE autocomplete
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:$springBootVersion")

    // Test dependencies
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Force upgrade AssertJ to fix CVE-2026-24400 (affects < 3.27.7)
    testImplementation("org.assertj:assertj-core:3.27.7")

    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.3"))
    testImplementation(project(":test-support:docker-transport", configuration = "patchedTransport"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:mysql")
    testImplementation("org.testcontainers:postgresql")

    testRuntimeOnly("com.mysql:mysql-connector-j")
    testRuntimeOnly("org.postgresql:postgresql")

    // Kotlin stdlib is used only to verify data-class mapping.
    testImplementation(kotlin("stdlib"))
}
