// Standalone module build (spec 4.2, 4.3). The Boot BOM is a Gradle platform; every version comes
// from this module's gradle/libs.versions.toml.
// mw-seeder (ticket 57) is a one-shot HTTP client of the stack's APIs: no web server, database,
// Kafka, Camel or Vault, so it carries none of the template's chassis for them (and no
// conformance suite: it has no outbox, inbox or retry topics).
plugins {
    java
    jacoco
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.cyclonedx)
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
    implementation(platform(libs.spring.boot.bom))
    // Security pass (ticket 46, docs/security-findings.md): Jackson 2 patch release above the BOM.
    implementation(platform(libs.jackson2.bom))

    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.jackson)
    implementation(libs.spring.boot.starter.validation)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.json.schema.validator)
    testImplementation(libs.wiremock)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial", "-Werror"))
}

tasks.withType<Test>().configureEach {
    // Schema tests read the root contracts/ folder through mwContractsDir only (spec 4.3 rule 2).
    val contracts = layout.projectDirectory.dir(providers.gradleProperty("mwContractsDir").get())
    inputs.dir(contracts).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("mwContracts")
    systemProperty("mwContractsDir", contracts.asFile.absolutePath)
}

// Test tiers (ticket 53). `test` leaves out the `*IT` classes; `integrationTest` runs them
// (WireMock in-process: no Docker needed here); `check` (make verify) runs both.
val integrationTestClasses = listOf("**/*IT.class", "**/*IT$*.class")
tasks.test {
    useJUnitPlatform()
    exclude(integrationTestClasses)
}

val integrationTest by tasks.registering(Test::class) {
    description = "Runs the integration tests (*IT classes: the whole seeder against WireMock stubs)."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    include(integrationTestClasses)
    shouldRunAfter(tasks.test)
}
tasks.check { dependsOn(integrationTest) }

// Coverage gate (spec 22.1): >= 80% line coverage per package on service, mapper and client
// (and their sub-packages), measured over the test and integrationTest runs.
val basePackage = "mfs.mw.seeder"
val gatedPackages = listOf("service", "mapper", "client")
jacoco { toolVersion = libs.versions.jacoco.get() }
tasks.jacocoTestReport {
    executionData(tasks.test.get(), integrationTest.get())
    dependsOn(tasks.test, integrationTest)
    reports { xml.required = true; html.required = true }
}
tasks.jacocoTestCoverageVerification {
    executionData(tasks.test.get(), integrationTest.get())
    dependsOn(tasks.test, integrationTest)
    violationRules {
        rule {
            element = "PACKAGE"
            includes = gatedPackages.flatMap { listOf("$basePackage.$it", "$basePackage.$it.*") }
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}
tasks.check { dependsOn(tasks.jacocoTestCoverageVerification, tasks.jacocoTestReport) }

// SBOM on every build (spec 3, OWN-05), written to build/reports/cyclonedx-direct/ and
// copied into the image by the Dockerfile.
tasks.named("bootJar") { dependsOn(tasks.named("cyclonedxDirectBom")) }
