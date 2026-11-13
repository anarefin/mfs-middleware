// mw-seeder: a self-contained subproject of the root build (spec 4.2, 4.3, D13, D14). Nothing is
// inherited from the root; every version is written here. The Boot BOM is a Gradle platform and
// carries most versions.
// mw-seeder (ticket 57) is a one-shot HTTP client of the stack's APIs: no web server, database,
// Kafka, Camel or Vault, so it carries none of the template's chassis for them (and no
// conformance suite: it has no outbox, inbox or retry topics).
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
    // Security pass (ticket 46, docs/security-findings.md): Jackson 2 patch release above the BOM.
    implementation(platform("com.fasterxml.jackson:jackson-bom:2.22.3"))

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.networknt:json-schema-validator:3.0.8")
    // The stack's APIs stubbed in-process (the *IT classes): no Docker needed in this module.
    testImplementation("org.wiremock:wiremock-standalone:3.13.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial", "-Werror"))
}

// The contracts the tests read (spec 4.3 rule 2): the files this module's contracts.yml lists (a
// `#<Msg>` suffix names a message type of the file and is dropped), each schema's per-message folder
// (<name>/) and samples folder (samples/<name>/) beside it, contracts/common/ and OWNERS.yml. Only
// these are test inputs, so a contract change re-runs the tests of the modules that use it and no
// others. contracts.yml is read through a provider, so the configuration cache notices its edits.
val contractsDir = layout.projectDirectory.dir(providers.gradleProperty("mwContractsDir").get())
//
// Configuration cache: lambdas stored with a task (the provider below) must capture only locals,
// never a top-level val of this script (that serialises the script object).
val listedContracts = providers.fileContents(layout.projectDirectory.file("contracts.yml")).asText.map { yml ->
    // Read by a test although contracts.yml does not list it (contracts/ContractSchemasTest: the
    // core.command.v1 HoldFunds worked example of the schema loader).
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
    // No Mockito agent: no test of this module mocks (the *IT classes stub HTTP with WireMock).
    inputs.files(contractsDir.files(listedContracts))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("mwContracts")
    systemProperty("mwContractsDir", contractsDir.asFile.absolutePath)
    // One test task runs everything: unit, contract and the `*IT` classes (the whole seeder against
    // in-process WireMock stubs; no Testcontainers, so nothing is skipped without Docker).
    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
}

// Fixed jar name: the Dockerfile copies exactly this file, never a stale jar. No plain jar.
tasks.bootJar { archiveFileName = "mw-seeder.jar" }
tasks.jar { enabled = false }
