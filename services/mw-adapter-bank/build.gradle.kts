// mw-adapter-bank: a self-contained subproject of the root build (spec 4.2, 4.3, D13, D14). Nothing is
// inherited from the root; every version is written here. The Boot, Spring Cloud, Camel, Resilience4j
// and Testcontainers BOMs are Gradle platforms and carry most versions.
plugins {
    java
    // Plugin versions must be the same in every module: Gradle rejects one plugin requested with two
    // versions by subprojects of the same build. Change them in all services/*/build.gradle.kts at once.
    id("org.springframework.boot") version "4.1.2"
}

group = "mfs.mw"
version = "0.1.0-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
    // Tests run on the versions the boot jar ships: a test-only library (json-schema-validator)
    // must not raise a shared dependency (Jackson) above the runtime one and hide a runtime bug.
    consistentResolution { useRuntimeClasspathVersions() }
}

repositories { mavenCentral() }

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.2"))
    // Security pass (ticket 46, docs/security-findings.md): patch releases of transitive libraries
    // with HIGH CVEs, above what the BOMs pin (Jackson 2 from Kafka/Camel, lz4 and snappy from
    // kafka-clients). Drop each once the Boot / Camel BOM ships it.
    implementation(platform("com.fasterxml.jackson:jackson-bom:2.22.3"))
    constraints {
        implementation("at.yawk.lz4:lz4-java:1.11.4")
        implementation("org.xerial.snappy:snappy-java:1.1.10.9")
    }
    implementation(platform("org.springframework.cloud:spring-cloud-dependencies:2025.1.4"))
    implementation(platform("org.apache.camel.springboot:camel-spring-boot-bom:4.22.2"))
    implementation(platform("io.github.resilience4j:resilience4j-bom:2.4.0"))
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-micrometer-tracing-brave")
    implementation("io.micrometer:micrometer-tracing-bridge-brave")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.cloud:spring-cloud-starter-vault-config")

    // Kafka through Camel (spec 6.1, 19.2): idempotent producer, retry topics, DLQ.
    implementation("org.apache.camel.springboot:camel-spring-boot-starter")
    implementation("org.apache.camel.springboot:camel-kafka-starter")
    // Resilience4j for client/ calls (19.2); annotations need AspectJ.
    implementation("io.github.resilience4j:resilience4j-spring-boot4:2.4.0")
    implementation("org.springframework.boot:spring-boot-starter-aspectj")
    // Own schema, migrated by Flyway on start-up (16.4).
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    // Scheduled jobs run on one instance only (ShedLock on this service's schema, 4.4).
    implementation("net.javacrumbs.shedlock:shedlock-spring:7.10.1")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:7.10.1")
    // Adapter framework (spec 12): partner request/response mappings in JSONata.
    implementation("com.dashjoin:jsonata:0.9.10")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.apache.camel:camel-test-spring-junit6")
    testImplementation("com.networknt:json-schema-validator:3.0.8")
    testImplementation("org.wiremock:wiremock-standalone:3.13.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial", "-Werror"))
}

// Mockito runs as a -javaagent of the test JVM. Self-attaching at runtime (the default) fails where
// the JVM attach mechanism is unavailable ("Could not self-attach to current VM"), and JDK 21+
// deprecates dynamic agent loading (JEP 451). The version comes from the Boot BOM.
val mockitoAgent = configurations.create("mockitoAgent")
dependencies {
    mockitoAgent(platform("org.springframework.boot:spring-boot-dependencies:4.1.2"))
    mockitoAgent("org.mockito:mockito-core") { isTransitive = false }
}

// The contracts the tests read (spec 4.3 rule 2): the files this module's contracts.yml lists (a
// `#<Msg>` suffix names a message type of the file and is dropped), each schema's per-message folder
// (<name>/) and samples folder (samples/<name>/) beside it, contracts/common/ and OWNERS.yml. Only
// these are test inputs, so a contract change re-runs the tests of the modules that use it and no
// others. contracts.yml is read through a provider, so the configuration cache notices its edits.
val contractsDir = layout.projectDirectory.dir(providers.gradleProperty("mwContractsDir").get())
//
// Configuration cache: lambdas stored with a task (the provider below, the Mockito agent provider)
// must capture only locals, never a top-level val of this script (that serialises the script object).
val listedContracts = providers.fileContents(layout.projectDirectory.file("contracts.yml")).asText.map { yml ->
    // Read by a test although contracts.yml does not list it (contracts/ContractSchemasTest).
    val extraTestContracts = listOf("events/mw-orchestrator/core.command.v1.schema.json")
    val paths = yml.lines()
        .filterNot { it.trimStart().startsWith("#") }
        .flatMap { line -> Regex("""(?:events|api|common)/[A-Za-z0-9._/-]+""").findAll(line).map { it.value }.toList() }
    (paths + extraTestContracts).distinct().flatMap { path ->
        val dir = path.substringBeforeLast('/')
        val name = path.substringAfterLast('/').removeSuffix(".schema.json")
        listOf(path, "$dir/$name", "$dir/samples/$name")
    } + listOf("common", "OWNERS.yml")
}

tasks.test {
    useJUnitPlatform()
    // Bounded heap: up to four module test JVMs run side by side (root gradle.properties).
    maxHeapSize = "1g"
    val agent: FileCollection = mockitoAgent
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-javaagent:${agent.singleFile}") })
    inputs.files(contractsDir.files(listedContracts))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("mwContracts")
    systemProperty("mwContractsDir", contractsDir.asFile.absolutePath)
    // One test task runs everything: unit, slice, contract, Testcontainers `*IT` and the @Conformance
    // suite, in one JVM sharing one PostgreSQL and one Kafka (TestInfrastructure). Without Docker the
    // `*IT` classes are skipped (DockerAvailableCondition). Classes run one at a time; a method may
    // opt in to running beside its class-mates (@Execution(CONCURRENT): RetryTopicRouteIT).
    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
}

// Fixed jar name: the Dockerfile copies exactly this file, never a stale jar. No plain jar.
tasks.bootJar { archiveFileName = "mw-adapter-bank.jar" }
tasks.jar { enabled = false }
