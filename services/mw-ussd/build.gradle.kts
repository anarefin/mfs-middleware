// Standalone module build (spec 4.2, 4.3). The Boot, Spring Cloud and Camel BOMs are
// Gradle platforms; every version comes from this module's gradle/libs.versions.toml.
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
}

repositories { mavenCentral() }

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.spring.cloud.bom))
    implementation(platform(libs.camel.bom))
    implementation(platform(libs.resilience4j.bom))
    testImplementation(platform(libs.testcontainers.bom))

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.micrometer.tracing.brave)
    implementation(libs.micrometer.tracing.bridge.brave)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.spring.cloud.starter.vault.config)

    // Kafka through Camel (spec 6.1, 19.2): idempotent producer, retry topics, DLQ.
    implementation(libs.camel.spring.boot.starter)
    implementation(libs.camel.kafka.starter)
    // Resilience4j for client/ calls (19.2); annotations need AspectJ.
    implementation(libs.resilience4j.spring.boot4)
    implementation(libs.spring.boot.starter.aspectj)
    // Own schema, migrated by Flyway on start-up (16.4).
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)
    // Scheduled jobs run on one instance only (ShedLock on this service's schema, 4.4).
    implementation(libs.shedlock.spring)
    implementation(libs.shedlock.provider.jdbc.template)
    // Valkey (Lettuce): USSD session state ussd:{mno}:{sessionId} (spec 9.2).
    implementation(libs.spring.boot.starter.data.redis)
    // SMPP USSD transport (spec 9.2, 9.2.1): deliver_sm in, submit_sm out, ussd_service_op and
    // its_session_info TLVs; jSMPP direct (as mw-notify) for TLV and enquire_link control.
    implementation(libs.jsmpp)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.camel.test.spring.junit6)
    testImplementation(libs.json.schema.validator)
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

tasks.test {
    useJUnitPlatform { excludeTags("conformance") }
}

// Conformance suite (spec 4.3 rule 5, 22.1): tests tagged @Conformance, copied from the template
// into every service. `./gradlew conformance` runs them alone; `check` (make verify) runs both.
val conformance by tasks.registering(Test::class) {
    description = "Runs the conformance suite (@Conformance tests)."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("conformance") }
    shouldRunAfter(tasks.test)
}
tasks.check { dependsOn(conformance) }

// Coverage gate (spec 22.1): >= 80% line coverage per package on service, mapper and client
// (and their sub-packages), measured over the unit and conformance runs. `check` (make
// verify) fails below it. Other packages are reported but not gated.
val basePackage = "mfs.mw.ussd"
val gatedPackages = listOf("service", "mapper", "client")
jacoco { toolVersion = libs.versions.jacoco.get() }
tasks.jacocoTestReport {
    executionData(tasks.test.get(), conformance.get())
    dependsOn(tasks.test, conformance)
    reports { xml.required = true; html.required = true }
}
tasks.jacocoTestCoverageVerification {
    executionData(tasks.test.get(), conformance.get())
    dependsOn(tasks.test, conformance)
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
